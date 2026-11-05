-- Optional opening/ending videos. Apply before the candidate backend; no data rewrite.
SET @video_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'video_url') = 0,
    'ALTER TABLE cms_topic_chapter ADD COLUMN video_url VARCHAR(512) NULL COMMENT ''Opening/ending reviewed video''', 'SELECT 1');
PREPARE video_stmt FROM @video_ddl;
EXECUTE video_stmt;
DEALLOCATE PREPARE video_stmt;
SET @poster_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter' AND column_name = 'video_poster_url') = 0,
    'ALTER TABLE cms_topic_chapter ADD COLUMN video_poster_url VARCHAR(512) NULL COMMENT ''Reviewed video poster frame''', 'SELECT 1');
PREPARE poster_stmt FROM @poster_ddl;
EXECUTE poster_stmt;
DEALLOCATE PREPARE poster_stmt;

-- runner: assert=scalar-true
SELECT COUNT(*) = 2 FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cms_topic_chapter'
  AND column_name IN ('video_url', 'video_poster_url') AND character_maximum_length = 512;
