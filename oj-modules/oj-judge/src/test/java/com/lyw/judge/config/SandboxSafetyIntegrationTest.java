package com.lyw.judge.config;

import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.domain.SandBoxExecuteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Timeout(120)
class SandboxSafetyIntegrationTest {
    private static final String LOOP = "public class Solution { public static void main(String[] args) { while (true) {} } }";

    @Test
    void kernelLimitsChineseOutputAndAllTaskFilesAreVerified() throws Exception {
        try (var fixture = fixture(SandboxLimits.defaults())) {
            fixture.pool.initDockerPool();
            String id = fixture.ids().get(0);
            Map<String, Object> actual = fixture.inspect(id);
            assertEquals(1_000_000_000L, actual.get("nanoCpus"));
            assertEquals(64L, actual.get("pidsLimit"));
            assertEquals(100000000L, actual.get("memory"));
            assertEquals(100000000L, actual.get("memorySwap"));
            assertEquals("none", actual.get("network"));
            assertEquals(true, actual.get("readonlyRoot"));
            fixture.event("inspect-limits", actual);
            String cgroups = """
                    public class Solution {
                        public static void main(String[] args) throws Exception {
                            for (String file : new String[]{"cpu.max", "pids.max", "memory.max", "memory.swap.max"}) {
                                System.out.println(java.nio.file.Files.readString(java.nio.file.Path.of("/sys/fs/cgroup", file)).trim());
                            }
                            Process pageSize = new ProcessBuilder("getconf", "PAGESIZE").start();
                            System.out.println(new String(pageSize.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim());
                            if (pageSize.waitFor() != 0) throw new IllegalStateException("读取内核页大小失败");
                        }
                    }
                    """;
            SandBoxExecuteResult kernel = fixture.execute("内核 cgroup 限制", cgroups, List.of(""), 1000);
            assertEquals(CodeRunStatus.SUCCEED, kernel.getRunStatus(), kernel.getExeMessage());
            String[] values = kernel.getOutputList().get(0).split("\\R");
            fixture.event("kernel-limits", Map.of("values", kernel.getOutputList()));
            assertEquals(5, values.length);
            String[] cpu = values[0].split(" ");
            assertEquals(Long.parseLong(cpu[1]), Long.parseLong(cpu[0]));
            assertEquals("64", values[1]);
            // cgroup 的内存字节限额按实际内核页大小向下对齐。
            long pageSize = Long.parseLong(values[4]);
            assertTrue(pageSize > 0);
            assertEquals(100000000L / pageSize * pageSize, Long.parseLong(values[2]));
            assertEquals("0", values[3]);

            Path sentinel = Files.writeString(fixture.root.resolve("outside-sentinel"), "保留外部文件");
            String filesAndUnicode = """
                    public class Solution {
                        static class Nested {}
                        public static void main(String[] args) throws Exception {
                            java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("java.class.path"));
                            java.nio.file.Files.createDirectories(root.resolve("generated/sub"));
                            java.nio.file.Files.writeString(root.resolve("generated/sub/extra.data"), "临时内容");
                            java.nio.file.Files.createSymbolicLink(root.resolve("outside-link"), java.nio.file.Path.of(args[0]));
                            System.out.println("中文测试🙂");
                        }
                    }
                    """;
            SandBoxExecuteResult result = fixture.execute("中文和完整文件清理", filesAndUnicode, List.of(sentinel.toString()), 1000);
            assertEquals(CodeRunStatus.SUCCEED, result.getRunStatus(), result.getExeMessage());
            assertEquals(List.of("中文测试🙂"), result.getOutputList());
            assertEquals("保留外部文件", Files.readString(sentinel));
            assertEquals(List.of(id), fixture.ids());
            fixture.assertEmptyDirectories();
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void ordinaryRuntimeErrorsAndStackOverflowReuseCleanContainer() throws Exception {
        try (var fixture = fixture(SandboxLimits.defaults())) {
            fixture.pool.initDockerPool();
            String id = fixture.ids().get(0);
            for (String code : List.of(
                    "public class Solution { public static void main(String[] a) { throw new IllegalArgumentException(\"普通错误\"); } }",
                    "public class Solution { static void f(){ f(); } public static void main(String[] a){ f(); } }")) {
                SandBoxExecuteResult result = fixture.execute("普通运行错误或栈溢出", code, List.of(""), 1000);
                assertEquals(CodeRunStatus.NOT_ALL_PASSED, result.getRunStatus(), result.getExeMessage());
                assertTrue(result.getExeMessage().codePointCount(0, result.getExeMessage().length()) <= 500);
                assertEquals(List.of(id), fixture.ids());
                fixture.assertEmptyDirectories();
            }
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void longerQuestionLimitAndCompletedSlowProgramDoNotForceRetirement() throws Exception {
        try (var fixture = fixture(SandboxLimits.defaults())) {
            fixture.pool.initDockerPool();
            String id = fixture.ids().get(0);
            String sleep = "public class Solution { public static void main(String[] a) throws Exception { Thread.sleep(Long.parseLong(a[0])); System.out.println(\"ok\"); } }";
            SandBoxExecuteResult longer = fixture.execute("题目允许 10 秒，程序超过原 5 秒", sleep, List.of("5500"), 10000);
            assertEquals(CodeRunStatus.SUCCEED, longer.getRunStatus(), longer.getExeMessage());
            assertTrue(longer.getUseTime() >= 5500);
            SandBoxExecuteResult completed = fixture.execute("已退出，仅超过题目判定时限", sleep, List.of("100"), 1);
            assertEquals(CodeRunStatus.SUCCEED, completed.getRunStatus());
            assertTrue(completed.getUseTime() > 1);
            assertEquals(List.of(id), fixture.ids());
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void realCompilationTimeoutRemovesOldContainerBeforeReturning() throws Exception {
        try (var fixture = fixture(limits(1, 1048576))) {
            fixture.pool.initDockerPool();
            String old = fixture.ids().get(0);
            StringBuilder source = new StringBuilder("public class Solution { public static void main(String[] args) {} ");
            for (int index = 0; index < 2000; index++) source.append("static int f").append(index).append("(){return ").append(index).append(";}");
            source.append('}');
            SandBoxExecuteResult result = fixture.execute("真实 javac 编译超时", source.toString(), List.of(""), 1000);
            assertEquals(CodeRunStatus.OUT_OF_TIME, result.getRunStatus(), result.getExeMessage());
            assertEquals("编译超时", result.getExeMessage());
            fixture.assertAbsent(old);
            fixture.awaitWorkers(1);
            assertNotEquals(List.of(old), fixture.ids());
        }
    }

    @Test
    void loopsDeadlocksAndUnclosedUserExecutorsAreReplaced() throws Exception {
        try (var fixture = fixture(SandboxLimits.defaults())) {
            fixture.pool.initDockerPool();
            for (String code : List.of(LOOP,
                    "public class Solution { public static void main(String[] a) throws Exception { Thread.currentThread().join(); } }",
                    "public class Solution { public static void main(String[] a) { java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {}); } }")) {
                String old = fixture.ids().get(0);
                SandBoxExecuteResult result = fixture.execute("运行等待超时及遗留用户线程", code, List.of(""), 1000);
                assertEquals(CodeRunStatus.OUT_OF_TIME, result.getRunStatus(), result.getExeMessage());
                fixture.assertAbsent(old);
                fixture.awaitWorkers(1);
            }
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void stdoutAndStderrFloodsExceedDefaultBudgetAndRetireContainer() throws Exception {
        try (var fixture = fixture(SandboxLimits.defaults())) {
            fixture.pool.initDockerPool();
            for (String channel : List.of("out", "err")) {
                String old = fixture.ids().get(0);
                String source = "public class Solution { public static void main(String[] a) { String x = \"x\".repeat(4096); while(true) System."
                        + channel + ".print(x); } }";
                SandBoxExecuteResult result = fixture.execute("默认 1 MiB 输出超限：" + channel, source, List.of(""), 1000);
                assertEquals(CodeRunStatus.FAILED, result.getRunStatus(), result.getExeMessage());
                assertEquals("程序输出超出限制", result.getExeMessage());
                fixture.assertAbsent(old);
                fixture.awaitWorkers(1);
            }
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void aggregateCaseBudgetEmptyOutputAndMultiFrameOutputAreCorrect() throws Exception {
        try (var fixture = fixture(limits(10000, 1024))) {
            fixture.pool.initDockerPool();
            String source = "public class Solution { public static void main(String[] a) { System.out.print(\"a\".repeat(Integer.parseInt(a[0]))); } }";
            SandBoxExecuteResult exact = fixture.execute("跨用例累计输出恰好达到上限", source, List.of("512", "512"), 1000);
            assertEquals(CodeRunStatus.SUCCEED, exact.getRunStatus());
            assertEquals(List.of("a".repeat(512), "a".repeat(512)), exact.getOutputList());
            String old = fixture.ids().get(0);
            assertEquals(CodeRunStatus.FAILED, fixture.execute("跨用例超过上限一字节", source, List.of("512", "513"), 1000).getRunStatus());
            fixture.assertAbsent(old);
        }
        try (var fixture = fixture(SandboxLimits.defaults())) {
            fixture.pool.initDockerPool();
            String empty = "public class Solution { public static void main(String[] a) {} }";
            assertEquals(List.of(""), fixture.execute("空输出", empty, List.of(""), 1000).getOutputList());
            String large = "public class Solution { public static void main(String[] a) { System.out.print(\"中文🙂\".repeat(10000)); } }";
            SandBoxExecuteResult result = fixture.execute("多帧 UTF-8 完整输出", large, List.of(""), 1000);
            assertEquals(CodeRunStatus.SUCCEED, result.getRunStatus(), result.getExeMessage());
            assertEquals(List.of("中文🙂".repeat(10000)), result.getOutputList());
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void threadCreationHitsActualPidsLimitWithAndWithoutCatchingErrors() throws Exception {
        try (var fixture = fixture(SandboxLimits.defaults())) {
            fixture.pool.initDockerPool();
            for (boolean keepTrying : List.of(false, true)) {
                fixture.awaitWorkers(1);
                String old = fixture.ids().get(0);
                Path proofFile = Path.of(fixture.pool.getCodeDir(old)).resolve("pids-proof.txt");
                ExecutorService submitter = Executors.newSingleThreadExecutor();
                try {
                    Future<SandBoxExecuteResult> future = submitter.submit(() -> fixture.service.exeJavaCode(1L,
                            threadSource(keepTrying), List.of(""), 1000L));
                    AtomicReference<String> proof = new AtomicReference<>();
                    SandboxDockerTestSupport.await(() -> {
                        try {
                            String text = Files.readString(proofFile);
                            if (text.contains("events=max ")) { proof.set(text); return true; }
                        } catch (Exception ignored) { }
                        return false;
                    }, 7000);
                    String text = proof.get();
                    assertTrue(text.contains("limit=64\n"), text);
                    long current = Long.parseLong(text.lines().filter(line -> line.startsWith("current=")).findFirst().orElseThrow().substring(8));
                    assertTrue(current <= 64, text);
                    long rejected = Long.parseLong(text.lines().filter(line -> line.startsWith("events=max ")).findFirst().orElseThrow().substring(11));
                    assertTrue(rejected > 0, "必须有内核 PIDs 拒绝事件，不能用 OOM 文案替代：" + text);
                    fixture.event("pids-enforced", Map.of("catchThrowable", keepTrying, "containerId", old, "kernelProof", text));
                    assertEquals(CodeRunStatus.OUT_OF_TIME, future.get(20, TimeUnit.SECONDS).getRunStatus());
                    fixture.assertAbsent(old);
                } finally {
                    submitter.shutdownNow();
                    assertTrue(submitter.awaitTermination(10, TimeUnit.SECONDS));
                }
            }
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void removalFailureQuarantinesRealContainerAndRejectsFollowingTask() throws Exception {
        try (var fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 1, 1, 2, 200, real -> {
            var decorated = spy(real);
            doAnswer(call -> {
                RemoveContainerCmd blocked = mock(RemoveContainerCmd.class, RETURNS_SELF);
                doThrow(new IllegalStateException("真实容器测试中的删除故障注入")).when(blocked).exec();
                return blocked;
            }).when(decorated).removeContainerCmd(anyString());
            return decorated;
        })) {
            fixture.expectQuarantine = true;
            fixture.pool.initDockerPool();
            String old = fixture.ids().get(0);
            assertEquals(CodeRunStatus.UNKNOWN_FAILED, fixture.execute("真实容器删除失败隔离", LOOP, List.of(""), 1000).getRunStatus());
            assertEquals(1, fixture.pool.getQuarantinedContainerCount());
            assertEquals(1, fixture.pool.getAllocatedContainerCount());
            assertEquals(List.of(old), fixture.ids());
            assertEquals(CodeRunStatus.SYSTEM_BUSY, fixture.execute("隔离后拒绝新任务", SandboxDockerTestSupport.ADDITION,
                    List.of("1 2"), 1000).getRunStatus());
        }
    }

    @Test
    void retiringOneWorkerDoesNotCloseAnotherWorkersStreams() throws Exception {
        try (var fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 2, 2, 2, 200)) {
            fixture.pool.initDockerPool();
            ExecutorService submitter = Executors.newSingleThreadExecutor();
            try {
                String slow = """
                        public class Solution {
                            public static void main(String[] a) throws Exception {
                                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.class.path"), "started"), "yes");
                                Thread.sleep(7000);
                                System.out.println("ok");
                            }
                        }
                        """;
                Future<SandBoxExecuteResult> first = submitter.submit(() -> fixture.service.exeJavaCode(1L, slow, List.of(""), 10000L));
                AtomicReference<String> slowId = new AtomicReference<>();
                SandboxDockerTestSupport.await(() -> {
                    for (String id : fixture.ids()) {
                        if (Files.exists(Path.of(fixture.pool.getCodeDir(id)).resolve("started"))) { slowId.set(id); return true; }
                    }
                    return false;
                }, 5000);
                assertEquals(CodeRunStatus.OUT_OF_TIME, fixture.execute("另一个 Worker 运行超时", LOOP, List.of(""), 1000).getRunStatus());
                SandBoxExecuteResult healthy = first.get(20, TimeUnit.SECONDS);
                assertEquals(CodeRunStatus.SUCCEED, healthy.getRunStatus(), healthy.getExeMessage());
                assertEquals(List.of("ok"), healthy.getOutputList());
                assertTrue(fixture.ids().contains(slowId.get()));
                fixture.awaitWorkers(2);
                fixture.assertEmptyDirectories();
            } finally {
                submitter.shutdownNow();
                assertTrue(submitter.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private SandboxDockerTestSupport fixture(SandboxLimits limits) throws Exception {
        return new SandboxDockerTestSupport(limits, 1, 1, 2, 200);
    }

    private SandboxLimits limits(long compileMs, long bytes) {
        return new SandboxLimits(100000000, 100000000, 1, SandboxLimits.defaults().pidsLimit(),
                compileMs, 5000, 1000, bytes, 10000);
    }

    private String threadSource(boolean keepTrying) {
        return """
                public class Solution {
                    static void proof() {
                        try {
                            java.nio.file.Path cg = java.nio.file.Path.of("/sys/fs/cgroup");
                            String value = "limit=" + java.nio.file.Files.readString(cg.resolve("pids.max")).trim()
                                + "\\ncurrent=" + java.nio.file.Files.readString(cg.resolve("pids.current")).trim()
                                + "\\nevents=" + java.nio.file.Files.readString(cg.resolve("pids.events")).trim() + "\\n";
                            java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("java.class.path"));
                            java.nio.file.Files.writeString(root.resolve("pids-proof.tmp"), value);
                            java.nio.file.Files.move(root.resolve("pids-proof.tmp"), root.resolve("pids-proof.txt"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        } catch (Exception error) { System.err.println(error.getMessage()); }
                    }
                    public static void main(String[] args) {
                        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> proof());
                        while (true) {
                """ + (keepTrying ? "try {" : "") + """
                            new Thread(() -> { while (true) java.util.concurrent.locks.LockSupport.parkNanos(1000000000L); }).start();
                """ + (keepTrying ? "} catch (Throwable error) { proof(); java.util.concurrent.locks.LockSupport.parkNanos(10000000L); }" : "") + """
                        }
                    }
                }
                """;
    }
}
