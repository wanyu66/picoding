CREATE TABLE IF NOT EXISTS `interaction_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `aggregate_type` varchar(32) NOT NULL,
  `aggregate_id` bigint unsigned NOT NULL,
  `payload` longtext NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `retry_count` int NOT NULL DEFAULT 0,
  `next_retry_time` datetime NOT NULL,
  `published_at` datetime DEFAULT NULL,
  `last_error` varchar(1000) DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_interaction_outbox_event` (`event_id`),
  KEY `idx_interaction_outbox_pending` (`status`,`next_retry_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交互事件事务Outbox';

CREATE TABLE IF NOT EXISTS `interaction_event_consume` (
  `consumer_name` varchar(64) NOT NULL,
  `event_id` varchar(64) NOT NULL,
  `event_type` varchar(32) NOT NULL,
  `consume_time` datetime NOT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`consumer_name`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息消费幂等记录';

ALTER TABLE `notify_msg`
  ADD COLUMN `event_id` varchar(64) DEFAULT NULL COMMENT '来源事件ID' AFTER `id`,
  ADD COLUMN `business_key` varchar(255) DEFAULT NULL COMMENT '通知业务唯一键' AFTER `event_id`;

-- 清理由旧“查询后插入”竞态产生的重复数据，并为存量交互通知回填业务键。
DELETE old_msg FROM `notify_msg` old_msg
INNER JOIN `notify_msg` newer ON old_msg.notify_user_id=newer.notify_user_id
 AND old_msg.operate_user_id=newer.operate_user_id AND old_msg.type=newer.type
 AND old_msg.related_id=newer.related_id AND old_msg.id < newer.id
WHERE old_msg.type IN (3,4,5);
UPDATE `notify_msg`
SET `business_key`=CONCAT(CASE `type` WHEN 3 THEN 'PRAISE' WHEN 4 THEN 'COLLECT' ELSE 'FOLLOW' END,
                          ':',`operate_user_id`,':',`related_id`,':',`notify_user_id`)
WHERE `type` IN (3,4,5);
ALTER TABLE `notify_msg`
  ADD UNIQUE KEY `uk_notify_event_id` (`event_id`),
  ADD UNIQUE KEY `uk_notify_business_key` (`business_key`);

-- user_foot.idx_user_doucument 与 user_relation.uk_user_follow 已对业务关系提供唯一约束；
-- notify_msg 的两个唯一键补齐通知侧原先“查询后插入”的并发缺口。
