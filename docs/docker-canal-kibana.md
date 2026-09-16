# 使用 Docker 运行 Canal 与 Kibana

本编排适配当前本地开发环境：MySQL 8、数据库 `pai_coding`，以及 Elasticsearch/Kibana 8.10.0。Kibana 使用 Elastic 为 8.10.0 发布的 `8.10.0-1` 容器标签。同步链路为 `MySQL binlog -> Canal Server -> Canal Adapter ES8 -> Elasticsearch`，Canal 仅订阅 `pai_coding` 库，服务端口只绑定到本机。

## 前置条件

- Docker Desktop 正在运行。
- MySQL 监听 `3306`，并已开启 `log_bin=ON`、`binlog_format=ROW`。
- Elasticsearch 8.10.0 监听 `127.0.0.1:9200`。若地址不同，修改 `.env` 中的 `KIBANA_ELASTICSEARCH_HOSTS`。

## 1. 创建 Canal 账号

先在项目根目录的 `.env` 中设置一个仅用于本地开发的密码：

```dotenv
CANAL_DB_USERNAME=canal
CANAL_DB_PASSWORD=canal
```

然后用 MySQL 管理账号执行：

```sql
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'canal';
ALTER USER 'canal'@'%' IDENTIFIED BY 'canal';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
```

## 2. 启停服务

```bash
docker compose -f docker-compose.canal-kibana.yml up -d
docker compose -f docker-compose.canal-kibana.yml ps
```

访问地址：

- Kibana: <http://127.0.0.1:5601>
- Canal TCP: `127.0.0.1:11111`，destination 为 `paicoding`
- Canal Adapter: <http://127.0.0.1:8081>，ES8 映射为 `es8/paicoding-article.yml`

查看日志或停止服务：

```bash
docker compose -f docker-compose.canal-kibana.yml logs -f canal kibana
docker compose -f docker-compose.canal-kibana.yml down
```

## 3. 首次全量同步

编排会自动创建 `paicoding_article_canal_v1` 索引。首次启动后执行：

```bash
curl -X POST http://127.0.0.1:8081/etl/es8/paicoding-article.yml
curl -X PUT http://127.0.0.1:8081/syncSwitch/paicoding/on
```

检查同步数量：

```bash
curl http://127.0.0.1:8081/count/es8/paicoding-article.yml
curl http://127.0.0.1:9200/paicoding_article_canal_v1/_count
```

之后对 MySQL `article` 表的 `INSERT`、`UPDATE`、`DELETE` 会由 Adapter 增量同步。Kibana 中可创建 `paicoding_article_canal_v1` Data View 进行查看。

## 设计说明

Canal Server 只负责解析并发布 MySQL binlog，实际 ES 写入由 Canal Adapter 完成。PaiCoding 当前还通过应用内的 `ArticleSearchSyncListener` 维护更丰富的 `paicoding_article_v1` 搜索索引，所以 Adapter 使用独立的 `paicoding_article_canal_v1`：它与教程中的单表 `article` 同步一致，同时避免覆盖应用索引中的正文、作者、专栏及精确匹配字段。

Adapter 镜像由本仓库基于 Alibaba Canal 官方 `canal.adapter-1.1.7.tar.gz` 在本地构建，不依赖非官方 Canal Adapter 镜像。配置针对 Canal 1.1.7 的 `es8` 插件和 Elasticsearch REST 模式。

如果 Elasticsearch 开启安全认证，应为 Kibana 配置 `kibana_system` 凭据或服务账号令牌，并删除编排中的 `XPACK_SECURITY_ENABLED=false`；生产环境也不应把管理端口公开到公网。
