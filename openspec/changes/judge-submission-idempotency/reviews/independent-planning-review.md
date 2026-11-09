# 独立规划评审

- 日期：2026-11-08。
- 结论：**PASS**，未发现阻塞当前实现的需求冲突或缺失决策。
- 风险等级：strict；核验方式：manual（两个已授权源码根）。
- 独立性：评审者为独立子代理 `/root/planning_review`，未编写本次 proposal、specs、design、tasks 或业务代码；仅只读检查并编写本报告。
- 本结论表示当前规划可进入实施，不表示代码已经实现、测试通过或具备发布条件。

## 评审范围与事实

核对本次源计划、proposal、两份增量规范、design、tasks，以及现有提交服务、生产者、普通消费者、死信消费者、结果保存、Redis 序列化、前端 Answer 查询和 Axios 拦截器。

授权根：`backend=/Users/lllyw/lyw-oj`、`frontend=/Users/lllyw/oj-fe-c`。读取时后端 HEAD 为 `580ae00b5dbcbce1d0a58330f070dfc09bc22abb`，前端 HEAD 为 `a14e3e9b542b54068f8d77ffa2b25b5e9786dc48`；HEAD 只作定位，不替代完整跨根文件清单。未读取无关项目、真实服务配置或凭据，未启动任何业务服务。

### 关键结论

1. **入库幂等与重复计算的边界清楚。**固定 submitId、消费前数据库查询、现有主键和冲突后复用覆盖顺序重投与并发竞争；系统错误不占用主键。独立短事务使失败事务先退出，再读取赢家记录，避免在 rollback-only 事务里继续处理。允许沙箱重复运行是已确认范围，不应被误记为幂等失败。
2. **单缓存能保护有效缓存中的真实状态。**WAITING < SYSTEM_ERROR < JUDGED 的原子状态更新、同级不续期、错误写被保护也算成功与已确认需求一致；只有获胜数据库记录可以写 JUDGED，避免两份计算结果竞争覆盖。过期后不能承诺缓存状态永久单调，设计已明确由数据库核对恢复。
3. **死信不会抢占真实终态。**死信不入提交表、不重判；缓存失败有限重试后在线程外停止专属监听，单消费者和 prefetch=1 限制未确认堆积。ACK/NACK 异常从业务重试中分离，避免数据库成功后再次执行沙箱。
4. **查询压力与可靠性取舍有明确边界。**普通查询读取正常时不访问数据库；核对和 Redis 读取异常才回源，已找到真实记录时不因缓存回填失败而丢失响应。登录态用户同时约束键和 SQL，不需要为了每次缓存命中另查数据库校验归属。checkDatabase 不被错误地当作限流机制。
5. **前端不会在临时错误时提前结束。**SYSTEM_ERROR 与查询请求失败分开，前者不重置 60 秒预算且继续查询；末尾核对、手动核对、取消和代次隔离有确定行为。没有 ID 的提交网络失败不自动重发；旧提交回执和旧查询都需要隔离。
6. **测试任务与交付门槛足够。**任务覆盖真实 MySQL 主键竞争、Redis Lua 状态竞争与 TTL、RabbitMQ 确认/暂停恢复，以及前端时钟和真实页面。验收本身作为交付条件，没有形成“先验收才能进入验收”的任务循环。

## 阻塞项

无。

## 已解决的 CLI 元数据兼容问题

本机 CLI 为 OpenSpec 1.14.3。初次独立运行 `openspec validate judge-submission-idempotency --strict --no-interactive --json` 返回 exit=1；唯一问题为 `.openspec.yaml` 中未经前缀的 `workflow_profile`、`verification_mode` 引发未知扩展字段 WARNING。

实施者随后仅把本变更的扩展字段改为：

```yaml
x-workflow-profile: strict
x-verification-mode: manual
```

本报告确认语义映射：`x-workflow-profile` 对应项目工作流的 `workflow_profile=strict`，`x-verification-mode` 对应 `verification_mode=manual`。这是 CLI 序列化兼容适配，不是风险降级、不省略独立评审或真实验收。全局模板未修改；后续评审、实施和验收报告须沿用该映射，不宣称旧机器助手已经支持此别名。

对上述最终文件再次独立运行同一严格校验：**exit=0、valid=true、issues=[]**。该结果只证明 CLI 格式与其声明的范围检查，不代替本报告的语义评审。

## 实施注意项与验收检查

这些事项是当前规格的具体落实，不需要新增产品决定：

- **繁忙不能按错误文案识别。**当前 `JudgeConsumer.java:35` 仅比较 `exeMessage`。修改后必须结合结构化结果状态或明确系统异常类型，确保 `JUDGED`（包括 pass=0）即使文案恰好与繁忙相同也走正常 ACK。建议加这个边界测试。
- **短事务必须真的独立且已经完成。**实现时防止自调用绕过事务代理或加入外层长事务；DuplicateKeyException 后必须在已结束的失败事务之外查询。除主键重复外的约束/连接错误不能一律按重复成功处理。
- **终止普通查询的取消不是查询失败。**至少覆盖第 55 秒有普通请求仍在途的情况：阶段切换取消后不得误触发错误暂停，也不能与最终核对重叠；第 60 秒后忽略迟到响应。最后一次核对之后才产生的结果仍属于后续重新查询才能取得的边界。
- **死信关闭必须用真实 Broker 验证。**不能只验证调用了 stop；需要证明专属监听停止、未确认消息重新留在队列、普通监听仍工作、人工恢复后能够处理。ACK 异常不能被计为缓存写入失败并触发业务重试。
- **实施开始前补齐跨根 manual 清单。**区分持续约束和允许修改的参考源码，记录实际 SHA-256、文件类型及可执行位；本报告下方规划哈希不能替代该完整清单。验收需保留所引用的原始证据，不能按普通临时文件清理。

## 独立评审规划基线

以下路径相对 backend；均为普通文件、无可执行位。哈希由实际文件读取计算。

| 输入 | SHA-256 |
|---|---|
| `openspec/changes/judge-submission-idempotency/.openspec.yaml` | `2cfdfca6746ff52637524b7074ffc78ed96a49115d2e3ca6139baafc791a06d6` |
| `openspec/changes/judge-submission-idempotency/proposal.md` | `3410825a72289bada8e5df99cce9442656ecbe50fcca240e387766018e14654f` |
| `openspec/changes/judge-submission-idempotency/design.md` | `d304dca40d7ade5e1b0307d4c15d755d230500e7e75eed78b194e91f60095e66` |
| `openspec/changes/judge-submission-idempotency/tasks.md` | `1eac3a47d45a28069f88fdabd2afe9d4c06787459a5d78c66fe6f3ad036802a7` |
| `openspec/changes/judge-submission-idempotency/specs/judge/submission-results/spec.md` | `d390a703a0753bf77980fc4266b7a1d73d40d36ed80ff1149cfc47239d07e975` |
| `openspec/changes/judge-submission-idempotency/specs/judge/container-pool/spec.md` | `5eecc9cdc1932e68f83b626a2cfcc674d6bf7bbc37120c9bccc098c6103b5796` |
| `.codex/plans/判题提交幂等与系统失败反馈改造执行计划.md` | `329acb0d50b63118c5b480164a7c8d9b636cd3251395e2e44a0a1b6c28dbb89e` |

## 未检查内容

本评审没有执行业务构建、单元测试、中间件集成测试、Chrome 页面验收或负载测试，也没有验证生产连接配置、Docker 故障恢复和实际 QPS。它们仍由任务和最终独立代码评审、实现验收承担。
