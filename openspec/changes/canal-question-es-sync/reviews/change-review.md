# 优化规划评审

- 日期：2026-11-10（Asia/Shanghai）。
- 风险等级：strict；核验方式：machine，单项目根、普通 UTF-8 文件。
- 结论：PASS。
- 评审者：主执行者 /root 聚合；独立评审者 /root/canal_review 未撰写本轮规划或实现，见 optimization-independent-planning-review.md。
- 基线：当前 .openspec.yaml、proposal、specs、design、tasks，以及原执行计划、优化计划、AGENTS.md 和采用的 OpenSpec 共享规则。x-workflow-profile / x-verification-mode 沿用当前 CLI 的扩展字段格式，语义为 strict / machine。

## 范围与授权

用户本轮明确要求“实施计划”，覆盖三项优化、必要规划评审、编码、隔离真实验证和实现验收。规划文档中的“仅规划”是上轮交付时的状态说明，不限制本轮明确授权。生产账号、真实数据、生产启停/迁移/发布、Git 提交推送及归档不在本次范围内。

本轮任务为 5.1～5.4：生产正式目录、ES 生命周期边界校验、维护锁清理，以及相应回归和隔离迁移/排空演练。1.1～4.2 的完成状态保留为历史。7 项需求、16 个场景覆盖原有保证和本轮优化，CLI strict 结构校验通过；结构通过不代替本报告的语义检查。

保留自动修复、位点屏障、候选失效、空库保护、空事务配置、SourcePositionGuard、P+T 和连接超时；反向对账只查 ID 暂不纳入，不迁移到 XXL-JOB。详情及判题继续读 MySQL，搜索读 ES，不恢复请求双写。

## 发现及处置

1. 原维护设计只写“核实在途结束”，缺少当前系统可执行的证明路径。已补充受控请求终态及已识别 MySQL 会话/事务核对；不可观测时在获准短维护窗口停相关写题进程，以进程退出及数据库事务结束/回滚为证。5.4 要求保留真实未提交写事务验证，不能拿 3503 或固定 sleep 当作排空。此项已由独立评审复核关闭。
2. reset 现有实现包含 PIT 关闭，不能因“仅重置状态”的措辞删掉资源清理。设计已明确只迁出 validate，保留 closeCursor、游标和候选重置。此项关闭。
3. 校验减少后，索引运行中被删除要依赖真实 bulk 逐项错误，不能继续只测试 HEAD 失败。现有 writer 检查 error 与状态，可以区分文档不存在的 delete 404 与索引缺失错误；本轮单元及真实集成须验证 index/delete 两类操作，不确认失败批次或隐式建索引。
4. 修改正式目录默认值不等于迁移完成。设计明确两处 state 挂载、完整复制与内容/权限核对；回退新状态已推进时须携最新有效 state，不直接拿旧备份覆盖。要求隔离真实容器重建、迁移和回退证据。

当前没有未解决的阻塞项。校验时点变化已明确写入规格；元数据异常在约定边界或实际请求报错时识别，不承诺稳态逐批验证映射。

## 实施交接

Pre-Code Context Gate: PASS。已核对 Java 17、Spring Boot 3.0.1、Maven 模块和全部修改调用方；现有 Docker 及 MySQL 8.0.40、Canal 1.1.8、ES/IK 8.5.3、JDK17 构建环境可用，未启动生产或既有业务容器。当前工作分支为 master，保留既有未提交内容，不擅自提交或清理。

使用现有 deploy/canal/tests/run_maven.py、integration.py 和 Python 配置测试入口，所有临时产物、源码快照、日志和隔离数据在 .codex/canal-question-es-sync。业务改动前须由 evidence.py 登记并核验新的实施基线。

验收至少覆盖：稳态请求数量与重连/新轮次校验；部分失败和 ACK 不确定重放；运行中索引丢失；自动修复及 DDL/TRUNCATE/空库/查询失败保护；在线维护门禁与真实在途事务结束；完整维护 JAR；正式目录迁移、回退和容器重建续传；正常无积压 _search 五秒可见及增量无题目查询。

原评审/验收文件已经逐字保存于 reviews/history/pre-optimization 和 reports/history/pre-optimization，原始运行证据未覆盖。历史 PASS 不代表本轮实现验收通过；本报告仅交接当前已获用户批准的实现范围，最终仍需独立代码评审和真实证据。
