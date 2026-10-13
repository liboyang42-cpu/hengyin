-- 刚到这座城·新生活装机计划实验主题骨架。
-- 前置：migration_20260820_new_city_setup_plan.sql 与 migration_topic_template_meta_20260726.sql。
-- 前置：migration_seed_new_city_lifestyle_category_20260821.sql(G5 品类;2026-08-21 预检发现线上品类零命中后补)。
-- P0 外部验证尚未回读，故主题固定为待审/未上架、模板状态 EXPERIMENTAL，不能改成已发布。

INSERT INTO cms_topic (
    member_id, topic_type, product_type, complete_rule_json, name, subtitle, description,
    address_name, total_time, location_count, is_template, status, user_status, merchant_status,
    lifecycle, del_flag, create_by, create_time)
SELECT 100000, 1, 2, JSON_OBJECT('distinctMerchantRoles',3,'needGroups',2),
       '刚到这座城·新生活装机计划', '把陌生城市装成自己的生活系统',
       '用序章和四个首轮商家角色完成无对错偏好题、跨店标签接力与新生活操作系统结果聚合。首轮仅验证G4收纳到G5生活感的space_constraints强接力。',
       '玩家当前城市与3km生活圈', 1800, 4, 1, 0, 0, 0, 0, 0,
       'seed_new_city_setup_20260820', NOW()
  FROM DUAL
 WHERE NOT EXISTS (
       SELECT 1 FROM cms_topic WHERE name='刚到这座城·新生活装机计划' AND del_flag=0);

INSERT INTO product_role(topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
  FROM cms_topic t
 WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM product_role r
                    WHERE r.topic_id=t.id AND r.slot='CONTENT' AND r.status=1);

INSERT INTO cms_topic_template_meta(
    topic_id, template_status, version, applicable_roles,
    audience_who, audience_moment, audience_need, player_promise, core_loop, evidence_level,
    preview_before, preview_during, preview_after, author)
SELECT t.id, 'EXPERIMENTAL', 'mvp-20260820', 'MERCHANT,AGENT',
       '来到当前城市90天内、正在搭建稳定生活系统的新住民',
       '刚搬来、生活信息分散、尚未形成可信本地锚点时',
       '用可解释偏好结果与少量真实商家锚点完成吃饭、收纳、生活感和第三地点的首轮安装',
       '完成3个不同商家角色并覆盖至少2个需求组，得到仅自己可见的新生活操作系统。',
       CONCAT_WS(CHAR(10),'完成序章偏好校准','进入一个真实商家角色','完成无对错偏好题','逐项确认可继承标签','下一家只读取当次所需标签','聚合节点结果与7/30天行动'),
       'NONE',
       '进入序章前说明标签用途、接收方与撤回权',
       '每站只下发该节点声明消费的已确认标签；缺标签改走现场替代题',
       '完成3角色/2需求组后生成仅自己可见的结果页',
       '平台内容组'
  FROM cms_topic t
 WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_template_meta m WHERE m.topic_id=t.id);

INSERT INTO cms_topic_chapter(
    topic_id, name, description, sort_id, category_id, required, need_group,
    recruit_enabled, terms_mode, max_merchant, allowed_validation_methods, create_by, create_time)
SELECT t.id, r.chapter_name, r.chapter_desc, r.sort_id,
       CASE WHEN r.category_pattern IS NULL THEN NULL ELSE (
           SELECT c.id FROM sys_category c
            WHERE c.del_flag=0 AND c.status=1 AND c.type=1
              AND c.category_name REGEXP r.category_pattern ORDER BY c.id LIMIT 1) END,
       r.required, r.need_group, r.recruit_enabled, 'TRAFFIC', r.max_merchant,
       r.allowed_validation_methods, 'seed_new_city_setup_20260820', NOW()
  FROM cms_topic t
  JOIN (
       SELECT 'G0 开机序章' chapter_name, 0 sort_id, 0 required, NULL need_group,
              0 recruit_enabled, 0 max_merchant, NULL category_pattern, '6' allowed_validation_methods,
              '平台序章：完成新住民偏好校准，不绑定商家、不参与核销角色计数。' chapter_desc
       UNION ALL SELECT 'G2 吃饭安装', 1, 1, 1, 1, 6, '餐|食|饭|烘焙|轻食', '6',
              '用真实营业、预算、过敏原与非进食替代方案形成可行动的吃饭锚点。'
       UNION ALL SELECT 'G4 收纳安装', 2, 1, 2, 1, 6, '收纳|家居|家政|整理', '6',
              '产出space_constraints档位，玩家确认后允许G5按需继承。'
       UNION ALL SELECT 'G5 生活感安装', 3, 1, 2, 1, 6, '花|书|咖啡|家居|生活', '6',
              '消费已确认的space_constraints；缺失或撤回时走现场替代题且不降低完成资格。'
       UNION ALL SELECT 'G6 第三地点安装', 4, 1, 3, 1, 6, '咖啡|书店|酒吧|空间|运动', '6',
              '形成可反复到访的第三地点锚点与7/30天行动。'
  ) r
 WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter ch
                    WHERE ch.topic_id=t.id AND ch.name=r.chapter_name);

INSERT INTO cms_topic_chapter_template_meta(
    chapter_id, topic_id, role_need, exclusivity_mode, exclusivity_scope,
    exclusivity_period, exclusivity_reason, effort_prep_hours, effort_staff_minutes,
    effort_venue, effort_materials, effort_available_slots, effort_needs_booking,
    effort_cancel_cost, effort_qualifications)
SELECT ch.id, ch.topic_id,
       CASE WHEN ch.name='G0 开机序章' THEN '平台内容章，无商家承接'
            ELSE CONCAT('只配置本角色的偏好题、结果分支、匹配建议与必要标签消费；不得读取玩家全量标签。') END,
       CASE WHEN ch.name='G0 开机序章' THEN 'NONE' ELSE 'HARD' END,
       '同一主题生活圈内的同一角色', '本实验期',
       '同角色可有多个候选商家，但一家商家在本主题首轮只承接一个角色。',
       0.5, 0, '商家现有营业空间', '不要求新增物料', '按真实营业时段', 0,
       '商家可暂停；标签撤回后下游必须改走替代题。',
       CASE WHEN ch.name='G2 吃饭安装' THEN '须确认样品批次、过敏原展示与非进食替代流程'
            ELSE '按现有经营范围提供真实信息，不作专业诊断或保证' END
  FROM cms_topic_chapter ch
  JOIN cms_topic t ON t.id=ch.topic_id
 WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
   AND NOT EXISTS (SELECT 1 FROM cms_topic_chapter_template_meta m WHERE m.chapter_id=ch.id);

-- 回读：必须为 1主题/5章节/5章节元数据；主题仍为待审未上架。
-- runner: assert=scalar-true
SELECT (
    (SELECT COUNT(*) FROM cms_topic
      WHERE name='刚到这座城·新生活装机计划' AND del_flag=0
        AND status=0 AND user_status=0
        AND CAST(JSON_UNQUOTE(JSON_EXTRACT(complete_rule_json,'$.distinctMerchantRoles')) AS UNSIGNED)=3
        AND CAST(JSON_UNQUOTE(JSON_EXTRACT(complete_rule_json,'$.needGroups')) AS UNSIGNED)=2) = 1
    AND
    (SELECT COUNT(*) FROM cms_topic_chapter ch
      JOIN cms_topic t ON t.id=ch.topic_id
      WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0) = 5
    AND
    (SELECT COUNT(*) FROM cms_topic_chapter ch
      JOIN cms_topic t ON t.id=ch.topic_id
      WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
        AND ch.terms_mode='TRAFFIC' AND ch.allowed_validation_methods='6'
        AND ((ch.name='G0 开机序章' AND ch.sort_id=0 AND ch.required=0 AND ch.need_group IS NULL
              AND ch.recruit_enabled=0 AND ch.max_merchant=0 AND ch.category_id IS NULL)
          OR (ch.name='G2 吃饭安装' AND ch.sort_id=1 AND ch.required=1 AND ch.need_group=1
              AND ch.recruit_enabled=1 AND ch.max_merchant=6
              AND ch.category_id=(SELECT c.id FROM sys_category c
                    WHERE c.del_flag=0 AND c.status=1 AND c.type=1
                      AND c.category_name REGEXP '餐|食|饭|烘焙|轻食' ORDER BY c.id LIMIT 1))
          OR (ch.name='G4 收纳安装' AND ch.sort_id=2 AND ch.required=1 AND ch.need_group=2
              AND ch.recruit_enabled=1 AND ch.max_merchant=6
              AND ch.category_id=(SELECT c.id FROM sys_category c
                    WHERE c.del_flag=0 AND c.status=1 AND c.type=1
                      AND c.category_name REGEXP '收纳|家居|家政|整理' ORDER BY c.id LIMIT 1))
          OR (ch.name='G5 生活感安装' AND ch.sort_id=3 AND ch.required=1 AND ch.need_group=2
              AND ch.recruit_enabled=1 AND ch.max_merchant=6
              AND ch.category_id=(SELECT c.id FROM sys_category c
                    WHERE c.del_flag=0 AND c.status=1 AND c.type=1
                      AND c.category_name REGEXP '花|书|咖啡|家居|生活' ORDER BY c.id LIMIT 1))
          OR (ch.name='G6 第三地点安装' AND ch.sort_id=4 AND ch.required=1 AND ch.need_group=3
              AND ch.recruit_enabled=1 AND ch.max_merchant=6
              AND ch.category_id=(SELECT c.id FROM sys_category c
                    WHERE c.del_flag=0 AND c.status=1 AND c.type=1
                      AND c.category_name REGEXP '咖啡|书店|酒吧|空间|运动' ORDER BY c.id LIMIT 1)))) = 5
    AND
    (SELECT COUNT(*) FROM cms_topic_template_meta tm
      JOIN cms_topic t ON t.id=tm.topic_id
      WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
        AND tm.del_flag=0 AND tm.template_status='EXPERIMENTAL'
        AND tm.version='mvp-20260820' AND tm.evidence_level='NONE') = 1
    AND
    (SELECT COUNT(*) FROM product_role r
      JOIN cms_topic t ON t.id=r.topic_id
      WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
        AND r.slot='CONTENT' AND r.entity_type='PLATFORM'
        AND r.entity_id IS NULL AND r.status=1) = 1
    AND
    (SELECT COUNT(*) FROM cms_topic_chapter_template_meta m
      JOIN cms_topic_chapter ch ON ch.id=m.chapter_id
      JOIN cms_topic t ON t.id=ch.topic_id
      WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
        AND m.del_flag=0 AND m.topic_id=t.id) = 5
    AND
    (SELECT COUNT(DISTINCT m.chapter_id) FROM cms_topic_chapter_template_meta m
      JOIN cms_topic_chapter ch ON ch.id=m.chapter_id
      JOIN cms_topic t ON t.id=ch.topic_id
      WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
        AND m.del_flag=0 AND m.topic_id=t.id) = 5
    AND
    (SELECT COUNT(*) FROM cms_topic_chapter_template_meta m
      JOIN cms_topic_chapter ch ON ch.id=m.chapter_id
      JOIN cms_topic t ON t.id=ch.topic_id
      WHERE t.name='刚到这座城·新生活装机计划' AND t.del_flag=0
        AND m.del_flag=0 AND m.topic_id=t.id
        AND m.exclusivity_scope='同一主题生活圈内的同一角色'
        AND m.exclusivity_period='本实验期'
        AND m.effort_prep_hours=0.5 AND m.effort_staff_minutes=0
        AND m.effort_needs_booking=0
        AND ((ch.name='G0 开机序章' AND m.exclusivity_mode='NONE'
              AND m.role_need='平台内容章，无商家承接')
          OR (ch.name='G2 吃饭安装' AND m.exclusivity_mode='HARD'
              AND m.role_need='只配置本角色的偏好题、结果分支、匹配建议与必要标签消费；不得读取玩家全量标签。'
              AND m.effort_qualifications='须确认样品批次、过敏原展示与非进食替代流程')
          OR (ch.name IN ('G4 收纳安装','G5 生活感安装','G6 第三地点安装')
              AND m.exclusivity_mode='HARD'
              AND m.role_need='只配置本角色的偏好题、结果分支、匹配建议与必要标签消费；不得读取玩家全量标签。'
              AND m.effort_qualifications='按现有经营范围提供真实信息，不作专业诊断或保证'))) = 5
) AS migration_assertion;
