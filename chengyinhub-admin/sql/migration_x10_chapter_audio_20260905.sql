-- ============================================================================
-- X10:音频从「故事流里的一个块」升成「章节的一个属性」
--
-- 变更前:音频是 cms_topic_chapter.blocks 里的一项({"type":"audio","url":...}),
--   可以插在故事流任意位置、一章能插任意多段。玩家端按它在故事流里的位置渲染成
--   一个可点的播放块(pages/play/index.wxml 的 .chfull__audio)。
--
-- 变更后(用户 2026-09-04 拍板,2026-09-06 修订为「两者并存」):
--   · 章节多出一段**背景旁白** cms_topic_chapter.audio_url —— 进本章自动播放,没有位置;
--   · 故事流里**仍然可以插带位置的音频块**(用户 2026-09-06:「章节一个音频,里面节点一个音频,
--     不冲突」)。所以 blocks 的白名单保持 text / node / image / audio 四种。
--
-- ⚠️ 本文件因此**只加一列,不动 blocks**。
--    9-05 的初版曾要把 audio 块从 blocks 里剔除、第一段升到章节列 —— 那是「音频只能有一处」
--    那一版的做法,已按 9-06 的裁决作废。**存量 audio 块原样留在故事流里**,
--    章节级那段是新的、独立的一段,不从存量里迁。
--
-- ⚠️ 列名是 blocks 不是 blocks_json —— Java 侧属性叫 blocksJson,
--    映射在 CmsTopicChapterMapper.xml:12。写 SQL 按列名来。
--
-- ⚠️ 为什么本文件要先于后端 jar 落库:
--    ChapterFlowCompiler 改完之后就不再接受 type=audio 的块;把旧数据里的音频
--    升到章节列靠的就是这里。顺序反了会出现「旧数据读不出、新列还不存在」的空窗。
--
-- ★ 存量:不迁、不动。故事流里已有的 audio 块继续按它原来的位置播;
--   audio_url 是新加的一段,存量章节一律为 NULL(= 没有背景旁白),由作者自己去补。
--
-- ✅ 顺带:初版那两个雷(改写 blocks 会与 TopicSaleCanonicalV1 的开售快照对不上、
--    一章多段会丢数据)**随之全部消失** —— 本文件现在只做一次 ADD COLUMN。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 第 1 步:加列。MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,先查后加:
--   下面这条返回 0 行时才执行 ALTER;返回 1 行表示列已存在,应停止。
-- ---------------------------------------------------------------------------
SELECT column_name, data_type, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'cms_topic_chapter'
  AND column_name = 'audio_url';

ALTER TABLE cms_topic_chapter
  ADD COLUMN audio_url VARCHAR(512) NULL COMMENT '本章背景旁白;玩家进入本章自动播放,一章最多一段' AFTER atmosphere_preset;

-- ---------------------------------------------------------------------------
-- 第 2 步:回读断言 —— 列真的加上了,且存量一律为 NULL(本迁移不写任何值)
-- ---------------------------------------------------------------------------
-- SELECT COUNT(*) AS should_be_1 FROM information_schema.columns
--  WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'audio_url';
-- SELECT COUNT(*) AS should_be_0 FROM cms_topic_chapter WHERE audio_url IS NOT NULL;
-- ⚠️ 故事流里的 audio 块不受影响,顺带确认一下没被谁动过:
-- SELECT COUNT(*) AS unchanged FROM cms_topic_chapter WHERE blocks LIKE '%"type":"audio"%';
