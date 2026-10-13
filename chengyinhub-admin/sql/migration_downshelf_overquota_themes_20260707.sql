-- 一次性数据清理:下架超额在架主题,对齐 T1「上架额度后端强制」的存量数据
-- 背景:T1(db210f9a)上线前,部分商家在架公开主题数已超过其角色 max_themes(merchant=3)。
--       T1 只拦新增,不回溯下架存量 → 需一次性清理。
-- 规则:每超额会员保留「最近 3 个」在架(create_time desc),其余下架。
-- 影响:仅改 cms_topic.user_status(1→0),可逆(重新上架即恢复);不删数据、不动支付/报名。
-- 预检(只读)确认的具体主题(2026-07-07):
--   member 100000 城瘾Hub(merchant,有效max=3):保留 id 8/7/6,下架 id 5/4/3/2
--   member 100092 城市运动装备馆(merchant;club_leader 能力 status=0 已停用不计,有效max=3):保留 22/21/20,下架 19/18/9
-- 仅按预检确认的具体 id 下架,避免任何 WHERE 误伤。

UPDATE cms_topic SET user_status = 0
WHERE user_status = 1 AND id IN (5, 4, 3, 2, 19, 18, 9);
