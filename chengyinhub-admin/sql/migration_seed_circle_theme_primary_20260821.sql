-- Questify《五个圈层自由探索主题》第一组：5个主题模板 + 每主题8个商家角色。
-- 前置：migration_circle_theme_batch2_20260821.sql、migration_topic_template_meta_20260726.sql。
-- 文档真源：Questify_五个圈层自由探索主题_每主题8个即插即用商家模板_20260821.md。
-- 每主题8个候选不是8店任务；城市实例只选3—4家，玩家任意顺序、跨天、自助记录任意2家。
-- 商家只绑定正常销售的现成供给：不要求新建套餐、不要求打折、不要求店员主持或核验。

INSERT INTO cms_topic (
    member_id, topic_type, product_type, circle_theme_code,
    name, subtitle, description, address_name,
    total_time, location_count, is_template, status, user_status, del_flag,
    create_by, create_time
)
SELECT 100000, 1, 2, r.theme_code, r.topic_name, r.subtitle, r.description,
       '由城市代理配置的本地商圈', 0, 3, 1, 1, 1, 0,
       'seed_circle_primary_20260821', NOW()
FROM (
    SELECT 'FITNESS' AS theme_code, '一起动起来' AS topic_name,
           '不办年卡，和朋友试几种更容易坚持的运动生活。' AS subtitle,
           '面向23—40岁运动新手与朋友；从8个商家角色中选3—4家，任意顺序、可跨天、自助记录任意2家。' AS description
    UNION ALL SELECT 'MIDLIFE', '四十正当时',
           '暂时放下妈妈的任务，和同龄朋友留一点时间给自己。',
           '面向38—50岁的妈妈朋友，不使用年龄焦虑表达；商家沿用现成服务，不开发妈妈专属低价套餐。'
    UNION ALL SELECT 'DATE', '今天认真约会',
           '不只是吃饭看电影，重新认识一次身边的人。',
           '面向情侣与夫妻；普通周末也能从3—4家正常供给中安排一次轻约会，不设置答题输赢。'
    UNION ALL SELECT 'FRIENDS', '朋友别只在群里',
           '三五好友的一次不赶场见面局。',
           '面向2—5人朋友小组；不是复杂团建或剧本杀，同一实例只选一种3—4家组合。'
    UNION ALL SELECT 'SHANGHAI', '上海生活切片',
           '不打卡地标，和朋友体验几种有上海味道的普通生活。',
           '面向游客、新居民和带朋友认识上海的本地居民；可跨区域、跨天、任意顺序完成。'
) r
WHERE NOT EXISTS (
    SELECT 1 FROM cms_topic t
    WHERE t.circle_theme_code = r.theme_code AND t.is_template = 1 AND t.del_flag = 0
);

INSERT INTO product_role (topic_id, slot, entity_type, entity_id, status)
SELECT t.id, 'CONTENT', 'PLATFORM', NULL, 1
FROM cms_topic t
WHERE t.circle_theme_code IN ('FITNESS', 'MIDLIFE', 'DATE', 'FRIENDS', 'SHANGHAI')
  AND t.is_template = 1 AND t.del_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM product_role r
      WHERE r.topic_id = t.id AND r.slot = 'CONTENT' AND r.status = 1
  );

INSERT INTO cms_topic_template_meta (
    topic_id, template_status, version, applicable_roles,
    audience_who, audience_moment, audience_need,
    player_promise, core_loop, evidence_level,
    risk_tags, risk_qualifications, risk_cancellation, risk_liability, risk_safety_notice,
    preview_before, preview_during, preview_after, author
)
SELECT t.id, 'EXPERIMENTAL', 'v1.0', 'MERCHANT,AGENT',
       CASE t.circle_theme_code
           WHEN 'FITNESS' THEN '23—40岁城市上班族、运动新手与朋友'
           WHEN 'MIDLIFE' THEN '38—50岁，希望为自己留出时间的妈妈朋友'
           WHEN 'DATE' THEN '恋爱中的情侣与夫妻'
           WHEN 'FRIENDS' THEN '2—5人的朋友小组'
           ELSE '游客、新居民与带朋友认识上海的本地居民'
       END,
       CASE t.circle_theme_code
           WHEN 'FITNESS' THEN '想和朋友比较并找到更容易坚持的运动生活'
           WHEN 'MIDLIFE' THEN '想从家庭角色切回个人兴趣与朋友关系'
           WHEN 'DATE' THEN '普通周末也想认真安排一次约会'
           WHEN 'FRIENDS' THEN '群里总说改天约，这次想轻松见面'
           ELSE '看过地标后，想体验上海普通生活'
       END,
       '从8个角色中看到本地3—4家互补供给，各店独立预约和购买，不赶路线。',
       '任意顺序、可跨天、可跳过一家；自助记录任意2家并完成一次平台轻互动后生成记忆卡。',
       CASE t.circle_theme_code
           WHEN 'FITNESS' THEN CONCAT_WS(CHAR(10), '出发前选最想坚持的一项', '自助记录任意2家', '完成后再次选择并生成变化卡')
           WHEN 'MIDLIFE' THEN CONCAT_WS(CHAR(10), '查看本地3—4家组合', '自助记录任意2家', '每人回答今天哪一刻最像我自己')
           WHEN 'DATE' THEN CONCAT_WS(CHAR(10), '一起从3—4家中自由选择', '自助记录任意2家', '双方只选一张约会问题卡')
           WHEN 'FRIENDS' THEN CONCAT_WS(CHAR(10), '每人先选今天最想做的事', '自助记录任意2家', '共同投出一个下次还会来')
           ELSE CONCAT_WS(CHAR(10), '自由选择上海生活切片', '自助记录任意2家', '从味道、穿搭、声音、夜色、人情中选关键词')
       END,
       'NONE',
       '供给真实性,营业时效,预约与退款边界,人数适配',
       '商家身份、正常经营、供给、价格、开放时段和行动入口必须可核实。',
       '各店沿用自己的预约、取消、改期与退款规则；平台不提供跨店统一退款。',
       '平台提供主题筛选与组织，不把玩家自助记录冒充商家到店、核销或成交。',
       '不要求商家新建套餐、必须打折、培训店员、现场主持或跨店协调。',
       '看到主题人群、当地3—4家供给与每家独立规则。',
       '玩家按自己的时间和顺序行动，可跨天、可跳过一家。',
       '自助记录任意2家并完成平台轻互动后生成记忆卡。',
       '平台内容组'
FROM cms_topic t
WHERE t.circle_theme_code IN ('FITNESS', 'MIDLIFE', 'DATE', 'FRIENDS', 'SHANGHAI')
  AND t.is_template = 1 AND t.del_flag = 0
  AND NOT EXISTS (SELECT 1 FROM cms_topic_template_meta m WHERE m.topic_id = t.id);

-- 40个候选角色。required=0：玩家不是八店全通关；terms_mode=TRAFFIC：无权益也能加入。
INSERT INTO cms_topic_chapter (
    topic_id, name, description, sort_id, category_id, required,
    circle_candidate_code, circle_role, circle_policy, circle_enabled, recruit_enabled,
    terms_mode, max_merchant, create_by, create_time
)
SELECT t.id, r.chapter_name, r.chapter_desc, r.sort_id,
       (SELECT c.id FROM sys_category c
        WHERE c.del_flag = 0 AND c.status = 1 AND c.type = 1
          AND c.category_name REGEXP r.category_pattern
        ORDER BY c.id LIMIT 1),
       -- 五个首批主题的专项校验由 topic.circle_theme_code 驱动；circle_policy 只存 batch2 既有专项策略码。
       0, r.candidate_code, r.circle_role, 'COMMON', 1, 1, 'TRAFFIC', 1,
       'seed_circle_primary_20260821', NOW()
FROM cms_topic t
JOIN (
    SELECT 'FITNESS' AS theme_code, 'F1' AS candidate_code, 1 AS sort_id, '身体起点' AS chapter_name, 'EXPERIENCE' AS circle_role, 'FITNESS' AS circle_policy, '健身|私教|运动' AS category_pattern, '绑定已有单次体测或私教体验课，找到基础训练入口。' AS chapter_desc
    UNION ALL SELECT 'FITNESS' AS theme_code, 'F2' AS candidate_code, 2, '出拳解压', 'EXPERIENCE', 'FITNESS', '拳击|搏击|泰拳|运动', '绑定已有零基础体验课，提供有情绪释放感的主体验。'
    UNION ALL SELECT 'FITNESS' AS theme_code, 'F3' AS candidate_code, 3, '柔韧恢复', 'REST', 'FITNESS', '瑜伽|普拉提|拉伸|运动', '绑定已有入门小班或单次课，降低新手压力。'
    UNION ALL SELECT 'FITNESS' AS theme_code, 'F4' AS candidate_code, 4, '一起攀或一起打', 'EXPERIENCE', 'FITNESS', '攀岩|羽毛球|网球|匹克球|运动', '绑定已有体验票、场地票或公开课，提供朋友共同参与的运动。'
    UNION ALL SELECT 'FITNESS' AS theme_code, 'F5' AS candidate_code, 5, '城市开跑', 'EXPRESSION', 'FITNESS', '跑步|骑行|运动|俱乐部', '绑定已有公开跑、公开骑行或常规活动，提供低门槛社交入口。'
    UNION ALL SELECT 'FITNESS' AS theme_code, 'F6' AS candidate_code, 6, '运动补给', 'REST', 'FITNESS', '轻食|健康餐|果昔|饮品', '绑定已有轻食、饮品或正常套餐，承接运动前后补给。'
    UNION ALL SELECT 'FITNESS' AS theme_code, 'F7' AS candidate_code, 7, '装备试一试', 'EXPRESSION', 'FITNESS', '运动服|跑鞋|户外|零售', '绑定已有试穿、足型测试或单品购买，帮助玩家找到合适装备。'
    UNION ALL SELECT 'FITNESS' AS theme_code, 'F8' AS candidate_code, 8, '放松一下', 'REST', 'FITNESS', '恢复|拉伸|按摩|健康', '绑定已有单次恢复或放松服务，形成运动后的恢复闭环。'

    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M1' AS candidate_code, 1, '安静喝一杯', 'REST', 'MIDLIFE', '咖啡|茶|饮品', '绑定已有饮品、下午茶或正常座位时段，供朋友集合和聊天。'
    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M2' AS candidate_code, 2, '今天穿给自己', 'EXPRESSION', 'MIDLIFE', '女装|鞋|包|服装|买手', '绑定已有试穿、搭配建议或正常商品，重新发现个人表达。'
    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M3' AS candidate_code, 3, '身体舒展开', 'EXPERIENCE', 'MIDLIFE', '瑜伽|普拉提|舞蹈|运动', '绑定已有入门小班或单次课，提供轻运动与身体状态。'
    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M4' AS candidate_code, 4, '状态焕新', 'EXPRESSION', 'MIDLIFE', '美发|美甲|护肤|造型|美容', '绑定已有单次服务，提供看得见的状态改变。'
    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M5' AS candidate_code, 5, '重新读点自己的', 'EXPERIENCE', 'MIDLIFE', '书|文化|阅读', '绑定已有书单、讲座或正常活动，从家庭角色切回个人兴趣。'
    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M6' AS candidate_code, 6, '做一件小作品', 'EXPERIENCE', 'MIDLIFE', '花|陶艺|手作|绘画', '绑定已有常规体验课，朋友共同完成一件东西。'
    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M7' AS candidate_code, 7, '留一张现在的照片', 'EXPRESSION', 'MIDLIFE', '摄影|写真|自拍|拍贴', '绑定已有单人或朋友合照服务，留下这一阶段的自己。'
    UNION ALL SELECT 'MIDLIFE' AS theme_code, 'M8' AS candidate_code, 8, '晚一点回家', 'REST', 'MIDLIFE', '餐|酒吧|饮品|小酒馆', '绑定已有晚餐、饮品或正常套餐，承接成年朋友的晚间社交。'

    UNION ALL SELECT 'DATE' AS theme_code, 'C1' AS candidate_code, 1, '先坐下来聊', 'REST', 'DATE', '咖啡|茶|饮品', '绑定已有双人饮品或正常消费，作为约会开场。'
    UNION ALL SELECT 'DATE' AS theme_code, 'C2' AS candidate_code, 2, '替对方选一件', 'EXPRESSION', 'DATE', '服装|饰品|眼镜|买手', '绑定已有商品或正常试戴试穿，产生轻互动和了解。'
    UNION ALL SELECT 'DATE' AS theme_code, 'C3' AS candidate_code, 3, '选一种彼此的味道', 'EXPRESSION', 'DATE', '香氛|护肤|生活方式', '绑定已有试香或正常商品，形成感官记忆。'
    UNION ALL SELECT 'DATE' AS theme_code, 'C4' AS candidate_code, 4, '带一束回家', 'EXPRESSION', 'DATE', '花|植物', '绑定已有花束或小型商品，给约会一个可带走的象征。'
    UNION ALL SELECT 'DATE' AS theme_code, 'C5' AS candidate_code, 5, '一起做一件东西', 'EXPERIENCE', 'DATE', '陶艺|银饰|烘焙|绘画|手作', '绑定已有常规双人或单人体验，提供共同完成感。'
    UNION ALL SELECT 'DATE' AS theme_code, 'C6' AS candidate_code, 6, '吃一个甜点', 'REST', 'DATE', '甜品|烘焙|饮品', '绑定已有甜点或饮品，提供低门槛停留。'
    UNION ALL SELECT 'DATE' AS theme_code, 'C7' AS candidate_code, 7, '留下今天', 'EXPERIENCE', 'DATE', '自拍|摄影|拍贴|照相', '绑定已有拍摄服务，形成共同记忆。'
    UNION ALL SELECT 'DATE' AS theme_code, 'C8' AS candidate_code, 8, '最后一杯', 'REST', 'DATE', '酒吧|小酒馆|饮品', '绑定已有饮品或正常座位，作为约会收尾和聊天。'

    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P1' AS candidate_code, 1, '集合第一杯', 'REST', 'FRIENDS', '咖啡|茶|饮品', '绑定已有饮品或正常套餐，承担集合与等人。'
    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P2' AS candidate_code, 2, '一起吃点好的', 'REST', 'FRIENDS', '餐|轻食|小吃|饭', '绑定已有菜品或正常套餐，提供稳定的共同消费。'
    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P3' AS candidate_code, 3, '朋友互相挑', 'EXPRESSION', 'FRIENDS', '服装|古着|饰品', '绑定已有商品和正常试穿，产生自然聊天和互选。'
    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P4' AS candidate_code, 4, '玩一局', 'EXPERIENCE', 'FRIENDS', '桌游|棋牌|游戏', '绑定已有小时票或常规项目，提供轻竞争和停留。'
    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P5' AS candidate_code, 5, '唱一会儿', 'EXPERIENCE', 'FRIENDS', 'KTV|音乐|娱乐', '绑定已有包间或开放麦活动，提供情绪释放。'
    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P6' AS candidate_code, 6, '一起做一件', 'EXPERIENCE', 'FRIENDS', '陶艺|画室|烘焙|手作', '绑定已有常规体验，形成共同作品。'
    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P7' AS candidate_code, 7, '拍一张新的', 'EXPRESSION', 'FRIENDS', '自拍|拍贴|摄影', '绑定已有多人拍摄服务，更新朋友共同照片。'
    UNION ALL SELECT 'FRIENDS' AS theme_code, 'P8' AS candidate_code, 8, '最后一轮', 'REST', 'FRIENDS', '酒吧|小酒馆|饮品', '绑定已有饮品或正常桌位，延长聊天和收尾。'

    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S1' AS candidate_code, 1, '上海早点', 'REST', 'SHANGHAI', '早餐|小吃|烘焙', '绑定已有早餐或招牌单品，作为城市日常的开始。'
    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S2' AS candidate_code, 2, '城市咖啡时间', 'REST', 'SHANGHAI', '咖啡|烘焙|饮品', '绑定已有咖啡或正常套餐，体现上海高密度咖啡生活。'
    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S3' AS candidate_code, 3, '穿一点上海', 'EXPRESSION', 'SHANGHAI', '服装|古着|旗袍|设计', '绑定已有商品和正常试穿，连接城市审美与穿搭。'
    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S4' AS candidate_code, 4, '翻几页城市', 'EXPERIENCE', 'SHANGHAI', '书|杂志|文化|阅读', '绑定已有书刊或正常活动，连接城市文化。'
    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S5' AS candidate_code, 5, '带走一件本地设计', 'EXPRESSION', 'SHANGHAI', '文创|设计|手作|零售', '绑定已有本地设计商品，形成非标准纪念品。'
    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S6' AS candidate_code, 6, '吃一口本地味道', 'REST', 'SHANGHAI', '本帮菜|小吃|甜品|餐', '绑定已有菜品或正常套餐，提供城市味觉。'
    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S7' AS candidate_code, 7, '留下一张街头感', 'EXPERIENCE', 'SHANGHAI', '自拍|拍贴|摄影', '绑定已有拍摄服务，形成城市记忆。'
    UNION ALL SELECT 'SHANGHAI' AS theme_code, 'S8' AS candidate_code, 8, '上海夜里见', 'EXPERIENCE', 'SHANGHAI', '酒吧|小酒馆|音乐|演出', '绑定已有饮品、演出或正常座位，连接上海夜生活。'
) r ON r.theme_code = t.circle_theme_code
WHERE t.is_template = 1 AND t.del_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM cms_topic_chapter ch
      WHERE ch.topic_id = t.id AND ch.circle_candidate_code = r.candidate_code
  );

-- 回读：五个主题各8个候选；城市副本由小程序8选3—4后调用服务端统一门禁。
-- SELECT t.circle_theme_code, COUNT(*) FROM cms_topic t
-- JOIN cms_topic_chapter ch ON ch.topic_id=t.id
-- WHERE t.is_template=1 AND t.circle_theme_code IN ('FITNESS','MIDLIFE','DATE','FRIENDS','SHANGHAI')
-- GROUP BY t.circle_theme_code;
