# Proposal

## Why

用户于 2026-11-10 明确要求把前端结果轮询频率调整为已分析的 LeetCode 非会员策略。当前固定等待 2 秒会推迟短任务结果的展示，改为较短的初始等待及有上限的退避。

## What Changes

- 首次查询立即发出；未获得最终结果时，在每次请求结束后依次等待 200、400、800 毫秒，后续保持 800 毫秒。
- 新提交和手动重新查询分别重置退避；沿用串行查询、5 秒单请求上限、55 秒最终核对及 60 秒总窗口。
- 保留 SYSTEM_ERROR/NO_RESULT 继续、JUDGED 停止、查询异常暂停、取消与旧响应隔离行为。
- 范围限定为前端轮询控制器及相关测试，不引入会员分流或 100 次限制。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `judge/submission-results`：调整“前端必须在限定窗口内继续查询迟到真实结果”的请求间隔。该能力已在 `judge-submission-idempotency` 中实施验收，尚未归档到正式规范；本次沿用同一能力路径。将来归档须先合并该基线变更，再应用本次增量，不能用旧的 2 秒要求覆盖新行为。

## Impact

- 规划根 A：`/Users/lllyw/lyw-oj`；源码根 B：`/Users/lllyw/oj-fe-c`。本次用户的前端修改请求授权读取相关工程上下文、修改轮询及其测试；不扩大到其他工作区外内容。
- 实现：B 的 `src/utils/judgeResultPoller.js`、`tests/judgeResultPoller.test.js`；页面调用签名和后端 API 保持兼容。
- 来源：本次用户指令、当前前端源码、A 的 `openspec/changes/judge-submission-idempotency/`，以及 `.codex/leetcode-rea/20261110-c5e50c0a/deep-dive-3d540dfe/findings.md` 第 2 节。力扣结论来自公开脚本及受控重放，不是后端实现或线上限流实测。
- 风险等级 fast：可回滚的单个前端调度行为调整，不改变权限、持久化或公共协议。稳定阶段单页面查询频率提高，60 秒窗口仍限制自动查询持续时间。
- 验证采用当前 Node 内置测试入口及语法检查，无需启动服务、安装依赖或调用真实判题接口。两个根以 manual 方式登记实际文件指纹；保留已有无关修改和历史报告。
