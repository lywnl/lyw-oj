# 实现验收

- 风险等级：strict；核验方式：machine。
- 结论：Ready for Archive。仅表示当前实现及约定验收满足，不表示已归档、发布或开启线上任务。
- 基线：当前规划评审及已登记 start-apply；独立代码结论见 independent-code-review.md，R1–R4 已关闭。

## 证据与覆盖

详细需求/场景/任务到实现、测试和证据的映射统一维护于 verification-manifest.json。最终 a6：89 项 Java 测试全部通过，9 项部署脚本测试通过，真实 Admin 调度 6 组场景通过；359 个构建输入与当前源码一致，git diff --check 通过。

实际命令、退出码与时间见清单及原始运行记录。对账数据为可控替身；真实 Admin/MySQL/执行器/Feign/HTTP/Runner 已执行。未连接业务 MySQL/Canal/ES 做真实数据验证，没有执行线上启用或 Nacos 配置修改。

## 异常与独立评审

独立评审和失败运行记录保留；前期测试隔离曾触发默认 Nacos 初始化并连接失败，后续纯 Web 上下文及 internal 网络完成隔离。Docker ID 解析、测试字符集及绑定文件交接问题已修复。见 .codex/reports/canal-reconciliation-xxl-job-20261111.md，不能把历史失败改写成从未发生。

## 清理及限制

本轮测试资源已按记录 ID 清理，保留报告、必要证据及既有缓存/代码。默认 bridge ID 的外部变化未确认原因且未尝试恢复，其他原有网络一致；本轮测试网络无残留。此事实已列于 cleanup.json 和最终报告，不声称完全恢复整个 Docker 基线。

功能默认关闭，部署需按维护说明配置两端凭据及唯一目标、移除旧间隔、核对 Admin 时区和执行时间，人工验证后开启。回退先停任务并排空。未授权提交、推送、归档或发布。
