-- ================= migration_20261129_channel_create_event_bit.sql =================
-- W7 x-channel-events (SOC-6): channel permission overwrites may carry CREATE_EVENT (bit 28).
--
-- ROLLBACK: first clear the bit, then put the old limit back:
--   UPDATE im_channel_permission_overwrite SET allow_bits = allow_bits & 67108863, deny_bits = deny_bits & 67108863;
--   ALTER TABLE im_channel_permission_overwrite DROP CHECK ck_overwrite_bits;
--   ALTER TABLE im_channel_permission_overwrite ADD CONSTRAINT ck_overwrite_bits
--     CHECK ((allow_bits & deny_bits) = 0 AND allow_bits <= 67108863 AND deny_bits <= 67108863);
--   The old jar never writes bit 28, so it runs on either limit.
--
-- The old CHECK capped both bit maps at bits 0-25 (67108863). The new one allows bits 0-25 plus bit 28
-- (335544319 = 0x13ffffff); bits 26 and 27 stay refused, as in ChannelCapability.WRITABLE_BITS.
-- Role permissions (im_guild_role.permissions) have no CHECK; the service validates them.
-- Idempotent: each step is guarded by information_schema.check_constraints.
-- Runs before the new jar (the old jar keeps working under the wider limit).

SET @ow_ck_old := (
    SELECT COUNT(*) FROM information_schema.check_constraints
      WHERE constraint_schema=DATABASE() AND constraint_name='ck_overwrite_bits'
        AND check_clause NOT LIKE '%335544319%');
SET @ow_ck_drop_sql := IF(@ow_ck_old=1,
    'ALTER TABLE im_channel_permission_overwrite DROP CHECK ck_overwrite_bits',
    'SELECT 1');
PREPARE ow_ck_drop_stmt FROM @ow_ck_drop_sql;
EXECUTE ow_ck_drop_stmt;
DEALLOCATE PREPARE ow_ck_drop_stmt;

SET @ow_ck_new := (
    SELECT COUNT(*) FROM information_schema.check_constraints
      WHERE constraint_schema=DATABASE() AND constraint_name='ck_overwrite_bits'
        AND check_clause LIKE '%335544319%');
SET @ow_ck_add_sql := IF(@ow_ck_new=0,
    'ALTER TABLE im_channel_permission_overwrite ADD CONSTRAINT ck_overwrite_bits CHECK ((allow_bits & deny_bits) = 0 AND (allow_bits | 335544319) = 335544319 AND (deny_bits | 335544319) = 335544319)',
    'SELECT 1');
PREPARE ow_ck_add_stmt FROM @ow_ck_add_sql;
EXECUTE ow_ck_add_stmt;
DEALLOCATE PREPARE ow_ck_add_stmt;

-- runner: assert=scalar-true
SELECT (SELECT COUNT(*) FROM information_schema.check_constraints
      WHERE constraint_schema=DATABASE() AND constraint_name='ck_overwrite_bits'
        AND check_clause LIKE '%335544319%')=1 AS ow_create_event_bit_ready;
