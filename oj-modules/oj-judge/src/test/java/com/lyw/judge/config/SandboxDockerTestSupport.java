package com.lyw.judge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.service.impl.SandboxPoolServiceImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Docker 测试只使用本轮唯一前缀、目录和明确记录的容器。 */
final class SandboxDockerTestSupport implements AutoCloseable {
    static final String ADDITION = """
            public class Solution {
                public static void main(String[] args) {
                    long a = Long.parseLong(args[0]);
                    long b = Long.parseLong(args[1]);
                    System.out.println(a + b);
                }
            }
            """;
    static final List<String> INPUTS = List.of("1 2", "0 0", "-7 12", "-10 -20",
            "2147483647 2147483647", "-2147483648 -2147483648");
    static final List<String> EXPECTED = List.of("3", "0", "5", "-30", "4294967294", "-4294967296");
    private static final Object EVENT_LOCK = new Object();
    private static final ObjectMapper JSON = new ObjectMapper();
    final String prefix = "oj-safety-" + UUID.randomUUID().toString().substring(0, 12);
    final Path root;
    final Path codeRoot;
    final DockerClient control;
    final DockerSandBoxPool pool;
    final SandboxPoolServiceImpl service;
    boolean expectQuarantine;

    SandboxDockerTestSupport(SandboxLimits limits, int core, int maximum, int queue, long keepAlive) throws Exception {
        this(limits, core, maximum, queue, keepAlive, UnaryOperator.identity());
    }

    SandboxDockerTestSupport(SandboxLimits limits, int core, int maximum, int queue, long keepAlive,
                             UnaryOperator<DockerClient> decorateControl) throws Exception {
        String host = System.getProperty("docker.host");
        String directory = System.getProperty("sandbox.test.root");
        if (host == null || directory == null) throw new IllegalStateException("真实测试必须指定 docker.host 与 sandbox.test.root");
        Path base = Path.of(directory).toAbsolutePath();
        Files.createDirectories(base);
        root = Files.createTempDirectory(base, prefix + "-");
        codeRoot = root.resolve("code");
        String version = System.getProperty("docker.api.version", "1.41");
        control = DockerSandBoxPoolConfig.newControlClient(host, version, limits.cleanupTimeoutMs());
        pool = new DockerSandBoxPool(decorateControl.apply(control), () -> DockerSandBoxPoolConfig.newStreamClient(host, version),
                "azul/zulu-openjdk:17", "/usr/share/java", limits, core, maximum, queue, keepAlive, prefix,
                new SandboxFileManager(codeRoot, limits.cleanupTimeoutMs()));
        service = new SandboxPoolServiceImpl(pool);
        event("fixture", Map.of("prefix", prefix, "root", root.toString(), "limits", limits.toString()));
    }

    List<String> ids() {
        return ownContainers().stream().map(Container::getId).toList();
    }

    private List<Container> ownContainers() {
        return control.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getLabels() != null
                        && prefix.equals(container.getLabels().get(DockerSandBoxPool.POOL_LABEL))).toList();
    }

    Map<String, Object> inspect(String id) {
        var container = control.inspectContainerCmd(id).exec();
        var config = container.getHostConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("imageId", container.getImageId());
        result.put("running", container.getState().getRunning());
        result.put("nanoCpus", config.getNanoCPUs());
        result.put("pidsLimit", config.getPidsLimit());
        result.put("memory", config.getMemory());
        result.put("memorySwap", config.getMemorySwap());
        result.put("network", config.getNetworkMode());
        result.put("readonlyRoot", config.getReadonlyRootfs());
        return result;
    }

    SandBoxExecuteResult execute(String name, String code, List<String> inputs, long questionMs) throws Exception {
        List<String> before = ids();
        long started = System.nanoTime();
        SandBoxExecuteResult result = service.exeJavaCode(1L, code, inputs, questionMs);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scenario", name);
        data.put("before", before);
        data.put("afterResult", ids());
        data.put("elapsedNanos", System.nanoTime() - started);
        data.put("status", result.getRunStatus().name());
        data.put("message", result.getExeMessage());
        data.put("workerCount", pool.getWorkerCount());
        data.put("allocated", pool.getAllocatedContainerCount());
        data.put("quarantined", pool.getQuarantinedContainerCount());
        event("submission", data);
        return result;
    }

    void assertAbsent(String id) {
        assertThrows(NotFoundException.class, () -> control.inspectContainerCmd(id).exec());
    }

    void awaitWorkers(int count) throws Exception {
        await(() -> pool.getWorkerCount() == count, 15000);
    }

    void assertEmptyDirectories() throws Exception {
        for (String id : ids()) {
            Path directory = Path.of(pool.getCodeDir(id));
            try (var entries = Files.list(directory)) { assertEquals(0, entries.count(), directory.toString()); }
        }
    }

    void assertAdditionWorks() throws Exception {
        awaitWorkers(1);
        SandBoxExecuteResult result = execute("后续正常 A+B", ADDITION, INPUTS, 1000);
        assertEquals(com.lyw.common.core.enums.CodeRunStatus.SUCCEED, result.getRunStatus(), result.getExeMessage());
        assertEquals(EXPECTED, result.getOutputList());
        assertEmptyDirectories();
    }

    static void await(BooleanSupplier condition, long milliseconds) throws InterruptedException {
        long start = System.nanoTime();
        while (!condition.getAsBoolean() && System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(milliseconds)) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "状态未在保护时间内收敛");
    }

    void event(String type, Map<String, ?> details) throws Exception {
        String destination = System.getProperty("sandbox.test.evidence");
        if (destination == null) throw new IllegalStateException("缺少测试证据目录");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", Instant.now().toString());
        record.put("nanoTime", System.nanoTime());
        record.put("fixture", prefix);
        record.put("type", type);
        record.putAll(details);
        synchronized (EVENT_LOCK) {
            Files.writeString(Path.of(destination, "docker-events.jsonl"), JSON.writeValueAsString(record) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    @Override
    public void close() throws Exception {
        Throwable failure = null;
        try {
            pool.shutdown();
            List<String> leftovers = ids();
            event("pool-shutdown", Map.of("leftovers", leftovers, "allocated", pool.getAllocatedContainerCount(),
                    "quarantined", pool.getQuarantinedContainerCount(), "expectedQuarantine", expectQuarantine));
            if (!expectQuarantine) {
                assertTrue(leftovers.isEmpty(), "容器池没有完成本轮容器回收");
                assertEquals(0, pool.getAllocatedContainerCount());
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            // 故障注入可以有意阻止业务回收；测试收尾使用独立控制客户端清理自己的资源。
            List<String> removed = new ArrayList<>();
            for (Container container : ownContainers()) {
                control.removeContainerCmd(container.getId()).withForce(true).exec();
                assertAbsent(container.getId());
                removed.add(container.getId());
            }
            event("fixture-cleanup", Map.of("manuallyRemoved", removed, "remaining", ids()));
            control.close();
        }
        if (failure instanceof Exception error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
