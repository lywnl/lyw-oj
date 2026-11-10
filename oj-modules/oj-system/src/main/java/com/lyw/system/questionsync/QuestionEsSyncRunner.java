package com.lyw.system.questionsync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单一消费线程拥有 source 与 writer；对账不得另起并行写入线程。 */
@Slf4j
public final class QuestionEsSyncRunner implements SmartLifecycle {
    public enum State { DISABLED, STARTING, RUNNING, RETRYING, BLOCKED, STOPPING, STOPPED }
    private final SyncProperties properties;
    private final SyncSource source;
    private final CanalEventMapper mapper;
    private final IndexStore index;
    private final PositionGuard positions;
    private final QuestionReconciler reconciler;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile State state = State.DISABLED;
    private volatile String reason;
    private volatile long lastAck = -1;
    private volatile Instant lastSuccess;
    private volatile long retries;
    private volatile long waitingMillis;
    private volatile BinlogPosition consumed;
    private volatile long differences;
    private volatile long repaired;
    private Thread worker;
    private boolean connected;
    private long nextPositionCheck;
    private long nextReconcile;
    private long nextWarning;
    private long nextStatusLog;
    private boolean reconciling;
    private final List<Runnable> stopCallbacks = new ArrayList<>();

    public QuestionEsSyncRunner(SyncProperties properties, SyncSource source, CanalEventMapper mapper,
            IndexStore index, PositionGuard positions, QuestionReconciler reconciler) {
        this.properties = properties; this.source = source; this.mapper = mapper; this.index = index;
        this.positions = positions; this.reconciler = reconciler;
    }

    @Override public synchronized void start() {
        if (running || !isAutoStartup()) return;
        properties.validate();
        stopRequested = false; running = true; state = State.STARTING;
        worker = new Thread(this::loop, "question-es-sync");
        worker.setDaemon(true); worker.start();
    }

    private void loop() {
        long delay = 1000;
        try {
            while (!stopRequested && state != State.BLOCKED) {
                try {
                    boolean work = tick(System.currentTimeMillis());
                    delay = 1000;
                    if (!work) Thread.sleep(properties.getIdleMillis());
                } catch (SyncBlockedException blocked) {
                    if (stopRequested) break;
                    state = State.BLOCKED; reason = blocked.getMessage();
                    log.error("题目索引同步已阻塞：{}", reason);
                    disconnect();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); break;
                } catch (Exception transientFailure) {
                    if (stopRequested) break;
                    state = State.RETRYING; reason = "连接或存储暂不可用：" + transientFailure.getClass().getSimpleName();
                    retries++;
                    log.warn("题目索引同步将重试，异常类型={}，次数={}", transientFailure.getClass().getSimpleName(), retries);
                    disconnect();
                    try { Thread.sleep(delay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    delay = Math.min(properties.getRetryMaxMillis(), delay * 2);
                }
            }
        } finally {
            disconnect();
            List<Runnable> callbacks;
            synchronized (this) {
                if (state != State.BLOCKED) state = State.STOPPED;
                running = false;
                callbacks = List.copyOf(stopCallbacks); stopCallbacks.clear();
            }
            // 只有消费和对账均已退出后才通知 Spring，可以安全地启动接替实例。
            for (Runnable callback : callbacks) callback.run();
        }
    }

    /** 一个步骤便于用真实 source 或可控故障验证确认边界。 */
    boolean tick(long now) throws Exception {
        checkStopping();
        if (!connected) {
            consumed = positions.check();
            checkStopping();
            index.validate();
            checkStopping();
            source.open(); connected = true;
            checkStopping();
            nextPositionCheck = now + properties.getPositionCheckMillis();
            nextReconcile = now + properties.getReconcileIntervalMillis();
        }
        if (now >= nextPositionCheck) {
            positions.check();
            checkStopping();
            nextPositionCheck = now + properties.getPositionCheckMillis();
        }
        var message = source.get(properties.getBatchSize());
        checkStopping();
        boolean work = message != null && message.getId() >= 0;
        if (work) {
            var batch = mapper.map(message);
            reconciler.observe(batch.changedIds());
            try {
                checkStopping();
                index.write(batch.changes());
            } catch (Exception failure) {
                try { source.rollback(message.getId()); } catch (RuntimeException ignored) { /* 重连再次恢复。 */ }
                throw failure;
            }
            // ACK 必须在写入成功之后；ACK 不确定只能重连重放，不能继续推进本地进度。
            checkStopping();
            source.ack(message.getId());
            lastAck = message.getId(); lastSuccess = Instant.now();
            if (batch.position() != null && (consumed == null || batch.position().compareTo(consumed) > 0)) consumed = batch.position();
        }
        checkStopping();
        state = State.RUNNING; reason = null;
        if (properties.getReconcileIntervalMillis() > 0 && (reconciling || now >= nextReconcile)) {
            if (!reconciling) {
                index.validate();
                checkStopping();
                reconciler.reset(); reconciling = true;
            }
            reconciler.step(consumed, now, true, true, () -> stopRequested);
            differences = reconciler.differences(); repaired = reconciler.repaired();
            waitingMillis = reconciler.waitingMillis(now);
            if (waitingMillis >= properties.getReconcileWarningMillis() && now >= nextWarning) {
                log.warn("对账候选等待源位点已超过阈值，等待毫秒={}；请执行 check，必要时进入维护 repair", waitingMillis);
                nextWarning = now + properties.getReconcileWarningMillis();
            }
            if (reconciler.finished()) {
                reconciling = false; nextReconcile = now + properties.getReconcileIntervalMillis();
                if (reconciler.timedOut()) log.warn("对账候选超过 5 分钟仍无法证明追平，已放弃本轮游标；请执行 check，必要时维护 repair");
                else if (reconciler.emptySourceProtected()) log.warn("自动对账遇主库空且索引有数据，已保留索引，需人工 check/repair");
                else log.info("题目索引对账完成，差异={}，修复={}", differences, repaired);
            }
            work = work || waitingMillis == 0;
        }
        if (now >= nextStatusLog) {
            log.info("题目索引同步状态={}，最近成功={}，最近ACK={}，重试={}，差异={}，修复={}",
                    state, lastSuccess, lastAck, retries, differences, repaired);
            nextStatusLog = now + 30000;
        }
        return work;
    }

    private void disconnect() {
        source.close(); connected = false; reconciling = false; reconciler.reset();
    }

    private void checkStopping() throws InterruptedException {
        if (stopRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException("同步停止");
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state.name()); result.put("reason", reason); result.put("lastAck", lastAck);
        result.put("lastSuccess", lastSuccess == null ? null : lastSuccess.toString()); result.put("retries", retries);
        result.put("position", consumed); result.put("differences", differences); result.put("repaired", repaired);
        result.put("candidateWaitingMillis", waitingMillis);
        return result;
    }

    private Thread requestStop() {
        Thread active;
        synchronized (this) {
            stopRequested = true;
            active = worker;
            if (running) state = State.STOPPING;
        }
        if (active != null) active.interrupt();
        // 主动关闭连接，以解除正在执行的 get；source 实现允许并发 close。
        source.close();
        return active;
    }

    @Override public void stop() {
        Thread active = requestStop();
        if (active != null && active != Thread.currentThread()) {
            try { active.join(properties.getTimeoutMillis() * 3L + 1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (active.isAlive()) log.warn("同步线程仍在退出，不能启动接替同步器");
        }
    }
    @Override public void stop(Runnable callback) {
        boolean alreadyStopped;
        synchronized (this) {
            alreadyStopped = !running;
            if (!alreadyStopped) stopCallbacks.add(callback);
        }
        requestStop();
        if (alreadyStopped) callback.run();
    }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return properties.isEnabled() && "normal".equals(properties.getMode()); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
