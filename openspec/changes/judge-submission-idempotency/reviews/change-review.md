# 规划评审

- 风险等级：strict。
- 核验方式：manual，授权根 B=/Users/lllyw/lyw-oj、F=/Users/lllyw/oj-fe-c。
- 结论：PASS。
- 聚合评审者：本次主执行上下文；独立评审：planning_review 子上下文，见 independent-planning-review.md。
- 来源：用户最后确认的单缓存修订计划、实际源码、现有容器池规范；用户已明确授权实施，无需再次确认。
- CLI：OpenSpec 1.14.3，`openspec validate judge-submission-idempotency --strict --no-interactive` 实测退出码 0。

## 基线与兼容适配

规划为本目录 metadata、proposal、design、tasks 与两份增量规范；持续约束包含用户计划和项目工作流规则。前后端参考实现分别登记在 planning-inputs.md 关联清单中。任务状态允许正常更新，需求、任务正文与评审结论改变需复审。

当前 CLI 要求扩展字段使用 x- 前缀，因此本变更以 `x-workflow-profile: strict` 映射项目语义 `workflow_profile=strict`，以 `x-verification-mode: manual` 映射 `verification_mode=manual`。这是元数据格式适配，不降低等级、不改变规范、不修改全局模板。多根项目不使用只识别单根/旧字段的 machine 助手。

## 阻塞项

无。独立评审未发现需要修改已确认业务需求的阻塞。

## 实施注意与验证要求

- 繁忙使用结构化状态识别，不依赖错误文案相等；真实编译/运行失败与 Docker 通信异常分开。
- 短事务退出后处理主键冲突；缓存只使用获胜的数据库记录；ACK 失败不可再次执行业务。
- Lua 必须实际验证序列化、并发优先级、同级不续期；等待标记不能作为幂等完成凭据。
- 55 秒切换最终核对时的取消属于内部调度，不是查询失败；60 秒总预算、单有效在途、代次失效需要可控时钟测试。
- 用隔离真实 MySQL/Redis/RabbitMQ 验证唯一性、死信暂停保留和恢复。前端构建与 Chrome 场景验证需明确替身边界。
- 构建使用 Docker JDK 17；本轮专属运行资源登记后清理，保存必要证据及既有基准报告。

## 实施授权与交接

用户指令“根据这份计划来修改我的代码”，当前已进入 Default 模式。允许按任务修改两端代码及必要测试，不自动提交、推送、部署或归档。入口比较规划、持续约束与参考代码清单后记录实施开始；验收需独立代码评审，不能以当前 PASS 冒充实现通过。
