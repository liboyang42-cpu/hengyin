# S3b sponsored-quests — backend receipt (hengyin `feature/r-sponsored-quests`)

Base: `origin/roadmap/be-integ`. App half: chengyin-app `cloud/r-sponsored-quests` (see its `docs/roadmap/sponsored-quests/RESULT.md`).
Scope: brief `s3-merchant-plans.md` step 4 (sponsored quests) and the step 5 tests for it. Steps 1–3 (take rate, plans, visit billing) belong to merchant-plans and are not touched.
This run resumed after an account usage limit; the three earlier commits (bd8fa9009, 657889b88, fa31e140c) were kept and verified, not recreated.

## Findings

| ID | State | Note |
|---|---|---|
| MON-15 (P2), brand / city campaigns | **Closed for sponsored quests** | Campaign object, admin console, player read, budget ledger behind the money switch. Season pass, cosmetics and insight products stay open (not in this package). |

## What changed

- `chengyinhub-admin/sql/migration_20261115_sponsored_quests.sql` (new, idempotent, rollback at the top): `sponsored_quest_campaign` (sponsor, optional merchant id, title, content, cover, budget and spent in cents, start/end, status), `sponsored_quest_place` (campaign → published `roam_poi`), `sponsored_quest_charge` (ledger with a unique idempotency key), admin menu 赞助任务 under 商家合作 with `business:sponsoredQuest:list|edit|charge` (granted to no role). Entry added to `ci/migration-dependencies.json`.
- `chengyinhub-system/.../sponsoredquest/`:
  - `SponsoredQuestLabel` — fixed 「广告」 / "Ad". Not a column, not editable.
  - `SponsoredQuestCampaign` — admin body (no label field).
  - `SponsoredQuestService` — player read `liveInBox` (live status, inside the start/end window, published places in a ≤0.5° box, max 20). Every row goes through `labelled()`, which writes `sponsored=true`, `label=广告`, `labelEn=Ad` last, so stored data cannot drop them. A campaign with no showable place is dropped.
  - `SponsoredQuestAdminService` — list / detail / create / update / status with validation and audit; admin rows also carry the label.
  - `SponsoredQuestBudgetService` — charge ledger. Writes only while the money switch area `merchantMoney` is open (server ceiling AND stored flag); refused and writes nothing otherwise, including when only another money area is open or the switch read fails. Idempotent per ref key, never over budget.
  - `mapper/SponsoredQuestMapper` — campaign SQL only.
- `chengyinhub-admin/.../api/ApiSponsoredQuestController` — `GET /api/marketplace/sponsored-quests?minLat&maxLat&minLng&maxLng`, signed-in, rate-limited, read only. New route; no existing route or field changed.
- `chengyinhub-admin/.../business/SponsoredQuestAdminController` — `/business/sponsoredQuest` list, state, detail, create, update, status, charges (GET) and charge (POST, `charge` permission + money switch).
- `chengyinhub-ui/src/api/business/sponsoredQuest.js`, `src/views/business/sponsoredquest/index.vue` — admin console page (RuoYi components only). The label is shown read-only; the charge action is only offered while the switch is open.
- Tests: `SponsoredQuestServicesTest` (12), `SponsoredQuestNeutralityMyBatisTest` (3, H2 MySQL mode), `SponsoredQuestIsolationGuardTest` (2), `chengyinhub-ui/tests/unit/s3b-sponsored-quests-admin-contract.test.js` (3).

## Neutrality proof (step 5)

`SponsoredQuestNeutralityMyBatisTest.liveCampaignChangesNeitherPinRankNorSearchOrderNorFog` runs the real roam pin read (distance rank and keyword search) and the real fog cell reads on the same H2 data, first without a campaign, then with a live, fully funded campaign that sponsors the lowest-ranked place, and asserts the results are identical (it also checks the campaign really is live during the comparison). `SponsoredQuestIsolationGuardTest` fails if any file outside the reviewed set names the campaign tables or services, or if sponsored code names fog, ranking or search code.

## Verification (this run)

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                       → exit 0
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest='SponsoredQuest*' -Dsurefire.failIfNoSpecifiedTests=false
  SponsoredQuestServicesTest 12/0 fail, SponsoredQuestNeutralityMyBatisTest 3/0, SponsoredQuestIsolationGuardTest 2/0 → exit 0
bash ci/migration-safety-check.sh                                                → 迁移安全门禁:通过
cd chengyinhub-ui && node --test tests/unit/s3b-sponsored-quests-admin-contract.test.js → 3 pass
```

## Not verified

- The migration against a real MySQL (H2 MySQL mode only); the admin page in a browser.
- Charges with the money switch open in a real TEST deployment (unit tests only). No live payment was called; there is no sponsor payment path at all, only the internal budget ledger.

## Owner actions and open questions

1. **Counsel:** is 「赞助」 enough, or must it be 「广告」? 《互联网广告管理办法》第九条 asks for a clear 「广告」 mark, so 「广告」 is used everywhere until counsel answers. No stricter existing convention was found in either repo.
2. Grant `business:sponsoredQuest:list|edit` to the operations role, and `charge` only to finance, in the role console (granted to no role by the migration).
3. Sponsor invoicing / payment is not built: the ledger records budget use only. Whether merchant-plans' monthly invoice should include sponsored-quest charges is a coordinator question.
4. Campaigns pick places from published `roam_poi` rows only; a sponsor that is not a merchant place needs a place created first.
