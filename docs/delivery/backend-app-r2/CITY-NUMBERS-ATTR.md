# City Monopoly numbers + Attributes v2 — backend delivery (`feature/city-numbers-attr`)

- **Branch**: `feature/city-numbers-attr`, based on `origin/feature/sys-citygame` (`eb610901a`).
- **Spec**: `origin/docs/city-monopoly-spec:docs/delivery/backend-app-r2/CITY-MONOPOLY-SPEC.md` (v1.0, 2026-11-14), with the owner's ruling of 2026-11-15.
- **Currency**: **one currency, 积分** (`ums_member.point` + `ums_member_points_detail`, through the existing `CityGameMapper` points statements). Every 城币/`CITY_COIN` amount in the spec is the same number of 积分. There is no coin or wallet table. All caps, claim limits, protection, decay, anti-self-farming and alt-account rules are kept.
- **Not deployed.** No secrets. No ledger or gitleaks files touched. Nothing was pushed to `main` or `master`.

## 1. What was built

| Area | Where |
|---|---|
| Approved numbers (single source of truth) | `chengyinhub-system/src/main/resources/citygame/city-monopoly-defaults.properties` (285 keys = every number in spec §6.1 YAML, plus `enabled` and `season.epoch`) |
| Admin-tunable config | `CityMonopolyConfig` (typed, validated, fails closed to the default), `CityMonopolyConfigService` (defaults ← `sys_config` overrides ← per-board `city_game_board.config_json`; 60 s cache; `rulesHash` binds quotes) |
| Pure rules | `CityMonopolyRules` (tiers, upgrades, rent, multipliers + cap, owner daily cap, visit eligibility, claim checks, protection/shield, challenge fee/DC/mods/d20/fee split, rerolls, decay, upkeep, chance, seasons, scores, leaderboard window, season rewards) |
| Runtime | `CityMonopolyService` (quotes, receipts, claim/upgrade/challenge commands, rent hook, nightly settle/upkeep/decay/abuse, scout, shield, chance, stipend, shop, reads, leaderboards, season settlement) |
| Hooks | `CityAttestVerifier` (App Attest), `CityTaskVerifier` (server-verified in-place tasks) |
| Attributes v2 | `AttributeRules` (curve, soft cap, sources, titles, archetypes, imprint, backfill), `MemberAttributeService` (idempotent AXP, levels, titles, archetype, imprint, backfill) |
| Existing paths rewired | `RoamTerritoryService` (legacy client-priced claim closed while enabled; every visitor rent goes through `CityMonopolyService.onVisit`), `ApiRoamController#checkin` (+`hAcc`, `deviceKey`; explore AXP for a first-lit POI) |
| Controllers | `ApiCityMonopolyController`, `ApiMemberAttributeController`, `CityMonopolyAdminController` |
| Migration | `chengyinhub-admin/sql/migration_20261124_city_numbers_attr.sql` (+ `ci/migration-dependencies.json` edge on `20261123_sys_citygame`) |

### 1.1 Rule summary (all from config)

- **Tiers** T1–T4: claim 60/120/240/400, base rent 3/5/8/12. **Upgrades** ×0.5/×0.8/×1.2 of claim cost (T1 30/48/72 … T4 200/320/480; invested at Lv3 210/420/840/1400), rent multiplier 1.0/1.4/1.8/2.4; 1 per property per day, 2 per member per day; presence required.
- **Rent** on another player's check-in (≤ 50 m, accuracy ≤ 35 m): `round(base × min(4.0, U × S_set × T_time × W_env × B_team × (1 + 0.002 × charm)))`; set 1.2 (≥ 50%) / 1.5 (100%); time bands 1.1/1.0/1.2/1.0/0 (23–06 no rent); event ≤ 2.0; team 1.1 at ≥ 3 team properties in the district; weathering ×0.5 and the concentration penalty ×0.5 apply after the cap. Visitors never pay; a rent-producing check-in gives the visitor +2 积分 (max 10 per day). Rent is written **PENDING** and only becomes 积分 in the nightly settle, or is voided when flagged.
- **Owner daily cap**: `T = 200 + min(100, 2 × charm)`; first T at 100%, the excess at 50%, hard max T + 100 (300–400 per day).
- **Caps**: 20 effective visits per property per day; visitor → property 1 per day and 1 per 7 days; visitor → owner 3 per 7 days; 10 rent-producing check-ins per visitor per day; chance 2 per day × 15; claims, upgrades and challenges 3/2/3 per day; 3 challenges per property per day.
- **Claim checks**: Haversine ≤ 40 m; accuracy ≤ 25 m (merchant static QR ≤ 65 m); dwell ≥ 3 samples spanning ≥ 60 s inside the 300 s window, all within 80 m; sample speed ≤ 35 m/s (> 50 m/s audited); simulated location rejected and recorded as a spoof flag; App Attest required; in-app camera photo only, captured ≤ 120 s before the request and ≤ 30 m from it; pHash reuse (self ≤ 8) and copy (others ≤ 4); recapture score ≥ 0.7 rejected; account ≥ 3 days, ≥ Lv3, phone bound; 06:00–23:00; 3 per day; 3 failures per property per day; 24 h reclaim cooldown after a loss; holding limit 9 (+1 at explore Lv15/30/45, max 12); staff cannot claim their own store; fog gate; human review when risk ≥ 0.6 or for a deterministic 5% sample.
- **Protection**: claim 24 h (12 h in the finale week), successful defence 12 h, takeover 24 h; shield +12 h for 60 积分, once per property per 7 days, never beyond 48 h.
- **Challenge**: fee `max(30, 0.5 × invested)` (−25% in the finale week, −50% revenge for the dispossessed owner within 24 h); DC `10 + 2 × lvl + 2 × [owner/team visit ≤ 72 h] + {1 majority, 2 monopoly} − 2 × [weathered]`, max 20; mods task 1/2, insight +1 @15 / +2 @35, warm-up +1 (stamina ≥ 20 and ≥ 1 km in 60 min), team assists +1 each (max 2, max 3 at charm 30), cap 6; server d20 (SecureRandom): `d20 + mod ≥ DC`, natural 20 wins, natural 1 loses; luck rerolls 1 @10 / 2 @25 per day, pre-committed (`rerollOnFail`). Win: transfer, level −1, invested recomputed, defender gets 40% (60% burned). Loss: defender gets 30% (70% burned) + 12 h protection, challenger has a 24 h cooldown on that property. Failed task: 50% refund.
- **Decay** from the last owner visit: 7 days weathered (rent ×0.5, DC −2), day 14 level −1 then −1 every 3 days, day 21 released; reminders on days 5/12/19; lore Lv20/40 add 1/2 grace days. **Upkeep** 2% of invested per day (min 1, −5% at lore 25, −10% at lore 45); a shortfall becomes debt that is repaid from later income.
- **Seasons**: 28 days, Monday 10:00 to the 4th Sunday 22:00, then a 12 h break; ID `s{YYYY}w{ISOWEEK}`, anchored at `season.epoch=2026-11-09` (= `s2026w46`). Phases: opening (days 1–3; 300 积分 stipend once, same account gates as claiming), regular, finale (days 22–28). Solo score `Σ confirmed rent + 20 × first claims + 30 × takeovers + 10 × defences + Σ holding-day weight (1/1.5/2/3)`; team = top 4 + 5 × dice-event score; club = top 20 + 50 × districts held; leaderboard shows the top 10 plus 2 above and 2 below you. Season settlement writes ranks, grants XP and titles once, and releases every MONOPOLY property.
- **Anti-abuse**: self, same-team and merchant-staff visits never yield; the owner's own check-in only refreshes the visit clock. Visitor eligibility: ≥ 7 days, ≥ Lv3, phone bound, and ≥ 2 distinct owners or ≥ 3 off-board check-ins in 14 days. Device rule: one rent-producing account per device in 30 days (IDFV/DeviceCheck key, or `wx:{unionid}` for the mini-program); at 3 accounts on a device all their pending rent is frozen and a DEVICE signal is opened. Reciprocity (≥ 3 each way in 14 days) zeroes that pair. Concentration (7-day rent > 300 with the top 3 > 60% or HHI > 0.35) halves rent for 7 days. Rings (≥ 4 members, ≥ 80% internal rent) are suspended. Spoofing: 3 flags in 30 days → 7-day CITY ban; a repeat → season ban with the score cleared. Confirming a signal claws back this season's settled rent (debt when the balance is short).

### 1.2 Attributes v2

- Six permanent attributes: `explore, stamina, insight, charm, luck, lore`. AXP sources and amounts match spec §3.2 (the `AttributeRules.Source` enum).
- Daily soft cap S (200/150/150/120/40/150): `eff = min(x, S) + 0.25 × min(max(x − S, 0), S)`, so the hard ceiling is 1.25 S. Steps are capped at 20 per day and likes at 10 per day.
- Curve `need(L) = round5(12 × L^1.25 × 1.075^max(0, L−25))`, Lv1–50, cumulative 91,010 at Lv50; the test checks every value in the spec's table. Luck effects cap at Lv30.
- Titles at Lv10/25/40/50 (luck 10/20/30), codes `ATTR_{KEY}_{LV}` in `member_title`; one title can be equipped at a time.
- Archetype from the top two attributes, with the five named combinations; 博物家 Polymath when `(max − min) / max ≤ 0.2`.
- Imprint map `observe→insight, insight→insight, nerve→explore, body→stamina, agility→stamina, charm→charm, fortune→luck` (custom is unmapped): +1 at Lv20, +2 at Lv40, clamped to the preset maximum. **OFF by default** (global `attributes.v2.imprint.default_enabled=false`, per topic `cms_topic.persona_imprint=0`).
- Backfill is capped at Lv15 (2,185 AXP); insight and charm are capped at Lv10.

## 2. Endpoints

All player writes use `requestId` (`CityGameRules.id`) receipts: the same requestId with a different body returns 409 `REQUEST_CONFLICT`. Quotes are valid for 300 s and are bound to `rulesHash`, so a config change invalidates open quotes (`RULES_CHANGED`). Errors use the existing `AjaxResult` envelope with `errorCode`.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/city-game/territory/map?minLat&minLng&maxLat&maxLng` | Property layer. A property in a tile you have not lit returns only `{districtId, tier, geohash7}`. |
| GET | `/api/city-game/territory/property/{propertyId}` | Property card: deed photo, visits this week, rent base and multiplier; DC only if you scouted it. |
| POST | `/api/city-game/territory/claim/quote` | `{poiId}` or `{boardId, pointId}` → `{quoteId, quoteHash, cost, currency:"POINTS", protectionHours, rulesHash, expiresAt}` |
| POST | `/api/city-game/territory/claim/command` | `{requestId, quoteId, quoteHash, evidence{photoAssetId, inAppCamera, capturedAt, captureLat, captureLng, phash, recaptureScore, lat, lng, hAcc, samples[{t,lat,lng,hAcc}], simulated, merchantQr, deviceKeyId, attestAssertion}}` → `{outcome: CLAIMED/REJECTED, reasons[], humanReview, property, balance}` |
| POST | `/api/city-game/territory/upgrade/quote`, `/upgrade/command` | Owner only (owner in WHERE); presence evidence required, no photo. |
| POST | `/api/city-game/territory/challenge/quote`, `/challenge/command` | quote `{fee, revengeDiscount, dc?, task?}`; command `{…, evidence, taskSubmissionId?, rerollOnFail?}` → `{roll, nat, rerolled, mods{}, dc, total, outcome, feeSplit, property}` |
| POST | `/api/city-game/territory/scout` | 10 积分 (free from lore Lv10); returns the DC. |
| POST | `/api/city-game/territory/shield` | 60 积分; owner only. |
| GET | `/api/city-game/territory/revenue?cursor&limit` | Rent detail: `{events[{visitorMasked, propertyId, base, multipliers, raw, granted, capReason, state}], todayPending, todayAvailable, threshold, dailyMax}` |
| GET | `/api/city-game/territory/points` | 积分 HUD: `{balance, pending, earnedSeason, spentSeason, debt, holdings, holdingLimit}`. A read model, not a wallet. |
| GET | `/api/city-game/territory/request/{requestId}` | Command receipt. |
| GET | `/api/city-game/season/current` | Phase, day, countdown, my score and rank. |
| GET | `/api/city-game/season/leaderboard?scope=solo\|team\|club&seasonId` | Top 10 plus 2 above and 2 below you. |
| POST | `/api/city-game/season/stipend` | 300 积分 once per season, only on the opening days. |
| POST | `/api/city-game/chance/command` | `action=ROLL` (server d6 → task type, fixed 15) / `action=COMPLETE` (server-verified). |
| POST | `/api/city-game/shop/purchase` | `flag_skin_1..3` (300/600/1200), `inscription` (50), `district_banner` (800). |
| **changed** POST | `/api/roam/checkin` | New optional `hAcc`, `deviceKey`. A hit on a held property returns `territory{propertyId, holderMasked, yield, visitorPoints, multipliers, capReason, state, ineligibleReason}`. A first-lit POI grants explore AXP. |
| **changed** POST | `/api/roam/territory/checkin` | Visitors no longer pay a toll; rent comes from the engine. Accepts `deviceKey`. |
| **changed** POST | `/api/roam/territory/claim` | 409 `CLAIM_USE_MONOPOLY` (with the new paths) while `city.monopoly.enabled=true`. |
| **changed** | `CityArrivalService` CONFIRM | A staff-confirmed arrival counts as presence for rent. |
| GET | `/api/member/attributes` | `{attrs[{key, level, effectiveLevel, axp, levelAxp, need, todayAxp, softCap, nextUnlock}], archetype, equippedTitle, titles, seasonDelta}` |
| GET | `/api/member/attributes/ledger?key&cursor&limit` | AXP entries for the caller only. |
| POST | `/api/member/titles/equip` | `{titleCode}`, which must be owned; null unequips. |
| Admin GET/PUT | `/business/city-game/monopoly/config` | Effective values, defaults, diff and `rulesHash`. PUT validates the whole batch (any invalid key → 400 `CONFIG_INVALID`, nothing written). Requires `business:citygame:list` / `edit`. |
| Admin POST | `/business/city-game/monopoly/settle` | 03:00 batch: PENDING → AVAILABLE 积分, or VOID. |
| Admin POST | `/business/city-game/monopoly/nightly` | Upkeep, holding-day score, decay/release, reminders list, concentration and ring detection. |
| Admin POST | `/business/city-game/monopoly/season/{seasonId}/settle?city=` | After the freeze. |
| Admin GET / POST | `/business/city-game/monopoly/abuse`, `/abuse/{signalId}/review` | Review queue; confirm → void pending rent and claw back settled rent. |

## 3. Config keys

Every key is seeded into `sys_config` (`config_type='Y'`) with the approved default, so it can be edited from the RuoYi parameter page or `PUT /business/city-game/monopoly/config`. A board can override any key in `city_game_board.config_json`. Validation rules: numbers must parse and be ≥ 0 (DC deltas may be negative); `multiplier_cap ≤ 4.0`; `event_multiplier ≤ event_multiplier_max ≤ 2.0`; `city_launch_multiplier` must be in [1.0, 2.0]; `currency` can only be `POINTS`; lists, level maps, time bands and the rewards JSON must parse. Anything invalid keeps the default.

**`city.monopoly.version.*`**

| Key | Default |
|---|---|
| `city.monopoly.version` | `1` |

**`city.monopoly.enabled.*`**

| Key | Default |
|---|---|
| `city.monopoly.enabled` | `true` |

**`city.monopoly.currency.*`**

| Key | Default |
|---|---|
| `city.monopoly.currency` | `POINTS` |

**`city.monopoly.timezone.*`**

| Key | Default |
|---|---|
| `city.monopoly.timezone` | `Asia/Shanghai` |

**`city.monopoly.board.*`**

| Key | Default |
|---|---|
| `city.monopoly.board.mode` | `LIVE` |
| `city.monopoly.board.districts.min` | `6` |
| `city.monopoly.board.districts.max` | `10` |
| `city.monopoly.board.properties_per_district.min` | `4` |
| `city.monopoly.board.properties_per_district.max` | `10` |
| `city.monopoly.board.neutral_share.min` | `0.10` |
| `city.monopoly.board.neutral_share.max` | `0.20` |
| `city.monopoly.board.fog_gate` | `require_lit_tile` |

**`city.monopoly.tiers.*`**

| Key | Default |
|---|---|
| `city.monopoly.tiers.T1.claim_cost` | `60` |
| `city.monopoly.tiers.T1.base_yield` | `3` |
| `city.monopoly.tiers.T1.expected_visits_launch` | `0.6` |
| `city.monopoly.tiers.T2.claim_cost` | `120` |
| `city.monopoly.tiers.T2.base_yield` | `5` |
| `city.monopoly.tiers.T2.expected_visits_launch` | `1.2` |
| `city.monopoly.tiers.T3.claim_cost` | `240` |
| `city.monopoly.tiers.T3.base_yield` | `8` |
| `city.monopoly.tiers.T3.expected_visits_launch` | `2.4` |
| `city.monopoly.tiers.T4.claim_cost` | `400` |
| `city.monopoly.tiers.T4.base_yield` | `12` |
| `city.monopoly.tiers.T4.expected_visits_launch` | `4.0` |

**`city.monopoly.upgrade.*`**

| Key | Default |
|---|---|
| `city.monopoly.upgrade.cost_factor` | `0.5,0.8,1.2` |
| `city.monopoly.upgrade.yield_multiplier` | `1.0,1.4,1.8,2.4` |
| `city.monopoly.upgrade.dc_bonus_per_level` | `2` |
| `city.monopoly.upgrade.per_property_per_day` | `1` |
| `city.monopoly.upgrade.per_member_per_day` | `2` |

**`city.monopoly.claim.*`**

| Key | Default |
|---|---|
| `city.monopoly.claim.radius_m` | `40` |
| `city.monopoly.claim.max_h_accuracy_m` | `25` |
| `city.monopoly.claim.merchant_qr_fallback.enabled` | `true` |
| `city.monopoly.claim.merchant_qr_fallback.max_h_accuracy_m` | `65` |
| `city.monopoly.claim.dwell.min_samples` | `3` |
| `city.monopoly.claim.dwell.min_span_s` | `60` |
| `city.monopoly.claim.dwell.window_s` | `300` |
| `city.monopoly.claim.dwell.max_spread_m` | `80` |
| `city.monopoly.claim.max_speed_mps` | `35` |
| `city.monopoly.claim.teleport_audit_mps` | `50` |
| `city.monopoly.claim.reject_simulated_location` | `true` |
| `city.monopoly.claim.require_app_attest` | `true` |
| `city.monopoly.claim.photo.in_app_camera_only` | `true` |
| `city.monopoly.claim.photo.max_capture_age_s` | `120` |
| `city.monopoly.claim.photo.max_capture_to_request_m` | `30` |
| `city.monopoly.claim.photo.phash_reuse_self_max_hamming` | `8` |
| `city.monopoly.claim.photo.phash_copy_other_max_hamming` | `4` |
| `city.monopoly.claim.photo.screen_recapture_reject_score` | `0.7` |
| `city.monopoly.claim.photo.human_review_sample_rate` | `0.05` |
| `city.monopoly.claim.photo.human_review_risk_threshold` | `0.6` |
| `city.monopoly.claim.min_account_age_days` | `3` |
| `city.monopoly.claim.min_account_level` | `3` |
| `city.monopoly.claim.hours.open` | `06:00` |
| `city.monopoly.claim.hours.close` | `23:00` |
| `city.monopoly.claim.per_member_per_day` | `3` |
| `city.monopoly.claim.per_property_failed_per_day` | `3` |
| `city.monopoly.claim.reclaim_after_loss_cooldown_h` | `24` |
| `city.monopoly.claim.holding_limit.base` | `9` |
| `city.monopoly.claim.holding_limit.explore_bonus_levels` | `15,30,45` |
| `city.monopoly.claim.holding_limit.max` | `12` |

**`city.monopoly.yield.*`**

| Key | Default |
|---|---|
| `city.monopoly.yield.visit_radius_m` | `50` |
| `city.monopoly.yield.visit_max_h_accuracy_m` | `35` |
| `city.monopoly.yield.multiplier_cap` | `4.0` |
| `city.monopoly.yield.set_bonus.majority_share` | `0.5` |
| `city.monopoly.yield.set_bonus.majority` | `1.2` |
| `city.monopoly.yield.set_bonus.monopoly` | `1.5` |
| `city.monopoly.yield.time_bands` | `06:00-10:00@1.1,10:00-17:00@1.0,17:00-21:00@1.2,21:00-23:00@1.0,23:00-06:00@0.0` |
| `city.monopoly.yield.weather.light_rain` | `1.2` |
| `city.monopoly.yield.weather.moderate_rain` | `1.2` |
| `city.monopoly.yield.weather.red_alert` | `SAFETY_BLOCKED` |
| `city.monopoly.yield.event_multiplier_max` | `2.0` |
| `city.monopoly.yield.event_multiplier` | `1.0` |
| `city.monopoly.yield.city_launch_multiplier` | `1.0` |
| `city.monopoly.yield.team_bonus.min_team_props_in_district` | `3` |
| `city.monopoly.yield.team_bonus.m` | `1.1` |
| `city.monopoly.yield.charm_bonus_per_level` | `0.002` |
| `city.monopoly.yield.owner_daily.soft_threshold` | `200` |
| `city.monopoly.yield.owner_daily.charm_threshold_per_level` | `2` |
| `city.monopoly.yield.owner_daily.charm_threshold_max` | `100` |
| `city.monopoly.yield.owner_daily.over_rate` | `0.5` |
| `city.monopoly.yield.owner_daily.over_max` | `100` |
| `city.monopoly.yield.per_property_daily_visits` | `20` |
| `city.monopoly.yield.visitor_per_property_per_day` | `1` |
| `city.monopoly.yield.pair_cap.per_property_days` | `7` |
| `city.monopoly.yield.pair_cap.per_owner_per_7d` | `3` |
| `city.monopoly.yield.visitor_daily_yielding_checkins` | `10` |
| `city.monopoly.yield.visitor_reward` | `2` |
| `city.monopoly.yield.settle.mode` | `pending_then_nightly` |
| `city.monopoly.yield.settle.nightly_at` | `03:00` |

**`city.monopoly.chance.*`**

| Key | Default |
|---|---|
| `city.monopoly.chance.reward` | `15` |
| `city.monopoly.chance.per_day` | `2` |
| `city.monopoly.chance.task_table_d6` | `PHOTO,PHOTO,CITY_QUIZ,CITY_QUIZ,FIND,CHOOSE` |

**`city.monopoly.protection.*`**

| Key | Default |
|---|---|
| `city.monopoly.protection.after_claim_h` | `24` |
| `city.monopoly.protection.after_defense_h` | `12` |
| `city.monopoly.protection.after_takeover_h` | `24` |
| `city.monopoly.protection.shield.cost` | `60` |
| `city.monopoly.protection.shield.hours` | `12` |
| `city.monopoly.protection.shield.per_property_cooldown_d` | `7` |
| `city.monopoly.protection.shield.max_total_h` | `48` |

**`city.monopoly.challenge.*`**

| Key | Default |
|---|---|
| `city.monopoly.challenge.fee.rate_of_invested` | `0.5` |
| `city.monopoly.challenge.fee.min` | `30` |
| `city.monopoly.challenge.on_win.defender_comp` | `0.40` |
| `city.monopoly.challenge.on_win.burn` | `0.60` |
| `city.monopoly.challenge.on_win.level_drop` | `1` |
| `city.monopoly.challenge.on_loss.defender_comp` | `0.30` |
| `city.monopoly.challenge.on_loss.burn` | `0.70` |
| `city.monopoly.challenge.on_loss.protection_h` | `12` |
| `city.monopoly.challenge.on_loss.challenger_cooldown_h` | `24` |
| `city.monopoly.challenge.task_fail_refund` | `0.5` |
| `city.monopoly.challenge.dc.base` | `10` |
| `city.monopoly.challenge.dc.per_level` | `2` |
| `city.monopoly.challenge.dc.owner_recent_visit_h` | `72` |
| `city.monopoly.challenge.dc.recent_visit` | `2` |
| `city.monopoly.challenge.dc.majority` | `1` |
| `city.monopoly.challenge.dc.monopoly` | `2` |
| `city.monopoly.challenge.dc.weathered` | `-2` |
| `city.monopoly.challenge.dc.max` | `20` |
| `city.monopoly.challenge.mods.task_pass` | `1` |
| `city.monopoly.challenge.mods.task_perfect` | `2` |
| `city.monopoly.challenge.mods.insight` | `15:1,35:2` |
| `city.monopoly.challenge.mods.warmup_km` | `1.0` |
| `city.monopoly.challenge.mods.warmup_window_min` | `60` |
| `city.monopoly.challenge.mods.warmup_min_stamina` | `20` |
| `city.monopoly.challenge.mods.assist_per_member` | `1` |
| `city.monopoly.challenge.mods.assist_max` | `2` |
| `city.monopoly.challenge.mods.assist_radius_m` | `100` |
| `city.monopoly.challenge.mods.cap` | `6` |
| `city.monopoly.challenge.nat20_wins` | `true` |
| `city.monopoly.challenge.nat1_loses` | `true` |
| `city.monopoly.challenge.per_member_per_day` | `3` |
| `city.monopoly.challenge.per_property_per_day` | `3` |
| `city.monopoly.challenge.revenge_discount.rate` | `0.5` |
| `city.monopoly.challenge.revenge_discount.window_h` | `24` |
| `city.monopoly.challenge.scout_cost` | `10` |

**`city.monopoly.decay.*`**

| Key | Default |
|---|---|
| `city.monopoly.decay.weathered_after_d` | `7` |
| `city.monopoly.decay.weathered.yield` | `0.5` |
| `city.monopoly.decay.weathered.dc` | `-2` |
| `city.monopoly.decay.level_drop_at_d` | `14` |
| `city.monopoly.decay.level_drop_every_d` | `3` |
| `city.monopoly.decay.release_at_d` | `21` |
| `city.monopoly.decay.reminders_d` | `5,12,19` |
| `city.monopoly.decay.lore_grace_days` | `20:1,40:2` |

**`city.monopoly.upkeep.*`**

| Key | Default |
|---|---|
| `city.monopoly.upkeep.rate_of_invested_per_day` | `0.02` |
| `city.monopoly.upkeep.min_per_property` | `1` |
| `city.monopoly.upkeep.lore_discount` | `25:0.05,45:0.10` |

**`city.monopoly.dice_event.*`**

| Key | Default |
|---|---|
| `city.monopoly.dice_event.schedule` | `SAT 14:00-18:00` |
| `city.monopoly.dice_event.faces` | `1,2,3,4,5,6` |
| `city.monopoly.dice_event.weights` | `3,3,3,2,2,1` |
| `city.monopoly.dice_event.actions_per_round` | `6` |
| `city.monopoly.dice_event.max_rounds` | `4` |
| `city.monopoly.dice_event.team.min` | `2` |
| `city.monopoly.dice_event.team.max` | `4` |
| `city.monopoly.dice_event.track.min` | `12` |
| `city.monopoly.dice_event.track.max` | `20` |
| `city.monopoly.dice_event.track.hop_m.min` | `150` |
| `city.monopoly.dice_event.track.hop_m.max` | `400` |
| `city.monopoly.dice_event.holding_yield` | `2` |
| `city.monopoly.dice_event.goal_score` | `1` |
| `city.monopoly.dice_event.toll` | `10` |
| `city.monopoly.dice_event.season_weight` | `5` |
| `city.monopoly.dice_event.rewards_xp.top3` | `150` |
| `city.monopoly.dice_event.rewards_xp.all` | `50` |

**`city.monopoly.rerolls.*`**

| Key | Default |
|---|---|
| `city.monopoly.rerolls.luck_levels` | `10:1,25:2` |

**`city.monopoly.season.*`**

| Key | Default |
|---|---|
| `city.monopoly.season.length_d` | `28` |
| `city.monopoly.season.epoch` | `2026-11-09` |
| `city.monopoly.season.start` | `MON 10:00` |
| `city.monopoly.season.freeze` | `SUN 22:00` |
| `city.monopoly.season.id_format` | `s{YYYY}w{ISOWEEK}` |
| `city.monopoly.season.stipend` | `300` |
| `city.monopoly.season.phases.opening_d` | `1,3` |
| `city.monopoly.season.phases.finale_d` | `22,28` |
| `city.monopoly.season.phases.finale_challenge_discount` | `0.25` |
| `city.monopoly.season.phases.finale_claim_protection_h` | `12` |
| `city.monopoly.season.reset.properties` | `AVAILABLE` |
| `city.monopoly.season.reset.carry_rate` | `0.2` |
| `city.monopoly.season.reset.carry_cap` | `300` |
| `city.monopoly.season.score.per_first_claim` | `20` |
| `city.monopoly.season.score.per_takeover` | `30` |
| `city.monopoly.season.score.per_defense` | `10` |
| `city.monopoly.season.score.holding_day_weight` | `T1:1,T2:1.5,T3:2,T4:3` |
| `city.monopoly.season.team_score_top_n` | `4` |
| `city.monopoly.season.club_score.top_n` | `20` |
| `city.monopoly.season.club_score.per_district` | `50` |
| `city.monopoly.season.leaderboard_window.top` | `10` |
| `city.monopoly.season.leaderboard_window.around_me` | `2` |
| `city.monopoly.season.rewards.solo` | `[{"rank":1,"title":"城主·{city}·S{n}","badge":"SEASON_LORD","xp":3000},{"rankMax":10,"badge":"SEASON_TOP10","xp":1500},{"pctMax":0.01,"badge":"SEASON_GOLD","xp":2000},{"pctMax":0.10,"badge":"SEASON_SILVER","xp":800},{"pctMax":0.50,"xp":300},{"participantMinClaims":1,"badge":"SEASON_PIONEER","xp":200}]` |
| `city.monopoly.season.rewards.team_top` | `3` |
| `city.monopoly.season.rewards.club_top` | `3` |

**`city.monopoly.xp.*`**

| Key | Default |
|---|---|
| `city.monopoly.xp.claim` | `30` |
| `city.monopoly.xp.upgrade` | `10` |
| `city.monopoly.xp.takeover_win` | `40` |
| `city.monopoly.xp.defense_win` | `20` |
| `city.monopoly.xp.defense_daily_max` | `3` |

**`city.monopoly.abuse.*`**

| Key | Default |
|---|---|
| `city.monopoly.abuse.visitor_min_account_age_d` | `7` |
| `city.monopoly.abuse.visitor_min_level` | `3` |
| `city.monopoly.abuse.visitor_diversity.window_d` | `14` |
| `city.monopoly.abuse.visitor_diversity.min_distinct_owners` | `2` |
| `city.monopoly.abuse.visitor_diversity.or_min_offboard_checkins` | `3` |
| `city.monopoly.abuse.device.max_yielding_accounts_per_30d` | `1` |
| `city.monopoly.abuse.device.freeze_at_accounts` | `3` |
| `city.monopoly.abuse.reciprocity.window_d` | `14` |
| `city.monopoly.abuse.reciprocity.min_each_way` | `3` |
| `city.monopoly.abuse.reciprocity.zero_weight_d` | `14` |
| `city.monopoly.abuse.concentration.window_d` | `7` |
| `city.monopoly.abuse.concentration.min_yield` | `300` |
| `city.monopoly.abuse.concentration.top3_share` | `0.6` |
| `city.monopoly.abuse.concentration.hhi` | `0.35` |
| `city.monopoly.abuse.concentration.penalty` | `0.5` |
| `city.monopoly.abuse.concentration.penalty_d` | `7` |
| `city.monopoly.abuse.ring.internal_share` | `0.8` |
| `city.monopoly.abuse.ring.min_members` | `4` |
| `city.monopoly.abuse.spoof.flags_window_d` | `30` |
| `city.monopoly.abuse.spoof.flags_for_ban` | `3` |
| `city.monopoly.abuse.spoof.ban_d` | `7` |
| `city.monopoly.abuse.spoof.repeat` | `SEASON_BAN` |
| `city.monopoly.abuse.staff_cannot_claim_own_store` | `true` |

**`city.monopoly.kpi_alerts.*`**

| Key | Default |
|---|---|
| `city.monopoly.kpi_alerts.median_balance_over_t2_cost` | `5` |
| `city.monopoly.kpi_alerts.sink_source_ratio_min` | `0.6` |
| `city.monopoly.kpi_alerts.sink_source_window_d` | `7` |
| `city.monopoly.kpi_alerts.hardcore_p95_balance` | `1500` |
| `city.monopoly.kpi_alerts.max_price_change_per_season` | `0.2` |

**`city.monopoly.shop.*`**

| Key | Default |
|---|---|
| `city.monopoly.shop.shield` | `60` |
| `city.monopoly.shop.scout` | `10` |
| `city.monopoly.shop.flag_skin` | `300,600,1200` |
| `city.monopoly.shop.inscription` | `50` |
| `city.monopoly.shop.district_banner` | `800` |

**`attributes.v2.keys.*`**

| Key | Default |
|---|---|
| `attributes.v2.keys` | `explore,stamina,insight,charm,luck,lore` |

**`attributes.v2.curve.*`**

| Key | Default |
|---|---|
| `attributes.v2.curve.a` | `12` |
| `attributes.v2.curve.p` | `1.25` |
| `attributes.v2.curve.knee` | `25` |
| `attributes.v2.curve.tail` | `1.075` |
| `attributes.v2.curve.round_to` | `5` |
| `attributes.v2.curve.max_level` | `50` |

**`attributes.v2.daily_soft_cap.*`**

| Key | Default |
|---|---|
| `attributes.v2.daily_soft_cap.explore` | `200` |
| `attributes.v2.daily_soft_cap.stamina` | `150` |
| `attributes.v2.daily_soft_cap.insight` | `150` |
| `attributes.v2.daily_soft_cap.charm` | `120` |
| `attributes.v2.daily_soft_cap.luck` | `40` |
| `attributes.v2.daily_soft_cap.lore` | `150` |

**`attributes.v2.over_soft_rate.*`**

| Key | Default |
|---|---|
| `attributes.v2.over_soft_rate` | `0.25` |

**`attributes.v2.luck_max_effective_level.*`**

| Key | Default |
|---|---|
| `attributes.v2.luck_max_effective_level` | `30` |

**`attributes.v2.sources.*`**

| Key | Default |
|---|---|
| `attributes.v2.sources.explore.new_tile` | `1` |
| `attributes.v2.sources.explore.new_poi` | `10` |
| `attributes.v2.sources.explore.new_district` | `30` |
| `attributes.v2.sources.explore.roam_2km` | `10` |
| `attributes.v2.sources.stamina.per_km` | `10` |
| `attributes.v2.sources.stamina.per_1000_steps` | `1` |
| `attributes.v2.sources.stamina.steps_daily_max` | `20` |
| `attributes.v2.sources.stamina.session_30min` | `10` |
| `attributes.v2.sources.insight.quiz_first_try` | `5` |
| `attributes.v2.sources.insight.accuracy_window` | `20` |
| `attributes.v2.sources.insight.accuracy_factor.lt50` | `0.5` |
| `attributes.v2.sources.insight.accuracy_factor.50to80` | `1.0` |
| `attributes.v2.sources.insight.accuracy_factor.gt80` | `1.2` |
| `attributes.v2.sources.insight.puzzle_no_hint` | `10` |
| `attributes.v2.sources.insight.check_dc13_success` | `5` |
| `attributes.v2.sources.insight.photo_approved` | `5` |
| `attributes.v2.sources.insight.challenge_task_perfect` | `10` |
| `attributes.v2.sources.charm.group_play_2plus` | `10` |
| `attributes.v2.sources.charm.club_event` | `20` |
| `attributes.v2.sources.charm.stamp_exchange` | `5` |
| `attributes.v2.sources.charm.photo_like` | `1` |
| `attributes.v2.sources.charm.photo_like_daily_max` | `10` |
| `attributes.v2.sources.charm.invite_reaches_lv3` | `30` |
| `attributes.v2.sources.luck.nat20` | `5` |
| `attributes.v2.sources.luck.drop` | `5` |
| `attributes.v2.sources.luck.chance_task` | `3` |
| `attributes.v2.sources.luck.active_day` | `2` |
| `attributes.v2.sources.lore.district_3_checkins` | `20` |
| `attributes.v2.sources.lore.new_category_30d` | `5` |
| `attributes.v2.sources.lore.city_quiz` | `5` |
| `attributes.v2.sources.lore.first_claim_in_season` | `10` |
| `attributes.v2.sources.lore.visitor_yield` | `3` |
| `attributes.v2.sources.lore.owner_maintain` | `2` |
| `attributes.v2.sources.lore.upgrade` | `5` |

**`attributes.v2.imprint.*`**

| Key | Default |
|---|---|
| `attributes.v2.imprint.default_enabled` | `false` |
| `attributes.v2.imprint.thresholds` | `20:1,40:2` |
| `attributes.v2.imprint.map` | `observe:insight,insight:insight,nerve:explore,body:stamina,agility:stamina,charm:charm,fortune:luck` |

**`attributes.v2.backfill.*`**

| Key | Default |
|---|---|
| `attributes.v2.backfill.max_level` | `15` |
| `attributes.v2.backfill.insight_max_level` | `10` |
| `attributes.v2.backfill.charm_max_level` | `10` |

**`attributes.v2.title_levels.*`**

| Key | Default |
|---|---|
| `attributes.v2.title_levels` | `10,25,40,50` |

**`attributes.v2.luck_title_levels.*`**

| Key | Default |
|---|---|
| `attributes.v2.luck_title_levels` | `10,20,30` |

**`attributes.v2.archetype.*`**

| Key | Default |
|---|---|
| `attributes.v2.archetype.polymath_max_spread` | `0.2` |
| `attributes.v2.archetype.recompute_at` | `03:00` |

## 4. Migration

`chengyinhub-admin/sql/migration_20261124_city_numbers_attr.sql` follows the repo's conventions: additive changes only, a guarded `ALTER` through `information_schema` + `PREPARE`, `CREATE TABLE IF NOT EXISTS`, idempotent `INSERT … WHERE NOT EXISTS` seeds, and the rollback steps written in the header comment. It is registered in `ci/migration-dependencies.json` as `"20261124_city_numbers_attr": ["20261123_sys_citygame"]`.

- **New columns**:
  - `city_game_board`: `currency` (default `POINTS`), `mode`, `config_json`, `season_starts_at/ends_at`, `launch_multiplier`.
  - `city_game_point`: `geohash7`, `district_id`, `tier`.
  - `roam_territory`: `rules_kind` (`LEGACY`/`MONOPOLY`), `tier`, `upgrade_level`, `invested`, `district_id`, `last_owner_visit_at`, `decay_state`, `decay_levels_applied`, `claim_evidence_id`, `daily_visits(_day)`, `challenges_today/_day`, `upgrades_day`, `shield_until`, `last_shield_at`, `released_at`, `release_reason`, plus `idx_roam_territory_district`.
  - `cms_topic`: `persona_imprint` (default 0).
- **New tables**: `city_district`, `city_monopoly_quote`, `city_monopoly_receipt`, `city_points_flow` (a 积分 movement audit with UK member/kind/ref; not a balance), `city_member_season`, `city_yield_event` (UK territory/visitor/day), `city_claim_evidence`, `city_challenge`, `city_season`, `city_season_score`, `city_abuse_signal`, `city_device_account`, `member_attribute`, `member_attribute_ledger` (UK member/attr/source/source_id), `member_title`, `member_archetype`.
- **Seeds**: 285 `sys_config` rows (`city.monopoly.*`, `attributes.v2.*`).
- **Not touched**: `ums_member`/`ums_member_points_detail` schema, every financial ledger table, `ci/migration_ledger.py` and the ledger docs, `.gitleaks*`.
- Gates: `python3 ci/migration_gates.py sql chengyinhub-admin/sql/migration_20261124_city_numbers_attr.sql` → exit 0; `bash ci/migration-safety-check.sh` → exit 0 (`迁移安全门禁:通过`).
- **Not applied to any database.** DDL on TEST/production needs the `chengyin-db-migrate` read-only precheck and the owner's confirmation (AGENTS.md).

## 5. Test results

Targeted command, as given in the brief:
`LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -q -B test -pl chengyinhub-admin -am -Dtest='CityMonopolyRulesTest,AttributeRulesTest,CityMonopolyConfigDefaultsTest,CityMonopolySeasonSimulationTest,CityMonopolyServiceUnitTest' -Dsurefire.failIfNoSpecifiedTests=false`

| Class | Run | Fail | Err |
|---|---|---|---|
| `CityMonopolyRulesTest` (one test per rule) | 43 | 0 | 0 |
| `AttributeRulesTest` (full curve table, caps, sources, titles, archetypes, imprint, backfill) | 12 | 0 | 0 |
| `CityMonopolyConfigDefaultsTest` (defaults ↔ migration seeds, validation, rules hash) | 6 | 0 | 0 |
| `CityMonopolySeasonSimulationTest` (28-day Monte Carlo, 400 runs per profile and density) | 2 | 0 | 0 |
| `CityMonopolyServiceUnitTest` (legacy claim closed, owner-scoped self visit, evidence parsing, seasons) | 7 | 0 | 0 |
| **Total** | **70** | **0** | **0** |

Season simulation output. The source and sink columns are 积分 per player per day; the spec's §2.12 sink/source figures are in brackets.

```
profile density source/day sink/day sink/source maxDayExComp maxSeason endMean endP95
casual   x1     31.6     28.2   0.89 [0.85]  115     1441      94     185
regular  x1     69.7     67.8   0.97 [0.89]  253     2970      54     140
hardcore x1    141.0    139.1   0.99 [0.97]  350     6929      54     148
casual   x3     67.3     37.8   0.56 [0.55]  270     3230     826    1184
regular  x3    193.9    152.3   0.79 [0.80]  346     8562    1164    1534
hardcore x3    314.2    306.3   0.97 [0.98]  350    10190     221     721
```

What the simulation asserts:
- Daily income excluding the stipend and compensation stays ≤ 350 (rent ≤ T+100 = 300, plus visitor ≤ 20, plus chance ≤ 30).
- Mean daily source ≤ 450.
- The highest season total stays ≤ 300 + 28 × 450 = 12,900.
- Sink/source stays within [0.5, 1.0] for every profile at both densities.
- Hardcore players at launch density recycle ≥ 0.85 of what they earn, and their P95 end balance stays below the 1,500 KPI alert.
- At extreme density (×25), rent saturates at the owner daily cap.

### 5.1 Module suite

`LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B test -pl chengyinhub-admin -am` → **Tests run: 12,639, Failures: 1, Errors: 34, Skipped: 560**.

- **All 34 errors** are MySQL-backed MyBatis ITs: AuditReportLane, ClubCrmCheckinDetail, ClubDissolutionGate, ClubTopicManageStats, ComplianceF7, MerchantDashboardAggregation, MerchantNumbersAgree, O08MemberOnlyTopicLocation, PublicActivityScopeGate, PublicTopicFeedGate, PublicVisibility, RedemptionRewardClawback, TopicDraftVisibility and TopicSyncPostRestore (`*MyBatisTest`). Each fails with `Communications link failure` because this container has no MySQL server.
- **The 1 failure** is `ChapterFlowWriteServiceTest.openBundleReviewCannotBeBypassedBySwitchingToSimpleMode` (expected `IllegalStateException`, got `ServiceException`). It fails the same way on the untouched base `origin/feature/sys-citygame` (`eb610901a`; checked in a separate worktree: 44 run, 1 failure), so it predates this change and is unrelated to it.
- After the rules-hash fix, the City tests were re-run: the 70 new tests plus `CityBoardAndTerritoryUnitTest`, `CityGameRulesTest` and `MerchantCityValueReportServiceTest` all pass.

Not run: the MySQL integration tests (`*MysqlIntegrationTest`, `*MyBatisTest`, `*MySqlIT`). This container has no MySQL server, so they fail with `Communications link failure`, and AGENTS.md keeps DB tests on the dedicated TEST VPS. `SysCityGameMysqlIntegrationTest` builds `RoamTerritoryService` without the monopoly bean, so it still exercises the legacy path unchanged.

## 6. Deviations and open items

1. **One currency (owner ruling 2026-11-15).**
   - No `city_coin_account` or `city_coin_ledger`. `city_points_flow` is an idempotent audit of 积分 movements, and `/api/city-game/territory/points` is a read model over `ums_member.point`. The spec's `/api/city-game/wallet` was not added; the existing `/api/city-game/points` path already belongs to `ApiCityGameController`.
   - ⚠ Spec D1 warned that 积分 can be redeemed for cash (0.02 元 each, up to ¥50 per order). Under this ruling, rent (≤ 400 per owner per day), the visitor reward, chance rewards and the 300-point stipend all create redeemable value. Every cap and anti-abuse rule is enforced, and rent is held PENDING until the nightly risk batch, but the cash exposure the spec warned about now exists. The owner should keep that in mind before raising any cap.
2. **No season-end carry or burn.** Points are long-lived, so `season.reset.carry_rate/carry_cap` are seeded but inert. Spending inside a season is bounded by the caps (the simulation proves this), but the spec's cross-season bound (§2.12 step 3) no longer holds. The KPI alert keys are seeded, but no alerting job reads them yet.
3. **App Attest is a hook, not a verifier.** `CityAttestVerifier` has no production bean; the existing `NativeAppAttestService` only verifies the native-steps payload. With `require_app_attest=true` (the approved default), **every claim, upgrade and challenge is refused with `APP_ATTEST_REQUIRED` until a reviewed adapter ships.** An admin can switch it off in config, but that weakens a safeguard.
4. **In-place tasks are a hook.** `CityTaskVerifier` has no bean yet. Until one exists, challenges skip the task step (modifier 0, no 50% refund path) and chance tasks cannot be completed (`TASK_VERIFIER_UNAVAILABLE`).
5. **No real weather feed.** `W_env` uses the admin event multiplier only (≤ 2.0). Red-alert blocking is per district (`city_district.safety_blocked`).
6. **Nightly jobs are manual for now.** No `sys_job` was registered; the repo's gate requires reviewed registration SQL. Settle, nightly and season-settle run through the admin endpoints. Decay reminders and archetype changes are returned as lists; no push is sent yet.
7. **Ownership lives in `roam_territory` (`rules_kind='MONOPOLY'`), as merged by SYS-citygame**, not in new `city_game_property` holder columns. The board property is synced on claim through the existing `syncBoardPropertyOnClaim`.
8. **Behaviour changes on existing paths while `city.monopoly.enabled=true`:**
   - `/api/roam/territory/claim` returns 409 `CLAIM_USE_MONOPOLY`. Its client-set toll and revenue (up to 500) was an abuse hole.
   - Visitors no longer pay a toll.
   - Legacy territories also earn rent at T1 Lv0 through the capped engine.
   - A staff-confirmed CITY arrival counts as presence.
   - Setting `city.monopoly.enabled=false` restores the old behaviour.
9. **Two new keys beyond the YAML:** `city.monopoly.enabled` (kill switch) and `city.monopoly.season.epoch=2026-11-09` (28-day block alignment, = `s2026w46`).
10. **Approximations:**
    - Ring detection uses connected components of reciprocal rent edges.
    - Team assists count teammates with non-rejected evidence within 100 m in the last 10 minutes.
    - The team score's dice-event term is 0 because there is no dice-event score feed yet.
    - The `dice_event.*` numbers are seeded, but the existing DICE_EVENT runtime still reads its board columns.
11. **Not yet issued or wired:**
    - Badges (`growth_badge` `ATTR_*`, `SEASON_*`) are not issued; titles go into `member_title`.
    - Shop items have no inventory table; purchases are recorded as `SHOP` flows.
    - AXP is wired for: first-lit POI (explore), first claim, upgrade, visitor rent, owner maintenance, nat 20, perfect challenge task, chance task.
    - Not yet wired, but available through `MemberAttributeService.grant`: roam finish (tiles, km, sessions), play completion (quiz, puzzle, checks), club, stamps, likes, invites.
    - Backfill has a service method but no batch runner.
    - The play runtime does not call `imprintInitial` yet. Imprint is OFF by default, so mini-program behaviour is unchanged.
12. **Mini-program:** claims need App Attest, so claiming is App-only, matching the ruling. Mini-program visitors can still produce rent; the alt-account device key is `wx:{unionid}`.
