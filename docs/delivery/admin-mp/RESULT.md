# admin-mp — result (2026-11-14)

Branch `feature/admin-review-pages-and-mp` off `integration/backend-app-r2` (`5c2017eef`), base implementation commit `01fcf4cf0`.
TEST / offline verification only (`work` sandbox, scratch DB copy of `cy_e2e_r2`, stubbed `/prod-api` Playwright run). Production was not touched and `master`/`main` was not modified.

## 1. Admin Pages, Routes & Permissions

All five new admin pages live under the existing `审核与风控` (`audit-center`) directory menu, share the API client (`chengyinhub-ui/src/api/business/reviewQueues.js`) and shared review-state helpers (`chengyinhub-ui/src/utils/review-queue.js`), and reuse the audit workbench permission pair:
- Read / list / detail / download: `business:audit:list`
- Write / approve / reject / escalate / sweep / redrive: `business:audit:review` (UI actions gated with `v-hasPermi="[business:audit:review]"`)

| Menu Name | Order | Route Path | Vue Component | Controller & Routes | Permissions (`@PreAuthorize`) |
|---|---|---|---|---|---|
| 外部游戏审核 | 52 | `/audit-center/external-game` | `business/externalgame/index` (`chengyinhub-ui/src/views/business/externalgame/index.vue`) | `ExternalGameReviewController` (`/business/external-game`):<br>`GET /review/list`, `GET /review/{submissionId}`, `GET /review/{submissionId}/package`<br>`POST /review/{submissionId}/approve`, `POST /review/{submissionId}/reject`, `POST /review/{submissionId}/escalate`, `POST /review/sweep-timeouts` | Read: `business:audit:list`<br>Actions & sweep: `business:audit:review` |
| 外部游戏举报 | 53 | `/audit-center/external-game-report` | `business/externalgame/reports` (`chengyinhub-ui/src/views/business/externalgame/reports.vue`) | `ExternalGameReviewController` (`/business/external-game`):<br>`GET /reports` (`gameId`, `status`, `pageNum`, `pageSize`) | Read: `business:audit:list` |
| 工坊专业化审核 | 54 | `/audit-center/workshop-review` | `business/workshopreview/index` (`chengyinhub-ui/src/views/business/workshopreview/index.vue`) | `WorkshopProfessionalReviewController` (`/business/workshop-professional`):<br>`GET /review/list`<br>`POST /review/{id}/approve`, `POST /review/{id}/reject`, `POST /review/{id}/escalate` | Read: `business:audit:list`<br>Actions: `business:audit:review` |
| 商家供给退出 | 55 | `/audit-center/offer-withdrawal` | `business/offerwithdrawal/index` (`chengyinhub-ui/src/views/business/offerwithdrawal/index.vue`) | `MerchantOfferWithdrawalAdminController` (`/business/offer-withdrawal`):<br>`GET /list` (`merchantId`, `offerId`, `outcome`, `pageNum`, `pageSize`; read-only ledger) | Read: `business:audit:list` |
| Webhook 投递 | 56 | `/audit-center/im-webhook` | `business/imwebhook/index` (`chengyinhub-ui/src/views/business/imwebhook/index.vue`) | `ImWebhookDeliveryAdminController` (`/business/im-webhook`):<br>`GET /deliveries` (`state`, `subscriptionId`, `pageNum`, `pageSize`; default `DEAD`)<br>`POST /deliveries/{deliveryId}/redrive` (`@Log` → `sys_oper_log`) | Read: `business:audit:list`<br>Redrive: `business:audit:review` |

Supporting backend services added in `01fcf4cf0`:
- `chengyinhub-system/src/main/java/com/chengyinhub/business/merchantgap/MerchantOfferWithdrawalAdminService.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/channel/platform/ChannelWebhookAdminService.java`

## 2. Menu Migration

- SQL file: `chengyinhub-admin/sql/migration_20261121_admin_review_queue_menus.sql` (`-- runner: requires=transaction`)
- Dependency entry in `ci/migration-dependencies.json`: `"20261121_admin_review_queue_menus": ["20261109_questo_admin_menus", "20261117_workshop_professional"]`
- Inserts 5 `menu_type=C` rows under `parent_id` of `sys_menu` where `menu_type=M AND path=audit-center` (`order_num` 52–56).
- Menu `perms` are intentionally left empty (``) to avoid colliding with the uniqueness guard on existing `business:audit:list` / `business:audit:review` permissions (`COUNT(*) = 1` each in `cy_e2e_r2.sys_menu`).
- No `sys_role_menu` rows are inserted (super-admin can access directly; role grants remain for owner decision).
- Scratch DB idempotency verification (`/data/workspace/fe-admin-mp-out/mig.sh` against a copy of `cy_e2e_r2.sys_menu`):
  - `run1 ok`, `run2 ok` (re-runnable, zero duplicates)
  - All 5 rows verified under `audit-center` (`order_num` 52–56) before dropping scratch DB `cy_admin_mp_scratch`.

## 3. Mini-Program Pages & Routes

Registered in `chengyinhub-xcx/app.json` under `subpackageMember` and `chengyinhub-xcx/scripts/uiaudit/route-matrix.json` (`routes`: `172` → `176`):

| Mini-Program Route | Purpose | Backend Endpoints Consumed | Entry / Notes |
|---|---|---|---|
| `subpackageMember/external-games/index` | 外部游戏目录（只读分页） | `GET /api/external-game/catalog` | Profile menu `外部游戏` (`goExternalGames`); cursor pagination via `external-game-view.js` |
| `subpackageMember/external-game-detail/index` | 外部游戏详情（只读） | `GET /api/external-game/catalog/{gameId}` | Read-only metadata; never executes games or exposes `downloadPath` |
| `subpackageMember/rewards/index` | 我的奖励（非现金券承载） | `GET /api/rewards/noncash` | Profile menu `我的奖励` (`goRewards`); cursor pagination via `noncash-reward-view.js` |
| `subpackageMember/reward-offer/index` | 主题奖励领取与断线恢复 | `GET /api/rewards/noncash/subjects/{topicId}`<br>`GET /api/rewards/noncash/versions/{versionId}`<br>`POST /api/rewards/noncash/claims`<br>`POST /api/rewards/noncash/claims/recover` | Entry `?topicId=...`; persists local `requestId` to recover interrupted claims without duplicate issuance |

## 4. Verification & Tests

- **Backend unit tests (13/13 pass, verified directly with `mvn -pl chengyinhub-admin -am -Dtest=MerchantOfferWithdrawalAdminServiceTest,ChannelWebhookAdminServiceTest,ReviewQueueAdminControllersTest -Dsurefire.failIfNoSpecifiedTests=false test`)**:
  - `ReviewQueueAdminControllersTest`: 2/2 pass (routes, `@PreAuthorize`, read-only constraint on offer-withdrawal, `@Log` on redrive, refusal envelopes)
  - `MerchantOfferWithdrawalAdminServiceTest`: 4/4 pass
  - `ChannelWebhookAdminServiceTest`: 7/7 pass
  - Total: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` (`BUILD SUCCESS`)
- **Admin UI build**:
  - Production bundle built cleanly into `/data/workspace/fe-admin-mp-out/ui-dist` (`ui-build.log`, `rc=0`).
- **Mini-program unit tests**:
  - New view/contract test (`node --test chengyinhub-xcx/tests/unit/external-game-and-reward-views.test.js`): **8/8 pass** (`fail: 0`).
  - Note on full `chengyinhub-xcx` unit run (`/data/workspace/fe-admin-mp-out/xcx-unit.log`): 17,537 pass / 18 fail across unrelated pre-existing static/audit checks (`tests/unit/**/*.test.js`, `rc=1`); the 8 tests added for this feature all pass.
- **API gate (`/data/workspace/fe-admin-mp-out/api-gate.log`)**:
  - `A=0`, `B=760`, `C=0` (0 unresolved routes).

## 5. Screenshots (Stubbed `/prod-api` via Playwright)

Generated from the built admin UI (`/data/workspace/fe-admin-mp-out/ui-dist`) with `/data/workspace/fe-admin-mp-out/shots.js` (0 `pageerror`s, all routes and drawers/dialogs verified):

| Page / State | Route | Screenshot |
|---|---|---|
| 外部游戏审核（队列列表） | `/audit-center/external-game` | [external-game-review.png](shots/external-game-review.png) |
| 外部游戏审核（详情抽屉：清单/文件/时间线） | `/audit-center/external-game` | [external-game-detail.png](shots/external-game-detail.png) |
| 外部游戏举报（列表） | `/audit-center/external-game-report` | [external-game-reports.png](shots/external-game-reports.png) |
| 工坊专业化审核（队列列表） | `/audit-center/workshop-review` | [workshop-review.png](shots/workshop-review.png) |
| 工坊专业化审核（审核记录抽屉） | `/audit-center/workshop-review` | [workshop-review-events.png](shots/workshop-review-events.png) |
| 商家供给退出（只读回执列表） | `/audit-center/offer-withdrawal` | [offer-withdrawal-list.png](shots/offer-withdrawal-list.png) |
| 商家供给退出（回执详情抽屉） | `/audit-center/offer-withdrawal` | [offer-withdrawal-detail.png](shots/offer-withdrawal-detail.png) |
| Webhook 投递（死信队列） | `/audit-center/im-webhook` | [im-webhook-dead.png](shots/im-webhook-dead.png) |
| Webhook 投递（重新投递二次确认） | `/audit-center/im-webhook` | [im-webhook-redrive-confirm.png](shots/im-webhook-redrive-confirm.png) |
