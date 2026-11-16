-- W7 x-city-monopoly (2026-11-16): three new City Monopoly switches, admin-tunable like the rest of city.monopoly.*.
--   city.monopoly.claim.attest_mode = REQUIRED (CITY-2; SIGNAL lets unverified devices claim, every such claim goes to a person)
--   city.monopoly.claim.photo.server_evidence_required = true (CITY-1; claim photo facts come only from the server check)
--   city.monopoly.legacy.convert = true (CITY-9; held LEGACY places on City Monopoly boards become City Monopoly properties)
-- Defaults live in citygame/city-monopoly-defaults.properties; CityMonopolyConfigDefaultsTest checks seeds = defaults.
-- Additive and idempotent (INSERT ... WHERE NOT EXISTS; an operator's value is never overwritten).
-- Rollback instructions:
--   DELETE FROM sys_config WHERE config_key IN ('city.monopoly.claim.attest_mode',
--     'city.monopoly.claim.photo.server_evidence_required', 'city.monopoly.legacy.convert');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.attest_mode', 'city.monopoly.claim.attest_mode', 'REQUIRED', 'Y', 'admin', NOW(), 'CITY-2 REQUIRED/SIGNAL'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.attest_mode');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 claim.photo.server_evidence_required', 'city.monopoly.claim.photo.server_evidence_required', 'true', 'Y', 'admin', NOW(), 'CITY-1 server photo evidence'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.claim.photo.server_evidence_required');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '城市大富翁 legacy.convert', 'city.monopoly.legacy.convert', 'true', 'Y', 'admin', NOW(), 'CITY-9 legacy territory conversion'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'city.monopoly.legacy.convert');
