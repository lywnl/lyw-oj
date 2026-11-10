# Tasks

## 1. 调整轮询及验证

- [x] 1.1 修改 B 的 `src/utils/judgeResultPoller.js`，实现 session 独立的 200→400→800 毫秒退避，并在 `tests/judgeResultPoller.test.js` 验证首查立即、响应结束后计时、串行及新提交/手动查询重置。
- [x] 1.2 基于 1.1 运行 B 的 `npm test` 全部控制器回归，核对 JUDGED 停止、临时错误继续、55 秒切换、60 秒截止、5 秒请求超时、取消和旧结果隔离均通过；原始日志保存在 A 的 `.codex/judge-result-polling-backoff/`。
- [x] 1.3 基于 1.1、1.2 运行修改文件的 `node --check`、两仓库 `git diff --check` 与本变更 OpenSpec 严格校验，核对实际差异仅包含本次范围。

## Workflow follow-up

- 完成上述实现与真实验证后执行 fast/manual 验收并保存对应记录，不把验收结论当作实施任务。
- 未执行提交、推送、部署或归档；后续归档依赖 `judge-submission-idempotency` 的规范先合并。
