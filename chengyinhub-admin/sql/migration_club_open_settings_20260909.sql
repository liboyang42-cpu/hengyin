-- 俱乐部「开放设置」三个开关。Figma ★俱乐部·全流程 M 管理页(268:223)「开放设置」组。
-- 本文件仅为迁移草案。执行前必须走 chengyin-db-migrate 只读预检。
--
-- 稿上那一组是四个开关,这里只加三列 —— 第四个「加入需审批」已有 club.join_policy,
-- 不重复加(2026-09-09 已 --describe club 核实过)。
--
-- ⚠️ 不要拿 can_merchant_coop 当「开放商家承接」用。它是**主理人申请表里的能力自述**
--   (club/apply 里的「谈商家合作 · 找店、谈条件、对接执行」,在 club-card 上展示成
--   「能对接商家」标签)。把一句自我介绍改成一道功能闸,会让所有没勾过它的存量俱乐部
--   突然关掉商家承接。
--
-- ★ 三个默认值都取「与加列之前行为逐字等价」的那一侧,存量俱乐部不因为这次迁移改变行为。
--   逐条核实过现状,不是猜的:
--     public_visible=1          /api/club/list 现在只按 status=1 过滤,没有任何可见性闸
--     merchant_undertake_open=1 俱乐部级现在没有总闸(主题级 cms_topic.club_coop_open 另有其闸)
--     member_post_allowed=1     发帖现在只看 canSeeMembers(isOwner || isJoined),成员都能发
--   写反任何一个,都会静默改变存量俱乐部的可见性或权限 —— 建表成功不代表建对。

-- 重复/半迁移预检:必须返回 0 行;有结果即停止,不得继续 ALTER。
SELECT column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'club'
  AND column_name IN ('public_visible', 'merchant_undertake_open', 'member_post_allowed');

ALTER TABLE club
    ADD COLUMN public_visible          TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '俱乐部公开可见:0仅成员能搜到和打开 1所有人(M 稿「俱乐部公开可见」)',
    ADD COLUMN merchant_undertake_open TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '开放商家承接(俱乐部级总闸):0本俱乐部主题一律不进商家可承接列表 1按主题各自的闸',
    ADD COLUMN member_post_allowed     TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '允许成员发帖:0仅管理员可发 1成员都可发(M 稿「允许成员发帖」)';

-- 落库后回读:三列都在,且默认值必须逐个等于上面写的那一侧。
-- 只查「列存在」是不够的 —— 默认值写反了列也照样建得出来。
SELECT column_name, column_default, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'club'
  AND column_name IN ('public_visible', 'merchant_undertake_open', 'member_post_allowed');
