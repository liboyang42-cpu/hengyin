# R11 growth-loops: backend and mini-program receipt (hengyin)

Branch `feature/r-growth-loops`, based on `origin/roadmap/be-integ` (contains links-push, daily-loop and location-trust).
App side: chengyin-app `cloud/r-growth-loops` (its own receipt).

## Findings

| ID | Status | What closes it |
|---|---|---|
| GRO-09 (P1) win-back off | Built; sending waits for an owner switch | New recall job `growthWinbackTask.run()` (hourly, registered as running). Triggers: streak at risk, new places in the player's explored area, a club event nearby. One message a week at most, none after two ignored, quiet hours, player opt-out, account "notifications off" and account quiet hours respected. Sends through the links-push APNs fan-out. **`growth.winback.mode` defaults to `OFF`** (see owner question 1); `SHADOW` computes and logs without sending. The old WeChat-subscribe roam recall (`feature.roamRecall.enabled`) is untouched. |
| GRO-13 (P2) invites pay only after a purchase | Closed (badge reward) | Inviter gets a badge (`INVITE_1/3/10`) when an invitee finishes their first qualifying walk with trusted location. One reward per invitee, per phone number and per device; inviter capped at 10 a month. City coin hook left in place (`InviteCityCoinHook`). Invitee sign-up points stay off (`cms_points_setting` id 6 not touched). App seat claims turned on (app receipt). |
| GRO-05 (P1) no mini-program ↔ app hand-off | Closed in source; owner hosting pending | Mini-program: Settings › 使用 App page with `<button open-type="launchApp">` (when opened from the app, scene 1069) and the universal-link page `https://www.chengyinhub.com/door` with an App Store fallback. App → mini-program: `POST /api/growth-loops/miniprogram-link` makes a WeChat URL Link (`wxa/generate_urllink`, release, 30 days, cached 7 days). Static fallback page `chengyinhub-ui/public/door/index.html`. |
| GRO-08 (P1) territory events silent | Hook ready for the coordinator | `ICityEventNotifyService` with `challenged`, `lost`, `weeklyYield`. No citygame file was changed (CITY wiring is the coordinator's). Call documented below. |

## Contract

All routes return the standard `AjaxResult`. Signed-in member unless marked anonymous.

- `GET /api/growth-loops/winback/pref` → `{enabled, serviceMode: OFF|SHADOW|ON, pushConfigured, maxPerWeek: 1, lastSentAt}`
- `POST /api/growth-loops/winback/pref` body `{"enabled": true|false}` (only key) → same shape. 400 on other keys or a non-boolean.
- `GET /api/growth-loops/invite/summary` → `{enabled, rewardKind: "BADGE", cityCoin, granted, monthlyCap, invites: [{nickname, status, at}]}`
- `POST /api/growth-loops/miniprogram-link` body `{"page": "pages/club/detail/index", "query": "id=3"}` → `{available: true, url}` or `{available: false, reason: WECHAT_NOT_CONFIGURED|WECHAT_REFUSED|WECHAT_UNREACHABLE|RESTORE_MODE}`. Page must match `(pages|subpackage*)/…` (no scheme, no `..`), query `[A-Za-z0-9_.~=&%-]{0,512}`. Rate limit 20/min per member.
- `GET /api/growth-loops/app-handoff` (anonymous) → `{universalLink, appStoreUrl|null}`.
- `POST /api/roam/finish` receipt gains `inviteReward: {outcome: WAIT|GRANTED|REFUSED, reason}` after commit, only for invited players.
- APNs payload `kind` gains `growth` (win-back, invite reward) and `city` (territory). `page` stays a mini-program source path: growth center, `/pages/activity/detail/index?id=`, `/subpackageRoam/poi-detail/index?id=`, badge wall, `/pages/roam/index` for CITY. All are in the app's reviewed page list.

### CITY notification call (for the coordinator, GRO-08)

```java
@Autowired(required = false) private com.chengyinhub.business.growthloops.ICityEventNotifyService cityNotify;

// inside the transaction that settles the claim/challenge; the push goes out only after commit
if (cityNotify != null) cityNotify.challenged(holderMemberId, cellKey, placeName); // a challenger arrived
if (cityNotify != null) cityNotify.lost(previousHolderId, cellKey, placeName);    // the place changed hands
// weekly yield job, one call per holder; the same (member, weekKey) is pushed once
if (cityNotify != null) cityNotify.weeklyYield(holderId, "2026-W46", placesHeld, coinsOrNull);
```

Never throws, never blocks, respects account "off" and quiet hours, collapses repeats per (member, place) for 30 s. Tap opens the roam tab (`/pages/roam/index`), where the territory view lives.

### City coin hook (for points-governance)

`com.chengyinhub.business.growthloops.InviteCityCoinHook#grantInviteReward(inviterId, inviteeId)`. Implement it as a Spring bean; `InviteRewardService` calls it right after the badge, in the same transaction, once per invitee (marked `===== CITY-COIN HOOK =====` in the source). Use `"invite:" + inviteeId` as the ledger idempotency key. Throwing rolls the reward back and it is retried on the next trusted walk.

## Rules in detail

**Win-back (`WinbackPolicy`, `WinbackService`)**
- Lapsed = last activity (max of login time, last streak day, last roam start) between 7 and 90 days ago.
- Streak at risk: streak ≥ 3, active yesterday, not today; only after 18:00 local.
- New places: `roam_poi` created in the last 14 days and after the player's last activity, inside a geohash-5 cell (~5 km) the player has revealed.
- Club event: public, uncancelled activity starting in the next 7 days, in a club the player belongs to or inside their explored cells.
- Priority streak → club event → new places. Each run claims the send atomically; if no device accepts it, the claim is released so the week is not used up.
- A send is "ignored" when the player has not come back 7 days later. After 2 ignored no more are sent; any return resets the count.
- Compiled limits: the gap is never less than 7 days and the ignored limit never more than 2, whatever `sys_config` says.
- Global quiet hours 21:00–09:00 Asia/Shanghai (configurable); per-account quiet hours and "off" come from the existing notification preferences.

**Invite reward (`InviteRewardService`)**
- Inviter comes from the existing one-time binding `ums_member.invite_user_id` (server-issued, written once).
- Walk must qualify (daily-loop thresholds: 500 m or 10 new cells).
- Location trust: the session has at least one clean fix and no refused, no-reward or simulated fix in `location_trust_fix`. Otherwise the player simply waits for the next walk (nothing recorded).
- Invitee must have a phone number. Refused for: phone already rewarded, any of the invitee's push tokens already rewarded, same phone as the inviter, inviter inactive, inviter over the monthly cap. Refusals are recorded and final.
- Phone and device are stored only as SHA-256 hashes.

## Files

- `chengyinhub-system/.../business/growthloops/` (new): `GrowthLoopsSettings`, `WinbackPolicy`, `WinbackService`, `GeoCell`, `InviteRewardPolicy`, `InviteRewardService`, `InviteCityCoinHook`, `ICityEventNotifyService`, `CityEventNotifier`, `MiniProgramUrlLinkService`.
- `chengyinhub-system/.../mapper/growthloops/GrowthLoopsMapper.java` + `resources/mapper/business/growthloops/GrowthLoopsMapper.xml` (new).
- `chengyinhub-system/.../push/MemberNotice.java` (new); `PushMessage` kinds `growth`, `city`; `PushPayloadBuilder` accepts them; `PushFanoutService` + `IPushFanoutService`: `notifyMemberNow`, `notifyMemberAfterCommit`, `hasPushableDevice`.
- `IPlayerProgressionService` / `PlayerProgressionServiceImpl`: `onInviteRewarded` (badge ladder, source_type 7).
- `chengyinhub-admin/.../controller/api/ApiGrowthLoopsController.java` (new); `.../web/task/GrowthWinbackTask.java` (new); `ApiRoamController.finish`: calls the invite reward after commit.
- `chengyinhub-admin/sql/migration_20261115_growth_loops.sql` (new, idempotent, rollback at the top): 4 tables, 3 badges, 13 `sys_config` rows, 1 `sys_job`.
- Mini-program: `chengyinhub-xcx/pages/shezhi/app/index.{js,json,wxml,wxss}` (new), `app.json` (page registered in the settings subpackage), `pages/shezhi/shezhi.{wxml,js}` (one row, one handler), `tests/unit/growth-loops-app-handoff.test.js` (new).
- Admin UI static: `chengyinhub-ui/public/door/index.html` (new).
- Tests (new, `chengyinhub-admin/src/test/java/com/chengyinhub/business/`): `growthloops/WinbackPolicyTest` (6), `WinbackServiceTest` (11), `InviteRewardServiceTest` (6), `CityEventNotifierTest` (3), `MiniProgramUrlLinkServiceTest` (4), `push/PushMemberNoticeTest` (3).

No route or field was removed or renamed.

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile                                   → exit 0
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='WinbackPolicyTest,WinbackServiceTest,InviteRewardServiceTest,CityEventNotifierTest,MiniProgramUrlLinkServiceTest,PushMemberNoticeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false                                                    → 33 run, 0 failures
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='Push*Test,ApiRoam*Test,Roam*Test,PlayerProgression*Test,ProgressionStreak*Test,DailyLoop*Test,ClubEventNotification*Test,Growth*Test,Apns*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false                                                    → 467 run, 0 failures, 0 errors, 45 skipped (MySQL ITs)
node tests/lint/ui-integrity.js --warn-as-error   (chengyinhub-xcx)                          → UI-GATE-0: PASS
node --test tests/unit/{growth-loops-app-handoff,app-config,backend-miniprogram-route-exists,
  ac3-cx31-backend-slash-route-exists,all-page-back-stack-contract,mainpkg-leaves-contract}.test.js → all pass (3+3+3+4+10+3)
```

## Not verified

- The migration and the new mapper SQL against MySQL (no database here). The SQL uses only existing columns: `ums_member.login_time/phone/invite_user_id`, `player_growth.streak_days/last_checkin_date`, `roam_session.started_at`, `roam_fog_tile.geohash7`, `roam_poi`, `cms_activity`, `club_member`, `push_device`, `location_trust_fix`.
- Real APNs delivery and a real `wxa/generate_urllink` call (no keys here).
- `launchApp` needs a real device opened from the app; the WeChat open-platform app must be linked to the mini-program.
- The after-commit `REQUIRES_NEW` invite reward inside a real Spring transaction (unit tests run without a transaction manager).
- The mini-program page in WeChat DevTools (no simulator here; static lint and unit checks only).

## Owner actions

1. Run `migration_20261115_growth_loops.sql` before the jar (test, then production, with the usual read-only preflight).
2. Decide on win-back sending (question 1), then set `growth.winback.mode` to `SHADOW` (check `growth_winback_log` for a few days) and later `ON`. APNs keys from links-push must be set first.
3. When the app is live on the App Store, set `growth.app.appStoreUrl` (`https://apps.apple.com/cn/app/id…`). Until then the mini-program page and `/door` say "coming soon".
4. Serve `/door` on www.chengyinhub.com: the UI build now contains `dist/door/index.html`; nginx needs `location = /door { try_files /door/index.html =404; }` (and `/prod-api` must keep proxying to the backend for the App Store lookup).
5. WeChat Open Platform: link the iOS app (bundle `com.chengyinhub.chengyinApp`, universal link `https://www.chengyinhub.com/`) to the mini-program, otherwise `launchApp` fails (the page then falls back to copying the link).
6. URL Link uses the existing mini-program `appid/secret` access token; no new secret.

## Open questions

1. **Promotional push consent.** App Store guideline 4.5.4 asks for explicit opt-in before marketing pushes. Win-back defaults to on for each player (opt-out, as the brief says), but the server switch is OFF. Confirm whether this needs an explicit opt-in prompt before `ON`.
2. **Invite reward without location fixes.** Old clients and the mini-program may send no location fix with reveals; those walks never count as trusted, so their invitees wait. Accept, or allow a server-side fix check instead?
3. **Refusals are final** (duplicate phone/device, monthly cap). Confirm, or should a cap refusal be retried next month?
4. **City coin amount** for an invite is the points-governance package's decision; the hook passes only the two member ids.
5. No hard-coded secrets found in the touched files.
