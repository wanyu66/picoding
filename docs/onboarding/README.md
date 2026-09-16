# PaiCoding 项目学习 Wiki

## 目的与读者

这套 Wiki 面向第一次接触 PaiCoding 的开发者，目标是建立一张可以回到源码验证的项目地图：先理解模块和启动过程，再沿真实请求追踪数据库、Redis、RabbitMQ、Elasticsearch 与认证，最后用核心业务流程串起这些基础设施。

## 阅读顺序

1. [整体架构与模块](01-architecture-and-modules.md)
2. [Spring Boot 启动与请求生命周期](02-startup-and-request-lifecycle.md)
3. [Controller → Service → DAO/Mapper → 数据库](03-controller-service-mapper-database.md)
4. [Redis 使用地图](04-redis.md)
5. [RabbitMQ 与领域事件](05-rabbitmq-and-domain-events.md)
6. [Elasticsearch 搜索链路](06-elasticsearch.md)
7. [登录认证与权限控制](07-authentication.md)
8. [核心业务流程](08-core-business-flows.md)
9. [开发地图、验证方式与风险](09-development-map-and-risks.md)

## 一句话架构

PaiCoding 是一个 Java 8、Spring Boot 2.7.1 的多模块内容社区：`paicoding-web` 提供 HTTP/WebSocket 入口，`paicoding-service` 承载业务与持久化，`paicoding-core` 提供横切基础能力，`paicoding-api` 放跨层模型，`paicoding-ui` 提供 Thymeleaf/静态资源；另有不进入 Maven reactor 的原生微信小程序客户端。根 POM 明确列出五个 Maven 模块及 Java 8 编译目标。`pom.xml:L5-L16` `pom.xml:L24-L29`

## 结论标记

- **已验证**：有直接源码、POM 或配置结构证据。
- **已观察**：在多个实现中重复出现的稳定模式，但不是强制框架规则。
- **待调查**：静态阅读不足以证明运行时状态，或需要真实外部服务验证。

## 分析范围与安全限制

- 分析日期：2026-08-23；仓库根目录：`D:/Downloads/paicoding`。
- 仅做只读源码分析；没有运行应用、构建、测试、脚本、容器、迁移或网络请求。
- 用户明确授权的唯一写入范围是 `docs/onboarding/`，本次只在该目录创建 Markdown。
- 仓库存在 `.env` 及环境配置文件；未读取、复制或记录任何密钥、口令、Token、Cookie、证书或数据库数据值。只盘点了安全的配置键名和 Java 配置绑定结构。
- 扫描未发现 reparse point/符号链接。技能附带 Python 扫描器因本机没有 `python`/`py` 而不可用，改用 `rg` 与 PowerShell 做定向只读扫描。
- 没有检查 Git 历史；本文描述当前工作树，不推断演进原因。
- 仓库指引要求 Java 8，并警告不要随意执行 `mvn clean`。`AGENTS.md:L9-L16`

## 快速导航

| 想回答的问题 | 页面 |
|---|---|
| 模块如何依赖、代码应放哪里 | [整体架构与模块](01-architecture-and-modules.md) |
| Spring Boot 如何拉起各模块 | [启动与请求生命周期](02-startup-and-request-lifecycle.md) |
| 一个请求如何落到 SQL | [数据库调用链](03-controller-service-mapper-database.md) |
| Session、统计、搜索缓存在哪里 | [Redis](04-redis.md) |
| 文章/互动事件如何异步扇出 | [RabbitMQ](05-rabbitmq-and-domain-events.md) |
| ES 如何搜索、同步和降级 | [Elasticsearch](06-elasticsearch.md) |
| Cookie/JWT/Redis/权限注解如何协作 | [认证](07-authentication.md) |
| 发布、阅读、互动、通知等业务如何串联 | [核心业务流程](08-core-business-flows.md) |
| 新需求从哪里改、有哪些不确定性 | [开发地图与风险](09-development-map-and-risks.md) |

