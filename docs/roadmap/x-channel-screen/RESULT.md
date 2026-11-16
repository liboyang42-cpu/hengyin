# W7 x-channel-screen — backend and mini-program receipt (SOC-7)

Branch `feature/r-x-channel-screen` from `origin/roadmap/be-integ` @ `63bc29f9d`. The app part (SOC-1, SOC-7 app,
SOC-8) is on chengyin-app `cloud/r-x-channel-screen` (receipt at the same path there).

## Issues

| ID | Status | Notes |
|---|---|---|
| SOC-7 | Fixed (backend steps 3–4, mini-program step 5, tests step 6) | |
| SOC-1, SOC-8 | App only | SOC-8 needs nothing on the server. |

## Changes, file by file

- `chengyinhub-system/.../channelevent/ChannelEventService.java`
  - `syncByActivity(activityId)` (`REQUIRES_NEW`): a waiting card is posted when its activity passed review, closed
    when refused or removed. Only rows still at status 0 are touched; `markPosted ... AND status = 0` keeps it
    idempotent.
  - `postCard` now publishes `message.created` (message id and sender id only) through `IImRealtimePublisher` after
    commit, beside the existing push; failure only logs.
- `chengyinhub-system/.../service/impl/AuditTaskServiceImpl.java`: after the activity review decision is written, an
  after-commit hook calls `syncByActivity` (lazy `ObjectProvider`, no new dependency cycle; failure only logs and never
  touches the decision). The review was right that the hook belongs to the approve/refuse path; that path lives in
  `AuditTaskServiceImpl` (BIZ_ACTIVITY branch), not in `CmsActivityServiceImpl`.
- `chengyinhub-xcx/subpackageB/pages/im/chat/system-card-target.js`: `channelEventUrl(message)`.
- `chengyinhub-xcx/subpackageB/pages/im/chat/index.js|.wxml`: `channelEvent` flag in `decorate`; both bubbles bind
  `onChannelEventTap` (with `cy-pressed` feedback, aria label 查看活动) only for event cards; the url is rebuilt from
  the stored message.
- Tests: `ChannelEventServiceTest` (+2: approval posts once with an ids-only hint, refusal closes, still-in-review
  posts nothing, works without a publisher); `tests/unit/im-channel-event-card.test.js` (new).

No migration, no new route, no new sys_job, no `@Scheduled`, no new personal-data table, no money path.

## Verification

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile` → OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='ChannelEventServiceTest,PlayerMessagePunctuationTest,TwoNodeSchedulingTest'`
  → 25 / 2 / 4 tests, 0 failures.
- All 47 test classes that reference `AuditTaskServiceImpl` → exit 0, no failures.
- Mini-program: `node ../ci/prepare-mini-artifacts.js`, then `node --test "tests/unit/**/*.test.js"`:
  base 17919 tests / 32 failed; branch 17923 / 32 failed, the same 32 (no new failure).
  `node tests/lint/ui-integrity.js --warn-as-error`: exit 1 on base and branch with an identical finding list (pre-existing).

## Needs the owner or a device

- On TEST with pre-review on (valve or a high-risk category): post an event from a channel, approve it in the console,
  and see the card appear in the channel at once (open chats via the stream, others via push).
- Mini-program channels still open as 1:1 chats (SOC-10, PKG-C); the card tap works in that screen whatever its type.

## Overlap with the UI agent

`origin/fix/registration-list-r3` (F-19) touches registration, not these files; nothing taken from it.
