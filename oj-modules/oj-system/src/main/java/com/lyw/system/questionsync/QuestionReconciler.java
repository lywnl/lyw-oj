package com.lyw.system.questionsync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 每次只准备一页；自动修复须等待 Canal 到达页末源位点。 */
public final class QuestionReconciler implements AutoCloseable {
    static final long MAX_CANDIDATE_WAIT_MILLIS = 300000;
    private final QuestionReader reader;
    private final IndexStore index;
    private final PositionGuard guard;
    private final int pageSize;
    private long afterId;
    private List<Object> afterIndex;
    private boolean reverse;
    private boolean finished;
    private boolean emptySourceProtected;
    private boolean timedOut;
    private List<IndexMutation> pending;
    private BinlogPosition barrier;
    private long preparedAt;
    private final Set<Long> invalidated = new HashSet<>();
    private long differences;
    private long repaired;

    public QuestionReconciler(QuestionReader reader, IndexStore index, PositionGuard guard, int pageSize) {
        this.reader = reader; this.index = index; this.guard = guard; this.pageSize = pageSize;
    }

    public void reset() {
        close();
        afterId = 0; afterIndex = null; reverse = false; finished = false; pending = null; barrier = null;
        invalidated.clear(); differences = 0; repaired = 0; emptySourceProtected = false; timedOut = false;
    }

    public void observe(Set<Long> ids) {
        if (pending != null) for (IndexMutation change : pending) if (ids.contains(change.id())) invalidated.add(change.id());
    }
    public boolean finished() { return finished && pending == null; }
    public long differences() { return differences; }
    public long repaired() { return repaired; }
    public boolean emptySourceProtected() { return emptySourceProtected; }
    public boolean timedOut() { return timedOut; }
    public long waitingMillis(long now) { return pending == null ? 0 : Math.max(0, now - preparedAt); }

    @Override public void close() {
        if (afterIndex != null) try { index.closeCursor(afterIndex); } catch (Exception ignored) { /* PIT 会自动过期。 */ }
        afterIndex = null;
    }

    public void step(BinlogPosition consumed, long now, boolean write, boolean automatic) throws Exception {
        step(consumed, now, write, automatic, () -> false);
    }

    public void step(BinlogPosition consumed, long now, boolean write, boolean automatic, BooleanSupplier stopping) throws Exception {
        checkStopping(stopping);
        if (pending != null) {
            if (automatic && waitingMillis(now) >= MAX_CANDIDATE_WAIT_MILLIS) {
                // 不继续使用可能过期的 PIT；保留差异计数，下个周期从头核对。
                if (afterIndex != null) index.closeCursor(afterIndex);
                afterIndex = null; pending = null; barrier = null; invalidated.clear();
                timedOut = true; finished = true;
                return;
            }
            if (!automatic || (consumed != null && consumed.compareTo(barrier) >= 0)) {
                List<IndexMutation> applicable = pending.stream().filter(change -> !invalidated.contains(change.id())).toList();
                checkStopping(stopping);
                if (write) { index.write(applicable); repaired += applicable.size(); }
                pending = null; barrier = null; invalidated.clear();
            }
            return;
        }
        if (finished) return;
        checkStopping(stopping);
        List<IndexMutation> changes = new ArrayList<>();
        if (!reverse) {
            var rows = reader.page(afterId, pageSize);
            if (rows.isEmpty()) { reverse = true; return; }
            var ids = rows.stream().map(QuestionDocument::questionId).toList();
            var actual = index.get(ids);
            checkStopping(stopping);
            for (var document : rows) {
                if (!Objects.equals(document, actual.get(document.questionId()))) changes.add(IndexMutation.put(document));
            }
            afterId = rows.get(rows.size() - 1).questionId();
        } else {
            var page = index.page(afterIndex, pageSize);
            afterIndex = page.after();
            if (page.ids().isEmpty()) { finished = true; return; }
            // 主库查询完成后才生成删除集合；失败不会被当作全部不存在。
            var present = reader.find(page.ids());
            checkStopping(stopping);
            for (long id : page.ids()) if (!present.containsKey(id)) changes.add(IndexMutation.delete(id));
            if (automatic && !changes.isEmpty() && reader.count() == 0) {
                emptySourceProtected = true;
                differences += changes.size();
                changes.clear();
            }
        }
        differences += changes.size();
        if (changes.isEmpty() || !write) return;
        checkStopping(stopping);
        barrier = automatic ? guard.capture() : null;
        checkStopping(stopping);
        pending = List.copyOf(changes); preparedAt = now; invalidated.clear();
        if (!automatic) {
            index.write(pending); repaired += pending.size(); pending = null;
        }
    }

    private void checkStopping(BooleanSupplier stopping) throws InterruptedException {
        if (stopping.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("对账停止");
    }
}
