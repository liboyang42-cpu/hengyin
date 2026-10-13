-- ============================================================================
-- 给「标题字面承诺了机制、代码里却零实现」的官方玩法补上 advanced_config_json
--
-- 病灶(同一类,不是同一个):
--   · 小队竞速点亮  validation_method=5(GPS 到达)
--   · G0｜30秒生活掉线测试 / G0｜30秒今天值得什么 / G3｜三小时时间方块 /
--     G5｜十分钟步行上限 / G7｜60秒共同落款  validation_method=0(到点即完成)
--   pages/play/index.js normNode:isGame = hasAdvanced || vm ∈ {1,2,3,4,6},0/5 被明确排除。
--   于是这些标题里写着「竞速」「30 秒」「三小时」「十分钟」「60 秒」的玩法,玩家走到点
--   就自动打卡 —— 没有计时、没有排名,而且不报错、零告警。
--
-- ★ 只补「标题已经写死了时长/竞速」的 6 个,不碰其余 vm=0 的模板:
--   生活备份 §5.1 统一合同明确「人工判分/解释:0」,庆祝的其余几个由仪式板
--   (cms_topic_template_meta.play_kind='BOARD')承载,都不走节点流。
--   给它们加机制属于发明产品,本迁移不做。
--
-- 修法不改代码:客户端已完整支持 vm=0/5 + 高级玩法 ——
--   pages/play/index.js onAdvancedReady 专门分支处理 vm===0||vm===5,
--   高级流程跑完后回到详情卡并走 onSheetGoTap()(到达路径)。
--   hasAdvanced 由 advancedConfigJson 里任一机制 enabled 推出。
--
-- ★ 为什么要写三张表(只写货架投影 = 玩家一辈子看不到):
--   TemplatePublishServiceImpl.copyCanonicalToProjection 的方向是
--   cms_member_template(正本)→ cms_template_library(货架投影),play 读的是**正本**。
--   ① 投影:商家在模板市场看到并采用时,发布表单预填带走 advancedConfigJson
--      (xcx pages/publish/temp/index.js:1778 → TemplatePublishServiceImpl:399)。
--   ② 官方正本:生活备份 / 庆祝两批 seed 本来就往 cms_member_template 写了 member_id=100000
--      的正本行,官方主题直接读它。只写投影的话官方局照旧没机制。
--   ③ 存量商家副本:已经采用过的商家,其副本 advanced_config_json 仍是 NULL,
--      不回填就永远拿不到 —— 这类「新配置只对未来生效」的静默半修复最难发现。
--      只回填 original_template_id 指向本批库行、且商家从未自行配置(IS NULL)的副本。
--
-- ★ 不开 multiplayer:多人要求活动场次 + play_team(AdvancedGameRuntimeServiceImpl
--   「队伍玩法仅支持活动场次」);无队伍时降级为 OWNER_PLAYER,但 state.multiplayer
--   仍会被建出来而 roles 为空,客户端 requiredTurns 闸(components/cy/advanced-game
--   /index.js)可能把单人卡在未完成态。该路径未实跑验证,不在货架模板上默认打开。
--   ⇒「竞速/计时」本次修好;「小队」仍是缺口,需队伍流程落地后另开。
--
-- timeoutResult 一律 EXPIRED(不是 FAILED):超时会话可被 expireIfNeeded → restart 重开,
--   玩家中途走开不至于把这一关判死。
-- leaderboard.scope 用 TOPIC 不用 ACTIVITY:货架模板会被商家复制到自玩场景(activity_id=0),
--   ACTIVITY 范围在自玩下没有意义。
--
-- 配置结构对齐 AdvancedGameConfigValidator(schemaVersion=1),由
--   AdvancedGameConfigValidatorTest#seededNamedMechanicConfigsAreValid 把本文件里
--   **每一条** JSON 逐字读出来跑校验器。
--
-- ⚠️ 前置依赖:migration_20260822_advanced_game_runtime.sql(advanced_config_json 列)。
--    已在 ci/migration-dependencies.json 声明,不只写在注释里。
-- ⚠️ 本文件只准备,不执行。生产库写操作由总控在用户点头后执行(chengyin-db-migrate)。
-- 不走 --transaction:窄事务白名单只收严格 INSERT/UPDATE 与临时表,不收多表
-- UPDATE ... JOIN(runner 无法确认它到底动了哪些表的引擎,拒绝是对的)。
-- 三条 UPDATE 各自带 advanced_config_json IS NULL 守卫,本来就幂等:中途失败重跑即收敛。
-- ============================================================================

-- 预检(执行前人工跑):应回 6 行,advanced_config_json 均为 NULL
--   SELECT id, title, validation_method, advanced_config_json FROM cms_template_library
--    WHERE member_id = 100000 AND del_flag = 0
--      AND title IN ('小队竞速点亮','G0｜30秒生活掉线测试','G0｜30秒今天值得什么',
--                    'G3｜三小时时间方块','G5｜十分钟步行上限','G7｜60秒共同落款');

CREATE TEMPORARY TABLE named_mechanic_config (
    title  VARCHAR(255) NOT NULL PRIMARY KEY,
    config JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- JSON 写单行字面量:MySQL 8 写入 JSON 列时会校验并规范化。
-- (不要用 JSON_COMPACT —— 那是 MariaDB 的函数,MySQL 8 没有。)
-- 单行也是为了让 Java 测试逐条提取去跑校验器,不必解析 SQL。
INSERT INTO named_mechanic_config (title, config) VALUES
    ('小队竞速点亮',           '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":5400,"timeoutResult":"EXPIRED"},"leaderboard":{"enabled":true,"metric":"ELAPSED_TIME","scope":"TOPIC","limit":50}}'),
    ('G0｜30秒生活掉线测试',   '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":30,"timeoutResult":"EXPIRED"}}'),
    ('G0｜30秒今天值得什么',   '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":30,"timeoutResult":"EXPIRED"}}'),
    ('G3｜三小时时间方块',     '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":10800,"timeoutResult":"EXPIRED"}}'),
    ('G5｜十分钟步行上限',     '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":600,"timeoutResult":"EXPIRED"}}'),
    ('G7｜60秒共同落款',       '{"schemaVersion":1,"timer":{"enabled":true,"durationSeconds":60,"timeoutResult":"EXPIRED"}}');

-- ① 货架投影:商家在模板市场采用时,预填会带走这份配置
UPDATE cms_template_library l
  JOIN named_mechanic_config c ON c.title = l.title
   SET l.advanced_config_json = c.config
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND l.advanced_config_json IS NULL;

-- ② 官方正本:play 读的是 cms_member_template,只写投影等于玩家看不到
UPDATE cms_member_template m
  JOIN named_mechanic_config c ON c.title = m.title
   SET m.advanced_config_json = c.config
 WHERE m.member_id = 100000
   AND m.del_flag = 0
   AND m.advanced_config_json IS NULL;

-- ③ 存量商家副本:只回填「从本批库行采用而来」且「商家从未自行配置」的副本。
--    不加 original_template_id 这层限制会误伤同名的无关模板。
UPDATE cms_member_template m
  JOIN cms_template_library l ON l.id = m.original_template_id
  JOIN named_mechanic_config c ON c.title = l.title
   SET m.advanced_config_json = c.config
 WHERE l.member_id = 100000
   AND l.del_flag = 0
   AND m.del_flag = 0
   AND m.advanced_config_json IS NULL;

DROP TEMPORARY TABLE named_mechanic_config;

-- 原来的回读只写在下面的注释里 —— 那是给人看的,不是门禁。改成可执行断言:
-- 六个标题承诺了机制的官方玩法,库行与正本行都必须真的拿到 timer 配置。
-- runner: assert=scalar-true
SELECT (
  (SELECT COUNT(*) FROM cms_template_library
    WHERE member_id = 100000 AND del_flag = 0
      AND advanced_config_json IS NOT NULL
      AND JSON_EXTRACT(advanced_config_json, '$.timer.enabled') = TRUE) >= 6
  AND
  (SELECT COUNT(*) FROM cms_member_template
    WHERE member_id = 100000 AND del_flag = 0
      AND advanced_config_json IS NOT NULL
      AND JSON_EXTRACT(advanced_config_json, '$.timer.enabled') = TRUE) >= 6
) AS named_mechanics_have_config;

-- 回读验证(期望 6 行库行 + 对应正本行,timer 均为 true):
--   SELECT 'library' AS src, title,
--          JSON_EXTRACT(advanced_config_json,'$.timer.enabled') AS timer_on,
--          JSON_EXTRACT(advanced_config_json,'$.timer.durationSeconds') AS secs
--     FROM cms_template_library
--    WHERE member_id=100000 AND del_flag=0 AND advanced_config_json IS NOT NULL
--   UNION ALL
--   SELECT 'member', title,
--          JSON_EXTRACT(advanced_config_json,'$.timer.enabled'),
--          JSON_EXTRACT(advanced_config_json,'$.timer.durationSeconds')
--     FROM cms_member_template
--    WHERE member_id=100000 AND del_flag=0 AND advanced_config_json IS NOT NULL;
--
-- ⚠️ 回读只证明「配置写进去了」,不证明「玩家真进了高级玩法」。后者要在小程序里
--    实走一遍该节点,确认弹出的是高级玩法而不是到达即完成。
--
-- 负控(确认门禁真会红):把任一条 durationSeconds 改成 5,再跑
--   AdvancedGameConfigValidatorTest,应抛「计时时长须为 10 秒至 24 小时」。
--
-- 回滚(只清本批写进去的那 6 份配置,不碰商家自己配的):
--   UPDATE cms_template_library SET advanced_config_json = NULL
--    WHERE member_id=100000 AND del_flag=0
--      AND title IN ('小队竞速点亮','G0｜30秒生活掉线测试','G0｜30秒今天值得什么',
--                    'G3｜三小时时间方块','G5｜十分钟步行上限','G7｜60秒共同落款');
--   UPDATE cms_member_template SET advanced_config_json = NULL
--    WHERE del_flag=0
--      AND title IN ('小队竞速点亮','G0｜30秒生活掉线测试','G0｜30秒今天值得什么',
--                    'G3｜三小时时间方块','G5｜十分钟步行上限','G7｜60秒共同落款');
