-- 存量修复:选中玩法不支持限时,advanced_config_json 里却留着 timer.enabled=true(PR #1163 同一个病)。
--
-- 成因:旧版编辑器换玩法(如 排序→技能检定)不关限时,限时开关被 wx:if="{{timerAvailable}}"
-- 藏起来,作者看不见关不掉就存进了库。服务端 validateTimer 只查格式不查玩法,
-- AdvancedGameRuntimeServiceImpl.deadline() 照样给会话挂截止时间,到点 expireIfNeeded
-- 判 FAILED/EXPIRED —— 玩家真的会超时。PR #1163 修了编辑器(换玩法 + 打开旧模板都关),
-- 但作者不重新打开保存,库里这批就一直在生效。本迁移一次性收掉。
--
-- 「选中玩法」与前端 node-game-catalog.detectGame 同口径:按目录顺序取第一个 enabled 的玩法段
-- (qa 需 mode ∈ TYPE/PICK/SHOT;photoCheck 带 mode CARD = 拍物成卡)。不支持限时 = 不在 TIMED_GAMES:
--   predict random profile note photoCheck(含拍物成卡) steps check typeIn coinFlip diceRoll scan。
-- 一个玩法段都没开的(老链路,supportsTimer('') = true)不动。
-- enabled 判据与后端 enabled()=asBoolean(false) 对齐:JSON true / "true" / 1。
--
-- 连带:编辑器配的「血量低时多给 30 秒」(when = sys.hp LTE 3,relax 键 timer.durationSeconds)
-- 在限时关掉后会被前后端 validateRelax 拒(「relax 的字段所在玩法没有启用」),同一句里一并删掉。
-- 后台手配的放宽(条件不同)不动。编辑器每份配置至多一条这样的放宽,前置断言守住。
--
-- 两张表:cms_member_template(作者模板)、cms_template_library(货架副本,采用时被复制)。
-- 每表一句 UPDATE,单句原子;幂等,重跑命中 0 行。
-- 不改:已在运行的会话 game_runtime_session.config_snapshot_json(截止时间已挂上,下次重开取新配置)。
-- 纯数据修复,无 DDL,不依赖 jar,可随时落库。
--
-- 只读预检(落库前先跑,看量):
--   SELECT 'member' t, COUNT(*) FROM cms_member_template   WHERE <下方 WHERE 同款条件>
--   UNION ALL SELECT 'library', COUNT(*) FROM cms_template_library WHERE <同款条件>;

-- 前置:每行至多一条编辑器限时放宽(JSON_REMOVE 只删第一条;多条说明不是编辑器写的,人工看)。
-- runner: assert=scalar-true
SELECT NOT EXISTS (
  SELECT 1 FROM (
    SELECT t.id FROM cms_member_template t,
      JSON_TABLE(t.advanced_config_json, '$.variants[*]' COLUMNS (
        wvar VARCHAR(32) PATH '$.when.var', wop VARCHAR(16) PATH '$.when.op',
        wval DECIMAL(12,4) PATH '$.when.value',
        hit INT EXISTS PATH '$.relax."timer.durationSeconds"')) v
    WHERE JSON_VALID(t.advanced_config_json)
      AND v.wvar = 'sys.hp' AND v.wop = 'LTE' AND v.wval = 3 AND v.hit = 1
    GROUP BY t.id HAVING COUNT(*) > 1
    UNION ALL
    SELECT t.id FROM cms_template_library t,
      JSON_TABLE(t.advanced_config_json, '$.variants[*]' COLUMNS (
        wvar VARCHAR(32) PATH '$.when.var', wop VARCHAR(16) PATH '$.when.op',
        wval DECIMAL(12,4) PATH '$.when.value',
        hit INT EXISTS PATH '$.relax."timer.durationSeconds"')) v
    WHERE JSON_VALID(t.advanced_config_json)
      AND v.wvar = 'sys.hp' AND v.wop = 'LTE' AND v.wval = 3 AND v.hit = 1
    GROUP BY t.id HAVING COUNT(*) > 1
  ) dup
) AS assertion_ok;

UPDATE cms_member_template t
SET t.advanced_config_json = JSON_SET(
  COALESCE(
    (SELECT JSON_REMOVE(t.advanced_config_json, CONCAT('$.variants[', MIN(v.ord) - 1, ']'))
       FROM JSON_TABLE(t.advanced_config_json, '$.variants[*]' COLUMNS (
         ord FOR ORDINALITY,
         wvar VARCHAR(32) PATH '$.when.var', wop VARCHAR(16) PATH '$.when.op',
         wval DECIMAL(12,4) PATH '$.when.value',
         hit INT EXISTS PATH '$.relax."timer.durationSeconds"')) v
      WHERE v.wvar = 'sys.hp' AND v.wop = 'LTE' AND v.wval = 3 AND v.hit = 1
     HAVING COUNT(*) > 0),
    t.advanced_config_json),
  '$.timer.enabled', CAST('false' AS JSON))
WHERE JSON_VALID(t.advanced_config_json)
  AND JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.timer.enabled')) IN ('true', '1')
  AND (CASE
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.qa.enabled')) IN ('true', '1')
         AND JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.qa.mode')) IN ('TYPE', 'PICK', 'SHOT') THEN 'T'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.branch.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.sort.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.match.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.classify.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.estimate.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.pricePair.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.hiddenObject.enabled')) IN ('true', '1') THEN 'T'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.predict.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.random.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.profile.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.note.enabled')) IN ('true', '1')
      OR (JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.photoCheck.enabled')) IN ('true', '1')
          AND JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.photoCheck.mode')) = 'CARD')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.steps.enabled')) IN ('true', '1') THEN 'N'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.reaction.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.ballShake.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.quietHold.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.compass.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.shout.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.countdown.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.stopwatch.enabled')) IN ('true', '1') THEN 'T'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.photoCheck.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.check.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.typeIn.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.coinFlip.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.diceRoll.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.scan.enabled')) IN ('true', '1') THEN 'N'
    ELSE '' END) = 'N';

UPDATE cms_template_library t
SET t.advanced_config_json = JSON_SET(
  COALESCE(
    (SELECT JSON_REMOVE(t.advanced_config_json, CONCAT('$.variants[', MIN(v.ord) - 1, ']'))
       FROM JSON_TABLE(t.advanced_config_json, '$.variants[*]' COLUMNS (
         ord FOR ORDINALITY,
         wvar VARCHAR(32) PATH '$.when.var', wop VARCHAR(16) PATH '$.when.op',
         wval DECIMAL(12,4) PATH '$.when.value',
         hit INT EXISTS PATH '$.relax."timer.durationSeconds"')) v
      WHERE v.wvar = 'sys.hp' AND v.wop = 'LTE' AND v.wval = 3 AND v.hit = 1
     HAVING COUNT(*) > 0),
    t.advanced_config_json),
  '$.timer.enabled', CAST('false' AS JSON))
WHERE JSON_VALID(t.advanced_config_json)
  AND JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.timer.enabled')) IN ('true', '1')
  AND (CASE
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.qa.enabled')) IN ('true', '1')
         AND JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.qa.mode')) IN ('TYPE', 'PICK', 'SHOT') THEN 'T'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.branch.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.sort.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.match.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.classify.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.estimate.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.pricePair.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.hiddenObject.enabled')) IN ('true', '1') THEN 'T'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.predict.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.random.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.profile.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.note.enabled')) IN ('true', '1')
      OR (JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.photoCheck.enabled')) IN ('true', '1')
          AND JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.photoCheck.mode')) = 'CARD')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.steps.enabled')) IN ('true', '1') THEN 'N'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.reaction.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.ballShake.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.quietHold.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.compass.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.shout.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.countdown.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.stopwatch.enabled')) IN ('true', '1') THEN 'T'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.photoCheck.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.check.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.typeIn.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.coinFlip.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.diceRoll.enabled')) IN ('true', '1')
      OR JSON_UNQUOTE(JSON_EXTRACT(t.advanced_config_json, '$.scan.enabled')) IN ('true', '1') THEN 'N'
    ELSE '' END) = 'N';
