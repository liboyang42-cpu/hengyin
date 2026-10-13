-- 2026-09-22《预制人生》第一站实配走查(台账 W-10)留下的孤儿草稿模板清理。
-- 背景:走查时从发布页存下「新生儿登记」草稿(id=140),绑定丢失后重配成 141,140 无任何节点引用。
-- 用户已同意清理(2026-09-22「另外几件事也要全部处理」)。软删:del_flag=2,可逆(改回 0)。
-- 守卫:只动 id=140、归属商家 100092、仍是草稿、未删除、且没有节点引用它 —— 任一不符则影响 0 行。
UPDATE cms_member_template
   SET del_flag = 2, update_time = NOW()
 WHERE id = 140
   AND member_id = 100092
   AND draft_status = 0
   AND IFNULL(del_flag, 0) = 0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_node n WHERE n.template_id = 140);
