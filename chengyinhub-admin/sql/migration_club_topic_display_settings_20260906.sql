-- 俱乐部对自己主题的三个开关(Figma J4「主题 · 俱乐部设置」)。
-- 三个默认值都选「与加这三列之前的行为逐字等价」的那一侧,存量主题不因为这次迁移改变可见性:
--   club_coop_open=1   加列前不存在这个闸,等价于一直开着
--   club_pinned=0      加列前没有置顶概念
--   club_member_only=0 加列前所有人都看得到
-- MySQL 8 的 ADD COLUMN 是 INSTANT,不重建表、不锁写。
ALTER TABLE cms_topic
  ADD COLUMN club_coop_open   TINYINT(1) NOT NULL DEFAULT 1 COMMENT '俱乐部开关:1=本主题出现在商家可承接列表里',
  ADD COLUMN club_pinned      TINYINT(1) NOT NULL DEFAULT 0 COMMENT '俱乐部开关:1=在俱乐部主页「活动」tab 排最前',
  ADD COLUMN club_member_only TINYINT(1) NOT NULL DEFAULT 0 COMMENT '俱乐部开关:1=只对俱乐部成员可见';

-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'cms_topic'
     AND column_name IN ('club_coop_open', 'club_pinned', 'club_member_only')) = 3
  AND
  -- 负向断言:默认值必须是上面那三个,写反了会静默改变存量主题的可见性
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'cms_topic'
     AND column_name = 'club_coop_open' AND column_default = '1') = 1
  AND
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'cms_topic'
     AND column_name IN ('club_pinned', 'club_member_only') AND column_default = '0') = 2
  AS ok;
