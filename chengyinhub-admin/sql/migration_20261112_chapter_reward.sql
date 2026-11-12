-- D-82 (2026-11-11): chapter-level reward line shown on the player's chapter end card.
-- Author-written display text only; nothing is issued from it. Apply before the candidate backend; no data rewrite.
SET @reward_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'reward_text') = 0,
    'ALTER TABLE cms_topic_chapter ADD COLUMN reward_text VARCHAR(64) NULL COMMENT ''D-82 chapter end-card reward text''', 'SELECT 1');
PREPARE reward_stmt FROM @reward_ddl;
EXECUTE reward_stmt;
DEALLOCATE PREPARE reward_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 1 FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter'
  AND column_name = 'reward_text' AND character_maximum_length = 64;
