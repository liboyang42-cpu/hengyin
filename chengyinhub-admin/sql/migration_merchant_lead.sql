-- 商家目录/线索表(含未注册商家):附近商家可展示可电话联系,不强制先注册小程序
-- 幂等:IF NOT EXISTS,可重复执行
CREATE TABLE IF NOT EXISTS `merchant_lead` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '绑定的小程序账号 member_id,未注册商家为空',
  `name` varchar(128) NOT NULL COMMENT '商家名称',
  `phone` varchar(32) DEFAULT NULL COMMENT '联系电话',
  `address` varchar(255) DEFAULT NULL COMMENT '地址',
  `longitude` varchar(32) DEFAULT NULL COMMENT '经度',
  `latitude` varchar(32) DEFAULT NULL COMMENT '纬度',
  `category` varchar(64) DEFAULT NULL COMMENT '商家类型',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签',
  `source` varchar(32) DEFAULT 'offline' COMMENT '来源:registered/offline/bd_import 等',
  `contact_open` tinyint DEFAULT 1 COMMENT '是否开放电话联系 1开0关',
  `coop_open` tinyint DEFAULT 1 COMMENT '是否开放线上协作 1开0关',
  `status` tinyint DEFAULT 1 COMMENT '1可展示 0隐藏',
  `del_flag` tinyint DEFAULT 0 COMMENT '0存在 2删除',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lead_geo` (`latitude`,`longitude`),
  KEY `idx_lead_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家目录/线索(含未注册商家)';
