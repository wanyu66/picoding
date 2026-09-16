RENAME TABLE `interaction_event_outbox` TO `domain_event_outbox`,
             `interaction_event_consume` TO `domain_event_consume`;

ALTER TABLE `domain_event_outbox` COMMENT='统一领域事件事务Outbox';
ALTER TABLE `domain_event_consume` COMMENT='统一领域事件消费幂等记录';
