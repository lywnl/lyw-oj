package com.lyw.friend.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 将真实 Redis 字节流转发给客户端；故障阶段只断开连接，不模拟 Redis 响应。 */
final class RedisFaultProxy implements AutoCloseable {
    private final String upstreamHost;
    private final int upstreamPort;
    private final ServerSocket server = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "rate-redis-fault-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private volatile boolean disconnected;

    RedisFaultProxy(String host, int port) throws IOException {
        upstreamHost = host;
        upstreamPort = port;
        threads.submit(this::accept);
    }

    int port() { return server.getLocalPort(); }

    void disconnect() {
        disconnected = true;
        sockets.forEach(RedisFaultProxy::closeSocket);
    }

    void reconnect() { disconnected = false; }

    private void accept() {
        while (!closed) {
            try {
                Socket incoming = server.accept();
                if (disconnected) {
                    closeSocket(incoming);
                    continue;
                }
                sockets.add(incoming);
                threads.submit(() -> connect(incoming));
            } catch (IOException failure) {
                if (!closed) throw new IllegalStateException("测试故障代理接收失败", failure);
            }
        }
    }

    private void connect(Socket incoming) {
        Socket upstream = new Socket();
        sockets.add(upstream);
        try {
            upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 1000);
            if (disconnected || closed) return;
            threads.submit(() -> copy(incoming, upstream));
            copy(upstream, incoming);
        } catch (IOException ignored) {
            // 测试主动断开连接时预期出现 I/O 异常。
        } finally {
            closeSocket(incoming);
            closeSocket(upstream);
            sockets.remove(incoming);
            sockets.remove(upstream);
        }
    }

    private void copy(Socket source, Socket target) {
        try {
            source.getInputStream().transferTo(target.getOutputStream());
        } catch (IOException ignored) {
            // 关闭其中一端会结束两个方向的转发。
        } finally {
            closeSocket(source);
            closeSocket(target);
        }
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        server.close();
        sockets.forEach(RedisFaultProxy::closeSocket);
        threads.shutdownNow();
        if (!threads.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("测试故障代理线程未退出");
    }
}

