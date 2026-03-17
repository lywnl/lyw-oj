# 🏆 LYW-OJ 在线判题系统

<p align="center">
  <b>基于 Spring Cloud Alibaba 微服务架构的在线编程判题平台</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.0.1-brightgreen" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/Spring%20Cloud-2022.0.0-blue" alt="Spring Cloud"/>
  <img src="https://img.shields.io/badge/Spring%20Cloud%20Alibaba-2022.0.0.0--RC2-orange" alt="Spring Cloud Alibaba"/>
  <img src="https://img.shields.io/badge/JDK-17-red" alt="JDK"/>
  <img src="https://img.shields.io/badge/MyBatis--Plus-3.5.5-yellow" alt="MyBatis Plus"/>
</p>

---

## 📖 项目简介

LYW-OJ 是一款在线编程判题系统（Online Judge），支持**算法题管理**、**在线提交代码**、**Docker 沙箱安全判题**和**编程竞赛**等功能。系统采用**前后端分离**架构，后端基于 Spring Cloud Alibaba 微服务体系构建，使用 Docker 容器化沙箱确保代码执行的安全性与隔离性。

### 核心亮点

- 🐳 **Docker 沙箱判题**：基于 Docker 容器的代码沙箱，支持资源限制（内存、CPU、时间），安全隔离用户代码执行
- 🚀 **容器池化技术**：预创建 Docker 容器池，避免每次判题重复创建/销毁容器，提升判题性能
- 🐰 **异步判题**：通过 RabbitMQ 消息队列实现异步判题，提高系统吞吐量
- 🏅 **竞赛系统**：支持编程竞赛的创建、发布、报名、答题、自动排名和成绩通知
- 🔍 **ElasticSearch 搜索**：题目全文检索，提升搜索体验
- ☁️ **阿里云 OSS 文件存储**：用户头像等文件存储于阿里云 OSS
- 📱 **短信验证码登录**：C 端用户通过阿里云短信验证码进行登录

---

## 🏗️ 系统架构

```
                          ┌──────────────────┐
                          │     Nginx 反代    │
                          └────────┬─────────┘
                                   │
                          ┌────────▼─────────┐
                          │   oj-gateway     │
                          │  (统一网关/鉴权)   │
                          └────────┬─────────┘
                                   │
              ┌────────────────────┼─────────────────────┐
              │                    │                     │
     ┌────────▼────────┐  ┌───────▼────────┐  ┌─────────▼─────────┐
     │   oj-system     │  │   oj-friend    │  │    oj-judge       │
     │  (管理端服务)     │  │  (用户端服务)   │  │  (判题服务)        │
     └────────┬────────┘  └───────┬────────┘  └─────────┬─────────┘
              │                    │                     │
              │            ┌──────▼──────┐      ┌───────▼───────┐
              │            │  RabbitMQ   │      │  Docker 沙箱   │
              │            └─────────────┘      └───────────────┘
              │
     ┌────────▼────────┐
     │    oj-job        │
     │  (定时任务服务)    │
     └─────────────────┘

    ┌───────────────────────────────────────────────────────────────┐
    │                       基础设施层                                │
    │  Nacos (注册/配置中心) │ MySQL │ Redis │ ElasticSearch │ XXL-Job  │
    └───────────────────────────────────────────────────────────────┘
```

---

## 📁 项目模块

```
lyw-oj
├── oj-gateway              # 网关服务
├── oj-modules              # 业务模块
│   ├── oj-system            # 管理端服务 (B端)
│   ├── oj-friend            # 用户端服务 (C端)
│   ├── oj-judge             # 判题服务
│   └── oj-job               # 定时任务服务
├── oj-api                   # 远程调用接口 (Feign/RPC)
├── oj-commom                # 公共模块
│   ├── oj-common-core       # 核心组件 (常量/工具/枚举/通用响应)
│   ├── oj-common-redis      # Redis 配置与服务封装
│   ├── oj-common-security   # 安全组件 (Token/拦截器/异常处理)
│   ├── oj-common-mybatis    # MyBatis-Plus 配置
│   ├── oj-common-swagger    # Swagger/OpenAPI 文档配置
│   ├── oj-common-elasticsearch  # ElasticSearch 配置
│   ├── oj-common-file       # 阿里云 OSS 文件服务
│   ├── oj-common-message    # 阿里云短信服务
│   └── oj-common-rabbitmq   # RabbitMQ 消息队列配置
├── deploy                   # 部署配置
│   ├── dev                  # 开发环境 (SQL/ES)
│   └── prod                 # 生产环境 (Docker Compose)
└── pom.xml                  # 父工程 POM
```

---

## 📋 功能模块详解

### 🌐 oj-gateway — 网关服务

| 功能 | 说明 |
|------|------|
| 统一路由 | 基于 Spring Cloud Gateway，统一转发请求至各微服务 |
| JWT 鉴权 | 全局过滤器进行 Token 解析与验证 |
| 白名单 | 支持 Nacos 动态配置接口白名单，无需认证即可访问 |
| 身份隔离 | 根据用户身份（管理员/普通用户）隔离 B 端与 C 端接口访问权限 |
| 负载均衡 | Spring Cloud LoadBalancer 服务负载均衡 |

### 🔧 oj-system — 管理端服务 (B端)

| 功能模块 | API | 说明 |
|----------|-----|------|
| 管理员管理 | `/sysUser/**` | 管理员登录/登出、获取信息、新增/删除管理员 |
| 题目管理 | `/question/**` | 题目列表/新增/编辑/删除/详情查询 |
| 竞赛管理 | `/exam/**` | 竞赛增删改查、发布/撤销发布、添加/移除竞赛题目 |
| 用户管理 | `/user/**` | C 端用户列表及状态管理 |

### 👥 oj-friend — 用户端服务 (C端)

| 功能模块 | API | 说明 |
|----------|-----|------|
| 用户认证 | `/user/**` | 手机验证码登录/登出、个人信息/头像修改 |
| 题目浏览 | `/question/**` | 题目列表（支持 ES 搜索）、详情、上/下一题导航 |
| 代码提交 | `/user/question/**` | 同步/异步（RabbitMQ）提交代码、查询执行结果 |
| 竞赛功能 | `/exam/**` | 竞赛列表（Redis 缓存）、题目导航、排名查看 |
| 竞赛报名 | `/user/exam/**` | 报名参赛、我的竞赛 |
| 消息中心 | `/user/message/**` | 查看系统消息（竞赛结果通知等） |
| 文件上传 | `/file/**` | 头像上传至阿里云 OSS |

### ⚖️ oj-judge — 判题服务

| 功能 | 说明 |
|------|------|
| Docker 沙箱执行 | 在 Docker 容器中编译和运行用户 Java 代码 |
| 容器池 | 预创建容器池（默认 4 个），使用 `azul/zulu-openjdk:17` 镜像 |
| 资源限制 | 内存限制（默认 100MB）、CPU 限制、执行时间限制 |
| 结果判定 | 逐一比对输入输出，检查内存/时间是否超限 |
| 分数计算 | 根据题目难度计算得分 |
| 异步消费 | 通过 RabbitMQ 消费判题消息，实现异步判题 |
| 提交记录 | 保存用户提交记录和判题结果 |

### ⏰ oj-job — 定时任务服务

| 任务 | 说明 |
|------|------|
| `examListOrganizeHandler` | 定时整理竞赛列表，将竞赛分类为"未完赛"和"历史"并刷新 Redis 缓存 |
| `examResultHandler` | 竞赛结束后自动统计参赛排名、计算得分，生成站内消息通知参赛用户 |

---

## 🗄️ 数据库设计

| 表名 | 说明 |
|------|------|
| `tb_sys_user` | 管理员账户表 |
| `tb_user` | 普通用户表（手机号、昵称、头像、学校等） |
| `tb_question` | 题目表（标题、难度、时间/空间限制、题目内容、用例、默认代码） |
| `tb_exam` | 竞赛表（标题、开始/结束时间、发布状态） |
| `tb_exam_question` | 竞赛-题目关联表（多对多，题目顺序） |
| `tb_user_exam` | 用户-竞赛报名表（得分、排名） |
| `tb_user_submit` | 用户代码提交记录表（代码、执行结果、得分） |
| `tb_message_text` | 系统消息内容表 |
| `tb_message` | 系统消息发送/接收关系表 |

---

## 🛠️ 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| **核心框架** | Spring Boot | 3.0.1 |
| **微服务** | Spring Cloud | 2022.0.0 |
| **微服务** | Spring Cloud Alibaba | 2022.0.0.0-RC2 |
| **网关** | Spring Cloud Gateway | — |
| **注册/配置中心** | Nacos | — |
| **ORM 框架** | MyBatis-Plus | 3.5.5 |
| **数据库** | MySQL | — |
| **缓存** | Redis | — |
| **消息队列** | RabbitMQ | — |
| **搜索引擎** | ElasticSearch | — |
| **定时任务** | XXL-Job | 2.4.0 |
| **容器沙箱** | Docker (docker-java) | — |
| **对象存储** | 阿里云 OSS | 3.17.4 |
| **短信服务** | 阿里云 SMS | — |
| **认证** | JWT (jjwt) | 0.9.1 |
| **API 文档** | SpringDoc OpenAPI | 2.2.0 |
| **工具库** | Hutool | 5.8.22 |
| **JSON** | Fastjson2 | 2.0.43 |
| **JDK** | JDK | 17 |

---

## 🚀 快速开始

### 环境要求

- **JDK** 17+
- **Maven** 3.8+
- **MySQL** 8.0+
- **Redis** 6.0+
- **Nacos** 2.x
- **RabbitMQ** 3.x
- **ElasticSearch** 7.x / 8.x
- **Docker** 20.x+
- **XXL-Job Admin** 2.4.0

### 1. 初始化数据库

```bash
# 执行 deploy/dev/sql/init.sql 初始化数据库表结构
mysql -u root -p < deploy/dev/sql/init.sql
```

### 2. 配置 Nacos

启动 Nacos 服务，在对应命名空间下配置各服务的配置文件（数据库、Redis、RabbitMQ 连接信息等）。

各服务的 `bootstrap.yml` 中已配置 Nacos 地址和命名空间，按需修改：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        namespace: your-namespace-id
        server-addr: http://your-nacos-host:8848
      config:
        namespace: your-namespace-id
        server-addr: http://your-nacos-host:8848
        file-extension: yaml
```

### 3. 启动基础设施

```bash
# 使用 Docker Compose 一键启动基础设施（生产环境）
cd deploy/prod
docker-compose up -d
```

### 4. 启动服务

按如下顺序依次启动各微服务：

```
1. oj-gateway        (网关服务)
2. oj-system          (管理端服务)
3. oj-friend          (用户端服务)
4. oj-judge           (判题服务)
5. oj-job             (定时任务服务)
```

每个服务均为独立的 Spring Boot 应用，可通过 IDEA 直接运行或使用 Maven 打包：

```bash
mvn clean package -DskipTests
java -jar oj-gateway/target/oj-gateway.jar
java -jar oj-modules/oj-system/target/oj-system.jar
java -jar oj-modules/oj-friend/target/oj-friend.jar
java -jar oj-modules/oj-judge/target/oj-judge.jar
java -jar oj-modules/oj-job/target/oj-job.jar
```

---

## 🐳 Docker 沙箱判题流程

```
用户提交代码
     │
     ▼
oj-friend 接收请求
     │
     ├── 同步模式 ──▶ 直接 RPC 调用 oj-judge
     │
     └── 异步模式 ──▶ 发送至 RabbitMQ 队列
                          │
                          ▼
                    oj-judge 消费消息
                          │
                          ▼
               从 Docker 容器池获取容器
                          │
                          ▼
              ┌─── Docker 容器内 ───┐
              │  1. 写入用户代码文件  │
              │  2. javac 编译代码   │
              │  3. java 逐个用例执行 │
              │  4. 收集输出/内存/时间 │
              └────────────────────┘
                          │
                          ▼
               比对预期输出 & 资源限制
                          │
                          ▼
               计算得分 & 保存提交记录
                          │
                          ▼
                    返回判题结果
```

---

## 📐 项目配置说明

### Docker 沙箱配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `sandbox.docker.host` | `tcp://localhost:2375` | Docker 远程 API 地址 |
| `sandbox.docker.image` | `azul/zulu-openjdk:17` | 沙箱 JDK 镜像 |
| `sandbox.docker.volume` | `/usr/share/java` | 容器挂载卷目录 |
| `sandbox.limit.memory` | `100000000` (≈100MB) | 内存限制 |
| `sandbox.limit.memory-swap` | `100000000` | Swap 限制 |
| `sandbox.limit.cpu` | `1` | CPU 核心限制 |
| `sandbox.docker.pool.size` | `4` | 容器池大小 |
| `sandbox.docker.name-prefix` | `oj-sandbox-jdk` | 容器名称前缀 |

---

## 📌 部署说明

项目提供了完整的生产环境 Docker Compose 部署方案，包含以下服务编排：

```
deploy/prod/
├── docker-compose.yml     # 服务编排配置
├── lywoj-jar/             # 各服务 JAR 及 Dockerfile
├── mysql/                 # MySQL 数据持久化
├── nacos/                 # Nacos 配置
├── nginx/                 # Nginx 反向代理 & 前端静态资源
├── redis/                 # Redis 数据持久化
├── rabbit/                # RabbitMQ
├── elasticsearch/         # ElasticSearch 配置
└── sh/                    # 部署脚本
```

---

## 🤝 参与贡献

1. Fork 本仓库
2. 新建 `feat/xxx` 分支
3. 提交代码
4. 新建 Pull Request

---

## 📄 许可证

本项目仅供学习交流使用。
