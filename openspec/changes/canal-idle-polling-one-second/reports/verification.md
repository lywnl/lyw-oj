# 验收记录

等级：fast；核验方式：machine；本上下文普通自查，无独立评审要求。规划基线为本变更当前规划及 `reviews/planning-review.json`，实施入口已核验。实现基于 Git `6f780098c1b75097bddad79499193686bce990d2` 加本次两个文件的修改，实际测试使用修改后的隔离副本。

| 任务与场景 | 实现与核对 | 实际证据 |
| --- | --- | --- |
| 1.1：默认空闲一秒 | `SyncProperties.java:24` 为 `idleMillis = 1000`，`deploy/canal/README.md:56` 同步说明；源码 SHA-256 与实际编译快照一致。 | `idle-one-second-20261111-1436-checks.json` 的默认值与快照核对 |
| 1.1：有工作连续处理、配置覆盖保留 | `QuestionEsSyncRunner`、`TcpCanalSource`、现有测试及开发/生产 bootstrap 与 HEAD 相同；原有 `if (!work)` 等待条件及 Spring 配置绑定保留。 | checks 中 `git diff --exit-code`；配置绑定及消费流程测试 |
| 1.2：回归与清理 | JDK 17.0.15、Maven 3.9.9；三个现有测试类共 15 项，失败 0、错误 0、跳过 0。`git diff --check` 通过，测试容器及本轮构建副本已删除。 | 本轮 Maven 日志、JUnit XML、命令记录和 checks |

执行时间：2026-11-11 14:36:00 至 14:36:14（Asia/Shanghai）。命令从项目根执行：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 deploy/canal/tests/run_maven.py idle-one-second-20261111-1436 -pl oj-modules/oj-system -am -Dtest=SyncConfigurationTest,QuestionEsSyncRunnerTest,TcpCanalSourceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

构建镜像为已有的 `maven:3.9.9-eclipse-temurin-17`，实际 ID：`sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc`。未拉取新镜像；已有 Maven 缓存与镜像保留。

原始证据位于 `.codex/canal-question-es-sync/evidence/`：

- [测试日志](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/evidence/idle-one-second-20261111-1436.log)
- [命令、时间与退出码](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/evidence/idle-one-second-20261111-1436-command.json)
- [核对与清理记录](/Users/lllyw/lyw-oj/.codex/canal-question-es-sync/evidence/idle-one-second-20261111-1436-checks.json)
- 源码快照：`idle-one-second-20261111-1436-sources.json`；JUnit：`idle-one-second-20261111-1436/oj-modules/oj-system/TEST-*.xml`。

清理按实际容器 ID `d974cdad1cc26772067ba6cdc270e8f8dfe840e85e49e58fa348283a58682bb0` 执行，确认已经退出并删除；本轮 `builds/idle-one-second-20261111-1436` 已删除。报告和必要原始证据保留。

本轮没有真实 MySQL/Canal/ES 联调、服务器压力测试或运行环境变更；测试使用可控替身，默认值与等待条件为代码核对，不声明测得端到端一秒延迟。现有外部配置覆盖仍可能优先；部署新版并重启指定同步实例后默认值才生效，本轮不部署、不提交。

结论：Ready for Archive。所有本次必需任务和聚焦验证完成，无未解决阻塞；未获归档指令，不归档。
