package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.lyw.judge.callback.SandboxOutputBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 使用真实 TCP 连接和真实 Docker 客户端验证无响应故障，不启动业务服务。 */
@Timeout(25)
class SandboxTransportTest {
    @Test
    void controlResponseTimeoutClosesUnresponsiveConnection() throws Exception {
        try (NoResponseEndpoint endpoint = new NoResponseEndpoint();
             DockerClient control = DockerSandBoxPoolConfig.newControlClient(endpoint.host(), "1.41", 200)) {
            long started = System.nanoTime();
            assertThrows(RuntimeException.class, () -> control.pingCmd().exec());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            endpoint.assertPeerClosed();
        }
    }

    @Test
    void missingExecResponseCannotLeakOwnedConnectionOrStreamThread() throws Exception {
        assertStreamingTimeout(false);
    }

    @Test
    void missingStatsStartCannotLeaveAChannelBehind() throws Exception {
        assertStreamingTimeout(true);
    }

    @Test
    void responseWithoutOutputUsesExecutionDeadlineAndClosesCallbackConnection() throws Exception {
        Set<Long> before = streamingThreads();
        try (NoResponseEndpoint endpoint = new NoResponseEndpoint(true);
             DockerClient streams = DockerSandBoxPoolConfig.newStreamClient(endpoint.host(), "1.41")) {
            SandboxDockerExecutor executor = new SandboxDockerExecutor(control(), limits());
            long started = System.nanoTime();
            SandboxDockerExecutor.Result result = executor.run(streams, "container", new String[]{"java"}, 300,
                    new SandboxOutputBudget(1048576), false);
            assertTrue(result.timedOut());
            assertTrue(result.useTimeMs() >= 300);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            // 尚未关闭整个客户端，单次执行的回调关闭就应结束已启动的响应流。
            endpoint.assertPeerClosed();
        }
        awaitStreamThreadsClosed(before);
    }

    private void assertStreamingTimeout(boolean statistics) throws Exception {
        Set<Long> before = streamingThreads();
        try (NoResponseEndpoint endpoint = new NoResponseEndpoint();
             DockerClient streams = DockerSandBoxPoolConfig.newStreamClient(endpoint.host(), "1.41")) {
            SandboxDockerExecutor executor = new SandboxDockerExecutor(control(), limits());
            long started = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> executor.run(streams, "container", new String[]{"java"}, 10000,
                    new SandboxOutputBudget(1048576), statistics));
            assertEquals(statistics ? "Docker 统计流启动失败或超时" : "Docker 执行流启动失败或超时", failure.getMessage());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            assertFalse(Thread.currentThread().isInterrupted(), "本作用域的超时不能影响后续任务");
            // 回调还没拿到 onStart 句柄时，由对应 Worker 的独占客户端负责关闭。
            streams.close();
            endpoint.assertPeerClosed();
        }
        awaitStreamThreadsClosed(before);
    }

    private DockerClient control() {
        DockerClient control = mock(DockerClient.class);
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse response = mock(ExecCreateCmdResponse.class);
        when(response.getId()).thenReturn("unresponsive-exec");
        when(create.exec()).thenReturn(response);
        when(control.execCreateCmd(anyString())).thenReturn(create);
        return control;
    }

    private SandboxLimits limits() {
        return new SandboxLimits(100000000, 100000000, 1, 128, 10000, 5000, 1000, 1048576, 1000);
    }

    private void awaitStreamThreadsClosed(Set<Long> before) throws Exception {
        SandboxDockerTestSupport.await(() -> {
            Set<Long> current = streamingThreads();
            current.removeAll(before);
            return current.isEmpty();
        }, 10000);
    }

    private static Set<Long> streamingThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("docker-java-stream-"))
                .map(Thread::getId).collect(Collectors.toSet());
    }

    private static final class NoResponseEndpoint implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread thread;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean peerClosed = new AtomicBoolean();
        private volatile Socket socket;
        private volatile IOException terminationError;
        private volatile boolean closing;

        private NoResponseEndpoint() throws IOException { this(false); }

        private NoResponseEndpoint(boolean sendHeaders) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                try (Socket connection = listener.accept()) {
                    socket = connection;
                    byte[] buffer = new byte[2048];
                    boolean headersSent = false;
                    int count;
                    StringBuilder requestHeaders = new StringBuilder();
                    while ((count = connection.getInputStream().read(buffer)) != -1) {
                        if (sendHeaders && !headersSent) {
                            requestHeaders.append(new String(buffer, 0, count, StandardCharsets.US_ASCII));
                            if (requestHeaders.indexOf("\r\n\r\n") >= 0) {
                                connection.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                                        + "Content-Type: application/vnd.docker.raw-stream\r\n"
                                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                                connection.getOutputStream().flush();
                                headersSent = true;
                            }
                        }
                        // 故意不发送响应正文，直到客户端因期限或关闭结束连接。
                    }
                    if (!closing) peerClosed.set(true);
                } catch (IOException error) {
                    terminationError = error;
                    // 已启动响应的 request.abort 使用 SO_LINGER=0，实测会发送 TCP RST。
                    // 只接受这一明确的对端复位；其他 I/O 错误和测试自身关闭均不算成功。
                    if (!closing && sendHeaders && error instanceof SocketException
                            && "Connection reset".equals(error.getMessage())) {
                        peerClosed.set(true);
                    }
                } finally {
                    finished.countDown();
                }
            }, "sandbox-test-no-response");
            thread.setDaemon(true);
            thread.start();
        }

        String host() { return "tcp://127.0.0.1:" + listener.getLocalPort(); }

        void assertPeerClosed() throws InterruptedException {
            assertTrue(finished.await(10, TimeUnit.SECONDS), "客户端超时后连接仍未关闭");
            assertTrue(peerClosed.get(), "未观察到对端 EOF 或已确认的取消复位，实际异常：" + terminationError);
            System.out.println("HttpClient5 连接结束已验证：" + (terminationError == null ? "EOF" : terminationError));
        }

        @Override
        public void close() throws Exception {
            closing = true;
            if (socket != null) socket.close();
            listener.close();
            thread.join(3000);
            assertFalse(thread.isAlive());
        }
    }
}
