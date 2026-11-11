# PIDs 默认值 64 独立代码评审

- 结论：**PASS**，当前改动及已完成验证没有未解决的阻塞缺陷。
- 等级/核验：strict / machine；评审者：独立子代理 `/root/sandbox_review`，未修改业务代码和测试，仅编写本报告。
- 已核对有效 check-plan 及实施开始 `2026-11-10T09:12:48.380039+00:00`。范围遵循用户“收紧到64”的确认。

相对 `reviews/history/pre-pids64-20261110/reports/verification.json` 的代码基线，**57 个模块文件中 53 个内容和可执行位一致，仅 4 个文件修改，无新增或删除**。未变部分复用该历史独立代码评审。

已核实 Spring 的 `@Value("${sandbox.limit.pids:64}")` 与 `SandboxLimits.defaults()` 的 `64L` 一致，显式配置仍可覆盖。默认单元断言、HostConfig/cgroup 和两种线程创建断言同步为 64；真实编译/输出限额夹具取 `SandboxLimits.defaults().pidsLimit()`，没有混用默认 128。合法自定义参数 128 保留；`invalidForDocker` 的 128 属于内存 1 字节的创建失败场景，不作为默认 64 的运行证据。其他防护实现未改动。

## 本轮真实证据

保留入口：[PIDs64 验证报告](/Users/lllyw/lyw-oj/.codex/reports/sandbox-resource-hardening-20261110-091015-cd05e376-pids64.md)。已逐字核对两批日志、命令、来源快照、全部 JUnit 和 Docker 事件，共 19 份原始材料已嵌入报告。

| 批次 | 测试数 | 失败 | 错误 | 跳过 | 命令退出码 |
|---|---:|---:|---:|---:|---:|
| unit-full-01 | 49 | 0 | 0 | 0 | 0 |
| docker-full-01 | 17 | 0 | 0 | 0 | 0 |

两批 **325 项源码快照完全一致并匹配当前工作区**，快照 SHA-256 均为 `6653f99d6605425708bf20af28b62761c96113f9c18be2d8286db76c298a14c7`。本轮没有失败批次，不借用此前 128 的测试通过结论。

已核实 91 条 Docker 事件：HostConfig PidsLimit=64，内核 pids.max=64；捕获与不捕获线程创建错误的两段程序均记录 `limit=64,current=64,events=max 4`。真实测试确认正常 Java 编译运行、超时删除旧容器、后续正常提交、回收隔离和跨 Worker 行为仍通过。夹具清理记录无 remaining；本轮构建容器、镜像和运行目录的最终清理由主流程另行核对。`git diff --check` 实际退出 0。

结论限于本轮 JDK 17/Linux ARM64 环境及已覆盖程序，不宣称 64 适合所有 Java 程序或已动态调整既有业务容器。接口限流、内存统计及后台子进程完整追踪等边界继续引用历史报告。最终报告保留，资源清理及正式验收按主流程完成。

## 修改文件 SHA-256

| 文件（相对授权根） | SHA-256 |
|---|---|
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPoolConfig.java` | `82a9f2b837f0c77f404a0c97574a5f2f07d44e9485a22763a428bdbf561d73a0` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/SandboxLimits.java` | `7b243b93e2063f3340f58a32bafd9b6c4e9987c44e4c3174ec4ba7054d1e458c` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxLimitsTest.java` | `ee7f9dff8e6053687f8a9615f6a2a85f806ada3104735669e715d0e821b82edd` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxSafetyIntegrationTest.java` | `7ee24e074f9c2d72a8a057fc583b67fb5d7d5733c61742217f585e6f23f82569` |

评审时间（UTC）：2026-11-10T09:18:50.167802+00:00。
