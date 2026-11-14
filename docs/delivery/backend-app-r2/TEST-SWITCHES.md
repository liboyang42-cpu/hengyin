# TEST 8120 Switch Audit (`integration/backend-app-r2` @ `794264eac`)

- **Target environment**: VPS TEST (`http://127.0.0.1:8120`, DB `cy_e2e_r2` @ `127.0.0.1:3330`, Redis db `9` @ `127.0.0.1:6391`, spring profiles `druid,e2e-test`, overlay `/data/workspace/be-int/app/application-beint.properties`).
- **Owner rule**: Every money / merchant / payout / feature switch whose `ON` path can operate without real production third-party credentials (or can point at a TEST stub/sandbox/local storage) is **ON in TEST** so the full app can be exercised, and **OFF in production** unless explicitly enabled by the owner with real credentials.
- **No secrets in repo**: TEST overlay secrets (JWT, SMS placeholder, PII key, external-game signing key, WeChat placeholders) remain only on VPS in `/data/workspace/be-int/app/start.sh` and `.test-*-key` files.
- **Rollback preserved on VPS**: Previous runtime files backed up in `/data/workspace/be-int/app/` alongside earlier `*.prev-*` snapshots (`application-beint.properties.prev-794264e`, `app.log.prev-794264e`, `sys_config.prev-794264e.sql`).

---

## 1. Spring Property & Annotation Switches (`config/application*.yml` + `@Value` / `@ConditionalOnProperty` / `@ConfigurationProperties`)

### 1.1 Money, Release, Payout & Settlement (`PayoutSwitches`, `CashPayoutSwitch`, `MiscMoneySwitch`)

| Key | Code / YML Default | TEST Value (Before → After) | PROD Proposed | Reason & Runtime Behavior |
|---|---|---|---|---|
| `chengyin.release.money-payments` | `false` | `true` → `true` | `false` | Owner release gate for member money payments (`CashPayoutSwitch`, `MiscMoneySwitch`). Already ON in TEST; OFF in PROD until owner opens. |
| `chengyin.release.payout` | `false` | `true` → `true` | `false` | Owner release gate for payouts. Already ON in TEST; OFF in PROD until owner opens. |
| `payout.ecommerce.enabled` | `false` | `false` → **`true`** | `false` | Required by `PayoutSwitches.ecommerceEnabled()` (along with non-empty `sp-mchid`). Previously caused `POST /api/merchant/payout-account` to return `PLATFORM_NOT_OPEN`. |
| `payout.ecommerce.sp-mchid` | `""` | `""` → **`1721249597`** | `""` | Partner merchant ID required for `PayoutSwitches.ecommerceEnabled()`. Set to TEST placeholder matching `wx.pay.merchant-id`; `EcommerceSubMerchantService.ownerView` is DB-only for `FINISH` rows (`1900008001`). |
| `payout.ecommerce.sp-appid` | `""` | `""` → **`wxdf8fd71ea3c614b4`** | `""` | Partner appId required by `sellerRoutingEnabled()` and `personalClubEnabled()`. Set to TEST placeholder matching `wx.mini.appid`. |
| `payout.ecommerce.seller-routing-enabled` | `false` | `false` → **`true`** | `false` | Enables partner checkout routing for `FINISH` sub-merchants. DB quote/routing works without creds; real WeChat prepay fails lazily only if WeChat APIv3 cert is absent. |
| `payout.ecommerce.profit-sharing-enabled` | `false` | `false` → **`true`** | `false` | Enables profit-sharing planning/admin APIs and `ecommercePayoutTask.shareAndRetry()`. Safe in TEST because `ecom_profit_share_order` is DB-only until real paid submit. |
| `payout.ecommerce.combined-enabled` | `false` | `false` → **`true`** | `false` | Enables combined-order settlement planning (`EcommerceCombinedSettlementService`). Requires `sellerRoutingEnabled && profitSharingEnabled && valid platform-seller-sub-mchid`. |
| `payout.ecommerce.platform-seller-sub-mchid` | `""` | `""` → **`1900000001`** | `""` | Satisfies `PayoutSwitches.combinedEnabled()` regex `[0-9]{6,32}` and `!= sp-mchid`. TEST placeholder. |
| `payout.ecommerce.personal-club-enabled` | `false` | `false` → **`false`** | `false` | **Kept OFF**: when `true` (`live=true`), `EcommercePersonalReceiverService.bind()` requires live WeChat `sessions.exchange(jsCode)` and `gateway.addPersonalReceiver` with real WeChat APIv3 cert (`PERSONAL_RECEIVER_PROVIDER_NOT_CONFIRMED`). Leaving `false` allows local `CONSENTED` binding in TEST DB. |
| `payout.ecommerce.max-share-percent` | `30` | `30` → `30` | `30` | Cap on WeChat ecommerce profit sharing ratio (30%). |
| `payout.ecommerce.platform-receiver-mchid` | `""` | `""` | `""` | Platform profit-sharing receiver mchid; empty when not executing live WeChat profit share calls. |
| `payout.ecommerce.notify-url` / `refund-notify-url` / `combine-notify-url` | `""` | `""` | `https://...` | Partner pay/refund/combine callbacks; only needed when executing live WeChat HTTP calls. |
| `payout.ecommerce.api-v3-key` / `merchant-serial-number` / `private-key-path` | `""` | `""` | Env/secret path | Real WeChat Partner APIv3 credentials; must stay absent in TEST and git. |
| `payout.creator.enabled` | `false` | `false` → **`false`** | `false` | **Kept OFF**: `DisabledCreatorPayoutProvider` is the only `CreatorPayoutProvider` bean in the codebase; no third-party flexible-employment payout provider or credentials exist. |
| `payout.creator.provider` | `none` | `none` → **`none`** | `none` | Matches `DisabledCreatorPayoutProvider` (`!= "none"` triggers missing provider error). |

### 1.2 Commerce, Platform Pass, Bundle, Marketing Cash & Withdrawals

| Key | Code / YML Default | TEST Value (Before → After) | PROD Proposed | Reason & Runtime Behavior |
|---|---|---|---|---|
| `withdrawal.fast-withdraw.enabled` | `false` | `false` → **`true`** | `false` | Enables `ApiClubSettlementController` club fast-withdraw gate (`FAST_WITHDRAW_DISABLED` -> reaches balance/settlement rules). |
| `merchant.commerce.self-checkout-enabled` | `false` | `false` → **`true`** | `false` | Enables `MerchantCommerceService` self-checkout order creation gated by `settlement_rule` (lazy failure only at WeChat prepay if paid). |
| `marketing.product.cash-enabled` | `false` | `false` → **`true`** | `false` | Enables `MarketingCashChannel` cash path (`MKO*` order creation/prepare). `WechatPaymentCallbackRouter` already routes `MKO` prefix; prepay fails lazily on missing WeChat cert. |
| `platform-pass.enabled` | `false` | `false` → **`true`** | `false` | Enables `/api/platform-pass` overview, library, entitlements, and coverage endpoints (DB-backed). |
| `platform-pass.jsapi-enabled` | `false` | `false` → **`true`** | `false` | Enables JSAPI order channel flag (reports `PAYMENT_UNAVAILABLE` cleanly when `wechatPayService.isReady()==false`). |
| `platform-pass.xpay.enabled` | `false` | `false` → **`false`** | `false` | **Kept OFF**: even with sandbox `env=1`, `HttpXpayGateway` requires real WeChat Virtual Payment `offer-id`, `app-key`, and live mini `jsCode`/`session_key`. |
| `platform-pass.xpay.env` | `1` | `1` → **`1`** | `0` | Pointed to WeChat XPay sandbox `1` (`0=prod`, `1=sandbox`). |
| `platform-pass.xpay.offer-id` / `app-key` | `""` | `""` | Secret env | No WeChat virtual payment sandbox app-key/offer-id provisioned. |
| `platform-pass.teammate-benefit-enabled` | `false` | `false` (default) | `false` | Part of `PlatformPassProperties`; can remain default `false` unless testing teammate pass discount. |
| `chengyin.free-explore.bundle.ledger-enabled` | `false` | `false` → **`true`** | `false` | Enables DB ledger reads/writes (`explore_bundle_*`) for free-explore bundle tickets. |
| `chengyin.free-explore.bundle.sale-enabled` | `false` | `false` → **`true`** | `false` | Freezes bundle entitlement units on creation when a topic has `required` chapters; pure content explore topics skip cleanly. |
| `chengyin.free-explore.bundle.reservation-enabled` | `false` | `false` → **`true`** | `false` | Enables bundle reservation/cancel/merchant-exit DB flows. |

### 1.3 Workshop, Apple IAP, US Apple Auth & Native App Attest

| Key | Code / YML Default | TEST Value (Before → After) | PROD Proposed | Reason & Runtime Behavior |
|---|---|---|---|---|
| `workshop.wechat-app-enabled` | `false` | `false` → **`true`** | `false` | Enables WeChat App purchase channel switch in `WorkshopService`; guarded cleanly by `payments.isAppReady()`. |
| `chengyin.workshop.professional.review` | `AUTO` | `MANUAL` → `MANUAL` | `AUTO` or `MANUAL` | Preserved TEST `MANUAL` setting so professional workshop submissions enter `/business/workshop-professional` review queue. |
| `workshop.apple-iap.enabled` | `false` | `false` → **`false`** | `false` | **Kept OFF**: requires Apple StoreKit Root CA certs (`root-cert-paths`), `bundle-id`, `app-apple-id`, and `sku-prices`. |
| `workshop.apple-iap.server-api-enabled` | `false` | `false` → **`false`** | `false` | **Kept OFF**: requires App Store Server API `.p8` private key, `key-id`, and `issuer-id`. |
| `workshop.apple-iap.allow-xcode-sandbox` | `false` | `false` | `false` | StoreKit local Xcode sandbox toggle inside `AppleIapProperties`. |
| `chengyin.us.apple.enabled` | `false` | `true` → `true` | `false` | Preserved ON in TEST with fixture `realm=us-test`, `client-id=com.example.questify.us`; `jwks-url` defaults to `https://appleid.apple.com/auth/keys`. |
| `chengyin.native-steps.app-attest.enabled` | `false` | `true` → `true` | `false` | Preserved ON in TEST using local Apple root DER/CER test fixtures at `/data/workspace/be-int/app/attest/`. |
| `chengyin.native.home.enabled` | `false` | `true` → `true` | `false` | Preserved ON in TEST. |
| `chengyin.native.home.return.enabled` | `false` | `true` → `true` | `false` | Preserved ON in TEST with `policy-version=home-return-test-1`. |

### 1.4 Webhooks, Merchant Authoring, External Games & Test Stubs

| Key | Code / YML Default | TEST Value (Before → After) | PROD Proposed | Reason & Runtime Behavior |
|---|---|---|---|---|
| `chengyin.e2e.test-providers` | *(cond `=true`)* | `true` → `true` | **absent / `false`** | Combined with Spring profile `e2e-test`, activates `E2eTestSmsCodeSender` (OTP kept in Redis, never sent via Aliyun SMS), `E2eTestMerchantAvatarProvider` (`e2e-test-passthrough`), and `E2eTestContentSecurityService` (bypasses `wx.sec-check` HTTP calls). Never in PROD. |
| `chengyin.im.webhook.dispatch.enabled` | `false` | `true` → `true` | `false` | Enables outbound IM channel webhook dispatcher with TEST `inbound-base-url=https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech`. |
| `chengyin.merchant.authoring.enabled` | *(cond `=true`)* | `true` → `true` | `false` | Activates `MerchantAuthoringPolicy` with TEST fixtures (`model-version`, `prompt-version`, `cost-limit=0`, `template-ids`, `merchant-review=true`, `theme-review=true`, `visibility=PUBLIC`, `timezone=Asia/Shanghai`, `branches=MORNING,AFTERNOON,EVENING`). |
| `chengyin.merchant.authoring.environment-provider` | `""` | `""` → `""` | Provider ID | No `EnvironmentObservationSource` Spring bean exists in repo; blank avoids rejecting valid observations. |
| `chengyin.merchant.authoring.weather-safe-branches` | `""` | `""` | CSV branches | Used only when `EnvironmentObservationSource` returns `DANGEROUS`. |
| `chengyin.merchant.avatar.provider` | *(cond `=http`)* | *(unset → `e2e-test-passthrough`)* | `http` | Handled by `@Primary E2eTestMerchantAvatarProvider` in TEST instead of `HttpMerchantAvatarProvider`. |
| `chengyin.external-game.signing-private-key` | `${CHENGYIN_EXTERNAL_GAME_SIGNING_KEY:}` | Injected via `.test-external-game-key` | Secret env | Required by `ExternalGameSigner`; TEST uses persistent local Ed25519 key generated in `/data/workspace/be-int/app/.test-external-game-key`. |
| `chengyin.test.wechat-api-base-url` | `https://api.weixin.qq.com` | `https://api.weixin.qq.com` (default) | `https://api.weixin.qq.com` | Override hook for `scripts/e2e/wechat_stub.py` (`E2E` local WeChat mock server when testing JSCode login stubs). |

### 1.5 Unified Search, Community, Audit (`O-05 / O5b / O5c`) & Moderation

| Key | Code / YML Default | TEST Value (Before → After) | PROD Proposed | Reason & Runtime Behavior |
|---|---|---|---|---|
| `chengyin.search.unified.enabled` | `false` | `false` → **`true`** | `false` | Enables `/api/search/unified` SQL unified search across entities; zero third-party credentials. |
| `chengyin.community.post-limit.enabled` | `false` | `false` → **`true`** | `false` | Enables O-05 community post `LIMITED` enforcement lifecycle (DB-backed). |
| `community.post.legacy-writes-enabled` | `false` | `false` → **`false`** | `false` | **Kept OFF in TEST & PROD**: legacy square/comment write paths intentionally return `426 UPGRADE_REQUIRED` so clients exercise `/api/community/*` v1 endpoints (`communityPostWrite=true`). |
| `chengyin.audit.risk-escalation.enabled` | `false` | `false` → **`true`** | `false` | Enables report-threshold (`report-threshold=3`) risk escalation in `AuditTaskServiceImpl` (DB-only). |
| `chengyin.audit.item-problems.enabled` | `false` | `false` → **`true`** | `false` | Enables structured audit rejection item problems (DB-only). |
| `chengyin.audit.o5b.appeal-attachments.enabled` | `false` | `false` → **`true`** | `false` | Enables O5b appeal attachment validation/storage (DB/local media). |
| `chengyin.audit.o5b.report-sla-inherit.enabled` | `false` | `false` → **`true`** | `false` | Enables O5b SLA deadline inheritance across re-reports (DB-only). |
| `chengyin.audit.o5b.template-title-similarity.enabled` | `false` | `false` → **`true`** | `false` | Enables O5b in-memory Dice title similarity check against published templates. |
| `chengyin.audit.o5c.unified-trail.enabled` | `false` | `false` → **`true`** | `false` | Enables O5c unified audit trail projection (`AuditTrailProjectionService`, DB-only). |
| `chengyin.audit.o5c.ai-precheck.enabled` | `false` | `false` → **`true`** | `false` | Enables O5c topic publish precheck; `TopicAiPrecheckService` fail-opens cleanly if `ai.model.apiKey` is empty without blocking topic publishing. |

### 1.6 AI, Voice, ImageSeg, Weather, Storage / QR & Map

| Key | Code / YML Default | TEST Value (Before → After) | PROD Proposed | Reason & Runtime Behavior |
|---|---|---|---|---|
| `chengyin.qrcode.storage` | `oss` | `oss` → **`local`** | `oss` | **Switched to local stub**: avoids Aliyun OSS credentials by writing QR PNGs under `ruoyi.profile` (`/data/workspace/be-int/app/upload/qrcode`), served via `/profile/**`. |
| `chengyin.qrcode.public-base-url` | `""` | `""` → **`https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech`** | CDN / domain URL | Required when `chengyin.qrcode.storage=local`. |
| `chengyin.rpg.weather.fixed` | `""` (`unknown`) | `""` → **`clear`** | `""` (or weather adapter) | `FixedTopicWeatherProvider` returns `clear` (valid lower-case code in `TopicEnvironmentService.WEATHER_VALUES`) so `ENV_IN` weather conditions can be tested without an external weather API. |
| `ai.model.enabled` | `true` (`application.yml`) | `true` → `true` | `true` | LLM gateway gate (routes via `AiSceneRouter`; empty `apiKey` returns `UPSTREAM_UNAVAILABLE` lazily). |
| `ai.providers.mock.enabled` | `true` (`application.yml`) | `true` → `true` | `false` | Mock LLM client enabled (`defaultProvider: mock`), though `AiSceneRouter` routes named scenes to `deepseek`. |
| `ai.providers.deepseek.enabled` / `qwen.enabled` / `minimax.enabled` | `false` | `false` | `true` (when keyed) | External provider keys (`CHENGYIN_DEEPSEEK_API_KEY`, `CHENGYIN_MINIMAX_API_KEY`) absent in TEST. |
| `ai.vision.enabled` | `true` (`application.yml`) | `true` → `true` | `true` | Vision check enabled; degrades cleanly on missing `CHENGYIN_DEEPSEEK_API_KEY`. |
| `ai.themeDraftIntent.enabled` | `false` | `false` → **`true`** | `false` | Enables idempotent theme draft request state machine (MySQL + Redis, no external credentials required). |
| `ai.themeDraftIntent.maintenanceEnabled` | `false` | `false` → **`true`** | `false` | Enables `AiQuotaRecoveryTask` stuck-draft/quota recovery task. |
| `object-card.naming.enabled` | `false` | `false` → **`true`** | `false` | Enables `ObjectNamer`; falls back gracefully when vision API key is blank. |
| `ai.imageseg.enabled` | `false` | `false` → **`false`** | `false` / `true` | **Kept OFF**: requires Aliyun VIAPI RAM credentials (`CHENGYIN_ALIYUN_VIAPI_AK_ID` / `AK_SECRET`). |
| `ai.voice.enabled` / `ai.voice.asrEnabled` | `false` | `false` → **`false`** | `false` / `true` | **Kept OFF**: requires Alibaba DashScope (`CHENGYIN_DASHSCOPE_API_KEY`) and `workspaceId`. Causes `shopNpcVoice=false`. |
| `tencent.map.enabled` | `false` | `false` → **`false`** | `true` (with key) | **Kept OFF**: requires real Tencent Location Service WebServiceAPI key (`CHENGYIN_TENCENT_MAP_KEY`). Client falls back to manual city selection. |

### 1.7 WeChat Pay / Mini / Subscribe, Security, Scheduling & Monitoring

| Key | Code / YML Default | TEST Value (Before → After) | PROD Proposed | Reason & Runtime Behavior |
|---|---|---|---|---|
| `wx.pay.enabled` | `true` (`application.yml`) | `true` → `true` | `true` | Enabled in `application.yml`; `WechatPaymentSdkGateway` catches missing `/www/wwwroot/cert/.../apiclient_key.pem` at startup and marks `isReady()=false` lazily. |
| `wx.sec-check.enabled` | `true` (`application.yml`) | `true` (stubbed) → `true` (stubbed) | `true` | Handled by `@Primary E2eTestContentSecurityService` stub under `e2e-test` + `chengyin.e2e.test-providers=true`. |
| `feature.roamRecall.enabled` | `false` | `false` → **`false`** | `false` | **Kept OFF**: requires non-empty `wx.subscribe.tplRoamRecall`, live `wx.mini.secret`, and WeChat subscribe authorization. |
| `chengyin.jobs.enabled` | `true` (matchIfMissing) | `true` → `true` | `true` | Enables background Quartz / scheduled tasks (`BackgroundSchedulingConfig`, `LeaderScheduledJobsConfig`). |
| `CHENGYIN_RESTORE_MODE` (env) | unset (`false`) | unset → unset | unset (`false`) | `RestoreDispatchGuard`: unset or `"false"` allows normal dispatch. Any other value pauses outbound/AI dispatch. |
| `xss.enabled` | `true` | `true` → `true` | `true` | Web XSS filter enabled. |
| `chengyin.team-trail.default-share` | `true` | `true` → `true` | `true` | Team trail sharing default flag. |
| `spring.datasource.druid.slave.enabled` / `statViewServlet.enabled` | `false` (`application-druid.yml`) | `false` → `false` | Ops-gated | Single MySQL datasource in TEST; Druid console disabled. |
| `springdoc.swagger-ui.enabled` / `api-docs.enabled` | `false` | `false` → `false` | `false` | OpenAPI UI disabled in runtime overlay. |
| `ruoyi.addressEnabled` | `false` | `false` → `false` | `false` | IP geolocation lookup off (`false`). |

*(Note: numeric thresholds/timeouts/secrets/DB-pool tuning keys like `cy.slo.*`, `merchant.verification-risk.*`, `fund.preflight.*`, `chengyin.audit.deadline-hours.*`, `chengyin.retention.*`, `chengyin.pii.*`, `token.*`, `wx.subscribe.tpl*`, `compliance.doc-versions.*`, `metrics.probe.*`, `druid.*` are operational configuration/secrets rather than boolean feature/money gates, and retain their existing TEST/code values.)*

---

## 2. Database Runtime Feature Switches (`cy_e2e_r2.sys_config` & `/api/config/features`)

These switches are read dynamically via `ISysConfigService` (`sys_config` table; missing row defaults to `false` / `off`). Non-capability safety/anti-automation gates remain permissive/OFF for TEST automation (`play.arrive.location_guard.mode=off`, `audit.activity.pre_review_all=false`, `content.sensitive.rescan.remove=false`, `sys.account.captchaEnabled=false`, `feature.flag.minorMode=false`).

| `sys_config` Key | TEST Before | TEST After | PROD Proposed | Consumer & Notes |
|---|---|---|---|---|
| `merchant.desk.f4.enabled` | `false` | **`true`** | `false` | `MerchantDeskSwitch.isEnabled()` — unlocks `/api/merchant/desk/*`. |
| `feature.flag.merchantDeskProducts` | *(missing → `false`)* | **`true`** | `false` | `MerchantDeskSwitch.productsEnabled()` & `/api/config/features` `merchantDeskProducts`. |
| `feature.flag.clubMembership` | `false` | **`true`** | `false` | `ClubMembershipFeatureGate` & `/api/config/features` `clubMembership`. |
| `feature.flag.userInvoice` | *(missing → `false`)* | **`true`** | `false` | `ComplianceFeatureSwitches.userInvoiceOn()` & `/api/config/features` `userInvoice`. |
| `feature.flag.merchantNpcChat` | `false` | **`true`** | `false` | `NpcFeatureFlags.merchantChatOn()` & `/api/config/features`. |
| `feature.flag.roamNpcEvent` | *(missing → `false`)* | **`true`** | `false` | `NpcFeatureFlags.eventOn()` & `/api/config/features`. |
| `feature.flag.drops` | *(missing → `false`)* | **`true`** | `false` | `RewardDropServiceImpl` & `/api/config/features` `drops`. |
| `feature.flag.finishNearbyRoute` | *(missing → `false`)* | **`true`** | `false` | `/api/config/features` `finishNearbyRoute` (mini-program play page). |
| `roam.visited_poi.stable_identity` | `false` | **`true`** | `false` | `VisitedPoiIdentityResolver` (`member_visited_poi.poi_key` column verified present). |
| `orient.geo.normalize.enabled` | `false` | **`true`** | `false` | `OrientSwitches.geoOn()` — WGS84 coordinate normalization. |
| `orient.alt_task.enabled` | `false` | **`true`** | `false` | `OrientSwitches.altTaskOn()` — AR alternative task. |
| `orient.team.board.enabled` | `false` | **`true`** | `false` | `OrientSwitches.teamBoardOn()` — team lobby & progress matrix. |
| `orient.safety.enabled` | `false` | **`true`** | `false` | `OrientSwitches.safetyOn()` — orienteering safety gate. |
| `orient.scoring.enabled` | `false` | **`true`** | `false` | `OrientScoringServiceImpl` — orienteering scoring master switch. |
| `feature.flag.communityPostRead` | `true` | `true` | `false` / phased | `CommunityPostFeatureGate.canRead()`. |
| `feature.flag.communityPostWrite` | `true` | `true` | `false` / phased | `CommunityPostFeatureGate.canWrite()` (pilot members `*`). |
| `feature.flag.shopNpcChat` | `true` | `true` | `false` / phased | `NpcFeatureFlags.shopChatOn()`. |
| `feature.flag.roamDemoPois` | *(missing → `false`)* | `false` | `false` | Kept `false` so mini-program does not mix dev demo POIs over `cy_e2e_r2` seeded POIs. |
| `feature.flag.minorMode` / `minorOrienteeringGate` | *(missing → `false`)* | `false` | `false` | Kept `false` in TEST so curfew/age gates do not block unattended E2E test runs. |
| `play.arrive.location_guard.mode` | `off` | `off` | `off` / phased | `ArriveLocationGuard` kept `off` for scriptable TEST coordinates. |
| `audit.activity.pre_review_all` | `false` | `false` | `false` | Kept `false` so non-high-risk test activity creation works without manual pre-audit block. |
| `content.sensitive.rescan.remove` | `false` | `false` | `false` | Kept `false` so cron rescan does not auto-unpublish fixtures. |
| `sys.account.captchaEnabled` | `false` | `false` | `true` / ops | Kept `false` for headless TEST login automation. |

---

## 3. Post-Change TEST 8120 Verification

1. **8120 Restart**: Stopped PID `348805`, launched via `/data/workspace/be-int/app/start.sh` (PID `363739`); booted in `15.66s` with no new errors in `/data/workspace/be-int/app/app.log`.
2. **E2E Seed & 32-Check Curl Suite** (`/data/workspace/seed-v2/docs/delivery/backend-app-r2/seed-e2e-v2.sh` on branch `docs/seed-e2e-v2`):
   - `SUMMARY: 32/32 checks PASSED`
   - `4d. merchant/payout-account (8001)`: `HTTP 200 code=200`, `available=True`, `state="FINISH"`, `stateDesc="已完成二级商户进件（TEST环境模拟户）"`, `subMchidMasked="19****8001"`, DB `FINISH:1900008001` (no `PLATFORM_NOT_OPEN`).
3. **Additional Switch Verification Curls**:
   - `POST /api/merchant/payout-account` (merchant `9002`): `HTTP 200 code=200`, `available=true, state="FINISH", subMchidMasked="19****8001"`.
   - `POST /api/config/features` (authenticated member `9004`): `drops=true, finishNearbyRoute=true, clubMembership=true, merchantDeskProducts=true, roamNpcEvent=true, shopNpcChat=true, merchantNpcChat=true, communityPostRead=true, communityPostWrite=true, userInvoice=true, freeExploreBundle=true` (with `shopNpcVoice=false` due to absent DashScope key, `roamNpcChat=false` hardcoded V1 off, `roamDemoPois=false`).
   - `GET /api/platform-pass` (member `9004`): `HTTP 200 code=200`, `enabled=true, libraryCount=3, channels.jsapi.reason="PAYMENT_UNAVAILABLE", channels.xpay.reason="XPAY_DISABLED"`.
   - `POST /api/search/unified` (member `9004`): passed switch gate (`HTTP 200 code=400 keyword 不能为空`).
   - `POST /api/club/settlement/summary` (merchant `9002`, `clubId=7001`): passed `withdrawal.fast-withdraw.enabled` gate (`没有本人的历史结算记录`).
