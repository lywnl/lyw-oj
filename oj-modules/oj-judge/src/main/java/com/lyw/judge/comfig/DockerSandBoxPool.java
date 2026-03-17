package com.lyw.judge.comfig;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.lyw.common.core.constants.JudgeConstants;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Docker 沙箱容器池（动态伸缩）
 * <p>
 * 设计理念类似 ThreadPoolExecutor：
 * - corePoolSize：核心容器数，始终保持存活
 * - maxPoolSize：最大容器数，高峰期动态扩容上限
 * - keepAliveTime：临时容器空闲超过该时间后自动销毁
 * - pollTimeout：获取容器的等待超时时间，超时后尝试扩容，扩容失败返回 null
 */
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
    private final long keepAliveTime;
    private final long pollTimeout;
    private final String containerNamePrefix;

    /** 可用容器队列 */
    private final BlockingQueue<String> containerQueue;
    /** containerId -> containerName */
    private final ConcurrentHashMap<String, String> containerNameMap = new ConcurrentHashMap<>();
    /** 核心容器 ID 集合（不会被销毁） */
    private final Set<String> coreContainerIds = ConcurrentHashMap.newKeySet();
    /** 容器归还时间（用于判断空闲时长） */
    private final ConcurrentHashMap<String, Long> lastReturnTime = new ConcurrentHashMap<>();
    /** 当前容器总数（核心 + 临时） */
    private final AtomicInteger currentSize = new AtomicInteger(0);
    /** 临时容器命名序号 */
    private final AtomicInteger tempContainerSeq = new AtomicInteger(0);
    /** 定时清理调度器 */
    private ScheduledExecutorService cleanupScheduler;

    public DockerSandBoxPool(DockerClient dockerClient, String sandboxImage,
                             String volumeDir, Long memoryLimit,
                             Long memorySwapLimit, Long cpuLimit,
                             int corePoolSize, int maxPoolSize,
                             long keepAliveTime, long pollTimeout,
                             String containerNamePrefix) {
        this.dockerClient = dockerClient;
        this.sandboxImage = sandboxImage;
        this.volumeDir = volumeDir;
        this.memoryLimit = memoryLimit;
        this.memorySwapLimit = memorySwapLimit;
        this.cpuLimit = cpuLimit;
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.pollTimeout = pollTimeout;
        this.containerNamePrefix = containerNamePrefix;
        this.containerQueue = new LinkedBlockingQueue<>(maxPoolSize);
    }

    /**
     * 初始化容器池：预热核心容器 + 启动定时清理任务
     */
    public void initDockerPool() {
        log.info("------  容器池初始化开始 (核心: {}, 最大: {})  -----", corePoolSize, maxPoolSize);
        pullJavaEnvImage();
        for (int i = 0; i < corePoolSize; i++) {
            String containerName = containerNamePrefix + "-" + i;
            String containerId = doCreateContainer(containerName);
            coreContainerIds.add(containerId);
            containerQueue.add(containerId);
            containerNameMap.put(containerId, containerName);
        }
        currentSize.set(corePoolSize);
        startCleanupTask();
        log.info("------  容器池初始化完成，当前容器数: {}  -----", currentSize.get());
    }

    /**
     * 获取可用容器（类似线程池获取线程）
     * 1. 先从队列 poll(timeout) 等待获取
     * 2. 超时后尝试动态扩容创建临时容器
     * 3. 已达最大容量则返回 null（系统繁忙）
     */
    public String getContainer() {
        try {
            String containerId = containerQueue.poll(pollTimeout, TimeUnit.MILLISECONDS);
            if (containerId != null) {
                lastReturnTime.remove(containerId);
                return containerId;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        // 队列超时无可用容器，尝试扩容
        return tryCreateTempContainer();
    }

    /**
     * 归还容器到池中
     */
    public void returnContainer(String containerId) {
        lastReturnTime.put(containerId, System.currentTimeMillis());
        containerQueue.add(containerId);
    }

    /**
     * 获取容器对应的用户代码目录
     */
    public String getCodeDir(String containerId) {
        String containerName = containerNameMap.get(containerId);
        log.info("containerName：{}", containerName);
        return System.getProperty("user.dir") + File.separator
                + JudgeConstants.CODE_DIR_POOL + File.separator + containerName;
    }

    /**
     * 关闭容器池：停止清理调度器
     */
    public void shutdown() {
        if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
            cleanupScheduler.shutdown();
            log.info("容器池清理调度器已关闭");
        }
    }

    // ==================== 扩容逻辑 ====================

    /**
     * 尝试创建临时容器（CAS 保证线程安全，不会超过 maxPoolSize）
     */
    private String tryCreateTempContainer() {
        int current = currentSize.get();
        while (current < maxPoolSize) {
            if (currentSize.compareAndSet(current, current + 1)) {
                String containerName = containerNamePrefix + "-tmp-" + tempContainerSeq.incrementAndGet();
                log.info("高峰扩容，创建临时容器: {}，当前容器数: {}", containerName, currentSize.get());
                String containerId = doCreateContainer(containerName);
                containerNameMap.put(containerId, containerName);
                // 直接返回给调用方，不放入队列
                return containerId;
            }
            current = currentSize.get();
        }
        log.warn("容器池已达最大容量 {}，无法扩容", maxPoolSize);
        return null;
    }

    // ==================== 缩容逻辑 ====================

    /**
     * 启动定时清理任务：周期性销毁空闲的临时容器
     */
    private void startCleanupTask() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sandbox-pool-cleanup");
            t.setDaemon(true);
            return t;
        });
        long checkInterval = Math.max(keepAliveTime / 2, 10000);
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleContainers,
                keepAliveTime, checkInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * 清理空闲超时的临时容器（只清理临时容器，核心容器永不销毁）
     */
    private void cleanupIdleContainers() {
        if (currentSize.get() <= corePoolSize) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = lastReturnTime.entrySet().iterator();
        while (it.hasNext() && currentSize.get() > corePoolSize) {
            Map.Entry<String, Long> entry = it.next();
            String containerId = entry.getKey();
            // 核心容器不清理
            if (coreContainerIds.contains(containerId)) {
                continue;
            }
            // 临时容器空闲超过 keepAliveTime 则销毁
            if (now - entry.getValue() > keepAliveTime) {
                if (containerQueue.remove(containerId)) {
                    String containerName = containerNameMap.remove(containerId);
                    destroyContainer(containerId, containerName);
                    currentSize.decrementAndGet();
                    it.remove();
                    log.info("低峰缩容，销毁临时容器: {}，当前容器数: {}", containerName, currentSize.get());
                }
            }
        }
    }

    /**
     * 销毁容器：停止 -> 删除 -> 清理挂载目录
     */
    private void destroyContainer(String containerId, String containerName) {
        try {
            dockerClient.stopContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.warn("停止容器 {} 失败: {}", containerName, e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.warn("移除容器 {} 失败: {}", containerName, e.getMessage());
        }
        if (containerName != null) {
            String dir = System.getProperty("user.dir") + File.separator
                    + JudgeConstants.CODE_DIR_POOL + File.separator + containerName;
            FileUtil.del(dir);
        }
    }

    // ==================== 容器创建 ====================

    /**
     * 创建或复用已有容器（支持服务重启后复用存量容器）
     */
    private String doCreateContainer(String containerName) {
        // 检查是否已存在同名容器，存在则复用
        List<Container> containerList = dockerClient.listContainersCmd().withShowAll(true).exec();
        if (!CollectionUtil.isEmpty(containerList)) {
            String names = JudgeConstants.JAVA_CONTAINER_PREFIX + containerName;
            for (Container container : containerList) {
                String[] containerNames = container.getNames();
                if (containerNames != null && containerNames.length > 0 && names.equals(containerNames[0])) {
                    if ("created".equals(container.getState()) || "exited".equals(container.getState())) {
                        dockerClient.startContainerCmd(container.getId()).exec();
                    }
                    return container.getId();
                }
            }
        }
        // 不存在则创建新容器
        HostConfig hostConfig = getHostConfig(containerName);
        CreateContainerResponse response = dockerClient
                .createContainerCmd(sandboxImage)
                .withName(containerName)
                .withEnv("JAVA_OPTS=-Djava.util.prefs.userRoot=/tmp/.java/.userPrefs")
                .withHostConfig(hostConfig)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();
        String containerId = response.getId();
        dockerClient.startContainerCmd(containerId).exec();
        return containerId;
    }

    private void pullJavaEnvImage() {
        List<Image> imageList = dockerClient.listImagesCmd().exec();
        for (Image image : imageList) {
            String[] repoTags = image.getRepoTags();
            if (repoTags != null && repoTags.length > 0 && sandboxImage.equals(repoTags[0])) {
                return;
            }
        }
        try {
            dockerClient.pullImageCmd(sandboxImage).exec(new PullImageResultCallback()).awaitCompletion();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private HostConfig getHostConfig(String containerName) {
        HostConfig hostConfig = new HostConfig();
        String userCodeDir = createContainerDir(containerName);
        hostConfig.setBinds(new Bind(userCodeDir, new Volume(volumeDir)));
        hostConfig.withMemory(memoryLimit);
        hostConfig.withMemorySwap(memorySwapLimit);
        hostConfig.withCpuCount(cpuLimit);
        hostConfig.withNetworkMode("none");
        hostConfig.withReadonlyRootfs(true);
        return hostConfig;
    }

    private String createContainerDir(String containerName) {
        String codeDir = System.getProperty("user.dir") + File.separator + JudgeConstants.CODE_DIR_POOL;
        if (!FileUtil.exist(codeDir)) {
            FileUtil.mkdir(codeDir);
        }
        return codeDir + File.separator + containerName;
    }
}
