-- BCrypt hashes contain 60 characters; retain wider columns and all existing password values.
-- runner: assert=scalar-true
SELECT COUNT(*)=1 AND SUM(data_type='varchar' AND extra='' AND character_set_name IS NOT NULL AND collation_name IS NOT NULL)=1
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member' AND column_name='password';
SET @member_password_width_ddl = (SELECT IF(character_maximum_length >= 100, 'SELECT 1',
 CONCAT('ALTER TABLE ums_member MODIFY COLUMN password VARCHAR(100) CHARACTER SET ', character_set_name,
 ' COLLATE ', collation_name, IF(is_nullable='YES',' NULL',' NOT NULL'),
 IF(column_default IS NULL, IF(is_nullable='YES',' DEFAULT NULL',''), CONCAT(' DEFAULT ',QUOTE(column_default))),
 ' COMMENT ',QUOTE(column_comment)))
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member' AND column_name='password');
PREPARE member_password_width_stmt FROM @member_password_width_ddl;
EXECUTE member_password_width_stmt;
DEALLOCATE PREPARE member_password_width_stmt;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 AND SUM(data_type='varchar' AND character_maximum_length >= 100)=1
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member' AND column_name='password';
