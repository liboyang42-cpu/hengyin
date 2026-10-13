-- ============================================================================
-- 「小队竞速点亮」补上「小队」—— 开启 multiplayer
--
-- 背景:20260825 那份只补了「竞速」(timer + leaderboard),「小队」留了缺口,
--   理由是当时没实跑验证过无队伍玩家的行为。现在验过了,结论要修正:
--
--   ★ 服务端**没有**优雅降级 —— 之前判断「无队伍时降级为 OWNER_PLAYER」是错的。
--     AdvancedGameRuntimeServiceImpl.inferTeamId 直接抛:
--       · activityId<=0(自玩) → 「队伍玩法仅支持活动场次」
--       · 活动内但没队伍     → 「请先加入本场队伍」
--     两条都在 start 就挡住,不会建出一个永远完不成的会话。
--     由 AdvancedGameRuntimeServiceImplTest 的两条用例锁死这个契约。
--
--   ★ 「要队伍」是既定设计,不是缺陷:#813(migration_seed_merchant_game_a8/b27_20260823)
--     已经有 18 个启用 multiplayer 的模板在跑,组队流程齐全(pages/team/join、
--     team/detail、报名页选队)。本文件照它们的成例配,不自创形状。
--
-- 形状对齐已上线的 18 个:ROLE_BASED / 2–4 人 / AUTO / 2 角色 / turnOrder 2。
--   requiredTurns=2:两个角色各行动一次即成局,比 #813 的 3–4 轻,
--   因为本玩法的主轴是「竞速」(计时+排名),多人只是编队,不是多轮剧情。
--
-- AUTO 分配的覆盖性(校验器会逐个人数验一遍,配错直接抛):
--   2 人 → 领跑1/收尾1 · 3 人 → 2/1 · 4 人 → 2/2,均落在每角色 [1,2] 内。
--
-- ★ 权衡说明(必须写清,别让下一个人以为是漏配):
--   开了 multiplayer 之后,**自玩场景和无队伍玩家将无法进入这个玩法**。
--   这是「小队」二字的直接代价,与 #813 的 18 个模板一致。
--   若日后要让单人也能玩,正确做法是另做一个单人变体模板,
--   而不是在运行时给多人玩法加降级 —— 后者会同时改变那 18 个模板的行为。
--
-- ⚠️ 前置依赖:migration_20260822_advanced_game_runtime.sql(advanced_config_json 列)。
--   与 20260825 那份**不构成依赖**:本文件写的是整份配置(含 timer/leaderboard),
--   无论 0825 跑没跑过,结果都一致 —— 这样避免「必须先跑另一份」的隐式顺序要求。
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- runner: requires=transaction
-- ============================================================================

-- 预检(执行前人工跑):看当前配置里有没有 multiplayer
--   SELECT id, title,
--          JSON_EXTRACT(advanced_config_json,'$.timer.enabled')       AS timer_on,
--          JSON_EXTRACT(advanced_config_json,'$.multiplayer.enabled') AS multi_on
--     FROM cms_template_library
--    WHERE member_id = 100000 AND del_flag = 0 AND title = '小队竞速点亮';

-- ★ 不用 SET @var:迁移执行器的 --transaction 安全白名单只放行
--   CREATE/DROP TEMPORARY TABLE + INSERT + 带 WHERE 的 UPDATE,
--   `SET @x = ...` 会在连库前被直接拒绝(ci/run_migration.py:validate_transaction_statements)。
--   单行 JSON 也便于 Java 测试逐字提取去跑校验器。
CREATE TEMPORARY TABLE team_race_config (
    title  VARCHAR(255) NOT NULL PRIMARY KEY,
    config JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO team_race_config (title, config) VALUES
    ('小队竞速点亮', '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":5400,"timeoutResult":"EXPIRED"},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"TOPIC","limit":50},"multiplayer":{"enabled":true,"mode":"ROLE_BASED","minPlayers":2,"maxPlayers":4,"assignment":"AUTO","requiredTurns":2,"roles":[{"id":"pacer","label":"领跑","min":1,"max":2},{"id":"closer","label":"收尾","min":1,"max":2}],"turnOrder":["pacer","closer"]}}');

-- ① 货架投影:商家采用时,发布表单预填会带走这份配置
--    幂等键 = 「还没配 multiplayer」,重复执行不覆盖运营后续的人工调整。
UPDATE cms_template_library l
  JOIN team_race_config c ON c.title = l.title
   SET l.advanced_config_json = c.config
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND (l.advanced_config_json IS NULL
        OR JSON_EXTRACT(l.advanced_config_json, '$.multiplayer.enabled') IS NULL);

-- ② 官方正本:play 读的是 cms_member_template,只写投影玩家看不到
--    (12packs 那批只灌了库表,这里命中 0 行属正常;留着是为了口径一致、将来补正本时不漏。)
UPDATE cms_member_template m
  JOIN team_race_config c ON c.title = m.title
   SET m.advanced_config_json = c.config
 WHERE m.member_id = 100000
   AND m.del_flag = 0
   AND (m.advanced_config_json IS NULL
        OR JSON_EXTRACT(m.advanced_config_json, '$.multiplayer.enabled') IS NULL);

-- ③ 存量商家副本:只回填「从本库行采用而来」且「还没配 multiplayer」的副本
-- ★ 第三段必须拆成两步:--transaction 白名单里唯一允许的多表 UPDATE 形状是
--   「单条 INNER JOIN + 单个等值 ON」(ci/run_migration.py 的 _TRANSACTION_UPDATE),
--   双 JOIN 会在连库前被拒,整份迁移一句都跑不了。先把库行 id 解析进第二张临时表,
--   再用单 JOIN 回填。
CREATE TEMPORARY TABLE team_race_targets (
    library_id BIGINT NOT NULL PRIMARY KEY,
    config     JSON   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO team_race_targets (library_id, config)
SELECT l.id, c.config
  FROM cms_template_library l
  JOIN team_race_config c ON c.title = l.title
 WHERE l.member_id = 100000
   AND l.del_flag = 0;

UPDATE cms_member_template m
  JOIN team_race_targets c ON c.library_id = m.original_template_id
   SET m.advanced_config_json = c.config
 WHERE m.del_flag = 0
   AND (m.advanced_config_json IS NULL
        OR JSON_EXTRACT(m.advanced_config_json, '$.multiplayer.enabled') IS NULL);

DROP TEMPORARY TABLE team_race_targets;

DROP TEMPORARY TABLE team_race_config;

-- 回读验证(期望 timer/leaderboard/multiplayer 三者均为 true):
--   SELECT title,
--          JSON_EXTRACT(advanced_config_json,'$.timer.enabled')       AS timer_on,
--          JSON_EXTRACT(advanced_config_json,'$.leaderboard.enabled') AS board_on,
--          JSON_EXTRACT(advanced_config_json,'$.multiplayer.enabled') AS multi_on,
--          JSON_EXTRACT(advanced_config_json,'$.multiplayer.minPlayers') AS min_p
--     FROM cms_template_library
--    WHERE member_id = 100000 AND del_flag = 0 AND title = '小队竞速点亮';
--
-- ⚠️ 回读只证明「配置写进去了」。真正算数的观测是:在活动场次里组一个 2 人队,
--    进这个节点,确认弹出的是高级玩法且能分到角色 —— 而不是看库里那行 JSON。
--
-- 负控(确认门禁真会红):把 minPlayers 改成 1,再跑
--   AdvancedGameConfigValidatorTest,应抛「多人玩法人数须为 2 至 20 人」。
--
-- 回滚(退回到只有竞速、没有小队的状态):
--   UPDATE cms_template_library
--      SET advanced_config_json = JSON_REMOVE(advanced_config_json, '$.multiplayer')
--    WHERE member_id = 100000 AND del_flag = 0 AND title = '小队竞速点亮';
--   UPDATE cms_member_template
--      SET advanced_config_json = JSON_REMOVE(advanced_config_json, '$.multiplayer')
--    WHERE del_flag = 0 AND title = '小队竞速点亮';
