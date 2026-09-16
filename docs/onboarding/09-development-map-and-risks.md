# 开发地图、验证方式与风险

## TL;DR

新增功能应从对应领域的 Web 入口和 Service 接口开始，沿 `service → repository/dao → mapper/entity` 落地；涉及跨域副作用时写 Outbox，不直接在主事务里调用多个下游。构建和测试必须使用 Java 8；本次 onboarding 没有执行任何命令。`AGENTS.md:L9-L16`

## 第一次修改地图

| 需求 | 首选入口 | 通常还要检查 |
|---|---|---|
| 新增 REST/页面接口 | `paicoding-web/src/main/java/.../web/<场景>` | `@Permission`、`ReqInfoContext`、统一 `ResVo`、Thymeleaf 模板 |
| 修改文章业务 | `paicoding-service/.../article/service` | ArticleDao、Mapper/XML、状态枚举、Outbox、ES 同步、Caffeine 清理 |
| 修改评论/互动 | `.../comment/service`、`.../user/service/userfoot` | 通知、统计、排行消费者与幂等键 |
| 新增表/字段 | `paicoding-web/src/main/resources/liquibase` | DO、Mapper、XML、转换器、重建/回填策略 |
| 修改登录/权限 | LoginService、UserSessionHelper、ReqRecordFilter、GlobalInitService、GlobalViewInterceptor | Cookie/header、JWT/Redis TTL、审计、设备策略、小程序兼容 |
| 修改缓存 | 领域 Service + `RedisClient` | TTL、key 前缀、删除时机、集群一致性、序列化兼容 |
| 修改 MQ | DomainEventType、Outbox 写入点、RabbitMqAutoConfig、目标消费者 | 事务边界、publisher confirm、重试/DLQ、幂等消费 |
| 修改搜索 | ArticleSearchService/Impl | MySQL fallback、索引 mapping、文档投影、alias、缓存 generation、重建一致性 |
| 修改 SSR 前端 | `paicoding-ui/src/main/resources/templates` / `static` | 对应 ViewController、TemplateEngineHelper、全局 VO |
| 修改微信小程序 | `paicoding-miniapp/pages` / `components` / `utils` | 嵌套 `AGENTS.md`、后端 `/mini/api/**`、token 传输、微信隐私要求 |

## 编码约定（从现有代码观察）

- Controller 依赖 Service 接口，不直接访问 Mapper；文章发布入口就是标准示例。`paicoding-web/src/main/java/com/github/paicoding/forum/web/front/article/rest/ArticleRestController.java:L53-L77`
- Service 负责事务和领域规则；DAO 负责 DB 语义；Mapper 负责 SQL 映射。`paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleWriteServiceImpl.java:L79-L117` `paicoding-service/src/main/java/com/github/paicoding/forum/service/article/repository/dao/ArticleDao.java:L45-L60`
- API 响应使用 `ResVo<T>`；分页使用 `PageVo`/`PageListVo`；请求身份使用 `ReqInfoContext`。`paicoding-api/src/main/java/com/github/paicoding/forum/api/model/vo/ResVo.java:L14-L44`
- 复杂对象转换既有手写 Converter，也有 MapStruct `StructMapper`；应优先模仿相邻领域。
- 跨域副作用通过 `DomainEventOutboxService`，消费者通过统一幂等执行器，不在 listener 中吞异常。`paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/service/impl/NotificationDomainEventConsumer.java:L22-L30`
- 基础设施开关使用 `@ConditionalOnProperty`，关闭时必须保留清晰降级语义。

## 配置地图（不含任何值）

| 配置 | 位置 | 用途 |
|---|---|---|
| 主应用、JWT、Liquibase、管理端点 | `paicoding-web/src/main/resources/application.yml` | 全局启动与安全/运维结构 |
| 站点展示 | `paicoding-web/src/main/resources/application-config.yml` | host、站点名称、分页、联系信息等 |
| 数据库/Redis/ES | `paicoding-web/src/main/resources-env/<env>/application-dal.yml` | 数据访问与搜索 |
| Web/Thymeleaf/WebSocket | `.../application-web.yml` | 渲染、短链、WebSocket |
| 图片/对象存储 | `.../application-image.yml` | 上传与 OSS |

配置目录结构由仓库指引记录。`AGENTS.md:L43-L51` 本文没有读取或复述任何配置值。

## 验证前提与命令

**未执行**：本次为了安全 onboarding，没有构建、测试、启动、迁移或连接外部服务。

仓库明确要求 Java 8，且当前默认 Java 21 与 Lombok/Javac 不兼容；还明确禁止未经请求使用 `mvn clean`。`AGENTS.md:L11-L16`

仓库给出的示例命令是 macOS/Homebrew 专用，因此在当前 Windows 工作区不能原样执行。可迁移的原则是：

1. 确认 `JAVA_HOME` 指向 JDK 8；
2. 确认 `PATH` 中 JDK 8 `bin` 在前；
3. 用目标模块的 Maven compile/test 命令，不用 `clean`；
4. 在启动集成测试前准备 MySQL、Redis，以及按开关需要的 RabbitMQ/ES；
5. 先跑最小目标模块，再扩大到依赖它的 Web 测试。

具体 Windows JDK 8 安装路径未在可信上下文中给出，因此本文不虚构可直接复制的命令。

## 上手练习路线

1. 读 `ArticleRestController.detail/post`，理解读写入口。
2. 跟 `ArticleWriteServiceImpl.insertArticle` 到 ArticleDao/Mapper。
3. 跟 `ReqRecordFilter → GlobalInitService → UserSessionHelper` 理解当前用户从哪里来。
4. 跟 `ArticleMsgEvent → Outbox → RabbitMQ search/statistics/activity` 理解最终一致性。
5. 跟 `SearchRestController → ArticleReadServiceImpl → ArticleSearchServiceImpl → MySQL fallback` 理解降级。
6. 做一个只读小改动，例如新增查询字段，同时补充 Service 和 Controller 单元测试；不要把第一项练习放在支付或认证密钥边界。

## 已确认的风险/技术债

### 1. 文档与代码漂移

`AGENTS.md` 说实体位于 API、并把 MongoDB 列为技术栈。`AGENTS.md:L36-L41` `AGENTS.md:L53-L61` 当前扫描显示业务 DO 位于 Service，且 POM/Java 主代码没有发现 MongoDB runtime 依赖或访问类。MongoDB 仅作为内容标签数据出现。因此 MongoDB 应标为**待调查/疑似历史文档残留**，不能当成当前运行依赖。

### 2. 启动期高权限数据库行为

初始化器可能使用应用数据源账号检测并创建数据库，还直接查询/修订 Liquibase 元数据。`paicoding-web/src/main/java/com/github/paicoding/forum/web/config/init/ForumDataSourceInitializer.java:L77-L140` 生产环境应按最小权限拆分迁移账号与运行账号。

### 3. 管理端与不安全开关

- Druid 监控 Servlet 有固定初始化参数，部署前应限制/关闭并审计凭据。`paicoding-core/src/main/java/com/github/paicoding/forum/core/dal/DataSourceConfig.java:L107-L121`
- ES 有跳过证书与主机名校验的本地调试选项，生产必须为 false。`paicoding-service/src/main/java/com/github/paicoding/forum/service/config/es/ElasticsearchConfig.java:L73-L77` `paicoding-service/src/main/java/com/github/paicoding/forum/service/config/es/ElasticsearchConfig.java:L108-L122`

### 4. 一致性与并发

- UserFoot 点赞/收藏代码自己记录了并发读写不一致风险。`paicoding-service/src/main/java/com/github/paicoding/forum/service/user/service/userfoot/UserFootServiceImpl.java:L81-L87`
- RabbitMQ 关闭会停止派生状态推进；应监控 Outbox 积压并明确恢复流程。
- Redis 与 ES 都是派生状态；修复脚本应从 MySQL/Outbox 重建，而不是把缓存反写为事实源。

### 5. 凭据与日志

- 仓库含 `.env` 与多环境配置；本次完全跳过其内容。应确认它们没有被提交真实秘密，并通过部署秘密管理注入。
- JWT 校验失败日志路径可能携带传入 token，应检查生产日志脱敏。`paicoding-service/src/main/java/com/github/paicoding/forum/service/user/service/help/UserSessionHelper.java:L341-L349`
- 请求日志会包含 URI、查询、payload、IP 和 User-Agent；应验证密码、token、支付回调等敏感端点的日志过滤。`paicoding-web/src/main/java/com/github/paicoding/forum/web/hook/filter/ReqRecordFilter.java:L207-L225`

## 待调查清单

- 生产实际启用的 profile、MQ/ES 开关及外部服务拓扑；
- Liquibase 当前版本与所有历史 changeset 的执行状态；
- ES alias/mapping/覆盖率和全量重建演练；
- Outbox/DLQ 的人工重放工具与告警阈值；
- Redis 淘汰、持久化、备份与高可用策略；
- 支付回调签名、金额、幂等与对账测试；
- 微信/知识星球/小程序真实第三方授权配置；
- 请求日志、会话日志和审计表的数据保留/脱敏策略；
- 当前 Windows 机器上可用的 JDK 8 和 Maven 绝对路径。

## Wiki 自检结果

- 所有技术结论均给出当前仓库路径和已核对行号，或显式标为观察/待调查。
- 未运行仓库代码、构建、测试、脚本、容器或迁移；未安装依赖；未联网。
- 未读取/复制 `.env`、凭据值、Token、Cookie、证书或数据库 dump。
- 未发现符号链接/reparse point；Python 扫描器不可用，已记录回退限制。
- 新文件仅位于 `docs/onboarding/`。

[上一页：核心业务](08-core-business-flows.md) · [返回索引](README.md)

