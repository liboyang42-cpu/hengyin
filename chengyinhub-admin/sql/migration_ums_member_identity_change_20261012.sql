-- migration_ums_member_identity_change_20261012.sql
-- X036 发布者实名改绑申请单(自助提交 + 平台人工审核 + 不可变审计)。
-- 走 chengyin-db-migrate:只读预检 `--describe ums_member_identity_change` 确认表不存在后执行。
-- 必须先于引用它的 jar 上线 —— 没有这张表,改绑申请/进度接口直接 500,但登记与发布闸不受影响。
--
-- 【这张表回答什么】「这个发布者申请把实名换成什么、谁在什么时候审的、结果如何」。
-- 当前生效的实名仍只在 ums_member_identity 一张表(发布闸唯一事实来源),本表只是申请单。
-- 审核通过时由服务端把新密文写回 ums_member_identity 并清空本行密文,旧证随之失效。
--
-- 【PII 口径】real_name / id_card 与 ums_member_identity 同宽同格式(AES-GCM 密文,v1: 前缀),
-- 只在 PENDING 期间保留,一旦进入终态(通过/驳回/撤回)即置 NULL —— 申请单不长期囤积第二份证件密文。
-- id_card_hash / old_id_card_hash 是 HMAC 盲索引,供人工核对「新证是否已绑其他账号」与事后追溯,
-- 不能还原号码。任何接口都不回显姓名与号码。
--
-- 【pending_member_id 为什么单独一列】一个账号同一时刻只允许一张待审单。
-- PENDING 时等于 member_id,终态时置 NULL —— 唯一键对 NULL 不生效,于是历史单可以有多张,
-- 待审单只能有一张,靠库级唯一键挡住双击/并发重复提交,不靠先查后插。
--
-- 【没有 FK】本仓一贯不建外键。
-- 【注释与字面量禁 ASCII 分号】半角分号会被按分号拆句的执行器劈开,一律用中文逗号。

CREATE TABLE ums_member_identity_change (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  member_id         BIGINT       NOT NULL COMMENT '申请人 ums_member.id,只能改自己的实名',
  real_name         VARCHAR(768) NULL     COMMENT '申请的新姓名,AES-GCM 密文,终态后置 NULL',
  id_card           VARCHAR(768) NULL     COMMENT '申请的新身份证号,AES-GCM 密文,终态后置 NULL',
  id_card_hash      CHAR(64)     NOT NULL COMMENT '新身份证号 HMAC 盲索引,人工核对是否已绑其他账号',
  old_id_card_hash  CHAR(64)     NULL     COMMENT '提交时生效证件的盲索引,追溯「从哪张证改到哪张证」',
  reason            VARCHAR(200) NOT NULL COMMENT '申请人填写的变更原因,不得包含证件号',
  status            VARCHAR(16)  NOT NULL COMMENT 'PENDING APPROVED REJECTED CANCELLED',
  pending_member_id BIGINT       NULL     COMMENT 'PENDING 时等于 member_id,终态置 NULL,保证一人一张待审单',
  review_note       VARCHAR(200) NULL     COMMENT '审核意见,驳回时展示给申请人',
  reviewer_user_id  BIGINT       NULL     COMMENT '审核人 sys_user.user_id',
  review_time       DATETIME     NULL,
  create_time       DATETIME     NOT NULL,
  update_time       DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_identity_change_pending (pending_member_id),
  KEY idx_identity_change_member (member_id, create_time),
  KEY idx_identity_change_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布者实名改绑申请单 人工审核 密文仅在待审期保留';

-- 后台审核权限按钮(F 类型,不建页面菜单,后台审核页尚未做)。parent_id 先挂 0,可按实际菜单树调整。
-- 上线后由管理员在角色管理里把 business:identity:review 授给负责实名审核的人。幂等:perms 已存在则跳过。
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '实名改绑审核', 0, 99, '', NULL, '', 1, 0,
       'F', '0', '0', 'business:identity:review', '#', 'admin', now(), 'X036 发布者实名改绑人工审核'
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE perms = 'business:identity:review' LIMIT 1) e);

-- 结构读回
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name = 'ums_member_identity_change' AND engine = 'InnoDB') = 1
  AND EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ums_member_identity_change'
      AND index_name = 'uk_identity_change_pending' AND non_unique = 0 AND column_name = 'pending_member_id')
  AS assertion_ok;
