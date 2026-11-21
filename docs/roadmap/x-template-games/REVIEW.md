# W7 x-template-games: review of the 19 built-in shelf games

Reviewed on `origin/roadmap/be-integ` @ `63bc29f9d` (hengyin) and `origin/roadmap/integ` (chengyin-app), 2026-11-16.
The background inventory (`docs/research/template-game-inventory-2026-08-21.md`) was re-checked against the current code. Its facts still hold for the 19, with the changes listed in "What changed since August".

## What is actually on the shelf now

The public node-game shelf (`cms_template_library`, `/api/template/list`, `/homeData`, the app's `DiscoveryTemplateBrowserView`) no longer holds only 19 official games. The seeds now put **69** official rows there:

| Seed | Rows | Notes |
|---|---:|---|
| `migration_seed_template_library_12packs_20260820.sql` | 12 | M01–M06, C01–C06 (this review) |
| `migration_seed_life_backup_games_20260820.sql` | 7 | 《生活不掉线》 G0–G6 (this review) |
| `migration_seed_celebrate_games_20260821.sql` | 8 | 《今天值得庆祝》 G0–G7, also `validation_method=0` |
| `migration_seed_merchant_game_a8_20260823.sql` / `b27_20260823` / `v51_20260827` | 8 + 27 + 7 | merchant interaction games on the advanced runtime |

This package covers the 19 the owner named. The other 50 are listed so nobody reads "19" as the whole shelf. The 8 celebrate games have the same "vm=0, the card describes choices it does not offer" problem as G0/G3/G4/G6, so finding R6 applies to them too. They are not changed here.

### What changed since August

- `migration_20260825_named_mechanic_advanced_config.sql` gave **C02** a 90-minute timer and an elapsed-time leaderboard (`scope: TOPIC`), and gave **G0** a 30-second timer. `migration_20260826_team_race_multiplayer.sql` then made C02 a real team race (multiplayer, activity + team required).
- `migration_template_merchant_guide_20260906.sql` split merchant-only text into `merchant_guide` for the 18 play packs, but **not** for these 12. Their `rule_instructions` still end in "(运营提示:…)", which players see.
- Manual photo review was removed on 2026-09-17 (`PlayPhotoCommandApplication`: "拍照任务不再有人工审核,一律机审"). Every photo is accepted on submit. **C04** still says `photo_review=1` and "等待被收入今日城市影集".
- x-rewards-evidence (merged) applied "progress is free, prizes need presence" to `arrive` and `answer`, and capped unjudged advanced sections. Plain **photo** completions (`/api/play/photo`, vm=2) and **preference** submits (vm=6) still pay `RewardPolicy.FULL` with no presence check.
- The server has a real preference engine now: `validation_method=6` (`PREFERENCE_GROUP`), `PreferenceResultEvaluator`, `/api/play/preference/{nodeId}` and `/submit`. Both the mini-program (`pages/play`) and the app (`Core/PlayPreferenceRuntime.swift`, `App/PlayPreferenceViews.swift`) play it. **M05** and G1/G2/G5 do not use it.

## How a shelf game is played

A merchant or club adopts the shelf row (`/api/template/use` / `adopt-draft`), which copies it to a private `cms_member_template`. They attach it to a stop and publish. The run freezes the judging fields (`TopicRoutePublicationCompiler.freezeNodeAnswers`). The server judges each completion method like this:

| vm | Completion | Who judges | App (iOS) | Mini-program |
|---|---|---|---|---|
| 0 | arrive | nothing to judge; arrive needs a GPS fix | `.arrive`, GPS capture then `/api/play/arrive` | arrive |
| 1 | text answer | server: `AnswerPolicy` + `BasicTextAnswer` | text field to `/answer` | yes |
| 2 | photo | **nobody**: any uploaded photo completes | camera, upload, `/photo` | yes |
| 3 | A–D option | server: exact letter against `correct_answer` | option cards to `/answer` | yes |
| 4 | scan | server: stop's `checkin_code` | QR scan to `/checkin` | yes |
| 5 | GPS arrive | server: geofence + location trust | `.arrive` | yes |
| 6 | preference group | server: deterministic result, no wrong answer | `PlayPreferenceViews` | yes |

App gaps that matter for these games (from `chengyin-app`):
- `answerReveal` and `feedbackText` are never shown (the answer receipt has no field for them; `Core/PlayContracts.swift:310-318`).
- The advanced timer's "remaining" row is a dash (`App/PlayAdvancedView.swift:94-96`). The leaderboard renders (`App/PlayAdvancedTeamViews.swift:91-130`).
- No player-side text for `rewardCeiling` / `REMOTE`.

## Game by game

### M01 今晚的暗号 (text, vm=1)
- **Player:** reaches the shop, says the passphrase to staff, and types it in.
- **Judging:** server-side text match. The seed answer is 城市醒了.
- **Playable:** app yes, mini-program yes.
- **Problems:**
  - The passphrase is a **placeholder shared by every adopter**. A merchant who adopts without editing has the same answer as every other shop, so one player can farm every copy remotely. Remote answers are now capped to XP by GAME-2 only on located linear stops.
  - The rules show "(运营提示:暗号每天可换一次;商家必须提前知道暗号和奖励…)" to the player.
  - hint1 "暗号藏在本关剧情里" is false: the story text does not contain it.

### M02 老板的秘密题 (option, vm=3)
- **Player:** reads a question about the shop and picks A–D.
- **Judging:** server-side, the letter must match.
- **Playable:** both.
- **Problems:**
  - The question and options are a generic placeholder ("这家店最早靠什么被街坊记住?", answer A). Unedited copies are farmable and untrue for the shop.
  - The operator note is shown to players.

### M03 拍下这一刻 / M06 今日搭配任务 (photo, vm=2)
- **Player:** takes a photo that should contain the shop sign or two products.
- **Judging:** **none**. Any photo of anything completes and pays full points (linear) or counts as the shop photo (free-collect).
- **Playable:** both.
- **Problems:**
  - The requirement text implies a check that does not exist.
  - Photos pay full points from anywhere.
  - The operator note is shown.

### M04 收集这枚风味印章 (scan, vm=4)
- **Player:** scans the shop's code.
- **Judging:** server (code).
- **Playable:** both.
- **Problems:** the strongest of the 12. The "集满 N 枚兑换" promise depends on the merchant setting it up; the rules show the operator note.

### M05 新品盲测局 (option, vm=3)
- **Player:** tries the new product and is asked "你觉得这款新品最突出的特点是什么?".
- **Judging:** server, **against a "correct" option A (香气)**.
- **Playable:** both.
- **Problems:**
  - A **preference question with a right answer**. A player who honestly picks 口感 is told "答案不正确" and cannot finish until they guess what the merchant wants to hear.
  - The merchant learns nothing: wrong answers are logged as failures, not as feedback.
  - The rules even say "把「正确项」设为最想传达的卖点…等问卷能力上线". That capability (vm=6) now exists.

### C01 集合点亮 (scan, vm=4)
- **Player:** scans the leader's code at the meeting point.
- **Judging:** server.
- **Playable:** both.
- **Problems:** fine as a check-in. The "今日身份" it promises is not given by anything; C06 is where roles live. The operator note is shown.

### C02 小队竞速点亮 (GPS, vm=5 + timer + leaderboard + multiplayer)
- **Player:** joins a team in an activity session, then walks to a point within 90 minutes.
- **Judging:** server geofence. The board (`GameRuntimeSessionMapper.selectLeaderboard`) ranks team sessions (`play_team`) by elapsed time.
- **Playable:** inside an activity with teams only. `migration_20260826_team_race_multiplayer.sql` enabled `multiplayer` (ROLE_BASED, 2–4 players), and the server refuses self-play ("队伍玩法仅支持活动场次") and players without a team ("请先加入本场队伍"). The app shows a dash instead of the remaining time.
- **Problems:**
  - The shelf card does not say it needs an activity and a team. A merchant who adopts it for self-play gets a stop nobody can start.
  - "最终按完成时间和任务数排名" is not what the board does: it ranks only by time.

### C03 街角谜题 (text, vm=1)
- **Player:** finds a year on a door plate.
- **Judging:** server.
- **Playable:** both.
- **Problems:** placeholder answer 1932 (same farm risk as M01); operator note shown.

### C04 城市取景任务 (photo, vm=2)
- **Player:** photographs a themed city detail.
- **Judging:** none. `photo_review=1` is ignored since 2026-09-17.
- **Problems:**
  - The copy promises review and selection ("等待被收入今日城市影集", "如做评选…"). Nothing reviews or selects.
  - Full points from anywhere.

### C05 城市接力棒 (text, vm=1)
- **Player:** types the relay phrase.
- **Judging:** server, against one fixed phrase.
- **Playable:** both.
- **Problems:**
  - **No relay exists.** Every team types the same phrase. Order and hand-over are coordinated offline by the leader.
  - The copy "将暗号交给下一队" implies the app carries the hand-over.

### C06 今日角色任务 (photo, vm=2)
- **Player:** is supposed to draw a role (导航员/观察员/记录员/交涉员) and then submit a squad photo.
- **Judging:** none for the photo. **No role is ever drawn in either client.**
- **Problems:**
  - The core mechanic is offline ("第一版角色可先线下抽卡").
  - The advanced `random` deck exists and both clients render it (`hasAdvancedPrerequisite` includes random), but the template doesn't use it.

### G0 30秒生活掉线测试 (vm=0 + 30 s timer)
- **Player:** sees a rule card that says "选情境→选片区→选可达时间→选首要考虑" and a timer, then arrives. **There are no choices to make.**
- **Judging:** none.
- **Problems:** the card describes an interaction the shelf copy cannot provide. It is honest only inside the full 《生活不掉线》 theme, where the theme board carries the flow.

### G1 现在出状况,你先选什么 / G2 我的底线三选一 / G5 值不值得现在处理 (vm=0)
- **Player:** reads "三选一" and arrives.
- **Judging:** none, because there is nothing to pick.
- **Problems:**
  - These are textbook single-choice preference questions. vm=6 can play them today, with a result card that quotes the player's choice.
  - On the shelf they are dull and dishonest.

### G3 营业时间拼图 / G4 去之前准备什么 / G6 首选失效怎么办 (vm=0)
- **Player:** reads a description of a time puzzle, a card pick, or a 30-second fallback drill, and arrives.
- **Judging:** none.
- **Problems:** the mechanics (time-window overlap, pick-three, a simulated closure) are not built anywhere. They need product work, not a seed change.

## References

- **Pokémon GO Field Research.** Tasks come from spinning a nearby PokéStop, one task per stop per day. Completion is judged by the game from in-game events, never self-reported. https://pokemongo.com/post/research/ , https://bulbapedia.bulbagarden.net/wiki/Field_Research
- **Geocaching.** A "Found it" needs a visit and a signature in the physical log. Virtual and Adventure Lab finds still need the visit. Owner-set questions are the fallback evidence. https://www.geocaching.com/blog/2019/06/geocaching-etiquette-201-finding-and-logging/ , https://www.geocaching.com/about/logging.aspx
- **Ingress missions.** Each waypoint has an objective. The passphrase objective is a creator-set question, answered while in range of the portal. Hidden sequential waypoints drive the order in-app. https://support.ingress.com/hc/en-us/articles/41140412990619-Creating-Missions , https://support.ingress.com/hc/en-us/articles/41140404493595-Ingress-Missions
- **Monopoly GO tournaments.** Short events with milestone rewards plus a **group leaderboard** of comparable players. The board is visible live and says exactly what is ranked. https://gameleap.com/articles/monopoly-go-every-event-and-tournament-explained

Lessons for these games:
- Judge on the server, or say plainly that nothing is judged (Field Research, Ingress).
- Tie prizes to being there (Geocaching, Ingress "in range").
- A race board must name exactly what it ranks and show it live (Monopoly GO).
- A creator-set passphrase only works when each creator sets their own (Ingress).

## Ranked fix list

| # | Issue | Why it matters | Fix (this package) |
|---|---|---|---|
| R1 | M05 is a preference question judged against a "correct" answer | Honest players are told they are wrong; merchants get no feedback | Seed: convert M05 to vm=6 preference group (香气/口感/造型/性价比 → result card quoting the choice). Merchant tally of results per stop (new owner-only endpoint) |
| R2 | Photo stops pay full prizes for any photo, from anywhere | Farmable; breaks "prizes need presence" | `/api/play/photo` accepts the fix; located linear stops pay XP/badge only unless the fix is within the reward radius (same GAME-2 switch and radius). Mini-program and app send the fix |
| R3 | Operator notes "(运营提示:…)" are shown to players in all 12 general games | Players read merchant instructions; M05's note admits the trick | Seed: move them into `merchant_guide` (already player-secret) |
| R4 | C04 promises manual review and selection that no longer exist; photo requirement texts imply checks | Dishonest | Seed: `photo_review=0`; honest feedback and requirement text ("照片不做自动识别,提交即完成") |
| R5 | C06's role draw is offline only | The headline mechanic is missing in both clients | Seed: add an advanced `random` role deck (4 roles, draw 1). Both clients already play `random` before the photo |
| R6 | G1/G2/G5 say "三选一" but offer no choice; G0/G3/G4/G6 describe mechanics that don't exist | Dull and dishonest on the shelf | Seed: G1/G2/G5 become vm=6 preference groups on the shelf. G0/G3/G4/G6 get an honest "rule card" label in their rules |
| R7 | C02 needs an activity and a team (since 08-26) but the card does not say so; it claims a "time and task count" ranking | Adopters build stops nobody can start in self-play | Seed copy: says it needs an activity session and a team, and that the board ranks team time. App: show the timer's remaining time instead of a dash |
| R8 | C05 relay hand-over is offline; copy implies the app does it | Misleading | Seed copy: leader sets the order; the app records each team's phrase |
| R9 | Placeholder answers (M01 城市醒了, M02 A, C03 1932, C05 星火继续) stay on adopted copies | Shared answers are farmable across shops and untrue | Authoring default: publishing an adopted copy of an official shelf game with the seed's unchanged question and answer is refused ("请换成你店里的题和答案"). Switch `template.official_placeholder_guard`, default on |
| R10 | App never shows `answerReveal`/`feedbackText` | Players miss the payoff text authors write | Left open (app answer receipt change; touches the stop-answer path the UI agent owns) |
| R11 | Preference submits (vm=6) pay FULL without a presence check on located linear stops | Same farm shape as R2 | Left open: free-collect (store scan) already gates it; linear located preference stops are rare. Recorded for the owner |
| R12 | G3/G4/G6 and the 8 celebrate games need real mechanics (time overlap, pick-three, closure drill) | Product work | Left open |
