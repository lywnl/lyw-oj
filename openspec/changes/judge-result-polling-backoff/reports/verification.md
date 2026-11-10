# 前端轮询退避调整：实现验收

日期：2026-11-10（Asia/Shanghai）。等级：fast；核验方式：manual。基线为本变更规划、`reviews/change-review.md` 和 `reviews/manual-planning-snapshot.json`，实施开始时间在首次源码修改前真实登记。

**本次前端修改与全部 3 项实施任务已完成，14 项测试通过。** 归档状态为 **Not Ready（仅归档前置未满足）**：`judge/submission-results` 尚待前置变更 `judge-submission-idempotency` 合并到正式规范，CLI 明确提示当前不能直接归档本增量。没有把实现验收通过冒充已可独立归档；无需为此修改本次已经通过验证的代码。

## 实现与证据

| 任务 | 实现与验证 | 证据 |
|---|---|---|
| 1.1 | B 的 `src/utils/judgeResultPoller.js` 默认初始间隔 200ms、上限 800ms，session 独立翻倍；首查立即发出。测试得到请求时间 0、200、600、1400、2200、3000ms。每次响应耗时 1000ms 时，请求时间为 0、1200、2600、4400ms，在途数量最大为 1；新提交和原 ID 手动重查均重置退避。 | [控制器测试日志](/Users/lllyw/lyw-oj/.codex/judge-result-polling-backoff/frontend-unit.log)；B 的 `tests/judgeResultPoller.test.js` 新增 4 项行为测试。 |
| 1.2 | Node v24.21.0、npm 11.19.0 下执行 `npm test`，14 项通过，0 失败、0 跳过。包含原有 JUDGED、临时错误、55 秒核对、60 秒截止、5 秒请求超时、取消及旧响应隔离回归。 | 同上；[完整命令记录](/Users/lllyw/lyw-oj/.codex/judge-result-polling-backoff/commands.json)。 |
| 1.3 | 两个修改文件的 `node --check`、两仓库 `git diff --check` 均退出 0；OpenSpec 严格校验退出 0，并如实保留归档依赖提示。前端差异只有控制器和测试两个文件；A 只新增本变更文档及 `.codex` 证据，没有改动后端业务实现。 | [代码差异与语法检查](/Users/lllyw/lyw-oj/.codex/judge-result-polling-backoff/frontend-syntax-and-diff.log)、[最终规划校验](/Users/lllyw/lyw-oj/.codex/judge-result-polling-backoff/final-plan-validation.log)。 |

源码根 B 为 `/Users/lllyw/oj-fe-c`，开始时分支 master、HEAD `1e3ca24c2bc41c1deda9f5095c8869cf537f559b`，工作区干净。规划根 A 为 `/Users/lllyw/lyw-oj`，HEAD `d274d57ffb21bc3b27eac1808d842cdbaa7922d4`，已有 Canal 等无关修改保留。本次实际代码及测试内容以 `verification-inputs.md` 的文件指纹为准，不能只用 HEAD 代表当前代码。

## 核验范围与交付

当前执行上下文 `/root` 完成普通自查：核对规格全部 6 个场景、任务映射、最终差异与原始测试日志；持续约束在实现后重新核对一致，记录见 `.codex/judge-result-polling-backoff/continuous-constraints.json`。本次 fast 不要求独立评审，没有独立评审声明。

验证使用真实控制器和可控时钟、受控 query 函数，不代表线上 HTTP、后台判题或负载测试。前端没有 node_modules；未安装依赖、未运行完整 Vite 构建或浏览器联调。页面调用及后端接口均未修改。

本次保留首查立即、响应结束后再等待的串行机制，以及原有 5/55/60 秒边界；不复制力扣的 100 次上限或停止处理。稳定阶段频率提升为每次响应后等 800ms，不能据此推断后端判题速度提高。

本轮独占 npm 缓存已删除，代码、测试、规划和所引用证据保留。未启动服务、容器或浏览器，未提交、推送、部署、归档或删除任何历史报告。回退仅需恢复本次两个前端文件的相关差异。

后续如需归档，先处理前置变更规范合并，再重新核对归档条件；当前记录仅确认本次实现和测试结果，不保存冒充 Ready for Archive 的通过记录。
