-- ============================================================================
-- 门店 AI 形象的声音:npc_profile 上挂音色
--
-- 为什么挂在 npc_profile 而不是新开一张表:一店一形象一音色,是 1:1 关系。
--   单独开表要多一次 join、多一处外键、多一条「形象删了音色还在」的孤儿路径,
--   换不来任何东西。等出现「一个形象多个音色」的需求再拆不迟。
--
-- 音色是这么来的:商家录一段话 → 上传得到音频 URL → 调阿里云百炼的声音复刻
--   (voice-enrollment)→ 拿到 voice_id → 存进这里。之后合成时带上它。
--
-- ⚠️ voice_status 必须存,不能只存 voice_id:
--   百炼创建音色后有 DEPLOYING → OK/UNDEPLOYED 的过程,不是立刻可用。
--   只看 voice_id 有没有值,会在音色还在部署时就拿去合成,得到一个失败的请求 ——
--   而失败长得像「服务抖了一下」,没人会想到是音色还没就绪。
--
-- 幂等:ALTER 前先查 information_schema,重复执行不报错。
-- ============================================================================

SET @db := DATABASE();

-- voice_id:百炼返回的音色标识,形如 cosyvoice-v2-chengyin-xxxxxxxx。
-- 长度按官方最长形态留足;它是外部系统的 id,不要自作主张截短。
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE npc_profile ADD COLUMN voice_id VARCHAR(96) NULL COMMENT ''声音复刻音色 id(阿里云百炼 voice-enrollment 返回)''',
    'SELECT ''npc_profile.voice_id 已存在'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'voice_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- voice_status:0=未配置 1=复刻中 2=可用 3=复刻失败。
-- 默认 0 而不是 NULL —— 读路径按 =2 判可用,NULL 参与比较的结果是 NULL 不是 false,
-- 一不留神就会写出永远取不到数据的条件。
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE npc_profile ADD COLUMN voice_status TINYINT NOT NULL DEFAULT 0 COMMENT ''0=未配置 1=复刻中 2=可用 3=复刻失败''',
    'SELECT ''npc_profile.voice_status 已存在'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'voice_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- voice_sample:商家录的那段音频。留着是为了音色要重建时不用再麻烦商家录一遍
-- (换模型、复刻失败重试都会用到)。
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE npc_profile ADD COLUMN voice_sample VARCHAR(255) NULL COMMENT ''商家录制的音频 URL,用于重建音色''',
    'SELECT ''npc_profile.voice_sample 已存在'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'npc_profile' AND COLUMN_NAME = 'voice_sample');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
