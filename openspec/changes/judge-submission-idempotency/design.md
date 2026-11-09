# 设计

## Context

后端 Spring Boot 3.0.1、Java 17、MyBatis-Plus 3.5.5、Spring AMQP/Redis；前端 Vue 3、Axios、Vite。当前用户提交由题号/竞赛/本地时间查询，死信普通 SET 会覆盖真实结果，ACK 异常混在业务重试中。需求以已确认计划为基线，不引入新数据库字段或执行锁。

## Goals / Non-Goals

满足 submission-results 中的固定身份、真实记录幂等、统一缓存、查询隔离、限时反馈与 container-pool 中的失败保留。业务执行可以重复，数据库记录不可重复；缓存不是处理完成的权威。没有旧消息，不兼容旧 DTO/查询参数；不重构容器池调度或执行引擎，不发布、不修改真实业务数据。

## Decisions

### 提交契约

在 DTO 增加 Long submitId，在回执及结果 VO 中用 Jackson 字符串序列化。friend 使用 IdWorker 在完成题目/语言/源码校验后生成一次 ID，MQ 重发不改变 DTO。提交业务错误 3701 保留回执；Result 新增带 data 的失败工厂，查询参数由控制器显式验证为正的十进制 Long，缺失/非法返回 3002。

结果 resultState 是 JUDGED/SYSTEM_ERROR/NO_RESULT；仅 JUDGED 有 pass=0/1。内部缓存 WAITING 不暴露为“后台正在执行”的证据。业务提交不使用前端用户 ID，缓存与 SQL 均绑定当前登录用户。

### 共享缓存与模块边界

oj-common-redis 提供通用、单 key 的 JSON 状态写入能力，接收已经序列化的候选缓存和值的级别，通过 Lua 执行 WAITING < SYSTEM_ERROR < JUDGED 优先级；读取/同级/降级不续 TTL。它不依赖 oj-api，避免公共依赖倒置。oj-api 放缓存对象及结果状态/转换支持，两端通过各自配置或适配器调用共享 Redis 服务；不增加框架。

键为 j:r:userId:submitId，默认 300 秒。初始化只允许空键；真实结果来自数据库唯一记录；错误不能写入更高优先级缓存。Lua 用字符串参数序列化避免 JSON 双重转义或数字 TTL 被 JSON 编码；返回“写入/保持”都算成功，通信或内容错误抛出并由调用方处理。

生产者重发与 returns 回调共享错误更新入口；删除旧 catch 中清理结果键的逻辑。日志只保留 ID 和异常摘要。Redis 初始化失败不重发消息。真实缓存已经过期时，临时错误仍可能重新出现，核对数据库后恢复，不承诺跨 TTL 的缓存单调性。

### 真实结果与事务

JudgeService 每次调用先查 submitId，确认 userId、questionId、examId、programType、源码匹配，且 pass 为 0/1；存在即转换成数据库结果并尽力更新缓存。不存在才调用沙箱。明确用户代码失败可存储，null、UNKNOWN_FAILED、SYSTEM_BUSY、RUNNING、不完整结果和基础设施异常走系统错误。

独立持久化服务通过 TransactionTemplate 的短事务插入，事务在 DuplicateKeyException 到调用方前结束；再读取并验证获胜记录，返回该记录而非本次计算结果。保留既有计分/对比。缓存失败被隔离，业务函数仍返回已保存结果。系统繁忙返回明确的系统提示供消费者立即 NACK；其他系统异常由消费重试处理，同步控制器捕获并返回带 ID 的 SYSTEM_ERROR。

### 消息与死信

普通消费总三次，等待 1 秒和 2 秒；非法必需标识不进入业务，交由现有死信路径保留。业务处理 try 与 ACK/NACK try 分离，确认失败关闭通道并返回，不再跑业务。日志不含 DTO 全文。

为死信注册明确 listener id 和独立工厂，固定并发 1、prefetch 1、MANUAL。写入或被更高/同级状态保护均 ACK；三次缓存操作失败或非法死信时，通过受 Spring 管理的单线程执行器从监听线程外停止该 listener，使未确认消息留在队列。ACK 失败关闭故障通道而不重新执行业务；恢复方式为人工重启服务。

### 两种结果查询

GET /user/question/exe/result?submitId=...&checkDatabase=false 默认只读取缓存。WAITING/缺失映射 NO_RESULT，真实和错误映射其状态。checkDatabase=true 或 Redis 读取异常时尝试一次用户与 ID 限定的主键查询；存在真实结果直接返回，缓存回填失败只日志。无记录则重新读取缓存，以捕捉查询期间出现的真实/错误结果；仍不可读返回 2000。SQL 只读取结果字段。

checkDatabase 不是限流安全机制，没有新增全局限流。普通轮询不空查数据库；手动操作、故障与末尾核对仍消耗数据库资源，不承诺严格 QPS 上限。

### 前端查询控制器

提取不依赖 Vue 的查询控制器，用于验证时钟、取消与串行行为；Answer 页面负责展示及用户操作。公开 API 接受 signal、timeout 和本地处理错误选项。拦截器保留错误 code/data/response；3001 保留原鉴权处理，取消不弹窗。

每轮用单调时钟计算 60 秒绝对截止，不被 SYSTEM_ERROR 重置。普通查询完成后等 2 秒，单请求上限沿用 5 秒。截止前 5 秒进入最终核对阶段：取消普通请求并等待其本地结束，使用剩余预算发一次 checkDatabase=true；总截止取消任何剩余请求并使其回调失效。任意时刻最多一个有效请求；后台可能仍处理已取消 HTTP，客户端不声称取消判题。

JUDGED 停止；SYSTEM_ERROR/NO_RESULT 继续。查询异常暂停并保留 ID。手动重新查询先核对一次，未取得真实结果则在同一新预算内进入普通轮询，末尾仍核对一次。提交成功或 3701 且有 ID 时开始普通轮询；无 ID 的网络失败不重发。页面切题/离开/新提交增加代次并取消旧任务，避免旧结果或旧提交回执串入新页面。

## Risks / Trade-offs

- 缓存漏写会延迟展示 → 末尾和手动数据库核对；窗口外不自动推送。
- 单缓存状态竞争 → Lua 原子条件写，真实来源绑定数据库获胜记录；不能仅用 SETNX 处理 WAITING。
- 长期系统异常 → 临时状态不落业务表，缓存过期不保证追溯；死信失败保留消息并暂停。
- 多根源码与公开契约 → strict 独立评审，manual 分根 SHA-256 清单，真实数据库/缓存/MQ 与 Chrome 验证。
- 当前宿主 JDK 25、前端尚无 node_modules → 本轮隔离 Docker JDK 17/Maven 缓存与前端 .codex 依赖目录，不修改系统运行时。

## Migration Plan

无数据库迁移，无旧消息兼容。两端按新协议一起交付，部署由用户另行执行。回退需两端一起回退且不得遗留新协议消息给旧消费者；不在本轮操作部署。保留真实提交、已有报告及验证所需证据，清理本轮专属临时容器与缓存。
