# Round 11, lane h5-play-finish: result

Branch `integ/launch-test-20261103`. My code commits: `b86980fb` (item 1), `4a65c7c4` (item 4), `a1f8c1c5` (item 3), `e5813a13` (h2's SQL file, see Checks), `460ea4e3` (item 6, compass), `45890d9b` (item 1, play page follow-up). This file is pushed after them.

**Every screenshot comes from the rig, not a phone.** The rig is `claude/cloud-walk-20261101-rig` with j4's `tools/rig-changes.diff` and j3's pointer-capture and `nav.js` fixes. It runs the real jar built from my commits (MySQL 8, Redis 7) and the real mini-program in headless Chrome at 390×844. Shots are under `integ/fix-shots/h5-*.jpg`. I looked at each one I quote.

## Rig additions (HARNESS, not product code)

| What | Why |
|---|---|
| Local OSS stand-in on 127.0.0.1:443 (`rig-bucket.oss-rig.invalid`, self-signed cert trusted in the JVM, PUT/GET/HEAD) | Photo uploads (`/api/common/uploadOSS`) need OSS. Same idea as j3's. |
| `api.weixin.qq.com` stand-in on the same server: token, `img_sec_check` and `msg_sec_check` always pass | The upload path calls WeChat's image check first. |
| Accelerometer stub in the rig's `runtime.js` (spikes while `MPWEB.shakeOn` is true) | 弹球 reads the accelerometer. |
| Mic level changed through the rig's existing `MPWEB.mic.level` | 安静挑战 and 喊一嗓子. |
| `migration_ledger` refused the tree (see Checks), so the rig's candidate checkout dropped h2's SQL file locally until my fix landed | — |

## Rig data (labelled)

| What | How |
|---|---|
| 990015 D20 + 相册, 定位到达, no 状态 | Real `/api/template/draft` + `/api/topic/v2/create`; configs from the editor's own `defaultConfig` / `applyToConfig`, with the `outcomeRules` the picker adds for D20 / 相册 / 输入挑战 and author-written 成功/失败反馈 for 选项问答 (`gencfg2.js`) |
| 990016 输入挑战 + 选项问答 with 反馈, 直接参与 | same |
| 990017 mix: 选项问答 with 反馈, 输入挑战, 预测, 翻卡, 直接参与 | same |
| 990018 选项问答 with 反馈 + 输入挑战, theme with 状态 (hp 10) | same, `journeyRules` as the editor's 状态 toggle writes it |
| 990020 D20 + 相册 with the theme's 「d20 检定」 on | same, `setCheckEnabled` from `theme-d20.js` |
| 990022 拍照审核, 找东西, 拍物成卡, 罗盘, 弹球, 安静挑战, 喊一嗓子, 计步, 直接参与 | same |
| Approval | Real admin `/business/audit/review` |
| **FIXTURE** on sale + fee rate | As j3 and g4: `cms_topic` lifecycle 3 and `fee_platform_rate = 0.0300` |
| Passes | Real `/api/compliance/consents`, quote, create, then the rig pay seam. Players 9004, 9009 |
| **FIXTURE** compass session 15 | It was created before the compass fix, so I set its `soloNoLead` marker by SQL, as a session created after the fix has it |

## Items

### 1. The blocker. Status: fixed and walked.

**What changed (`TopicRoutePublicationCompiler`, `AdvancedGameRuntimeServiceImpl`, `TopicRouteRuntimeServiceImpl`, `PlayEntryApplication`, `pages/play`):**
- A station's 结果反馈 no longer makes the whole theme a generic run. The generic runtime is compiled only when:
  - the theme has 状态 (`journeyRules.hasRuntimeState()`: d20 检定, 状态 or 思维阁); or
  - a station has settlement rules (`interactionRules` / `interactionTools`); or
  - the 结果反馈 needs the generic run: D20 检定, 预测, 分支, or a result row with costs, effects or 进入下一段.
- In a theme without the generic run, a station's 结果反馈 plays as an ordinary station:
  - the server's own PASS/FAIL is recorded as the station's pass fact;
  - 没通过 can be retried on the spot (「重新开始本次玩法」);
  - the author's 成功/失败反馈 is shown once, right after the decision.
- D20 needs the generic run, because its roll lives in the run.
  - Without 状态 the run read `sys.hp` and stopped. `syncTheme` now reads `sys.hp` only when the theme has 状态 and hp.
- Publish now refuses a D20 station when the theme's 「d20 检定」 is off: 「节点 N（name）：D20 检定要先在主题设置里打开「d20 检定」」. Before, the station opened as a black screen with no action.
- Generic personal runs: a settled station now counts as done in `/api/play/nodes`.
  - Before, the next station was never auto-arrived, so a generic theme stuck after station 1.
  - This also happened in a generic theme with 状态 that compiled generically before my change (990018).
- The two author-side refusals 「尚无通用结算完成证明」 and 「个人任务须使用真实商户到达…」 are gone for themes that no longer compile generically (990016, 990017 published on 直接参与).
  - They stay for themes that still compile generically. A D20 theme on 直接参与 is still refused; 990015/990020 use 定位到达.

**Proof (rig):**

| Theme | Result | Shots / read-back |
|---|---|---|
| 990016 输入挑战 + 选项问答 | Published on 直接参与; opens with no sys.hp stop. 输入挑战 passes. A wrong answer shows the author's 失败反馈 「再看看，换个方向想想。」, then 重新开始本次玩法, then the right answer shows 「对了，钟楼就在江边。」. Played to 尾声 and 已通关 2 点亮坐标. | h5-09, h5-10, h5-11; `game_runtime_session` 2: `gameplayDecision` FAIL then PASS; `player_node_progress` 75, 76 status 1 |
| 990017 mix (选项问答 with 反馈, 输入挑战, 预测, 翻卡) | Published on 直接参与 (refused before). All four played, 全部完成 4 坐标. | h5-16; progress 77–80 status 1 |
| 990020 D20 + 相册, d20 检定 on | D20 rolled 16 vs DC 13, passed, settled in 背包 结算任务; 相册 photo through the real upload, settled; 本局结局 尾声 「走过 · 01 D20 · 02 相册」. | h5-29, h5-30; route session 7 `completedNodeIds` [85, 86] |
| 990018 generic theme with 状态 | Still generic: arrival, 背包 开始任务, a pass shows the author's reply and settles with 结算任务 (「通过」). | quests compiled (2) |
| 990015 D20, d20 检定 off | Published before my D20 refusal existed. It opens (no sys.hp) but the D20 screen is black with 「本局未启用可信检定尝试」. A theme like it is now refused at publish (re-authored: refused with the message above). | h5-24 |

**Not verified / limits:**
- The D20 card renders inline in the story flow. In the rig its paragraphs never become visible (all `in:false`), so the screen is black (h5-24) even when the server offers ROLL_CHECK. I rolled through the card's own endpoint and body (`/api/play/advanced/action` ROLL_CHECK, SUBMIT_CHECK), labelled API leg. A phone must confirm the D20 card shows.
- Generic themes, existing behaviour I did not change: after a 结果反馈 FAIL the task sits at 「待审核 / 凭证未通过」.
  - 取消 in the 背包 did nothing in the rig, and the game screen keeps a dead 提交 (h5-21). This may strand a player in a generic theme with 状态.
  - It needs the generic-run owner's design (cancel or new attempt).
- In generic themes the station card's 开始任务 opens the game before the task is started (「请先开始当前任务」). The player has to use 开始任务 in the 背包 first.
- Tests:
  - `TopicRoutePublicationOutcomeScopeTest` (4; the first and third fail on the old code);
  - `r11-h5-plain-outcome.test.js` (3);
  - existing suites, below.

### 2. Walks g4 couldn't do. Status: partly done.

| Walk | Result |
|---|---|
| D20 三档 and 相册 played through | PASS, item 1 (D20 roll via API leg). |
| Photo check down, retake then go on | PASS. The vision check has no provider in the rig, so it is down. 「这次没能审，再拍一张」 while tries remain; after the last try 「这次没能审，先往下走」, 「本机成绩：只记探索值与徽章，不发券或积分」 and 继续故事 (h5-33). 拍物成卡 behaves the same. |
| 「等队长放行」 and 返回地图 under the director runtime | NOT DONE. j3's `tools/l6/fixtures.py` hardcodes j3's template ids and needs a club co-op and open session; I did not get to it. |
| Closed 预测 with a shop | NOT DONE, same reason as g4: a merchant on the station takes it off the solo route, and a merchant co-op session fixture was not built. |

### 3. Chain. Status: script changed as asked; the chain then fails at the sale review, needs owner.

- `chain_sale_close.py`: the club's open-session now runs after SELLING and the 15-field snapshot read-back (`open_sessions()`). The snapshot checks are unchanged. Test: `test_chain_sale_close.py` (6, one new).
- Rig run (`chains.py player`): platform PASS, merchant_club PASS, sale_close FAIL.
  - The sale review refuses with `SALE_TICKET_PROJECTION_MISSING`.
  - The chain theme is 自由探索 (`product_type 2`). For it, `TopicSaleFinalizationServiceImpl.assertActivityTicketProjection` requires the sessions and their tickets to exist **before** the theme can sell.
  - h4's rule refuses opening a session while the theme is in pricing / sale review (lifecycle 2).
- **So 自由探索 can't reach SELLING at all now.** The old order fails at open-session, the new order fails at the review. I did not change the rule.
- **Needs owner.** Recommended default: let open-session through at lifecycle 2 for a 自由探索 theme the club has a live co-op order on. That is the state the sale review needs. Next best: drop the projection check for 自由探索 and build the activity tickets at SELLING.

### 4. Receipt. Status: fixed.
- When the machine check holds a theme (author-only, `check_status=2`), these all answer 「已提交审核」 and return `published:false`, `reviewState:PENDING`:
  - `/api/topic/v2/create` and `/create`;
  - `/v2/update` and `/update`;
  - `/publish-draft`.
- The editor's existing `pendingReview` check then shows 「已提交审核」 with 「主题与引用的玩法将一起审核。」, not 「城市路线已发布」.
- Files: `ApiTopicController`, `TopicBundleSubmitResultVO.markHeldForReview()`.
- Tests: `ApiTopicPostReviewTest`:
  - the held create now asserts 「已提交审核」 (fails on old code);
  - a clean publish still says 「主题发布成功」;
  - a VO case.
- Not walked in the rig: the rig's content check stub passes everything, so nothing gets held.

### 5. j3 F13, F14, F16, F17, F18 and p1 F-9. Status: not done.
- p1 F-9: I started it.
  - Switching official-detail and 主办控制台 to `theme-dark` for players also needs their page config changed: fixed light first frame `#F3F4F4`, black native nav text.
  - It also needs the cover/scrolled-nav contrast contract changed. Two existing tests pin 「官方活动详情是商家工作域，必须固定使用商家浅色主题」.
  - I reverted it rather than half-do it. This also needs the owner to confirm which decision stands.
- F13, F14, F16, F17, F18: not started.
- Seen again in the walks:
  - F14's station-sheet lines 「走到这里，留意四周——故事就藏在细节里。」 / 「本站任务」 / 「到达后开启本站任务」;
  - the 计步 caption 「步数只能同步，不能填 —— …」 and 「少排 0.00 kg」 (h5-48).

### 6. Game types not played. Status: seven of eight played; one fixed dead end; three more dead ends found.

| Game | Result |
|---|---|
| 拍照审核 | Played; check down → retake → 先往下走 (h5-33). |
| 找东西 | Server leg only. The author image is a `/profile/upload` path, which the player view blanks (`imageUrl: ""`), so the frame has no size and taps do nothing (placeholder 「这里会是你上传的那张图」). Production images are OSS https, so this is HARNESS. The three hotspots via `SUBMIT_HIDDEN_OBJECT` (0–1 ratios) → readyForBase, then the station completed in the UI. |
| 拍物成卡 | Played; check down → 先往下走. |
| 罗盘 | **Dead end found and fixed.** The rig gives no compass reading, so the screen reports a sensor failure each time. Sensor stations default to `allowSkip:false` and only a lead can release a player, so a solo pass had no way on. Now a solo pass (no activity, so no lead) gets the existing 「跳过这一站」 after two failed reads: 0 score, no xp for that part, recorded as `SOLO`. Led sessions still need the author or the lead. Read-back: `player_node_progress` 94 `completion_mode=SKIPPED`, `puzzle_score=0` (h5-40). Test `SoloSensorSkipTest` (2). |
| 弹球 | Played with the accelerometer stub, 30/30 撞满了 (h5-42). Without a reading: 「动作读取已中断，重新开始会重新校准」 and **no way on**. |
| 安静挑战 | Played with the mic stub, held 15 s. With mic denied: no skip either (component throws `unavailable`). |
| 喊一嗓子 | Played, mic level raised for 5 s. Same no-skip gap. |
| 计步 | 「同步微信运动」 with WeRun denied does nothing: no message, **no way on** (h5-48). |
| 九宫格 | Not authored: the picker key `bingo` has no section in `node-game-catalog`, so the editor's own config path can't build it. |

- **Dead ends left (needs a decision, I recommend the compass rule for all four):**
  - On a solo pass, 弹球, 安静挑战, 喊一嗓子 and 计步 have no skip when the phone lacks the sensor or the player denies the mic or WeRun.
  - `SKIPPABLE_SENSOR_SECTIONS` holds only `compass`.
  - The fix is the same rule per game: the client reports failed reads, the server allows a 0-score skip after two. That touches four components.
- **Only a phone proves:** the real compass, accelerometer, microphone levels, WeRun decrypt, camera, and the vision check passing.

### 7. j4's unwalked list. Status: not done.

## Checks
- **Backend**, wide run on my code: 205 test classes, 2,398 tests, 0 failures. The selection was `*Play*Test, *AdvancedGame*Test, *TopicRoute*Test, *Gameplay*Test, *TopicPublication*Test, ApiTopic*Test, *TopicCreate*Test`, plus a sensor selection and the new tests.
- **Mini:**
  - Full unit run: 16,161 tests, 1 failure.
    - The failure was `object-card-reveal-contract`, which pins `pickPlayKit(Object.assign({}, view, { placeName }))`. Fixed in `45890d9b` and the file re-run green.
  - `ui-integrity.js`: `UI-GATE-0:PASS`.
  - `build-action-ledger.js`: rebuilt with `--write` in `45890d9b`; only line numbers in `pages/play/index.js` moved; 5,712 controls.
  - Main package by preflight: 2.97 MB, under the 3.00 MB ratchet.
- No migration. No `@Test` added or removed in a gated suite (`ci/funds_it_gate.py` untouched). No money rule changed.
- **h2's leftover:** `docs/.../proof/round11-h2/stored-text-precheck.sql` made `ci/migration_ledger.audit_inventory` refuse the whole tree.
  - This broke `build_schema.py` (rig) and CI's "Migration safety gate".
  - I classified it `AUXILIARY_NOT_EXECUTED` (`e5813a13`). That gate went from red (on `6a8e8f7e`) to green (on `e5813a13`).
- **CI on `e5813a13`** (items 1, 3, 4):
  - Funds MySQL IT, Redis recovery and Mini followup regression: green.
  - GitHub CI red in three jobs:
    - gitleaks: `subpackageRoam/components/roam-atmos-entry/index.js:8`, commit `7f3ce957`, not mine; red before my push too;
    - Mini static gates: `merchantinfo.js` `hostMerchants` [C] and `session-picker` `ticketsError` U6, not my files; red before my push too;
    - unit shard 3/20: the placeName test, fixed in `45890d9b`.
- **CI on `45890d9b`** (contains all my code):
  - Funds MySQL IT, Redis recovery, Mini followup regression: green.
  - GitHub CI: 28 of 30 jobs green, including every mini unit shard and the migration safety gate.
  - The two red jobs are the same as on the commits before mine and not from my files: gitleaks (`roam-atmos-entry`, commit `7f3ce957`) and Mini static gates (`merchantinfo` `hostMerchants`, `session-picker` `ticketsError`).
- Secrets: my diff has no keys or tokens; the rig cert and stand-ins live outside the repo.

STATUS: PARTIAL - items 1, 4 done; item 3 runs into a 自由探索 sale deadlock that needs the owner; item 2 led-session and closed-预测-with-shop walks, item 5 (F13–F18, F-9) and item 7 not done; 弹球/安静/喊一嗓子/计步 have no solo skip (compass fixed); CI on 45890d9b green except two jobs red from other lanes (gitleaks, static gates)
