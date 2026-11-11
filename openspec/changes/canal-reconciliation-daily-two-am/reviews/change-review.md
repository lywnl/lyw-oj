# 规划评审

- 风险等级：strict；核验方式：machine。
- 结论：PASS。
- 聚合评审者：/root；独立评审者：/root/xxl_reconciliation_review，见 independent-plan-review.md。
- 基线：当前 proposal、specs、design、tasks、元数据及用户已确认计划；旧本地 Cron 规划/证据已保存历史。

## 阻塞项

无。范围、请求权限、单槽、合作取消、失败收尾、旧配置迁移及真实调度验证均可实施。独立评审无阻塞发现。

## 警告及处置

实现必须显式观察游标清理失败，并把对账取消与整个消费线程中断分开；必须覆盖停机竞态、编码路径与真实 Admin 回调。对应 design 3–5 及任务 1.2、2.2、3.2。既有唯一活动消费者为前提，不新增跨实例锁或持久任务状态。

## 实施交接

用户已明确授权整个计划实施、隔离 Docker 验证和本轮资源清理。修改 oj-api、oj-job、oj-system、oj-gateway 和相关维护/测试，使用已有 JDK 17 Maven 镜像，新增真实 2.4.0 Admin 隔离测试。不修改真实 Nacos、业务数据或线上任务，不自动提交/推送/归档。65 项旧测试不是新实现验收。
