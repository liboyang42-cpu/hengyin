-- Keep message identity/sent time; approval creates a separate visibility time.
SET @im_visible_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='im_message' AND column_name='visible_at');
SET @im_visible_ddl = IF(@im_visible_exists=0,
    'ALTER TABLE im_message ADD COLUMN visible_at DATETIME(6) NULL COMMENT ''First visible time; pending media stays NULL''', 'SELECT 1');
PREPARE im_visible_stmt FROM @im_visible_ddl;
EXECUTE im_visible_stmt;
DEALLOCATE PREPARE im_visible_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='im_message' AND column_name='visible_at' AND data_type='datetime'
    AND datetime_precision=6 AND is_nullable='YES';

SET @im_read_visible_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='im_conversation_member' AND column_name='last_read_visible_at');
SET @im_read_visible_ddl = IF(@im_read_visible_exists=0,
    'ALTER TABLE im_conversation_member ADD COLUMN last_read_visible_at DATETIME(6) NULL COMMENT ''Snapshot of last read visibility time''', 'SELECT 1');
PREPARE im_read_visible_stmt FROM @im_read_visible_ddl;
EXECUTE im_read_visible_stmt;
DEALLOCATE PREPARE im_read_visible_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='im_conversation_member' AND column_name='last_read_visible_at'
    AND data_type='datetime' AND datetime_precision=6 AND is_nullable='YES';

START TRANSACTION;
UPDATE im_message SET visible_at=create_time
WHERE visible_at IS NULL AND (msg_type != 2 OR check_status=1);
UPDATE im_conversation_member recipient LEFT JOIN im_message cutoff
    ON cutoff.id=recipient.last_read_msg_id AND cutoff.conversation_id=recipient.conversation_id
SET recipient.last_read_visible_at=COALESCE(cutoff.visible_at,cutoff.create_time,'1970-01-01 00:00:00.000000')
WHERE recipient.last_read_visible_at IS NULL;
UPDATE im_conversation_member recipient
SET unread_count=(SELECT COUNT(*) FROM im_message msg
    WHERE msg.conversation_id=recipient.conversation_id AND msg.sender_id != recipient.member_id
      AND msg.status != 2 AND (msg.msg_type != 2 OR msg.check_status=1)
      AND (COALESCE(msg.visible_at,msg.create_time)>recipient.last_read_visible_at
           OR (COALESCE(msg.visible_at,msg.create_time)=recipient.last_read_visible_at
               AND msg.id>COALESCE(recipient.last_read_msg_id,0))))
WHERE recipient.deleted=0;
COMMIT;
-- runner: assert=scalar-true
SELECT COUNT(*)=0 FROM im_conversation_member WHERE last_read_visible_at IS NULL;
