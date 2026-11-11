package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.UnauthorizedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.callback.SandboxOutputBudget;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.JudgeTaskState;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.SandboxTaskOutcome;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 保留既有有界队列与独占 Worker，资源名额只在确认回收后释放。 */
@Slf4j
public class DockerSandBoxPool {
    public static final String POOL_LABEL = "com.lyw.oj.sandbox.pool";
    public static final String INSTANCE_LABEL = "com.lyw.oj.sandbox.instance";
    private final Object lifecycle = new Object();
    private final DockerClient controlClient;
    private final SandboxStreamClientFactory streamClients;
    private final String sandboxImage;
    private final String volumeDir;
    private final SandboxLimits limits;
    private final SandboxFileManager files;
    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final String namePrefix;
    private final String instanceId = UUID.randomUUID().toString();
    private final BlockingQueue<JudgeTask> taskQueue;
    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final SandboxDockerExecutor executor;
    private volatile boolean running;
    private volatile boolean closing;
    private volatile Thread initializer;
    private String imageId;
    private int temporarySequence;

    public DockerSandBoxPool(DockerClient controlClient, SandboxStreamClientFactory streamClients,
                             String sandboxImage, String volumeDir, SandboxLimits limits,
                             int corePoolSize, int maxPoolSize, int queueCapacity, long keepAliveTime,
                             String namePrefix, SandboxFileManager files) {
        if (corePoolSize <= 0 || maxPoolSize < corePoolSize || queueCapacity <= 0 || keepAliveTime <= 0
                || namePrefix == null || !namePrefix.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")
                || volumeDir == null || !volumeDir.startsWith("/") || "/".equals(volumeDir)) {
            throw new IllegalArgumentException("容器池参数无效");
        }
        SandboxLimits.checkedNanos(keepAliveTime);
        this.controlClient = Objects.requireNonNull(controlClient);
        this.streamClients = Objects.requireNonNull(streamClients);
        this.sandboxImage = Objects.requireNonNull(sandboxImage);
        this.volumeDir = volumeDir;
        this.limits = Objects.requireNonNull(limits);
        this.files = Objects.requireNonNull(files);
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.namePrefix = namePrefix;
        this.taskQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.executor = new SandboxDockerExecutor(controlClient, limits);
    }

    public void initDockerPool() {
        synchronized (lifecycle) {
            if (running) return;
            if (closing || initializer != null) throw new IllegalStateException("容器池不能重复初始化");
            initializer = Thread.currentThread();
        }
        try {
            rejectExistingContainers();
            resolveImage();
            List<Resource> cores = new ArrayList<>();
            for (int index = 0; index < corePoolSize; index++) {
                Resource resource;
                synchronized (lifecycle) { resource = reserve(namePrefix + "-" + index, true); }
                create(resource);
                cores.add(resource);
            }
            synchronized (lifecycle) {
                if (closing) throw new IllegalStateException("容器池初始化已取消");
                running = true;
            }
            for (Resource core : cores) startWorker(core, null);
            log.info("容器池已就绪，核心数={}，最大数={}，实例={}", corePoolSize, maxPoolSize, instanceId);
        } catch (Exception | LinkageError error) {
            shutdown();
            throw new IllegalStateException("核心容器初始化失败，判题服务无法就绪", error);
        } finally {
            initializer = null;
        }
    }

    public boolean submit(JudgeTask task) {
        if (!task.transition(JudgeTaskState.CREATED, JudgeTaskState.QUEUED)) return false;
        Resource extra = null;
        synchronized (lifecycle) {
            if (running && hasCapacityToExecute()) {
                if (taskQueue.offer(task)) return true;
                if (resources.size() < maxPoolSize) {
                    extra = reserve(namePrefix + "-tmp-" + ++temporarySequence, false);
                }
            }
        }
        if (extra != null) {
            try {
                create(extra);
                startWorker(extra, task);
                return true;
            } catch (Exception | LinkageError error) {
                retire(extra);
                releaseOrReplace(extra, false);
                log.warn("临时容器创建失败，name={}，原因={}", extra.name, error.toString());
                rejectUnserviceableQueue();
            }
        }
        task.reject(busy());
        return false;
    }

    public int getQueueSize() { return taskQueue.size(); }
    public int getWorkerCount() {
        synchronized (lifecycle) {
            return (int) resources.values().stream()
                    .filter(item -> item.state == State.ACTIVE && item.worker != null && item.worker.accepting).count();
        }
    }
    public int getAllocatedContainerCount() { synchronized (lifecycle) { return resources.size(); } }
    public int getQuarantinedContainerCount() {
        synchronized (lifecycle) {
            return (int) resources.values().stream().filter(item -> item.state == State.QUARANTINED).count();
        }
    }
    public SandboxLimits getLimits() { return limits; }
    public String getVolumeDir() { return volumeDir; }
    public SandboxDockerExecutor getExecutor() { return executor; }
    public String getCodeDir(String containerId) { return activeResource(containerId).directory.toString(); }
    public DockerClient getStreamClient(String containerId) { return activeResource(containerId).streams; }
    public void writeSource(String containerId, String source) throws IOException {
        files.writeSource(activeResource(containerId).directory, source);
    }

    private Resource activeResource(String id) {
        synchronized (lifecycle) {
            return resources.values().stream().filter(item -> Objects.equals(id, item.id) && item.state == State.ACTIVE)
                    .findFirst().orElseThrow(() -> new IllegalStateException("容器已不再接受执行: " + id));
        }
    }

    public void shutdown() {
        List<Resource> snapshot;
        List<JudgeTask> queued = new ArrayList<>();
        synchronized (lifecycle) {
            closing = true;
            running = false;
            snapshot = List.copyOf(resources.values());
            taskQueue.drainTo(queued);
            for (Resource resource : snapshot) if (resource.worker != null) resource.worker.stop();
        }
        Thread initializing = initializer;
        if (initializing != null && initializing != Thread.currentThread()) initializing.interrupt();
        queued.forEach(task -> task.reject(busy()));
        for (Resource resource : snapshot) {
            retire(resource);
            releaseOrReplace(resource, false);
        }
        boolean interrupted = Thread.interrupted();
        try {
            for (Resource resource : snapshot) {
                if (resource.worker != null && resource.worker.thread != Thread.currentThread()) {
                    resource.worker.thread.join(limits.cleanupTimeoutMs());
                    if (resource.worker.thread.isAlive()) log.error("Worker 关闭等待超时，name={}", resource.name);
                }
            }
        } catch (InterruptedException error) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private Resource reserve(String name, boolean core) {
        if (closing || resources.size() >= maxPoolSize || resources.containsKey(name)) {
            throw new IllegalStateException("容器名额不可用: " + name);
        }
        Resource resource = new Resource(name, core, files.directory(name));
        resources.put(name, resource);
        return resource;
    }

    private void rejectExistingContainers() {
        List<String> conflicts = new ArrayList<>();
        for (Container container : controlClient.listContainersCmd().withShowAll(true).exec()) {
            if (container.getNames() == null) continue;
            for (String name : container.getNames()) {
                String normalized = name.startsWith("/") ? name.substring(1) : name;
                if (normalized.equals(namePrefix) || normalized.startsWith(namePrefix + "-")) {
                    conflicts.add(container.getId() + ":" + normalized);
                }
            }
        }
        if (!conflicts.isEmpty()) throw new IllegalStateException("存在本池遗留容器，拒绝接管: " + conflicts);
    }

    private void resolveImage() throws Exception {
        try {
            imageId = controlClient.inspectImageCmd(sandboxImage).exec().getId();
        } catch (NotFoundException missing) {
            try (DockerClient client = streamClients.create();
                 PullImageResultCallback callback = new PullImageResultCallback()) {
                if (!client.pullImageCmd(sandboxImage).exec(callback).awaitCompletion(120, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("沙箱镜像拉取超时");
                }
            }
            imageId = controlClient.inspectImageCmd(sandboxImage).exec().getId();
        }
        if (imageId == null || imageId.isBlank()) throw new IllegalStateException("镜像 ID 无效");
    }

    private void create(Resource resource) throws Exception {
        synchronized (resource) {
            try {
                if (closing || resource.state != State.CREATING) throw new IllegalStateException("资源创建已取消");
                files.create(resource.name);
                resource.directoryOwned = true;
                HostConfig config = new HostConfig().withBinds(new Bind(resource.directory.toString(), new Volume(volumeDir)))
                        .withMemory(limits.memoryLimit()).withMemorySwap(limits.memorySwapLimit())
                        .withNanoCPUs(limits.nanoCpus()).withPidsLimit(limits.pidsLimit())
                        .withNetworkMode("none").withReadonlyRootfs(true);
                var command = controlClient.createContainerCmd(imageId).withName(resource.name)
                        .withLabels(Map.of(POOL_LABEL, namePrefix, INSTANCE_LABEL, instanceId))
                        .withEnv("JAVA_OPTS=-Djava.util.prefs.userRoot=/tmp/.java/.userPrefs")
                        .withHostConfig(config).withAttachStderr(true).withAttachStdout(true).withTty(true);
                resource.createAttempted = true;
                String createdId;
                try {
                    createdId = command.exec().getId();
                } catch (BadRequestException | ConflictException | NotFoundException | UnauthorizedException rejected) {
                    // 服务端明确拒绝创建，与请求已发出但响应丢失不同。
                    resource.createAttempted = false;
                    throw rejected;
                }
                if (createdId == null || createdId.isBlank()) throw new IllegalStateException("创建未返回容器 ID");
                resource.id = createdId;
                controlClient.startContainerCmd(resource.id).exec();
                validateContainer(resource, controlClient.inspectContainerCmd(resource.id).exec());
                resource.streams = streamClients.create();
                verifyMount(resource);
                if (closing) throw new IllegalStateException("资源创建期间服务关闭");
                resource.state = State.ACTIVE;
            } catch (Exception | LinkageError error) {
                retire(resource);
                releaseOrReplace(resource, false);
                throw error;
            }
        }
    }

    private void validateContainer(Resource resource, InspectContainerResponse actual) {
        HostConfig config = actual.getHostConfig();
        boolean mountMatches = actual.getMounts() != null && actual.getMounts().stream().anyMatch(mount ->
                mount.getDestination() != null && volumeDir.equals(mount.getDestination().getPath())
                        && Boolean.TRUE.equals(mount.getRW()) && Objects.equals(resource.directory.toString(),
                        mount.getSource() != null && mount.getSource().startsWith("/host_mnt/")
                                ? mount.getSource().substring("/host_mnt".length()) : mount.getSource()));
        if (!owned(actual) || !isRunning(actual) || config == null || !mountMatches
                || !Objects.equals(actual.getImageId(), imageId)
                || !Objects.equals(config.getNanoCPUs(), limits.nanoCpus())
                || !Objects.equals(config.getPidsLimit(), limits.pidsLimit())
                || !Objects.equals(config.getMemory(), limits.memoryLimit())
                || !Objects.equals(config.getMemorySwap(), limits.memorySwapLimit())
                || !"none".equals(config.getNetworkMode()) || !Boolean.TRUE.equals(config.getReadonlyRootfs())) {
            throw new IllegalStateException("容器实际资源限制、归属或挂载不符: " + resource.name);
        }
    }

    private void verifyMount(Resource resource) throws Exception {
        String marker = UUID.randomUUID().toString();
        Path probe = resource.directory.resolve(".sandbox-mount-check");
        Files.writeString(probe, marker, StandardCharsets.UTF_8);
        SandboxDockerExecutor.Result result = executor.run(resource.streams, resource.id,
                new String[]{"cat", volumeDir + "/.sandbox-mount-check"}, limits.cleanupTimeoutMs(),
                new SandboxOutputBudget(1024L), false);
        if (result.timedOut() || result.outputExceeded() || result.exitCode() != 0 || !marker.equals(result.stdout())) {
            throw new IllegalStateException("容器无法读取本实例绑定目录: " + resource.name);
        }
        Files.delete(probe);
    }

    private void startWorker(Resource resource, JudgeTask firstTask) {
        synchronized (lifecycle) {
            if (!running || closing || resource.state != State.ACTIVE) throw new IllegalStateException("Worker 启动已取消");
            resource.worker = new ContainerWorker(resource, firstTask);
            resource.worker.thread.start();
        }
    }

    private boolean owned(InspectContainerResponse container) {
        return container.getConfig() != null && container.getConfig().getLabels() != null
                && namePrefix.equals(container.getConfig().getLabels().get(POOL_LABEL))
                && instanceId.equals(container.getConfig().getLabels().get(INSTANCE_LABEL));
    }

    private static boolean isRunning(InspectContainerResponse container) {
        var state = container.getState();
        return state != null && Boolean.TRUE.equals(state.getRunning()) && !Boolean.TRUE.equals(state.getPaused())
                && !Boolean.TRUE.equals(state.getRestarting()) && !Boolean.TRUE.equals(state.getDead());
    }

    private boolean retire(Resource resource) {
        synchronized (resource) {
            if (resource.retirementDone) return resource.state == State.DESTROYED;
            resource.state = State.RETIRING;
            if (resource.worker != null) resource.worker.accepting = false;
            boolean interrupted = Thread.interrupted();
            Throwable closeFailure = null;
            try {
                if (resource.streams != null) {
                    try { resource.streams.close(); } catch (Exception | LinkageError error) { closeFailure = error; }
                }
                if (resource.id == null && resource.createAttempted) {
                    // 查询暂时 404 不能排除创建请求稍后成功，异常时保留名称与名额。
                    InspectContainerResponse discovered = controlClient.inspectContainerCmd(resource.name).exec();
                    if (!owned(discovered)) throw new IllegalStateException("创建结果无法确认归属");
                    resource.id = discovered.getId();
                    if (resource.id == null) throw new IllegalStateException("创建结果缺少 ID");
                }
                if (resource.id != null) {
                    try {
                        InspectContainerResponse actual = controlClient.inspectContainerCmd(resource.id).exec();
                        if (!owned(actual)) throw new IllegalStateException("拒绝删除非本实例容器");
                        controlClient.removeContainerCmd(resource.id).withForce(true).exec();
                    } catch (NotFoundException alreadyRemoved) {
                        // 已知 ID 已不存在，可继续做独立确认和目录清理。
                    }
                    try {
                        controlClient.inspectContainerCmd(resource.id).exec();
                        throw new IllegalStateException("移除后仍可查询到旧容器");
                    } catch (NotFoundException confirmed) {
                        // 只有明确不存在才认为容器已经终止和移除。
                    }
                }
                if (resource.directoryOwned) files.clean(resource.directory, true);
                if (closeFailure != null) throw new IllegalStateException("流客户端关闭失败", closeFailure);
                resource.state = State.DESTROYED;
                return true;
            } catch (Exception | LinkageError error) {
                resource.state = State.QUARANTINED;
                log.error("沙箱回收失败，保留隔离名额，name={}，id={}，directory={}，原因={}",
                        resource.name, resource.id, resource.directory, error.toString());
                return false;
            } finally {
                resource.retirementDone = true;
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private Resource releaseOrReplace(Resource resource, boolean recoverCore) {
        synchronized (lifecycle) {
            if (resource.state != State.DESTROYED || resources.get(resource.name) != resource) return null;
            resources.remove(resource.name);
            if (recoverCore && resource.core && running && !closing) return reserve(resource.name, true);
            return null;
        }
    }

    private boolean hasCapacityToExecute() {
        return resources.values().stream().anyMatch(resource -> resource.state == State.CREATING || resource.state == State.ACTIVE
                // 核心资源的回收决定尚未完成，不能把其他 Worker 的失败当成全池恢复失败。
                || (resource.core && (resource.state == State.RETIRING || resource.state == State.DESTROYED)));
    }

    private void rejectUnserviceableQueue() {
        List<JudgeTask> rejected = new ArrayList<>();
        synchronized (lifecycle) {
            if (!hasCapacityToExecute()) taskQueue.drainTo(rejected);
        }
        rejected.forEach(task -> task.reject(busy()));
    }

    private void rebuild(Resource replacement) {
        if (replacement == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            create(replacement);
            startWorker(replacement, null);
        } catch (Exception | LinkageError error) {
            retire(replacement);
            releaseOrReplace(replacement, false);
            log.error("核心容器单次补建失败，容量降低，name={}，原因={}", replacement.name, error.toString());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static SandBoxExecuteResult busy() { return SandBoxExecuteResult.fail(CodeRunStatus.SYSTEM_BUSY); }

    private enum State { CREATING, ACTIVE, RETIRING, QUARANTINED, DESTROYED }

    private final class Resource {
        private final String name;
        private final boolean core;
        private final Path directory;
        private volatile String id;
        private volatile State state = State.CREATING;
        private boolean createAttempted;
        private boolean directoryOwned;
        private boolean retirementDone;
        private DockerClient streams;
        private volatile ContainerWorker worker;

        private Resource(String name, boolean core, Path directory) {
            this.name = name; this.core = core; this.directory = directory;
        }
    }

    private final class ContainerWorker implements Runnable {
        private final Resource resource;
        private final JudgeTask firstTask;
        private final Thread thread;
        private volatile boolean accepting = true;
        private Resource replacement;

        private ContainerWorker(Resource resource, JudgeTask firstTask) {
            this.resource = resource;
            this.firstTask = firstTask;
            this.thread = new Thread(this, "sandbox-worker-" + resource.name);
            this.thread.setDaemon(true);
        }

        private void stop() { accepting = false; thread.interrupt(); }

        @Override
        public void run() {
            JudgeTask task = firstTask;
            try {
                while (running && accepting) {
                    if (task == null) {
                        task = resource.core ? taskQueue.take() : taskQueue.poll(keepAliveTime, TimeUnit.MILLISECONDS);
                        if (task == null) break;
                    }
                    if (!running || !accepting) break;
                    executeTask(task);
                    task = null;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                accepting = false;
                retire(resource);
                Resource reserved = releaseOrReplace(resource, true);
                if (replacement == null) replacement = reserved;
                if (task != null) {
                    if (task.getState() == JudgeTaskState.QUEUED) task.reject(busy());
                    else task.complete(SandBoxExecuteResult.fail(CodeRunStatus.UNKNOWN_FAILED), JudgeTaskState.FAILED);
                }
                rebuild(replacement);
                rejectUnserviceableQueue();
            }
        }

        private void executeTask(JudgeTask task) {
            if (!task.transition(JudgeTaskState.QUEUED, JudgeTaskState.RUNNING)) return;
            SandboxTaskOutcome outcome;
            try {
                if (!isRunning(controlClient.inspectContainerCmd(resource.id).exec())) {
                    throw new IllegalStateException("容器不再运行");
                }
                outcome = task.execute(resource.id);
                if (outcome == null) outcome = SandboxTaskOutcome.systemFailure("缺少内部执行结果");
                if (!running || !accepting || Thread.currentThread().isInterrupted()) {
                    outcome = SandboxTaskOutcome.systemFailure("Worker 已停止或被中断");
                }
                if (!outcome.discardContainer()) {
                    if (!isRunning(controlClient.inspectContainerCmd(resource.id).exec())) {
                        throw new IllegalStateException("执行后容器不再运行");
                    }
                    files.clean(resource.directory, false);
                }
            } catch (Exception | LinkageError error) {
                log.warn("沙箱任务发生系统异常，taskId={}，id={}，原因={}", task.getTaskId(), resource.id, error.toString());
                outcome = SandboxTaskOutcome.systemFailure(error.getClass().getSimpleName());
            }
            if (outcome.discardContainer()) {
                accepting = false;
                boolean removed = retire(resource);
                replacement = releaseOrReplace(resource, true);
                if (!removed) outcome = SandboxTaskOutcome.systemFailure("容器或目录回收失败");
            }
            task.complete(outcome.result(), outcome.result().getRunStatus() == CodeRunStatus.SUCCEED
                    ? JudgeTaskState.SUCCEEDED : JudgeTaskState.FAILED);
        }
    }
}
