-- Store only the immutable moderated asset identity for replay, never a vendor bearer URL.
SET @npc_audio_asset_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='npc_chat_request' AND column_name='audio_asset_id');
SET @npc_audio_asset_ddl=IF(@npc_audio_asset_exists=0,'ALTER TABLE npc_chat_request ADD COLUMN audio_asset_id BIGINT NULL','SELECT 1');
PREPARE npc_audio_statement FROM @npc_audio_asset_ddl;
EXECUTE npc_audio_statement;
DEALLOCATE PREPARE npc_audio_statement;
-- runner: assert=scalar-true
SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='npc_chat_request' AND column_name='audio_asset_id' AND data_type='bigint' AND is_nullable='YES';
