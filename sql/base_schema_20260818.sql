-- ============================================================================
-- 城瘾 base schema 基线快照（结构，零业务数据）
--
-- 来源：生产 chengyinhubdb @ MySQL 8.0.45，2026-08-18
--       mysqldump --no-data --routines --triggers --events --single-transaction
--                --set-gtid-purged=OFF --skip-add-drop-table --skip-add-locks
--
-- 为什么要有这个文件：
--   本仓此前【没有 base schema】。全部 SQL 只 CREATE 了 182 张表，而 mapper XML 引用
--   205 张，差集 52 张承重表（ums_member / oms_ticket / cms_activity / cms_topic_node /
--   mms_merchant / cms_registration_* / oms_order* 等）的建表语句在仓库任何 .sql 里都不存在，
--   只活在生产库上。后果：
--     · 真库 IT 只能靠手写裁剪 fixture（explore-alliance-it-schema.sql 只有 10 张表）
--     · 端到端链路验证（第 5 批 5-1）在本地根本建不出可跑的库
--     · 「建表语句不在本仓」直接让 oms_ticket.del_flag 默认值、9 条唯一键可空性
--       这类判定悬空（R11-C / R13-B 实证）
--     · 生产库一旦丢失，schema 无法从仓库重建
--
-- ⚠️ 这个文件【不是 migration】，不进 migration 台账、不由 runner 执行、不在生产上跑。
--    它只用于：① 本地/CI 从零重建一个可跑的 schema ② 作为表结构的可追溯真源。
--    有意去掉了 DROP TABLE 语句 —— 只能导进空库。
--
-- ⚠️ 增量结构变更仍然走 chengyinhub-admin/sql/migration_*.sql，不要直接改这个文件。
--    需要刷新快照时重新 dump，并在 commit message 里写明 dump 时点。
-- ============================================================================

-- MySQL dump 10.13  Distrib 8.0.45, for Linux (x86_64)
--
-- Host: 127.0.0.1    Database: chengyinhubdb
-- ------------------------------------------------------
-- Server version	8.0.45

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `_bak_20260705_club`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260705_club` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '拥有者会员ID',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '俱乐部名称',
  `logo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'LOGO/头像',
  `cover` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '封面图',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '简介',
  `club_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '俱乐部类型大类(单选)',
  `club_types` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动方向(逗号分隔,原俱乐部类型多选降级)',
  `activity_prefs` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动倾向(逗号分隔)',
  `experience` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动经验',
  `join_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '加入理由',
  `case_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '案例展示图(逗号分隔URL)',
  `category_id` bigint DEFAULT NULL COMMENT '分类ID(SysCategory)',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '地点',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '所在城市',
  `keywords` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '核心关键词(逗号分隔,≤3)',
  `style` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '风格/调性',
  `max_event_size` int DEFAULT NULL COMMENT '可承接最大活动人数',
  `avg_event_size` int DEFAULT NULL COMMENT '常规活动人数',
  `can_design_route` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计路线 0否1是',
  `can_design_task` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计任务 0否1是',
  `can_npc` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否提供NPC 0否1是',
  `can_merchant_coop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否商家合作 0否1是',
  `has_guide_cert` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否有导游/旅游资质 0否1是',
  `cert_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '资质证书图(逗号分隔URL)',
  `leader_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '负责人',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '联系电话',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '审核状态 0待审核 1通过 2驳回',
  `level` tinyint(1) NOT NULL DEFAULT '0' COMMENT '俱乐部定级 0未定级 1-5',
  `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '运营标签(逗号分隔)',
  `reject_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '驳回原因',
  `audit_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '定级审核人',
  `audit_time` datetime DEFAULT NULL COMMENT '定级审核时间',
  `member_count` int NOT NULL DEFAULT '0' COMMENT '成员数(冗余)',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '0存在 2删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `_bak_20260705_club_leader`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260705_club_leader` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '主理人会员ID(唯一,一人一档)',
  `leader_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '真实/对外昵称',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '联系电话(内部审核用)',
  `identity` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '身份说明',
  `co_founders` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '联合创始人',
  `has_experience` tinyint(1) DEFAULT NULL COMMENT '是否有过往组织经验 0否1是',
  `experience` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '过往活动经验(场次档)',
  `max_event_size` int DEFAULT NULL COMMENT '最大活动人数',
  `avg_event_size` int DEFAULT NULL COMMENT '平均活动人数',
  `can_design_route` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计路线 0否1是',
  `can_design_task` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计任务 0否1是',
  `can_npc` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否带NPC/角色 0否1是',
  `can_merchant_coop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否与商家联动 0否1是',
  `has_guide_cert` tinyint(1) NOT NULL DEFAULT '0' COMMENT '有无导游证/旅游资质 0否1是',
  `cert_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '资质/案例图(逗号分隔URL)',
  `level` tinyint(1) NOT NULL DEFAULT '0' COMMENT '等级 0未定级 1-5=L1-L5',
  `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '标签(逗号分隔)',
  `audit_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '定级操作人',
  `audit_time` datetime DEFAULT NULL COMMENT '定级时间',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1正常',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '0存在 2删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `_bak_20260705_member_capability`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260705_member_capability` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `capability` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '能力:club_leader/merchant',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1有效 0已撤销',
  `granted_at` datetime DEFAULT NULL,
  `granted_by` bigint DEFAULT NULL COMMENT '授予人(0=系统)',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `_bak_20260705_ums_member`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260705_ums_member` (
  `id` bigint unsigned NOT NULL DEFAULT '0',
  `user_type` int DEFAULT '1' COMMENT '用户类型：1普通会员 2商户会员',
  `role` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'player' COMMENT '会员角色 player/club/merchant',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录手机号码',
  `password` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '账号密码',
  `level_id` int DEFAULT '0' COMMENT '会员等级',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '真实姓名',
  `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '微信二维码',
  `status` int DEFAULT '1' COMMENT '状态 1启用  2禁用',
  `freeze_end_time` datetime DEFAULT NULL COMMENT '账号冻结结束时间',
  `invite_user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '邀请人ID',
  `login_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后登录IP',
  `login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '昵称',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '头像',
  `sex` tinyint DEFAULT '0' COMMENT '性别：0未知 1男 2女',
  `wx_open_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '微信小程序OpenID',
  `union_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '微信开放平台unionid',
  `apple_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Apple sub',
  `area_id` bigint DEFAULT NULL COMMENT '所在地',
  `birthday` datetime DEFAULT NULL COMMENT '出生日期',
  `mark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '会员备注',
  `tag_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '擅长领域ID集合，以逗号分隔',
  `case_pics` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '作品图片，逗号隔开',
  `case_introducton` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '作品介绍',
  `website` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '个人网址',
  `work_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '工作地点',
  `introduction` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '个人介绍',
  `point` int DEFAULT '0' COMMENT '积分',
  `balance` decimal(11,2) DEFAULT '0.00' COMMENT '余额',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `_bak_20260708_cms_topic`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260708_cms_topic` (
  `id` bigint unsigned NOT NULL DEFAULT '0',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID-用户和后台发布（商家不能发布）',
  `topic_type` int DEFAULT NULL COMMENT '主题类型：1平台主题  2自定义主题',
  `topic_id` bigint DEFAULT NULL COMMENT '选择主题的ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题名称',
  `subtitle` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '副标题',
  `description` varchar(8000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题描述',
  `address_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '地址',
  `total_time` int DEFAULT '0' COMMENT '总时长，单位秒',
  `total_mileage` decimal(10,2) DEFAULT NULL COMMENT '总里程KM',
  `location_count` int DEFAULT '0' COMMENT '地点数',
  `template_count` int DEFAULT '0' COMMENT '模版数',
  `start_date` datetime DEFAULT NULL COMMENT '主题开始日期',
  `end_date` datetime DEFAULT NULL COMMENT '主题结束日期',
  `img_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题长图（单张）',
  `img_arr` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题轮播图（多张）分号隔开',
  `category_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '主题类别IDS，逗号隔开',
  `status` tinyint(1) DEFAULT '0' COMMENT '后台审核状态：0->待审核；1->审核通过；2->审核失败；',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `user_status` tinyint(1) DEFAULT '0' COMMENT '用户端状态：0未上架  1已上架',
  `merchant_status` tinyint(1) DEFAULT NULL COMMENT '商家端状态：0未上架  1已上架 ',
  `merchant_sign_up_start_date` datetime DEFAULT NULL COMMENT '商家报名开始时间',
  `merchant_sign_up_end_date` datetime DEFAULT NULL COMMENT '商家报名结束时间',
  `recruit_deadline` datetime DEFAULT NULL COMMENT '招募截止时间(到期触发 TopicRecruitDeadlineTask 判定)',
  `recruit_extend_count` tinyint(1) NOT NULL DEFAULT '0' COMMENT '招募期已延长次数(最多 2 次)',
  `lifecycle` tinyint(1) NOT NULL DEFAULT '0' COMMENT '生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `is_sync` tinyint(1) DEFAULT '0' COMMENT '是否同步到创意广场1 同步',
  `is_recommend` tinyint DEFAULT '0' COMMENT '是否推荐 1 是 0 否',
  `check_status` tinyint DEFAULT '0' COMMENT '后台查阅状态：0->待审核；1->审核通过；2->审核失败；',
  `is_settlement` tinyint DEFAULT '0' COMMENT '是否结算 1是 0否',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `publish_mode` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'pro' COMMENT '发布来源 simple建议版 pro专业版',
  `club_id` bigint DEFAULT NULL COMMENT '所属俱乐部id 城市定向团',
  `self_play` tinyint DEFAULT '0' COMMENT 'M2 是否开放自玩票 0否1是(仅城市定向)',
  `self_play_price` decimal(10,2) DEFAULT NULL COMMENT 'M2 自玩票价格(元)',
  `self_play_quota` int DEFAULT NULL COMMENT 'M2 自玩票数/配额(NULL 或 <=0 = 不限)',
  `xp_budget` int DEFAULT '3000' COMMENT 'M7 主题探索值预算',
  `finish_medal_name` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'P0.5 通关勋章名(空=默认 主题名·通关)',
  `finish_medal_img` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'P0.5 通关勋章图',
  `audio_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题音频URL(OSS)',
  `audio_duration` int DEFAULT NULL COMMENT '主题音频时长(秒)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `_bak_20260708_member_capability`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260708_member_capability` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `capability` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '能力:club_leader/merchant',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1有效 0已撤销',
  `granted_at` datetime DEFAULT NULL,
  `granted_by` bigint DEFAULT NULL COMMENT '授予人(0=系统)',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `_bak_20260708_mms_merchant`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260708_mms_merchant` (
  `id` bigint unsigned NOT NULL DEFAULT '0',
  `member_id` bigint DEFAULT NULL COMMENT '会员ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家名称',
  `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '微信二维码',
  `category_id` int DEFAULT '0' COMMENT '分类ID',
  `logo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家LOGO',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '介绍',
  `derivatives` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '衍生品',
  `website` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌网址',
  `preference` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌偏好',
  `phone` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '联系电话',
  `address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '商户地址',
  `business_time` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业时间',
  `business_license` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业执照',
  `status` int DEFAULT '0' COMMENT '入住审核状态0 待审核  1审核通过  2审核失败',
  `reson` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `account_status` int DEFAULT '0' COMMENT '账号状态：1启用  2禁用',
  `business_status` tinyint NOT NULL DEFAULT '1' COMMENT '营业状态 1营业中 0已打烊',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `capacity` int DEFAULT NULL COMMENT '可容纳/承接人数',
  `available_time` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可承接时段',
  `suit_activity_types` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '适合活动类型(逗号分隔)',
  `charge_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '承接收费 0免费 1收费',
  `demand` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '诉求',
  `coop_open` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否开放被达人对接 0否1是',
  `cover_image` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-封面头图URL',
  `slogan` varchar(60) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-一句话slogan',
  `city_role` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-城市角色名',
  `story_title` varchar(60) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-品牌故事标题(正文用description)',
  `gallery` text COLLATE utf8mb4_general_ci COMMENT '店铺装修-门店相册JSON数组',
  `tags` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-特色标签JSON/逗号',
  `featured_type` tinyint DEFAULT '0' COMMENT '店铺装修-招牌主推类型0无/1活动主题/2券',
  `featured_id` bigint DEFAULT NULL COMMENT '店铺装修-招牌主推目标id',
  `location_lat` decimal(10,7) DEFAULT NULL COMMENT '商家纬度(手动打点/LBS)',
  `location_lng` decimal(10,7) DEFAULT NULL COMMENT '商家经度',
  `location_verified` tinyint DEFAULT '0' COMMENT '0未定位 1已定位'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `_bak_20260708_ums_member`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `_bak_20260708_ums_member` (
  `id` bigint unsigned NOT NULL DEFAULT '0',
  `user_type` int DEFAULT '1' COMMENT '用户类型：1普通会员 2商户会员',
  `role` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'player' COMMENT '会员角色 player/club/merchant',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录手机号码',
  `password` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '账号密码',
  `level_id` int DEFAULT '0' COMMENT '会员等级',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '真实姓名',
  `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '微信二维码',
  `status` int DEFAULT '1' COMMENT '状态 1启用  2禁用',
  `freeze_end_time` datetime DEFAULT NULL COMMENT '账号冻结结束时间',
  `invite_user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '邀请人ID',
  `login_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后登录IP',
  `login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '昵称',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '头像',
  `sex` tinyint DEFAULT '0' COMMENT '性别：0未知 1男 2女',
  `wx_open_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '微信小程序OpenID',
  `union_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '微信开放平台unionid',
  `apple_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Apple sub',
  `area_id` bigint DEFAULT NULL COMMENT '所在地',
  `birthday` datetime DEFAULT NULL COMMENT '出生日期',
  `mark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '会员备注',
  `tag_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '擅长领域ID集合，以逗号分隔',
  `case_pics` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '作品图片，逗号隔开',
  `case_introducton` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '作品介绍',
  `website` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '个人网址',
  `work_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '工作地点',
  `introduction` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '个人介绍',
  `point` int DEFAULT '0' COMMENT '积分',
  `balance` decimal(11,2) DEFAULT '0.00' COMMENT '余额',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `account_deregistration`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `account_deregistration` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'PENDING/CANCELLED/COMPLETED/FAILED',
  `apply_time` datetime NOT NULL,
  `execute_after` datetime NOT NULL,
  `cancel_time` datetime DEFAULT NULL,
  `complete_time` datetime DEFAULT NULL,
  `failure_reason` varchar(500) DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime DEFAULT NULL,
  `pending_member` bigint GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'PENDING') then `member_id` else NULL end)) VIRTUAL COMMENT 'PENDING时=member_id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dereg_pending_member` (`pending_member`),
  KEY `idx_deregister_member_status` (`member_id`,`status`,`apply_time`),
  KEY `idx_deregister_execute` (`status`,`execute_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号注销申请与冷静期执行记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `agreement_doc`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agreement_doc` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_type` varchar(32) NOT NULL COMMENT 'privacy/user_agreement',
  `version` varchar(32) NOT NULL,
  `title` varchar(100) NOT NULL,
  `content` longtext NOT NULL,
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0草稿 1生效',
  `publish_time` datetime DEFAULT NULL,
  `active_doc_type` varchar(32) GENERATED ALWAYS AS ((case when (`status` = 1) then `doc_type` else NULL end)) VIRTUAL COMMENT '生效时=doc_type',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agreement_doc_type_version` (`doc_type`,`version`),
  UNIQUE KEY `uk_agreement_active_type` (`active_doc_type`),
  KEY `idx_agreement_doc_active` (`doc_type`,`status`,`publish_time`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协议文档版本';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_generation_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_generation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `role` varchar(16) DEFAULT NULL,
  `biz_type` varchar(32) NOT NULL,
  `provider` varchar(16) DEFAULT NULL,
  `input_brief` varchar(512) DEFAULT NULL,
  `output_brief` varchar(512) DEFAULT NULL,
  `safety_flag` tinyint DEFAULT '0',
  `latency_ms` int DEFAULT '0',
  `token_est` int DEFAULT '0',
  `trace_id` varchar(40) DEFAULT NULL,
  `del_flag` int DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  `token_in` int DEFAULT '0' COMMENT '输入token',
  `token_out` int DEFAULT '0' COMMENT '输出token',
  PRIMARY KEY (`id`),
  KEY `idx_ai_gen_member` (`member_id`,`create_time`),
  KEY `idx_ai_gen_biz` (`biz_type`),
  KEY `idx_ai_gen_trace` (`trace_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI生成日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_merchant_match_snapshot`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_merchant_match_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `trace_id` varchar(40) NOT NULL,
  `idea` varchar(512) DEFAULT NULL,
  `conditions_json` text,
  `candidates_json` text,
  `choice_json` text,
  `del_flag` int DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_snap_member` (`member_id`,`create_time`),
  KEY `idx_ai_snap_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI组题候选快照';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `audit_record`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint NOT NULL COMMENT '审核任务ID',
  `action` varchar(40) NOT NULL COMMENT '动作',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `reason_code` varchar(60) DEFAULT NULL COMMENT '原因编码',
  `reason_text` varchar(500) DEFAULT NULL COMMENT '原因说明',
  `snapshot_json` mediumtext COMMENT '审核快照',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_record_task` (`task_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3统一审核记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `audit_task`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type` varchar(40) NOT NULL COMMENT '业务类型',
  `biz_id` bigint NOT NULL COMMENT '业务ID',
  `submitter_id` bigint DEFAULT NULL COMMENT '提交人会员ID',
  `source_type` varchar(40) DEFAULT NULL COMMENT '来源类型',
  `risk_level` varchar(20) NOT NULL DEFAULT 'NORMAL' COMMENT '风险等级',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0待审 1通过 2驳回 3升级 4取消',
  `current_assignee` bigint DEFAULT NULL COMMENT '当前处理人',
  `submitted_at` datetime NOT NULL COMMENT '提交时间',
  `deadline_at` datetime DEFAULT NULL COMMENT '处理截止时间',
  `snapshot_json` mediumtext COMMENT '提交快照',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `open_guard` tinyint GENERATED ALWAYS AS ((case when (`status` in (0,3)) then 1 else NULL end)) STORED COMMENT '未结任务唯一闸：终态为NULL以允许重复提审',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_biz_open` (`biz_type`,`biz_id`,`open_guard`),
  KEY `idx_audit_status_deadline` (`status`,`deadline_at`),
  KEY `idx_audit_submitter` (`submitter_id`,`submitted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3统一审核任务';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `biz_event_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_event_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_name` varchar(80) NOT NULL COMMENT '事件名称',
  `member_id` bigint DEFAULT NULL COMMENT '会员ID',
  `anonymous_id` varchar(80) DEFAULT NULL COMMENT '匿名ID',
  `session_id` varchar(80) DEFAULT NULL COMMENT '会话ID',
  `source` varchar(80) DEFAULT NULL COMMENT '来源',
  `page_path` varchar(255) DEFAULT NULL COMMENT '页面路径',
  `biz_type` varchar(40) DEFAULT NULL COMMENT '业务类型',
  `biz_id` bigint DEFAULT NULL COMMENT '业务ID',
  `city_code` varchar(40) DEFAULT NULL COMMENT '城市编码',
  `properties_json` text COMMENT '扩展属性JSON',
  `idempotency_key` varchar(120) DEFAULT NULL COMMENT '幂等键',
  `occurred_at` datetime NOT NULL COMMENT '发生时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_event_idem` (`event_name`,`member_id`,`idempotency_key`),
  KEY `idx_biz_event_name_time` (`event_name`,`occurred_at`),
  KEY `idx_biz_event_member_time` (`member_id`,`occurred_at`),
  KEY `idx_biz_event_biz_time` (`biz_type`,`biz_id`,`occurred_at`),
  KEY `idx_biz_event_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=215 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3行为事件日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `biz_funnel_daily`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `biz_funnel_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `funnel_code` varchar(60) NOT NULL COMMENT '漏斗编码',
  `step_code` varchar(60) NOT NULL COMMENT '步骤编码',
  `source` varchar(80) DEFAULT NULL COMMENT '来源',
  `city_code` varchar(40) DEFAULT NULL COMMENT '城市编码',
  `user_count` bigint NOT NULL DEFAULT '0' COMMENT '用户数',
  `event_count` bigint NOT NULL DEFAULT '0' COMMENT '事件数',
  `conversion_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '转化率',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_funnel_daily` (`stat_date`,`funnel_code`,`step_code`,`source`,`city_code`),
  KEY `idx_funnel_daily_code_date` (`funnel_code`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3每日漏斗聚合';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '拥有者会员ID',
  `name` varchar(64) NOT NULL COMMENT '俱乐部名称',
  `logo` varchar(255) DEFAULT NULL COMMENT 'LOGO/头像',
  `cover` varchar(255) DEFAULT NULL COMMENT '封面图',
  `description` varchar(1000) DEFAULT NULL COMMENT '简介',
  `club_type` varchar(32) DEFAULT NULL COMMENT '俱乐部类型大类(单选)',
  `club_types` varchar(500) DEFAULT NULL COMMENT '活动方向(逗号分隔,原俱乐部类型多选降级)',
  `activity_prefs` varchar(500) DEFAULT NULL COMMENT '活动倾向(逗号分隔)',
  `experience` varchar(64) DEFAULT NULL COMMENT '活动经验',
  `join_reason` varchar(500) DEFAULT NULL COMMENT '加入理由',
  `case_images` text COMMENT '案例展示图(逗号分隔URL)',
  `category_id` bigint DEFAULT NULL COMMENT '分类ID(SysCategory)',
  `address` varchar(255) DEFAULT NULL COMMENT '地点',
  `city` varchar(64) DEFAULT NULL COMMENT '所在城市',
  `keywords` varchar(255) DEFAULT NULL COMMENT '核心关键词(逗号分隔,≤3)',
  `style` varchar(64) DEFAULT NULL COMMENT '风格/调性',
  `max_event_size` int DEFAULT NULL COMMENT '可承接最大活动人数',
  `avg_event_size` int DEFAULT NULL COMMENT '常规活动人数',
  `can_design_route` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计路线 0否1是',
  `can_design_task` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计任务 0否1是',
  `can_npc` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否提供NPC 0否1是',
  `can_merchant_coop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否商家合作 0否1是',
  `has_guide_cert` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否有导游/旅游资质 0否1是',
  `cert_images` text COMMENT '资质证书图(逗号分隔URL)',
  `leader_name` varchar(64) DEFAULT NULL COMMENT '负责人',
  `phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '审核状态 0待审核 1通过 2驳回',
  `level` tinyint(1) NOT NULL DEFAULT '0' COMMENT '俱乐部定级 0未定级 1-5',
  `tags` varchar(255) DEFAULT NULL COMMENT '运营标签(逗号分隔)',
  `reject_reason` varchar(255) DEFAULT NULL COMMENT '驳回原因',
  `audit_by` varchar(64) DEFAULT NULL COMMENT '定级审核人',
  `audit_time` datetime DEFAULT NULL COMMENT '定级审核时间',
  `member_count` int NOT NULL DEFAULT '0' COMMENT '成员数(冗余)',
  `priority_signup_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '成员优先报名 0否1是',
  `member_discount_price` decimal(10,2) DEFAULT NULL COMMENT '成员优惠价(元，NULL=不启用)',
  `member_reserved_quota` int NOT NULL DEFAULT '0' COMMENT '成员保留报名名额',
  `join_policy` tinyint(1) NOT NULL DEFAULT '0' COMMENT '加入策略:0公开直进 1需主理人审批',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  PRIMARY KEY (`id`),
  KEY `idx_club_member` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=990004 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_bak_20260809`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_bak_20260809` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '拥有者会员ID',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '俱乐部名称',
  `logo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'LOGO/头像',
  `cover` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '封面图',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '简介',
  `club_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '俱乐部类型大类(单选)',
  `club_types` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动方向(逗号分隔,原俱乐部类型多选降级)',
  `activity_prefs` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动倾向(逗号分隔)',
  `experience` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动经验',
  `join_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '加入理由',
  `case_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '案例展示图(逗号分隔URL)',
  `category_id` bigint DEFAULT NULL COMMENT '分类ID(SysCategory)',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '地点',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '所在城市',
  `keywords` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '核心关键词(逗号分隔,≤3)',
  `style` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '风格/调性',
  `max_event_size` int DEFAULT NULL COMMENT '可承接最大活动人数',
  `avg_event_size` int DEFAULT NULL COMMENT '常规活动人数',
  `can_design_route` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计路线 0否1是',
  `can_design_task` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计任务 0否1是',
  `can_npc` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否提供NPC 0否1是',
  `can_merchant_coop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否商家合作 0否1是',
  `has_guide_cert` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否有导游/旅游资质 0否1是',
  `cert_images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '资质证书图(逗号分隔URL)',
  `leader_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '负责人',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '联系电话',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '审核状态 0待审核 1通过 2驳回',
  `level` tinyint(1) NOT NULL DEFAULT '0' COMMENT '俱乐部定级 0未定级 1-5',
  `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '运营标签(逗号分隔)',
  `reject_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '驳回原因',
  `audit_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '定级审核人',
  `audit_time` datetime DEFAULT NULL COMMENT '定级审核时间',
  `member_count` int NOT NULL DEFAULT '0' COMMENT '成员数(冗余)',
  `priority_signup_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '成员优先报名 0否1是',
  `member_discount_price` decimal(10,2) DEFAULT NULL COMMENT '成员优惠价(元，NULL=不启用)',
  `member_reserved_quota` int NOT NULL DEFAULT '0' COMMENT '成员保留报名名额',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '0存在 2删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_lead_checkin`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_lead_checkin` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `arrived_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_arrive` (`activity_id`,`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P1 集合签到(幂等)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_lead_state`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_lead_state` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `leader_member_id` bigint NOT NULL,
  `current_chapter_id` bigint DEFAULT NULL COMMENT '当前已放行章节(队长控)',
  `broadcast_text` varchar(200) DEFAULT NULL,
  `broadcast_at` datetime DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1=集合中 2=行进 3=已结算',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P1 带领场状态';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_leader`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_leader` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '主理人会员ID(唯一,一人一档)',
  `leader_name` varchar(64) DEFAULT NULL COMMENT '真实/对外昵称',
  `phone` varchar(20) DEFAULT NULL COMMENT '联系电话(内部审核用)',
  `identity` varchar(255) DEFAULT NULL COMMENT '身份说明',
  `co_founders` varchar(255) DEFAULT NULL COMMENT '联合创始人',
  `has_experience` tinyint(1) DEFAULT NULL COMMENT '是否有过往组织经验 0否1是',
  `experience` varchar(64) DEFAULT NULL COMMENT '过往活动经验(场次档)',
  `max_event_size` int DEFAULT NULL COMMENT '最大活动人数',
  `avg_event_size` int DEFAULT NULL COMMENT '平均活动人数',
  `can_design_route` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计路线 0否1是',
  `can_design_task` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否设计任务 0否1是',
  `can_npc` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否带NPC/角色 0否1是',
  `can_merchant_coop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '能否与商家联动 0否1是',
  `has_guide_cert` tinyint(1) NOT NULL DEFAULT '0' COMMENT '有无导游证/旅游资质 0否1是',
  `cert_images` text COMMENT '资质/案例图(逗号分隔URL)',
  `level` tinyint(1) NOT NULL DEFAULT '0' COMMENT '等级 0未定级 1-5=L1-L5',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `audit_by` varchar(64) DEFAULT NULL COMMENT '定级操作人',
  `audit_time` datetime DEFAULT NULL COMMENT '定级时间',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1正常',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_club_leader_member` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部主理人档案与定级';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_member`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `club_id` bigint NOT NULL COMMENT '俱乐部ID',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `role` tinyint(1) NOT NULL DEFAULT '0' COMMENT '成员角色 0成员(群主=club.member_id)',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '成员状态:0待审批 1正常 2已拒绝',
  `join_time` datetime DEFAULT NULL COMMENT '加入时间',
  `del_flag` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_club_member_uniq` (`club_id`,`member_id`),
  KEY `idx_club` (`club_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部成员';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_member_bak_20260809`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_member_bak_20260809` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `club_id` bigint NOT NULL COMMENT '俱乐部ID',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `role` tinyint(1) NOT NULL DEFAULT '0' COMMENT '成员角色 0成员(群主=club.member_id)',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1正常',
  `join_time` datetime DEFAULT NULL COMMENT '加入时间',
  `del_flag` int NOT NULL DEFAULT '0'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_post`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_post` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `club_id` bigint NOT NULL COMMENT '俱乐部ID',
  `author_member_id` bigint NOT NULL COMMENT '作者会员ID',
  `content` text COMMENT '正文',
  `images` varchar(2000) DEFAULT NULL COMMENT '图片(逗号分隔URL)',
  `type` int DEFAULT '0' COMMENT '类型 0图文 1战报 2公告',
  `like_count` int DEFAULT '0' COMMENT '点赞数',
  `comment_count` int DEFAULT '0' COMMENT '评论数',
  `del_flag` int DEFAULT '0' COMMENT '0存在 2删除',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_club_post_club` (`club_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部动态';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_post_comment`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_post_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id` bigint NOT NULL COMMENT '动态ID',
  `member_id` bigint NOT NULL COMMENT '评论会员ID',
  `content` varchar(1000) DEFAULT NULL COMMENT '评论内容',
  `del_flag` int DEFAULT '0' COMMENT '0存在 2删除',
  `create_time` datetime DEFAULT NULL COMMENT '评论时间',
  PRIMARY KEY (`id`),
  KEY `idx_comment_post` (`post_id`,`del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部动态评论';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `club_post_like`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `club_post_like` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id` bigint NOT NULL COMMENT '动态ID',
  `member_id` bigint NOT NULL COMMENT '点赞会员ID',
  `create_time` datetime DEFAULT NULL COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_member` (`post_id`,`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部动态点赞';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_activity`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_activity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动名称',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动描述',
  `img_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动封面图',
  `img_arr` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动轮播图（多张）分号隔开',
  `category_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动类别IDS,逗号隔开',
  `address_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '场馆名称',
  `longitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址经度',
  `latitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址纬度',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地址',
  `day_note` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '当日运营备注',
  `status` int DEFAULT '0' COMMENT '后台审核状态：0->待审核；1->审核通过；2->审核失败；',
  `publish_status` int DEFAULT '0' COMMENT '发布状态：0下架中  1已上架',
  `cancel_time` datetime DEFAULT NULL COMMENT '取消时间,非空=活动已被主办方取消(已对未核销报名全额退款)',
  `cancel_reason` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '取消原因(主办方填写,展示给已报名用户)',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `start_date` datetime DEFAULT NULL COMMENT '活动开始日期',
  `end_date` datetime DEFAULT NULL COMMENT '活动结束日期',
  `template_id` int DEFAULT NULL COMMENT '我的模版ID',
  `topic_id` bigint DEFAULT NULL COMMENT '来源主题ID 商家报名主题生成时回填 独立创建活动为空',
  `check_status` tinyint DEFAULT '0' COMMENT '后台查阅状态：0->待审核；1->审核通过；2->审核失败；',
  `is_settlement` tinyint DEFAULT '0' COMMENT '是否结算 1是 0否',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `club_id` bigint DEFAULT NULL COMMENT 'M1-3 社群活动所属俱乐部ID(有值=社群活动,公域不出);NULL=公开活动',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_cms_activity_topic` (`topic_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='活动表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_activity_like`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_activity_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `data_id` bigint DEFAULT NULL COMMENT '活动ID',
  `like_type` bigint DEFAULT '1' COMMENT '类型：1已点赞',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='活动点赞表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_beta_game`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_beta_game` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '游戏名称',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '游戏描述',
  `img_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '游戏封面图',
  `img_arr` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '游戏轮播图（多张）分号隔开',
  `type_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题/活动',
  `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '游戏标签,逗号隔开',
  `status` int DEFAULT '0' COMMENT '后台审核状态：0下架  1上架',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `start_date` datetime DEFAULT NULL COMMENT '投票开始日期',
  `end_date` datetime DEFAULT NULL COMMENT '投票结束日期',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='游戏调查';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_beta_game_vote`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_beta_game_vote` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `game_id` bigint DEFAULT NULL COMMENT '游戏ID',
  `star` int DEFAULT NULL COMMENT '评分',
  `contents` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '评论内容',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='游戏调查投票表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_collaborators`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_collaborators` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_type` int DEFAULT NULL COMMENT '类型1、主题  2、活动 ',
  `data_id` bigint DEFAULT '0' COMMENT '主题ID/活动ID',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=71 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='活动/主题合作者表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_infomation`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_infomation` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '标题',
  `subtitle` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '副标题',
  `sort_id` int DEFAULT NULL COMMENT '排序',
  `contents` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '详细内容',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_member_template`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_member_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '模板ID',
  `original_template_id` bigint DEFAULT '0' COMMENT '引用的模板库ID(可为空)',
  `member_id` bigint NOT NULL COMMENT '发布用户ID',
  `category_id` bigint DEFAULT '0' COMMENT '模板类别ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '标题',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '介绍',
  `img_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '图片URL',
  `players` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '游戏人数',
  `usage_location` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '适用场景',
  `required_materials` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '所需材料',
  `duration` int DEFAULT NULL COMMENT '时长(分钟)',
  `difficulty` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '难度',
  `is_sync` tinyint(1) DEFAULT '0' COMMENT '是否同步到模板库1 同步',
  `rule_instructions` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '规则说明',
  `validation_method` tinyint(1) DEFAULT '0' COMMENT '验证方式:1、文字验证 2、图片验证  3、选项验证',
  `question_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题名称',
  `question_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '问题答案',
  `feedback_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '反馈文字',
  `coupon_id` bigint DEFAULT NULL COMMENT '反馈优惠券ID',
  `medal_img` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '勋章图片URL(俱乐部上传,空=该节点不发勋章)',
  `medal_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '勋章名称',
  `story_text` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '剧情文案',
  `story_img` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '剧情配图URL',
  `story_json` text COLLATE utf8mb4_general_ci COMMENT '剧情多节点JSON',
  `hint1` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '一级提示',
  `hint2` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '二级提示',
  `answer_reveal` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '兜底答案/解析',
  `photo_require_desc` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '拍照打卡要求描述',
  `photo_review` tinyint NOT NULL DEFAULT '0' COMMENT '拍照打卡是否需人工审核:0否 1是',
  `audio_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '语音讲解音频URL',
  `audio_duration` int DEFAULT NULL COMMENT '语音时长(秒)',
  `question_a` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项A',
  `question_b` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项B',
  `question_c` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项C',
  `question_d` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项D',
  `correct_answer` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '正确答案(A/B/C/D)',
  `activity_categoryids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动类别(多选)，逗号隔开',
  `status` int DEFAULT '0' COMMENT '后台审核状态：0->待审核；1->审核通过；2->审核失败；',
  `publish_status` int DEFAULT '0' COMMENT '发布状态：0下架中  1已上架',
  `draft_status` tinyint NOT NULL DEFAULT '1' COMMENT '草稿态:0草稿 1已发布',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `rating` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '评分-后台输入',
  `use_num` int DEFAULT '0' COMMENT '使用人数-后台输入',
  `check_status` tinyint DEFAULT '0' COMMENT '后台查阅状态：0->待审核；1->审核通过；2->审核失败；',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `xp_value` int DEFAULT NULL COMMENT 'M7 该游戏节点探索值',
  `interaction_type` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '玩法语义:hidden_menu/city_story_card/photo_spot/qr_checkin',
  `merchant_node_enabled` tinyint DEFAULT '0' COMMENT '是否作为点亮城市商家节点互动:0否 1是',
  `node_submit_status` tinyint DEFAULT '0' COMMENT '商家节点提交态:0草稿 1审核中 2已上线 3已驳回',
  `medal_style` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'glow' COMMENT '勋章样式:glow=二维发光/enamel=3D珐琅',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_member_id` (`member_id`) USING BTREE,
  KEY `idx_category_id` (`category_id`) USING BTREE,
  KEY `idx_is_sync` (`is_sync`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='我的模板表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_points_setting`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_points_setting` (
  `id` int NOT NULL COMMENT 'ID (1-8)',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '规则标题',
  `value` decimal(10,2) NOT NULL COMMENT '规则数值',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '规则描述',
  `type` tinyint DEFAULT '1' COMMENT '类型：1-积分规则 2-分润比例',
  `event_type` int DEFAULT NULL COMMENT '对应的事件类型（参考ums_member_points_detail.event_type）',
  `status` tinyint DEFAULT '1' COMMENT '状态：0-禁用 1-启用',
  `sort_order` int DEFAULT '0' COMMENT '排序',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在 2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_type_status` (`type`,`status`) USING BTREE,
  KEY `idx_sort_order` (`sort_order`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='积分规则与分润设置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_registration`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_registration` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '报名ID',
  `registration_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '报名编号(唯一)',
  `parent_order_no` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '父订单号 同一支付的多条记录共享',
  `request_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'BE-03 幂等键:前端结账 UUID',
  `request_fingerprint` char(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '服务端规范化请求指纹',
  `owner_type` int DEFAULT '0' COMMENT '拥有类型1、主题  2、活动',
  `owner_id` bigint NOT NULL COMMENT '主题ID/活动ID',
  `activity_title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动/主题名称(冗余)',
  `ticket_id` bigint NOT NULL COMMENT '票种ID',
  `ticket_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '票种名称(冗余)',
  `ticket_price` decimal(10,2) NOT NULL COMMENT '票种价格(冗余)',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `real_name` varchar(768) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '真实姓名(AES-GCM密文)',
  `phone` varchar(256) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号(AES-GCM密文)',
  `phone_hash` char(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号HMAC盲索引',
  `email` varchar(768) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱(AES-GCM密文)',
  `address` varchar(768) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地址(AES-GCM密文)',
  `address_id` bigint DEFAULT NULL COMMENT '地址ID',
  `participate_date` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '参与日期 yyyy-MM-dd（自由探索）',
  `order_num` int DEFAULT '0' COMMENT '订票数量',
  `original_amount` decimal(10,2) NOT NULL COMMENT '原始金额',
  `discount_amount` decimal(10,2) DEFAULT NULL COMMENT '折扣金额',
  `total_amount` decimal(10,2) NOT NULL COMMENT '应付总额',
  `point_used` int DEFAULT '0' COMMENT '使用积分数量',
  `point_deduct_amount` decimal(10,2) DEFAULT NULL COMMENT '积分抵扣金额',
  `point_deduct_rate` decimal(5,4) DEFAULT '0.0100' COMMENT '积分抵扣比例(1积分=0.01元)',
  `max_point_deduct` decimal(10,2) DEFAULT NULL COMMENT '最大可抵扣金额',
  `payable_amount` decimal(10,2) NOT NULL COMMENT '实际需支付金额',
  `payment_status` tinyint NOT NULL DEFAULT '0' COMMENT '支付状态：0待支付 1支付中 2已支付 3支付失败 4已退款',
  `payment_type` tinyint DEFAULT NULL COMMENT '支付方式：0纯积分 1纯微信 2积分+微信',
  `payment_time` datetime DEFAULT NULL COMMENT '支付时间',
  `transaction_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '支付交易号',
  `point_payment_amount` decimal(10,2) DEFAULT NULL COMMENT '积分支付金额',
  `wechat_payment_amount` decimal(10,2) DEFAULT NULL COMMENT '微信支付金额',
  `fee_platform_rate` decimal(10,4) DEFAULT NULL COMMENT '下单冻结平台费率',
  `registration_status` tinyint NOT NULL DEFAULT '1' COMMENT '报名状态：1待支付 2报名成功 3已取消 4已过期',
  `pay_expire_time` datetime NOT NULL COMMENT '支付过期时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `verification_code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核销码',
  `qrcode_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核销二维码图片URL',
  `verification_status` tinyint DEFAULT '0' COMMENT '核销状态：0未核销 1已核销',
  `verification_time` datetime DEFAULT NULL COMMENT '核销时间',
  `verifier_id` bigint DEFAULT NULL COMMENT '核销人ID',
  `points_returned` tinyint(1) DEFAULT '0' COMMENT '积分是否返还 0未返  1已返',
  `topic_id` bigint DEFAULT NULL COMMENT 'M2 自玩:主题通行证的主题ID(配合 owner_type/activity_id=0)',
  `purchase_kind` tinyint DEFAULT NULL COMMENT '购买类型 1有人带票GUIDED_TICKET/2自玩通行证SELF_PLAY_PASS/3自由探索票EXPLORE_PASS。NULL=存量未回填,读侧回落推断',
  `expires_at` datetime DEFAULT NULL COMMENT 'M2 自玩:通行证有效期(购后90天)',
  `refund_policy_code` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '退款政策标识(如EXPLORE_END_100_0)。下单时快照,NULL=存量单,读侧回落现取',
  `refund_policy_version` int DEFAULT NULL COMMENT '退款政策版本号。同一code的参数调整靠它区分。NULL同上',
  `refund_deadline` datetime DEFAULT NULL COMMENT '可退截止点快照(③=票end_time)。「别再算了」的那一列:锚点字段卖票后还能改,现取=追溯改写已售单的退款窗口',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_registration_no` (`registration_no`) USING BTREE,
  UNIQUE KEY `uk_reg_member_request` (`member_id`,`request_id`),
  KEY `idx_payment_status` (`payment_status`) USING BTREE,
  KEY `idx_expire_time` (`pay_expire_time`) USING BTREE,
  KEY `idx_cms_registration_parent_order` (`parent_order_no`),
  KEY `idx_cms_registration_participate` (`owner_id`,`participate_date`),
  KEY `idx_reg_paystatus_ctime` (`payment_status`,`create_time`),
  KEY `idx_reg_phone_hash` (`phone_hash`)
) ENGINE=InnoDB AUTO_INCREMENT=74 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='活动/主题报名表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_registration_merchant`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_registration_merchant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '报名ID',
  `member_id` bigint NOT NULL COMMENT '商家用户ID',
  `topic_id` bigint NOT NULL COMMENT '主题ID',
  `mode` tinyint(1) NOT NULL COMMENT '报名模式1、自由探索模式  2 城市旅团模式',
  `node_id` bigint DEFAULT NULL COMMENT '节点ID',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动地点',
  `address_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '场馆名称',
  `latitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址纬度',
  `longitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址经度',
  `template_id` bigint DEFAULT NULL COMMENT '模版ID',
  `pic_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '商家报名图片(多张)',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱',
  `start_date` datetime DEFAULT NULL COMMENT '参与开始时间',
  `end_date` datetime DEFAULT NULL COMMENT '参与结束时间',
  `cooperate_date` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可配合时间',
  `activity_desc` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动说明',
  `limit_num` int DEFAULT NULL COMMENT '限定人数',
  `rule_instructions` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '规则说明',
  `validation_method` tinyint(1) DEFAULT '0' COMMENT '验证方式:1、文字验证 2、图片验证  3、选项验证',
  `question_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题名称',
  `question_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '问题答案',
  `feedback_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '反馈文字',
  `coupon_id` bigint DEFAULT NULL COMMENT '反馈优惠券ID',
  `question_a` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项A',
  `question_b` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项B',
  `question_c` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项C',
  `question_d` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项D',
  `correct_answer` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '正确答案(A/B/C/D)',
  `sharing_rate` decimal(10,2) DEFAULT '0.00' COMMENT '分润比例',
  `sharing_amount` decimal(10,2) DEFAULT '0.00' COMMENT '分润金额',
  `status` int DEFAULT NULL COMMENT '状态：0->待审核  1已审核 2审核失败',
  `audit_status` tinyint DEFAULT '0' COMMENT '调配状态：0待调配 1中标 2落选',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `check_status` tinyint DEFAULT '0' COMMENT '后台查阅状态：0->待审核；1->审核通过；2->审核失败；',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `win_key` varchar(64) COLLATE utf8mb4_general_ci GENERATED ALWAYS AS ((case when ((`audit_status` = 1) and (`del_flag` = 0) and (`node_id` is not null) and (`node_id` <> 0)) then concat(`topic_id`,_utf8mb4'-',`node_id`) else NULL end)) VIRTUAL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_reg_merchant_win` (`win_key`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='商家主题报名表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_registration_scan`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_registration_scan` (
  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `registration_type` int DEFAULT NULL COMMENT '报名类型0无商家 1有商家',
  `registration_id` int DEFAULT '0' COMMENT '报名ID',
  `member_id` int DEFAULT '0' COMMENT '商家用户ID',
  `validation_method` tinyint(1) DEFAULT '0' COMMENT '验证方式:1、文字验证 2、图片验证  3、选项验证',
  `question_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '问题答案',
  `feedback_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '反馈文字',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '名称',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '地址',
  `latitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址纬度',
  `longitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址经度',
  `template_id` bigint DEFAULT '0' COMMENT '模板ID',
  `status` int DEFAULT '0' COMMENT '状态 0未验证 1已验证',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='商家扫码记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_setting`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_setting` (
  `id` int NOT NULL,
  `activity_platform_commission` decimal(10,2) DEFAULT '0.00' COMMENT '活动平台佣金（百分比）',
  `activity_merchant_commission` decimal(10,2) DEFAULT '0.00' COMMENT '活动商户佣金（百分比）',
  `activity_designer_commission` decimal(5,2) DEFAULT '0.00' COMMENT '活动主题设计者佣金（百分比）',
  `platform_member_id` bigint DEFAULT NULL COMMENT '平台账户会员ID（接收平台分润）',
  `topic_platform_commission` decimal(10,2) DEFAULT '0.00' COMMENT '主题平台佣金（百分比）',
  `topic_merchant_commission` decimal(10,2) DEFAULT '0.00' COMMENT '主题商户佣金（百分比）',
  `topic_publisher_commission` decimal(10,2) DEFAULT '0.00' COMMENT '主题发布者佣金（百分比）',
  `topic_invite_commission` decimal(10,2) DEFAULT NULL COMMENT '主题邀请人佣金（百分比）',
  `user1_topic_num` int DEFAULT '0' COMMENT '普通用户主题发布数',
  `user2_topic_num` int DEFAULT NULL COMMENT '俱乐部主题发布数',
  `user3_topic_num` int DEFAULT NULL COMMENT '畅玩VIP主题发布数',
  `merchant_topic_num` int DEFAULT NULL COMMENT '商户承接主题数',
  `merchant_template_num` int DEFAULT NULL COMMENT '商户模板数',
  `merchant_coupon_num` int DEFAULT NULL COMMENT '商户优惠券数',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='参数设置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_template_library`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_template_library` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '模板ID',
  `member_id` bigint NOT NULL COMMENT '发布用户ID',
  `member_template_id` bigint DEFAULT '0' COMMENT '关联的用户模板ID，如果是0代表平台发布',
  `category_id` bigint DEFAULT '0' COMMENT '模板类别ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '标题',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '介绍',
  `img_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '图片URL',
  `players` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '游戏人数',
  `usage_location` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '适用场景',
  `required_materials` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '所需材料',
  `duration` int DEFAULT NULL COMMENT '时长(分钟)',
  `difficulty` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '难度',
  `is_sync` tinyint(1) DEFAULT '0' COMMENT '是否同步到模板库',
  `rule_instructions` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '规则说明',
  `validation_method` tinyint(1) DEFAULT '0' COMMENT '验证方式:1、文字验证 2、图片验证  3、选项验证',
  `question_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题名称',
  `question_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '问题答案',
  `feedback_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '反馈文字',
  `coupon_id` bigint DEFAULT NULL COMMENT '反馈优惠券ID',
  `story_text` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '剧情文案',
  `story_img` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '剧情配图URL',
  `story_json` text COLLATE utf8mb4_general_ci COMMENT '剧情多节点JSON',
  `hint1` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '一级提示',
  `hint2` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '二级提示',
  `answer_reveal` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '兜底答案/解析',
  `photo_require_desc` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '拍照打卡要求描述',
  `photo_review` tinyint NOT NULL DEFAULT '0' COMMENT '拍照打卡是否需人工审核:0否 1是',
  `audio_url` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '语音讲解音频URL',
  `audio_duration` int DEFAULT NULL COMMENT '语音时长(秒)',
  `question_a` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项A',
  `question_b` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项B',
  `question_c` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项C',
  `question_d` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题选项D',
  `correct_answer` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '正确答案(A/B/C/D)',
  `activity_categoryids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '活动类别(多选)，逗号隔开',
  `status` int DEFAULT '0' COMMENT '后台审核状态：0->待审核；1->审核通过；2->审核失败；',
  `publish_status` int DEFAULT '0' COMMENT '发布状态：0下架中  1已上架',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `rating` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '评分-后台输入',
  `use_num` int DEFAULT '0' COMMENT '使用人数-后台输入',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_member_id` (`member_id`) USING BTREE,
  KEY `idx_category_id` (`category_id`) USING BTREE,
  KEY `idx_is_sync` (`is_sync`) USING BTREE,
  KEY `idx_member_template_id` (`member_template_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='模板库表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID-用户和后台发布（商家不能发布）',
  `topic_type` int DEFAULT NULL COMMENT '主题类型：1平台主题  2自定义主题',
  `product_type` tinyint DEFAULT NULL COMMENT '产品类型 1经典定向ORIENTEERING/2自由定向FREE_EXPLORE。NULL=存量未回填或混票待人工,读侧回落票mode推断',
  `topic_id` bigint DEFAULT NULL COMMENT '选择主题的ID',
  `source_topic_id` bigint DEFAULT NULL COMMENT 'REOPEN_NEXT_EDITION来源cms_topic.id；不得使用历史topic_id',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题名称',
  `subtitle` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '副标题',
  `description` varchar(8000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题描述',
  `address_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '地址',
  `total_time` int DEFAULT '0' COMMENT '总时长，单位秒',
  `total_mileage` decimal(10,2) DEFAULT NULL COMMENT '总里程KM',
  `location_count` int DEFAULT '0' COMMENT '地点数',
  `template_count` int DEFAULT '0' COMMENT '模版数',
  `start_date` datetime DEFAULT NULL COMMENT '主题开始日期',
  `end_date` datetime DEFAULT NULL COMMENT '主题结束日期',
  `img_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题长图（单张）',
  `img_arr` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题轮播图（多张）分号隔开',
  `category_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '主题类别IDS，逗号隔开',
  `status` tinyint(1) DEFAULT '0' COMMENT '后台审核状态：0->待审核；1->审核通过；2->审核失败；',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `user_status` tinyint(1) DEFAULT '0' COMMENT '用户端状态：0未上架  1已上架',
  `merchant_status` tinyint(1) DEFAULT NULL COMMENT '商家端状态：0未上架  1已上架 ',
  `open_club_pool` tinyint(1) NOT NULL DEFAULT '0' COMMENT '开放范围·俱乐部承接(P0-4):0不开放 1开放给俱乐部申请承接',
  `merchant_sign_up_start_date` datetime DEFAULT NULL COMMENT '商家报名开始时间',
  `merchant_sign_up_end_date` datetime DEFAULT NULL COMMENT '商家报名结束时间',
  `recruit_deadline` datetime DEFAULT NULL COMMENT '招募截止时间(到期触发 TopicRecruitDeadlineTask 判定)',
  `sale_end_at` datetime DEFAULT NULL COMMENT '本期所有可售SKU的整期硬售止（排他上界）',
  `selling_started_at` datetime DEFAULT NULL COMMENT 'PRICING→SELLING首次CAS成功的实际开售时刻',
  `recruit_extend_count` tinyint(1) NOT NULL DEFAULT '0' COMMENT '招募期已延长次数(最多 2 次)',
  `lifecycle` tinyint(1) NOT NULL DEFAULT '0' COMMENT '生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `is_sync` tinyint(1) DEFAULT '0' COMMENT '是否同步到创意广场1 同步',
  `is_recommend` tinyint DEFAULT '0' COMMENT '是否推荐 1 是 0 否',
  `check_status` tinyint DEFAULT '0' COMMENT '后台查阅状态：0->待审核；1->审核通过；2->审核失败；',
  `is_settlement` tinyint DEFAULT '0' COMMENT '是否结算 1是 0否',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `publish_mode` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'pro' COMMENT '发布来源 simple建议版 pro专业版',
  `club_id` bigint DEFAULT NULL COMMENT '所属俱乐部id 城市定向团',
  `self_play` tinyint DEFAULT '0' COMMENT 'M2 是否开放自玩票 0否1是(仅城市定向)',
  `self_play_price` decimal(10,2) DEFAULT NULL COMMENT 'M2 自玩票价格(元)',
  `self_play_quota` int DEFAULT NULL COMMENT 'M2 自玩票数/配额(NULL 或 <=0 = 不限)',
  `xp_budget` int DEFAULT '3000' COMMENT 'M7 主题探索值预算',
  `finish_medal_name` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'P0.5 通关勋章名(空=默认 主题名·通关)',
  `finish_medal_img` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'P0.5 通关勋章图',
  `complete_reward_coupon_id` bigint DEFAULT NULL COMMENT '主题通关奖励券 sms_coupon.id',
  `audio_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题音频URL(OSS)',
  `audio_duration` int DEFAULT NULL COMMENT '主题音频时长(秒)',
  `is_draft` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否草稿(存了未提审,1=仅作者可见)',
  `is_template` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否主题模板(1=已另存为模板,可复制即用)',
  `fee_platform_rate` decimal(10,4) DEFAULT NULL COMMENT '锁价时冻结的平台抽成率。NULL=未锁价/存量,读侧回落settlement_rule。单位:小数',
  `fee_guarantee_base_rate` decimal(10,4) DEFAULT NULL COMMENT '锁价时冻结的保底基础率。NULL同上',
  `fee_guarantee_step_rate` decimal(10,4) DEFAULT NULL COMMENT '锁价时冻结的保底追加率。NULL同上',
  `fee_guarantee_max_merchant` tinyint DEFAULT NULL COMMENT '锁价时冻结的保底封顶家数。NULL同上',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_topic_club` (`club_id`),
  KEY `idx_topic_recruit` (`lifecycle`,`recruit_deadline`),
  KEY `idx_cms_topic_is_draft` (`is_draft`),
  KEY `idx_cms_topic_is_template` (`is_template`),
  KEY `idx_cms_topic_lifecycle_sale_end` (`lifecycle`,`sale_end_at`),
  KEY `idx_cms_topic_source_topic_id` (`source_topic_id`)
) ENGINE=InnoDB AUTO_INCREMENT=990013 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='主题表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic_bak_20260811_w`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic_bak_20260811_w` (
  `id` bigint unsigned NOT NULL DEFAULT '0',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID-用户和后台发布（商家不能发布）',
  `topic_type` int DEFAULT NULL COMMENT '主题类型：1平台主题  2自定义主题',
  `product_type` tinyint DEFAULT NULL COMMENT '产品类型 1经典定向ORIENTEERING/2自由定向FREE_EXPLORE。NULL=存量未回填或混票待人工,读侧回落票mode推断',
  `topic_id` bigint DEFAULT NULL COMMENT '选择主题的ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题名称',
  `subtitle` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '副标题',
  `description` varchar(8000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题描述',
  `address_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '地址',
  `total_time` int DEFAULT '0' COMMENT '总时长，单位秒',
  `total_mileage` decimal(10,2) DEFAULT NULL COMMENT '总里程KM',
  `location_count` int DEFAULT '0' COMMENT '地点数',
  `template_count` int DEFAULT '0' COMMENT '模版数',
  `start_date` datetime DEFAULT NULL COMMENT '主题开始日期',
  `end_date` datetime DEFAULT NULL COMMENT '主题结束日期',
  `img_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题长图（单张）',
  `img_arr` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题轮播图（多张）分号隔开',
  `category_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '主题类别IDS，逗号隔开',
  `status` tinyint(1) DEFAULT '0' COMMENT '后台审核状态：0->待审核；1->审核通过；2->审核失败；',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `user_status` tinyint(1) DEFAULT '0' COMMENT '用户端状态：0未上架  1已上架',
  `merchant_status` tinyint(1) DEFAULT NULL COMMENT '商家端状态：0未上架  1已上架 ',
  `open_club_pool` tinyint(1) NOT NULL DEFAULT '0' COMMENT '开放范围·俱乐部承接(P0-4):0不开放 1开放给俱乐部申请承接',
  `merchant_sign_up_start_date` datetime DEFAULT NULL COMMENT '商家报名开始时间',
  `merchant_sign_up_end_date` datetime DEFAULT NULL COMMENT '商家报名结束时间',
  `recruit_deadline` datetime DEFAULT NULL COMMENT '招募截止时间(到期触发 TopicRecruitDeadlineTask 判定)',
  `recruit_extend_count` tinyint(1) NOT NULL DEFAULT '0' COMMENT '招募期已延长次数(最多 2 次)',
  `lifecycle` tinyint(1) NOT NULL DEFAULT '0' COMMENT '生命周期 0草稿 1招募 2定价 3售票 4进行 5结算 6关闭',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `is_sync` tinyint(1) DEFAULT '0' COMMENT '是否同步到创意广场1 同步',
  `is_recommend` tinyint DEFAULT '0' COMMENT '是否推荐 1 是 0 否',
  `check_status` tinyint DEFAULT '0' COMMENT '后台查阅状态：0->待审核；1->审核通过；2->审核失败；',
  `is_settlement` tinyint DEFAULT '0' COMMENT '是否结算 1是 0否',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `publish_mode` varchar(16) COLLATE utf8mb4_general_ci DEFAULT 'pro' COMMENT '发布来源 simple建议版 pro专业版',
  `club_id` bigint DEFAULT NULL COMMENT '所属俱乐部id 城市定向团',
  `self_play` tinyint DEFAULT '0' COMMENT 'M2 是否开放自玩票 0否1是(仅城市定向)',
  `self_play_price` decimal(10,2) DEFAULT NULL COMMENT 'M2 自玩票价格(元)',
  `self_play_quota` int DEFAULT NULL COMMENT 'M2 自玩票数/配额(NULL 或 <=0 = 不限)',
  `xp_budget` int DEFAULT '3000' COMMENT 'M7 主题探索值预算',
  `finish_medal_name` varchar(40) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'P0.5 通关勋章名(空=默认 主题名·通关)',
  `finish_medal_img` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'P0.5 通关勋章图',
  `complete_reward_coupon_id` bigint DEFAULT NULL COMMENT '主题通关奖励券 sms_coupon.id',
  `audio_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主题音频URL(OSS)',
  `audio_duration` int DEFAULT NULL COMMENT '主题音频时长(秒)',
  `is_draft` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否草稿(存了未提审,1=仅作者可见)',
  `is_template` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否主题模板(1=已另存为模板,可复制即用)',
  `fee_platform_rate` decimal(10,4) DEFAULT NULL COMMENT '锁价时冻结的平台抽成率。NULL=未锁价/存量,读侧回落settlement_rule。单位:小数',
  `fee_guarantee_base_rate` decimal(10,4) DEFAULT NULL COMMENT '锁价时冻结的保底基础率。NULL同上',
  `fee_guarantee_step_rate` decimal(10,4) DEFAULT NULL COMMENT '锁价时冻结的保底追加率。NULL同上',
  `fee_guarantee_max_merchant` tinyint DEFAULT NULL COMMENT '锁价时冻结的保底封顶家数。NULL同上'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic_chapter`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic_chapter` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `topic_id` bigint DEFAULT NULL COMMENT '主题的ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '章节名称',
  `description` varchar(8000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '描述',
  `img_arr` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '图片（多张）分号隔开',
  `total_time` int DEFAULT '0' COMMENT '总时长，单位秒',
  `total_mileage` decimal(10,2) DEFAULT NULL COMMENT '总里程KM',
  `location_count` int DEFAULT '0' COMMENT '地点数',
  `template_count` int DEFAULT '0' COMMENT '模版数',
  `sort_id` int DEFAULT '0' COMMENT '排序ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `category` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '章节品类(如 咖啡/书店/酒吧),用于商家匹配',
  `category_id` int DEFAULT NULL COMMENT '适合商家品类ID，匹配mms_merchant.category_id',
  `recruit_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '是否开放商家承接：0否 1是',
  `perk_min_value` decimal(10,2) DEFAULT NULL COMMENT '入驻门槛:商家权益零售标价须≥此值。⚠️比的是retail_value零售价,不是unit_cost成本价',
  `max_merchant` tinyint NOT NULL DEFAULT '4' COMMENT '本章节最多可承接商家数(默认4)',
  `required` tinyint NOT NULL DEFAULT '1' COMMENT '1必选章(计入通关判定与容量) 0可选章。默认1=保持既有「每章都算」语义',
  `terms_mode` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PERK' COMMENT 'D21供给条款档:PERK权益档(承诺可兑换权益,走quota容量链)/REVSHARE计酬档(按核销付人头费,不设容量)/TRAFFIC引流档(零结算,不设容量)。默认PERK=从严:它是唯一有容量约束的档',
  `max_per_head_fee` decimal(10,2) DEFAULT NULL COMMENT '计酬档人头费上限(元/人次);NULL=未配置,fail-closed 拒绝计酬档入驻',
  `allowed_validation_methods` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '本章节允许商家使用的验证方式,逗号分隔如"1,3,4";NULL=不限(存量语义)',
  `max_node_xp` int DEFAULT NULL COMMENT '单节点探索值上限,商家节点不得超过;NULL=不限',
  `blocks` longtext COLLATE utf8mb4_general_ci COMMENT '章节内容块(JSON 数组,应用层解析)',
  PRIMARY KEY (`id`) USING BTREE,
  CONSTRAINT `ck_chapter_terms` CHECK ((`terms_mode` in (_utf8mb4'TRAFFIC',_utf8mb4'REVSHARE',_utf8mb4'PERK')))
) ENGINE=InnoDB AUTO_INCREMENT=42 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='主题章节表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic_chapter_template_meta`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic_chapter_template_meta` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `chapter_id` bigint NOT NULL COMMENT '章节id(cms_topic_chapter.id)',
  `topic_id` bigint NOT NULL COMMENT '主题id,冗余一列供按主题批量拉取,避免 N+1',
  `role_need` text COMMENT '本章节需要的商家角色与职责描述',
  `exclusivity_mode` varchar(8) DEFAULT NULL COMMENT '排他:HARD硬排他/SOFT软排他/NONE不限',
  `exclusivity_scope` varchar(255) DEFAULT NULL COMMENT '排他区域',
  `exclusivity_period` varchar(255) DEFAULT NULL COMMENT '排他期限',
  `exclusivity_reason` text COMMENT '排他原因',
  `effort_prep_hours` decimal(6,2) DEFAULT NULL COMMENT '准备工时(小时)',
  `effort_staff_minutes` int DEFAULT NULL COMMENT '每场店员投入(分钟)',
  `effort_venue` varchar(255) DEFAULT NULL COMMENT '场地要求',
  `effort_materials` text COMMENT '物料要求',
  `effort_max_concurrent` int DEFAULT NULL COMMENT '最大同时接待人数',
  `effort_available_slots` varchar(255) DEFAULT NULL COMMENT '可用时段',
  `effort_needs_booking` tinyint DEFAULT NULL COMMENT '是否需预约:1是 0否',
  `effort_cancel_cost` text COMMENT '取消代价',
  `effort_qualifications` text COMMENT '资质要求',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '0正常 2删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chapter_meta_chapter` (`chapter_id`),
  KEY `idx_chapter_meta_topic` (`topic_id`),
  CONSTRAINT `ck_chapter_meta_exclusivity` CHECK (((`exclusivity_mode` is null) or (`exclusivity_mode` in (_utf8mb4'HARD',_utf8mb4'SOFT',_utf8mb4'NONE'))))
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主题模板详情页元数据(07-23 P0 字段字典·章节级)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic_completion_coupon_reward_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic_completion_coupon_reward_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_member` (`topic_id`,`member_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主题通关奖励券发放幂等日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic_like`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `data_id` bigint DEFAULT NULL COMMENT '主题ID',
  `like_type` bigint DEFAULT '1' COMMENT '类型：1已点赞',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=114 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='主题点赞表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic_node`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic_node` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `topic_id` bigint DEFAULT NULL COMMENT '主题的ID',
  `chapter_id` bigint DEFAULT '0' COMMENT '章节ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '节点名称',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '节点描述',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '节点地址',
  `longitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址经度',
  `latitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址纬度',
  `img_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地点图片(多张)',
  `node_time` int DEFAULT NULL COMMENT '节点时间(分钟）',
  `template_id` bigint DEFAULT '0' COMMENT '我的模版ID',
  `merchant_member_id` bigint DEFAULT NULL COMMENT '该节点归属的承接商家 member_id;NULL=主办方自建节点',
  `node_audit_status` tinyint DEFAULT NULL COMMENT '商家节点审核态 0待审 1通过 2驳回;NULL=主办方节点不走审核',
  `node_audit_reason` varchar(200) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因(审核态=2 时给商家看)',
  `node_audit_member_id` bigint DEFAULT NULL COMMENT '放行/驳回该节点的主办方 member_id。审核是准入不是背书,责任仍在内容作者',
  `node_audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `checkin_code` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '节点打卡码(扫码内容)，唯一随机串',
  `business_time` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '该地点营业时间',
  `status` int DEFAULT '0' COMMENT '状态0不显示 1不显示',
  `sort_id` int DEFAULT '0' COMMENT '排序ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `hook_text` varchar(120) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自由探索:卡堆行一句钩子',
  `card_hook_long` varchar(300) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自由探索:地点大卡NPC 2-3句钩子',
  `fragment_text` varchar(600) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '自由探索:故事碎片正文(完成入手记)',
  `xp_value` int DEFAULT NULL COMMENT 'M7 纯停靠节点探索值',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_topic_node_chapter_merchant` (`chapter_id`,`merchant_member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=71 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='主题节点表（路线）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cms_topic_template_meta`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_topic_template_meta` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '主题id(cms_topic.id);仅对 is_template=1 的主题有意义',
  `template_status` varchar(16) NOT NULL DEFAULT 'EXPERIMENTAL' COMMENT '模板状态:EXPERIMENTAL实验/PILOT试点/VERIFIED已验证/RETIRED停用',
  `version` varchar(32) DEFAULT NULL COMMENT '模板版本号,如 v1.0。实例默认锁定采用时的版本',
  `applicable_roles` varchar(64) DEFAULT NULL COMMENT '适用身份,逗号分隔:MERCHANT商家/CLUB俱乐部/AGENT代理人。必填口径=至少一类',
  `audience_who` varchar(255) DEFAULT NULL COMMENT '核心人群:是谁',
  `audience_moment` varchar(255) DEFAULT NULL COMMENT '核心触发:什么时刻',
  `audience_need` varchar(255) DEFAULT NULL COMMENT '核心需求:要解决什么',
  `player_promise` varchar(255) DEFAULT NULL COMMENT '玩家承诺,40-80字。必须说明过程和结果,不能只写口号',
  `core_loop` text COMMENT '核心玩家循环六段,换行分隔,顺序即阶段顺序',
  `main_skeleton_template_id` bigint DEFAULT NULL COMMENT '主玩法骨架(cms_member_template.id)。⚠️只存id,详情走既有白名单玩法VO,不得由此另开出口',
  `evidence_level` varchar(16) DEFAULT NULL COMMENT '证据等级四级:NONE无实测/ESTIMATED估算/PILOTED已试点/VERIFIED已验证。无实测时必须显式标记',
  `risk_tags` varchar(255) DEFAULT NULL COMMENT '风险标签,逗号分隔',
  `risk_qualifications` text COMMENT '所需资质材料说明',
  `risk_cancellation` text COMMENT '取消规则',
  `risk_liability` text COMMENT '责任划分',
  `risk_safety_notice` text COMMENT '安全提示(对玩家公开)',
  `preview_before` text COMMENT '玩家预览:开始前',
  `preview_during` text COMMENT '玩家预览:进行中',
  `preview_after` text COMMENT '玩家预览:完成后',
  `author` varchar(64) DEFAULT NULL COMMENT '模板作者主体(可追溯)',
  `is_official` tinyint NOT NULL DEFAULT '0' COMMENT '1=官方范本(平台官方账号出品,运营用SQL标记)',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '0正常 2删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间;「模板作者与更新」的时间轴读这一列',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_meta_topic` (`topic_id`),
  CONSTRAINT `ck_meta_evidence_level` CHECK (((`evidence_level` is null) or (`evidence_level` in (_utf8mb4'NONE',_utf8mb4'ESTIMATED',_utf8mb4'PILOTED',_utf8mb4'VERIFIED')))),
  CONSTRAINT `ck_meta_template_status` CHECK ((`template_status` in (_utf8mb4'EXPERIMENTAL',_utf8mb4'PILOT',_utf8mb4'VERIFIED',_utf8mb4'RETIRED')))
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主题模板详情页元数据(07-23 P0 字段字典·主题级)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `consent_record`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `consent_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `doc_type` varchar(32) NOT NULL,
  `doc_version` varchar(32) NOT NULL,
  `scene` varchar(32) NOT NULL,
  `event_type` varchar(16) NOT NULL COMMENT 'AGREE/REVOKE',
  `occurred_at` datetime NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `ip_address` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consent_request` (`request_id`),
  KEY `idx_consent_member_doc_scene` (`member_id`,`doc_type`,`scene`,`occurred_at`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同意与撤回留痕';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_club_apply`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_club_apply` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '主题ID',
  `club_id` bigint NOT NULL COMMENT '申请俱乐部ID',
  `member_id` bigint NOT NULL COMMENT '申请时的俱乐部主理人memberId',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '状态 0待处理 1已婉拒 2已撤回;已邀约/已合作按 coop_invite 派生不落本表',
  `message` varchar(500) DEFAULT NULL COMMENT '申请留言',
  `handle_time` datetime DEFAULT NULL COMMENT '处理时间(婉拒/撤回)',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_club_apply_topic_club` (`topic_id`,`club_id`),
  KEY `idx_club_apply_topic_status` (`topic_id`,`status`),
  KEY `idx_club_apply_club` (`club_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='俱乐部承接申请(P0-4 开放范围·意向记录,不生成合作单)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_complaint`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_complaint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint NOT NULL COMMENT '关联主题',
  `registration_id` bigint DEFAULT NULL COMMENT '关联报名',
  `reporter_member_id` bigint NOT NULL COMMENT '投诉玩家',
  `respondent_type` varchar(16) DEFAULT NULL COMMENT '过错方类型',
  `respondent_member_id` bigint DEFAULT NULL COMMENT '过错方 member_id',
  `status` tinyint DEFAULT '0' COMMENT '0受理 1已垫付 2追偿中 3关闭',
  `advance_amount` decimal(12,2) DEFAULT NULL COMMENT '先行垫付额',
  `fault_ratio` int DEFAULT NULL COMMENT '过错比例',
  `deduct_amount` decimal(12,2) DEFAULT NULL COMMENT '扣划额',
  `reason` varchar(500) DEFAULT NULL COMMENT '投诉事由',
  `handle_note` varchar(500) DEFAULT NULL COMMENT '处理说明',
  `handler_id` bigint DEFAULT NULL COMMENT '处理人',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_complaint_topic` (`topic_id`,`status`),
  KEY `idx_complaint_respondent` (`respondent_member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='coop 客诉工单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_credit_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_credit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '被记账方memberId',
  `event_type` tinyint(1) NOT NULL COMMENT '0履约 1超时未配置 2保护期外取消 3保护期内违约',
  `invite_id` bigint DEFAULT NULL COMMENT '关联邀约',
  `reason` varchar(255) DEFAULT NULL COMMENT '理由/备注',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_credit_member` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协作信用记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_group_member_verify`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_group_member_verify` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '主题(cms_topic.id)',
  `activity_id` bigint NOT NULL COMMENT '带队场次(cms_activity.id)',
  `registration_id` bigint NOT NULL COMMENT '已核销主题报名单(cms_registration.id)',
  `verifier_member_id` bigint NOT NULL COMMENT '执行玩家核销的主理人或管理员 memberId',
  `verify_time` datetime NOT NULL COMMENT '核销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_member_registration` (`registration_id`),
  KEY `idx_group_member_activity` (`activity_id`),
  KEY `idx_group_member_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='带队场次玩家核销事实：报名单属于哪个独立团';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_group_verify`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_group_verify` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '主题(cms_topic.id)',
  `activity_id` bigint DEFAULT NULL COMMENT '签发团码时绑定的带队场次(cms_activity.id)',
  `coop_order_id` bigint NOT NULL COMMENT '该商家的生效合作单 —— 结算按它取分账条款(定稿§6.5-C 结算只认合作单)',
  `merchant_member_id` bigint NOT NULL COMMENT '接待该团的商家 memberId(= coop_order.to_id,to_type=merchant)',
  `leader_member_id` bigint NOT NULL COMMENT '出示团码的主理人/管理员 memberId —— 结算按它反查该团已核销的玩家',
  `verify_time` datetime NOT NULL COMMENT '商家扫团码的时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_verify_activity_merchant` (`activity_id`,`merchant_member_id`),
  KEY `idx_group_verify_topic` (`topic_id`),
  KEY `idx_group_verify_activity` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='D22 团核销台账:哪个商家接待了哪个领队带的团';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_invite`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_invite` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `invite_type` tinyint(1) NOT NULL COMMENT '0达人↔商家 1商家→俱乐部 2商家→商家做节点',
  `from_type` varchar(16) NOT NULL COMMENT 'talent/merchant',
  `from_id` bigint NOT NULL COMMENT '发起方memberId',
  `to_type` varchar(16) NOT NULL COMMENT 'merchant/club',
  `to_id` bigint NOT NULL COMMENT '受邀方memberId或clubId',
  `topic_id` bigint DEFAULT NULL COMMENT '关联主题',
  `origin_apply_id` bigint DEFAULT NULL COMMENT '开放报名溯源 coop_club_apply.id(type1 有值则发起方缴押金)',
  `game_id` bigint DEFAULT NULL COMMENT '城市定向:目标游戏(游戏级邀约)',
  `target_level` tinyint(1) DEFAULT '0' COMMENT '槽位粒度 0主题级/1游戏级',
  `node_id` bigint DEFAULT NULL COMMENT '节点',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '状态 0待确认 1已接受 2已拒绝 3已取消 4已顶替 5已过期',
  `message` varchar(500) DEFAULT NULL,
  `handle_time` datetime DEFAULT NULL,
  `handle_reason` varchar(255) DEFAULT NULL COMMENT '受邀方拒绝原因',
  `expire_time` datetime DEFAULT NULL COMMENT '邀约自身过期时间(=主题招募截止,挂起超时判过期)',
  `config_deadline` datetime DEFAULT NULL COMMENT '自由探索:同意后配置游戏死线(超时释放槽位)',
  `share_mode` tinyint(1) DEFAULT NULL COMMENT '报酬模式 0引流/1分成/2固定',
  `share_rate` decimal(5,2) DEFAULT NULL COMMENT '分成型比例(share_mode=1)',
  `fixed_fee` decimal(10,2) DEFAULT NULL COMMENT '固定型每核销人头费(share_mode=2)',
  `deposit_amount` decimal(10,2) DEFAULT NULL COMMENT '保证金额(仅type1俱乐部,固定¥50)',
  `deposit_status` tinyint(1) DEFAULT '0' COMMENT '保证金状态 0无需/1支付中/2已缴冻结/3已扣/4已退/5应缴未缴',
  `deposit_out_trade_no` varchar(64) DEFAULT NULL COMMENT '保证金微信支付商户订单号(前缀DEP)',
  `deposit_refund_no` varchar(64) DEFAULT NULL COMMENT '保证金退款单号(完成原路退)',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `del_flag` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_coop_from` (`from_type`,`from_id`,`status`),
  KEY `idx_coop_to` (`to_type`,`to_id`,`status`),
  KEY `idx_coop_topic` (`topic_id`),
  KEY `idx_coop_expire` (`status`,`expire_time`),
  KEY `idx_coop_config` (`status`,`config_deadline`),
  KEY `idx_coop_origin_apply` (`origin_apply_id`)
) ENGINE=InnoDB AUTO_INCREMENT=990024 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协作邀请';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_order`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `invite_id` bigint DEFAULT NULL COMMENT '来源邀约(邀约侧候选;报名侧为空)',
  `reg_id` bigint DEFAULT NULL COMMENT '来源报名(候选池报名侧;邀约侧为空)',
  `topic_id` bigint DEFAULT NULL COMMENT '主题',
  `game_id` bigint DEFAULT NULL COMMENT '城市定向:游戏',
  `from_id` bigint DEFAULT NULL COMMENT '发起方memberId',
  `to_id` bigint DEFAULT NULL COMMENT '受邀方(merchant=memberId/club=clubId)',
  `to_type` varchar(16) DEFAULT NULL COMMENT '受邀方类型 merchant/club',
  `share_mode` tinyint(1) DEFAULT NULL COMMENT '0引流 1分成 2固定',
  `share_rate` decimal(5,2) DEFAULT NULL COMMENT '分成比例',
  `fixed_fee` decimal(10,2) DEFAULT NULL COMMENT '固定型每核销人头费',
  `perks_snapshot` json DEFAULT NULL COMMENT '同意瞬间的权益快照',
  `terms_version` varchar(16) DEFAULT NULL COMMENT '平台规则版本号',
  `role_snapshot` varchar(32) DEFAULT NULL COMMENT '接受时服务端冻结的合同角色 SUPPLY/ORGANIZER',
  `scope_type_snapshot` varchar(32) DEFAULT NULL COMMENT '接受时冻结的履约范围类型 TOPIC/NODE',
  `scope_id_snapshot` bigint DEFAULT NULL COMMENT '接受时冻结的履约范围 ID',
  `eligible_purchase_kinds_snapshot` varchar(128) DEFAULT NULL COMMENT '接受时服务端冻结的允许 purchase_kind 集合',
  `fulfillment_evidence_rule_snapshot` varchar(64) DEFAULT NULL COMMENT '接受时服务端冻结的履约证据规则',
  `terms_snapshot` text COMMENT '接受时服务端冻结的两轴/金额/权益条款 JSON',
  `deposit_amount_snapshot` decimal(10,2) DEFAULT NULL COMMENT '独立于两轴的接受时保证金快照',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0生效 1作废 2已结算',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `reg_key` bigint GENERATED ALWAYS AS ((case when (`del_flag` = 0) then `reg_id` else NULL end)) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coop_order_reg` (`reg_key`),
  KEY `idx_order_invite` (`invite_id`),
  KEY `idx_order_reg` (`reg_id`),
  KEY `idx_order_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合作单(条款快照)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_order_bak_20260811_w`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_order_bak_20260811_w` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `invite_id` bigint DEFAULT NULL COMMENT '来源邀约(邀约侧候选;报名侧为空)',
  `reg_id` bigint DEFAULT NULL COMMENT '来源报名(候选池报名侧;邀约侧为空)',
  `topic_id` bigint DEFAULT NULL COMMENT '主题',
  `game_id` bigint DEFAULT NULL COMMENT '城市定向:游戏',
  `from_id` bigint DEFAULT NULL COMMENT '发起方memberId',
  `to_id` bigint DEFAULT NULL COMMENT '受邀方(merchant=memberId/club=clubId)',
  `to_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '受邀方类型 merchant/club',
  `share_mode` tinyint(1) DEFAULT NULL COMMENT '0引流 1分成 2固定',
  `share_rate` decimal(5,2) DEFAULT NULL COMMENT '分成比例',
  `fixed_fee` decimal(10,2) DEFAULT NULL COMMENT '固定型每核销人头费',
  `perks_snapshot` json DEFAULT NULL COMMENT '同意瞬间的权益快照',
  `terms_version` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '平台规则版本号',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0生效 1作废 2已结算',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `reg_key` bigint DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_perk`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_perk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `invite_id` bigint DEFAULT NULL COMMENT '所属邀约；开放招商独立供给为空',
  `merchant_id` bigint DEFAULT NULL COMMENT '开放招商独立供给的商家memberId',
  `perk_type` tinyint(1) NOT NULL COMMENT '0礼品 1优惠券 2折扣体验',
  `name` varchar(64) DEFAULT NULL COMMENT '名称',
  `unit_cost` decimal(10,2) DEFAULT NULL COMMENT '单人成本价(商家自报)',
  `quota` int DEFAULT NULL COMMENT '总配额',
  `redeem_rule` varchar(255) DEFAULT NULL COMMENT '核销规则',
  `valid_end` datetime DEFAULT NULL COMMENT '有效期截止',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `retail_value` decimal(10,2) DEFAULT NULL COMMENT '权益零售标价(玩家视角价值);入驻门槛比它,不比unit_cost',
  PRIMARY KEY (`id`),
  KEY `idx_perk_invite` (`invite_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协作权益申报(礼品/券/折扣)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_perk_template`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_perk_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL COMMENT '商家memberId(模板归属)',
  `perk_type` tinyint(1) NOT NULL COMMENT '0礼品 1优惠券 2折扣体验',
  `name` varchar(64) NOT NULL COMMENT '名称',
  `unit_cost` decimal(10,2) DEFAULT NULL COMMENT '单人成本价(商家自报)',
  `quota` int DEFAULT NULL COMMENT '总配额',
  `redeem_rule` varchar(255) DEFAULT NULL COMMENT '核销/使用规则',
  `valid_end` datetime DEFAULT NULL COMMENT '有效期截止',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `retail_value` decimal(10,2) DEFAULT NULL COMMENT '权益零售标价(玩家视角价值);入驻门槛比它,不比unit_cost',
  PRIMARY KEY (`id`),
  KEY `idx_perktpl_merchant` (`merchant_id`,`del_flag`)
) ENGINE=InnoDB AUTO_INCREMENT=990031 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家常备权益模板(礼品/券/折扣)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_review`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_review` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '主题',
  `from_id` bigint NOT NULL COMMENT '评价人memberId',
  `to_id` bigint NOT NULL COMMENT '被评人memberId',
  `rating` tinyint(1) NOT NULL COMMENT '评分 1~5',
  `comment` varchar(255) DEFAULT NULL COMMENT '评语',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_review` (`topic_id`,`from_id`,`to_id`),
  KEY `idx_review_to` (`to_id`,`del_flag`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合作双向互评';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_settlement`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_settlement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `settlement_route` varchar(32) DEFAULT NULL,
  `source_contract_type` varchar(48) DEFAULT NULL,
  `source_contract_id` bigint DEFAULT NULL,
  `commercial_context_type` varchar(32) DEFAULT NULL,
  `commercial_context_id` bigint DEFAULT NULL,
  `beneficiary_role` varchar(32) DEFAULT NULL,
  `beneficiary_id` bigint DEFAULT NULL,
  `service_unit` varchar(48) DEFAULT NULL,
  `topic_id` bigint NOT NULL COMMENT '主题',
  `coop_order_id` bigint DEFAULT NULL COMMENT '合作单id(平台/发起人行为空)',
  `line_type` varchar(24) DEFAULT 'MERCHANT_VERIFY' COMMENT '分项类型',
  `payee_type` varchar(16) DEFAULT 'merchant' COMMENT '收款方类型',
  `merchant_id` bigint NOT NULL COMMENT '收款商家memberId(=coop_order.to_id)',
  `share_mode` tinyint(1) DEFAULT NULL COMMENT '0引流 1分成 2固定(条款快照)',
  `share_rate` decimal(5,2) DEFAULT NULL COMMENT '分成比例',
  `fixed_fee` decimal(10,2) DEFAULT NULL COMMENT '固定型每核销人头费',
  `verified_heads` int NOT NULL DEFAULT '0' COMMENT '核销人头数',
  `verified_sales` decimal(15,2) NOT NULL DEFAULT '0.00' COMMENT '核销销售额(分成型用)',
  `amount` decimal(15,2) NOT NULL DEFAULT '0.00' COMMENT '商家应收',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0待打款 1已打款 2作废',
  `payable_time` datetime DEFAULT NULL COMMENT '可打款时间(=活动结束+7天)',
  `settle_time` datetime DEFAULT NULL COMMENT '结算(挂单)时间',
  `payout_time` datetime DEFAULT NULL COMMENT '实际打款时间',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `verified_reg_ids` text COMMENT 'T9结算审计快照:被本次结算计入的已核销报名id(CSV)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle_order` (`coop_order_id`),
  KEY `idx_settle_topic` (`topic_id`),
  KEY `idx_settle_merchant` (`merchant_id`),
  KEY `idx_settle_payout` (`status`,`payable_time`),
  KEY `idx_coop_settlement_claim_header` (`settlement_route`,`source_contract_type`,`source_contract_id`,`beneficiary_role`,`beneficiary_id`,`service_unit`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家结算单(核销分润·T+7打款)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_settlement_adjustment`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_settlement_adjustment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `settlement_id` bigint NOT NULL,
  `claim_id` bigint NOT NULL,
  `entry_kind` varchar(24) NOT NULL COMMENT 'ADJUSTMENT',
  `event_key` varchar(128) NOT NULL COMMENT '不可变业务事件 id',
  `amount` decimal(15,2) NOT NULL COMMENT '有符号调整额',
  `status` varchar(16) NOT NULL COMMENT 'EXECUTED',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coop_adjustment_event` (`claim_id`,`entry_kind`,`event_key`),
  KEY `idx_coop_adjustment_head` (`settlement_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='COOP_ORDER append-only 调整事件';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_settlement_bak_20260811_w`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_settlement_bak_20260811_w` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '主题',
  `coop_order_id` bigint DEFAULT NULL COMMENT '合作单id(平台/发起人行为空)',
  `line_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'MERCHANT_VERIFY' COMMENT '分项类型',
  `payee_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'merchant' COMMENT '收款方类型',
  `merchant_id` bigint NOT NULL COMMENT '收款商家memberId(=coop_order.to_id)',
  `share_mode` tinyint(1) DEFAULT NULL COMMENT '0引流 1分成 2固定(条款快照)',
  `share_rate` decimal(5,2) DEFAULT NULL COMMENT '分成比例',
  `fixed_fee` decimal(10,2) DEFAULT NULL COMMENT '固定型每核销人头费',
  `verified_heads` int NOT NULL DEFAULT '0' COMMENT '核销人头数',
  `verified_sales` decimal(15,2) NOT NULL DEFAULT '0.00' COMMENT '核销销售额(分成型用)',
  `amount` decimal(15,2) NOT NULL DEFAULT '0.00' COMMENT '商家应收',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0待打款 1已打款 2作废',
  `payable_time` datetime DEFAULT NULL COMMENT '可打款时间(=活动结束+7天)',
  `settle_time` datetime DEFAULT NULL COMMENT '结算(挂单)时间',
  `payout_time` datetime DEFAULT NULL COMMENT '实际打款时间',
  `create_time` datetime DEFAULT NULL,
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `verified_reg_ids` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT 'T9结算审计快照:被本次结算计入的已核销报名id(CSV)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coop_settlement_detail`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coop_settlement_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `settlement_id` bigint NOT NULL COMMENT 'coop_settlement 聚合头',
  `claim_id` bigint NOT NULL COMMENT 'REGISTRATION 原子 claim',
  `registration_id` bigint NOT NULL,
  `origin_amount` decimal(15,2) NOT NULL COMMENT '原始毛额,调整不得回写',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coop_settlement_detail_claim` (`claim_id`),
  KEY `idx_coop_settlement_detail_head` (`settlement_id`,`registration_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='COOP_ORDER 原子归属明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `creator_income_detail`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `creator_income_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `creator_id` bigint NOT NULL COMMENT '创作者ID',
  `source_type` varchar(40) NOT NULL COMMENT '来源类型',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '金额',
  `income_status` tinyint NOT NULL DEFAULT '0' COMMENT '状态:0待结算 1已计入 2已取消',
  `occur_time` datetime NOT NULL COMMENT '发生时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_creator_income_creator` (`creator_id`,`occur_time`),
  KEY `idx_creator_income_source` (`source_type`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3创作者收益明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `creator_metric_daily`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `creator_metric_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `creator_id` bigint NOT NULL COMMENT '创作者ID',
  `content_count` int NOT NULL DEFAULT '0' COMMENT '内容数',
  `view_count` bigint NOT NULL DEFAULT '0' COMMENT '浏览数',
  `like_count` bigint NOT NULL DEFAULT '0' COMMENT '点赞数',
  `favorite_count` bigint NOT NULL DEFAULT '0' COMMENT '收藏数',
  `signup_count` bigint NOT NULL DEFAULT '0' COMMENT '报名数',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_creator_metric_daily` (`stat_date`,`creator_id`),
  KEY `idx_creator_metric_creator` (`creator_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3创作者每日指标';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `creator_profile`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `creator_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `creator_name` varchar(120) NOT NULL COMMENT '创作者名称',
  `bio` varchar(500) DEFAULT NULL COMMENT '简介',
  `avatar_url` varchar(255) DEFAULT NULL COMMENT '头像',
  `apply_status` tinyint NOT NULL DEFAULT '0' COMMENT '申请状态:0待审 1通过 2驳回',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人',
  `review_reason` varchar(500) DEFAULT NULL COMMENT '审核原因',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_creator_member` (`member_id`),
  KEY `idx_creator_status` (`apply_status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3创作者档案';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `cy_migration_history`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cy_migration_history` (
  `migration_id` varchar(191) NOT NULL COMMENT 'migration_ filename stem without prefix',
  `migration_path` varchar(512) NOT NULL COMMENT 'repository-relative immutable path',
  `checksum_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `target_commit` char(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `applied_at` datetime(6) DEFAULT NULL COMMENT 'set only after observed APPLIED or RECONCILED state',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`migration_id`),
  UNIQUE KEY `uk_cy_migration_history_path` (`migration_path`),
  CONSTRAINT `chk_cy_migration_history_status` CHECK ((`status` in (_utf8mb4'APPLYING',_utf8mb4'APPLIED',_utf8mb4'UNKNOWN',_utf8mb4'RECONCILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Manual migration release ledger; no SQL body or credentials';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `event_arrival_verification`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `event_arrival_verification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `mission_id` bigint NOT NULL,
  `binding_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `session_id` bigint DEFAULT NULL,
  `verification_type` varchar(32) NOT NULL,
  `request_id` varchar(80) NOT NULL,
  `distance_m` int DEFAULT NULL,
  `accuracy_bucket` varchar(32) DEFAULT NULL,
  `rule_version` varchar(32) NOT NULL,
  `result` varchar(16) NOT NULL COMMENT 'ACCEPTED/REJECTED',
  `risk_code` varchar(64) DEFAULT NULL,
  `occurred_at` datetime NOT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_mission_member_request` (`event_id`,`mission_id`,`member_id`,`request_id`),
  KEY `idx_member_time` (`member_id`,`occurred_at`),
  KEY `idx_event_result` (`event_id`,`result`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动到达验证事实';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `event_signup`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `event_signup` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1已报名 2进行中 3已完成 4已领奖',
  `progress` int DEFAULT '0' COMMENT '个人完成任务数',
  `reward_claimed` tinyint DEFAULT '0' COMMENT '结算奖是否已发',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_member` (`event_id`,`member_id`),
  KEY `idx_event` (`event_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动报名';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `gen_table`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gen_table` (
  `table_id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `table_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '表名称',
  `table_comment` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '表描述',
  `sub_table_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '关联子表的表名',
  `sub_table_fk_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '子表关联的外键名',
  `class_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '实体类名称',
  `tpl_category` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT 'crud' COMMENT '使用的模板（crud单表操作 tree树表操作）',
  `tpl_web_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '前端模板类型（element-ui模版 element-plus模版）',
  `package_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成包路径',
  `module_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成模块名',
  `business_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成业务名',
  `function_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成功能名',
  `function_author` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '生成功能作者',
  `gen_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '生成代码方式（0zip压缩包 1自定义路径）',
  `gen_path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '/' COMMENT '生成路径（不填默认项目路径）',
  `options` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '其它生成选项',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`table_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=56 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='代码生成业务表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `gen_table_column`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gen_table_column` (
  `column_id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `table_id` bigint DEFAULT NULL COMMENT '归属表编号',
  `column_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列名称',
  `column_comment` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列描述',
  `column_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '列类型',
  `java_type` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'JAVA类型',
  `java_field` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'JAVA字段名',
  `is_pk` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否主键（1是）',
  `is_increment` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否自增（1是）',
  `is_required` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否必填（1是）',
  `is_insert` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否为插入字段（1是）',
  `is_edit` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否编辑字段（1是）',
  `is_list` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否列表字段（1是）',
  `is_query` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否查询字段（1是）',
  `query_type` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT 'EQ' COMMENT '查询方式（等于、不等于、大于、小于、范围）',
  `html_type` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  `dict_type` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `sort` int DEFAULT NULL COMMENT '排序',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`column_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1115 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='代码生成业务表字段';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `growth_badge`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `growth_badge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `badge_code` varchar(80) NOT NULL,
  `badge_name` varchar(120) NOT NULL,
  `badge_desc` varchar(255) DEFAULT NULL,
  `icon_url` varchar(255) DEFAULT NULL,
  `unlock_rule` varchar(255) DEFAULT NULL,
  `status` char(1) NOT NULL DEFAULT '0',
  `sort_no` int NOT NULL DEFAULT '0',
  `club_id` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `asset_type` varchar(16) DEFAULT NULL COMMENT 'IDENTITY/GROWTH/COLLECTION/HONOR',
  `category` varchar(16) DEFAULT NULL COMMENT 'EXPLORE/CREATE/ORGANIZE/CONNECT/CO_CREATE',
  `trigger_event` varchar(64) DEFAULT NULL COMMENT '订阅的领域事件',
  `unlock_rule_json` json DEFAULT NULL COMMENT '规则表达(aggregate/threshold/...)',
  `rule_version` int DEFAULT '1',
  `rarity` varchar(16) DEFAULT NULL COMMENT 'L3/L4可用,L1/L2为空',
  `badge_family` varchar(64) DEFAULT NULL COMMENT '梯度族(如MILE),同族不同阈值',
  `name_en` varchar(64) DEFAULT NULL,
  `statement` varchar(255) DEFAULT NULL COMMENT '身份宣言/卡面主文案',
  `unlock_hint` varchar(255) DEFAULT NULL COMMENT '锁定态可理解的下一步,PRD 8.3',
  `badge_style` varchar(16) NOT NULL DEFAULT 'glow' COMMENT '勋章样式:glow=二维发光/enamel=3D珐琅',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`badge_code`)
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `growth_level_config`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `growth_level_config` (
  `level` int NOT NULL COMMENT '等级',
  `need_exp` int DEFAULT NULL COMMENT '达到本级所需累计探索值',
  `title` varchar(20) DEFAULT NULL COMMENT '等级称号',
  PRIMARY KEY (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M7 成长等级配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `growth_mission`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `growth_mission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `mission_code` varchar(80) NOT NULL COMMENT '任务编码',
  `mission_name` varchar(120) NOT NULL COMMENT '任务名称',
  `mission_desc` varchar(255) DEFAULT NULL COMMENT '任务说明',
  `trigger_event` varchar(80) NOT NULL COMMENT '触发事件',
  `exp_reward` int NOT NULL DEFAULT '0' COMMENT '经验奖励',
  `daily_limit` int NOT NULL DEFAULT '1' COMMENT '每日上限',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_growth_mission_code` (`mission_code`),
  KEY `idx_growth_mission_event` (`trigger_event`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3成长任务';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `growth_mission_progress`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `growth_mission_progress` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `mission_code` varchar(80) NOT NULL COMMENT '任务编码',
  `biz_type` varchar(40) NOT NULL DEFAULT '' COMMENT '业务类型',
  `biz_id` bigint NOT NULL DEFAULT '0' COMMENT '业务ID',
  `progress_date` date NOT NULL COMMENT '进度日期',
  `complete_count` int NOT NULL DEFAULT '1' COMMENT '完成次数',
  `reward_exp` int NOT NULL DEFAULT '0' COMMENT '已发经验',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_growth_progress_idem` (`member_id`,`mission_code`,`biz_type`,`biz_id`,`progress_date`),
  KEY `idx_growth_progress_member_date` (`member_id`,`progress_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3成长任务进度';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `im_block`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `im_block` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '拉黑发起人',
  `blocked_member_id` bigint NOT NULL COMMENT '被拉黑人',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block` (`member_id`,`blocked_member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IM拉黑';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `im_conversation`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `im_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type` tinyint(1) NOT NULL DEFAULT '1' COMMENT '类型 1单聊 2系统 3商家',
  `biz_key` varchar(64) NOT NULL COMMENT '业务唯一键(单聊=小id_大id、系统=sys_会员id)',
  `last_msg_type` tinyint(1) DEFAULT NULL COMMENT '最后一条消息类型',
  `last_msg_text` varchar(500) DEFAULT NULL COMMENT '最后一条消息摘要',
  `last_msg_at` datetime DEFAULT NULL COMMENT '最后一条消息时间',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '状态 0正常 1封禁',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_bizkey` (`type`,`biz_key`),
  KEY `idx_last_msg_at` (`last_msg_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IM会话';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `im_conversation_member`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `im_conversation_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` bigint NOT NULL COMMENT '会话id',
  `member_id` bigint NOT NULL COMMENT '会员id',
  `role` tinyint(1) NOT NULL DEFAULT '0' COMMENT '角色 0普通 1商家 2系统',
  `unread_count` int NOT NULL DEFAULT '0' COMMENT '未读数',
  `last_read_msg_id` bigint NOT NULL DEFAULT '0' COMMENT '已读到的消息id',
  `muted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '免打扰',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1删除会话',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conv_member` (`conversation_id`,`member_id`),
  KEY `idx_member` (`member_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IM会话参与者';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `im_message`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `im_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` bigint NOT NULL COMMENT '会话id',
  `sender_id` bigint NOT NULL DEFAULT '0' COMMENT '发送者会员id(0=系统)',
  `receiver_id` bigint NOT NULL DEFAULT '0' COMMENT '接收者会员id',
  `msg_type` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1文本 2图片 3系统卡片',
  `content` varchar(2000) DEFAULT NULL COMMENT '内容(文本/图片url/卡片json备注)',
  `extra_json` varchar(1000) DEFAULT NULL COMMENT '卡片扩展(跳转/活动id等)',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1撤回 2违规屏蔽',
  `check_status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '内容安全 0待检 1通过 2违规',
  `trace_id` varchar(64) DEFAULT NULL COMMENT '图片异步检测trace_id',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_conv` (`conversation_id`,`id`),
  KEY `idx_receiver` (`receiver_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IM消息';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ip_character`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ip_character` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `world_id` bigint NOT NULL COMMENT '世界ID',
  `character_name` varchar(120) NOT NULL COMMENT '角色名称',
  `avatar_url` varchar(255) DEFAULT NULL COMMENT '头像',
  `intro` varchar(500) DEFAULT NULL COMMENT '简介',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ip_character_world` (`world_id`,`status`,`sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3 IP角色';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ip_city_chapter`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ip_city_chapter` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `world_id` bigint NOT NULL COMMENT '世界ID',
  `chapter_name` varchar(120) NOT NULL COMMENT '章节名称',
  `city_code` varchar(40) DEFAULT NULL COMMENT '城市编码',
  `cover_url` varchar(255) DEFAULT NULL COMMENT '封面',
  `intro` varchar(500) DEFAULT NULL COMMENT '简介',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ip_chapter_world` (`world_id`,`status`,`sort_no`),
  KEY `idx_ip_chapter_city` (`city_code`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3城市章节';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ip_quest`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ip_quest` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `world_id` bigint NOT NULL COMMENT '世界ID',
  `chapter_id` bigint DEFAULT NULL COMMENT '章节ID',
  `quest_name` varchar(120) NOT NULL COMMENT '任务名称',
  `quest_type` varchar(40) NOT NULL DEFAULT 'explore' COMMENT '任务类型',
  `target_biz_type` varchar(40) DEFAULT NULL COMMENT '目标业务类型',
  `target_biz_id` bigint DEFAULT NULL COMMENT '目标业务ID',
  `reward_exp` int NOT NULL DEFAULT '0' COMMENT '奖励经验',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ip_quest_world` (`world_id`,`status`,`sort_no`),
  KEY `idx_ip_quest_target` (`target_biz_type`,`target_biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3世界任务';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ip_world`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ip_world` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `world_code` varchar(80) NOT NULL COMMENT '世界编码',
  `world_name` varchar(120) NOT NULL COMMENT '世界名称',
  `subtitle` varchar(160) DEFAULT NULL COMMENT '副标题',
  `city_code` varchar(40) DEFAULT NULL COMMENT '城市编码',
  `cover_url` varchar(255) DEFAULT NULL COMMENT '封面',
  `intro` varchar(500) DEFAULT NULL COMMENT '简介',
  `status` char(1) NOT NULL DEFAULT '0' COMMENT '状态',
  `deleted` char(1) NOT NULL DEFAULT '0' COMMENT '删除标记',
  `sort_no` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ip_world_code` (`world_code`),
  KEY `idx_ip_world_city_status` (`city_code`,`status`,`deleted`,`sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3 IP世界';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_badge`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_badge` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `badge_code` varchar(80) NOT NULL COMMENT '徽章编码',
  `unlock_time` datetime NOT NULL COMMENT '解锁时间',
  `source_type` varchar(40) DEFAULT NULL COMMENT '来源类型',
  `source_id` bigint DEFAULT NULL COMMENT '来源ID',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_badge` (`member_id`,`badge_code`),
  KEY `idx_member_badge_member` (`member_id`,`unlock_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3会员徽章';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_capability`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_capability` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `capability` varchar(32) NOT NULL COMMENT '能力:club_leader/merchant',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1有效 0已撤销',
  `granted_at` datetime DEFAULT NULL,
  `granted_by` bigint DEFAULT NULL COMMENT '授予人(0=系统)',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_capability` (`member_id`,`capability`),
  KEY `idx_mc_member` (`member_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P1会员能力索引';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_cash_withdraw`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_cash_withdraw` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '提现会员(商家/主理人)',
  `openid` varchar(64) DEFAULT NULL COMMENT '收款人微信openid',
  `amount` decimal(15,2) NOT NULL COMMENT '提现金额(元)',
  `out_batch_no` varchar(64) DEFAULT NULL COMMENT '商户批次单号(幂等键=CW+id)',
  `out_detail_no` varchar(64) DEFAULT NULL COMMENT '商户明细单号',
  `transfer_batch_id` varchar(64) DEFAULT NULL COMMENT '微信批次单号',
  `wechat_detail_id` varchar(64) DEFAULT NULL COMMENT '微信明细号(渠道侧detail_id,对账用)',
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0处理中 1成功 2失败',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因',
  `channel_error_code` varchar(64) DEFAULT NULL COMMENT '渠道错误码(微信FailReasonType枚举/ServiceException.getErrorCode(),机器可读,区别于fail_reason人类文案)',
  `create_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL COMMENT '出金完成/失败时间',
  `del_flag` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0存在 2删除',
  `wechat_batch_status` varchar(32) DEFAULT NULL COMMENT '微信批次状态',
  `submit_time` datetime DEFAULT NULL COMMENT '提交微信时间',
  `last_query_time` datetime DEFAULT NULL COMMENT '最后查询微信时间',
  `balance_deduct_id` bigint DEFAULT NULL COMMENT '余额扣减流水ID',
  `balance_refund_id` bigint DEFAULT NULL COMMENT '余额回补流水ID',
  `manual_operator` varchar(64) DEFAULT NULL COMMENT '人工处理人',
  `manual_verifier` varchar(64) DEFAULT NULL COMMENT '人工回补第二复核人(须与处理人不同)',
  `manual_note` varchar(500) DEFAULT NULL COMMENT '人工处理说明',
  `trace_id` varchar(64) DEFAULT NULL COMMENT '审计 trace id',
  `pending_member_key` bigint GENERATED ALWAYS AS ((case when (`status` in (0,3,4)) then `member_id` else NULL end)) VIRTUAL COMMENT '在途(0/3/4)时=member_id,用于唯一约束原子防重',
  `audit_by` bigint DEFAULT NULL COMMENT '审计操作人ID(自助=memberId,人工=operatorId,自动=0)',
  `audit_time` datetime DEFAULT NULL COMMENT '审计操作时间',
  `frozen` tinyint(1) DEFAULT '0' COMMENT 'P块冻结:1=冻结中(暂停自动化处理,不动余额),0/NULL=未冻结',
  `frozen_reason` varchar(255) DEFAULT NULL COMMENT '冻结原因(运营填写)',
  `frozen_by` varchar(64) DEFAULT NULL COMMENT '冻结操作人(sys_user.user_name)',
  `frozen_at` datetime DEFAULT NULL COMMENT '冻结时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cw_out_batch` (`out_batch_no`),
  UNIQUE KEY `uk_pending_member` (`pending_member_key`),
  KEY `idx_cw_member` (`member_id`),
  KEY `idx_cw_status` (`status`),
  KEY `idx_mcw_status_query` (`status`,`last_query_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信零钱提现单(真实出金)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_collectible`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_collectible` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '玩家 member_id',
  `coll_type` tinyint NOT NULL COMMENT '1=碎片 2=彩蛋',
  `topic_id` bigint DEFAULT NULL COMMENT '所属主题',
  `ref_id` bigint DEFAULT NULL COMMENT '碎片=node_id / 彩蛋=egg_id',
  `content` varchar(1024) DEFAULT NULL COMMENT '碎片文 / 彩蛋文',
  `create_time` datetime DEFAULT NULL COMMENT '收集时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_type_ref` (`member_id`,`coll_type`,`ref_id`),
  KEY `idx_member_type` (`member_id`,`coll_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='FE-01 玩家收集:碎片/彩蛋';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_growth`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_growth` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `level_no` int NOT NULL DEFAULT '1' COMMENT '等级',
  `exp_value` int NOT NULL DEFAULT '0' COMMENT '经验值',
  `last_active_time` datetime DEFAULT NULL COMMENT '最近活跃时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_growth_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3玩家成长账户';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_interest_profile`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_interest_profile` (
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `city_code` varchar(40) DEFAULT NULL COMMENT '城市编码',
  `category_weights_json` text COMMENT '分类权重JSON',
  `creator_weights_json` text COMMENT '创作者权重JSON',
  `last_event_at` datetime DEFAULT NULL COMMENT '最近行为时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`member_id`),
  KEY `idx_interest_city` (`city_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3会员兴趣画像';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `member_visited_poi`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `member_visited_poi` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '玩家',
  `geo_key` varchar(24) NOT NULL COMMENT 'POI坐标3位小数',
  `poi_name` varchar(120) DEFAULT NULL COMMENT 'POI名',
  `lat` decimal(10,6) DEFAULT NULL,
  `lng` decimal(10,6) DEFAULT NULL,
  `first_visit_time` datetime DEFAULT NULL COMMENT '首次到点',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_geo` (`member_id`,`geo_key`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点亮城市到点记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_chapter_application`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_chapter_application` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '由chapter反查写入，查询索引用，不信任前端',
  `chapter_id` bigint NOT NULL COMMENT '申请承接的章节ID',
  `merchant_id` bigint NOT NULL COMMENT '申请商家memberId，不是mms_merchant.id',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待审核 1已通过 2已拒绝',
  `source` tinyint DEFAULT '0' COMMENT '来源 0商家申请 1主办方邀请;邀请=预先批准的申请,其点位免审',
  `message` varchar(500) DEFAULT NULL COMMENT '商家申请说明',
  `audit_remark` varchar(500) DEFAULT NULL COMMENT '发布者审核备注',
  `audit_member_id` bigint DEFAULT NULL COMMENT '实际审核的主题发布者memberId',
  `handle_time` datetime DEFAULT NULL COMMENT '审核时间',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记 0存在 2删除',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chapter_application_merchant` (`chapter_id`,`merchant_id`),
  KEY `idx_chapter_application_topic_status` (`topic_id`,`status`),
  KEY `idx_chapter_application_merchant_status` (`merchant_id`,`status`),
  KEY `idx_chapter_application_chapter_status` (`chapter_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家章节承接申请';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_chapter_offer`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_chapter_offer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint NOT NULL COMMENT '主题id(冗余,便于按主题聚合容量)',
  `chapter_id` bigint NOT NULL COMMENT '章节id',
  `merchant_id` bigint NOT NULL COMMENT '商家memberId(⚠️非mms_merchant.id)',
  `perk_id` bigint DEFAULT NULL COMMENT '引用coop_perk.id(申报层)。⚠️仅PERK档非空——TRAFFIC/REVSHARE没有权益可引用,原NOT NULL会让它们连行都插不进去',
  `quota_total` int NOT NULL DEFAULT '0' COMMENT '本商家在本章的接待上限(人次,不是权益种类数)',
  `quota_used` int NOT NULL DEFAULT '0' COMMENT '已核销人次(核销时原子+1,撤销时原子-1)',
  `valid_end` datetime DEFAULT NULL COMMENT '供给有效期(空=不限)。⚠️到期不会自动翻status,算容量必须同时判它',
  `allowed_validation_methods` varchar(32) DEFAULT NULL COMMENT '承接时冻结的允许验证方式快照;NULL=不限',
  `max_node_xp` int DEFAULT NULL COMMENT '承接时冻结的单节点探索值上限快照;NULL=不限',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1生效 0失效(商家退出标失效,不删行)。⚠️生效判据=status=1 AND (valid_end IS NULL OR valid_end>NOW()),两条缺一即超卖',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `terms_mode` varchar(16) NOT NULL DEFAULT 'PERK' COMMENT 'D21条款档,入驻时须与所属章节的terms_mode一致。章节改档不追溯改写存量offer——已谈定的条款不被追溯(同P0-06/P0-28快照纪律)',
  `per_head_fee` decimal(10,2) DEFAULT NULL COMMENT 'REVSHARE档:每核销人次的固定人头费(条款快照,复用coop shareMode=2语义)。仅REVSHARE非空。⚠️平台采购支出,非分账,零收付通依赖',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_offer_merchant_chapter` (`chapter_id`,`merchant_id`),
  KEY `idx_offer_capacity` (`chapter_id`,`status`,`valid_end`,`quota_total`,`quota_used`),
  KEY `idx_offer_topic` (`topic_id`,`status`),
  KEY `idx_offer_merchant` (`merchant_id`,`status`),
  CONSTRAINT `ck_offer_quota` CHECK (((`quota_used` >= 0) and (`quota_used` <= `quota_total`))),
  CONSTRAINT `ck_offer_terms` CHECK (((`terms_mode` in (_utf8mb4'TRAFFIC',_utf8mb4'REVSHARE',_utf8mb4'PERK')) and ((`terms_mode` <> _utf8mb4'PERK') or (`perk_id` is not null)) and ((`terms_mode` = _utf8mb4'PERK') or (`perk_id` is null)) and ((`terms_mode` <> _utf8mb4'REVSHARE') or ((`per_head_fee` is not null) and (`per_head_fee` >= 0))) and ((`terms_mode` = _utf8mb4'REVSHARE') or (`per_head_fee` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家×章节生效供给(执行层;申报层是coop_perk)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_clawback_application`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_clawback_application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_member_id` bigint NOT NULL COMMENT '被追回的商家 ums_member.id',
  `reason` varchar(1000) NOT NULL COMMENT '违规/投诉依据说明(运营人工填写)',
  `amount` decimal(10,2) NOT NULL COMMENT '倒扣金额(正数,运营人工核实后填写,不由系统计算)',
  `settle_path` varchar(20) NOT NULL COMMENT 'OFFSET_BATCH=冲抵未来批次(默认) / BALANCE=直接扣余额(兜底,仅无未来批次时)。发起时系统自动判定',
  `target_batch_id` bigint DEFAULT NULL COMMENT 'settle_path=OFFSET_BATCH 时的目标 merchant_payable_batch.id;BALANCE 路径为 NULL',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW=待复核 / EXECUTED=已执行 / REJECTED=已驳回',
  `initiated_by` varchar(64) NOT NULL COMMENT '发起人 sys_user.user_name',
  `confirmed_by` varchar(64) DEFAULT NULL COMMENT '复核人 sys_user.user_name(执行/驳回时回填)。必须 ≠ initiated_by',
  `confirmed_at` datetime DEFAULT NULL COMMENT '复核(执行或驳回)时刻',
  `executed_at` datetime DEFAULT NULL COMMENT '实际完成扣款/冲抵的时刻',
  `reject_reason` varchar(500) DEFAULT NULL COMMENT '驳回原因(复核人填写)',
  `create_time` datetime NOT NULL COMMENT '发起时刻',
  PRIMARY KEY (`id`),
  KEY `idx_merchant` (`merchant_member_id`,`create_time`),
  KEY `idx_status` (`status`),
  KEY `idx_target_batch` (`target_batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M块:商家违规倒扣申请(已打款结算款的事后人工追回,发起-复核两步)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_customer_profile`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_customer_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `member_nickname` varchar(100) DEFAULT NULL COMMENT '用户昵称快照',
  `member_phone` varchar(32) DEFAULT NULL COMMENT '用户手机号快照',
  `registration_count` bigint NOT NULL DEFAULT '0' COMMENT '报名次数',
  `order_count` bigint NOT NULL DEFAULT '0' COMMENT '订单次数',
  `order_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '订单金额',
  `coupon_receive_count` bigint NOT NULL DEFAULT '0' COMMENT '领券次数',
  `coupon_verify_count` bigint NOT NULL DEFAULT '0' COMMENT '核销次数',
  `last_interaction_time` datetime DEFAULT NULL COMMENT '最近互动时间',
  `customer_stage` varchar(32) NOT NULL DEFAULT 'new' COMMENT '客户阶段 new/active/lost/vip',
  `city_code` varchar(32) DEFAULT NULL COMMENT '城市编码',
  `tags_json` varchar(1000) DEFAULT NULL COMMENT '标签JSON',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记 0正常 2删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_member` (`merchant_id`,`member_id`),
  KEY `idx_merchant_stage` (`merchant_id`,`customer_stage`),
  KEY `idx_merchant_last_interaction` (`merchant_id`,`last_interaction_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3商家客户画像';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_customer_tag`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_customer_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `tag_code` varchar(64) NOT NULL COMMENT '标签编码',
  `tag_name` varchar(64) NOT NULL COMMENT '标签名称',
  `tag_color` varchar(32) DEFAULT NULL COMMENT '标签颜色',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记 0正常 2删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_tag_code` (`merchant_id`,`tag_code`),
  KEY `idx_merchant_sort` (`merchant_id`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3商家客户标签';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_lead`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_lead` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '绑定 member_id',
  `name` varchar(128) NOT NULL COMMENT '商家名称',
  `phone` varchar(32) DEFAULT NULL COMMENT '联系电话',
  `address` varchar(255) DEFAULT NULL COMMENT '地址',
  `longitude` varchar(32) DEFAULT NULL COMMENT '经度',
  `latitude` varchar(32) DEFAULT NULL COMMENT '纬度',
  `category` varchar(64) DEFAULT NULL COMMENT '商家类型',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签',
  `source` varchar(32) DEFAULT 'offline' COMMENT '来源',
  `contact_open` tinyint DEFAULT '1' COMMENT '开放电话联系',
  `coop_open` tinyint DEFAULT '1' COMMENT '开放线上协作',
  `status` tinyint DEFAULT '1' COMMENT '1展示 0隐藏',
  `del_flag` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lead_geo` (`latitude`,`longitude`),
  KEY `idx_lead_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商家目录/线索';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_payable`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_payable` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `claim_id` bigint DEFAULT NULL COMMENT '存量待历史裁决;新写必填',
  `redemption_id` bigint NOT NULL COMMENT '回指核销流水 redemption.id;REVERSAL 行指向被冲的那笔核销',
  `topic_id` bigint NOT NULL COMMENT '主题id(③-自营)',
  `chapter_id` bigint NOT NULL COMMENT '章节id。③ 必须非空——一票跨N章,**章才是应付的粒度**',
  `merchant_member_id` bigint NOT NULL COMMENT '收款商家 memberId(供应商)。⚠️是 memberId 不是 mms_merchant.id,与 redemption.merchant_id 同口径',
  `per_head_fee_snapshot` decimal(10,2) NOT NULL COMMENT '★核销那一刻冻结的 ¥X/人。**绝不 join merchant_chapter_offer 现算**——商家改价会追溯改写历史应付(P0-28 同型病)',
  `amount` decimal(10,2) NOT NULL COMMENT '★绝对值,不带正负。方向由 entry_type 承载。MVP 形态下 == per_head_fee_snapshot(固定人头费×1人次)',
  `entry_type` varchar(16) NOT NULL COMMENT 'ACCRUAL 计提(核销即计提,已拍板) / REVERSAL 冲正(撤销核销)',
  `event_key` varchar(128) DEFAULT NULL COMMENT 'ORIGIN/不可变调整事件',
  `idempotency_key` varchar(128) NOT NULL COMMENT 'accrual:{redemptionId} / reversal:{redemptionId}。同一笔核销重复入账被 uk 拦住',
  `create_time` datetime NOT NULL COMMENT '★兼作批次归属依据:批次 = (merchant_member_id, 本列所在自然月)。改这列的语义前先读文件头「为什么不存 batch_id」',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payable_idem` (`idempotency_key`),
  UNIQUE KEY `uk_merchant_payable_claim_event` (`claim_id`,`entry_type`,`event_key`),
  KEY `idx_payable_merchant_time` (`merchant_member_id`,`create_time`),
  KEY `idx_payable_redemption` (`redemption_id`),
  CONSTRAINT `ck_payable_amount` CHECK ((`amount` >= 0)),
  CONSTRAINT `ck_payable_entry_type` CHECK ((`entry_type` in (_utf8mb4'ACCRUAL',_utf8mb4'REVERSAL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='③计酬档平台应付台账(append-only事件流水;冲正走REVERSAL新行;净额=SUM(CASE entry_type WHEN ACCRUAL THEN amount ELSE -amount END))';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_payable_bak_20260811_w`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_payable_bak_20260811_w` (
  `id` bigint NOT NULL DEFAULT '0',
  `redemption_id` bigint NOT NULL COMMENT '回指核销流水 redemption.id;REVERSAL 行指向被冲的那笔核销',
  `topic_id` bigint NOT NULL COMMENT '主题id(③-自营)',
  `chapter_id` bigint NOT NULL COMMENT '章节id。③ 必须非空——一票跨N章,**章才是应付的粒度**',
  `merchant_member_id` bigint NOT NULL COMMENT '收款商家 memberId(供应商)。⚠️是 memberId 不是 mms_merchant.id,与 redemption.merchant_id 同口径',
  `per_head_fee_snapshot` decimal(10,2) NOT NULL COMMENT '★核销那一刻冻结的 ¥X/人。**绝不 join merchant_chapter_offer 现算**——商家改价会追溯改写历史应付(P0-28 同型病)',
  `amount` decimal(10,2) NOT NULL COMMENT '★绝对值,不带正负。方向由 entry_type 承载。MVP 形态下 == per_head_fee_snapshot(固定人头费×1人次)',
  `entry_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'ACCRUAL 计提(核销即计提,已拍板) / REVERSAL 冲正(撤销核销)',
  `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'accrual:{redemptionId} / reversal:{redemptionId}。同一笔核销重复入账被 uk 拦住',
  `create_time` datetime NOT NULL COMMENT '★兼作批次归属依据:批次 = (merchant_member_id, 本列所在自然月)。改这列的语义前先读文件头「为什么不存 batch_id」'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_payable_batch`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_payable_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_member_id` bigint NOT NULL COMMENT '收款商家 memberId',
  `period_ym` char(7) NOT NULL COMMENT '结算期,自然月 YYYY-MM(如 2026-07)。次月结上月',
  `amount_total` decimal(12,2) NOT NULL COMMENT '★生成时冻结的净额=SUM(CASE entry_type WHEN ACCRUAL THEN amount ELSE -amount END)。是实际打出去的那个数,留痕用,别每次查都重算。⚠️可能为负(上月已付、本月大量冲正)——那是欠平台钱,别夹到0',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待结 / CONFIRMED对账确认 / PAID已打款(2026-07-17拍板三档)。⚠️与 invoice_status 是**两条独立轴**,别混——混了就表达不了「已打款但未开票」',
  `invoice_status` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE未开票 / ISSUED已开蓝票 / RED已红冲。★冲正路径的分支键:已开票的冲正必须走红字发票流程',
  `invoice_no` varchar(64) DEFAULT NULL COMMENT '蓝票号',
  `red_invoice_no` varchar(64) DEFAULT NULL COMMENT '红票号(已开票批次被冲正时必须回填)',
  `confirmed_at` datetime DEFAULT NULL COMMENT '对账确认时间',
  `confirmed_by` varchar(64) DEFAULT NULL COMMENT '对账确认人(运营)',
  `paid_at` datetime DEFAULT NULL COMMENT '打款时间',
  `paid_by` varchar(64) DEFAULT NULL COMMENT '打款操作人(运营)',
  `pay_voucher_no` varchar(64) DEFAULT NULL COMMENT '对公打款凭证号/流水号,留痕对账用',
  `create_time` datetime DEFAULT NULL,
  `frozen` tinyint(1) DEFAULT '0' COMMENT 'P块冻结:1=冻结中(暂停对账确认/打款,不动金额),0/NULL=未冻结',
  `frozen_reason` varchar(255) DEFAULT NULL COMMENT '冻结原因(运营填写)',
  `frozen_by` varchar(64) DEFAULT NULL COMMENT '冻结操作人(sys_user.user_name)',
  `frozen_at` datetime DEFAULT NULL COMMENT '冻结时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_merchant_period` (`merchant_member_id`,`period_ym`),
  KEY `idx_batch_status` (`status`,`period_ym`),
  CONSTRAINT `ck_batch_invoice_status` CHECK ((`invoice_status` in (_utf8mb4'NONE',_utf8mb4'ISSUED',_utf8mb4'RED'))),
  CONSTRAINT `ck_batch_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'CONFIRMED',_utf8mb4'PAID')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='③计酬档应付月结批次(状态机+发票轴;成员由(商家,period_ym)推导不落batch_id)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_referral_bounty`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_referral_bounty` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_member_id` bigint NOT NULL COMMENT '被推荐的商家 ums_member.id',
  `inviter_member_id` bigint NOT NULL COMMENT '拓展人 ums_member.id(来源 ums_member.invite_user_id)',
  `merchant_registered_at` datetime NOT NULL COMMENT '商家注册时间 = 30 天激活窗口的起点',
  `window_expires_at` datetime NOT NULL COMMENT '= merchant_registered_at + 30 天;落值不落算式,免得改规则时追溯改写历史',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0=待激活 1=已发放 2=已追回 3=已失效(超窗未达标)',
  `activated_at` datetime DEFAULT NULL COMMENT '达到 3 次核销的时刻',
  `verified_heads_snapshot` varchar(512) DEFAULT NULL COMMENT '★判定快照:构成激活的 cms_registration.id 列表(逗号分隔)。clawback 的唯一依据——没有它就不知道该追哪笔',
  `bounty_amount` decimal(10,2) DEFAULT NULL COMMENT '实发金额。★落值不落配置:日后改价不该追溯改写历史记录',
  `paid_at` datetime DEFAULT NULL COMMENT '入账时刻',
  `frozen_until` datetime DEFAULT NULL COMMENT '= paid_at + 48h,冻结期内不可提现,让 clawback 在钱离开平台前完成',
  `clawed_back_at` datetime DEFAULT NULL COMMENT '追回时刻',
  `clawback_reason` varchar(255) DEFAULT NULL COMMENT '追回原因(如:构成激活的核销 #123 已退款)',
  `del_flag` char(1) DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant` (`merchant_member_id`),
  KEY `idx_inviter_status` (`inviter_member_id`,`status`),
  KEY `idx_frozen` (`status`,`frozen_until`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拓展人激活赏金(冷启动;一次性¥30/30天3核销/封顶20/48h冻结)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `merchant_subscription`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `merchant_subscription` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint NOT NULL COMMENT '商家 memberId',
  `subscription_type` varchar(50) COLLATE utf8mb4_general_ci NOT NULL COMMENT '权益类型 premium_template/promotion_slot/brand_home/custom_event',
  `start_date` datetime DEFAULT NULL,
  `end_date` datetime DEFAULT NULL COMMENT '到期时间(NULL=永久)',
  `max_usage` int DEFAULT NULL COMMENT '可用次数(NULL=不限)',
  `used_count` int DEFAULT '0',
  `is_active` tinyint DEFAULT '1' COMMENT '1生效 0停用',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_merchant` (`merchant_id`),
  KEY `idx_type` (`subscription_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='E4商家付费权益/订阅';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `metrics_snapshot`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `metrics_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `metric_key` varchar(96) NOT NULL COMMENT '指标自然键，低基数，不含 URL/用户/订单维度',
  `window_start` datetime NOT NULL COMMENT '采样窗口开始',
  `window_end` datetime NOT NULL COMMENT '采样窗口结束',
  `source` varchar(64) NOT NULL COMMENT '采样来源，如 app/druid/tomcat/jvm',
  `numerator` decimal(20,6) DEFAULT NULL COMMENT '分子或计数',
  `denominator` decimal(20,6) DEFAULT NULL COMMENT '分母',
  `value` decimal(20,6) NOT NULL COMMENT '指标值',
  `threshold_version` varchar(64) NOT NULL COMMENT '阈值/口径版本',
  `status` varchar(16) NOT NULL COMMENT 'OK 或 FAIL',
  `sampled_at` datetime NOT NULL COMMENT '实际采样时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_metrics_snapshot_window` (`metric_key`,`window_start`,`window_end`),
  KEY `idx_metrics_snapshot_sampled_at` (`sampled_at`),
  KEY `idx_metrics_snapshot_status_sampled` (`status`,`sampled_at`)
) ENGINE=InnoDB AUTO_INCREMENT=49373 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='D12 轻量指标快照，不含 PII';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `mms_merchant`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mms_merchant` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '会员ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家名称',
  `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '微信二维码',
  `category_id` int DEFAULT '0' COMMENT '分类ID',
  `logo` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家LOGO',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '介绍',
  `derivatives` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '衍生品',
  `website` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌网址',
  `preference` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '品牌偏好',
  `phone` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '联系电话',
  `address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '商户地址',
  `business_time` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业时间',
  `business_license` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '营业执照',
  `status` int DEFAULT '0' COMMENT '入住审核状态0 待审核  1审核通过  2审核失败',
  `reson` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `account_status` int DEFAULT '0' COMMENT '账号状态：1启用  2禁用',
  `business_status` tinyint NOT NULL DEFAULT '1' COMMENT '营业状态 1营业中 0已打烊',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `capacity` int DEFAULT NULL COMMENT '可容纳/承接人数',
  `available_time` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '可承接时段',
  `suit_activity_types` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '适合活动类型(逗号分隔)',
  `charge_type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '承接收费 0免费 1收费',
  `demand` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '诉求',
  `coop_open` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否开放被达人对接 0否1是',
  `cover_image` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-封面头图URL',
  `slogan` varchar(60) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-一句话slogan',
  `city_role` varchar(30) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-城市角色名',
  `story_title` varchar(60) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-品牌故事标题(正文用description)',
  `gallery` text COLLATE utf8mb4_general_ci COMMENT '店铺装修-门店相册JSON数组',
  `tags` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '店铺装修-特色标签JSON/逗号',
  `featured_type` tinyint DEFAULT '0' COMMENT '店铺装修-招牌主推类型0无/1活动主题/2券',
  `featured_id` bigint DEFAULT NULL COMMENT '店铺装修-招牌主推目标id',
  `location_lat` decimal(10,7) DEFAULT NULL COMMENT '商家纬度(手动打点/LBS)',
  `location_lng` decimal(10,7) DEFAULT NULL COMMENT '商家经度',
  `location_verified` tinyint DEFAULT '0' COMMENT '0未定位 1商家地图选点 2按地址反查推断',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='商家表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `npc_chat_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `npc_chat_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `profile_id` bigint NOT NULL,
  `activity_id` bigint NOT NULL COMMENT '记忆作用域',
  `user_msg` text NOT NULL,
  `npc_reply` text,
  `safety_flag` tinyint DEFAULT '0' COMMENT '0正常 1输入拦 2输出拦 3降级',
  `model` varchar(32) DEFAULT NULL COMMENT '命中模型',
  `latency_ms` int DEFAULT NULL,
  `token_in` int DEFAULT NULL,
  `token_out` int DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_activity` (`user_id`,`activity_id`),
  KEY `idx_safety` (`safety_flag`),
  KEY `idx_npc_chat_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='NPC追问日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `npc_chat_request`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `npc_chat_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `session_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'PROCESSING/SUCCEEDED/REJECTED/FAILED',
  `safety_decision` varchar(16) NOT NULL COMMENT 'PASS/BLOCKED/NOT_RUN',
  `error_code` varchar(64) DEFAULT NULL,
  `limit_type` varchar(16) DEFAULT NULL,
  `retry_after_seconds` int DEFAULT NULL,
  `retryable` tinyint NOT NULL DEFAULT '0',
  `safe_text` varchar(1000) DEFAULT NULL,
  `ai` tinyint NOT NULL DEFAULT '1',
  `lease_token` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `active_slot` tinyint DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_npc_chat_request_user_request` (`user_id`,`request_id`),
  UNIQUE KEY `uk_npc_chat_request_active_session` (`user_id`,`session_id`,`active_slot`),
  KEY `idx_npc_chat_request_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='漫游NPC Chat幂等终态（48小时）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `npc_line_cache`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `npc_line_cache` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `profile_id` bigint NOT NULL COMMENT '关联npc_profile.id',
  `event_type` varchar(32) NOT NULL COMMENT 'enter/checkin_success/answer_wrong/answer_right/near_merchant/activity_finish',
  `node_id` bigint DEFAULT NULL COMMENT '绑定节点',
  `variants_json` text NOT NULL COMMENT '话术变体JSON数组',
  `audit_status` tinyint DEFAULT '0' COMMENT '0待审 1通过',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `version` int NOT NULL DEFAULT '1' COMMENT '话术版本,更新=新version行',
  `node_key` bigint GENERATED ALWAYS AS (ifnull(`node_id`,0)) STORED COMMENT '唯一键用(NULL节点→0)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_line_version` (`profile_id`,`event_type`,`node_key`,`version`),
  KEY `idx_lookup` (`profile_id`,`event_type`,`node_id`,`audit_status`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='NPC事件话术缓存';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `npc_profile`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `npc_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scope_type` tinyint NOT NULL COMMENT '1=剧本角色 2=门店分身 3=全局NPC',
  `scope_id` bigint NOT NULL DEFAULT '0' COMMENT 'activityId/merchantId',
  `name` varchar(32) NOT NULL COMMENT 'NPC名',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像URL',
  `persona` text NOT NULL COMMENT '人设提示词',
  `greeting` varchar(255) DEFAULT NULL COMMENT '默认开场白',
  `sort` int DEFAULT '0' COMMENT '出场顺序',
  `audit_status` tinyint DEFAULT '0' COMMENT '0待审 1通过 2驳回',
  `enabled` tinyint DEFAULT '1',
  `del_flag` char(1) DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `active_slot` tinyint DEFAULT NULL COMMENT '1=生效槽(仅全局NPC scope_type=3 用,同scope唯一);NULL=非生效/非全局',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_scope` (`scope_type`,`scope_id`,`active_slot`),
  KEY `idx_scope` (`scope_type`,`scope_id`,`sort`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI NPC 人设';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `npc_route_egg`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `npc_route_egg` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic_id` bigint DEFAULT NULL,
  `seq` int DEFAULT NULL COMMENT '沿路顺序',
  `lat` decimal(10,6) DEFAULT NULL,
  `lng` decimal(10,6) DEFAULT NULL,
  `radius_m` int DEFAULT '120' COMMENT '触发半径(米)',
  `text` varchar(300) DEFAULT NULL COMMENT '彩蛋文案(≤80字,预审入库)',
  `status` tinyint DEFAULT NULL COMMENT '0待审 1已审',
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_topic` (`topic_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M5 途中彩蛋池(每主题≤15条)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_broadcast`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_broadcast` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint DEFAULT NULL COMMENT '关联 official_event.id,NULL=纯公告',
  `title` varchar(128) NOT NULL COMMENT '通知标题',
  `audience` varchar(32) NOT NULL COMMENT '目标角色逗号集:player,club,merchant',
  `copy_mode` tinyint DEFAULT '1' COMMENT '1一起发(统一文案) 2分开发(分角色文案)',
  `content_json` text COMMENT '一起发={title,sub},分开发={player:{..},club:{..},merchant:{..}}',
  `channels` varchar(32) NOT NULL DEFAULT 'inapp' COMMENT '触达渠道逗号集:inapp,subscribe',
  `city` varchar(32) DEFAULT NULL COMMENT '城市名关键词,NULL=全国全体该角色',
  `scheduled_time` datetime DEFAULT NULL COMMENT 'NULL=立即发',
  `status` tinyint DEFAULT '0' COMMENT '0草稿 1待发 2已发 3已撤',
  `reached_count` int DEFAULT '0' COMMENT '触达数(冗余统计)',
  `sender_account` bigint DEFAULT NULL COMMENT '发起白名单账号 member_id',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_event` (`event_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方通知/广播';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_broadcast_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_broadcast_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `broadcast_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `role` varchar(16) DEFAULT NULL COMMENT 'player/club/merchant',
  `channel` varchar(16) DEFAULT NULL COMMENT 'inapp/subscribe',
  `reached` tinyint DEFAULT '0' COMMENT '1已触达',
  `clicked` tinyint DEFAULT '0' COMMENT '1已点击',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bc_member_channel` (`broadcast_id`,`member_id`,`channel`),
  KEY `idx_broadcast` (`broadcast_id`),
  KEY `idx_obl_member_channel_reached_time` (`member_id`,`channel`,`reached`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方通知分发日志(uk 防重发)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(128) NOT NULL COMMENT '活动标题',
  `subtitle` varchar(255) DEFAULT NULL COMMENT '副标题',
  `category` varchar(32) DEFAULT NULL COMMENT '预设分类:city_light/custom/festival/brand/challenge/hidden/community/merchant_hunt',
  `cover_img` varchar(500) DEFAULT NULL COMMENT '主题海报',
  `story` text COMMENT '故事背景(富文本/纯文本)',
  `city` varchar(32) DEFAULT NULL COMMENT '城市名关键词(匹配club.city/work_address),NULL=全国',
  `signup_start` datetime DEFAULT NULL COMMENT '报名开始',
  `activity_start` datetime DEFAULT NULL COMMENT '活动开始',
  `activity_end` datetime DEFAULT NULL COMMENT '活动结束',
  `settle_time` datetime DEFAULT NULL COMMENT '结算时间',
  `signup_limit` int DEFAULT '0' COMMENT '报名上限,0=不限',
  `content_json` text COMMENT '绑定内容:{"play_type":"linear/collect","topic_ids":[..],"poi_ids":[..],"tasks":[..]}',
  `collective_enabled` tinyint DEFAULT '0' COMMENT '集体玩法开关',
  `collective_metric` varchar(32) DEFAULT NULL COMMENT '集体指标:light_count/signup_count/complete_count',
  `collective_threshold` int DEFAULT '0' COMMENT '集体达标阈值',
  `leaderboard_enabled` tinyint DEFAULT '0' COMMENT '排行榜开关',
  `reward_json` text COMMENT '奖励配置:{"instant":..,"milestone":..,"settle":..,"collective":..,"limit":N}',
  `merchant_ids` varchar(255) DEFAULT NULL COMMENT '绑定合作商家 mms_merchant.id,逗号分隔',
  `brand_logo` varchar(500) DEFAULT NULL COMMENT '品牌/主办方 logo',
  `banner_enabled` tinyint DEFAULT '0' COMMENT '首页城市事件 banner 开关',
  `sort` int DEFAULT '0' COMMENT '运营排序权重',
  `status` tinyint DEFAULT '0' COMMENT '0草稿 1已排期 2报名中 3进行中 4待结算 5已结束 6已复盘 9已下线',
  `sender_account` bigint DEFAULT NULL COMMENT '发起白名单账号 member_id',
  `creator_identity_source` varchar(24) NOT NULL DEFAULT 'LEGACY_UNRESOLVED' COMMENT 'V2创建身份域:UMS_MEMBER/LEGACY_UNRESOLVED',
  `contract_version` tinyint NOT NULL DEFAULT '1' COMMENT '1历史兼容/2证据合同',
  `fulfillment_policy` varchar(32) NOT NULL DEFAULT 'SELF_RUN' COMMENT 'SELF_RUN/OPTIONAL_PARTNERS/REQUIRED_FULFILLMENT',
  `control_state` varchar(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/PAUSED',
  `paused_from_status` tinyint DEFAULT NULL COMMENT '暂停前生命周期状态',
  `paused_at` datetime DEFAULT NULL COMMENT '暂停时间',
  `paused_by` bigint DEFAULT NULL COMMENT '暂停操作者',
  `paused_reason` varchar(500) DEFAULT NULL COMMENT '暂停原因',
  `pause_impact_scope` varchar(500) DEFAULT NULL COMMENT '暂停影响范围或人数',
  `pause_alternative_plan` varchar(500) DEFAULT NULL COMMENT '暂停替代方案',
  `pause_notification_plan` varchar(500) DEFAULT NULL COMMENT '暂停通知策略',
  `pause_notification_status` varchar(16) DEFAULT NULL COMMENT '暂停通知状态:PENDING/NOT_REQUIRED',
  `resume_note` varchar(500) DEFAULT NULL COMMENT '恢复风险处理说明',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_city_status` (`city`,`status`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动(可配置活动引擎)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_audit_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `action` varchar(64) NOT NULL,
  `operator_id` bigint DEFAULT NULL,
  `summary` varchar(500) DEFAULT NULL,
  `before_json` text,
  `after_json` text,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_event_time` (`event_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2操作审计';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_binding`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `binding_code` varchar(64) NOT NULL,
  `binding_type` varchar(32) NOT NULL COMMENT 'THEME/ROAM_POI/ROAM_AREA/MERCHANT_NODE/CLUB_SESSION/EVENT_OWNED_POINT/DISPLAY_ONLY',
  `source_mode` varchar(16) NOT NULL COMMENT 'REFERENCE/EVENT_OWNED',
  `source_ref_id` bigint DEFAULT NULL,
  `city` varchar(32) DEFAULT NULL,
  `latitude` decimal(10,6) DEFAULT NULL,
  `longitude` decimal(10,6) DEFAULT NULL,
  `radius_m` int DEFAULT NULL,
  `geometry_json` text,
  `display_json` text,
  `snapshot_json` text,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `sort` int NOT NULL DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_binding_code` (`event_id`,`binding_code`),
  UNIQUE KEY `uk_event_binding_reference` (`event_id`,`binding_type`,`source_ref_id`),
  KEY `idx_event_status` (`event_id`,`status`),
  KEY `idx_source` (`binding_type`,`source_ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2内容与空间绑定';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_fulfillment_requirement`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_fulfillment_requirement` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `mission_id` bigint DEFAULT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` bigint NOT NULL,
  `window_start` datetime DEFAULT NULL,
  `window_end` datetime DEFAULT NULL,
  `required_role` varchar(32) NOT NULL,
  `required_capacity` int NOT NULL DEFAULT '0',
  `critical` tinyint NOT NULL DEFAULT '1',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_event_status` (`event_id`,`status`),
  KEY `idx_mission` (`mission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2履约覆盖需求';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_mission`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_mission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `binding_id` bigint NOT NULL,
  `mission_code` varchar(64) NOT NULL,
  `mission_type` varchar(32) NOT NULL COMMENT 'ROAM_POI_ARRIVAL/EVENT_POINT_ARRIVAL/THEME_VERIFIED_FINISH',
  `title` varchar(128) NOT NULL,
  `short_description` varchar(500) DEFAULT NULL,
  `rule_json` text NOT NULL,
  `prerequisite_json` text,
  `rule_version` varchar(32) NOT NULL DEFAULT '1',
  `required_count` int NOT NULL DEFAULT '1',
  `visibility` tinyint NOT NULL DEFAULT '1',
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `sort` int NOT NULL DEFAULT '0',
  `available_start` datetime DEFAULT NULL,
  `available_end` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_mission_code` (`event_id`,`mission_code`),
  KEY `idx_event_status` (`event_id`,`status`),
  KEY `idx_binding` (`binding_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2玩家任务合同';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_mission_evidence`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_mission_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `mission_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `signup_id` bigint DEFAULT NULL,
  `source_type` varchar(48) NOT NULL,
  `source_fact_id` varchar(128) NOT NULL,
  `dedupe_key` varchar(200) NOT NULL,
  `status` varchar(20) NOT NULL COMMENT 'PENDING_REVIEW/VALID/REJECTED/REVOKED',
  `reason` varchar(500) DEFAULT NULL,
  `source_snapshot_json` text,
  `rule_snapshot_json` text,
  `occurred_at` datetime NOT NULL,
  `validated_at` datetime DEFAULT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `operated_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evidence_dedupe` (`dedupe_key`),
  UNIQUE KEY `uk_evidence_source` (`event_id`,`mission_id`,`member_id`,`source_type`,`source_fact_id`),
  UNIQUE KEY `uk_evidence_source_once` (`source_type`,`source_fact_id`),
  KEY `idx_event_member_status` (`event_id`,`member_id`,`status`),
  KEY `idx_mission_status` (`mission_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2任务证据';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_operator_authorization`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_operator_authorization` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sys_user_id` bigint NOT NULL,
  `active_sys_user_id` bigint DEFAULT NULL,
  `member_id` bigint NOT NULL COMMENT 'ums_member.id，必须是官方发布白名单会员',
  `status` varchar(16) NOT NULL COMMENT 'ACTIVE/REVOKED',
  `granted_by` bigint NOT NULL COMMENT '授予授权的sys_user.id',
  `granted_at` datetime NOT NULL,
  `grant_reason` varchar(500) NOT NULL,
  `revoked_by` bigint DEFAULT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `revoke_reason` varchar(500) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_sys_user` (`active_sys_user_id`),
  KEY `idx_member_status` (`member_id`,`status`),
  KEY `idx_sys_user_history` (`sys_user_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台官方活动操作员到会员主体的显式授权';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_participation`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_participation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `signup_id` bigint DEFAULT NULL,
  `state` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/LEFT/REFUNDED/BANNED',
  `reason` varchar(500) DEFAULT NULL,
  `joined_at` datetime NOT NULL,
  `invalidated_at` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_member` (`event_id`,`member_id`),
  KEY `idx_event_state` (`event_id`,`state`),
  KEY `idx_member_state` (`member_id`,`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2有效参与关系';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_party`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_party` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `party_type` varchar(16) NOT NULL COMMENT 'OFFICIAL/MERCHANT/CLUB',
  `party_id` bigint NOT NULL COMMENT 'OFFICIAL=ums_member.id;MERCHANT=mms_merchant.id;CLUB=club.id',
  `identity_source` varchar(24) NOT NULL DEFAULT 'LEGACY_UNRESOLVED' COMMENT 'OFFICIAL=UMS_MEMBER;未知旧身份不参与授权',
  `organizer_authority` tinyint NOT NULL DEFAULT '0' COMMENT '仅OFFICIAL/ORGANIZER:1当前唯一有效主办',
  `role` varchar(32) NOT NULL COMMENT 'ORGANIZER/CO_ORGANIZER/FULFILLMENT_MERCHANT/FULFILLMENT_CLUB/SPONSOR/PROMOTION_PARTNER',
  `target_type` varchar(32) DEFAULT NULL,
  `target_id` bigint DEFAULT NULL,
  `window_start` datetime DEFAULT NULL,
  `window_end` datetime DEFAULT NULL,
  `capacity` int DEFAULT NULL,
  `responsibility_summary` varchar(500) DEFAULT NULL,
  `contact_role` varchar(64) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'INVITED',
  `invited_by` bigint DEFAULT NULL,
  `invited_at` datetime DEFAULT NULL,
  `status_by` bigint DEFAULT NULL,
  `status_at` datetime DEFAULT NULL,
  `status_reason` varchar(500) DEFAULT NULL,
  `snapshot_json` text,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_event_status` (`event_id`,`status`),
  KEY `idx_party` (`party_type`,`party_id`),
  KEY `idx_event_role` (`event_id`,`role`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2主办协办与承接关系';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `official_event_reward_issuance`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `official_event_reward_issuance` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL,
  `member_id` bigint NOT NULL,
  `reward_rule_version` varchar(32) NOT NULL,
  `status` varchar(20) NOT NULL COMMENT 'NOT_ELIGIBLE/ELIGIBLE/PENDING_ISSUE/ISSUED/CLAIMED/FAILED/MANUAL_REVIEW',
  `issuance_key` varchar(160) NOT NULL,
  `payload_json` text,
  `failure_reason` varchar(500) DEFAULT NULL,
  `source_snapshot_json` text,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_member_rule` (`event_id`,`member_id`,`reward_rule_version`),
  UNIQUE KEY `uk_issuance_key` (`issuance_key`),
  KEY `idx_event_status` (`event_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方活动V2奖励投递与资格读模型';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `oms_order`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单id',
  `order_type` int DEFAULT NULL COMMENT '订单类型：1积分商城',
  `biz_type` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商业化订单类型:premium_template/promotion_slot/custom_event',
  `entitlement_max_usage` int DEFAULT NULL COMMENT '下单时冻结的权益次数',
  `entitlement_days` int DEFAULT NULL COMMENT '下单时冻结的有效天数',
  `payment_transaction_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '微信支付交易号,用于回调对账',
  `order_sn` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单编号',
  `pay_id` bigint DEFAULT NULL COMMENT '支付id',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `mms_merchant_id` bigint DEFAULT NULL COMMENT '商家ID',
  `product_quantity` int DEFAULT NULL COMMENT '订单商品数量',
  `product_total_amount` decimal(10,2) DEFAULT NULL COMMENT '订单商品总金额',
  `delivery_fee` decimal(10,2) DEFAULT NULL COMMENT '配送费',
  `pay_amount` decimal(10,2) DEFAULT NULL COMMENT '第三方支付金额',
  `tax_fee` decimal(10,2) DEFAULT NULL COMMENT '税费',
  `order_total_amount` decimal(10,2) DEFAULT NULL COMMENT '订单总金额',
  `points_amount` decimal(10,2) DEFAULT NULL COMMENT '积分抵扣金额',
  `pay_type` int DEFAULT NULL COMMENT '支付方式：0未支付  1积分支付',
  `status` int DEFAULT NULL COMMENT '订单状态：0->待付款；1->待发货；2->已发货；3->待评价；4->已完成；5->已关闭；6->无效订单',
  `aftersale_status` int DEFAULT NULL COMMENT '退款状态，枚举值：1：无售后或售后关闭，2：售后处理中，3：退款中，4： 退款成功',
  `delivery_company` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '物流公司(配送方式)',
  `delivery_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '物流单号',
  `auto_confirm_day` int DEFAULT NULL COMMENT '自动确认时间（天）',
  `receiver_name` varchar(768) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收货人姓名(AES-GCM密文)',
  `receiver_phone` varchar(256) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收货人手机(AES-GCM密文)',
  `receiver_phone_hash` char(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收货手机HMAC盲索引',
  `receiver_phone_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收货人电话区号',
  `receiver_province` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收货人省市区',
  `receiver_detail_address` varchar(1280) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收货详细地址(AES-GCM密文)',
  `order_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '订单备注',
  `merchant_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商家备注',
  `confirm_status` int DEFAULT NULL COMMENT '确认收货状态：0->未确认；1->已确认',
  `payment_time` datetime DEFAULT NULL COMMENT '支付时间',
  `delivery_time` datetime DEFAULT NULL COMMENT '发货时间',
  `receive_time` datetime DEFAULT NULL COMMENT '确认收货时间',
  `member_coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券id',
  `coupon_amount` decimal(10,2) DEFAULT NULL COMMENT '优惠券金额',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_order_receiver_phone_hash` (`receiver_phone_hash`),
  KEY `idx_oms_order_biz_sn` (`biz_type`,`order_sn`)
) ENGINE=InnoDB AUTO_INCREMENT=154 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='订单表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `oms_order_item`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL COMMENT '订单id',
  `product_id` bigint DEFAULT NULL,
  `out_product_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品编码',
  `sku_id` bigint DEFAULT NULL COMMENT '商品sku id',
  `out_sku_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'sku编码',
  `pic` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '展示图片',
  `product_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品名称',
  `price` decimal(10,2) DEFAULT NULL COMMENT '价格',
  `original_price` decimal(10,2) DEFAULT NULL COMMENT '原价',
  `quantity` int DEFAULT NULL COMMENT '购买数量',
  `sp_data` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品sku属性:[{"key":"颜色","value":"颜色"},{"key":"容量","value":"4G"}]',
  `is_evaluate` int DEFAULT '0' COMMENT '是否评价 0未评价 1已评价 （但是订单要已收货才能评价）',
  `aftersale_order_id` bigint DEFAULT NULL COMMENT '售后订单ID',
  `aftersale_status` int DEFAULT '0' COMMENT '售后状态：0未申请售后  ',
  `create_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime(3) DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime(3) DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=165 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='订单中所包含的商品';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `oms_ticket`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_ticket` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '票务发布用户ID',
  `owner_type` int DEFAULT NULL COMMENT '拥有类型1、主题  2、活动',
  `owner_id` bigint DEFAULT '0' COMMENT '主题ID/活动ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '票务名称',
  `price` decimal(10,2) DEFAULT NULL COMMENT '价格',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `total_inventory` int DEFAULT NULL COMMENT '总库存',
  `remaining_inventory` int DEFAULT NULL COMMENT '剩余库存',
  `mode` int DEFAULT '1' COMMENT '活动模式 1=城市定向 2=自由探索',
  `sub_type` varchar(8) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '票种子类型 guided有人带/self无人带(仅mode=1城市定向)',
  `meeting_point` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '集合地点',
  `team_size` int DEFAULT '0' COMMENT '建议组队人数',
  `min_people` int DEFAULT NULL COMMENT '最低成团人数',
  `max_people` int DEFAULT NULL COMMENT '成团人数上限',
  `signup_deadline` datetime DEFAULT NULL COMMENT '报名截止时间',
  `gather_lng` decimal(10,6) DEFAULT NULL COMMENT '集合点经度',
  `gather_lat` decimal(10,6) DEFAULT NULL COMMENT '集合点纬度',
  `team_status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '成团状态 0募集中 1已成团 2未成团已散',
  `refund_rule` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '退款规则',
  `need_audit` tinyint(1) NOT NULL DEFAULT '0' COMMENT '报名是否需主理人确认',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=75 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='主题/活动票务表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pay_transaction`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pay_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_type` bigint NOT NULL COMMENT '订单类型：1报名  2积分商城 ',
  `order_id` bigint NOT NULL,
  `payment_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '支付流水号',
  `payment_amount` decimal(10,2) NOT NULL COMMENT '支付金额',
  `payment_type` tinyint NOT NULL COMMENT '支付方式',
  `payment_status` tinyint NOT NULL COMMENT '支付状态',
  `transaction_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '第三方交易号',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_payment_no` (`payment_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='支付记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `play_attempt_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `play_attempt_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL,
  `topic_id` bigint DEFAULT NULL,
  `node_id` bigint DEFAULT NULL,
  `activity_id` bigint DEFAULT NULL,
  `attempt_type` tinyint DEFAULT NULL COMMENT '1答题 2扫码 3GPS 4拍照',
  `result` tinyint DEFAULT NULL COMMENT '1过 0错 2放弃',
  `wrong_answer` varchar(120) DEFAULT NULL COMMENT '答错时用户答案(供AI出题评估)',
  `duration_s` int DEFAULT NULL COMMENT '本次尝试用时(秒,可空)',
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tn` (`topic_id`,`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M9 节点级游玩尝试日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `player_badge`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `player_badge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `badge_code` varchar(64) NOT NULL COMMENT '徽章code(FIRST_STEP/TOPIC_CLEAR/STREAK_x/MILE_x)',
  `badge_name` varchar(128) DEFAULT NULL COMMENT '徽章名(冗余,展示用)',
  `icon_url` varchar(255) DEFAULT NULL COMMENT '徽章图标',
  `source_type` int DEFAULT NULL COMMENT '1首次 2通关 3里程 4连续 5等级',
  `source_id` bigint DEFAULT '0' COMMENT '来源对象ID(通关=topicId,其余0)',
  `obtain_time` datetime DEFAULT NULL,
  `del_flag` int DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_badge` (`member_id`,`badge_code`,`source_id`),
  KEY `idx_badge_code_source` (`badge_code`,`source_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='玩家已获徽章';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `player_growth`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `player_growth` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `level` int DEFAULT '1' COMMENT '等级',
  `total_checkins` int DEFAULT '0' COMMENT '累计节点完成数',
  `total_mileage` decimal(10,2) DEFAULT '0.00' COMMENT '累计里程(km)',
  `streak_days` int DEFAULT '0' COMMENT '连续打卡天数',
  `last_checkin_date` date DEFAULT NULL COMMENT '最近打卡日期(算streak)',
  `update_time` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='玩家成长聚合(等级/里程/连续打卡)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `player_node_progress`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `player_node_progress` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '玩家会员ID',
  `activity_id` bigint NOT NULL COMMENT '活动ID(进度按活动隔离)',
  `topic_id` bigint DEFAULT NULL COMMENT '主题ID',
  `node_id` bigint NOT NULL COMMENT '节点ID',
  `status` tinyint DEFAULT '1' COMMENT '1已打卡',
  `checkin_time` datetime DEFAULT NULL COMMENT '打卡时间',
  `pic_url` varchar(255) DEFAULT NULL COMMENT '打卡照片(可选)',
  `review_status` tinyint NOT NULL DEFAULT '1' COMMENT '拍照审核态:0待审 1通过 2驳回',
  `longitude` varchar(32) DEFAULT NULL COMMENT '打卡经度(腾讯地图开通后回填)',
  `latitude` varchar(32) DEFAULT NULL COMMENT '打卡纬度',
  `del_flag` int DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_activity_node` (`member_id`,`activity_id`,`node_id`),
  UNIQUE KEY `uk_progress` (`member_id`,`topic_id`,`node_id`,`activity_id`),
  KEY `idx_node_progress_topic_member` (`topic_id`,`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='玩家节点打卡进度';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pms_gift`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_gift` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `gift_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '礼物名称',
  `pic_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '礼物图片（支持GIF）',
  `contents` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '礼物介绍',
  `point` int DEFAULT NULL COMMENT '所需积分',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态 1显示 2隐藏',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='众筹礼物表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pms_gift_change_audit`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_gift_change_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `gift_id` bigint NOT NULL COMMENT 'pms_gift 主键',
  `action` varchar(32) NOT NULL COMMENT 'CREATE/PRICE_CHANGE/STATUS_CHANGE/DETAIL_CHANGE',
  `old_point` bigint DEFAULT NULL COMMENT '变更前积分',
  `new_point` bigint DEFAULT NULL COMMENT '变更后积分',
  `old_status` tinyint DEFAULT NULL COMMENT '变更前状态 1可赠送 2停用',
  `new_status` tinyint DEFAULT NULL COMMENT '变更后状态 1可赠送 2停用',
  `reason` varchar(500) NOT NULL COMMENT '操作人填写的变更说明',
  `operator_username` varchar(64) NOT NULL COMMENT '后台登录操作人',
  `create_time` datetime NOT NULL COMMENT '审计写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_pms_gift_change_audit_gift_time` (`gift_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='礼物目录不可缺失变更审计';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pms_product`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint DEFAULT '0' COMMENT '商户ID',
  `product_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品名称',
  `pic` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '主图',
  `album_pics` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '画册图片，连产品图片限制为5张，以分号分割',
  `publish_status` int DEFAULT '0' COMMENT '上架状态：0 审核中  1上架  2下架',
  `sort_id` int DEFAULT '0' COMMENT '排序',
  `price` int DEFAULT NULL COMMENT '售价',
  `original_price` int DEFAULT NULL COMMENT '原价',
  `stock` int DEFAULT '0' COMMENT '库存',
  `unit` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '单位',
  `weight` decimal(10,2) DEFAULT NULL COMMENT '商品重量，默认为克',
  `detail_html` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '产品详情网页内容',
  `product_attr` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品销售属性，json格式',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1001 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='积分商城商品表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pms_product_like`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_product_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `product_id` bigint DEFAULT NULL COMMENT '产品ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='商品收藏表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pms_sku`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_sku` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sku_snapshot_id` bigint DEFAULT NULL,
  `product_id` bigint DEFAULT NULL,
  `out_sku_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'sku编码',
  `sku_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `sku_name_ar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  `price` decimal(10,2) NOT NULL COMMENT '销售价',
  `weight` decimal(10,2) DEFAULT NULL COMMENT '重量',
  `original_price` decimal(10,2) DEFAULT NULL COMMENT '原价',
  `pic` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '展示图片',
  `stock` int DEFAULT '0' COMMENT '库存',
  `sp_data` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品销售属性，json格式',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1002 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='sku信息';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `preset_route`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preset_route` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '路线ID',
  `city` varchar(64) DEFAULT NULL COMMENT '城市',
  `name` varchar(128) NOT NULL COMMENT '路线名称',
  `preview_img` varchar(500) DEFAULT NULL COMMENT '路线静态预览图URL',
  `route_geometry` text COMMENT '沿路坐标(JSON)',
  `total_distance` decimal(10,2) DEFAULT '0.00' COMMENT '总距离(km)',
  `total_time` bigint DEFAULT '0' COMMENT '总时长(分钟)',
  `default_category_ids` varchar(128) DEFAULT NULL COMMENT '默认分类ID(逗号分隔)',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序(小在前)',
  `status` char(1) NOT NULL DEFAULT '1' COMMENT '上下架 1上架 0下架',
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '删除标志 0存在 2删除',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_preset_route_status` (`status`),
  KEY `idx_preset_route_city` (`city`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='建议版城市路线预设';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `preset_route_node`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preset_route_node` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '站点ID',
  `route_id` bigint NOT NULL COMMENT '所属路线ID',
  `name` varchar(128) NOT NULL COMMENT '站点名称',
  `address` varchar(255) DEFAULT NULL COMMENT '站点地址',
  `longitude` varchar(32) DEFAULT NULL COMMENT '经度',
  `latitude` varchar(32) DEFAULT NULL COMMENT '纬度',
  `img_url` varchar(500) DEFAULT NULL COMMENT '站点配图URL',
  `node_time` bigint DEFAULT '0' COMMENT '建议停留时长(分钟)',
  `sort` int NOT NULL DEFAULT '0' COMMENT '站点顺序(小在前)',
  `play_template_id` bigint DEFAULT NULL COMMENT '绑定玩法模板ID(cms_template_library,可空)',
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '删除标志 0存在 2删除',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_preset_node_route` (`route_id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='建议版路线站点预设';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `preset_theme_style`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preset_theme_style` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '风格ID',
  `name` varchar(64) NOT NULL COMMENT '风格名称',
  `cover_img` varchar(500) DEFAULT NULL COMMENT '封面图URL',
  `tone_tokens` text COMMENT '一组 --cy- token 色值(JSON)',
  `default_title` varchar(128) DEFAULT NULL COMMENT '默认主题标题文案',
  `default_subtitle` varchar(255) DEFAULT NULL COMMENT '默认副标题文案',
  `default_desc` text COMMENT '默认简介/规则文案',
  `default_category_ids` varchar(128) DEFAULT NULL COMMENT '默认分类ID(逗号分隔)',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序(小在前)',
  `status` char(1) NOT NULL DEFAULT '1' COMMENT '上下架 1上架 0下架',
  `del_flag` int NOT NULL DEFAULT '0' COMMENT '删除标志 0存在 2删除',
  `create_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_preset_style_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='建议版主题风格预设';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `product_role`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic_id` bigint NOT NULL COMMENT '主题id(cms_topic.id)',
  `slot` varchar(16) NOT NULL COMMENT '角色槽:CONTENT内容方/ORGANIZER组织方/SUPPLY供给方/DISTRIBUTION分销方。CONTENT与ORGANIZER各仅1个生效,SUPPLY与DISTRIBUTION可多行',
  `entity_type` varchar(16) NOT NULL COMMENT '主体类型:PLATFORM平台/MEMBER玩家/CLUB俱乐部/MERCHANT商家。⚠️PLATFORM是③-自营(首轮标的D12)的唯一表达方式,原枚举没有它',
  `entity_id` bigint DEFAULT NULL COMMENT '主体id。entity_type=PLATFORM时为NULL(平台不是某个member);其余为对应的memberId/clubId',
  `revenue_share` decimal(10,4) DEFAULT NULL COMMENT '该槽的分成比例。③票款归CONTENT槽持有者(D8);本次不落分账逻辑,列先留着由P0-46/P0-50填',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1生效 0失效。⚠️旧承接标失效不删行(要留痕),所以唯一键必须只对生效行生效',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `singleton_key` varchar(32) GENERATED ALWAYS AS ((case when ((`status` = 1) and (`slot` in (_utf8mb4'CONTENT',_utf8mb4'ORGANIZER'))) then concat(`topic_id`,_utf8mb4'-',`slot`) end)) VIRTUAL COMMENT '生效单例槽的唯一键(生成列)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_role_singleton` (`singleton_key`),
  KEY `idx_role_topic_slot` (`topic_id`,`slot`,`status`),
  KEY `idx_role_entity` (`entity_type`,`entity_id`,`status`),
  CONSTRAINT `ck_role_active_content_owner` CHECK (((`slot` <> _utf8mb4'CONTENT') or (`status` <> 1) or (`entity_type` in (_utf8mb4'PLATFORM',_utf8mb4'MEMBER',_utf8mb4'CLUB')))),
  CONSTRAINT `ck_role_entity_id` CHECK ((((`entity_type` = _utf8mb4'PLATFORM') and (`entity_id` is null)) or ((`entity_type` <> _utf8mb4'PLATFORM') and (`entity_id` is not null)))),
  CONSTRAINT `ck_role_entity_type` CHECK ((`entity_type` in (_utf8mb4'PLATFORM',_utf8mb4'MEMBER',_utf8mb4'CLUB',_utf8mb4'MERCHANT'))),
  CONSTRAINT `ck_role_slot` CHECK ((`slot` in (_utf8mb4'CONTENT',_utf8mb4'ORGANIZER',_utf8mb4'SUPPLY',_utf8mb4'DISTRIBUTION')))
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品角色槽(D6):角色声明挂在这个主题上,不挂在主体的全局身份上';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_blob_triggers`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_blob_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `blob_data` blob COMMENT '存放持久化Trigger对象',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`) USING BTREE,
  CONSTRAINT `QRTZ_BLOB_TRIGGERS_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='Blob类型的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_calendars`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_calendars` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `calendar_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '日历名称',
  `calendar` blob NOT NULL COMMENT '存放持久化calendar对象',
  PRIMARY KEY (`sched_name`,`calendar_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='日历信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_cron_triggers`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_cron_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `cron_expression` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'cron表达式',
  `time_zone_id` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '时区',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`) USING BTREE,
  CONSTRAINT `QRTZ_CRON_TRIGGERS_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='Cron类型的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_fired_triggers`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_fired_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `entry_id` varchar(95) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度器实例id',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `instance_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度器实例名',
  `fired_time` bigint NOT NULL COMMENT '触发的时间',
  `sched_time` bigint NOT NULL COMMENT '定时器制定的时间',
  `priority` int NOT NULL COMMENT '优先级',
  `state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态',
  `job_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '任务名称',
  `job_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '任务组名',
  `is_nonconcurrent` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否并发',
  `requests_recovery` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否接受恢复执行',
  PRIMARY KEY (`sched_name`,`entry_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='已触发的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_job_details`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_job_details` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `job_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务名称',
  `job_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务组名',
  `description` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '相关介绍',
  `job_class_name` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '执行任务类名称',
  `is_durable` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否持久化',
  `is_nonconcurrent` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否并发',
  `is_update_data` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否更新数据',
  `requests_recovery` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '是否接受恢复执行',
  `job_data` blob COMMENT '存放持久化job对象',
  PRIMARY KEY (`sched_name`,`job_name`,`job_group`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='任务详细信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_locks`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_locks` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `lock_name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '悲观锁名称',
  PRIMARY KEY (`sched_name`,`lock_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='存储的悲观锁信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_paused_trigger_grps`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_paused_trigger_grps` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  PRIMARY KEY (`sched_name`,`trigger_group`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='暂停的触发器表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_scheduler_state`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_scheduler_state` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `instance_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '实例名称',
  `last_checkin_time` bigint NOT NULL COMMENT '上次检查时间',
  `checkin_interval` bigint NOT NULL COMMENT '检查间隔时间',
  PRIMARY KEY (`sched_name`,`instance_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='调度器状态表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_simple_triggers`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_simple_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `repeat_count` bigint NOT NULL COMMENT '重复的次数统计',
  `repeat_interval` bigint NOT NULL COMMENT '重复的间隔时间',
  `times_triggered` bigint NOT NULL COMMENT '已经触发的次数',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`) USING BTREE,
  CONSTRAINT `QRTZ_SIMPLE_TRIGGERS_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='简单触发器的信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_simprop_triggers`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_simprop_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键',
  `str_prop_1` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'String类型的trigger的第一个参数',
  `str_prop_2` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'String类型的trigger的第二个参数',
  `str_prop_3` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'String类型的trigger的第三个参数',
  `int_prop_1` int DEFAULT NULL COMMENT 'int类型的trigger的第一个参数',
  `int_prop_2` int DEFAULT NULL COMMENT 'int类型的trigger的第二个参数',
  `long_prop_1` bigint DEFAULT NULL COMMENT 'long类型的trigger的第一个参数',
  `long_prop_2` bigint DEFAULT NULL COMMENT 'long类型的trigger的第二个参数',
  `dec_prop_1` decimal(13,4) DEFAULT NULL COMMENT 'decimal类型的trigger的第一个参数',
  `dec_prop_2` decimal(13,4) DEFAULT NULL COMMENT 'decimal类型的trigger的第二个参数',
  `bool_prop_1` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Boolean类型的trigger的第一个参数',
  `bool_prop_2` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Boolean类型的trigger的第二个参数',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`) USING BTREE,
  CONSTRAINT `QRTZ_SIMPROP_TRIGGERS_ibfk_1` FOREIGN KEY (`sched_name`, `trigger_name`, `trigger_group`) REFERENCES `qrtz_triggers` (`sched_name`, `trigger_name`, `trigger_group`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='同步机制的行锁表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_triggers`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qrtz_triggers` (
  `sched_name` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调度名称',
  `trigger_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器的名字',
  `trigger_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器所属组的名字',
  `job_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_job_details表job_name的外键',
  `job_group` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'qrtz_job_details表job_group的外键',
  `description` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '相关介绍',
  `next_fire_time` bigint DEFAULT NULL COMMENT '上一次触发时间（毫秒）',
  `prev_fire_time` bigint DEFAULT NULL COMMENT '下一次触发时间（默认为-1表示不触发）',
  `priority` int DEFAULT NULL COMMENT '优先级',
  `trigger_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器状态',
  `trigger_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '触发器的类型',
  `start_time` bigint NOT NULL COMMENT '开始时间',
  `end_time` bigint DEFAULT NULL COMMENT '结束时间',
  `calendar_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '日程表名称',
  `misfire_instr` smallint DEFAULT NULL COMMENT '补偿执行的策略',
  `job_data` blob COMMENT '存放持久化job对象',
  PRIMARY KEY (`sched_name`,`trigger_name`,`trigger_group`) USING BTREE,
  KEY `sched_name` (`sched_name`,`job_name`,`job_group`) USING BTREE,
  CONSTRAINT `QRTZ_TRIGGERS_ibfk_1` FOREIGN KEY (`sched_name`, `job_name`, `job_group`) REFERENCES `qrtz_job_details` (`sched_name`, `job_name`, `job_group`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='触发器详细信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `recommendation_score_daily`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `recommendation_score_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `candidate_type` varchar(40) NOT NULL COMMENT '候选类型',
  `candidate_id` bigint NOT NULL COMMENT '候选ID',
  `score` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '推荐分',
  `reason_codes` varchar(500) DEFAULT NULL COMMENT '推荐原因编码',
  `rank_position` int DEFAULT NULL COMMENT '排序位置',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reco_daily_candidate` (`stat_date`,`member_id`,`candidate_type`,`candidate_id`),
  KEY `idx_reco_daily_rank` (`member_id`,`candidate_type`,`stat_date`,`rank_position`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3每日推荐评分';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `redemption`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `redemption` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `registration_id` bigint NOT NULL COMMENT '报名id(=票)',
  `chapter_id` bigint DEFAULT NULL COMMENT '章节id;NULL/0=① 整单核销(无章节维度)。③ 必须非空,由核销服务保证',
  `topic_id` bigint NOT NULL COMMENT '主题id',
  `merchant_id` bigint NOT NULL COMMENT '核销商家memberId',
  `offer_id` bigint DEFAULT NULL COMMENT '命中的merchant_chapter_offer.id;NULL=① 无供给概念',
  `operator_id` bigint NOT NULL COMMENT '实际操作人memberId(店员≠商家主体时可区分)',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE生效 / REVERSED已撤销。⚠️与active_key的CASE是双胞胎,加值必改CASE',
  `idempotency_key` varchar(128) NOT NULL COMMENT '请求级幂等键 redeem:{reg}:{chapter}:{merchant} / reverse:{redemptionId}',
  `reversed_at` datetime DEFAULT NULL COMMENT '撤销时间',
  `reverse_reason` varchar(255) DEFAULT NULL COMMENT '撤销原因(误扫/运营撤销/权益补发)',
  `create_time` datetime DEFAULT NULL,
  `active_key` varchar(64) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'ACTIVE') then concat(`registration_id`,_utf8mb4'-',ifnull(nullif(`chapter_id`,0),_utf8mb4'ORDER')) end)) VIRTUAL COMMENT '生效核销的唯一键;撤销后为NULL自动释放',
  `active_idem_key` varchar(128) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'ACTIVE') then `idempotency_key` end)) VIRTUAL COMMENT '生效核销的请求幂等键;撤销后为NULL自动释放,让同一商家可改正重扫',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redemption_active` (`active_key`),
  UNIQUE KEY `uk_redemption_idem_active` (`active_idem_key`),
  KEY `idx_redemption_reg` (`registration_id`),
  KEY `idx_redemption_merchant` (`merchant_id`,`create_time`),
  KEY `idx_redemption_chapter` (`chapter_id`,`status`),
  CONSTRAINT `ck_redemption_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'REVERSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核销流水(不可变事件+反向撤销;唯一键只对ACTIVE生效)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `refund_application`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refund_application` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no` varchar(64) DEFAULT NULL COMMENT '退款单号',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 registration/order/coupon',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `owner_type` bigint DEFAULT NULL COMMENT '拥有类型 1主题 2活动',
  `owner_id` bigint DEFAULT NULL COMMENT '主题/活动ID',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `merchant_id` bigint DEFAULT NULL COMMENT '商家ID',
  `paid_amount` decimal(18,2) DEFAULT '0.00' COMMENT '支付金额',
  `refund_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '退款金额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0待审核 1通过 2驳回',
  `reason` varchar(500) DEFAULT NULL COMMENT '申请原因',
  `reject_reason` varchar(500) DEFAULT NULL COMMENT '驳回/审核原因',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `adjustment_created` tinyint NOT NULL DEFAULT '0' COMMENT '是否已生成冲销',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '幂等键',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `payout_status` tinyint NOT NULL DEFAULT '0' COMMENT '打款结果:0处理中 1原路成功 2原路失败待人工 3人工已退待复核 4双人复核完成',
  `payout_submission_state` varchar(32) NOT NULL DEFAULT 'LEGACY_RECONCILE' COMMENT '原路退款提交态:历史待核对/未发起/提交中/处理中/终态',
  `payout_method` varchar(32) DEFAULT NULL COMMENT '人工退款方式',
  `payout_receipt_url` varchar(512) DEFAULT NULL COMMENT '人工退款回执凭证URL',
  `payout_handler_id` bigint DEFAULT NULL COMMENT '人工处理人ID',
  `payout_handled_at` datetime DEFAULT NULL COMMENT '人工处理时间',
  `payout_reviewer2_id` bigint DEFAULT NULL COMMENT '双人复核第二复核人ID',
  `payout_reviewed2_at` datetime DEFAULT NULL COMMENT '双人复核时间',
  `payout_remark` varchar(500) DEFAULT NULL COMMENT '人工处理备注/原路失败原因',
  `payout_lease_token` varchar(64) DEFAULT NULL COMMENT '退款派发 fencing token',
  `payout_lease_owner` bigint DEFAULT NULL COMMENT '人工打款租约 owner',
  `payout_lease_expires_at` datetime(3) DEFAULT NULL COMMENT '人工打款租约到期时间',
  `created_by_user_id` bigint DEFAULT NULL COMMENT '后台建单人sys_user_id(maker≠checker用;C端/系统发起为NULL)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_idempotency` (`idempotency_key`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_source` (`source_type`,`source_id`),
  KEY `idx_merchant_status` (`merchant_id`,`status`),
  KEY `idx_refund_payout_status` (`payout_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3退款申请';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `registration_entitlement`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `registration_entitlement` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `registration_id` bigint NOT NULL COMMENT '报名id(=票)',
  `chapter_id` bigint NOT NULL COMMENT '必选章节id',
  `topic_id` bigint NOT NULL COMMENT '主题id(冗余,便于按主题聚合)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0应得(已售未核销,占容量) 1已得(已核销) 2失效(退款/取消,释放容量)',
  `invalid_reason` varchar(32) DEFAULT NULL COMMENT '失效原因 REFUND/ORGANIZER_CANCEL/FULFILLMENT_GAP/MANUAL(status=2时应填)',
  `redeemed_at` datetime DEFAULT NULL COMMENT '核销时间',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlement` (`registration_id`,`chapter_id`),
  KEY `idx_entitlement_chapter_status` (`chapter_id`,`status`),
  KEY `idx_entitlement_topic` (`topic_id`),
  CONSTRAINT `ck_entitlement_status` CHECK ((`status` in (0,1,2)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每票每必选章的权益应得/已得/失效(容量预占真源)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reward_drop_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reward_drop_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL,
  `topic_id` bigint DEFAULT NULL,
  `node_id` bigint DEFAULT NULL,
  `activity_id` bigint DEFAULT NULL,
  `scene_key` varchar(64) NOT NULL DEFAULT 'topic_node' COMMENT '掉落发生场景',
  `drop_type` tinyint DEFAULT NULL,
  `param_json` varchar(200) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_drop_scene` (`member_id`,`scene_key`,`topic_id`,`node_id`,`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M6 掉落流水(uk 保证每节点最多掉一次)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reward_drop_rule`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reward_drop_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scope` tinyint DEFAULT NULL COMMENT '0=全局 1=指定主题 2=指定场景',
  `topic_id` bigint DEFAULT NULL COMMENT 'scope=1 时指定主题',
  `scene_key` varchar(64) DEFAULT NULL COMMENT 'scope=2 场景键，如 roam_merchant_poi',
  `drop_type` tinyint DEFAULT NULL COMMENT '1=XP倍率 2=徽章碎片 3=商家彩蛋券',
  `weight` int DEFAULT NULL COMMENT '按权重抽取',
  `param_json` varchar(200) DEFAULT NULL COMMENT '类型参数:{"mult":2} / {"badgeCode":"x"} / {"couponTemplateId":1}',
  `status` tinyint DEFAULT NULL COMMENT '1=启用 0=停用',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_scope_topic` (`scope`,`topic_id`,`status`),
  KEY `idx_scope_scene` (`scope`,`scene_key`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M6 随机掉落规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reward_pending`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reward_pending` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT '玩家',
  `reward_type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'xp / coupon / badge / drop',
  `ref_id` bigint DEFAULT NULL COMMENT '关联 node/topic/coupon id',
  `amount` int DEFAULT NULL COMMENT 'xp 额度 或 券模板 id',
  `reason` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '发放场景(节点探索/通关奖励/漫游...)',
  `status` tinyint DEFAULT '0' COMMENT '0待补 1已补 2放弃',
  `retry_count` int DEFAULT '0' COMMENT '已重试次数',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发奖待补发(C3:发奖失败落表,定时 job 补发,杜绝静默丢奖)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_ai_content`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_ai_content` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `content_type` varchar(32) NOT NULL COMMENT 'ROUTE_STORY / POI_MEANING',
  `content_key` varchar(64) NOT NULL COMMENT '运营稳定键；地点意义由 poi-{poiId} 固定生成',
  `poi_id` bigint DEFAULT NULL COMMENT '仅 POI_MEANING 绑定 roam_poi.id',
  `content_text` varchar(80) NOT NULL COMMENT '审核短文本；路线故事仅允许白名单指标占位符',
  `audit_status` tinyint NOT NULL DEFAULT '0' COMMENT '0待审 1通过 2驳回',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '0停用 1启用',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roam_ai_content_type_key` (`content_type`,`content_key`),
  KEY `idx_roam_ai_content_poi_audit` (`poi_id`,`audit_status`,`enabled`),
  KEY `idx_roam_ai_content_route_audit` (`content_type`,`audit_status`,`enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='漫游无形陪伴审核内容';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_fog_tile`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_fog_tile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `session_id` bigint DEFAULT NULL,
  `geohash7` varchar(12) NOT NULL COMMENT '~150m 精度网格键',
  `revealed_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_tile` (`member_id`,`geohash7`),
  KEY `idx_member_day` (`member_id`,`revealed_at`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB AUTO_INCREMENT=55 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P2 已点亮迷雾格(版图,uk 幂等)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_poi`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_poi` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(80) NOT NULL,
  `type` tinyint DEFAULT '1' COMMENT '1 地标/公园 2 商户招牌',
  `lat` decimal(10,6) NOT NULL,
  `lng` decimal(10,6) NOT NULL,
  `radius_m` int DEFAULT '80' COMMENT '触发半径(米)',
  `address` varchar(255) DEFAULT NULL COMMENT '商家投放表单地址',
  `description` varchar(255) DEFAULT NULL,
  `xp` int DEFAULT '20' COMMENT '发现奖励探索值',
  `coupon_template_id` bigint DEFAULT NULL COMMENT '商户 POI 关联券模板(可空)',
  `city_code` varchar(20) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1 上架 0 下架',
  `audit_status` tinyint DEFAULT NULL COMMENT '商家申请审核态:0待审 1通过 2驳回 3审核中;NULL非申请',
  `application_type` tinyint DEFAULT NULL COMMENT '商家申请类型:1据点投放 2节点认领',
  `applicant_merchant_id` bigint DEFAULT NULL COMMENT '待审申请人:mms_merchant.id;不得当作生效owner',
  `audit_reason` varchar(200) DEFAULT NULL COMMENT '驳回原因',
  `create_time` datetime DEFAULT NULL,
  `merchant_id` bigint DEFAULT NULL COMMENT '商户据点:mms_merchant.id',
  `template_id` bigint DEFAULT NULL COMMENT '商户据点:绑定互动模板 cms_member_template.id',
  `node_level` tinyint DEFAULT '1' COMMENT '1普通 2精选 3官方合作 4主题路线',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签,逗号分隔',
  `cover_img` varchar(500) DEFAULT NULL COMMENT '地图卡封面',
  `sort` int DEFAULT '0' COMMENT '运营排序',
  PRIMARY KEY (`id`),
  KEY `idx_city_status` (`city_code`,`status`),
  KEY `idx_geo` (`lat`,`lng`),
  KEY `idx_merchant` (`merchant_id`),
  KEY `idx_roam_apply_review` (`application_type`,`audit_status`),
  KEY `idx_roam_apply_applicant` (`applicant_merchant_id`,`audit_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P2 漫游 POI 兴趣点';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_poi_found`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_poi_found` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `poi_id` bigint NOT NULL,
  `session_id` bigint DEFAULT NULL,
  `found_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_poi` (`member_id`,`poi_id`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P2 POI 发现记录(uk 幂等,防重复发 XP)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_poi_reward_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_poi_reward_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `poi_id` bigint NOT NULL,
  `member_id` bigint NOT NULL COMMENT '触发的玩家',
  `coupon_id` bigint DEFAULT NULL COMMENT '券模板 sms_coupon.id',
  `coupon_history_id` bigint DEFAULT NULL COMMENT '发放的券包记录 sms_coupon_history.id',
  `trigger_lat` decimal(10,6) DEFAULT NULL COMMENT '触发点(审计)',
  `trigger_lng` decimal(10,6) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1已领 2已核销',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_poi_member` (`poi_id`,`member_id`),
  KEY `idx_member` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='据点互动领券记录(uk 防重发券)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_session`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `recall_sent_at` datetime DEFAULT NULL COMMENT '召回消息发送时间',
  `recall_attempts` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '召回尝试次数',
  `recall_last_attempt_at` datetime DEFAULT NULL COMMENT '最近召回尝试时间',
  `distance_m` int DEFAULT '0' COMMENT '本次里程(米)',
  `tiles_revealed` int DEFAULT '0' COMMENT '本次新点亮格子数(结算写入)',
  `pois_found` int DEFAULT '0' COMMENT '本次新发现 POI 数',
  `xp_awarded` int DEFAULT '0' COMMENT '本次实发探索值(日限后)',
  `status` tinyint DEFAULT '0' COMMENT '0 进行中 1 已结算',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member` (`member_id`),
  KEY `idx_roam_session_recall` (`status`,`recall_sent_at`,`finished_at`,`recall_attempts`,`recall_last_attempt_at`)
) ENGINE=InnoDB AUTO_INCREMENT=30 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P2 漫游会话';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_shop_visit`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_shop_visit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `session_id` bigint NOT NULL COMMENT 'roam_session.id',
  `source_type` tinyint NOT NULL COMMENT '1=roam_poi 2=cms_registration_merchant',
  `source_id` bigint NOT NULL COMMENT '来源表主键',
  `shop_name` varchar(128) NOT NULL COMMENT '服务端从来源表读的真名,不采信客户端',
  `visited_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_source` (`session_id`,`source_type`,`source_id`),
  KEY `idx_member_time` (`member_id`,`visited_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='漫游会话内探店记录(任意真商家:roam据点/报名商家)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roam_stamp`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roam_stamp` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT '归属用户',
  `pic_url` varchar(500) NOT NULL COMMENT 'OSS 原图 URL',
  `caption` varchar(140) DEFAULT NULL COMMENT '用户自填说明',
  `kind` tinyint NOT NULL DEFAULT '0' COMMENT '0=普通(自由拍),1=盖销(P1)',
  `geohash7` char(7) DEFAULT NULL COMMENT '盖销专用,与 roam_fog_tile 同精度,普通票为 NULL',
  `lat` decimal(10,6) DEFAULT NULL,
  `lng` decimal(10,6) DEFAULT NULL,
  `city_code` varchar(16) DEFAULT NULL,
  `check_state` tinyint NOT NULL DEFAULT '0' COMMENT '0=未送检(含机审关闭与送检失败),1=机审通过,2=违规下架',
  `check_trace` varchar(64) DEFAULT NULL COMMENT 'checkMediaAsync 的 trace_id',
  `idem_key` varchar(64) DEFAULT NULL COMMENT '客户端幂等键,防双击与重试重复入册,不送则为 NULL',
  `del_flag` tinyint NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_geohash` (`member_id`,`geohash7`),
  UNIQUE KEY `uk_member_idem` (`member_id`,`idem_key`),
  KEY `idx_member_time` (`member_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='漫游集邮 UGC 邮票';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `settlement_batch`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_no` varchar(64) NOT NULL COMMENT '批次号',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `period_start` datetime DEFAULT NULL COMMENT '结算周期开始',
  `period_end` datetime DEFAULT NULL COMMENT '结算周期结束',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '结算总额',
  `detail_count` bigint NOT NULL DEFAULT '0' COMMENT '明细数',
  `success_count` bigint NOT NULL DEFAULT '0' COMMENT '成功数',
  `failed_count` bigint NOT NULL DEFAULT '0' COMMENT '失败数',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0待审核 1已通过 2已失败',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人ID',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`),
  UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
  KEY `idx_merchant_status` (`merchant_id`,`status`),
  KEY `idx_period` (`period_start`,`period_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3结算批次';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `settlement_claim`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_claim` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `commercial_context_type` varchar(32) NOT NULL COMMENT 'TOPIC_EDITION/ACTIVITY',
  `commercial_context_id` bigint NOT NULL COMMENT '直接 owner id',
  `fulfillment_type` varchar(32) NOT NULL COMMENT 'REGISTRATION/REDEMPTION',
  `fulfillment_id` bigint NOT NULL COMMENT '直接履约事实 id',
  `beneficiary_role` varchar(32) NOT NULL COMMENT 'MERCHANT/CLUB/PLATFORM/CREATOR',
  `beneficiary_id` bigint NOT NULL COMMENT '按 role 规范化后的 id',
  `service_unit` varchar(48) NOT NULL COMMENT 'CHAPTER_SUPPLY/MERCHANT_PARTICIPATION/ORGANIZER_LEAD',
  `settlement_route` varchar(32) NOT NULL COMMENT 'NONE/CHAPTER_OFFER/COOP_ORDER/REGISTRATION_SPLIT',
  `source_contract_type` varchar(48) NOT NULL COMMENT 'MERCHANT_CHAPTER_OFFER/COOP_ORDER/DIRECT_REGISTRATION',
  `source_contract_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_claim_key` (`commercial_context_type`,`commercial_context_id`,`fulfillment_type`,`fulfillment_id`,`beneficiary_role`,`beneficiary_id`,`service_unit`),
  KEY `idx_claim_source` (`source_contract_type`,`source_contract_id`),
  KEY `idx_claim_fulfillment` (`fulfillment_type`,`fulfillment_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合同四:原子经济请求唯一归属';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `settlement_detail`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `claim_id` bigint DEFAULT NULL COMMENT '存量待历史裁决;新写必填',
  `entry_kind` varchar(24) DEFAULT NULL COMMENT 'ORIGIN/ADJUSTMENT',
  `event_key` varchar(128) DEFAULT NULL COMMENT 'ORIGIN/不可变调整事件',
  `batch_id` bigint NOT NULL COMMENT '批次ID',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 order/coupon/activity/topic',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `receiver_type` varchar(32) NOT NULL COMMENT '收款方类型 merchant/creator/platform',
  `receiver_id` bigint NOT NULL COMMENT '收款方ID',
  `gross_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '原始金额',
  `commission_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '佣金金额',
  `settlement_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '结算金额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0待处理 1成功 2失败',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因',
  `idempotency_key` varchar(160) NOT NULL COMMENT '明细幂等键',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_detail_idempotency` (`idempotency_key`),
  UNIQUE KEY `uk_source_receiver` (`source_type`,`source_id`,`receiver_type`,`receiver_id`),
  UNIQUE KEY `uk_settlement_detail_claim_event` (`claim_id`,`entry_kind`,`event_key`),
  KEY `idx_batch` (`batch_id`),
  KEY `idx_merchant_status` (`merchant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3结算明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `settlement_detail_bak_20260811_w`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_detail_bak_20260811_w` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `batch_id` bigint NOT NULL COMMENT '批次ID',
  `merchant_id` bigint NOT NULL COMMENT '商家ID',
  `source_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '来源类型 order/coupon/activity/topic',
  `source_id` bigint NOT NULL COMMENT '来源ID',
  `receiver_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收款方类型 merchant/creator/platform',
  `receiver_id` bigint NOT NULL COMMENT '收款方ID',
  `gross_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '原始金额',
  `commission_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '佣金金额',
  `settlement_amount` decimal(18,2) NOT NULL DEFAULT '0.00' COMMENT '结算金额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0待处理 1成功 2失败',
  `fail_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '失败原因',
  `idempotency_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '明细幂等键',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `settlement_rule`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `merchant_id` bigint NOT NULL DEFAULT '0' COMMENT '商家ID,0为平台默认',
  `biz_type` varchar(32) NOT NULL COMMENT '业务类型 activity/topic/coupon/order',
  `rule_name` varchar(100) DEFAULT NULL COMMENT '规则名称',
  `settlement_cycle` varchar(32) NOT NULL DEFAULT 'monthly' COMMENT '结算周期 daily/weekly/monthly/manual',
  `platform_commission_rate` decimal(10,4) NOT NULL DEFAULT '0.1000' COMMENT '平台抽成比例',
  `merchant_rate` decimal(10,4) NOT NULL DEFAULT '0.9000' COMMENT '商家比例',
  `creator_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '创作者比例',
  `inviter_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '邀请人比例',
  `min_settlement_amount` decimal(18,2) NOT NULL DEFAULT '100.00' COMMENT '最低结算金额',
  `refund_deadline_hours` int NOT NULL DEFAULT '24' COMMENT '开始前多少小时可退',
  `allow_refund_after_verified` tinyint NOT NULL DEFAULT '0' COMMENT '已核销是否可退',
  `auto_approve_refund` tinyint NOT NULL DEFAULT '0' COMMENT '是否自动通过退款',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 1启用 0停用',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `guarantee_base_rate` decimal(10,4) NOT NULL DEFAULT '0.0500' COMMENT '发起人保底基础率(原PricingServiceImpl硬编码0.05)。单位:小数',
  `guarantee_step_rate` decimal(10,4) NOT NULL DEFAULT '0.0200' COMMENT '每个已核销商家追加的保底率(原硬编码0.02)。单位:小数',
  `guarantee_max_merchant` tinyint NOT NULL DEFAULT '5' COMMENT '保底率封顶的商家家数(原硬编码5,即保底率封顶base+step×5)',
  `unit_price` decimal(18,2) DEFAULT NULL COMMENT '商业化后台配置单价;NULL=不可自助售卖',
  `entitlement_max_usage` int DEFAULT NULL COMMENT '购买一次授予的次数',
  `entitlement_days` int DEFAULT NULL COMMENT '购买一次授予的有效天数',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_scope` (`merchant_id`,`biz_type`,`status`,`del_flag`),
  KEY `idx_biz_type` (`biz_type`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P3结算规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `settlement_rule_bak_20260805`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_rule_bak_20260805` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `merchant_id` bigint NOT NULL DEFAULT '0' COMMENT '商家ID,0为平台默认',
  `biz_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '业务类型 activity/topic/coupon/order',
  `rule_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '规则名称',
  `settlement_cycle` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'monthly' COMMENT '结算周期 daily/weekly/monthly/manual',
  `platform_commission_rate` decimal(10,4) NOT NULL DEFAULT '0.1000' COMMENT '平台抽成比例',
  `merchant_rate` decimal(10,4) NOT NULL DEFAULT '0.9000' COMMENT '商家比例',
  `creator_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '创作者比例',
  `inviter_rate` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '邀请人比例',
  `min_settlement_amount` decimal(18,2) NOT NULL DEFAULT '100.00' COMMENT '最低结算金额',
  `refund_deadline_hours` int NOT NULL DEFAULT '24' COMMENT '开始前多少小时可退',
  `allow_refund_after_verified` tinyint NOT NULL DEFAULT '0' COMMENT '已核销是否可退',
  `auto_approve_refund` tinyint NOT NULL DEFAULT '0' COMMENT '是否自动通过退款',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 1启用 0停用',
  `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '删除标记',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `guarantee_base_rate` decimal(10,4) NOT NULL DEFAULT '0.0500' COMMENT '发起人保底基础率(原PricingServiceImpl硬编码0.05)。单位:小数',
  `guarantee_step_rate` decimal(10,4) NOT NULL DEFAULT '0.0200' COMMENT '每个已核销商家追加的保底率(原硬编码0.02)。单位:小数',
  `guarantee_max_merchant` tinyint NOT NULL DEFAULT '5' COMMENT '保底率封顶的商家家数(原硬编码5,即保底率封顶base+step×5)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sms_coupon`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sms_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '优惠券名称',
  `coupon_type` int DEFAULT NULL COMMENT '优惠券类型：0礼品券 1、9折券  2、8折券',
  `platform` int DEFAULT NULL COMMENT '使用平台：0->全部；1->移动；2->PC',
  `amount` decimal(10,2) DEFAULT NULL COMMENT '金额 / 折扣系数',
  `min_point` decimal(10,2) DEFAULT NULL COMMENT '使用门槛（0.00表示无门槛）',
  `start_time` datetime DEFAULT NULL COMMENT '优惠券可使用开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '优惠券可使用结束时间',
  `use_type` int DEFAULT NULL COMMENT '使用类型：0->全场通用；1->指定分类；2->指定商品',
  `publish_count` int DEFAULT NULL COMMENT '发行数量',
  `use_count` int DEFAULT NULL COMMENT '已使用数量',
  `receive_count` int DEFAULT NULL COMMENT '领取数量',
  `enable_time` datetime DEFAULT NULL COMMENT '可以领取的日期',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '优惠码',
  `scope_type` int DEFAULT '0' COMMENT '适用范围：0->平台优惠券；1->商家优惠券',
  `member_id` bigint DEFAULT '0' COMMENT '发布用户ID',
  `merchant_id` bigint DEFAULT NULL COMMENT '如果scope_type=1（商家券），此字段记录所属商家ID',
  `status` int DEFAULT NULL COMMENT '状态：0->未开始；1->进行中；2->已结束；3->手动失效',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '说明',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `publish_minute` varchar(16) COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (date_format(`create_time`,_utf8mb4'%Y%m%d%H%i')) STORED COMMENT 'M0-4 发券分钟(生成列),用于发券防重唯一键',
  `per_member_limit` int DEFAULT NULL COMMENT '每人限领张数(NULL或<=0不限,>0每member最多领N张)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_coupon_dedup` (`member_id`,`coupon_type`,`publish_minute`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='优惠券表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sms_coupon_history`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sms_coupon_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coupon_id` bigint NOT NULL COMMENT '优惠券ID',
  `member_id` bigint NOT NULL COMMENT '用户ID',
  `coupon_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '优惠券码（唯一标识）',
  `qrcode_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核销二维码URL',
  `get_type` int DEFAULT '0' COMMENT '获取方式：0->后台赠送；1->主动领取',
  `get_time` datetime DEFAULT NULL COMMENT '领取时间',
  `use_status` int DEFAULT '0' COMMENT '使用状态：0->未使用；1->已使用；2->已过期',
  `use_time` datetime DEFAULT NULL COMMENT '使用时间',
  `order_id` bigint DEFAULT NULL COMMENT '订单ID',
  `order_sn` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '订单编号',
  `start_time` datetime DEFAULT NULL COMMENT '有效开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '有效结束时间',
  `note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='优惠券领取表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sms_hot_search`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sms_hot_search` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `keyword` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '搜索关键词',
  `search_count` int NOT NULL DEFAULT '0' COMMENT '搜索次数',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态（0-禁用，1-启用）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_keyword` (`keyword`) USING BTREE,
  KEY `idx_count` (`search_count`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='热门搜索词表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_banner`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_banner` (
  `id` int unsigned NOT NULL AUTO_INCREMENT,
  `show_type` tinyint(1) DEFAULT '0' COMMENT '显示类型 1首页头部轮播  2其它',
  `link_type` tinyint(1) DEFAULT '0' COMMENT '点击类型   0无事件 1商家报名 2游戏投票',
  `data_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT 'H5链接/活动ID/主题ID',
  `pic_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '图片地址',
  `contents` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '单页详情内容',
  `sort_id` tinyint(1) DEFAULT '0' COMMENT '排序',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态 1显示 2隐藏',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='轮播图';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_category`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `type` int DEFAULT NULL COMMENT '类型1、主题  2、活动  3、个人偏好 4、模版 5 品牌偏好',
  `parent_id` bigint DEFAULT NULL COMMENT '上级分类的编号：0表示一级分类',
  `category_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '分类名称',
  `level` int DEFAULT NULL COMMENT '分类级别：0->1级；1->2级',
  `sort_id` int DEFAULT NULL COMMENT '排序',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'logo 图片',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态 1显示 2隐藏',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='分类表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_config`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_config` (
  `config_id` int NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `config_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键名',
  `config_value` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '参数键值',
  `config_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`config_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=113 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='参数配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_dept`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dept` (
  `dept_id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门id',
  `parent_id` bigint DEFAULT '0' COMMENT '父部门id',
  `ancestors` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `leader` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=200 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='部门表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_dict_data`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_data` (
  `dict_code` bigint NOT NULL AUTO_INCREMENT COMMENT '字典编码',
  `dict_sort` int DEFAULT '0' COMMENT '字典排序',
  `dict_label` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典标签',
  `dict_value` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `css_class` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
  `list_class` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '表格回显样式',
  `is_default` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_code`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=174 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='字典数据表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_dict_type`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_type` (
  `dict_id` bigint NOT NULL AUTO_INCREMENT COMMENT '字典主键',
  `dict_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典名称',
  `dict_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '字典类型',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_id`) USING BTREE,
  UNIQUE KEY `dict_type` (`dict_type`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=125 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='字典类型表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_job`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_job` (
  `job_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `job_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '任务名称',
  `job_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'DEFAULT' COMMENT '任务组名',
  `invoke_target` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调用目标字符串',
  `cron_expression` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT 'cron执行表达式',
  `misfire_policy` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '3' COMMENT '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  `concurrent` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '1' COMMENT '是否并发执行（0允许 1禁止）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '状态（0正常 1暂停）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '备注信息',
  PRIMARY KEY (`job_id`,`job_name`,`job_group`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=124 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='定时任务调度表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_job_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_job_log` (
  `job_log_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务日志ID',
  `job_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务组名',
  `invoke_target` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调用目标字符串',
  `job_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '日志信息',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '执行状态（0正常 1失败）',
  `exception_info` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '异常信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`job_log_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=70472 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='定时任务调度日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_logininfor`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_logininfor` (
  `info_id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
  `user_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户账号',
  `ipaddr` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录IP地址',
  `login_location` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录地点',
  `browser` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '浏览器类型',
  `os` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作系统',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '登录状态（0成功 1失败）',
  `msg` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '提示消息',
  `login_time` datetime DEFAULT NULL COMMENT '访问时间',
  PRIMARY KEY (`info_id`) USING BTREE,
  KEY `idx_sys_logininfor_s` (`status`) USING BTREE,
  KEY `idx_sys_logininfor_lt` (`login_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=389 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='系统访问记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_menu`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `menu_id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件路径',
  `query` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '路由参数',
  `route_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由名称',
  `is_frame` int DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  `is_cache` int DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）',
  `menu_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '#' COMMENT '菜单图标',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`menu_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2252 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='菜单权限表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_menu_bak_p2_4`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu_bak_p2_4` (
  `menu_id` bigint NOT NULL DEFAULT '0' COMMENT '菜单ID',
  `menu_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件路径',
  `query` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '路由参数',
  `route_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由名称',
  `is_frame` int DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  `is_cache` int DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）',
  `menu_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '#' COMMENT '菜单图标',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '备注'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_menu_bak_t1_20260726`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu_bak_t1_20260726` (
  `menu_id` bigint NOT NULL DEFAULT '0' COMMENT '菜单ID',
  `menu_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID',
  `order_num` int DEFAULT '0' COMMENT '显示顺序',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组件路径',
  `query` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '路由参数',
  `route_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '路由名称',
  `is_frame` int DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  `is_cache` int DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）',
  `menu_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '#' COMMENT '菜单图标',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '备注'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_notice`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_notice` (
  `notice_id` int NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `notice_title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告标题',
  `notice_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告类型（1通知 2公告）',
  `notice_content` longblob COMMENT '公告内容',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`notice_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='通知公告表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_oper_log`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_oper_log` (
  `oper_id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '模块标题',
  `business_type` int DEFAULT '0' COMMENT '业务类型（0其它 1新增 2修改 3删除）',
  `method` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求方式',
  `operator_type` int DEFAULT '0' COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
  `oper_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作人员',
  `dept_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '部门名称',
  `oper_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '主机地址',
  `oper_location` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '操作地点',
  `oper_param` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '返回参数',
  `status` int DEFAULT '0' COMMENT '操作状态（0正常 1异常）',
  `error_msg` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '错误消息',
  `oper_time` datetime DEFAULT NULL COMMENT '操作时间',
  `cost_time` bigint DEFAULT '0' COMMENT '消耗时间',
  PRIMARY KEY (`oper_id`) USING BTREE,
  KEY `idx_sys_oper_log_bt` (`business_type`) USING BTREE,
  KEY `idx_sys_oper_log_s` (`status`) USING BTREE,
  KEY `idx_sys_oper_log_ot` (`oper_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=760 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='操作日志记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_post`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_post` (
  `post_id` bigint NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
  `post_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位编码',
  `post_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '岗位名称',
  `post_sort` int NOT NULL COMMENT '显示顺序',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`post_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='岗位信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_role`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `role_id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `menu_check_strictly` tinyint(1) DEFAULT '1' COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) DEFAULT '1' COMMENT '部门树选择项是否关联显示',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=104 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='角色信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_role_dept`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_dept` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`,`dept_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='角色和部门关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_role_menu`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`,`menu_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='角色和菜单关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_role_menu_bak_p2_4`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu_bak_p2_4` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_role_menu_bak_t1_20260726`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu_bak_t1_20260726` (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_user`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `user_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `dept_id` bigint DEFAULT NULL COMMENT '部门ID',
  `user_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户账号',
  `nick_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户昵称',
  `user_type` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '00' COMMENT '用户类型（00系统用户）',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '用户邮箱',
  `phonenumber` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '手机号码',
  `sex` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '头像地址',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '密码',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '账号状态（0正常 1停用）',
  `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `login_ip` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime DEFAULT NULL COMMENT '最后登录时间',
  `pwd_update_date` datetime DEFAULT NULL COMMENT '密码最后更新时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=101 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_user_post`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_post` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  PRIMARY KEY (`user_id`,`post_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户与岗位关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_user_role`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`,`role_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户和角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `tms_browse_record`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tms_browse_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `member_id` bigint NOT NULL COMMENT '用户ID（关联用户表）',
  `browse_type` tinyint NOT NULL COMMENT '浏览类型：1-主题 2-活动',
  `target_id` bigint NOT NULL COMMENT '目标ID（主题ID或活动ID，关联对应表）',
  `target_title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '目标名称（冗余，如主题/活动标题）',
  `browse_time` datetime NOT NULL COMMENT '浏览时间',
  `browse_duration` int DEFAULT '0' COMMENT '浏览时长（秒，可选，统计停留时间）',
  `ip_address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '浏览IP地址',
  `user_agent` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '用户终端信息（浏览器/设备，可选）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间（同browse_time，冗余便于索引）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_member_id` (`member_id`) USING BTREE COMMENT '按用户查询浏览记录',
  KEY `idx_target` (`browse_type`,`target_id`) USING BTREE COMMENT '按主题/活动查询被浏览次数',
  KEY `idx_browse_time` (`browse_time`) USING BTREE COMMENT '按时间范围统计浏览量'
) ENGINE=InnoDB AUTO_INCREMENT=460 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='主题/活动浏览记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `topic_edition_reopen_request`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `topic_edition_reopen_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `source_topic_id` bigint NOT NULL COMMENT '来源 cms_topic.id',
  `idempotency_key` varchar(64) NOT NULL COMMENT 'REOPEN_NEXT_EDITION 幂等键',
  `new_topic_id` bigint DEFAULT NULL COMMENT '成功创建后同事务回填的新 cms_topic.id',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reopen` (`source_topic_id`,`idempotency_key`),
  KEY `idx_reopen_new_topic_id` (`new_topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主题再开一期请求幂等映射';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `track_draft`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `track_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL,
  `name` varchar(60) DEFAULT NULL,
  `polyline` text COMMENT '抽稀后轨迹(前端可直接渲染)',
  `distance_m` int DEFAULT NULL COMMENT '距离(米)',
  `duration_s` int DEFAULT NULL COMMENT '用时(秒)',
  `waypoint_json` json DEFAULT NULL COMMENT '停靠点[{lat,lng,name,photo,note,seq}]',
  `raw_oss_url` varchar(255) DEFAULT NULL COMMENT '原始点>5000时转存 OSS 的 url',
  `lit_blocks` int DEFAULT NULL COMMENT '点亮区块数(M4)',
  `status` tinyint DEFAULT NULL COMMENT '0草稿 1已转路线 2已删',
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member` (`member_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M8 轨迹草稿';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_comment`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `owner_type` int DEFAULT NULL COMMENT '拥有类型1、主题  2、活动  3、创意广场',
  `owner_id` bigint DEFAULT '0' COMMENT '主题ID/活动ID/创意广场帖子ID',
  `rating` int DEFAULT NULL COMMENT '评分0-5',
  `contents` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '评论内容',
  `reply_id` bigint DEFAULT NULL COMMENT '回复评论ID',
  `img_arr` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '评论图片（多张）分号隔开',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态 1显示 2隐藏',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=60 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户评论表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_comment_like`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_comment_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `comment_id` bigint DEFAULT NULL COMMENT '评论ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='评论点赞表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_creative_square`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_creative_square` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL,
  `contents` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '发布内容',
  `pics` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '发布的图片，英文分号隔开',
  `file_url` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '上传的文件下载地址，英文分号隔开',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地址',
  `longitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址经度',
  `latitude` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci DEFAULT NULL COMMENT '地址纬度',
  `data_id` bigint DEFAULT NULL COMMENT '活动ID/主题ID',
  `data_type` int DEFAULT '0' COMMENT '0直接发布   1、活动发布 2、主题发布',
  `like_num` int DEFAULT '0' COMMENT '点赞数量',
  `nolike_num` int DEFAULT '0' COMMENT '踩一踩数量',
  `status` tinyint(1) DEFAULT '1' COMMENT '状态 1显示 2隐藏',
  `is_crowdfunding` tinyint(1) DEFAULT '1' COMMENT '是否参与众筹投票  1参与  2不参与',
  `route_preview_img` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '运动feed路线静态预览图URL(发布快照)',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `check_status` tinyint DEFAULT '0' COMMENT '后台查阅状态：0->待审核；1->审核通过；2->审核失败；',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=54 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='创意广场-用户发布';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_creative_square_like`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_creative_square_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `data_id` bigint DEFAULT NULL COMMENT '创意广场帖子ID',
  `like_type` bigint DEFAULT '0' COMMENT '类型：1已点赞  2已踩',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=143 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='创意广场点赞表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_crowdfunding`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_crowdfunding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `active_id` bigint DEFAULT NULL COMMENT '创意广场活动ID',
  `gift_id` bigint DEFAULT NULL COMMENT '礼物ID',
  `point` int DEFAULT '0' COMMENT '花费积分',
  `contents` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '内容',
  `del_flag` tinyint(1) DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户众筹表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_type` int DEFAULT '1' COMMENT '用户类型：1普通会员 2商户会员',
  `role` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'player' COMMENT '会员角色 player/club/merchant',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '登录手机号码',
  `password` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '账号密码',
  `level_id` int DEFAULT '0' COMMENT '会员等级',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '真实姓名',
  `wechat` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '微信二维码',
  `status` int DEFAULT '1' COMMENT '状态 1启用  2禁用',
  `freeze_end_time` datetime DEFAULT NULL COMMENT '账号冻结结束时间',
  `invite_user_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '邀请人ID',
  `login_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后登录IP',
  `login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '昵称',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '头像',
  `sex` tinyint DEFAULT '0' COMMENT '性别：0未知 1男 2女',
  `wx_open_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '微信小程序OpenID',
  `union_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '微信开放平台unionid',
  `apple_id` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Apple sub',
  `area_id` bigint DEFAULT NULL COMMENT '所在地',
  `birthday` datetime DEFAULT NULL COMMENT '出生日期',
  `mark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '会员备注',
  `tag_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '擅长领域ID集合，以逗号分隔',
  `case_pics` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '作品图片，逗号隔开',
  `case_introducton` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '作品介绍',
  `website` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '个人网址',
  `work_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '工作地点',
  `introduction` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '个人介绍',
  `point` int DEFAULT '0' COMMENT '积分',
  `balance` decimal(11,2) DEFAULT '0.00' COMMENT '余额',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `deregister_status` varchar(16) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/PENDING/COMPLETED',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deregister_apply_time` datetime DEFAULT NULL,
  `deregister_execute_after` datetime DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ums_member_union_id` (`union_id`),
  KEY `idx_ums_member_apple_id` (`apple_id`)
) ENGINE=InnoDB AUTO_INCREMENT=990005 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member_address`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member_address` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `full_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '姓名',
  `area_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '区号',
  `mobile_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机',
  `province` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '省市区，用腾讯的三级地址',
  `detail_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '详细地址',
  `is_default` tinyint DEFAULT '0' COMMENT '是否默认 0不是  1是',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户地址表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member_balance_detail`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member_balance_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `current_balance` decimal(11,2) DEFAULT NULL COMMENT '用户当前余额',
  `after_balance` decimal(11,2) DEFAULT NULL COMMENT '用户变化后余额',
  `change_balance` decimal(11,2) DEFAULT NULL COMMENT '变化金额',
  `change_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '变化原因',
  `event_type` int DEFAULT NULL COMMENT '事件类型：1、主题分润 2、活动分润  3、提现申请  4、提现驳回  5、邀请商家主题分润',
  `event_id` bigint DEFAULT NULL COMMENT '对应事件ID如：报名ID等',
  `coop_settlement_payout_id` bigint GENERATED ALWAYS AS ((case when ((`event_type` = 5) and (`change_reason` like _utf8mb4'Coop结算打款 - 主题%')) then `event_id` else NULL end)) VIRTUAL COMMENT 'Coop结算打款流水唯一业务键',
  `change_type` int DEFAULT '0' COMMENT '变化类型1：收入  2、支出',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_coop_settlement_payout` (`coop_settlement_payout_id`),
  KEY `idx_member_id` (`member_id`) USING BTREE,
  KEY `idx_select` (`member_id`,`event_type`,`change_type`,`create_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户余额明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member_balance_detail_bak_20260809`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member_balance_detail_bak_20260809` (
  `id` bigint NOT NULL DEFAULT '0',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `current_balance` decimal(11,2) DEFAULT NULL COMMENT '用户当前余额',
  `after_balance` decimal(11,2) DEFAULT NULL COMMENT '用户变化后余额',
  `change_balance` decimal(11,2) DEFAULT NULL COMMENT '变化金额',
  `change_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '变化原因',
  `event_type` int DEFAULT NULL COMMENT '事件类型：1、主题分润 2、活动分润  3、提现申请  4、提现驳回  5、邀请商家主题分润',
  `event_id` bigint DEFAULT NULL COMMENT '对应事件ID如：报名ID等',
  `change_type` int DEFAULT '0' COMMENT '变化类型1：收入  2、支出',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member_cart`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member_cart` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '购物车表ID',
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `merchant_id` bigint DEFAULT '0' COMMENT '商家ID',
  `cart_type` int DEFAULT NULL COMMENT '购物车类型：0本地商家 1线上商城  2积分商城',
  `product_id` bigint DEFAULT NULL COMMENT '商品ID',
  `sku_id` bigint DEFAULT NULL COMMENT 'SKU ID',
  `sp_data` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商品属性',
  `quantity` int DEFAULT '0' COMMENT '商品数量',
  `status` int DEFAULT NULL COMMENT '0->失效；1->有效',
  `is_buy` int DEFAULT '0' COMMENT '默认0 ，1是直接购买',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `member_id` (`member_id`) USING BTREE,
  KEY `product_id` (`product_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=889 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='购物车';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member_follow`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member_follow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '关注用户ID',
  `follow_member_id` bigint DEFAULT NULL COMMENT '被关注用户ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=78 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户之间关注表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member_points_detail`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member_points_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `current_points` decimal(10,0) DEFAULT NULL COMMENT '用户当前积分',
  `after_points` decimal(10,0) DEFAULT NULL COMMENT '用户变化后积分',
  `change_points` decimal(10,0) DEFAULT NULL COMMENT '变化积分',
  `change_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '变化原因',
  `event_type` int DEFAULT NULL COMMENT '事件类型：1、注册账号  2、首次报名  3、参与活动 4、活动评价  5、被邀请用户注册并完成首单 6、被邀请用户每次报名  7、推荐商户注册  99 其它',
  `event_id` bigint DEFAULT NULL COMMENT '对应事件ID如：报名ID等',
  `reward_business_key` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '命名空间奖励业务键',
  `change_type` int DEFAULT '0' COMMENT '变化类型1：收入  2、支出',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_member_event_once` (`member_id`,`event_type`,`event_id`),
  UNIQUE KEY `uk_member_reward_business` (`member_id`,`reward_business_key`),
  KEY `idx_member_id` (`member_id`) USING BTREE,
  KEY `idx_select` (`member_id`,`event_type`,`change_type`,`create_time`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=57 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户积分明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ums_member_withdrawal`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ums_member_withdrawal` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint DEFAULT NULL COMMENT '用户ID',
  `realname` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '姓名',
  `bank_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行名称',
  `bank_account` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行账号',
  `mobilephone` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '电话',
  `withdrawal_amount` decimal(11,2) DEFAULT NULL COMMENT '提现金额',
  `service_fee` decimal(10,3) DEFAULT NULL COMMENT '提现手续费',
  `received_amount` decimal(10,3) DEFAULT NULL COMMENT '实际到账金额',
  `status` int DEFAULT '0' COMMENT '状态：0待审核 1审核通过 2审核失败 3已打款',
  `payout_reference` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行流水号/打款凭据；status=3 时必填',
  `del_flag` tinyint DEFAULT '0' COMMENT '删除标记 0 代表存在  2删除',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '驳回原因',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修改人',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `audit_by` bigint DEFAULT NULL COMMENT '审核人',
  `handle_by` bigint DEFAULT NULL COMMENT '处理人',
  `handle_time` datetime DEFAULT NULL COMMENT '处理时间',
  `realname_enc` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '姓名AES-GCM密文',
  `bank_name_enc` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行名称AES-GCM密文',
  `bank_account_enc` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行卡AES-GCM密文',
  `mobilephone_enc` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号AES-GCM密文',
  `realname_mask` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '姓名掩码',
  `bank_name_mask` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行名称掩码',
  `bank_account_mask` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '银行卡掩码',
  `mobilephone_mask` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号掩码',
  `pii_cipher_version` tinyint DEFAULT NULL COMMENT '敏感资料密文版本',
  `pii_migrated_at` datetime DEFAULT NULL COMMENT '历史明文清除完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_member_id` (`member_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=18176 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户提现申请表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_permission`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role` varchar(16) NOT NULL COMMENT '角色 player/club/merchant',
  `max_themes` int NOT NULL DEFAULT '0' COMMENT '主题同时存在上限',
  `max_nodes_per_theme` int DEFAULT NULL COMMENT '单主题节点上限,NULL=不限',
  `max_events` int NOT NULL DEFAULT '0' COMMENT '活动同时存在上限',
  `max_coupons` int NOT NULL DEFAULT '0' COMMENT '优惠券同时存在上限',
  `can_create_theme` tinyint(1) NOT NULL DEFAULT '0' COMMENT '可创建主题',
  `can_create_event` tinyint(1) NOT NULL DEFAULT '0' COMMENT '可创建活动',
  `can_publish_coupon` tinyint(1) NOT NULL DEFAULT '0' COMMENT '可发布优惠券',
  `can_redeem_coupon` tinyint(1) NOT NULL DEFAULT '0' COMMENT '到店核销权(仅商家)',
  `node_types` varchar(128) DEFAULT NULL COMMENT '可用节点类型,逗号分隔 checkin,scan,quiz,...',
  `can_branch` tinyint(1) NOT NULL DEFAULT '0' COMMENT '分支/条件逻辑',
  `can_game_mechanic` tinyint(1) NOT NULL DEFAULT '0' COMMENT '博弈机制 竞速/对抗/协作',
  `can_open_as_node` tinyint(1) NOT NULL DEFAULT '0' COMMENT '开放为城市节点(仅商家)',
  `can_design_medal` tinyint(1) NOT NULL DEFAULT '0' COMMENT '勋章设计权(仅俱乐部)',
  `can_create_group` tinyint(1) NOT NULL DEFAULT '0' COMMENT '建群权',
  `data_scope` varchar(32) NOT NULL DEFAULT 'self' COMMENT '数据范围 self/store/theme+city',
  `withdrawable` tinyint(1) NOT NULL DEFAULT '0' COMMENT '可提现(否=积分制)',
  `membership_level` varchar(16) DEFAULT NULL COMMENT '预留:free/pro/premium,本期不用',
  `can_publish_official_event` tinyint DEFAULT '0' COMMENT '是否可发官方活动/通知(白名单)',
  `max_city_nodes` int DEFAULT '0' COMMENT '可常驻点亮城市据点数(仅商家)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_level` (`role`,`membership_level`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色配额配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Temporary view structure for view `v_node_quality`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `v_node_quality` AS SELECT 
 1 AS `topic_id`,
 1 AS `node_id`,
 1 AS `attempt_cnt`,
 1 AS `pass_cnt`,
 1 AS `wrong_cnt`,
 1 AS `abandon_cnt`,
 1 AS `pass_rate`,
 1 AS `abandon_rate`,
 1 AS `avg_duration_s`,
 1 AS `uniq_member_cnt`*/;
SET character_set_client = @saved_cs_client;

--
-- Table structure for table `verification_review`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `verification_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `verifier_member_id` bigint NOT NULL COMMENT '被抽检的核销负责人memberId',
  `topic_id` bigint NOT NULL,
  `risk_type` varchar(32) NOT NULL COMMENT 'HIGH_RATE/BURST/HIGH_RATE_AND_BURST',
  `verification_rate` decimal(10,4) NOT NULL,
  `paid_count` int NOT NULL,
  `verified_count` int NOT NULL,
  `one_minute_count` int NOT NULL,
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待复核 1已确认正常 2确认异常',
  `idempotency_key` varchar(128) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_verification_review_idempotency` (`idempotency_key`),
  KEY `idx_verification_review_queue` (`status`,`create_time`),
  KEY `idx_verification_review_topic` (`topic_id`,`verifier_member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核销率与频次异常人工复核队列';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Temporary view structure for view `view_collaborators`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_collaborators` AS SELECT 
 1 AS `id`,
 1 AS `data_type`,
 1 AS `data_id`,
 1 AS `member_id`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `member_nickname`,
 1 AS `member_avatar`,
 1 AS `member_phone`,
 1 AS `member_real_name`,
 1 AS `member_sex`,
 1 AS `member_status`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_comment`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_comment` AS SELECT 
 1 AS `id`,
 1 AS `member_id`,
 1 AS `owner_type`,
 1 AS `owner_id`,
 1 AS `rating`,
 1 AS `contents`,
 1 AS `reply_id`,
 1 AS `img_arr`,
 1 AS `status`,
 1 AS `del_flag`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `member_nickname`,
 1 AS `member_avatar`,
 1 AS `member_user_type`,
 1 AS `member_level_id`,
 1 AS `like_count`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_coupon_history`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_coupon_history` AS SELECT 
 1 AS `id`,
 1 AS `coupon_id`,
 1 AS `member_id`,
 1 AS `coupon_code`,
 1 AS `qrcode_url`,
 1 AS `get_type`,
 1 AS `get_time`,
 1 AS `use_status`,
 1 AS `use_time`,
 1 AS `order_id`,
 1 AS `order_sn`,
 1 AS `start_time`,
 1 AS `end_time`,
 1 AS `note`,
 1 AS `del_flag`,
 1 AS `coupon_name`,
 1 AS `coupon_type`,
 1 AS `coupon_description`,
 1 AS `coupon_del_flag`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_creative_square`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_creative_square` AS SELECT 
 1 AS `id`,
 1 AS `member_id`,
 1 AS `contents`,
 1 AS `pics`,
 1 AS `file_url`,
 1 AS `address`,
 1 AS `longitude`,
 1 AS `latitude`,
 1 AS `data_id`,
 1 AS `data_type`,
 1 AS `like_num`,
 1 AS `nolike_num`,
 1 AS `status`,
 1 AS `is_crowdfunding`,
 1 AS `del_flag`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `member_nickname`,
 1 AS `member_avatar`,
 1 AS `member_user_type`,
 1 AS `member_level_id`,
 1 AS `comment_count`,
 1 AS `no_like_count`,
 1 AS `like_count`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_member_cart`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_member_cart` AS SELECT 
 1 AS `id`,
 1 AS `member_id`,
 1 AS `merchant_id`,
 1 AS `cart_type`,
 1 AS `product_id`,
 1 AS `sku_id`,
 1 AS `sp_data`,
 1 AS `quantity`,
 1 AS `status`,
 1 AS `is_buy`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `product_name`,
 1 AS `product_pic`,
 1 AS `price`,
 1 AS `original_price`,
 1 AS `sku_name`,
 1 AS `sku_name_ar`,
 1 AS `sku_pic`,
 1 AS `weight`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_order`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_order` AS SELECT 
 1 AS `id`,
 1 AS `order_type`,
 1 AS `order_sn`,
 1 AS `pay_id`,
 1 AS `member_id`,
 1 AS `mms_merchant_id`,
 1 AS `product_quantity`,
 1 AS `product_total_amount`,
 1 AS `delivery_fee`,
 1 AS `pay_amount`,
 1 AS `tax_fee`,
 1 AS `order_total_amount`,
 1 AS `points_amount`,
 1 AS `pay_type`,
 1 AS `status`,
 1 AS `aftersale_status`,
 1 AS `delivery_company`,
 1 AS `delivery_sn`,
 1 AS `auto_confirm_day`,
 1 AS `receiver_name`,
 1 AS `receiver_phone`,
 1 AS `receiver_phone_code`,
 1 AS `receiver_province`,
 1 AS `receiver_detail_address`,
 1 AS `order_note`,
 1 AS `merchant_note`,
 1 AS `confirm_status`,
 1 AS `del_flag`,
 1 AS `payment_time`,
 1 AS `delivery_time`,
 1 AS `receive_time`,
 1 AS `member_coupon_id`,
 1 AS `coupon_amount`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `phone`,
 1 AS `nickname`,
 1 AS `merchant_name`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_product_like`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_product_like` AS SELECT 
 1 AS `id`,
 1 AS `member_id`,
 1 AS `product_id`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `product_name`,
 1 AS `pic`,
 1 AS `price`,
 1 AS `original_price`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_registration`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_registration` AS SELECT 
 1 AS `id`,
 1 AS `registration_no`,
 1 AS `owner_type`,
 1 AS `owner_id`,
 1 AS `activity_title`,
 1 AS `ticket_id`,
 1 AS `ticket_name`,
 1 AS `ticket_price`,
 1 AS `member_id`,
 1 AS `real_name`,
 1 AS `phone`,
 1 AS `email`,
 1 AS `address`,
 1 AS `address_id`,
 1 AS `order_num`,
 1 AS `original_amount`,
 1 AS `discount_amount`,
 1 AS `total_amount`,
 1 AS `point_used`,
 1 AS `point_deduct_amount`,
 1 AS `point_deduct_rate`,
 1 AS `max_point_deduct`,
 1 AS `payable_amount`,
 1 AS `payment_status`,
 1 AS `payment_type`,
 1 AS `payment_time`,
 1 AS `transaction_id`,
 1 AS `point_payment_amount`,
 1 AS `wechat_payment_amount`,
 1 AS `registration_status`,
 1 AS `pay_expire_time`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `verification_code`,
 1 AS `qrcode_url`,
 1 AS `verification_status`,
 1 AS `verification_time`,
 1 AS `verifier_id`,
 1 AS `points_returned`,
 1 AS `avatar`,
 1 AS `nickname`*/;
SET character_set_client = @saved_cs_client;

--
-- Temporary view structure for view `view_registration_merchant`
--

SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `view_registration_merchant` AS SELECT 
 1 AS `id`,
 1 AS `member_id`,
 1 AS `topic_id`,
 1 AS `mode`,
 1 AS `node_id`,
 1 AS `address`,
 1 AS `address_name`,
 1 AS `longitude`,
 1 AS `latitude`,
 1 AS `template_id`,
 1 AS `pic_url`,
 1 AS `email`,
 1 AS `start_date`,
 1 AS `end_date`,
 1 AS `cooperate_date`,
 1 AS `activity_desc`,
 1 AS `limit_num`,
 1 AS `rule_instructions`,
 1 AS `validation_method`,
 1 AS `question_name`,
 1 AS `question_answer`,
 1 AS `feedback_text`,
 1 AS `coupon_id`,
 1 AS `question_a`,
 1 AS `question_b`,
 1 AS `question_c`,
 1 AS `question_d`,
 1 AS `correct_answer`,
 1 AS `status`,
 1 AS `sharing_rate`,
 1 AS `sharing_amount`,
 1 AS `reason`,
 1 AS `del_flag`,
 1 AS `create_by`,
 1 AS `create_time`,
 1 AS `update_by`,
 1 AS `update_time`,
 1 AS `topic_member_id`,
 1 AS `topic_type`,
 1 AS `related_topic_id`,
 1 AS `topic_name`,
 1 AS `topic_subtitle`,
 1 AS `topic_description`,
 1 AS `topic_address_name`,
 1 AS `total_time`,
 1 AS `total_mileage`,
 1 AS `location_count`,
 1 AS `template_count`,
 1 AS `topic_start_date`,
 1 AS `topic_end_date`,
 1 AS `topic_img_url`,
 1 AS `topic_img_arr`,
 1 AS `category_ids`,
 1 AS `topic_status`,
 1 AS `topic_reason`,
 1 AS `user_status`,
 1 AS `merchant_status`,
 1 AS `merchant_sign_up_start_date`,
 1 AS `merchant_sign_up_end_date`,
 1 AS `topic_del_flag`,
 1 AS `is_sync`,
 1 AS `is_recommend`,
 1 AS `topic_check_status`,
 1 AS `is_settlement`,
 1 AS `topic_create_by`,
 1 AS `topic_create_time`,
 1 AS `topic_update_by`,
 1 AS `topic_update_time`,
 1 AS `display_status`,
 1 AS `is_in_progress`,
 1 AS `is_ended`,
 1 AS `is_under_review`,
 1 AS `is_rejected`*/;
SET character_set_client = @saved_cs_client;

--
-- Table structure for table `wx_content_callback_inbox`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wx_content_callback_inbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `trace_hash` binary(32) NOT NULL COMMENT 'trace_id SHA-256；不保存原值',
  `biz_type` smallint unsigned NOT NULL,
  `biz_id` bigint NOT NULL,
  `media_hash` binary(32) DEFAULT NULL COMMENT '送检媒体 URL SHA-256；兼容旧任务时可空',
  `verdict` tinyint unsigned DEFAULT NULL COMMENT '0 pass, 1 risky',
  `state` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '0 waiting,1 processing,2 done,3 retryable,4 ignored_stale',
  `owner_token` binary(16) DEFAULT NULL,
  `fence_token` bigint unsigned NOT NULL DEFAULT '0',
  `lease_until` datetime(3) DEFAULT NULL,
  `attempt_count` int unsigned NOT NULL DEFAULT '0',
  `last_error_code` varchar(64) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_content_callback_trace_hash` (`trace_hash`),
  KEY `idx_wx_content_callback_claim` (`state`,`lease_until`),
  KEY `idx_wx_content_callback_target` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信媒体内容审核回调 inbox/fencing';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `wx_payment_callback_inbox`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wx_payment_callback_inbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `notify_hash` binary(32) NOT NULL COMMENT '微信回调唯一标识(如通知ID/密文)SHA-256；用于去重同一回调的重复投递,不保存原值',
  `biz_type` smallint unsigned NOT NULL COMMENT '1支付回调 2退款回调 3转账回调',
  `biz_id` bigint NOT NULL COMMENT '内部业务单据ID(cms_registration.id / refund_application.id / member_cash_withdraw.id)',
  `event_type` varchar(32) DEFAULT NULL COMMENT '微信事件类型,如 TRANSACTION.SUCCESS/REFUND.SUCCESS',
  `state` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '0 waiting,1 processing,2 done,3 retryable,4 ignored_stale',
  `owner_token` binary(16) DEFAULT NULL COMMENT '当前持有处理权的 worker 令牌',
  `fence_token` bigint unsigned NOT NULL DEFAULT '0' COMMENT '单调递增隔离令牌,防止旧 worker 在重新抢占后继续写',
  `lease_until` datetime(3) DEFAULT NULL COMMENT '处理租约到期时间,过期可被其他 worker 抢占',
  `attempt_count` int unsigned NOT NULL DEFAULT '0',
  `last_error_code` varchar(64) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_payment_callback_notify_hash` (`notify_hash`),
  KEY `idx_wx_payment_callback_claim` (`state`,`lease_until`),
  KEY `idx_wx_payment_callback_target` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信支付/退款/转账回调 inbox/fencing(P1-5)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `wx_payment_callback_outbox`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wx_payment_callback_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `biz_type` smallint unsigned NOT NULL COMMENT '1支付终态查询 2退款终态查询 3转账终态查询(仅查询类;涉及金额变化的动作见 money_affecting)',
  `biz_id` bigint NOT NULL COMMENT '内部业务单据ID',
  `idempotency_key` varchar(128) NOT NULL COMMENT '出站请求幂等键(如 out_trade_no/out_refund_no/out_batch_no)',
  `money_affecting` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '1=涉及金额变化的动作(如二次转账),不允许自动重试,只能人工触发',
  `state` tinyint unsigned NOT NULL DEFAULT '0' COMMENT '0 pending,1 dispatching,2 done,3 retryable,4 manual_required',
  `owner_token` binary(16) DEFAULT NULL,
  `fence_token` bigint unsigned NOT NULL DEFAULT '0',
  `lease_until` datetime(3) DEFAULT NULL,
  `attempt_count` int unsigned NOT NULL DEFAULT '0',
  `max_attempts` int unsigned NOT NULL DEFAULT '3' COMMENT '任务书1.6:自动重试上限,默认3次,超过转 manual_required',
  `next_attempt_at` datetime(3) DEFAULT NULL COMMENT '下次自动重试时间(间隔递增由消费方计算写入)',
  `last_error_code` varchar(64) DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_payment_outbox_idempotency` (`idempotency_key`),
  KEY `idx_wx_payment_outbox_claim` (`state`,`next_attempt_at`),
  KEY `idx_wx_payment_outbox_target` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信支付/退款/转账出站重试 outbox/fencing(P1-5)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `wx_trade_bill_snapshot`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wx_trade_bill_snapshot` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `bill_date` date NOT NULL COMMENT '账单归属日期(T日,微信账单文件的自然日,非落库时间)',
  `wx_trade_no` varchar(64) NOT NULL COMMENT '微信侧单号:支付行=微信支付订单号(transaction_id);退款行=微信退款单号(refund_id)',
  `out_trade_no` varchar(64) NOT NULL COMMENT '商户侧业务单号,对应 cms_registration.registration_no(下单时作为 out_trade_no 传给微信)',
  `out_refund_no` varchar(64) DEFAULT NULL COMMENT '商户侧退款单号(仅退款行有值,对应 RefundApplication.refundNo)',
  `biz_type` tinyint unsigned NOT NULL COMMENT '1=支付成功 2=退款成功(与账单文件的"交易状态/退款状态"列对应,只落成功态,失败/关闭态不落)',
  `amount` decimal(12,2) NOT NULL COMMENT '金额(元)。★绝对值不带正负,方向由 biz_type 承载,别塞负数退款',
  `trade_time` datetime NOT NULL COMMENT '微信侧交易时间(账单文件"交易时间"列,不是落库时间)',
  `raw_ingested_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本行从账单文件解析落库的时间,供追查"账单何时导入"',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wx_trade_bill_no` (`wx_trade_no`,`biz_type`) COMMENT '同一笔支付/退款重复下载同一天账单或跨天重复出现时防重复入账',
  KEY `idx_wx_trade_bill_date` (`bill_date`),
  KEY `idx_wx_trade_bill_out_trade_no` (`out_trade_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信交易账单(T+1下载)本地留痕快照,供四账对账消费(P1-6 N块)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping events for database 'chengyinhubdb'
--

--
-- Dumping routines for database 'chengyinhubdb'
--

--
-- Final view structure for view `v_node_quality`
--

/*!50001 DROP VIEW IF EXISTS `v_node_quality`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `v_node_quality` AS select `play_attempt_log`.`topic_id` AS `topic_id`,`play_attempt_log`.`node_id` AS `node_id`,count(0) AS `attempt_cnt`,sum((case when (`play_attempt_log`.`result` = 1) then 1 else 0 end)) AS `pass_cnt`,sum((case when (`play_attempt_log`.`result` = 0) then 1 else 0 end)) AS `wrong_cnt`,sum((case when (`play_attempt_log`.`result` = 2) then 1 else 0 end)) AS `abandon_cnt`,round((sum((case when (`play_attempt_log`.`result` = 1) then 1 else 0 end)) / count(0)),4) AS `pass_rate`,round((sum((case when (`play_attempt_log`.`result` = 2) then 1 else 0 end)) / count(0)),4) AS `abandon_rate`,round(avg(`play_attempt_log`.`duration_s`),1) AS `avg_duration_s`,count(distinct `play_attempt_log`.`member_id`) AS `uniq_member_cnt` from `play_attempt_log` group by `play_attempt_log`.`topic_id`,`play_attempt_log`.`node_id` */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_collaborators`
--

/*!50001 DROP VIEW IF EXISTS `view_collaborators`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_collaborators` AS select `cc`.`id` AS `id`,`cc`.`data_type` AS `data_type`,`cc`.`data_id` AS `data_id`,`cc`.`member_id` AS `member_id`,`cc`.`create_by` AS `create_by`,`cc`.`create_time` AS `create_time`,`cc`.`update_by` AS `update_by`,`cc`.`update_time` AS `update_time`,`um`.`nickname` AS `member_nickname`,`um`.`avatar` AS `member_avatar`,`um`.`phone` AS `member_phone`,`um`.`name` AS `member_real_name`,`um`.`sex` AS `member_sex`,`um`.`status` AS `member_status` from (`cms_collaborators` `cc` left join `ums_member` `um` on((`cc`.`member_id` = `um`.`id`))) where (`um`.`del_flag` = 0) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_comment`
--

/*!50001 DROP VIEW IF EXISTS `view_comment`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_comment` AS select `c`.`id` AS `id`,`c`.`member_id` AS `member_id`,`c`.`owner_type` AS `owner_type`,`c`.`owner_id` AS `owner_id`,`c`.`rating` AS `rating`,`c`.`contents` AS `contents`,`c`.`reply_id` AS `reply_id`,`c`.`img_arr` AS `img_arr`,`c`.`status` AS `status`,`c`.`del_flag` AS `del_flag`,`c`.`create_by` AS `create_by`,`c`.`create_time` AS `create_time`,`c`.`update_by` AS `update_by`,`c`.`update_time` AS `update_time`,`m`.`nickname` AS `member_nickname`,`m`.`avatar` AS `member_avatar`,`m`.`user_type` AS `member_user_type`,`m`.`level_id` AS `member_level_id`,(select count(0) from `ums_comment_like` where (`ums_comment_like`.`comment_id` = `c`.`id`)) AS `like_count` from (`ums_comment` `c` left join `ums_member` `m` on(((`c`.`member_id` = `m`.`id`) and (`m`.`del_flag` = 0)))) where ((`c`.`del_flag` = 0) and (`c`.`status` = 1)) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_coupon_history`
--

/*!50001 DROP VIEW IF EXISTS `view_coupon_history`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_coupon_history` AS select `h`.`id` AS `id`,`h`.`coupon_id` AS `coupon_id`,`h`.`member_id` AS `member_id`,`h`.`coupon_code` AS `coupon_code`,`h`.`qrcode_url` AS `qrcode_url`,`h`.`get_type` AS `get_type`,`h`.`get_time` AS `get_time`,`h`.`use_status` AS `use_status`,`h`.`use_time` AS `use_time`,`h`.`order_id` AS `order_id`,`h`.`order_sn` AS `order_sn`,`h`.`start_time` AS `start_time`,`h`.`end_time` AS `end_time`,`h`.`note` AS `note`,`h`.`del_flag` AS `del_flag`,`c`.`name` AS `coupon_name`,`c`.`coupon_type` AS `coupon_type`,`c`.`description` AS `coupon_description`,`c`.`del_flag` AS `coupon_del_flag` from (`sms_coupon_history` `h` left join `sms_coupon` `c` on((`h`.`coupon_id` = `c`.`id`))) where ((`h`.`del_flag` = 0) and (`c`.`del_flag` = 0)) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_creative_square`
--

/*!50001 DROP VIEW IF EXISTS `view_creative_square`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_creative_square` AS select `c`.`id` AS `id`,`c`.`member_id` AS `member_id`,`c`.`contents` AS `contents`,`c`.`pics` AS `pics`,`c`.`file_url` AS `file_url`,`c`.`address` AS `address`,`c`.`longitude` AS `longitude`,`c`.`latitude` AS `latitude`,`c`.`data_id` AS `data_id`,`c`.`data_type` AS `data_type`,`c`.`like_num` AS `like_num`,`c`.`nolike_num` AS `nolike_num`,`c`.`status` AS `status`,`c`.`is_crowdfunding` AS `is_crowdfunding`,`c`.`del_flag` AS `del_flag`,`c`.`create_by` AS `create_by`,`c`.`create_time` AS `create_time`,`c`.`update_by` AS `update_by`,`c`.`update_time` AS `update_time`,`m`.`nickname` AS `member_nickname`,`m`.`avatar` AS `member_avatar`,`m`.`user_type` AS `member_user_type`,`m`.`level_id` AS `member_level_id`,(select count(0) from `ums_comment` where ((`ums_comment`.`owner_type` = 3) and (`ums_comment`.`owner_id` = `c`.`id`) and (`ums_comment`.`status` = 1) and (`ums_comment`.`del_flag` = 0))) AS `comment_count`,(select count(0) from `ums_creative_square_like` where ((`ums_creative_square_like`.`data_id` = `c`.`id`) and (`ums_creative_square_like`.`like_type` = 0))) AS `no_like_count`,(select count(0) from `ums_creative_square_like` where ((`ums_creative_square_like`.`data_id` = `c`.`id`) and (`ums_creative_square_like`.`like_type` = 1))) AS `like_count` from (`ums_creative_square` `c` left join `ums_member` `m` on(((`c`.`member_id` = `m`.`id`) and (`m`.`del_flag` = 0)))) where ((`c`.`del_flag` = 0) and (`c`.`status` = 1)) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_member_cart`
--

/*!50001 DROP VIEW IF EXISTS `view_member_cart`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_member_cart` AS select `ums_member_cart`.`id` AS `id`,`ums_member_cart`.`member_id` AS `member_id`,`ums_member_cart`.`merchant_id` AS `merchant_id`,`ums_member_cart`.`cart_type` AS `cart_type`,`ums_member_cart`.`product_id` AS `product_id`,`ums_member_cart`.`sku_id` AS `sku_id`,`ums_member_cart`.`sp_data` AS `sp_data`,`ums_member_cart`.`quantity` AS `quantity`,`ums_member_cart`.`status` AS `status`,`ums_member_cart`.`is_buy` AS `is_buy`,`ums_member_cart`.`create_by` AS `create_by`,`ums_member_cart`.`create_time` AS `create_time`,`ums_member_cart`.`update_by` AS `update_by`,`ums_member_cart`.`update_time` AS `update_time`,`pms_product`.`product_name` AS `product_name`,`pms_product`.`pic` AS `product_pic`,`pms_sku`.`price` AS `price`,`pms_sku`.`original_price` AS `original_price`,`pms_sku`.`sku_name` AS `sku_name`,`pms_sku`.`sku_name_ar` AS `sku_name_ar`,`pms_sku`.`pic` AS `sku_pic`,`pms_sku`.`weight` AS `weight` from (((`ums_member_cart` join `pms_product` on((`pms_product`.`id` = `ums_member_cart`.`product_id`))) left join `mms_merchant` on((`mms_merchant`.`id` = `ums_member_cart`.`merchant_id`))) left join `pms_sku` on((`pms_sku`.`id` = `ums_member_cart`.`sku_id`))) order by `ums_member_cart`.`id` desc */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_order`
--

/*!50001 DROP VIEW IF EXISTS `view_order`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_order` AS select `oms_order`.`id` AS `id`,`oms_order`.`order_type` AS `order_type`,`oms_order`.`order_sn` AS `order_sn`,`oms_order`.`pay_id` AS `pay_id`,`oms_order`.`member_id` AS `member_id`,`oms_order`.`mms_merchant_id` AS `mms_merchant_id`,`oms_order`.`product_quantity` AS `product_quantity`,`oms_order`.`product_total_amount` AS `product_total_amount`,`oms_order`.`delivery_fee` AS `delivery_fee`,`oms_order`.`pay_amount` AS `pay_amount`,`oms_order`.`tax_fee` AS `tax_fee`,`oms_order`.`order_total_amount` AS `order_total_amount`,`oms_order`.`points_amount` AS `points_amount`,`oms_order`.`pay_type` AS `pay_type`,`oms_order`.`status` AS `status`,`oms_order`.`aftersale_status` AS `aftersale_status`,`oms_order`.`delivery_company` AS `delivery_company`,`oms_order`.`delivery_sn` AS `delivery_sn`,`oms_order`.`auto_confirm_day` AS `auto_confirm_day`,`oms_order`.`receiver_name` AS `receiver_name`,`oms_order`.`receiver_phone` AS `receiver_phone`,`oms_order`.`receiver_phone_code` AS `receiver_phone_code`,`oms_order`.`receiver_province` AS `receiver_province`,`oms_order`.`receiver_detail_address` AS `receiver_detail_address`,`oms_order`.`order_note` AS `order_note`,`oms_order`.`merchant_note` AS `merchant_note`,`oms_order`.`confirm_status` AS `confirm_status`,`oms_order`.`del_flag` AS `del_flag`,`oms_order`.`payment_time` AS `payment_time`,`oms_order`.`delivery_time` AS `delivery_time`,`oms_order`.`receive_time` AS `receive_time`,`oms_order`.`member_coupon_id` AS `member_coupon_id`,`oms_order`.`coupon_amount` AS `coupon_amount`,`oms_order`.`create_by` AS `create_by`,`oms_order`.`create_time` AS `create_time`,`oms_order`.`update_by` AS `update_by`,`oms_order`.`update_time` AS `update_time`,`ums_member`.`phone` AS `phone`,`ums_member`.`nickname` AS `nickname`,`mms_merchant`.`name` AS `merchant_name` from ((`oms_order` join `ums_member` on((`ums_member`.`id` = `oms_order`.`member_id`))) left join `mms_merchant` on((`mms_merchant`.`id` = `oms_order`.`mms_merchant_id`))) order by `oms_order`.`id` desc */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_product_like`
--

/*!50001 DROP VIEW IF EXISTS `view_product_like`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_product_like` AS select `c`.`id` AS `id`,`c`.`member_id` AS `member_id`,`c`.`product_id` AS `product_id`,`c`.`create_by` AS `create_by`,`c`.`create_time` AS `create_time`,`c`.`update_by` AS `update_by`,`c`.`update_time` AS `update_time`,`p`.`product_name` AS `product_name`,`p`.`pic` AS `pic`,`p`.`price` AS `price`,`p`.`original_price` AS `original_price` from (`pms_product_like` `c` left join `pms_product` `p` on(((`c`.`product_id` = `p`.`id`) and (`p`.`del_flag` = 0) and (`p`.`publish_status` = 1)))) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_registration`
--

/*!50001 DROP VIEW IF EXISTS `view_registration`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_registration` AS select `c`.`id` AS `id`,`c`.`registration_no` AS `registration_no`,`c`.`owner_type` AS `owner_type`,`c`.`owner_id` AS `owner_id`,`c`.`activity_title` AS `activity_title`,`c`.`ticket_id` AS `ticket_id`,`c`.`ticket_name` AS `ticket_name`,`c`.`ticket_price` AS `ticket_price`,`c`.`member_id` AS `member_id`,`c`.`real_name` AS `real_name`,`c`.`phone` AS `phone`,`c`.`email` AS `email`,`c`.`address` AS `address`,`c`.`address_id` AS `address_id`,`c`.`order_num` AS `order_num`,`c`.`original_amount` AS `original_amount`,`c`.`discount_amount` AS `discount_amount`,`c`.`total_amount` AS `total_amount`,`c`.`point_used` AS `point_used`,`c`.`point_deduct_amount` AS `point_deduct_amount`,`c`.`point_deduct_rate` AS `point_deduct_rate`,`c`.`max_point_deduct` AS `max_point_deduct`,`c`.`payable_amount` AS `payable_amount`,`c`.`payment_status` AS `payment_status`,`c`.`payment_type` AS `payment_type`,`c`.`payment_time` AS `payment_time`,`c`.`transaction_id` AS `transaction_id`,`c`.`point_payment_amount` AS `point_payment_amount`,`c`.`wechat_payment_amount` AS `wechat_payment_amount`,`c`.`registration_status` AS `registration_status`,`c`.`pay_expire_time` AS `pay_expire_time`,`c`.`create_by` AS `create_by`,`c`.`create_time` AS `create_time`,`c`.`update_by` AS `update_by`,`c`.`update_time` AS `update_time`,`c`.`verification_code` AS `verification_code`,`c`.`qrcode_url` AS `qrcode_url`,`c`.`verification_status` AS `verification_status`,`c`.`verification_time` AS `verification_time`,`c`.`verifier_id` AS `verifier_id`,`c`.`points_returned` AS `points_returned`,`u`.`avatar` AS `avatar`,`u`.`nickname` AS `nickname` from (`cms_registration` `c` left join `ums_member` `u` on((`c`.`member_id` = `u`.`id`))) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;

--
-- Final view structure for view `view_registration_merchant`
--

/*!50001 DROP VIEW IF EXISTS `view_registration_merchant`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`chengyinhubdb`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `view_registration_merchant` AS select `crm`.`id` AS `id`,`crm`.`member_id` AS `member_id`,`crm`.`topic_id` AS `topic_id`,`crm`.`mode` AS `mode`,`crm`.`node_id` AS `node_id`,`crm`.`address` AS `address`,`crm`.`address_name` AS `address_name`,`crm`.`longitude` AS `longitude`,`crm`.`latitude` AS `latitude`,`crm`.`template_id` AS `template_id`,`crm`.`pic_url` AS `pic_url`,`crm`.`email` AS `email`,`crm`.`start_date` AS `start_date`,`crm`.`end_date` AS `end_date`,`crm`.`cooperate_date` AS `cooperate_date`,`crm`.`activity_desc` AS `activity_desc`,`crm`.`limit_num` AS `limit_num`,`crm`.`rule_instructions` AS `rule_instructions`,`crm`.`validation_method` AS `validation_method`,`crm`.`question_name` AS `question_name`,`crm`.`question_answer` AS `question_answer`,`crm`.`feedback_text` AS `feedback_text`,`crm`.`coupon_id` AS `coupon_id`,`crm`.`question_a` AS `question_a`,`crm`.`question_b` AS `question_b`,`crm`.`question_c` AS `question_c`,`crm`.`question_d` AS `question_d`,`crm`.`correct_answer` AS `correct_answer`,`crm`.`status` AS `status`,`crm`.`sharing_rate` AS `sharing_rate`,`crm`.`sharing_amount` AS `sharing_amount`,`crm`.`reason` AS `reason`,`crm`.`del_flag` AS `del_flag`,`crm`.`create_by` AS `create_by`,`crm`.`create_time` AS `create_time`,`crm`.`update_by` AS `update_by`,`crm`.`update_time` AS `update_time`,`ct`.`member_id` AS `topic_member_id`,`ct`.`topic_type` AS `topic_type`,`ct`.`topic_id` AS `related_topic_id`,`ct`.`name` AS `topic_name`,`ct`.`subtitle` AS `topic_subtitle`,`ct`.`description` AS `topic_description`,`ct`.`address_name` AS `topic_address_name`,`ct`.`total_time` AS `total_time`,`ct`.`total_mileage` AS `total_mileage`,`ct`.`location_count` AS `location_count`,`ct`.`template_count` AS `template_count`,`ct`.`start_date` AS `topic_start_date`,`ct`.`end_date` AS `topic_end_date`,`ct`.`img_url` AS `topic_img_url`,`ct`.`img_arr` AS `topic_img_arr`,`ct`.`category_ids` AS `category_ids`,`ct`.`status` AS `topic_status`,`ct`.`reason` AS `topic_reason`,`ct`.`user_status` AS `user_status`,`ct`.`merchant_status` AS `merchant_status`,`ct`.`merchant_sign_up_start_date` AS `merchant_sign_up_start_date`,`ct`.`merchant_sign_up_end_date` AS `merchant_sign_up_end_date`,`ct`.`del_flag` AS `topic_del_flag`,`ct`.`is_sync` AS `is_sync`,`ct`.`is_recommend` AS `is_recommend`,`ct`.`check_status` AS `topic_check_status`,`ct`.`is_settlement` AS `is_settlement`,`ct`.`create_by` AS `topic_create_by`,`ct`.`create_time` AS `topic_create_time`,`ct`.`update_by` AS `topic_update_by`,`ct`.`update_time` AS `topic_update_time`,(case when (`crm`.`status` = 2) then '已驳回' when (`crm`.`status` = 0) then '审核中' when (`crm`.`status` = 1) then (case when (`ct`.`end_date` < now()) then '已结束' when ((`ct`.`start_date` <= now()) and (`ct`.`end_date` >= now())) then '进行中' when (`ct`.`start_date` > now()) then '未开始' else '已审核' end) else '未知状态' end) AS `display_status`,(case when ((`crm`.`status` = 1) and (`ct`.`start_date` <= now()) and (`ct`.`end_date` >= now())) then 1 else 0 end) AS `is_in_progress`,(case when ((`crm`.`status` = 1) and (`ct`.`end_date` < now())) then 1 else 0 end) AS `is_ended`,(case when (`crm`.`status` = 0) then 1 else 0 end) AS `is_under_review`,(case when (`crm`.`status` = 2) then 1 else 0 end) AS `is_rejected` from (`cms_registration_merchant` `crm` left join `cms_topic` `ct` on((`crm`.`topic_id` = `ct`.`id`))) where (`crm`.`del_flag` = 0) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-18 15:35:34
