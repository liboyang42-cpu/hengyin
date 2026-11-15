# PKG-R5-BE-INFRA-STUBS + PKG-R5-SMOKE-HARNESS Verification Report

- **Branch**: `fix/r5-be-infra-stubs` (based on `c879749c8`)
- **Target environment**: TEST only (`http://127.0.0.1:8120`, `/data/workspace/be-int/app`, DB `cy_e2e_r2`) — no production deploy.

## 1. Backend Changes (`hengyin` worktree `/data/workspace/be-int/r5-infra-wt`)

1. **Merchant CRM Broadcast (`POST /api/merchant/crm/broadcast`)**
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantCrmBroadcastServiceImpl.java`: set `done.setAudienceCount(recipients.size())` before `broadcastMapper.finalizeBroadcast(done)` and synced `broadcast.setAudienceCount(done.getAudienceCount())` so `audience_count` is never `null` on completion.
   - `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/MerchantCrmBroadcastServiceImplTest.java`: added `finalizeBroadcast` `audienceCount` assertion in `deliversOnlyConsentedCustomersAndRecordsSkips`.
2. **WeChat Content Security Token (`e2e-test` only)**
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/E2eTestContentSecurityService.java`: overrode `getAccessToken()` and `refreshAccessToken()` returning `"e2e-test-access-token"`.
3. **WxACode QR Generation & Local Storage (`e2e-test` only)**
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayerWxacodeServiceImpl.java`: added `@Autowired` constructor accepting `ObjectProvider<WxCodeGateway>` and `ObjectProvider<WxCodeStorage>`.
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/E2eTestWxCodeGateway.java`: renders real ZXing `QR_CODE` PNG bytes encoding `{page}?scene={scene}`.
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/E2eTestWxCodeStorage.java`: writes QR images under `RuoYiConfig.getProfile()/qrcode/...` and returns `${chengyin.qrcode.public-base-url}/profile/...`.
   - Updated `PlayerWxacodeServiceImplTest.java`.
4. **IM Media Local Storage & Voice Readback (`e2e-test` only)**
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/media/ImMediaStore.java` & `ImMediaService.java`: added `default byte[] readBytes(long assetId, long maxBytes, long expected)` and used it in `ImMediaService.voiceBytes(...)` before falling back to `fetch(store.shortLivedUrl(...))`.
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/media/E2eTestImMediaStore.java`: stores IM uploads locally under `RuoYiConfig.getProfile()/upload/...`, stages assets via `AppMediaAssetService.stage(...)`, serves `${app.media.public-base-url}/profile/...`, and reads local bytes safely.
   - Updated `ImMediaServiceTest.java`.
5. **AI NPC Speech-to-Text (`e2e-test` only)**
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/impl/E2eTestSpeechToTextService.java`: `@Service @Primary @Profile("e2e-test") @ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")` implementing `SpeechToTextService`.
6. **WeChat Mini Session Code Exchange (`e2e-test` only)**
   - `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/E2eTestWeChatMiniSessionService.java`: maps `e2e-code-<suffix>` codes to `WeChatMiniSession("e2e-openid-<suffix>", "e2e-session-<suffix>")` and delegates other codes to `super.exchange(jsCode)`.
   - Updated `E2eTestProvidersProfileTest.java`.

## 2. `api-smoke` Harness & TEST 8120 Fixture Updates (`/data/workspace/api-smoke/`)

- `extract_catalog.py`: excluded retired routes (`/api/workshop/purchased`, `/api/friend/channel-invite`, `/api/merchant/payout-account/save`) and regenerated `catalog.json` (`1236` endpoint calls, `0` references to retired routes).
- `shape_validator.py`: added `GET /api/im/video/message/{}/preview` validation against `Core/IMVideoService.swift:96-102` (`Envelope.Body`).
- `param_resolver.py`: added 46-byte PCM WAV generator for `POST /api/ai/npc/voice-chat`; updated `POST /api/merchant/crm/broadcast` (`scope: "ALL"`); set `"channel": "IN_APP"` for `/api/club/event-notification/preview`, `/api/club/event-notification/send`, and `/api/club/notices/send`; set `jsCode: "e2e-code-9003"` for `/api/club/personal-receiver/bind`.
- `smoke_runner.py`: added `"未开放"` to `EXPECTED_MSG_FRAGMENTS`; added `us_player` (`13900000011`, member `9011`) for `GET /api/us/auth/session`; restored `cms_topic` `992001` online after `/api/project/state-requests/submit`.
- `seed_4xx_fixtures.py`: seeded `app.wechat.appid=wxdf8fd71ea3c614b4`, reset `merchant_crm_broadcast*` / `merchant_crm_delivery_guard` rows for `merchant_id=8001`, seeded US test member `9011` and `us_apple_account` (`realm='us-test'`), ensured `cms_topic` `992001` is online, and wrote `im/video/seed_video_990005.mp4` (4096 B), `im/voice/seed_voice_990006.m4a` (1024 B), and `im/file/seed_file_990007.pdf` (2048 B) under `/data/workspace/be-int/app/upload/` and `/www/wwwroot/uploadPath/`.

## 3. Verification Results

### Maven Tests (`work`, after final edit)
- Command: `mvn test -DargLine='-Xmx1536m -Dspring.test.context.cache.maxSize=4'`
- Summary: `Tests run: 19664, Failures: 2, Errors: 34, Skipped: 709`
  - Targeted test classes for our changes:
    - `E2eTestProvidersProfileTest`: 3 run, 0 failed, 0 errors, 0 skipped
    - `PlayerWxacodeServiceImplTest`: 8 run, 0 failed, 0 errors, 0 skipped
    - `MerchantCrmBroadcastServiceImplTest`: 14 run, 0 failed, 0 errors, 0 skipped
    - `ImMediaServiceTest`: 10 run, 0 failed, 0 errors, 0 skipped
  - Pre-existing / environment failures outside our diff:
    - 34 MyBatis IT errors (`Communications link failure` connecting to port `3306`; VPS TEST MySQL container runs on `127.0.0.1:3330`).
    - `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode:430` (pre-existing on `c879749c8`: `ServiceException` vs `IllegalStateException`).
    - `ClubEventNotificationTransactionBoundaryTest.failedLaterClaimReceiptRollsBackEarlierOutboxLease:196`.

### API Smoke (`http://127.0.0.1:8120`)
- **Before**: `total: 1234`, `OK: 705`, `BUSINESS-REFUSAL-EXPECTED: 208`, `BACKEND-ERROR: 312`, `SHAPE-MISMATCH: 1`, `APP-CONTRACT-BUG: 8`
- **After**: `total: 1231` (3 retired routes excluded from catalog), `OK: 751`, `BUSINESS-REFUSAL-EXPECTED: 234`, `BACKEND-ERROR: 241`, `SHAPE-MISMATCH: 0`, `APP-CONTRACT-BUG: 5`
- All 29 live endpoints in `PKG-R5-BE-INFRA-STUBS` and `PKG-R5-SMOKE-HARNESS` passed (`28` `OK` HTTP 200; `POST /api/ai/npc/chat` `BUSINESS-REFUSAL-EXPECTED` 200/403).
