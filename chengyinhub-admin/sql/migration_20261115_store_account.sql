-- P1a store-account (FLOW-03): Sign in with Apple token revocation ledger for account deletion.
-- Idempotent: CREATE TABLE IF NOT EXISTS only. Safe to re-run.
-- Rollback: DROP TABLE IF EXISTS `ums_apple_token_revocation`;
--   (only after confirming no row is in TOKEN_READY / PENDING_CONFIG / PENDING_NO_TOKEN / REVOKE_FAILED,
--    otherwise the pending Apple revocations lose their record).
-- The refresh token is stored only as PII ciphertext (chengyin.pii.key, AES-GCM "v1:" prefix) and is wiped on
-- revocation or when the owner cancels the deletion. No plaintext token is ever written.

CREATE TABLE IF NOT EXISTS `ums_apple_token_revocation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL COMMENT 'ums_member.id of the account being deleted',
  `status` varchar(32) NOT NULL COMMENT 'TOKEN_READY / PENDING_CONFIG / PENDING_NO_TOKEN / REVOKED / REVOKE_FAILED / CANCELLED',
  `refresh_token_cipher` varchar(2048) DEFAULT NULL COMMENT 'Apple refresh token, PII ciphertext only; wiped after revoke/cancel',
  `last_error` varchar(64) DEFAULT NULL COMMENT 'error code only, never a token or response body',
  `revoked_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_apple_revocation_member` (`member_id`),
  KEY `idx_apple_revocation_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sign in with Apple token revocation on account deletion (FLOW-03)';
