-- 2026-09-24《预制人生》第一站配置留下的两处生产数据问题(用户已同意:「跑 一起删」)。
--
-- 1) original_template_id 被旧前端写错:编辑自己的模板时,编辑页把「来源模板」写成了自己的 id
--    (147→147、149→149;154 是从 146 另存出来的副本→146)。这个字段本意是「引用了哪个模板库模板」,
--    写错后三条从「我的故事玩法草稿」里消失。前端已修(pages/publish/temp fillFormWithTemplateData),
--    这里把存量改回 0(自主创建)。可逆:改回原值即可。
-- 2) 146 被 154 取代(草稿现在用 154),146 无任何节点引用 → 软删 del_flag=2,可逆(改回 0)。
--
-- 守卫:只动这四个 id、归属商家 100092、仍是草稿、当前值正是写错的那个;任一不符则影响 0 行。
UPDATE cms_member_template
   SET original_template_id = 0, update_time = NOW()
 WHERE member_id = 100092
   AND draft_status = 0
   AND ((id = 147 AND original_template_id = 147)
     OR (id = 149 AND original_template_id = 149)
     OR (id = 154 AND original_template_id = 146));

UPDATE cms_member_template
   SET del_flag = 2, update_time = NOW()
 WHERE id = 146
   AND member_id = 100092
   AND draft_status = 0
   AND IFNULL(del_flag, 0) = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.template_id = 146);
