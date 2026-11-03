# l3-sell result: selling, checkout, publishing and honest errors

Branch `claude/walk-fix-1102-l3-sell`, from `origin/master` 014fc0d8. Draft PR to master; not merged.

Evidence in this folder:
- `shots/<ID>-before.jpg` and `shots/<ID>-after.jpg`: 390×844, JPEG q70, from the walkthrough rig (`claude/cloud-walk-20261101-rig`), **not a phone**. "Before" is the master bundle (014fc0d8), "after" is this branch's bundle, both against the same backend and data.
- `proof/*.log`: real HTTP runs in the rig with database read-backs (round 1; `*.log` is gitignored, so these stayed local). Round 2 proof is committed as `proof/p01-self-after.txt`.
- `tools/p01_city_sale.py`: the P-01 run (publish → review → organiser → pricing → sale review → session → quote → sign-up → payment callback). Round 2 adds `self` mode (club's own theme, no partner club).

Rig notes (harness only, no product code): the club lane's two runtime patches; `up.sh` takes `RIG_SHA`; Docker Hub returned 429, so dockerd used `mirror.gcr.io` for the same pinned digests; `app.py` passes dummy `CHENGYIN_OSS_*` values so approved media covers serialise (cover images themselves do not load in the rig).

| ID | Status | What changed | Files | How verified |
|---|---|---|---|---|
| P-01 / C-01 | fixed (platform-owned paid city theme reaches sale; see proof) | Traced the whole path and fixed four gates, each found by running it. See "P-01 gate list" below. Club workbench shows 「未开售 · 待开售审核」 / 「招募中 · 未开售」 instead of 「已确认」 while a theme is recruiting or in pricing. `assertCollectible` not touched. | `TopicCommercialReviewServiceImpl`, `ApiPricingController`, `PricingServiceImpl`, `TopicSaleTimeValidator`, `TopicSaleFinalizationServiceImpl`, `pages/club/topic-detail/index.js` | Unit: `TopicCommercialReviewServiceImplTest` (city sale review without canonical15; null product type still strict), `PricingDepositDueTest` (city confirm adopts authored tickets), `TopicSaleTimeValidatorTest`, `TopicSaleFinalizationServiceImplTest` (city required chapter without merchant recruitment). HTTP: `proof/p01-before.log` (master: 「缺少 canonical15 草稿，拒绝开售提审」), `proof/p01-platform-after.log`, `proof/p01-club-after.log`. Shots `C-01-*`. |
| P-05 / C-02 | fixed (safe part) + needs owner | When the quote is refused, checkout keeps the server's reason, shows it as 「暂不可报名」 and disables the button; pressing pay no longer creates an order with an empty signature (which produced 「缺少报价签名」). Gate unchanged. | `pages/activity/baoming/baoming.js`, `.wxml` | `tests/unit/p05-baoming-quote-refusal.test.js` (fails on old code). Rig: before = 「缺少报价签名，请重新进入报名页」 after a `/api/registration/create` call; after = real reason, no create call. Shots `P-05-*`. |
| P-15 / C-15 | fixed | Template-reference guard threw bare `RuntimeException`, which `createTopic` collapsed into 「主题发布失败，请稍后重试」. Plain `RuntimeException` messages now pass through `UserSafeMessage` (same rule as `GlobalExceptionHandler`); other exception types keep the generic line. Guard messages name the game: 「玩法「x」还在审核中，审核通过后才能用于发布」, 「不能引用他人的模板「x」…」. Sign-up gate codes (`SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED`, `SALE_SNAPSHOT_MISSING`) are mapped to sentences on both quote and create (the quote used to leak the raw code). Audit: remaining 「请稍后重试」 hits on publish/sign-up are true system failures (cancel, content-safety appeal) or payment-confirmation states; client publish and sign-up pages already show `res.msg`. | `ApiTopicController`, `TopicContentWriteGuard`, `ApiRegistrationController` | `TopicCreateBusinessErrorPassthroughTest` (+2), `RegistrationConsentGateTest`; HTTP C-22 run shows the plain sentence. |
| P-16 | fixed | The server rejected 31.2304,121.4737 ±1e-5, which is the real 人民广场 point, while the editor's untouched fallback is 31.230416,121.473701 (outside that tolerance). Now only the exact fallback (6 decimals) is refused, with 「站点「x」还在地图默认位置…」 (no 「:79」). The editor refuses to save the untouched map centre with 「请先拖动地图对准站点」. | `TopicBundleReviewService`, `pages/publish/utils/publish/route-map-view.js`, `pages/publish/fabu/index.js` | `TopicNodeDefaultCoordinateTest`, `tests/unit/p16-node-default-coordinate.test.js`. HTTP: `proof/p16-before.log` (master: 「节点仍是默认上海坐标，请重新在地图确认:72」); `proof/p01-platform-after.log` publishes the 人民广场 station at 31.2304,121.4737. |
| P-17 | fixed | Publish (not draft) without a cover is refused with 「请上传主题封面」. Home cards without a cover fall back to `route_city_cover.png` instead of the 「Marina」 mock-up; 「LIVE」 is 「进行中」 on the neutral primary chip. Covers pending content review are not affected: the check is on the submitted cover reference, and the read side already hides unchecked media. The CI chain fixture now uses an approved media asset (same shape as the Java IT `MediaFixture`). | `ApiTopicController`, `pages/index/index.wxml/.wxss`, `scripts/e2e/chain_platform.py` | `TopicCreateBusinessErrorPassthroughTest`, `tests/unit/p17-home-cover-fallback.test.js`, `scripts/e2e/test_chain_platform.py`; rig chains platform → merchant_club → sale_close → player all PASS on this branch. Shots `P-17-*` (fixture: two existing activities' `img_url` blanked to stand for old rows). |
| P-18 | fixed (display) + needs owner | The picker drops the auto-created multi-day window when real sessions exist, sorts by start, and with only one session shows it directly. A lone window reads 「随时可玩 · 至 M.D」. The window activity itself is kept. | `pages/topic/components/cy/session-picker/index.js` | `tests/unit/p18-session-picker-real-sessions.test.js`. Shots `P-18-*` (before: two dates incl. the 16:14 window; after: 「2026年11月5日(周四) · 14:00 集合」). |
| C-22 | fixed for ordinary free exploration; needs owner for 探店日 editions | Ordinary free exploration limits one live ticket per member **per session**; the four-store 探店日 contract (`FOUR_STORE_REDEEM`, "一期一票") and session-less self-play passes stay per theme. Completion rewards are unique per (theme, member), so a second session cannot claim twice; refund and inventory paths are untouched (the gate only reads). | `ExploreAccountTicketGate`, `CmsRegistrationMapper.xml/.java`, `CmsRegistrationServiceImpl` | `ExploreAccountTicketGateTest` (same session refused, other session allowed, shop-day per edition, self-play per theme), `ShopDayOneTicketGateTest`. HTTP on the shop-day chain theme: second session refused with 「这一期每人限购一张，你已经有票了」 (`proof/c22-p07.log`). The per-session branch is not run over HTTP: the rig's chain theme is a 探店日 edition. |
| P-07 / C-07 | fixed | The quote used 「集合前 24 小时」 while orders freeze the free-exploration rule (ticket end time) and the order page and real cancel follow that. The quote now reads the same frozen deadline. After a refund the progress shows 「支付已完成 → 退款处理中/已退款」, never 「等待支付」. | `CmsRegistrationServiceImpl.quoteRegistrationInternal`, `scene-member-order-detail/index.js` | `tests/unit/p07-order-timeline-after-refund.test.js`. HTTP (`proof/c22-p07.log`): session moved to start in 60 min (fixture; the ticket's end time is not moved, so the frozen deadline stays 2026-11-12 23:59:59); quote `refundDeadline` = order `refundInfo.deadline` = DB `refund_deadline`, `refundableNow:true`; paid through the real callback; cancel-refund accepted, `payment_status=4`. Master's quote code uses `RefundPolicy.sessionRefundDeadline(start)` (24 h before start), which gives `refundableNow:false` for this case; that is the walker's 「本单不可退」 (q11). Not re-run on a master backend. Shots `P-07-*`. |
| P-30 | partly fixed | Picker sorted by start time (with P-18). The ticket rule line still shows the ticket's own window. | session picker | P-18 test. |
| P-31 | fixed | After ticking the consent, the button no longer says 「请先勾选信息共享同意」 while the consent record is saving. The `<icon>` tick not drawing is the rig runtime. | `baoming.wxml` | `p05-baoming-quote-refusal.test.js`. Not shot. |
| P-32 | fixed | No 「积分抵扣 -¥0」 row for a ¥0 ticket. | `baoming.wxml` | `p05-…test.js`, `baoming-points-row-contract.test.js`. Not shot (no ¥0 session in rig data). |
| P-33 | fixed | The full-screen editor sheet starts below the capsule row (`top`) instead of padding its scroll content, so 「票务设置」 and cards no longer scroll under the status bar and back button. | `pages/publish/fabu/index.wxss` | `tests/unit/p33-editor-sheet-below-chrome.test.js`. Shots `P-33-*` (page 2 scrolled 160 px). |
| P-34 | fixed | Pricing preview returns `currentPrice` (highest authored ticket price, or the self-play price); the page starts there unless it is below the floor. | `PricingResult`, `PricingServiceImpl.previewTopic`, `pages/topic/pricing/index.js` | `tests/unit/p34-pricing-prefill.test.js`. Shots `P-34-*` (¥0 → ¥69). |
| P-36 | fixed | Card flag 「报名成功」; when the theme can't be read, the signup card says 「票已放进票夹，点这里查看」 and opens the wallet instead of a dead 「活动暂不可用」. | `subpackageB/pages/im/chat/index.*` | `im-route-card-unavailable-copy.test.js` (+1), `im-system-card-record.test.js`. Shots `P-36-*` (flag only; the unavailable state is unit-tested, not staged). |
| P-38 | fixed | The home city-location prompt is not shown while the privacy gate is open or usage consent is missing; it is asked after 「同意并继续」 (existing `onPrivacyGateSettled` path). | `pages/index/index.js` | `tests/unit/p38-home-city-after-privacy-gate.test.js`. Shots `P-38-*` (fresh player). |

## P-01 gate list (file:line at this branch)

Path for a paid city theme (product_type 1), in order. Gates 1–4 were hit by the run and fixed; 5 is the payment rule left as is.

1. **Sale submit demanded the four-store canonical15 draft.** `TopicCommercialReviewServiceImpl.submitSaleReview` (city branch added) and `submitSaleReviewIfReady`. canonical15 (`TopicSaleCanonicalV1.validateComplete`) requires 4 stores, 4 perks and 4 required chapters, so a city theme could never pass; `finalizeForSale` never reads canonical15 for city themes and registration reads it only for free exploration (`ExploreAccountTicketGate`). The organizer-actor error the walkers saw (`SALE_ORGANIZER_ONLY_FREE_EXPLORE`, `ProductRoleServiceImpl:118`) belongs to that same draft path and is no longer on the city path; it was not changed. 「确认终价」 now submits the city sale review at once (`ApiPricingController.afterConfirmMessage`) and says so.
2. **Pricing confirm added a second, time-less 「有人带」 ticket.** The editor sends tickets without `sub_type`; `PricingServiceImpl.confirmPrice` inserted a `guided` ticket with no times, and sale approval then failed 「ticket.startTime 与 ticket.endTime 必须同时存在」. City themes now keep the author's tickets (price checked against the floor, inventory untouched).
3. **Recruit deadline.** A club's city theme that invited its organiser club during recruiting kept `recruit_deadline` while `open_club_pool=0`; `TopicSaleTimeValidator` refused 「未开放承接时 recruitDeadline 必须为空」. Allowed when the frozen lineup has a partner (looked up only in that case).
4. **Required chapters.** `TopicSaleFinalizationServiceImpl.assertRequiredChapterCoverage` demanded merchant supply for every `required=1` chapter; the editor marks all chapters required. For city themes only chapters with merchant recruitment (`recruit_enabled=1`) need supply; free exploration unchanged.
5. **Payment qualification (not changed).** `CmsRegistrationServiceImpl.assertCollectible`: only a PLATFORM content slot can take online payment. Club-owned paid themes still get 「该产品暂不支持在线支付…」 at quote; the player sees it plainly (P-05) and the organiser notice on 开一场 is lane l6 (C-02 notice).

Other facts the run surfaced, unchanged: a club leader must bind their own club when publishing a city theme (`ApiTopicController` D1/D2), a club-bound theme never opens the club pool (`CmsTopicServiceImpl.resolveOpenClubPool`), and guided tickets need exactly one ORGANIZER coop order (`TopicSaleFinalizationServiceImpl.assertOrganizer`), which a club can only get by inviting **another** club. In round 1 a club that wanted to run its own paid city theme alone therefore still could not sell; round 2 (below) fixes that.

## Round 2 (2026-11-02, owner decisions)

| ID | Status | What changed | Files | How verified |
|---|---|---|---|---|
| P-01 (club runs its own paid city theme) | fixed | Sale finalisation (`assertOrganizer`): a city theme (guided tickets) bound to a club with **no** ORGANIZER in its frozen lineup counts the publishing club as its organiser, but only when that club exists, is not deleted, its owner is the theme's publisher, and the publisher's club-leader record is active. With one partner club the rule is unchanged (exactly one); two partners are still refused. No `coop_order` is written, nothing about payees, shares or settlement is changed, and `assertCollectible` is untouched. When the organiser confirms the final price of a paid theme that is not platform-owned, the reply says so in plain words and the pricing page shows it in a modal (it used to be a toast that closed in 0.5 s): 「注意：平台正在办理支付资质，开放前这个主题开售后玩家还不能在线付款报名」. The note reads `isPlatformOwned`, the same fact `assertCollectible` uses. | `TopicSaleFinalizationServiceImpl`, `ApiPricingController`, `pages/topic/pricing/index.js` | Unit, each red on the old code: `TopicSaleFinalizationServiceImplTest` (own club, no partner → sells, payee/compensation untouched; someone else's club, inactive leader, deleted club, no club → still 「当前=0」; partners → old rule), `ApiPricingControllerPaymentNoteTest`, `tests/unit/p01-pricing-payment-not-open-modal.test.js`. HTTP in the rig (`proof/p01-self-after.txt`, `tools/p01_city_sale.py self`): club 7001's leader publishes a paid city theme bound to 7001, no partner invited, no recruiting window → project review → PRICING → confirm reply carries the note → sale review approved → DB `lifecycle=3`, `selling_started_at` set, `coop_order` count 0, content slot still `CLUB 7001` → 7001 opens its own session → quote returns 「该产品暂不支持在线支付…如需报名请联系主办方」, no registration row. The ownership refusals are unit-tested only: over HTTP a theme bound to someone else's club always has an accepted invite, which is a partner. Shots `P-01-*`: before = the round-1 page code against this backend (the note squeezed into a toast that closes after 0.5 s; on master the note does not exist at all), after = this branch. |
| PR | done | Draft PR #715 exists (`claude/walk-fix-1102-l3-sell` → master); it was open as a draft at 09:20 UTC. | | GitHub |

Round 2 test sweep, after merging master: mini-program `node --test tests/unit/*.test.js` 15580 tests, 15579 pass; the one failure (`no-system-toast-contract`: the new modal called `wx.showModal` directly) was fixed by using `utils/modal.js`, and the page's own `cy-modal-host` now renders it (the after shot shows that host). The contract, P-01 and pricing tests pass afterwards. `UI-GATE-0:PASS`, no baseline entries added. Java (`chengyinhub-admin`, `*Topic*Test,*Pricing*Test,*Sale*Test,*Registration*Test`): 2497 run, 0 failures. The 7 errors are the `*MyBatisTest` classes that need their own MySQL, the same as round 1.

Master merged in with a normal merge (`26765567`); the action ledger conflict was resolved by rebuilding it on the merged tree (line-number shifts only).

## Test sweep

- Mini-program: `node --test tests/unit/*.test.js` from `chengyinhub-xcx/`: 15566 pass, 0 fail. `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS` (no baseline entries added; edits in baselined files keep their line numbers). Action ledger rebuilt: line-number shifts only.
- Java (`chengyinhub-admin`, `*Registration*Test,*Topic*Test,*Pricing*Test,*Sale*Test,*Explore*Test,*Refund*Test,*Quote*Test,*Checkout*Test`, 3237 tests): every failure caused by this branch fixed (four controller tests now send a cover; `TopicBundleReviewServiceTest` now uses the editor fallback point). The 7 remaining errors are `*MyBatisTest` classes that create their own MySQL database and fail with 「Communications link failure」 here (no local MySQL for them); not run.
- `scripts/e2e/test_chain_platform.py`: 11 pass. Rig chains platform → merchant_club → sale_close → player: all PASS on this branch.

## Needs owner

- **P-05 / C-02: may non-platform themes take online payment at launch?** Today `assertCollectible` lets only platform-owned content collect money (a 2026-07-16 decision recorded in code). You wrote 「为什么不能报名 应该是可以报名的啊」. Recommended default: keep the rule until the payment qualification is in place; this branch shows the player the real reason and blocks the empty order. If you open it, the change is one gate in `CmsRegistrationServiceImpl.assertCollectible` plus payout routing for club content; lane l6 removes its organiser notice.
- ~~P-01: a club running its own paid city theme~~: approved and done in round 2 (below).
- **P-01, for when online payment opens (not urgent while `assertCollectible` stays closed):** a club's own theme sold without a partner has no ORGANIZER `coop_order`, so the settlement code's organiser lead-fee line has nobody, and the residual margin is attributed to the initiator (`topic.member_id`, the club leader as a person) by the existing settlement producer. Ticket money follows the content slot (CLUB, the club itself) and nothing is paid today. Recommended default: when the merchant-account model goes live (organiser's own WeChat merchant account, platform 3%), route a club-owned theme's revenue to the club's account and skip the organiser lead-fee line; settle that in the payment work, not here.
- **C-22: 探店日 four-store editions.** Kept at one ticket per member per edition (their sale contract freezes `maxTicketPerAccount=1`). Recommended default: keep; per-session applies to ordinary free exploration.
- **P-18: the auto-created window activity.** It carries the theme's tickets for players who come without a club session; removing it would leave themes without club sessions unsellable. Recommended default: keep it as the 「随时可玩」 entry and hide it whenever real sessions exist (done).

## Not verified on a real phone

All screenshots are from the rig browser runtime. Not verified on a device: map picker drag and the 「请先拖动地图对准站点」 toast (rig map is a stand-in), real WeChat payment (rig seam calls the real callback), safe-area insets for P-33, icons drawn by `<icon>`.

## Round 3 (2026-11-02, owner review notes; restarted after the usage limit)

Nothing from the lost run had been pushed, so every item was redone from the current branch (`origin/master` already merged; `git merge origin/master` reported up to date). Shots are from the walkthrough rig (`claude/cloud-walk-20261101-rig` with the club lane's two runtime patches, `up.sh` pinned to this branch), **not a phone**. One backend (this branch) serves both bundles: "before" is the round-2 tip `702e41cd`, "after" is this branch.

| ID | Status | What changed | Files | How verified |
|---|---|---|---|---|
| P-33 | done (fixed) | Round 2 moved the full-screen sheet below the capsule row, which exposed the root `.slopes` dark background as a black band at the top. In full-screen state the root now carries `slopes--full` and `.fabu.slopes--full { background: var(--cy-color-bg-page); }`, so the status-bar area is the page colour and content still stops below the back button. The map view keeps its dark root. | `pages/publish/fabu/index.wxml`, `.wxss` | `tests/unit/p33-editor-sheet-below-chrome.test.js` (+1, fails on old code). Shots `P-33-*`: 票务设置 scrolled 160 px; before = black band, after = page colour. |
| C-14 | done (fixed) | New read-only `POST /api/club/name-check` (login required, writes nothing) using the same rule as creation (`ClubServiceImpl.checkNameForCreate` and `requireNameFree` share `isNameHeld`: pending or live, not deleted, trimmed; the caller's own clubs are skipped because creation replays them). The create page checks on leaving the name field (`bindblur`) and again when 下一步 is pressed on step 3; a taken name shows the reason under the field in the shared `cy-inline-error` (the label keeps its red *) and keeps 下一步 disabled. (`fba075a8`, from another session on this branch, moved the reason from a bare line with its own * into `cy-inline-error` because `scripts/ui-validity-gates.js` U6 refuses bare error text; kept, and the after shot is retaken on it.) Typing clears it; a stale answer is ignored; if the check cannot be reached the page does not block and the final check at creation (and `uk_club_holding_name`) still applies. The existing `aria-label="俱乐部名称，必填"` is unchanged. | `ApiClubController`, `IClubService`, `ClubServiceImpl`, `pages/club/create/index.js/.wxml/.wxss` | `ClubNameUniquenessTest` (+3: taken name gives the create message; pending/rejected/dissolved/own follow the create rule; check and create agree on every case), 12/12 pass. `tests/unit/c14-club-name-check-on-blur.test.js` (5, all fail on old code). Rig: `/api/club/name-check` called on blur (network log). Shots `C-14-*`: club leader 9008 types 「城瘾跑团」 (club 7001's name); fixture: 9008's `ums_member.role` set to `club` in the rig DB only, since the seed leaves both leaders as `player`. |
| P-34 | done (two real defects fixed) | (1) Confirming the final price of a city theme did not reach the author's tickets: `confirmPrice` only checked them against the floor, so confirming ¥79 left the ¥69 ticket and players paid ¥69. The confirmed price is now written to each authored ticket whose price differs (inventory untouched; a 0-row write rolls the confirmation back). (2) After sale the page said 「合作阵容锁定后可定价」. Preview now returns `priced` (lifecycle > 2) and the page says 「终价已确认」 instead; the disabled-button reason is 「终价已确认」. The backend refusal text 「主题未在定价阶段,不能确认终价」 is unchanged (tests pin it). Price prefill and the confirm flow were otherwise correct. | `PricingServiceImpl.confirmPrice/previewTopic`, `PricingResult`, `pages/topic/pricing/index.js/.wxml` | `PricingDepositDueTest` (+3: confirmed price written to authored tickets and an already-equal one left alone; changed ticket refuses; `priced` true only past pricing), 3 fail on old code, 7/7 pass. `tests/unit/p34-pricing-prefill.test.js` (+1). HTTP in the rig (`proof/p34-confirm-price-after.txt`, `tools/p01_city_sale.py self` with `P01_CONFIRM_PRICE=79`): author's 「领队场」 ¥69 → confirm ¥79 → DB `oms_ticket.price=79.00`, inventory 20 unchanged; after sale approval the session ticket is ¥79 and preview returns `priced:true, currentPrice:79`. Shots `P-34-*` (same sold theme; the before shot also shows ¥79 because the backend is this branch, so only the note differs). |
| P-38 | done (fixed) | The home privacy gate now also asks for home-city location: `cy-privacy-gate` takes a `location` flag (only the home page sets it); its sentence gains 「同意后将使用你的精确位置确定首页城市，不用于附近排序。」 and 「同意并继续」 reports `location:true`. The home page then records the `home_city_location` consent itself and goes straight to `wx.getLocation`, without the second 「首页城市定位」 dialog. Other pages that use the gate are unchanged (same sentence, same event payload). A previous REVOKE of location consent is still respected. When the gate is not shown (consent already given), the old single dialog still asks. **WeChat's own location permission prompt (`scope.userLocation`) is shown by WeChat and cannot be merged into ours.** | `components/cy/privacy-gate/index.js/.wxml`, `pages/index/index.js/.wxml` | `tests/unit/p38-home-city-after-privacy-gate.test.js` (+4: no dialog, consent recorded, location called; other hosts still ask; revoke respected; markup). `pr69-v3-cl24-home-privacy-gate-text.test.js` updated on purpose: it pinned the gate to one sentence, which the owner's P-38 note changes; it still requires the base sentence and allows only the location sentence, only under `location`. Shots `P-38-*` (gate), `P-38b-*` (right after 同意并继续: before = 「首页城市定位」 dialog, after = no dialog, city 上海市 from the rig's location stand-in). |
| P-01 | not done (as instructed) | No flow change; round-2 work left in place. | | |

Round 3 checks: mini-program `node --test tests/unit/*.test.js` 15599 tests, 2 failures, both in `action-ledger.test.js` pinning control totals; C-14's `bindblur` adds one event contract, so the totals were raised by one (5599 → 5600, 2725 → 2726) with a note, and that file now passes 46/46. Action ledger rebuilt after each item. `node scripts/ui-validity-gates.js`: U3–U6 pass. `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`, no baseline entries added (edits keep baselined line numbers). Java (`chengyinhub-admin`, `*Club*Test,*Pricing*Test,*Topic*Sale*Test,*Consent*Test`): 1419 run, 0 failures; 3 errors are `*MyBatisTest` classes that need their own MySQL, as in earlier rounds.

Round 3 observations, not changed:
- **P-34, a theme with several authored tickets at different prices:** the pricing page sets one guided price, and confirming writes it to every authored ticket. Needs owner: should a city theme with several price tiers keep its tiers at confirm? Recommended default: one confirmed price for all (current behaviour), since the page offers only one.
- **P-34:** on a sold theme the pricing page still shows the 带队成本 inputs and the 「有人带定价需填写成团人数」 hint. That is existing behaviour, left alone because the item did not name it.

Not verified on a real phone: WeChat's system location prompt after the gate, safe-area insets for P-33, and the keyboard closing when the name field loses focus.

## Round 4 (2026-11-03, owner review of round 3)

`origin/master` was already merged into this branch, so there was nothing new to merge. Shots come from the walkthrough rig (`claude/cloud-walk-20261101-rig` with the club lane's two runtime patches, `up.sh` pinned to this branch), **not from a phone**. One backend (this branch) serves both bundles: "before" is the round-3 tip `497875d8`, "after" is this branch.

| Item | Status | What changed | Files | Proof |
|---|---|---|---|---|
| P-34 (blue box, sold theme) | fixed | The blue 「有人带定价需填写成团人数…」 box under 重新计算地板 is gone. The problem now shows under 成团人数 as `cy-inline-error kind="error"` with the shorter text 「请填写大于 0 的成团人数」, and the 成团人数 label has the red `*`. Tapping the grey confirm button no longer repeats it in a box at the bottom: the red line under the field is the answer. On a sold theme (`priced`) the whole 带队成本 card, including 重新计算地板, is hidden. The 终价确认失败 box at the bottom is now red (`kind="error"`). | `pages/topic/pricing/index.wxml/.wxss/.js` | `tests/unit/p34-r4-pricing-tickets-and-team-size.test.js` (fails on old code). `pricing-page-gate-and-copy-contract.test.js` was updated on purpose: the grey-button answer is now the field error, not `submitError`. Shots `P-34-r4-*` (theme in pricing, team size empty) and `P-34b-r4-*` (sold theme: before shows 带队成本, after hides it). |
| Several ticket prices | fixed | **Preview** returns `tickets` (`ticketId`, `name`, `price`) for the author's own tickets. This applies to city themes, 有人带 only, while no `sub_type=guided` ticket exists: the same condition under which confirm adopts the author's tickets. **The page:** with two or more tickets, the slider card becomes 「你的终价」, with one price field per ticket. Each field is prefilled with the author's price and labelled with the ticket name and a red `*`. A price below the floor, or an empty field, shows red under that ticket and greys out the button. **Confirm** sends `ticketPrices: [{ticketId, price}]`. **Server checks:** the set must exactly match that theme's authored tickets, otherwise 「票种已变化，请刷新后重新确认终价」. That rejects another theme's ticket and a missing ticket. The floor and the 必亏闸 (guaranteed-loss check) are checked against the lowest price, so every ticket clears them. Each ticket gets its own price, and inventory is not touched. One-ticket themes, and requests without `ticketPrices`, behave as before. The owner check (`requireOwner`) and the lifecycle and supply gates are unchanged. | `PricingResult`, `IPricingService`, `PricingServiceImpl`, `ApiPricingController`, pricing page | `PricingPerTicketConfirmTest` (7), `ApiPricingTicketPricesParseTest` (3), plus the page test above. **HTTP** (`proof/p34-r4-two-tickets.txt`, `tools/p01_city_sale.py self` with `P34_TWO_TICKETS=1 P34_TICKET_PRICES=79,109`): authored 领队场 ¥69 / 20 and 夜场 ¥99 / 12. A confirm with a foreign ticket id is refused. The real confirm then reads back DB `oms_ticket` 领队场 **79.00** (20/20) and 夜场 **109.00** (12/12), with inventory unchanged. After sale approval, the club session's tickets are ¥79 and ¥109. Shots `P-34c-r4-*`, with 带队成本 280 / 4 人 → floor ¥82.36: before = one slider at ¥99 (which would have set both tickets to 99); after = two fields, 领队场 ¥69 red 「不能低于最低价 ¥82.36」, 夜场 ¥99 OK. |
| Sweep | done in part (see the list) | All 77 blue boxes that report an input problem are now red (`kind="error"`), each under its field. Pop-ups that repeated an error already shown under the field were removed. Three toast-only forms now show their errors under each field. The remaining pop-ups are listed below, each marked left with the reason. | see the list | `tests/unit/r4-input-error-under-field-sweep.test.js` (5). Three existing tests were updated on purpose: `date-range-sheet-overrange` used to require blue, `merchant-apply-state-contract` the identity failure kind, `merchant-decor-cu-g5a` the perks validity toast. Shots `S-temp-r4-*` (玩法模板: before blue, after red) and `S-addr-r4-*` (参与人信息: before toast 「请填写姓名」, after red under 姓名 and 手机号). |

### Sweep findings (`chengyinhub-xcx/pages`, input problems only)

Not included: load and network failures, permission and state notices, success messages, and yes/no confirm dialogs. Messages mirrored from the backend keep their text unchanged; where they sit in a box that was changed to red, only the box colour changed.

**Replaced: blue box → red `cy-inline-error kind="error"` under the field**
- `publish/activity/index.wxml`: 38, 46, 66, 84, 95, 114, 130, 168, 177, 186 (title, place, dates, type, description, cover, template, ticket name / price / stock).
- `topic/merchantapply/index.wxml`: 90, 103, 127, 143, 154.
- `publish/temp/index.wxml`: 84, 105, 136, 150, 180, 228, 233, 418, 687, 692, 851, 856, 881, 886, 970, 1038, 1099, 1301, 1306, 1605, 1642, 1650, 1828, 1850, 1853, 1867, 1870, 1880, 1884, 1888, 1905, 1914, 1921, 1930, 1933 (all page and sheet field errors). The `errors.finish` box at 180 is now the same red as at 210.
- `publish/fabu/index.wxml`: 1692, 1743, 1760, 1771, 1799.
- `publish/fabu/topic-detail-sheet.wxml`: 135, 218.
- `publish/fabu/survival-editor.wxml`: 17.
- `publish/fabu/runtime-structure-editor.wxml`: 20, 39, 59.
- `publish/fabu/runtime-editor.wxml`: 35.
- `publish/components/topic-state-keys/index.wxml`: 50.
- `topic/components/cy/date-range-sheet/index.wxml`: 23.
- `activity/official-organizer/index.wxml`: 72, 84.
- `coop/invite-detail/index.wxml`: 117.
- `merchant/reviews/index.wxml`: 130, 245. At 232 it is red unless the reply is still pending (「回复待确认」 is not an error, so it stays blue).
- `merchant/customer/index.wxml`: 32.
- `merchant/customer/detail/index.wxml`: 114, 125.
- `merchant/aftercare/detail/index.wxml`: 186.
- `club/create/index.js`: 351. A missing type, name or city now uses `kind:'error'`; the name field already used red.
- `merchant/apply/index.js`: 540. A failed identity check is red; network failures keep the network style.

**Replaced: pop-up removed or moved under the field**
- `publish/activity/index.js`: 519 `cyToast('请完善表单信息')` removed. The field errors are already shown.
- `publish/temp/index.js`: 3280 `toast(errors[first])` removed. It still scrolls to the first field, which already shows the error.
- `topic/merchantapply/index.js`: 415–418 (the four toasts from tapping the grey button) and 622 `toast(errors[first])` replaced. Tapping the grey button now runs `validateForm`, which writes each problem under its field.
- `coop/invite-detail/index.js`: 325 and 336 toasts removed. `draftError` already shows under the reason box.
- `addressinfo/addressinfo.js`: 159, 160, 248, 253, 259 replaced by `fieldErrors` under 姓名 and 手机号.
- `club/notify/index.js`: 185, 189 replaced by `titleError` and `contentError` under each field.
- `merchant/decor/perks/index.js`: 253–258 replaced by `fieldErrors` under name, retail price, cost, quota and validity.

**Left: these need per-field error state built on each page.** Each needs its own wiring, tests and shots; that work is not done in this round. All are pop-ups.

*Activity, club and coop pages:*
- `activity/baoming/baoming.js`: 560, 1542, 1548, 1554, 1560, 1567 (participant picker, which lives in a sheet or the addressinfo page). Also `activity/official-detail/index.js`: 577.
- `club/detail/index.js`: 1554, 1944, 3277.
- `club/event-ops/index.js`: 796, 803, 808, 812, 817, 825, 831, 838.
- `club/governance/index.js`: 424, 538.
- `club/roles/index.js`: 386, 387.
- `club/topic-detail/director.js`: 614, 616, 1038, 1117, 1206, 1249 (all inside sheets).
- `club/topic-detail/index.js`: 482.
- `coop/invite/index.js`: 617, 619, 710, 718, 723, 784.
- `gerenziliao/gerenziliao.js`: 701.

*Merchant pages:*
- `merchant/apply/index.js`: 365–371, 473, 481.
- `merchant/citynode/create/index.js`: 377–379.
- `merchant/decor/index.js`: 860, 931–933, 1047, 1053, 1169, 1173.
- `merchant/decor/ai-npc/index.js`: 382, 386, 413, 426, 725.
- `merchant/decor/coop-setting/index.js`: 82, 193, 196.
- `merchant/game-node/index.js`: 421, 439, 500, 652.

*Play, publish and search pages:*
- `play/index.js`: 2974, 5929. Also `play/utils/playkit-r5-segments.js`: 48, 79, `play/circle/index.js`: 404, 409, 421, and `play/components/playkit-dailysign/index.js`: 275.
- `publish/activity/index.js`: 481, 620 (identity check), 1076, 1141, 1148, 1435 (sheets).
- `publish/components/reward-selector/index.js`: 258.
- `publish/fabu/index.js`: 1248, 1347, 1712, 2070, 2111, 2479, 2500, 3327, 4104, 4108, 4113, 4121, 4139, 4168, 4664, 4668, 5601, 5610, 5672, 6266, 6268, 6454, 6462, 6945, 7008, 6958, 7011, 7318, 7509, 7571, 7608, 8009, 8524.
- `publish/fabu/theme-runtime.js`: 238, 261, 318, 390, 403, 412, 452, 601, 625, 631, 653. Also `publish/fabu/theme-d20-page.js`: 111, 118, 119.
- `publish/temp/index.js`: 1111, 1130, 2550, 2762.
- `search/search2/index.js`: 118, 322, 347, 452, 456. Also `search/searchmap/index.js`: 824, 849, 954, 958.

*Square, template and topic pages:*
- `square/detail/index.js`: 235, 312. Also `square/components/cy/post-compose/index.js`: 586.
- `templatedetail/templatedetail.js`: 492.
- `topic/merchantapply/index.js`: 587.
- `topic/merchantinfo/merchantinfo.js`: 1179, 1183, 1309, 1313, 1324.
- `topic/components/cy/chapter-node-form/index.js`: 270, 378, 382, 386, 390, 409, 413, 419.
- `topic/index/index.js`: 1171, 1176.

**Left: a different kind of notice, or not a pop-up about an input field**
- `topic/components/cy/node-npc-form/index.js` (313, 315, 317): a result sheet titled 「没有成功」 inside a sheet. It needs a decision on the sheet pattern.
- `publish/fabu/theme-runtime-structure.js` (337): an alert asking the author to pick the chapter again after a structure change. It is a notice about the structure, not a single field.
- `club/topic-detail/plan-fallback.js` (12/44): a warning-coloured caption inside a sheet. It is not blue or grey; it is left alone.
- Count-limit and "keep at least one" guards, such as 「至少保留一个票单」 (`publish/activity`: 354, and others listed in the audit). They block an action and do not judge what was typed.
- Conflict and capability reasons, such as `publish/temp` 2553, 2727, 2761, 2773 and `publish/fabu` 8502, 8722, 9041, 6623. They explain why something can't be used.

**Left: validated inside the editable modal** (the modal's `validate` hook shows the error inside the modal): `club/governance` 438/548/614, `club/event-ops` 987–993, `club/join-requests` 168, `coop/list` 1055, `merchant/customer` 717/748.

**Left: modal closes, then a pop-up** (needs the modal's `validate` hook added): `coop/invite-detail` 476; `coop/list` 609 (its blue `actionError` box at wxml:22 is shared with server failures, so it was left); `club/topic-detail/director.js` 639, 670; `topic/merchantinfo/merchantinfo.js` 2078, 2125; `play/index.js` 5527.

**Left: backend-mirrored, wording unchanged.**
- These boxes stay blue because they also carry server and state failures: `club/apply` wxml:160, `gerenziliao` wxml:76, `merchant/apply` 587/599, `activity/baoming` 1142, `publish/simple` 42 (AI planning failure).
- `publish/temp` 3681 is a toast relaying `res.msg`, also left.

### Round 4 checks

- **Mini-program:** full `node --test tests/unit/*.test.js` run, 15605 tests. The four failures were all caused by this round and are fixed: three tests now pin the new red / no-toast behaviour, and the action-ledger count was rebuilt. After the fixes the affected files pass: 257 files / 3180 tests, plus the action ledger 46/46 and the new sweep test.
- **Action ledger:** rebuilt. The pricing ticket input adds one control, 5600 → 5601, and the event contract goes 2726 → 2727 (counts updated with a note).
- **Gates:** `node tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`, with no baseline entries added. The `publish/temp` edit keeps its line count, so baselined line numbers do not move. `scripts/ui-validity-gates.js` U1, U3–U6 pass.
- **Java** (`chengyinhub-admin`): `PricingPerTicketConfirmTest`, `ApiPricingTicketPricesParseTest`, `PricingDepositDueTest`, `FreeExplorePriceLockAcceptanceTest`, `ApiPricingControllerPaymentNoteTest`, `PricingServiceImplTest`: 46 run, 0 failures.

### Needs owner (round 4)

- **The remaining pop-ups in the sweep list.** About 160 input pop-ups would each need per-field error state, mostly inside editor sheets in `publish/fabu`, `theme-runtime`, the club director and merchant decor. My recommendation is to convert them page by page in follow-up work, starting with the participant flow in `activity/baoming` and `merchant/apply`, which players and new merchants see first.
- **Several ticket prices: the 「无人带」 tab is unchanged** (a single self-play price). Recommended default: keep it, since a theme has only one self-play price.

### Not verified on a real phone

- The keyboard and the field scrolling into view when a red line appears.
- `<input type="digit">` on iOS and Android for the per-ticket fields.
- Safe-area insets.

Round 4 status: PARTIAL - P-34 and several ticket prices are fixed and proven over HTTP. In the sweep, every blue input-error box and every repeated pop-up is fixed. About 160 input pop-ups that need per-field error state built page by page are listed with file:line and left for follow-up (see "Needs owner (round 4)").

## Round 5D (2026-11-03): input pop-ups on 结算 (`activity/baoming`) and 商家入驻 (`merchant/apply`)

Scope as the owner set it: these two pages and the components they own (`baoming` uses only shared `cy-*` components; `merchant/apply` owns `merchant-claim-sheet`). The editor pages are untouched.

### Every pop-up on the two pages, listed before changing (file:line at `c27169e6`, the branch head before this round)

**Converted** (reports a problem with what the user just filled in or left empty):

| File:line | Trigger | Type | Now |
|---|---|---|---|
| `activity/baoming/baoming.js:1542` | Pay with no participant chosen | `app.tips` toast | Red line under 参与人信息, scroll to top. Still opens the picker sheet, or the add page when the list is empty, as before. |
| `baoming.js:1554` | The chosen participant has no name | toast | Same red line, same picker. |
| `baoming.js:1560` | The chosen participant has no phone | toast | Same red line, same picker. |
| `baoming.js:1567` | The chosen participant's phone is malformed | toast | Same red line, same picker. |
| `merchant/apply/index.js:365` | Tapping the grey 继续 on step 1 (name or phone empty) | toast (first problem only) | A red line under each empty field, scroll to the first. |
| `merchant/apply/index.js:366` | Grey 继续 on step 2 (address or hours empty) | toast | Same, per field. |
| `merchant/apply/index.js:367` | Grey 继续 on step 3 (no licence) | toast | Red line under 营业执照. |
| `merchant/apply/index.js:371` | Grey 提交申请 on step 4: name or ID number invalid | toast (`checkIdentityForm` message) | Red lines under 真实姓名 and 身份证号. The wording and rules are unchanged; `publisher-identity.js` gains `identityFieldErrors`, and `checkIdentityForm` returns its first entry in the same order. |
| `merchant/apply/index.js:473` | 经营时间 sheet, 完成 with no day selected | toast | Red line under 经营日 in the sheet. The sheet stays open, as before. |
| `merchant/apply/index.js:481` | 经营时间 sheet, start equals end | toast | Red line under 营业时段 in the sheet. The sheet stays open. |

Labels: 参与人信息 now has a red `*`, and so do 经营日 and 营业时段 in the sheet. The 入驻 fields already had theirs. Each line clears as soon as that field changes: on typing, picking a location, confirming the hours, uploading, or choosing another participant.

**Left as they are:**

- `baoming.js:1548` 「请同意向主办方提供报名信息」 and `merchant/apply/index.js:371` when **only** the consent is missing (「请先同意提供真实姓名与身份证号」): consent.
- `baoming.js:1577` 本单不可退 and `:1016` 退出候补: confirmations with two buttons.
- `baoming.js:1813`, `:1828` 支付结果待确认, `:1792` 您已取消支付, `:1521`/`:1852` 支付服务, and `:1604`, `:1633`, `:1724` (consent save, order and payment failures): server, network and payment results.
- `baoming.js:1363` 价格已更新, `:1135` 暂无可用积分 / 该票不支持积分抵扣, and `:1480` the quote refusal: decided or relayed by the backend.
- `baoming.js:983`, `:987`, `:1062`, `:1462`, `:1468`, `:1474`, `:1487`, `:1493`, `:1509`, `:1514`, `:1527`, `:1537`: the state of the session, the waitlist, the shop or the order, not something the user typed.
- `baoming.js:574` 「参与人信息已选择」: a success notice.
- `baoming.js:560` (`confirmAddressSelection`, 「请选择参与人信息」): unreachable. No WXML binds it, so it is left alone rather than changed blind.
- `merchant/apply/index.js:406`, `:433`: the map-permission explanation and the location-failure dialog (permission and confirmation).
- `merchant/apply/index.js:508` 「最多6张」: a count limit, not a judgement of what was typed (the same rule as round 4).
- `merchant/apply/index.js:611` 「审核中，请耐心等待」: application state.
- `merchant-claim-sheet`: no pop-ups. Its form error is already a red `cy-inline-error`.

### Blocking and payloads are unchanged

The early returns are the same: the participant checks still `return` before any consent record, subscription or order call, and still open the picker or the add page. The 入驻 button is still greyed by the same `validate()`, and `onNextDisabledTap` only reports. Each step's scroll-to-first-error uses `wx.pageScrollTo` with the selector and an offset for the fixed nav bar, because the 入驻 page scrolls at page level: `.page` has a `min-height`, so the scroll-view grows with its content and does not scroll itself.

### Tests

- New: `tests/unit/r5d-first-pages-input-errors.test.js`, 11 tests. For each converted pop-up it checks three things: the invalid case shows the line, makes no pop-up and does not submit (no `merchant_registration` request; no `_workflow.submit`); fixing the field clears the line; and valid input submits. Two of the tests compare payloads: a run that hit every error and then fixed it sends **exactly** the payload of a clean run (baoming: all fields except the random `requestId`; 入驻: identical, with the same key set). Consent stays a pop-up (both pages). Structure: red `*`, `kind="error"` under each field. **On the old code 9 of 11 fail.** The two payload-equality tests pass on both, as they should.
- Updated: `cl29-phone-entries-contract.test.js`. It pinned the toast 「参与人信息需要包含手机号」; it now pins the red line, and the no-order and go-to-add-page assertions are unchanged.
- Every existing unit test that touches either page or `publisher-identity` passes: 740 tests across the matching files, 0 failures. Action ledger: 46/46. The ledger was rebuilt: still 5601 controls, with only line shifts and the new error selectors as evidence. `node tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`, with no baseline entries added. The baselined dynamic `setData` in `merchant/apply` `onInput` stays at line 359. `scripts/ui-validity-gates.js` U3–U6 pass.

### Rig walk (cloud rig, not a phone)

The rig ran this branch's code (`60a55fe1` plus this round's follow-up, copied into the candidate worktree) with the club lane's two runtime patches. The rig-only changes are in `tools/r5d-rig-patches.diff` and stay out of product code:
- `wx.pageScrollTo` was a no-op in the rig, so it is implemented there for `scrollTop` and `selector + offsetTop`.
- `app.py` passes dummy `CHENGYIN_OSS_*` values, as in round 4, so `/api/activity/info` serialises.

**商家入驻, player 9004, end to end.** Every invalid case was walked: step 1 empty, then name filled (that line cleared). Step 2 empty. The hours sheet with no days, then a day picked (cleared). Start equal to end, then end changed (cleared). Step 3 with no licence. Step 4 with name and ID empty: the page scrolls to 真实姓名. Then step 4 with only consent missing: the pop-up is kept. Then valid input. The submission reached 「提交成功」, and the DB read-back of `mms_merchant` 8006 has exactly the typed values plus the identity registration (`proof/r5d-first-pages-readback.txt`). Harness notes:
- The licence upload's WeChat image check cannot reach WeChat from the rig (`UnknownHostException`), so `app.chooseImage` was stubbed in the browser to return a path.
- The 18:00 / 10:00 end times were set through the page's own `onHoursEndChange`, because the time wheel cannot be scrolled from the driver.

**结算, player 9004, session 10 (¥69), end to end.**
- With no participants: the line appears and the add page opens as before. After coming back, the line is still there.
- To show the legacy-record case, two participant rows were inserted as fixtures into the throwaway DB, one with phone `1380013` and one valid. The backend refuses such a phone today; the row stands for an old record.
- Paying with the bad one shows the line and opens the picker as before. Closing the picker leaves the line. Choosing the valid one clears it.
- Paying then went through the rig payment seam to the real callback. The DB shows exactly one `cms_registration` for 9004, `payment_status=2`: the blocked attempts created nothing.

Shots in `shots/` (390 wide, q70, same page and state before and after; before = `c27169e6` code, players 9005 / walk-player-1, because 9004 already had an order and an application):

| Shot | State |
|---|---|
| `R5D-MA-1-*` | 入驻 step 1, grey 继续 tapped with both fields empty. Before: a toast with only the first problem. After: a line under each field. |
| `R5D-MA-2-*` | Step 2, both empty. |
| `R5D-MA-3-*` | The hours sheet with no day selected. |
| `R5D-MA-4-*` | The hours sheet with start equal to end. |
| `R5D-MA-5-*` | Step 3 with no licence. |
| `R5D-MA-6-*` | Step 4 with name and ID empty. After: scrolled to the identity fields. |
| `R5D-BM-1-*` | 结算 after coming back from the add page with no participant. Before: no trace; the toast was gone under the navigation (`R5D-BM-1-before-nav`). After: the line. |
| `R5D-BM-2-sheet-*` | Paying with the bad-phone participant: the picker opens. Before: a toast over the sheet. After: the line on the page behind it. |
| `R5D-BM-2-*` | The same with the picker closed. |
| After only | `MA-1b` (line cleared on typing), `MA-3b` (day picked, cleared), `MA-4b` (end changed, cleared), `MA-6b` (fields filled, cleared), `MA-8` (submitted), `BM-1-addressinfo` (the add page still opens), `BM-2b` (valid participant, cleared), `BM-3` (paid, order detail). |

The rig doesn't draw the `<icon>` tick on the consent checkbox (a harness limit noted in round 3).

### Not verified on a real phone

- Whether `wx.pageScrollTo` with `selector` and a negative `offsetTop` lands exactly below the nav bar on iOS and Android. It needs base library ≥ 2.23.1 for `offsetTop`; older versions ignore the offset.
- The keyboard covering a field when a line appears.
- The baoming scroll back to the top while the picker sheet animates in.

STATUS: COMPLETE
