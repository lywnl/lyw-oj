package com.lyw.system.questionsync;

/** PIT 尚未交给 Reconciler 时也必须把清理失败传递到实例隔离边界。 */
final class CursorCleanupException extends SyncBlockedException {
    CursorCleanupException() { super("对账游标清理失败，同步器需要隔离"); }
}
