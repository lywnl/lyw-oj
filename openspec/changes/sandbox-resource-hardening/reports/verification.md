# PIDs 默认上限 64 实现验收

结论：**Ready for Archive**。用户要求的默认值收紧已完成，实际测试、独立评审和本轮资源清理均已通过；本轮没有归档、提交、推送或部署。

## 基线与实施

沿用 sandbox-resource-hardening 的 spec-driven、strict / machine。用户明确要求“收紧到64”，来源为 [PIDs64 确认](/Users/lllyw/lyw-oj/.codex/plans/容器池PIDs64确认.md)。原默认 128 的规划与验收记录已保存在 reviews/history/pre-pids64-20261110，原有报告未修改。

聚焦规划及独立复审通过后，真实重新登记实施入口，开始时间为 `2026-11-10T09:12:48.380039+00:00`。本轮只修改四个模块文件：DockerSandBoxPoolConfig 的 Spring 回退值和 SandboxLimits.defaults() 同步为 64；SandboxLimitsTest 与 SandboxSafetyIntegrationTest 同步默认断言，真实自定义编译/输出限额夹具使用当前默认 PIDs。其他模块文件与历史验收基线一致。

显式 sandbox.limit.pids 配置仍然优先；合法的自定义 128 单元参数未机械替换。真实 invalidForDocker 的内存 1 字节场景仍使用自定义参数验证创建失败，不将其当作默认 64 的运行证据。未修改外部配置服务或既有业务容器。

## 本轮真实证据

详细需求、场景及任务映射见 [verification-manifest.json](/Users/lllyw/lyw-oj/openspec/changes/sandbox-resource-hardening/reports/verification-manifest.json)：5 项需求、20 个场景、13 项任务，共 38 个 verified 条目。

原始材料统一保留在 [PIDs64 测试报告](/Users/lllyw/lyw-oj/.codex/reports/sandbox-resource-hardening-20261110-091015-cd05e376-pids64.md)，包括全部命令、源码快照、日志、JUnit、Docker 事件、环境和清理原始记录。引用不依赖已删除的运行目录。

| 批次 | 验证范围 | 测试数 | 失败/错误/跳过 | 退出码 |
|---|---|---:|---|---:|
| unit-full-01 | 默认与非法配置、文件/输出/执行、生命周期、任务、容量、幂等及消费者；包含真实 TCP 传输故障 | 49 | 0/0/0 | 0 |
| docker-full-01 | 真实 Java 编译/运行、默认 64、线程/输出/超时防护、回收和后续提交 | 17 | 0/0/0 | 0 |

两批 325 项源码快照一致并匹配当前工作区，没有复用 128 的旧测试结果。prepare-01 是修改前准备依赖、跳过测试的编译记录，不计为当前通过证据。JDK 17 隔离构建与 git diff 检查通过。

实际 Docker HostConfig 的 PidsLimit 与内核 pids.max 均为 64；两类持续创建线程程序均记录 `limit=64/current=64/events=max 4`。正常 A+B 六条用例、中文/内部类编译、普通错误与栈溢出、长题时限、真实 javac 超时、循环/死锁/用户线程、输出控制、回收后正常提交均通过。未以 OOM 文案或创建参数替代内核拒绝证据。

## 独立评审及清理

[独立代码评审](/Users/lllyw/lyw-oj/openspec/changes/sandbox-resource-hardening/reviews/independent-code-review.md) 由未编写实现的 /root/sandbox_review 完成，结论 PASS。核对全部 57 个模块文件，仅 4 项变化、53 项保持一致；真实证据已逐字嵌入保留报告。无未解决的代码阻塞。

测试夹具已回收全部沙箱容器。证据转存后删除了 3 个已退出的构建容器、本轮独占新增的 Zulu 镜像、隔离源码/缓存/构建/运行/日志目录及临时指针，无本轮独占资源残留。原有 8 个容器 ID/名称/状态、10 个镜像和 4 份报告哈希与基线一致。按明确 ID、标签及挂载归属清理，没有全局 prune。

实现、测试源码、授权材料、OpenSpec 交付文件及全部报告保留；没有用户明确指令不删除报告。

## 边界

此结论证明本轮覆盖的 Java 程序可在默认 64 下运行，未测量正常编译的完整 PIDs 峰值，不把 64 宣称为所有 Java 程序的最佳值。实际测试在 JDK 17 / Linux ARM64 / Docker Desktop 下完成，没有验证其他架构或生产部署。

原有范围继续有效：不加限流，不修改非池化链路、内存统计口径或完整后台子进程隔离；没有启动 HTTP/数据库/MQ 全链路测试，也没有验证数据库大输出存储边界或高并发性能。相关失败注入、传输和文件处理的能力边界继续引用保留的前序验收。

当前默认值需要服务按新代码配置创建容器才生效；已有外部显式配置不会被回退值覆盖。后续归档、提交、发布或环境切换需要相应授权。
