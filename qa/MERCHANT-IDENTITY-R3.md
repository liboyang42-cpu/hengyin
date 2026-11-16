# MERCHANT-IDENTITY-R3 — 商家入驻收集真实姓名与身份证号

Branch: `fix/merchant-identity-r3` (from `origin/integration/backend-app-r3`). Nothing is deployed. No new secrets or config values were added.

## Summary

This reuses the existing publisher-identity system (RUN-52). It already had AES-GCM encryption, a blind index, a GB 11643 validator, PIPL consent records, a `merchant_apply` source and account-deregistration cleanup. No second identity store and no second encryption utility were added.

## API contract

### Submit: `POST /api/merchant/merchant_registration` (app JWT)

Three new JSON fields go next to the existing ones:

| Field | Type | Rule |
|---|---|---|
| `realName` | string | Trimmed, 2 to 30 characters, no digits. `·` is allowed for names like 买买提·艾力. |
| `idCardNumber` | string | 18 characters. The first 17 must be digits. The last is a digit or X, and a lowercase x is accepted and stored as X. The birth date must be plausible and the GB 11643-1999 check digit must match. Spaces are removed. 15-digit cards are rejected. |
| `identityConsent` | boolean | **Must be `true` whenever `realName` or `idCardNumber` is sent.** This is the separate consent PIPL Art. 29 requires for sensitive personal information. The existing identity service already enforced it, and it is recorded in `consent_record`. |

> ⚠️ **iOS action needed:** the iOS app has to send `identityConsent: true` along with `realName` and `idCardNumber`, for example from a "同意向平台提供真实姓名与身份证号" checkbox. The brief named only the two fields. I did not remove the existing consent gate because it is a legal compliance control, not a formatting rule.

When the fields are required:
- **New application (`id` is null):** required. The only exception is a member who already registered through `POST /api/publisher/identity` (the WeChat mini program's flow). They can leave the fields out.
- **Editing an existing application (`id` set):** the fields are not required and are ignored. Existing merchants without identity rows keep working.
- If the fields are sent and the member already registered **the same** ID card, the request succeeds and nothing is overwritten. A **different** card is refused with `实名信息已登记,如需变更请联系平台客服`; changing a registered card goes through manual support. That refusal rolls back the whole transaction, so no merchant row is written.

Error messages (`code` 500, the project's standard `AjaxResult.error`). None of them echo the input:

| Case | `msg` |
|---|---|
| Bad check digit, wrong length or illegal character | `身份证号格式不正确,请核对后重填` (`MemberIdentityService.MSG_ID_CARD_INVALID`) |
| Name length | `请填写真实姓名(2-30 个字)` |
| Name contains a digit | `姓名里不应包含数字` |
| Only one of the two fields sent | `身份证号格式不正确,请核对后重填` or the name message |
| Consent missing or false | `请先勾选同意提供真实姓名与身份证号` |
| New application with no fields and no earlier registration | `请填写真实姓名与身份证号` (`MSG_IDENTITY_REQUIRED`) |
| Same member, different card | `实名信息已登记,如需变更请联系平台客服` |

`POST /api/publisher/identity` (the standalone registration endpoint) now also accepts `idCardNumber` as an alias for `idCard`, so iOS can use one field name everywhere.

### Reads: masked values only

| Path | Who | Returns |
|---|---|---|
| `POST /api/publisher/identity/status` | Player or merchant (the member themselves) | `{registered, realNameMasked, idCardNumberMasked}` |
| `GET /business/merchant/{id}` | Admin with `business:merchant:query` | The merchant plus `ownerIdentity: {registered, realNameMasked, idCardNumberMasked, source, consentTime}`, or `null` for older merchants with no identity |
| `POST /api/merchant/merchant_registration` response | Applicant | `更新成功` only, with no identity data |

Masked formats:
- ID card: first 3 + 11 `*` + last 4, for example `110***********1234` (`IdCardUtils.mask`). Strings shorter than 8 characters are fully masked.
- Name: first character + `*`, for example `张*` (the existing `DesensitizedUtil.username`).

No API returns the full number. **Admin full-number view was not added.** There is no existing reveal-plus-audit pattern for identity data. The closest one, the invoice tax-number reveal, needs a new `sys_menu` permission and an audit trail. AGENTS.md lists RBAC changes under "Ask first", so admins get masked values only. If reviewers need the full number, that needs an owner decision on the permission key and the audit log entry.

## Storage

- Table `ums_member_identity` already exists (`chengyinhub-admin/sql/migration_ums_member_identity_20260919.sql`). It holds one row per member.
  - `real_name` and `id_card` use `VARCHAR(768)` and store AES-256-GCM ciphertext: `v1:` + base64(random 12-byte nonce + ciphertext + tag). Encryption and decryption happen only inside `PiiCryptoTypeHandler` in the mapper XML.
  - `id_card_hash` is an HMAC-SHA256 blind index, used to find duplicate cards during manual rebinding.
  - `source` is `merchant_apply` for this flow. `consent_time` is set and a row is added to `consent_record`.
- The masked value is **computed on read** from the decrypted value inside `MemberIdentityService.maskedView`. It is not stored, so the plaintext only exists inside that method call.
- `mms_merchant` gets no new columns. That table is read by `select *` and Excel export paths, and adding PII columns there would leak it into every merchant query.
- If no key is configured, writes fail closed: the request is refused rather than stored as plaintext.

## Migration

**No new DDL.** The columns this flow needs already exist through the migration above, which runs before the jar. A new column would have added a deploy-order risk without storing anything new. Before the first deploy that uses this branch, a read-only pre-check should confirm `ums_member_identity` exists in the target database (`chengyin-db-migrate` skill, `--describe ums_member_identity`).

## Config

Property names only. They already exist in `application.yml`, so nothing new is needed:
- `chengyin.pii.key`: Base64 of a 32-byte AES-256 key. It resolves to `withdrawal.pii.key`, which resolves to the environment variable `CHENGYIN_WITHDRAWAL_PII_KEY`.
- `chengyin.pii.hmac-key` (optional): the blind-index HMAC secret. If it is unset, a separate key is derived from the AES key.

## Logging

- `merchantRegistration` and every method of `ApiMemberIdentityController` have no `@Log`. A test enforces this. The `@Log(title="商家入驻")` annotation near that code is attached to the private helper `registrationCategoryIds`, so AOP never applies it. `LogAspect` no longer records request parameters anyway.
- The new DTO fields have **no** Bean Validation annotations, because `GlobalExceptionHandler` logs `BindException` messages, which include the rejected value. Validation happens in the service, and its messages never include the input.
- `MerchantRegistrationDTO`, `MemberIdentityRequest` and `UmsMemberIdentity` have no custom `toString`.
- MyBatis SQL debug logging records the values passed to the JDBC statement, which are the ciphertext and the hash, not the Java plaintext. The test checks this with every logger at ALL level.
- `RepeatableFilter`, `RequestIdFilter` and `RuntimeFailureAlarmFilter` do not log request bodies.

## Tests

New: `chengyinhub-admin/src/test/java/com/chengyinhub/business/security/MerchantIdentityR3Test.java` (13 tests). It uses H2 in MySQL mode with the real mapper XML and the real type handlers. A random key is generated for each run and never committed.

- Validation: valid number, lowercase `x` normalized to `X`, bad check digit, 17 and 19 characters, illegal characters, empty or null. Names of 1, 30, 31, blank, containing a digit, or containing `·`. Missing consent.
- Encryption round trip: the raw column starts with `v1:` and contains no digits from the card. The same card encrypts to different ciphertexts. Reading back returns the normalized plaintext.
- Masking on every read path: the status endpoint, the admin merchant detail and the submit response. Each response is serialized to JSON and checked for the full number, its middle digits and the full name.
- No plaintext in logs: root, `com.chengyinhub` and the mapper loggers are captured at ALL level across a successful submit, a rejected submit and a card-conflict exception passed through `GlobalExceptionHandler`. Messages and the throwable chain are checked.
- Older data: editing an existing application without the fields still succeeds. Admin detail for a merchant with no identity returns `ownerIdentity: null`. A legacy plaintext row (no `v1:` prefix) still reads back masked.
- The mini-program flow (registered earlier, no fields on submit) still works. Submitting with no fields and no registration is refused.

Existing tests that still pass: `MemberIdentityServiceTest` (10), `PublisherIdentityPiiWiringTest`, `ApiMerchantRegistrationStateGateTest` (6), `ApiMerchantOperatorEndpointTest`, `ClubOwnerTransferIdentityFlowTest`, `FreeClubPublisherIdentityTest`. The targeted run had 42 tests and 0 failures.

### Suite totals

RESULTS_PLACEHOLDER

## Not changed / follow-ups

- The mini program (`chengyinhub-xcx/utils/publisher-identity.js`) still limits names to 20 characters on the client. The server now allows 30. I left it alone because it is out of scope.
- Admin full-number reveal needs an owner decision (see above).
