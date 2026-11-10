# Canal 题目同步独立代码质量评审

结论：**PASS**。当前评审基线没有未解决的代码正确性阻塞；此前发现的问题及 ES 响应读取超时证据缺口均已处置。本结论用于独立代码质量门禁，项目最终验收由主线程结合完整需求映射和交付条件形成。

## 独立性与范围

- 变更：`canal-question-es-sync`；风险等级：`strict`。
- 评审者：`/root/canal_review`；评审日期：2026-11-10（Asia/Shanghai）。
- 本评审上下文未编写或修改业务实现、部署脚本及测试。工作包括只读代码检查、Canal/MySQL 官方源码核验、提出修复建议，以及复核主线程执行的真实测试与文件快照。本次只写入本报告和配套指纹文件。
- 范围为 37 个生产文件：`questionsync` 全包的 21 个 Java 文件；应用启动入口、管理端题目服务及 ES 实体、两个 bootstrap 配置和模块 POM；用户端两个服务及 ES 实体；公共结果码；开发/生产 Compose；Canal 的配置生成、启动、维护脚本及 README。
- 参考 HEAD：`d274d57ffb21bc3b27eac1808d842cdbaa7922d4`。实际基线包含未提交和未跟踪文件，不能仅用 HEAD 代表本次代码。

## 文件基线

[37 个文件的指纹明细](/Users/lllyw/lyw-oj/openspec/changes/canal-question-es-sync/reviews/independent-code-files.json) 保存了完整文件集合、逐文件 SHA-256、可执行标志及聚合序列化规则。

```text
2f62a433e952585c6652933a9eeb34f28d50ed23f93ecff5300d01026586050f
```

聚合方法：按相对路径排序，将每项 `executable`、`path`、`sha256` 使用键排序、无多余空白的 UTF-8 JSON 序列化，对所得字节计算 SHA-256。最终落盘前已重新读取全部文件并核对，37 项均未变化。

`unit-13`、`it13-probe-1`、`it13-probe-2` 和 `it13-package-1` 的源码快照，与当前 31 个应用/配置输入全部匹配，无差异。其余 6 个部署文件不在 Maven 源码快照中，已单独纳入本评审指纹。

## 发现与处置

| 检查边界 | 最终实现与复核结果 |
|---|---|
| DDL 与完整行 | 在库表 header 过滤前识别 DDL，覆盖重命名离开目标表、多表 DROP 中目标非首项；拒绝 STATEMENT 空行，以及 UPDATE/DELETE 不完整 before 图像和不完整 after 图像。 |
| ES 写入与确认 | 完整 bulk index/delete，逐项检查响应；显式 NULL 清除旧值；删除不存在文档成功。整批写入成功后才 ACK，失败保留重放。 |
| 共享位点与初始状态 | 区分合法的首次省略/null cursor、既有元数据丢失、目录缺失和 I/O/权限故障。已建立游标不能回退到初始 P；ROW/FULL 和日志范围检查补充 TCP 不透传 parser 异常的缺口。 |
| 起点恢复 | 新基线使用 P 和源时间 T；保留旧 state，并从新 state 启动。已纳入基线的旧 DDL 不再因补时间戳而重放，新 DDL 仍阻塞。 |
| 建连与关闭 | 建连/DNS 设置可取消总时限；`openingOwner` 代际校验阻止吞掉中断后晚到的工厂继续连接/订阅；消费和对账退出后才执行关闭回调。 |
| 对账与资源 | 候选先等待源位点屏障，期间变动 ID 作废；增量和修复同线程写入；查询失败不生成误删除；空库保护仍统计差异；PIT 为 10 分钟，候选等待超过 5 分钟取消并释放。 |
| 业务与维护边界 | 管理端去除 ES 双写；用户端详情及判题快照读 MySQL；两端关闭自动建索引。维护入口只导入必要设施，不加载演示表、普通控制器和 Web/JWT 依赖。 |
| 部署与操作说明 | 固化 TSDB 系统库黑名单、Canal Perl 正则、注册密码字节上限、密码日志抑制及 HEAD 404 处理；说明先启动 Server 再 check、使用新 state 恢复、单活动消费者及启动配置语义。 |
| ES 读取超时证据 | 增加测试代理：先将 bulk 转发真实 ES 并取得 HTTP 200，再延迟响应，产生实际 SocketTimeoutException；验证失败批次未 ACK，重放后追平后续更新。 |

未解决的 P0/P1/P2 阻塞：0。

## 实际证据

以下测试由主线程执行并保留原始结果，本评审独立核对日志、JUnit XML、场景记录和相关源码快照。

| 证据 | 实际结果 |
|---|---|
| [unit-13 命令记录](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/evidence/unit-13-command.json)及对应 JUnit XML | JDK 17 Maven reactor test，退出码 0；56 条记录中 **53 项实际执行通过、3 个真实集成入口按条件跳过**，0 failure、0 error。跳过项未计为执行通过。 |
| [it13 场景与命令记录](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/integration/it13/run.json) | 独立一轮全量链路，退出码 0，**46 项检查记录全部通过**，无失败项。 |
| [it13-friend-real 命令记录](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/evidence/it13-friend-real-command.json)及对应 JUnit XML | **14 项执行通过、0 跳过**；其中 2 项是真实 MySQL 测试，其余 12 项为相关回归，不能把 14 项全部表述成真实数据库测试。 |
| [最终 JAR 构建记录](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/evidence/it13-package-1-command.json) | 退出码 0；31 个应用输入与本评审基线匹配。 |
| 完整维护入口 [check 日志](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/integration/it13/evidence/command-0169.log)、[repair 日志](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/integration/it13/evidence/command-0171.log) | 实际执行 maintain.sh → JAR → 最小 Spring 上下文，均输出 COMPLETE 并退出 0，保留已有索引。 |
| 本评审执行的 `git diff --check` | 退出码 0。 |

集成环境为 MySQL 8.0.40、Canal 1.1.8、ES/IK 8.5.3。检查包括保留索引基线、首次空闲 32 秒、增删改与主键变化、无增量题目查询、故障重放、ES 停机业务读取、Canal 容器重建、ACK 前真实进程终止、DDL/非法 binlog、P+T 恢复、位点和映射故障、对账及完整维护入口。

正常新增、更新、删除的 `_search` 可见性记录约为 **0.91～1.08 秒**；未强制 refresh。该结果来自本轮无积压的隔离场景，不作为生产负载测试结论。

请求超时证据已核对：[真实 ES 响应记录](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/integration/it13/probe/timeout-backend-applied.json)为批次 4、HTTP 200；[探针日志](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/evidence/it13-probe-2.log)记录实际 `SocketTimeoutException`。超时耗时 **5137 毫秒**，此前 ACK 为 3，失败批次为 4，最终 ACK 为 5；超时期间生成的新 UPDATE 最终可见，正常增量题目查询计数仍为 0。

部分写入和 ACK 不确定通过真实存储外围的测试适配器注入；逐项 bulk 响应异常另有 HTTP 测试。响应读取超时使用真实 ES 前的测试代理，不是直接抛出普通 IOException。既往 it12 的首次失败及补验记录保持原样，最终全量结果采用 it13，未将失败退出码改写为通过。

## 剩余限制

- 单活动消费者、外部导入和多实例停写由部署过程保证，不提供自动选主。
- 至少一次重放可能短暂覆盖成旧值；没有增加源版本列或删除墓碑。
- 无法可靠归属的 QUERY DDL 保守阻塞；DDL、失效位点及映射问题进入人工维护。
- `canal.sync.*` 按启动配置使用，修改后应停止并重启指定实例；热更新 enabled=false 不代表线程已退出。
- 生产环境未连接、部署或重启。该独立代码质量结论适用于本报告指纹及隔离验证基线；生产操作授权、项目最终验收与资源清理由主线程按项目流程处理。

