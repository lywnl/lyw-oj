package com.lyw.system.questionsync;

import com.lyw.api.reconcile.QuestionReconcileProperties;
import com.lyw.api.reconcile.ReconcileResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** 单一消费线程拥有 source 与 writer；HTTP 线程只提交请求和等待结果。 */
@Slf4j
public final class QuestionEsSyncRunner implements SmartLifecycle {
    public enum State { DISABLED, STARTING, RUNNING, RETRYING, BLOCKED, STOPPING, STOPPED }
    private final SyncProperties properties;
    private final SyncSource source;
    private final CanalEventMapper mapper;
    private final IndexStore index;
    private final PositionGuard positions;
    private final QuestionReconciler reconciler;
    private final QuestionReconcileProperties reconcileProperties;
    private final LongSupplier clock;
    private final LongSupplier wallClock;
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
    private volatile ReconciliationRequest request;
    private volatile boolean cleanupFailed;
    private Thread worker;
    private boolean connected;
    private long nextPositionCheck;
    private long nextWarning;
    private long nextStatusLog;
    private final List<Runnable> stopCallbacks = new ArrayList<>();

    public QuestionEsSyncRunner(SyncProperties properties, SyncSource source, CanalEventMapper mapper,
            IndexStore index, PositionGuard positions, QuestionReconciler reconciler,
            QuestionReconcileProperties reconcileProperties) {
        this(properties, source, mapper, index, positions, reconciler, reconcileProperties, System::nanoTime, System::currentTimeMillis);
    }

    QuestionEsSyncRunner(SyncProperties properties, SyncSource source, CanalEventMapper mapper,
            IndexStore index, PositionGuard positions, QuestionReconciler reconciler,
            QuestionReconcileProperties reconcileProperties, LongSupplier clock, LongSupplier wallClock) {
        this.properties = properties; this.source = source; this.mapper = mapper; this.index = index;
        this.positions = positions; this.reconciler = reconciler;
        this.reconcileProperties = reconcileProperties; this.clock = clock; this.wallClock = wallClock;
    }

    public synchronized ReconciliationRequest requestReconciliation() {
        if (!reconcileProperties.isEnabled() || !isAutoStartup() || !running || stopRequested
                || state != State.RUNNING || cleanupFailed) throw ReconciliationException.unavailable();
        if (request != null) throw new ReconciliationException(409, "已有一轮对账等待、执行或清理中");
        request = new ReconciliationRequest(reconcileProperties.getTimeoutMillis(), clock);
        return request;
    }

    @Override public synchronized void start() {
        if (running || !isAutoStartup()) return;
        if (cleanupFailed) throw new SyncBlockedException("上轮对账清理失败，当前同步器已隔离");
        properties.validate(); reconcileProperties.validateServer();
        stopRequested = false; running = true; state = State.STARTING;
        worker = new Thread(this::loop, "question-es-sync");
        worker.setDaemon(true); worker.start();
    }

    private void loop() {
        long delay = 1000;
        try {
            while (!stopRequested && state != State.BLOCKED) {
                try {
                    boolean work = tick(wallClock.getAsLong());
                    delay = 1000;
                    if (!work) Thread.sleep(properties.getIdleMillis());
                } catch (CursorCleanupException failedCursor) {
                    cleanupFailed = true; state = State.BLOCKED; reason = failedCursor.getMessage();
                    log.error("题目索引同步已隔离：{}", reason);
                    disconnect(new ReconciliationException(500, "对账游标清理失败，同步器已隔离"));
                } catch (SyncBlockedException blocked) {
                    if (stopRequested) break;
                    state = State.BLOCKED; reason = blocked.getMessage();
                    log.error("题目索引同步已阻塞：{}", reason);
                    disconnect(new ReconciliationException(503, "同步已阻塞，本轮对账结束"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); break;
                } catch (Exception transientFailure) {
                    if (stopRequested) break;
                    state = State.RETRYING; reason = "连接或存储暂不可用：" + transientFailure.getClass().getSimpleName();
                    retries++;
                    log.warn("题目索引同步将重试，异常类型={}，次数={}", transientFailure.getClass().getSimpleName(), retries);
                    disconnect(new ReconciliationException(500, "连接或存储异常，本轮对账结束"));
                    if (state == State.BLOCKED) break;
                    try { Thread.sleep(delay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    delay = Math.min(properties.getRetryMaxMillis(), delay * 2);
                }
            }
        } finally {
            try { disconnect(new ReconciliationException(503, "同步已停止，本轮对账结束")); }
            finally {
                List<Runnable> callbacks;
                synchronized (this) {
                    if (state != State.BLOCKED) state = State.STOPPED;
                    running = false;
                    callbacks = List.copyOf(stopCallbacks); stopCallbacks.clear();
                }
                // 消费和对账均已退出后才通知 Spring；清理失败状态仍保留。
                for (Runnable callback : callbacks) callback.run();
            }
        }
    }

    /** 一个步骤便于验证实际 ACK 与安全写入边界。 */
    boolean tick(long now) throws Exception {
        checkStopping();
        ReconciliationRequest pending = request;
        if (pending != null && pending.cancelled()) finishRequest(pending, pending.cancellation());
        if (!connected) {
            consumed = positions.check(); checkStopping();
            index.validate(); checkStopping();
            source.open(); connected = true; checkStopping();
            nextPositionCheck = now + properties.getPositionCheckMillis();
        }
        if (now >= nextPositionCheck) {
            positions.check(); checkStopping();
            nextPositionCheck = now + properties.getPositionCheckMillis();
        }
        var message = source.get(properties.getBatchSize());
        checkStopping();
        boolean work = message != null && message.getId() >= 0;
        if (work) {
            var batch = mapper.map(message);
            reconciler.observe(batch.changedIds());
            try {
                checkStopping(); index.write(batch.changes());
            } catch (Exception failure) {
                try { source.rollback(message.getId()); } catch (RuntimeException ignored) { /* 重连恢复。 */ }
                throw failure;
            }
            // ACK 只在写入成功后发送；不确定时重连重放，不推进本地位点。
            checkStopping(); source.ack(message.getId());
            lastAck = message.getId(); lastSuccess = Instant.now();
            if (batch.position() != null && (consumed == null || batch.position().compareTo(consumed) > 0)) consumed = batch.position();
        }
        checkStopping();
        state = State.RUNNING; reason = null;
        ReconciliationRequest active = request;
        if (active != null) work = advanceReconciliation(active, now) || work;
        if (now >= nextStatusLog) {
            log.info("题目索引同步状态={}，最近成功={}，最近ACK={}，重试={}，差异={}，修复={}",
                    state, lastSuccess, lastAck, retries, differences, repaired);
            nextStatusLog = now + 30000;
        }
        return work;
    }

    private boolean advanceReconciliation(ReconciliationRequest active, long now) throws Exception {
        if (active.cancelled()) { finishRequest(active, active.cancellation()); return false; }
        if (!active.startedByConsumer) {
            index.validate(); checkStopping();
            reconciler.resetStrict();
            active.startedByConsumer = true;
            differences = 0; repaired = 0; waitingMillis = 0; nextWarning = 0;
        }
        try {
            reconciler.step(consumed, now, true, true, () -> stopRequested || active.cancelled());
        } catch (InterruptedException cancelled) {
            if (stopRequested || Thread.currentThread().isInterrupted()) throw cancelled;
            if (!active.cancelled()) throw cancelled;
            finishRequest(active, active.cancellation());
            return false;
        }
        differences = reconciler.differences(); repaired = reconciler.repaired();
        waitingMillis = reconciler.waitingMillis(now);
        if (active.cancelled()) { finishRequest(active, active.cancellation()); return false; }
        if (waitingMillis >= properties.getReconcileWarningMillis() && now >= nextWarning) {
            log.warn("对账候选等待位点已超过阈值，等待毫秒={}", waitingMillis);
            nextWarning = now + properties.getReconcileWarningMillis();
        }
        if (reconciler.finished()) {
            ReconciliationException failure = null;
            if (reconciler.timedOut()) failure = new ReconciliationException(504, "候选位点等待超时，请核查同步状态");
            else if (reconciler.emptySourceProtected()) failure = new ReconciliationException(500, "已触发空库保护，请人工 check/repair");
            finishRequest(active, failure);
        }
        return waitingMillis == 0;
    }

    /** 仅消费线程调用；清理完成前槽始终保留。 */
    private void finishRequest(ReconciliationRequest active, ReconciliationException failure) {
        if (cleanupFailed) {
            active.fail(new ReconciliationException(500, "对账清理失败，同步器已隔离"));
            return;
        }
        long found = reconciler.differences(), fixed = reconciler.repaired();
        try {
            reconciler.resetStrict();
        } catch (Exception cleanupFailure) {
            cleanupFailed = true; state = State.BLOCKED; reason = "对账游标清理失败，同步器已隔离";
            active.fail(new ReconciliationException(500, reason));
            throw new SyncBlockedException(reason);
        }
        synchronized (this) {
            if (failure == null) failure = stopRequested ? ReconciliationException.unavailable() : active.cancellation();
            if (failure == null) {
                log.info("题目索引对账完成，差异={}，修复={}，耗时毫秒={}", found, fixed, active.elapsedMillis());
                active.succeed(new ReconcileResult(found, fixed, active.elapsedMillis()));
            } else {
                log.warn("题目索引对账未完成，原因={}，差异={}，修复={}", failure.getMessage(), found, fixed);
                active.fail(failure);
            }
            if (request == active) request = null;
        }
        waitingMillis = 0;
    }

    private void disconnect(ReconciliationException failure) {
        connected = false;
        try { source.close(); }
        catch (RuntimeException closeFailure) {
            state = State.BLOCKED; reason = "同步连接关闭失败，请人工核查";
            log.error("同步连接关闭失败，类型={}", closeFailure.getClass().getSimpleName());
        }
        ReconciliationRequest active = request;
        if (active != null) {
            try { finishRequest(active, failure); }
            catch (SyncBlockedException cleanupFailure) { /* 请求已失败，保留 BLOCKED 和占用槽。 */ }
        }
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
        result.put("reconciliationActive", request != null); result.put("reconciliationCleanupFailed", cleanupFailed);
        return result;
    }

    private Thread requestStop() {
        Thread active;
        synchronized (this) {
            stopRequested = true; active = worker;
            if (running && state != State.BLOCKED) state = State.STOPPING;
        }
        if (active != null) active.interrupt();
        // close 可并发调用，解除正在等待的 get；Reconciler 仍只由消费线程清理。
        try { source.close(); }
        catch (RuntimeException failure) { log.warn("请求停止时连接关闭异常，类型={}", failure.getClass().getSimpleName()); }
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
