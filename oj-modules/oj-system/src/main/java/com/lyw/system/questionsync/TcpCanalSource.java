package com.lyw.system.questionsync;

import com.alibaba.otter.canal.client.impl.SimpleCanalConnector;
import com.alibaba.otter.canal.protocol.Message;
import java.net.InetSocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class TcpCanalSource implements SyncSource {
    private final SyncProperties properties;
    private final AtomicReference<SimpleCanalConnector> connector = new AtomicReference<>();
    private final AtomicReference<FutureTask<Void>> opening = new AtomicReference<>();
    private final AtomicReference<Object> openingOwner = new AtomicReference<>();
    private final Supplier<SimpleCanalConnector> factory;
    public TcpCanalSource(SyncProperties properties) {
        this(properties, () -> new SimpleCanalConnector(new InetSocketAddress(properties.getHost(), properties.getPort()),
                properties.getUsername(), properties.getPassword(), properties.getDestination()));
    }
    TcpCanalSource(SyncProperties properties, Supplier<SimpleCanalConnector> factory) {
        this.properties = properties; this.factory = factory;
    }
    @Override public void open() {
        close();
        Object owner = new Object(); openingOwner.set(owner);
        FutureTask<Void> task = new FutureTask<>(() -> {
            // 主机名解析也放入同一截止时间，不能在等待任务之前执行可能阻塞的 DNS。
            SimpleCanalConnector active = factory.get();
            if (openingOwner.get() != owner || Thread.currentThread().isInterrupted() || !connector.compareAndSet(null, active)) {
                active.disconnect(); throw new IllegalStateException("Canal TCP 建连已停止");
            }
            active.setSoTimeout(properties.getTimeoutMillis()); checkOpen(active, owner);
            active.connect(); checkOpen(active, owner);
            // 库名已限制为字母、数字、下划线和短横线，避免向 Aviator 传递 Java 专用引用语法。
            active.subscribe(properties.getDatabase() + "\\." + SyncProperties.TABLE); checkOpen(active, owner);
            active.rollback(); checkOpen(active, owner);
            return null;
        });
        opening.set(task);
        Thread connection = new Thread(task, "question-canal-connect"); connection.setDaemon(true); connection.start();
        try {
            // SimpleCanalConnector 的 SO_TIMEOUT 不覆盖 SocketChannel.connect，因此限制整次握手时长。
            task.get(properties.getTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            close(); throw new IllegalStateException("Canal TCP 建连或订阅超时");
        } catch (InterruptedException interrupted) {
            close(); Thread.currentThread().interrupt(); throw new IllegalStateException("Canal TCP 建连已停止");
        } catch (ExecutionException failed) {
            close();
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Canal TCP 建连失败");
        } catch (CancellationException cancelled) {
            close(); throw new IllegalStateException("Canal TCP 建连已取消");
        } finally { opening.compareAndSet(task, null); }
    }
    private void checkOpen(SimpleCanalConnector active, Object owner) {
        if (openingOwner.get() != owner || connector.get() != active || Thread.currentThread().isInterrupted()) {
            connector.compareAndSet(active, null);
            active.disconnect();
            throw new IllegalStateException("Canal 连接已停止");
        }
    }
    private SimpleCanalConnector active() {
        SimpleCanalConnector active = connector.get();
        if (active == null) throw new IllegalStateException("Canal 连接已关闭");
        return active;
    }
    @Override public Message get(int size) { return active().getWithoutAck(size); }
    @Override public void ack(long id) { active().ack(id); }
    @Override public void rollback(long id) { active().rollback(id); }
    @Override public void close() {
        openingOwner.set(null);
        SimpleCanalConnector active = connector.getAndSet(null);
        FutureTask<Void> task = opening.getAndSet(null);
        if (task != null) task.cancel(true);
        if (active != null) {
            try { active.disconnect(); } catch (RuntimeException ignored) { /* 未确认批次由重连恢复。 */ }
        }
    }
}
