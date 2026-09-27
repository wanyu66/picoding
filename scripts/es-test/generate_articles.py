
import argparse
import os
import uuid

import pymysql

TOPICS = [
    ("Redis", "缓存击穿", "通过分布式互斥锁控制并发回源"),
    ("Redis", "缓存穿透", "使用布隆过滤器与空值缓存"),
    ("MySQL", "索引优化", "分析联合索引和最左前缀原则"),
    ("MySQL", "事务隔离", "研究MVCC和可重复读"),
    ("Spring Boot", "自动装配", "分析自动配置与条件注解"),
    ("Spring Cloud", "微服务治理", "实现服务注册和配置管理"),
    ("Elasticsearch", "全文检索", "使用倒排索引和BM25相关性评分"),
    ("Elasticsearch", "中文分词", "比较不同中文分词策略"),
    ("RabbitMQ", "消息可靠性", "实现消息确认与失败重试"),
    ("JVM", "垃圾回收", "分析GC日志和内存管理"),
    ("Java", "并发编程", "研究线程池和CompletableFuture"),
    ("Docker", "容器部署", "实现应用容器化和网络隔离"),
]

parser = argparse.ArgumentParser()
parser.add_argument("--count", type=int, default=10000)
parser.add_argument("--batch-size", type=int, default=200)
args = parser.parse_args()

if args.count <= 0 or args.batch_size <= 0:
    parser.error("count 和 batch-size 必须大于 0")

# 使用独立的测试数据库
db_name = os.environ["DB_NAME"]

if not os.environ.get("CONFIRM_TEST_DB") == "YES":
    raise RuntimeError("请先确认使用测试数据库，设置 CONFIRM_TEST_DB=YES")

conn = pymysql.connect(
    host=os.getenv("DB_HOST", "127.0.0.1"),
    port=int(os.getenv("DB_PORT", "3306")),
    user=os.environ["DB_USER"],
    password=os.environ["DB_PASSWORD"],
    database=db_name,
    charset="utf8mb4",
    autocommit=False
)

run_id = uuid.uuid4().hex[:8]

try:
    with conn.cursor() as cursor:
        # 关联现有的测试作者和分类
        cursor.execute("SELECT user_id FROM user_info LIMIT 1")
        author = cursor.fetchone()

        if not author:
            raise RuntimeError("请先创建测试作者")

        user_id = author[0]

        cursor.execute("SELECT id FROM category LIMIT 1")
        category = cursor.fetchone()
        category_id = category[0] if category else 0

        for i in range(1, args.count + 1):
            topic, subtopic, description = TOPICS[
                (i - 1) % len(TOPICS)
            ]

            title = f"{topic} {subtopic} 实践指南 {i:06d}"
            summary = f"介绍{topic}中的{subtopic}，重点分析{description}。"

            content = f"""# {title}

## 技术背景
{summary}

## 核心原理
{subtopic}是{topic}领域的重要技术问题。
本文围绕{description}进行分析。

## 实现方案
在实际项目中，需要结合业务特点，
考虑数据一致性、系统吞吐量和故障恢复。

## 性能分析
可以通过并发测试、响应时间和资源监控，
评估方案在不同数据规模下的表现。

## 总结
本文介绍了{topic}中{subtopic}的基本原理和应用。
"""

            # 插入文章基础信息
            cursor.execute("""
                INSERT INTO article
                (
                    user_id, article_type,
                    title, short_title, url_slug,
                    summary, category_id,
                    source, status, deleted
                )
                VALUES
                (%s, 1, %s, %s, %s, %s, %s, 2, 1, 0)
            """, (
                user_id,
                title,
                title,
                f"es-test-{run_id}-{i}",
                summary,
                category_id
            ))

            article_id = cursor.lastrowid

            # 插入文章正文，维护关联
            cursor.execute("""
                INSERT INTO article_detail
                (article_id, version, content, deleted)
                VALUES (%s, 1, %s, 0)
            """, (article_id, content))

            if i % args.batch_size == 0:
                conn.commit()
                print(f"已导入 {i}/{args.count}")

        conn.commit()
        print(f"导入完成：{args.count} 篇")
        print(f"测试批次：{run_id}")

except Exception:
    conn.rollback()
    raise

finally:
    conn.close()