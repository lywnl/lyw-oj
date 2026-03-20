# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 构建命令

```bash
# 全量构建（跳过测试）
mvn clean package -DskipTests

# 构建单个模块（从根目录）
mvn clean package -DskipTests -pl oj-modules/oj-judge -am

# 编译检查（不打包）
mvn clean compile
```

各服务 JAR 产出路径: `oj-gateway/target/`, `oj-modules/oj-*/target/`

## 项目架构

Spring Cloud Alibaba 微服务架构的在线判题系统 (OJ)，JDK 17 + Spring Boot 3.0.1。

**四大顶级模块：**
- `oj-gateway` — Spring Cloud Gateway 网关，JWT 鉴权 + 身份隔离（B端管理员/C端用户）
- `oj-modules/` — 四个业务微服务
- `oj-api/` — Feign 远程调用接口定义 + 跨服务 DTO/VO
- `oj-commom/` — 9 个公共组件库（注意拼写是 commom 不是 common）

**业务服务：**
- `oj-system` — B端管理：题目/竞赛/用户 CRUD
- `oj-friend` — C端用户：登录、题目浏览、代码提交、竞赛报名
- `oj-judge` — 判题核心：Docker 沙箱容器池 + RabbitMQ 异步消费
- `oj-job` — XXL-Job 定时任务：竞赛排名统计、列表整理

**关键依赖流向：**
- `oj-friend` → RabbitMQ → `oj-judge`（异步判题）
- `oj-friend` → Feign → `oj-judge`（同步判题）
- 所有服务 → Nacos（注册发现 + 配置中心）
- 所有服务的 bootstrap.yml 指向 Nacos namespace `2e7bb6a9-1f86-4534-9d86-013e7d78574f`

## 核心业务逻辑

### 判题流程
1. `oj-friend` 的 `JudgeProducer` 发送 `JudgeSubmitDTO` 到 RabbitMQ（exchange: `oj.judge.exchange`）
2. `oj-judge` 的 `JudgeConsumer` 消费消息，从 `DockerSandBoxPool` 获取容器
3. 容器内执行：写入 Solution.java → javac 编译 → java 逐用例运行 → 收集内存/时间
4. 结果比对后写入 `tb_user_submit`，同步更新 Redis 缓存
5. 失败消息进死信队列（`DeadLetterConsumer` 处理）

### Docker 容器池（类 ThreadPool 设计）
- 位于 `oj-judge` 的 `DockerSandBoxPool`，镜像 `azul/zulu-openjdk:17`
- corePoolSize=4 核心容器常驻，临时容器超时自动销毁
- 容器通过 BlockingQueue 管理获取/归还

### RabbitMQ 消息可靠性
- 生产者 Confirm + Returns 回调，失败重试 3 次
- 消费者手动 ACK + 指数退避重试（1s/2s/4s）
- 死信队列兜底，标记 Redis 判题异常状态

## 代码组织约定

每个业务模块内部结构：`controller/` → `service/` → `mapper/`（MyBatis-Plus）

- **Controller 分层**：管理端接口在根 `controller/`，用户端接口在 `controller/user/`
- **DTO/VO 位置**：跨服务的在 `oj-api/domain/`，模块内部的在各自 `domain/`
- **常量集中管理**：`oj-common-core/constants/` 下按业务分类（如 `RabbitMQConstants`、`CacheConstants`）
- **Redis Key 前缀规范**：见 `CacheConstants`，如 `e:d:{examId}`、`j:r:{userId}:{questionId}:{examId}`

## 配置管理

所有运行时配置由 Nacos 下发，本地仅有 `bootstrap.yml` 指定 Nacos 连接信息。修改业务配置需到 Nacos 控制台操作，不在本地配置文件中。

## 数据库

- ORM：MyBatis-Plus 3.5.5，使用 LambdaQueryWrapper 查询
- 表前缀：`tb_`（如 `tb_question`、`tb_user_submit`）
- 初始化 SQL：`deploy/dev/sql/init.sql`
- 自动填充：`MyMetaObjectHandler` 处理 createTime/updateTime

## 部署

```bash
cd deploy/prod
docker-compose up -d
```

基础设施：MySQL 8.0 (3308) / Nacos (8848) / Redis (6379) / RabbitMQ / ElasticSearch / XXL-Job (8080)
