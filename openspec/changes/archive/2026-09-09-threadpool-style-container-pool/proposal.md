## Why

当前 `oj-judge` 的容器池由请求线程直接从 `BlockingQueue<String>` 获取容器并执行判题，任务调度、容器占用和生命周期管理彼此耦合，难以稳定控制排队、扩容、拒绝和故障恢复。需要将其改造成类似 Java `ThreadPoolExecutor` 的任务执行模型，使判题任务先进入有界队列，由绑定 Docker 容器的 Worker 消费，同时保证核心容器从服务启动后常驻。

## What Changes

- 引入有界 `JudgeTask` 任务队列，提交方不再直接竞争容器。
- 引入与 Docker 容器一一绑定的 `ContainerWorker`，由 Worker 循环消费任务并执行判题。
- 按核心数量、任务队列容量和最大数量实现线程池式扩容规则。
- 保留核心 Worker/容器，临时 Worker/容器按空闲存活时间回收。
- 增加任务拒绝、容器健康检查、异常隔离和核心容器故障补建行为。
- 调整同步判题和 RabbitMQ 异步判题的提交、结果等待及消息确认边界，保持现有业务接口语义兼容。
- 统一任务执行后的用户代码清理和容器复用判断。

## Capabilities

### New Capabilities

- `judge/container-pool`: 提供带有界任务队列、核心/临时 Worker、扩容缩容、拒绝策略和容器故障恢复的判题容器执行池。

### Modified Capabilities

无。当前 `openspec/specs/` 下没有可修改的既有能力规范。

## Impact

- 主要代码：`oj-modules/oj-judge` 下的 `DockerSandBoxPool`、`DockerSandBoxPoolConfig`、`SandboxPoolServiceImpl`、`JudgeConsumer` 及判题执行相关类。
- 跨服务调用：`oj-friend` 的同步 Feign 判题和 RabbitMQ 异步判题提交路径需要适配新的任务提交与结果等待行为。
- 基础设施：影响 Docker 容器创建/启动/销毁、RabbitMQ 未确认消息和 Redis 判题状态；不新增数据库表或外部服务。
- 配置：新增或调整容器池核心数量、最大数量、任务队列容量和临时 Worker 空闲存活时间等 Nacos 配置。
- 来源：本次对话中已确认的容器池改造需求；现有实现见 `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPool.java`、`SandboxPoolServiceImpl.java`、`JudgeConsumer.java` 和 `DockerSandBoxPoolConfig.java`；工程约束见项目 `AGENTS.md`、`openspec/config.yaml` 及共享 OpenSpec 工作流规则。
