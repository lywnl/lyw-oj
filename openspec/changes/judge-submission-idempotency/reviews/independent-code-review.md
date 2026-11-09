# 独立代码质量评审

- 日期：2026-11-09，Asia/Shanghai。
- 结论：**PASS**。本次检查范围内没有剩余 P1/P2 问题，前一轮发现的两处前端竞态已由主执行上下文修复并经独立复核关闭。
- 风险等级：strict；核验方式：manual，覆盖两个已授权源码根。
- 评审者：独立子代理 `/root/planning_review`。本评审者没有修改业务代码或测试，只进行了只读检查、证据复核并编写本报告。
- 本报告是独立代码质量结论；最终验收映射、补充页面场景、资源清理及是否可归档仍由主执行上下文核对，不表示已部署或完成生产全链路验证。

## 基线与检查范围

`B=/Users/lllyw/lyw-oj`，`F=/Users/lllyw/oj-fe-c`。规划依照本变更的 proposal、specs、design、tasks 与已通过的独立规划评审。`x-workflow-profile: strict`、`x-verification-mode: manual` 继续分别映射项目的严格等级和跨根人工核验方式。

已检查两端当前 Git 差异和新增文件，重点包括：固定提交 ID 与回执、统一缓存模型、StateCacheService 与 Lua、UserSubmitStore 事务及冲突复用、普通消费者和死信生命周期、生产者失败回调、用户限定的结果 SQL、前端请求封装、Answer 页面与 judgeResultPoller。

实施开始记录存在于 `reviews/manual-planning-snapshot.json`，`implementation_started=2026-11-08T15:45:01.050582+00:00`。本次没有重写规划评审或补造实施入口记录。

已逐一读取并比对 `B:.codex/judge-submission-idempotency/evidence/final-source-files.json` 的 192 项内容哈希：**全部与当前实际文件一致**。该清单 SHA-256 为：

```text
399faf86f7a621b637148812d86190bc84d231b92058653f575b79e3151c7d94
```

源码清单外的已修改构建清单另行核对如下，最终 manual 验收清单也必须覆盖它们：

| 输入 | SHA-256 |
|---|---|
| `B:oj-modules/oj-friend/pom.xml` | `577138b20c055ea9bfdbd3c701fff860c55c565548ec25b8720a643dc4386fb0` |
| `B:oj-modules/oj-judge/pom.xml` | `3a07df13a53feaf1b6eb51726aa5ca6e57189742d316b1d74c6505eaf1bddafe` |
| `F:package.json` | `421968d14bee510dbcf117664a576df4e0a48262059361a8beb8209c3bbdae88` |

## 已关闭的评审发现

| 发现 | 影响 | 主执行上下文的修复与独立复核 |
|---|---|---|
| P2：提交重置了题目详情使用的代次 | 首屏详情尚未返回时点击提交，会使详情响应被丢弃，题面和默认代码可能不再加载 | 将 `contextVersion` 与 `submissionVersion` 分开，并增加题目加载期间的按钮与函数入口守卫。复核了首屏加载、提交回执及详情的各自版本判断；Chrome 审计记录首屏加载期间禁止提交。 |
| P2：切题在途时仍可提交旧题 | 上一题/下一题请求尚未返回时提交，可能将旧题回执和结果显示在新题页面 | `questionLoading` 覆盖导航请求及后续详情加载；`submitQuestion` 入口同步拒绝加载期间提交；仅当前题目代次的 finally 可解除加载状态。复核了切题、路由变化、离开和旧回调判断；Chrome 审计与请求记录显示导航期间没有新增提交、旧查询停止。 |

上述代码修复均由 `/root` 主执行上下文完成，本评审者只提出问题并复核。主执行上下文另外将当前 Spring AMQP 3.0.0 不支持的配置 API 调整为 `setShutdownTimeout(1000L)` 与 `setForceCloseChannel(true)`；当前代码和真实死信保留/恢复测试已覆盖该实现。

## 核心实现结论

- **数据库幂等**：`UserSubmitStore` 使用 REQUIRES_NEW 短事务，插入结束后才返回；DuplicateKeyException 在失败事务退出后重新读取并验证用户、题目、竞赛、语言和源码身份，使用数据库获胜记录。两次原始计算结果不同也不能让缓存跟随失败插入方。数据库异常不被当成无记录或重复成功。
- **系统异常边界**：判题服务排除空结果、UNKNOWN_FAILED、RUNNING、SYSTEM_BUSY 和基础设施异常；用户真实失败保留为 JUDGED/pass=0。繁忙由明确的 JudgeSystemException 状态识别，已不依赖 exeMessage 文案比较。
- **缓存原子性与序列化**：共享服务用 StringRedisTemplate 传入原始 JSON 与普通 TTL 字符串；Lua 仅解析状态，保存原始 JSON，避免重编码大整数 ID。等待/错误不能覆盖 JUDGED，同级更新不续期，缓存回填异常不会丢失已取得的数据库响应。
- **消息确认**：ACK/NACK 与业务重试分离，确认失败只关闭故障通道，不回到判题重试。死信不写提交表；已有真实/同级缓存导致的无操作也正常 ACK。三次缓存失败后只停止专属监听，未确认消息留在 Broker。
- **生产者**：重发保留同一业务 ID，实际消息 ID 与提交 ID 明确分开；mandatory、publisher returns 和 CORRELATED 已启用。失败及退回回调共用受保护的缓存入口，不删除真实缓存。
- **查询隔离**：缓存同时验证用户与提交身份，生产 SQL 使用 submit_id 与 user_id 条件。普通缓存读取正常时不查数据库；显式核对及 Redis 异常才回源，查不到且缓存不可确认时返回查询错误。
- **前端生命周期**：SYSTEM_ERROR 不停止或重置等待窗口；普通请求串行，55 秒阶段取消用于切换最后核对，60 秒总截止禁止迟到响应继续更新。提交、题目、轮询代次分别隔离；取消不当作用户可见的查询失败；错误回执保留 submitId，重新查询不再 POST。

## 已核验的测试与页面证据

本评审者读取了测试源码、最终命令记录、日志、JUnit XML、页面审计、请求记录和两张状态截图，没有再次执行业务测试或操作 Chrome。

下表中省略目录的证据文件名均位于 `B:.codex/judge-submission-idempotency/evidence/`。

| 验证 | 复核结果 | 稳定证据路径（相对 B） |
|---|---|---|
| JDK 17 后端选定回归与集成测试 | friend 14 + judge 28 = **42**；独立汇总 JUnit XML 确认 failures=0、errors=0、skipped=0，BUILD SUCCESS，命令 exit_code=0 | `.codex/judge-submission-idempotency/evidence/final-backend.log`、`final-backend-command.json`、`backend-test-summary.json`、`junit/` |
| 真实存储与消息验证 | 真实 MySQL 主键竞争/回滚复用、生产查询 XML 的用户过滤、Redis Lua/TTL/大 ID 与自动配置注册、Broker 在数据库提交后未 ACK 的重投、死信暂停和恢复均有已通过测试 | 同上，相关 IntegrationTest 测试源码与 XML |
| 前端查询控制器 | **10** 个 Node 测试通过，覆盖系统错误后真实结果、55 秒取消、60 秒截止、手动核对、旧响应和请求超时 | `.codex/judge-submission-idempotency/evidence/frontend-unit.log`、`frontend-unit-command.json` |
| 前端正式构建 | npm/Vite 构建 exit_code=0；存在 chunk-size 提示，不将其冒充本次幂等缺陷或性能验证 | `.codex/judge-submission-idempotency/evidence/frontend-build.log`、`frontend-build-command.json` |
| Chrome 页面 | 审计显示系统错误后继续查询并展示真实结果、原 ID 手动核对、3701 后仅一次 POST、加载期间禁提交、旧查询停止、**55.004 秒且仅一次**最终核对；浏览器错误日志计数为 0 | `.codex/judge-submission-idempotency/evidence/ui-audit.json`、`ui-requests.jsonl`、`ui-browser-logs.json`、`ui-*.png` |
| 差异检查 | 本评审者在两仓库分别执行 git diff --check，均 exit=0 | 本次独立工具调用结果 |

后端证据中的 ERROR 日志含故障注入与非法消息测试，不能把日志级别误当测试失败；JUnit 与构建结果已单独核对。真实沙箱计算在中间件测试中使用受控替身，Chrome 使用真实页面与受控 HTTP API；这些证据不代表生产完整判题链路或沙箱性能测试。

已复核稳定日志的 SHA-256：

| 证据 | SHA-256 |
|---|---|
| `final-backend.log` | `933dc1e843c84ee4a2446fd347e93ce740fa8d92f103a52b004b473a1b355cf8` |
| `frontend-unit.log` | `c0fb3d55de5651161cb833b18d81649f153a261eb10babf72670380b8f824632` |
| `frontend-build.log` | `93e49a79306b2421e6ed6a7dd7a2ddfe13a792a90ba7b22929130d5f2facc24b` |

页面审计和请求记录可能由主执行上下文追加未取得 ID 的提交超时、迟到提交回执等补充场景，本报告只声明上述已读取记录支持的结论；追加证据不改变已复核业务源码，最终汇总应使用最新完整文件。

## 剩余边界

没有未解决的本次代码质量阻塞。按已确认设计，仍不保证沙箱恰好执行一次、数据库回源具有严格 QPS 上限、缓存永久保留或停止查询后任意晚到结果自动刷新。旧数据和排名计算未纳入改造，部署与真实业务数据操作不在本次评审授权范围。

最终验收必须保留本报告及所引用的日志、XML、源码/配置清单；环境清理结果和最后补充页面场景由主执行上下文在验收报告中核对，不能以本报告替代尚未完成的收尾工作。
