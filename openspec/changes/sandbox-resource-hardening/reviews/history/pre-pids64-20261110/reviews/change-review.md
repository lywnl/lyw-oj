# HttpClient5 选型修订规划评审

- 风险等级：strict；核验方式：machine。
- 结论：PASS。独立评审者已核对 SDK 异步启动与关闭路径，未发现本次选型规划阻塞。
- 聚合评审者：主执行上下文 /root；独立评审者 /root/sandbox_review，见 independent-planning-review.md。
- 用户已明确选择“采用 HttpClient5（推荐）”，来源为 `.codex/plans/容器池沙箱防护HttpClient5选型确认.md`；沿用原沙箱范围与完整实施、测试授权。
- 此前规划和真实验收记录保存在 `reviews/history/pre-httpclient5-20261110`，旧保留报告未修改。新选择不沿用旧执行链路的验收 PASS。

## 修订核查

控制与执行流使用同版本 HttpClient5，控制请求有短响应等待，执行流设置 responseTimeout=Duration.ZERO，并通过 callback 的有界启动/执行等待控制期限。SDK DefaultInvocationBuilder.executeAndStream 异步启动已从 3.3.4 原始源码确认，因此删除池化 Netty 启动定时器，不再引入额外定时调度。

仍按每 Worker 独占客户端控制关闭范围。未收到 onStart 时关闭所属客户端；已取得句柄时关闭 callback；等待超时仍移除对应容器并确认不存在后完成任务。镜像拉取沿用 120 秒有界等待并关闭专属客户端。非池化原有依赖保留；只删除本轮为池化 Netty 新增且不再使用的 epoll 依赖。

规格中的可观察行为与接口没有变化，design/proposal/tasks 已承接用户选型，受影响任务已取消完成标记。未受影响的内部接口和文件管理任务保留已成立证据，最终仍执行相关整体回归。

## 必需验证

重新执行配置、回调、执行、文件、生命周期、容量、任务、判题幂等及消费者回归。真实 TCP 测试覆盖控制请求无响应、exec/stats 无响应头、响应头后无输出，确认期限、客户端 EOF 与 SDK 流线程退出；验证迟到 onStart 的关闭以及其他 Worker 不受影响。真实 Docker 验证 Java 编译运行、资源实际限制、超时/输出异常回收与后续正常提交。

独立评审通过后重新登记本次规划及真实实施入口，不冒充原时间仍适用于修订。测试环境、证据保存与清理边界不变，不提交、推送、归档或部署。

## 版本核对事项

“同版本”指 docker-java 与其传输模块均保持 3.3.4；实际 Apache HttpClient/HttpCore 版本受项目 BOM 管理，先前实测为 5.1.4/5.1.5。新版运行证据须保存实际依赖版本，不以传输模块原始 POM 的 5.0.3 代替运行版本。该事项不改变当前已授权接口或依赖升级范围。
