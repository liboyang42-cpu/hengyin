-- 可选历史回填:把"已是俱乐部身份的会员"过去发的城市定向主题(票种 mode=1)归到其俱乐部
-- 执行方式:走 chengyin-db-migrate(宝塔终端 mysql 本机),只读预检后确认。非必须,仅为让历史城市定向团也显示在俱乐部下。
-- 前置:migration_club_topic_link.sql(cms_topic.club_id)已落库。
-- 注:城市定向票种在 oms_ticket 用 owner_type=1(主题)+owner_id=主题id+mode=1 标识(无 topic_id 列)。

-- 预检(只读):看会命中多少行
-- SELECT COUNT(*) FROM cms_topic t
--   JOIN club c ON c.member_id = t.member_id AND c.del_flag = 0
--   WHERE t.del_flag = 0 AND t.club_id IS NULL
--     AND EXISTS (SELECT 1 FROM oms_ticket k WHERE k.owner_type = 1 AND k.owner_id = t.id AND k.mode = 1 AND k.del_flag = 0);

-- 回填:
UPDATE cms_topic t
  JOIN club c ON c.member_id = t.member_id AND c.del_flag = 0
  SET t.club_id = c.id
  WHERE t.del_flag = 0 AND t.club_id IS NULL
    AND EXISTS (SELECT 1 FROM oms_ticket k WHERE k.owner_type = 1 AND k.owner_id = t.id AND k.mode = 1 AND k.del_flag = 0);
