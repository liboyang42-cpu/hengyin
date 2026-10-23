-- 商家主表 mms_merchant 的真库 IT 建表脚本。
-- 基线来源:生产 schema-only 快照 base_schema_20260818.sql(生产 chengyinhubdb @ MySQL 8.0.45,2026-08-18)。
-- 同步投影(按合入顺序):2026-08-20 service_line(service_tag/service_text)、
--   2026-08-24 mms_merchant_member_index(idx_mms_merchant_member)、
--   2026-09-12 derivative_benefits(derivative_benefits)、
--   2026-09-15 disable_reason(R9-35,平台停用原因;只由平台停用入口写入,启用时清空)。
--   2026-10-22 merchant_wechat_qrcode(wechat_qrcode)、merchant_category_ids(category_ids)。
-- 因此本文件是「基线快照 + 已合入迁移投影」,不是单次 SHOW CREATE TABLE 的未裁剪副本。
--
-- 为什么需要:平台停用/启用写入链路(disable_reason)与 MmsMerchantMapper 的列映射,
-- 必须拿生产真实 schema 验;手写简化表等于拿假 schema 验真映射。
-- 由 ItSchema.apply 先 DROP 再 CREATE,不沿用环境里恰好剩下的旧表。
-- ⚠️ 生产再有加列时这份快照会过期,表现是 IT 变红(缺列),不是静默放行 —— 红了就按上面的基线+投影口径重新同步。
CREATE TABLE `mms_merchant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '会员ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家名称',
  `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '微信二维码',
  `category_id` int DEFAULT '0' COMMENT '分类ID',
  `wechat_qrcode` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '门店微信二维码图片URL',
  `category_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '行业类型多选(逗号分隔sys_category.id)',
  `logo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家LOGO',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '介绍',
  `derivatives` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '衍生品',
  `derivative_benefits` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '装修页衍生权益文字',
  `website` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌网址',
  `preference` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌偏好',
  `phone` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '联系电话',
  `address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '商户地址',
  `business_time` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业时间',
  `business_license` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业执照',
  `status` int DEFAULT '0' COMMENT '入住审核状态0 待审核  1审核通过  2审核失败',
  `reson` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `disable_reason` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '平台停用原因;只由平台停用入口写入,启用时清空',
  `account_status` int DEFAULT '0' COMMENT '账号状态：1启用  2禁用',
  `business_status` tinyint NOT NULL DEFAULT '1' COMMENT '营业状态 1营业中 0已打烊',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `capacity` int DEFAULT NULL COMMENT '可容纳/承接人数',
  `available_time` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可承接时段',
  `suit_activity_types` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '适合活动类型(逗号分隔)',
  `charge_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '承接收费 0免费 1收费',
  `demand` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '诉求',
  `coop_open` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否开放被达人对接 0否1是',
  `cover_image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-封面头图URL',
  `slogan` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-一句话slogan',
  `city_role` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-城市角色名',
  `story_title` varchar(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-品牌故事标题(正文用description)',
  `gallery` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '店铺装修-门店相册JSON数组',
  `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-特色标签JSON/逗号',
  `featured_type` tinyint DEFAULT '0' COMMENT '店铺装修-招牌主推类型0无/1活动主题/2券',
  `featured_id` bigint DEFAULT NULL COMMENT '店铺装修-招牌主推目标id',
  `location_lat` decimal(10,7) DEFAULT NULL COMMENT '商家纬度(手动打点/LBS)',
  `location_lng` decimal(10,7) DEFAULT NULL COMMENT '商家经度',
  `location_verified` tinyint DEFAULT '0' COMMENT '0未定位 1商家地图选点 2按地址反查推断',
  `service_tag` varchar(8) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-服务行短标签(如¥5/免费)',
  `service_text` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-服务行文案(如健身体验卡),空=不展示服务行',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_mms_merchant_member` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='商家表'
