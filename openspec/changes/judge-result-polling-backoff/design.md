# Design

## Context

前端 B 使用 Vue 3、Vite 7 和 Node 内置测试。`Answer.vue` 通过 `createJudgeResultPoller` 查询原 submitId，仅传入请求超时和业务回调，仓库中没有调用方传入 `intervalMs`。控制器已经用会话对象隔离旧响应，并使用单调时钟维护 55 秒阶段切换和 60 秒绝对截止。

## Goals / Non-Goals

实现 specs 中既有轮询要求的间隔调整。源码修改仅涉及控制器及测试；不用 `setInterval`，不引入调度依赖，也不改变接口、沙箱或后端存储。计划目录与源码位于两个授权根，采用 manual 核验。

## Decisions

1. 把原固定间隔参数替换为 `initialIntervalMs = 200`、`maxIntervalMs = 800`，每个 session 持有 `nextIntervalMs`。等待一次后翻倍并封顶；不新增跨轮次的全局计数器，避免重启查询沿用旧退避。
2. 保留 `await query → waitForNext → 下一次 query` 顺序。等待时间继续取退避间隔与距最终核对剩余时间的较小值；现有阶段定时器负责 55 秒切换，因此不会为了凑足 800 毫秒推迟核对。
3. 手动核对未获得最终结果时，同样从 200 毫秒开始等待；异常仍按原规则暂停。原 `intervalMs` 无实际调用方，替换内部配置不影响页面调用。
4. 复用已有可控时钟测试，直接断言默认时序、响应耗时叠加、每轮重置及在途数量。保留全部原有终态、异常、取消和预算回归；无需真实提交或访问力扣。

## Risks / Trade-offs

- 稳定阶段每个等待页面的理论查询频率从约 0.5 次/秒提高到约 1.25 次/秒（忽略网络耗时）；这是用户要求的频率变化。普通请求仍传 `checkDatabase=false`，最终核对仍只有一次，不将前端节奏视为后端限流。
- 误把退避当固定触发周期会造成请求重叠；以慢响应时序和在途计数验证。
- 当前前端没有 node_modules；本次只改纯 JavaScript 控制器，`npm test` 和 `node --check` 不依赖第三方安装，可覆盖本次行为。完整 Vite 构建及真实页面/后端验证不作为本次结论。

## Migration Plan

无需数据迁移或服务配置变化。交付前端两个文件；回退仅恢复本次等待策略及对应测试，不能覆盖其他修改。旧 `judge-submission-idempotency` 的验收记录保留为历史证据；本次增量声明其后续行为，归档时按 proposal 的依赖顺序合并。本次不执行部署或归档。
