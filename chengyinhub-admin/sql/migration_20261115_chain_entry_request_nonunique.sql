-- im_chain_entry.request_id is only the latest relation command (see migration_20261105_l8_chain_storage.sql:
-- "Separate immutable command history"). Command identity, idempotent replay and rejection of a rebind to another
-- member or payload belong to im_chain_command_receipt.uk_chain_command (chain_id, request_id). The extra
-- UNIQUE KEY uk_chain_entry_request on the entry pointer made a second member with the same request text fail
-- with a raw duplicate-key error (ChainPersistenceMySqlIT.sameRequestCannotBeReboundToAnotherMemberOrPayload),
-- and ChannelInteractionService scopes request ids per operator. Replace it with a plain lookup index.
-- Rerunnable: each step checks information_schema first. No data change.
SET @chain_entry_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='im_chain_entry' AND index_name='uk_chain_entry_request')>0,
 'ALTER TABLE im_chain_entry DROP INDEX uk_chain_entry_request', 'SELECT 1');
PREPARE chain_entry_stmt FROM @chain_entry_ddl;
EXECUTE chain_entry_stmt;
DEALLOCATE PREPARE chain_entry_stmt;
SET @chain_entry_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='im_chain_entry' AND index_name='idx_chain_entry_request')=0,
 'ALTER TABLE im_chain_entry ADD KEY idx_chain_entry_request (chain_id, request_id)', 'SELECT 1');
PREPARE chain_entry_stmt FROM @chain_entry_ddl;
EXECUTE chain_entry_stmt;
DEALLOCATE PREPARE chain_entry_stmt;
