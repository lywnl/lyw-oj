package com.lyw.judge.config;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Volume;
import com.lyw.common.core.constants.JudgeConstants;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.JudgeTaskState;
import com.lyw.judge.domain.SandBoxExecuteResult;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 由容器 Worker 消费判题任务的执行池。 */
@Slf4j
public class DockerSandBoxPool {
    private final DockerClient dockerClient;
    private final String sandboxImage;
    private final String volumeDir;
    private final Long memoryLimit;
    private final Long memorySwapLimit;
    private final Long cpuLimit;
    private final int corePoolSize;
    private final int maxPoolSize;
    private final int queueCapacity;
    private final long keepAliveTime;
    private final String containerNamePrefix;
    private final BlockingQueue<JudgeTask> taskQueue;
    private final Set<ContainerWorker> workers = ConcurrentHashMap.newKeySet();
    private final Map<String, String> containerNameMap = new ConcurrentHashMap<>();
    private final Set<String> destroyedContainers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger currentSize = new AtomicInteger();
    private final AtomicInteger tempContainerSeq = new AtomicInteger();
    private volatile boolean running;

    public DockerSandBoxPool(DockerClient dockerClient, String sandboxImage, String volumeDir,
                             Long memoryLimit, Long memorySwapLimit, Long cpuLimit,
                             int corePoolSize, int maxPoolSize, int queueCapacity,
                             long keepAliveTime, String containerNamePrefix) {
        if (corePoolSize <= 0 || maxPoolSize < corePoolSize || queueCapacity <= 0) {
            throw new IllegalArgumentException("容器池参数无效");
        }
        this.dockerClient = dockerClient;
        this.sandboxImage = sandboxImage;
        this.volumeDir = volumeDir;
        this.memoryLimit = memoryLimit;
        this.memorySwapLimit = memorySwapLimit;
        this.cpuLimit = cpuLimit;
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.queueCapacity = queueCapacity;
        this.keepAliveTime = keepAliveTime;
        this.containerNamePrefix = containerNamePrefix;
        this.taskQueue = new LinkedBlockingQueue<>(queueCapacity);
    }

    public synchronized void initDockerPool() {
        if (running) return;
        try {
            pullJavaEnvImage();
            for (int i = 0; i < corePoolSize; i++) {
                addWorker(containerNamePrefix + "-" + i, true, null);
            }
        } catch (RuntimeException e) {
            shutdownWorkers();
            throw new IllegalStateException("核心容器初始化失败，判题服务无法就绪", e);
        }
        running = true;
        workers.forEach(ContainerWorker::start);
        log.info("容器池初始化完成，核心容器数: {}，最大容器数: {}，任务队列容量: {}",
                corePoolSize, maxPoolSize, queueCapacity);
    }

    public boolean submit(JudgeTask task) {
        if (!running || !task.transition(JudgeTaskState.CREATED, JudgeTaskState.QUEUED)) return false;
        if (workers.isEmpty()) {
            task.reject(SandBoxExecuteResult.fail(com.lyw.common.core.enums.CodeRunStatus.SYSTEM_BUSY));
            return false;
        }
        if (taskQueue.offer(task)) return true;
        synchronized (this) {
            if (workers.size() < maxPoolSize) {
                String name = containerNamePrefix + "-tmp-" + tempContainerSeq.incrementAndGet();
                try {
                    addWorker(name, false, task).start();
                    return true;
                } catch (RuntimeException e) {
                    log.error("临时容器创建失败: {}", name, e);
                }
            }
        }
        task.reject(SandBoxExecuteResult.fail(
                com.lyw.common.core.enums.CodeRunStatus.SYSTEM_BUSY,
                com.lyw.common.core.enums.CodeRunStatus.SYSTEM_BUSY.getMsg()));
        return false;
    }

    public int getQueueSize() { return taskQueue.size(); }
    public int getWorkerCount() { return workers.size(); }
    public String getCodeDir(String containerId) {
        String name = containerNameMap.get(containerId);
        return System.getProperty("user.dir") + File.separator + JudgeConstants.CODE_DIR_POOL
                + File.separator + name;
    }

    public synchronized void shutdown() {
        if (!running) return;
        running = false;
        shutdownWorkers();
        JudgeTask task;
        while ((task = taskQueue.poll()) != null) {
            task.reject(SandBoxExecuteResult.fail(com.lyw.common.core.enums.CodeRunStatus.SYSTEM_BUSY));
        }
    }

    private void shutdownWorkers() {
        workers.forEach(worker -> {
            worker.stop();
            destroyContainer(worker.containerId, worker.containerName);
            containerNameMap.remove(worker.containerId);
        });
        workers.clear();
        currentSize.set(0);
    }

    private ContainerWorker addWorker(String name, boolean core, JudgeTask firstTask) {
        String id = doCreateContainer(name);
        destroyedContainers.remove(id);
        containerNameMap.put(id, name);
        currentSize.incrementAndGet();
        ContainerWorker worker = new ContainerWorker(id, name, core, firstTask);
        workers.add(worker);
        return worker;
    }

    private final class ContainerWorker implements Runnable {
        private final String containerId;
        private final String containerName;
        private final boolean core;
        private final JudgeTask firstTask;
        private volatile boolean workerRunning = true;
        private volatile boolean failed;
        private Thread thread;

        private ContainerWorker(String id, String name, boolean core, JudgeTask firstTask) {
            this.containerId = id; this.containerName = name; this.core = core; this.firstTask = firstTask;
        }
        private ContainerWorker start() {
            thread = new Thread(this, "sandbox-worker-" + containerName);
            thread.setDaemon(true); thread.start(); return this;
        }
        private void stop() { workerRunning = false; if (thread != null) thread.interrupt(); }

        @Override public void run() {
            JudgeTask task = firstTask;
            try {
                while (running && workerRunning) {
                    if (task == null) {
                        task = core ? taskQueue.take() : taskQueue.poll(keepAliveTime, TimeUnit.MILLISECONDS);
                        if (task == null) break;
                    }
                    executeTask(task); task = null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally { removeWorker(this); }
        }

        private void executeTask(JudgeTask task) {
            if (!task.transition(JudgeTaskState.QUEUED, JudgeTaskState.RUNNING)) return;
            try {
                if (!isContainerRunning()) {
                    throw new IllegalStateException("容器已停止: " + containerId);
                }
                var result = task.execute(containerId);
                task.complete(result, result != null &&
                        com.lyw.common.core.enums.CodeRunStatus.SUCCEED.equals(result.getRunStatus())
                        ? JudgeTaskState.SUCCEEDED : JudgeTaskState.FAILED);
            } catch (Exception e) {
                log.error("容器执行任务失败，containerId={}, taskId={}", containerId, task.getTaskId(), e);
                task.complete(null, JudgeTaskState.FAILED);
                workerRunning = false;
                failed = true;
            }
        }

        private boolean isContainerRunning() {
            var state = dockerClient.inspectContainerCmd(containerId).exec().getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        }
    }

    private void removeWorker(ContainerWorker worker) {
        if (!workers.remove(worker)) return;
        currentSize.decrementAndGet();
        destroyContainer(worker.containerId, worker.containerName);
        containerNameMap.remove(worker.containerId);
        if (worker.core && running) {
            synchronized (this) {
                if (running && workers.size() < corePoolSize) {
                    try {
                        addWorker(worker.containerName, true, null).start();
                    } catch (RuntimeException e) {
                        log.error("核心容器补建失败，判题服务核心容量暂时不足", e);
                    }
                }
            }
        }
    }

    private String doCreateContainer(String name) {
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        if (!CollectionUtil.isEmpty(containers)) {
            String expected = JudgeConstants.JAVA_CONTAINER_PREFIX + name;
            for (Container c : containers) {
                String[] names = c.getNames();
                if (names != null && names.length > 0 && expected.equals(names[0])) {
                    if ("created".equals(c.getState()) || "exited".equals(c.getState()))
                        dockerClient.startContainerCmd(c.getId()).exec();
                    return c.getId();
                }
            }
        }
        CreateContainerResponse response = dockerClient.createContainerCmd(sandboxImage)
                .withName(name).withEnv("JAVA_OPTS=-Djava.util.prefs.userRoot=/tmp/.java/.userPrefs")
                .withHostConfig(getHostConfig(name)).withAttachStderr(true).withAttachStdout(true)
                .withTty(true).exec();
        dockerClient.startContainerCmd(response.getId()).exec();
        return response.getId();
    }

    private void pullJavaEnvImage() {
        for (Image image : dockerClient.listImagesCmd().exec()) {
            String[] tags = image.getRepoTags();
            if (tags != null && tags.length > 0 && sandboxImage.equals(tags[0])) return;
        }
        try {
            dockerClient.pullImageCmd(sandboxImage)
                    .exec(new com.github.dockerjava.core.command.PullImageResultCallback()).awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Java 沙箱镜像拉取被中断", e);
        }
    }

    private HostConfig getHostConfig(String name) {
        HostConfig config = new HostConfig();
        config.setBinds(new Bind(createContainerDir(name), new Volume(volumeDir)));
        config.withMemory(memoryLimit).withMemorySwap(memorySwapLimit).withCpuCount(cpuLimit)
                .withNetworkMode("none").withReadonlyRootfs(true);
        return config;
    }

    private String createContainerDir(String name) {
        String dir = System.getProperty("user.dir") + File.separator + JudgeConstants.CODE_DIR_POOL;
        if (!FileUtil.exist(dir)) FileUtil.mkdir(dir);
        return dir + File.separator + name;
    }

    private void destroyContainer(String id, String name) {
        if (id == null || !destroyedContainers.add(id)) return;
        try { dockerClient.stopContainerCmd(id).exec(); } catch (Exception e) { log.warn("停止容器 {} 失败: {}", name, e.getMessage()); }
        try { dockerClient.removeContainerCmd(id).withForce(true).exec(); } catch (Exception e) { log.warn("移除容器 {} 失败: {}", name, e.getMessage()); }
        if (name != null) FileUtil.del(System.getProperty("user.dir") + File.separator + JudgeConstants.CODE_DIR_POOL + File.separator + name);
    }
}
