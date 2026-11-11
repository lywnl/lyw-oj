# 独立规划评审

- 风险等级：strict。
- 结论：PASS。
- 评审者：`/root/xxl_reconciliation_review`，本次独立子代理；未撰写该规划、未修改业务代码，未参与此前本地 Cron 版本实施。
- 评审时间：2026-11-11T10:44:24.805811+00:00。
- 项目及变更根：`/Users/lllyw/lyw-oj`，`openspec/changes/canal-reconciliation-daily-two-am`。
- 核验方式：machine。Git HEAD 为 `6f780098c1b75097bddad79499193686bce990d2`，已有未提交修改按当前文件核对，不以 HEAD 代替实际基线。

## 检查范围及结论依据

1. 用户确认的目标已被 proposal、specs、design、tasks 承接：复用现有 oj-job，原线程分页，真实结果回传，默认关闭及旧间隔迁移，隔离调度验收。没有新增调度中心、任务库、锁或对账线程。
2. 内部入口具备独立凭据和网关禁止要求；明确不能依赖当前空登录令牌直接放行的 TokenInterceptor。配置明确目标地址，关闭重定向、重试及请求日志，避免退化为负载均衡或把凭据发送到重定向目标。
3. 单槽包含等待、运行、取消及清理阶段，禁止 HTTP 线程执行 Reconciler；受控取消与真正停机区分。重连、BLOCKED、停止和清理异常均有 Future 收尾及隔离要求，不留下静默成功或重启本轮的分支。
4. 预算使用单调时钟，默认 600 秒服务端、660 秒读取、720 秒平台；已有单页候选 300 秒保留。规划准确限定为合作取消，没有把上游断开描述成后台已停止。
5. 成功仅表示按既有位点及变化候选协议完成一轮；丢响应可能后台已完成，失败重试为 0、人工核查后处理，不承诺恰好一次。
6. 测试包含权限、状态、竞态及真实 Admin→执行器→Handler→系统入口→Runner→Admin 回调；对账读写是替身，未冒充真实业务 MySQL/Canal/ES 集成。现有 65 项证据被保留为历史，新任务均未勾选。
7. 部署前关闭新任务、移除旧配置、核对唯一消费者和 JVM 时区、手动验证、再启用；回退先停任务并等待结束。真实环境操作明确另行授权。

## 阻塞项

无。本次 PASS 仅为规划可实施，不代表代码或测试已通过；主执行者仍须完成聚合报告、record-plan/check-plan 和首次 start-apply。

## 实施交接与非阻塞提醒

以下是落实现有设计时必须验证的代码落点，不新增需求：

- `QuestionReconciler.java:50` 当前 `close()` 吞掉 `index.closeCursor()` 异常。任务 2.2 的清理失败验收必须实际观察该失败，不能继续调用原方法后认定资源已清理；保留隔离与失败响应。
- `QuestionEsSyncRunner.java:73` 的外层 `InterruptedException` 会结束整个消费循环。任务 2.2 须在对账边界处理合作取消，且完成/清理失败不能绕过 finally 中的生命周期回调和 Future 收尾。
- 请求接收与停止/断连状态转换须原子检查，覆盖“检查 RUNNING 后停止”和“清理结束前第二次请求”等竞态；清理成功前不允许新请求继承旧游标或计数。
- 网关禁止不能仅比较原始字符串；系统端鉴权也不能因编码、分号、路径重写或 servlet 映射差异被绕过。真实 HTTP 测试需确认拒绝发生在任务提交之前，普通用户与管理员登录都不能代替内部凭据。
- Feign 配置必须只作用专用客户端，默认关闭时不能注册失败的客户端或影响已有竞赛任务。启用校验的异常消息也不得回显目标中的凭据或内部令牌。
- 10 分钟预算若被调整，客户端和平台超时须同时核对；部署双方参数一致性是维护步骤的一部分。固定地址必须属于唯一活动消费者，不能用普通多实例 Service 地址冒充此约束。

## 执行的检查

- 只读检查实际 AGENTS、评审技能、共享工作流/评审及证据规则、规划产物、Runner/Reconciler、公共鉴权、网关、维护上下文与已有 XxlJobConfig。
- `openspec status --change canal-reconciliation-daily-two-am --json`：退出码 0；根正确、schema 为 spec-driven，四份规划产物齐备。
- `openspec validate canal-reconciliation-daily-two-am --strict`：退出码 0；仅作为格式校验，不替代上述语义审查。
- 未启动容器、调度中心或业务服务；未运行业务测试；未修改代码、规划、planning-review.json 或实施开始记录。

## 评审基线

下列哈希由评审时实际文件计算，用于识别独立评审对应的内容；正式 machine 基线由主执行者完成聚合评审后记录。

| 文件 | SHA-256 |
| --- | --- |
| `.codex/plans/canal-reconciliation-xxl-job-20261111.md` | `d4fd8a5de9497ef369826f2fb5f430eb6a126ccec55ae643655dc8db71635873` |
| `openspec/changes/canal-reconciliation-daily-two-am/.openspec.yaml` | `58c34cf569a4f69f5a29b14e48609d876cbc7708a2efcf4ccf9b0cd171a7ec76` |
| `openspec/changes/canal-reconciliation-daily-two-am/proposal.md` | `3d1253450279d4864f379d5eaf91b29c869ef240c18e2d572eae4f0ba1cbdcdd` |
| `openspec/changes/canal-reconciliation-daily-two-am/specs/question/index-sync/spec.md` | `f147f871cdebf288a4862c1e554eef0618085f67ee880d727156569063eed69e` |
| `openspec/changes/canal-reconciliation-daily-two-am/design.md` | `e818a5e297374d1071a8d2db471d6b6e48ccdf08120a27244d1c3e3ecf318564` |
| `openspec/changes/canal-reconciliation-daily-two-am/tasks.md` | `e57a526d20a26007cffa66b768822174dba968a3354b0eded329fc57a5ca09c6` |
