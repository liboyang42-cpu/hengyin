-- 主办方取消活动:新增取消态两列
--
-- 背景:cms_activity 原有三个状态字段都不表达「已取消且需退款给已报名者」——
--   status        = 后台审核态(0待审/1通过/2失败)
--   publish_status= 上下架(0下架/1上架)
--   del_flag      = 删除(0存在/2删除)
-- 删除与下架都只是「列表不出」,对已存在的 cms_registration 零影响:主办方今天删掉一个有人
-- 付款的活动,活动查不到了,而用户的钱还在平台、报名还在 —— 这是个活的资损/客诉口子。
--
-- 语义:cancel_time IS NOT NULL 即「已取消」。取消动作会把该活动下所有【已付款且未核销】的
-- 报名整单全额退款(0 手续费),并把活动下架(publish_status=0)。
--
-- MySQL 8:ADD COLUMN 走 INSTANT 算法,秒级、不锁表、不重建。
-- ⚠️ 禁用 ADD COLUMN IF NOT EXISTS(MariaDB 专属语法,MySQL 8 报 1064)—— 靠执行前 --describe 预检幂等。
-- ⚠️ COMMENT 内不得出现分号:run_migration.py 按 `;` 切语句且不认字符串字面量,
--    带分号的 COMMENT 会被从中间劈成两条(第一条引号未闭合 → 1064)。此坑由 --dry-run 发现。
-- 可逆:两列均可空、无默认值约束,回滚用 DROP COLUMN。

ALTER TABLE cms_activity
    ADD COLUMN cancel_time DATETIME NULL COMMENT '取消时间,非空=活动已被主办方取消(已对未核销报名全额退款)' AFTER publish_status;

ALTER TABLE cms_activity
    ADD COLUMN cancel_reason VARCHAR(255) NULL COMMENT '取消原因(主办方填写,展示给已报名用户)' AFTER cancel_time;
