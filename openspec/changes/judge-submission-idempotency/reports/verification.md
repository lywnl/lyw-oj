# 判题提交幂等与系统失败反馈：实施验收

日期：2026-11-09（Asia/Shanghai）。风险等级：strict。核验方式：manual，覆盖后端 `/Users/lllyw/lyw-oj` 与前端 `/Users/lllyw/oj-fe-c`。

结论：**Ready for Archive**。已完成本次实施和验收，13 项任务均有对应实现与验证；没有执行 Git 提交、推送、部署或 OpenSpec 归档。

## 验收依据

采用[最终修订计划](/Users/lllyw/lyw-oj/.codex/plans/判题提交幂等与系统失败反馈改造执行计划.md)以及本变更的 proposal、specs、design、tasks。该版本采用一个结果缓存、普通轮询只读 Redis、系统提示后继续等待以及窗口末尾核对数据库。

[规划评审](/Users/lllyw/lyw-oj/openspec/changes/judge-submission-idempotency/reviews/change-review.md)和[独立规划评审](/Users/lllyw/lyw-oj/openspec/changes/judge-submission-idempotency/reviews/independent-planning-review.md)均为 PASS。原实施开始记录为 `2026-11-08T15:45:01.050582+00:00`，没有重写或补造。`x-workflow-profile`、`x-verification-mode` 分别承接项目的 strict、manual 语义，以兼容当前 OpenSpec CLI 的扩展字段要求。

[结构化验收映射](/Users/lllyw/lyw-oj/openspec/changes/judge-submission-idempotency/reports/verification-manifest.json)逐项关联 **7 项需求、18 个场景、13 项任务**与实现、测试、命令及原始证据。[验收文件清单](/Users/lllyw/lyw-oj/openspec/changes/judge-submission-idempotency/reports/verification-inputs.md)绑定两个授权根的当前文件，包括全部相关源码、测试、POM、前端依赖清单和构建配置。

## 已实现的行为

| 改动位置 | 当前行为 |
|---|---|
| [UserSubmitStore.java](/Users/lllyw/lyw-oj/oj-modules/oj-judge/src/main/java/com/lyw/judge/service/UserSubmitStore.java:38)、JudgeServiceImpl | 每次消费先查已保存结果；以原 submitId 在短事务中插入。主键冲突退出失败事务后读取数据库获胜记录，双方复用同一个真实结果。 |
| [JudgeProducer.java](/Users/lllyw/lyw-oj/oj-modules/oj-friend/src/main/java/com/lyw/friend/rabbit/JudgeProducer.java)、消息 DTO 与回执 | 后端只分配一次 submitId，重发沿用；回执用字符串 ID。发送错误 3701 携带已分配 ID，前端可以继续查询。 |
| [update-result-state.lua](/Users/lllyw/lyw-oj/oj-commom/oj-common-redis/src/main/resources/lua/update-result-state.lua)、StateCacheService | 统一键 `j:r:{userId}:{submitId}`。状态只能按 `WAITING < SYSTEM_ERROR < JUDGED` 升级，同级或降级更新不覆盖、不续期；保持原始 JSON 中大整数 ID 的精度。 |
| [JudgeConsumer.java](/Users/lllyw/lyw-oj/oj-modules/oj-judge/src/main/java/com/lyw/judge/rabbit/JudgeConsumer.java)、DeadLetterConsumer | 普通消费最多三次业务尝试，繁忙立即送死信。系统异常不插入提交表；死信只更新缓存，连续三次写入失败后暂停独立监听、保留未确认消息。ACK/NACK 故障不重新执行业务。 |
| [UserQuestionController.java](/Users/lllyw/lyw-oj/oj-modules/oj-friend/src/main/java/com/lyw/friend/controller/user/UserQuestionController.java:30)、查询服务与 SQL | 按登录用户与 submitId 查询。普通请求只读缓存；显式核对或 Redis 读取异常才允许回源。数据库查询失败返回 2000，已取得真实结果时缓存回填失败不会让响应失败。 |
| [judgeResultPoller.js](/Users/lllyw/oj-fe-c/src/utils/judgeResultPoller.js:2)、[Answer.vue](/Users/lllyw/oj-fe-c/src/views/Answer.vue:145) | 每次请求结束后间隔 2 秒，总窗口 60 秒。系统错误提示后继续查询，第 55 秒切换一次最终核对；手动重新查询沿用原 ID。切题、离开和新提交隔离旧回调，题目加载及导航期间禁止提交。 |

对外结果为 `JUDGED`、`SYSTEM_ERROR`、`NO_RESULT`；只有真实判题结果带 `pass=0/1`。系统提示不会使同 submitId 后来的真实结果无法入库；前端在当前等待窗口内能够用真实结果替换提示。

## 测试结果与环境

| 验证 | 结果与原始证据 |
|---|---|
| 后端 JDK 17 回归 | **42 项通过，0 失败、0 错误、0 跳过**：friend 14 项、judge 28 项。含原有 JudgeTaskTest、DockerSandBoxPoolConcurrencyTest。[JUnit 汇总](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/backend-test-summary.json)、[完整运行日志](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/final-backend.log)。 |
| 真实中间件 | Docker 内真实 MySQL 主键竞争、事务回滚、实际查询 XML 的用户归属；真实 Redis Lua 并发/TTL/大 ID/自动配置；真实 RabbitMQ 在入库后 ACK 前断连重投、死信暂停保留及恢复，均包含在上述 42 项中。 |
| 前端控制器 | **10 项通过，0 失败、0 跳过**，覆盖可控时钟、请求串行、窗口不续期、55 秒切换、60 秒截止、取消与 ID 隔离。[运行日志](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/frontend-unit.log)。 |
| 前端生产构建 | `npm run build` 成功；保留 Vite 的大 chunk 提示，没有将其当作幂等功能失败。[构建日志](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/frontend-build.log)。 |
| Chrome 页面 | 实际页面连接受控 HTTP API：系统提示后继续并显示真实结果、加载时禁提交、旧响应隔离、查询失败后手动核对、3701 后继续查询、无 ID 的提交超时不重发、迟到回执不串题。实测末尾在 **55.004 秒**发出且仅发出一次 `checkDatabase=true` 请求。[页面审计](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/ui-audit.json)、[55 条请求记录](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/ui-requests.jsonl)。 |
| 最终检查 | 两仓库 `git diff --check`、持续规划约束比较、OpenSpec 严格格式校验均通过；192 个运行时源码条目及独立评审登记的三份已修改构建清单与当前文件一致。[命令记录](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/final-checks.json)、[源码复核](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/source-recheck.json)。 |

后端实际运行于 Linux arm64、Eclipse Adoptium **17.0.15**、Maven 镜像 `maven:3.9.9-eclipse-temurin-17`。隔离依赖镜像为 `mysql:8.0.40`、`redis:7.4-alpine`、`rabbitmq:3.13-alpine`。Docker Server 为 29.8.2，本机 Docker 配置 8 核、8,319,770,624 字节内存。镜像 ID 和环境信息已保存，宿主 JDK 未切换。

详细命令、原工作目录、时间与退出码见验收映射中的 commands 及各 `*-command.json`。复跑时在新的 `.codex` 目录准备源码副本、独占 Maven/npm 缓存及隔离 MySQL/Redis/RabbitMQ，执行已记录的 Maven 选定测试命令（包含 `-Doj.integration=true`）、`npm test` 与 `npm run build`。Chrome 夹具源码保留在原始证据的 `support/ui-fixture.mjs`，页面断言另见审计；已删除的旧构建目录和容器 ID 不能直接复用。

早期编译和测试尝试存在失败，已修复并由最终完整运行覆盖。[尝试索引](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/attempt-history.json)及 `evidence/attempts/` 保留失败原件，不将早期失败冒充通过。日志中的部分 ERROR 为数据库、缓存或消息故障注入。页面夹具在验证结束后主动 SIGTERM，终端退出码 143 表示本轮收尾，不作为断言失败。

## 独立评审与边界

[独立代码评审](/Users/lllyw/lyw-oj/openspec/changes/judge-submission-idempotency/reviews/independent-code-review.md)由没有修改业务代码或测试的 `/root/planning_review` 完成，最终结论 **PASS**，无剩余 P1/P2。评审发现的首屏详情代次冲突、导航期间可提交旧题两项竞态已修复并复核。

本次保证同 submitId 最多保存一条真实记录，允许故障或并发时重复运行沙箱。统一缓存的状态优先级只约束仍有效的缓存，缓存过期后迟到系统提示仍可能重新出现，显式数据库核对能恢复已保存结果。系统失败不保证长期可追溯，前端停止查询后不会自动推送任意晚到结果；手动核对和 Redis 故障回源也不具备全局 QPS 上限。

中间件验证中的沙箱计算使用可控替身，Chrome 使用受控 API；本次未运行生产完整判题链路、完整真实 Docker 沙箱回归或性能测试。容器池执行与健康恢复代码保持原实现，按已确认计划执行任务和池化单元回归；具体范围已写入对应场景映射。

没有新增数据库迁移，没有修改历史提交记录或排名规则。新消息和查询协议需要前后端一起交付；实际部署及回退须协调两端，并避免旧消费者读取新协议消息，本轮未执行这些环境操作。

## 清理与报告保留

[清理记录](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/cleanup.json)确认按登记 ID 和归属标签清理了 **12 个本轮容器、1 个网络、1 个临时卷**，删除本轮新增且无人引用的 Maven、RabbitMQ 镜像，以及后端隔离构建/Maven 缓存、前端隔离源码/node_modules/dist/npm 缓存。测试页面已关闭，UI 进程和本轮构建进程均已退出，无本轮隔离资源残留。

原有 8 个容器、7 份镜像和 4 个网络仍存在；没有执行全局 Docker 清理。两份历史容器性能报告的内容哈希保持不变。代码变更按本次任务保留，没有重置 Git 工作区。

本报告、计划、OpenSpec 评审、原始日志/JUnit/截图、请求审计及必要核验脚本作为验收记录保留。它们是本轮有意保留的交付证据，不计为未清理临时资源；没有设置自动删除时间，等待用户明确指令后处理。

页面证明（真实 Chrome 页面，受控 API）：

![系统提示后显示真实结果](/Users/lllyw/lyw-oj/.codex/judge-submission-idempotency/evidence/ui-real-result.png)
