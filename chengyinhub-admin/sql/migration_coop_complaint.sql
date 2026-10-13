-- P3 责任链:客诉工单表。平台单一入口受理 → 先行垫付 → 过错方扣划 → 关闭。
-- 有未结工单(status<3)时冻结该主题结算行放款(payoutDueCoopSettlements 跳过)。幂等。
CREATE TABLE IF NOT EXISTS `coop_complaint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint NOT NULL COMMENT '关联主题',
  `registration_id` bigint DEFAULT NULL COMMENT '关联报名(可空)',
  `reporter_member_id` bigint NOT NULL COMMENT '投诉玩家',
  `respondent_type` varchar(16) DEFAULT NULL COMMENT '过错方类型:initiator/club/merchant',
  `respondent_member_id` bigint DEFAULT NULL COMMENT '过错方 member_id(俱乐部填主理人)',
  `status` tinyint DEFAULT 0 COMMENT '0受理 1已垫付 2追偿中 3关闭',
  `advance_amount` decimal(12,2) DEFAULT NULL COMMENT '先行垫付额',
  `fault_ratio` int DEFAULT NULL COMMENT '过错比例 0-100',
  `deduct_amount` decimal(12,2) DEFAULT NULL COMMENT '从过错方扣划额',
  `reason` varchar(500) DEFAULT NULL COMMENT '投诉事由',
  `handle_note` varchar(500) DEFAULT NULL COMMENT '处理说明',
  `handler_id` bigint DEFAULT NULL COMMENT '处理人',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` tinyint DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_complaint_topic` (`topic_id`,`status`),
  KEY `idx_complaint_respondent` (`respondent_member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='coop 客诉工单(责任链)';
