-- ============================================================================
-- S3 自由探索"途中台词" v1 种子数据(npc_line_cache 表已存在,本文件只灌数据,不建表)
-- ⚠️ 只是文件,进部署 backlog;不在生产库直接执行,生产库改动走 chengyin-db-migrate 由用户确认后跑。
--
-- 背景(先侦察结论,详见 S3-report.md):
--   npc_profile 没有"topic"这个 scope_type——scope_type 只有 1=剧本角色(挂activityId)/
--   2=门店分身(挂merchantId)/3=全局城瘾NPC(scope_id固定0,业务保证只有一条 enabled)。
--   自由探索 mode2 的会话是 topicId 自玩(activity_id=0,无活动挂靠)，没有天然的
--   topic→profile 外键，因此本 v1 的"途中台词" NPC 统一挂**全局 NPC(scope_type=3)**：
--   这与既有 NpcEventService/NpcChatService 的兜底逻辑完全一致，不是新造的关系。
--   活动会话(城市定向 mode1 走真实 activityId)则优先使用该活动挂的剧本角色
--   (scope_type=1, scope_id=activityId)；查不到再回落全局 NPC —— 这条路径由
--   ApiPlayProgressController#companionLine 在查询时处理，本文件只灌"全局 NPC"这一份种子。
--
-- event_type 新增取值(位置槽,非身份槽 after_X)：explore_first / explore_middle / explore_last
-- node_id = NULL 表示不分节点的通用途中台词。
-- ============================================================================

-- 执行前预检: SELECT id, name FROM npc_profile WHERE scope_type = 3 AND enabled = 1 AND del_flag = '0';
-- 若查不到全局 NPC(业务保证只有一条),需先在后台创建一条 scope_type=3 的 npc_profile,再跑本文件。

-- explore_first：路才刚开始
INSERT INTO npc_line_cache (profile_id, event_type, node_id, variants_json, audit_status, create_time, update_time)
SELECT p.id, 'explore_first', NULL,
       '["路才刚开始,慢慢逛","刚出发呢,不着急,这城市有的是故事","才第一步,后面还有更好玩的等着你"]',
       1, NOW(), NOW()
FROM npc_profile p
WHERE p.scope_type = 3 AND p.enabled = 1 AND p.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM npc_line_cache c
    WHERE c.profile_id = p.id AND c.event_type = 'explore_first' AND c.node_id IS NULL
  )
LIMIT 1;

-- explore_middle：走了一半
INSERT INTO npc_line_cache (profile_id, event_type, node_id, variants_json, audit_status, create_time, update_time)
SELECT p.id, 'explore_middle', NULL,
       '["走了一半啦,前面还有好东西","过半程了,脚下的路你已经认得不少","一半的故事收进包里了,剩下的继续走"]',
       1, NOW(), NOW()
FROM npc_profile p
WHERE p.scope_type = 3 AND p.enabled = 1 AND p.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM npc_line_cache c
    WHERE c.profile_id = p.id AND c.event_type = 'explore_middle' AND c.node_id IS NULL
  )
LIMIT 1;

-- explore_last：就快集齐了
INSERT INTO npc_line_cache (profile_id, event_type, node_id, variants_json, audit_status, create_time, update_time)
SELECT p.id, 'explore_last', NULL,
       '["就快集齐了,最后一程","收尾阶段,慢慢走完这最后几步","故事快拼完整了,别急,走到就是了"]',
       1, NOW(), NOW()
FROM npc_profile p
WHERE p.scope_type = 3 AND p.enabled = 1 AND p.del_flag = '0'
  AND NOT EXISTS (
    SELECT 1 FROM npc_line_cache c
    WHERE c.profile_id = p.id AND c.event_type = 'explore_last' AND c.node_id IS NULL
  )
LIMIT 1;
