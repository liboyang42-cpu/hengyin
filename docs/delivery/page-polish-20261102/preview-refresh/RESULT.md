# Page polish preview refresh (2026-11-02)

Lane: preview-refresh. Branch `claude/page-polish-20261102-preview-refresh`, created from `claude/page-polish-20261102-preview` at `7d817793`.
That commit includes the two regression fixes (`2bd06fca` filters, `ed73d0a7` profile tags) and current master `014fc0d8` (#701, #704, #707, #708).
No product file was changed. There is no PR and nothing was merged.

## Setup

- Rig: `claude/cloud-walk-20261101-rig` (390×844 browser phone). I used a local copy of `up.sh` with `SHA` set to the preview head `7d817793`, so the backend jar and the
  AFTER bundle come from the preview branch and #704's endpoints exist. `chain_acceptance precheck` reported: merge-base `014fc0d8`, ahead 38, behind 0, JDK 21.
- **BEFORE** bundle: `origin/master` `014fc0d8`, built with the rig's `mpweb/build.sh` (410 templates, `missing []`), served on port 8098.
  **AFTER** bundle: the preview head, served on port 8097. Both run against the same backend (127.0.0.1:8099) at the same time.
- Seed: `chains.py finance`. platform, merchant_club, sale_close, player and finance all **PASS** (topic 990014 「E2E 探店日一期」).
- Docker: before dockerd started I set `mirror.gcr.io` as a registry mirror. No 429 appeared in the dockerd log. The MySQL and Redis image digests
  (`4af1f881…`, `858f009f…`) match `ci/real-storage-it-registry.json`.
- Measurements: px at 390 width. Probes use `getBoundingClientRect` and text `Range` boxes, ink to ink, the same method as measure-merchant's `measure.js`.
  For #704's two pages I also ran measure-merchant's `tools/run.mjs` (annotated stitched shots).
- Every shot in `shots/` is BEFORE (left, origin/master) | AFTER (right, preview), except the two `*-measured.png` files, which are AFTER only, annotated.
  I looked at every shot at full size.

## Regressions

**None found** in what I exercised. Both regressions from the first preview are fixed (items 1 and 2 below).

Page problems that remain (same in BEFORE and AFTER, so not regressions):
- club/detail: the floating 「加入」 button still covers 「5.0 分」 on the first contribution row at scroll 0 (`r19-club-detail.png`).
- mycanyu: the card still clips 「查看报名凭证」 at its bottom edge (`r20-mycanyu.png`).
- search2/result: the chip row is still cut at the right edge (「玩…」). It is a horizontal scroll strip (`r18-search2-result.png`).
- game-node, not playable: 「恢复接待」 sits about 3 px under the caption 「暂停期间如需退款请联系平台客服。」 (`r14-gamenode-notplayable.png`).
- ledger row: the status chip 「权益已履约 · 无现金结算」 starts at x 55, while the title and sub line start at x 51 (`r06-ledger-redemptions.png`).

Rig artifacts (not page problems):
- Profile: the hero background image is sticky, so it shows behind the scrolled sections (`r03`, `r04`). This was already noted by measure-merchant.
- Covers are fallbacks because no OSS is reachable: merchantinfo grey hero, club topic-detail 「封面暂不可用」, the #704 project card shows the grey 城瘾 logo placeholder.
- game-node: the seeded node returns business-error, and the page goes back. I stubbed `wx.navigateBack` and called the page's own `_showStation` with dummy station data.
  For example, 「样板咖啡第1站」 with task 「在店内找到菜单上的隐藏图案并拍照。」. 「玩家会做什么」 is empty because my dummy data has no field the page reads there.
- roam loading: I wrapped `wx.request` with a 6 s delay, then `reLaunch`ed to roam.
- `r05-home-mid.png` and `r05-home-end.png`: the merchant home has no fixed header, so scrolled content runs under the status bar and capsule. Same in both builds.

## 1. verify-records and aftercare filters

Look: **separate white pills again**, as in BEFORE. There is no grey track. 「已核销 0」 is 77.9 px wide and 「未核销 2」 77.6 px, both 34.5 px tall, in both builds.
The aftercare pills 「待回应」「处理中」「已完成」 are each 66.5 × 34.5 px, in both builds.

| Measure | BEFORE | AFTER |
|---|---|---|
| verify-records: title ink bottom → filter pill top | 16.3 | **9.0** |
| aftercare: title ink bottom → filter pill top | 16.3 | **9.0** |
| verify-records 「未核销 2」 (real rows): pill bottom → 「2026-11-02」 date heading | 35.2 | 22.8 |
| verify-records: date heading → first card | 12.0 | 11.9 |

- Title → filter is **9.0 px**, against a 9.1 px target. The ledger's title → hero gap measures 9.0 in this run too.
- Tab switch, row position: I sampled the filter's ink box on every animation frame for 1.5 s after each tap. That was 91 frames per switch.
  - verify-records 「已核销」→「未核销」→「已核销」: the top stays at **161.0** and the height at 34.5 the whole time. BEFORE stays at 168.3.
  - aftercare 「待回应」→「处理中」→「待回应」: the top stays at **161.0** on all frames, and the filter is never absent.
  - In BEFORE, the aftercare filter **disappears for some frames** during each switch (ink box `null`) because it was remounted. AFTER removes that.
- New, now that the seed has data: verify-records 「未核销」 shows two real rows (「未命名节点」, 「样板咖啡第1站」). Pill → list is 22.8. That equals the ledger's alert → 「收入明细」 gap (22.8).
- Aftercare has no rows in the seed, so only the empty state was seen.
- Shots: `r01-verify-unverified.png`, `r01-verify-verified.png`, `r02-aftercare.png`.

## 2. cy-profile tag spacing

| View | Measure | BEFORE | AFTER |
|---|---|---|---|
| Player 9004, dark, 「关于」 | 「兴趣标签」 title → 「添加兴趣」 pill | 12.2 | **12.2** |
| | pill bottom → 「加入时间」 cell top | 8.3 | **8.3** |
| | `.pc-tags` padding top / bottom | 0 / 0 | 0 / 0 |
| Merchant 8001 (`pages/merchant/profile/index?id=8001` → `pages/userinfo/userinfo`) | 「品类」 title → 「咖啡」 tag | 12.2 | **26.8** |
| | tag → 「营业信息」 title | 17.5 | **34.2** |
| | `.pc-tags` padding top / bottom | 0 / 0 | 14.56 / 16.64 |

The player view is unchanged from BEFORE. The merchant view keeps the A9 values. For reference, 「品牌故事」 title → text is 26.7 in both builds.
Shots: `r03-profile-player.png`, `r04-profile-merchant.png`.

## 3. Merchant home (9002) with #704

| Measure | BEFORE | AFTER |
|---|---|---|
| 「项目」 title → project card | 31.0 | 31.0 |
| project card → 「开通前置检查」 title | 26.9 | **37.3** |
| 「开通前置检查」 title → card | 20.6 | **31.0** |
| precheck card → 「我的消息」 title | 37.3 | 37.3 |
| 「我的消息」 title → message stack | 31.0 | 31.0 |
| Full scroll: last ink (message stack) → tab bar top (747.6) | 24.8 | **25.0** |
| Full scroll, stack opened: 「清空」 row bottom → tab bar top | 25.1 | **25.3** |

After the polish, every title → card gap is 31.0 and every card → next title gap is 37.3.

- Project card (#704 cover variant): 356.8 × 216.3 at x 16.6, the same in both builds. Nothing overlaps it. The next title starts 37.3 px below it.
  The 「承接中」 chip and the bell sit inside the card.
- Message stack, collapsed (2 items, `rv-msg-stack--n2`): the front item is 77.8 tall. The back item is scaled 0.94 and shifted 12.48 px down.
  It peeks 12.5 px below the front item, which is the intended stack. The unread dot shows on the front item.
- Message stack, opened (tap): two rows, each 69.4 tall with 8.3 between them. Each row has a 25 × 25 「×」 at right (x 336–360.9), placed under the time.
  The 「清空」 row comes under the rows. I saw no overlap between the 「×」, the time or the text. Positions match BEFORE within 0.2 px.
- No content sits under the tab bar at full scroll in either state.
- Shots: `r05-home-top.png`, `r05-home-mid.png`, `r05-home-end.png`, `r05-home-msg-open-end.png`.
- Not exercised: the 「都看完了」 pill, dismissing an item with 「×」 and 「清空」. These write read state to the backend, and I kept the seed unchanged for the other shots.

## 4. Ledger (9002)

| View | Measure | BEFORE | AFTER |
|---|---|---|---|
| settlement | title → hero | 21.5 | **9.0** |
| | hero → 「去我的资产」 | 20.8 | 20.8 |
| | button → 「结算到账提醒」 row | 12.4 | **20.8** |
| | alert row → 「收入明细」 | 27.0 | **22.8** |

- `?view=redemptions`: title 「核销与退款」, one row 「E2E 探店日一期 · 第1章 咖啡」. BEFORE and AFTER look the same.
- `?view=redemptions&topicId=990014` (the entry merchantinfo uses): the title becomes 「台账」. The request body to `/api/merchant/finance/redemptions` includes
  `"topicId":990014`. The three `/api/merchant/aftercare/list` refund calls are **not** made (without `topicId` they are), and that is the intended behaviour.
- Narrowing itself could not be observed, because the seed has only one topic and the list is the same.
- Settlement end of page: 「收入明细」 and 「对公结算」 empty states. Spacing is unchanged.
- Money landing was not observable: every amount is ¥0.00.
- Shots: `r06-ledger-redemptions.png`, `r06-ledger-topic.png`, `r06-ledger-settlement.png`, `r06-ledger-settlement-end.png`.

## 5. #704 pages (spacing unevenness only, no restyle proposals)

BEFORE and AFTER are **pixel-identical** on both pages (the image difference box was empty for all six frames). The polish lanes did not touch them.

`pages/topic/merchantinfo/merchantinfo?topicId=990014` as merchant 9002 (承接 view, `project-join`):
- Section gaps are uneven: quick actions → 「我的节点」 is **23.8**, but node card → 「我的章节供给」 is **16.6**. Title → card is 12.0 for both sections.
- Top of the page: hero → title 17.6, title → date chip 12.5, chip → quick actions 19.6.
- Inside the 「我的节点」 card, 「节点NPC」 (a `cy-btn variant="ghost" size="sm"`) shows no outline. Its label sits at x 45.7, while the card's other text starts at x 29.1.
  The space under it to the card edge is 28.3 px, against 16.6 px above the first line.
- Both cards: radius 16.64, padding 12.5. Tapping 「节点NPC」 opens the 「节点NPC」 sheet.
- Shots: `r07-merchantinfo.png`, `r07-merchantinfo-measured.png`.

`pages/club/topic-detail/index?topicId=990014&clubId=7001` as club 9003:
- Section gaps are uneven: action row → 「主题」 is **38.0**, but 「主题」 card → 「接下来」 is **11.4**. Title → card is 10.0 for both.
- Card shapes differ: `ctd-topic` has radius 16.64 and padding 10.4 / 12.5 / 10.4 / 10.4 (left 10.4, right 12.5). `ctd-next` has radius 20.8.
- The fixed bottom bar (「退出与暂停规则」, 「去选一场」) does not cover the last timeline item at full scroll.
- Shots: `r08-club-topic-detail-end.png`, `r08-club-topic-detail-measured.png`.
- Not exercised: merchantinfo's host view (`project-host`, `broadcast-sheet`). No merchant-hosted topic exists in the seed.

## 6. Re-shoot: confirm no change from the first preview

Each row compares this run's BEFORE → AFTER difference with the one the first preview recorded.

| Page | This run | Same as first preview? | Shot |
|---|---|---|---|
| coop finance | card inset 33.3 → 16.6; the 「可提现」 row and the button are separated | yes | `r09-coop-finance.png` |
| coop-center | first tab label x 37 → 12.5 | yes | `r10-coop-center.png` |
| marketing, full scroll | 「进入合作中心」 text bottom 735.3 → 727.3 (−8.0, as before). It is now 20.3 above the tab bar top (747.6), was 12.3. The banner ends at the bar. | yes | `r11-marketing-full-scroll.png` |
| customer | chips 「筛选」「管理」 left 16.6 → 20.8 | yes | `r12-customer.png` |
| game-node, playable (forced) | 「本站运行」 moves directly under the hero, above 「这一站怎么接待」 | yes | `r13-gamenode-playable.png` |
| game-node, not playable (forced) | pixel-identical; 「本站运行」 stays after the briefing and pause info | yes | `r14-gamenode-notplayable.png` |
| My Projects, player 9004 | light → dark, light 「去发布」 pill | yes | `r15-myproject-player.png` |
| My Projects, club 9003 | light → dark cards; 「未开始」 and 「已下架」 chips readable | yes | `r16-myproject-club.png` |
| My Projects `?scope=MERCHANT` 9002 | pixel-identical, light | yes | `r17-myproject-merchant.png` |
| search2/result `?keyword=E2E` | banner inset fixed; 「重试本页」 inline | yes | `r18-search2-result.png` |
| club/detail 7001 | visibly tighter 「本团贡献榜」 title → card (not re-measured) | yes | `r19-club-detail.png` |
| mycanyu 9009 | card inset 8 → 17 | yes | `r20-mycanyu.png` |
| my-teams 9009 | 「附近在招募」 x 20 → 17 | yes | `r21-my-teams.png` |
| roam, loading (API +6 s) | false empty 「附近暂无活动 / 打开地图寻找附近活动」 → two card placeholders | yes | `r22-roam-loading.png` |
| roam, settled | pixel-identical, 「E2E 探店日一期」 card | yes | `r23-roam-settled.png` |

## Not exercised

- Money landing (all amounts ¥0.00). Motion-only changes. I did no frame-by-frame review beyond the filter-position sampling in item 1.
- Aftercare, customer, reviews and batch-detail rows (no seed data). Ledger `topicId` narrowing across two topics (only one topic seeded).
- Merchant home: inbox 「×」, 「清空」 and the 「都看完了」 pill (backend writes). merchantinfo host view and broadcast sheet.
- My Projects ops sheet, refresh pill and toasts were not re-shot this time. The first preview covered them, and the files did not change since then.
- WeChat devtools compile. Real device fonts (the rig uses Noto CJK), safe areas, native map.

STATUS: COMPLETE
