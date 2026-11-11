# Design

## Context

参见 proposal.md。`QuestionEsSyncRunner.loop()` 在 `tick()` 没有工作时读取 `SyncProperties.idleMillis` 休眠；Java 默认值当前为 200。开发、生产 bootstrap 及部署 Compose 均没有显式设置该字段。

## Goals / Non-Goals

仅调整默认空闲等待及维护说明。保留现有 TCP 连接复用、单线程、批量消费、ACK、故障退避、对账及停机逻辑，不引入固定频率调度器。

## Decisions

- 将 Java 默认值改为 1000，维护文档注明单位及仅空闲时等待。无需在多个 bootstrap 文件重复声明默认值，避免配置漂移。
- 保留外部配置优先级。Nacos 或环境已有显式覆盖时，应按现有部署方式自行统一配置并重启指定实例，本轮不修改运行环境。
- 本变更作为 fast 参数跟进保留原 `canal-question-es-sync` 的历史规划和证据；原版本证据不充当本次修改后的验证。
- 运行现有 `SyncConfigurationTest`、`QuestionEsSyncRunnerTest` 和 `TcpCanalSourceTest`，检查配置加载、确认顺序、回滚与停止边界。默认值和休眠分支使用聚焦代码核对，不为常量新增持久化测试。

## Risks / Trade-offs

空闲期间新消息可能等待更久；1000 毫秒是空闲等待参数，不是端到端同步延迟保证。积压处理速度不增加人为一秒停顿。需要回退时恢复为 200 或显式配置旧值，并按既有方式重启；本轮不执行部署或恢复操作。
