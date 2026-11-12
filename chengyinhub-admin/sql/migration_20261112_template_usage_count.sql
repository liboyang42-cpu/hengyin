-- D89: persisted popularity is distinct first adopters, never clicks or receipt attempts.
-- Run in the release write-pause: the application takes canonical -> library locks before inserting copies.
-- Existing personal copies (including drafts and deleted copies) are the existing durable dedupe identity.
SET @template_use_num_exists = (SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='cms_template_library' AND column_name='use_num');
SET @template_use_num_ddl = IF(@template_use_num_exists=0,
 'ALTER TABLE cms_template_library ADD COLUMN use_num INT NOT NULL DEFAULT 0 COMMENT ''D89 distinct first adopters''', 'SELECT 1');
PREPARE template_use_num_stmt FROM @template_use_num_ddl;
EXECUTE template_use_num_stmt;
DEALLOCATE PREPARE template_use_num_stmt;

UPDATE cms_template_library library
LEFT JOIN (SELECT original_template_id, COUNT(DISTINCT member_id) AS adopters
 FROM cms_member_template WHERE original_template_id > 0 AND member_id > 0
 GROUP BY original_template_id) adopted ON adopted.original_template_id=library.id
SET library.use_num=IFNULL(adopted.adopters,0)
WHERE NOT (library.use_num <=> IFNULL(adopted.adopters,0));
ALTER TABLE cms_template_library MODIFY COLUMN use_num INT NOT NULL DEFAULT 0 COMMENT 'D89 distinct first adopters';

-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='cms_template_library' AND column_name='use_num'
 AND data_type='int' AND is_nullable='NO' AND column_default='0'
 AND column_comment='D89 distinct first adopters';
