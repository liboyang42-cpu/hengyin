# Club lane: a volunteer organiser runs two club sessions

Candidate `master` a1d30fb, run on the rig branch `claude/cloud-walk-20261101-rig` (RIG-STATUS READY) in one cloud container, 2026-11-02 13:00–14:25 Shanghai time.
Persona: **林小舟**, a volunteer who runs 「沪上慢走社」, a Shanghai city-walking club, about once a month. Phone in one hand, 8–20 people around.

## 1. Verdict in five lines

1. Getting in is pleasant: becoming an organiser and creating a club took two short forms and no waiting. But a club's own **paid** city-orienteering theme can never take a sign-up, and nothing on the organiser's side says so (C-01). Paid club-led free exploration is also blocked until the platform changes a content slot (C-02).
2. Before the session, the organiser can't tell who is who. Every roster row reads 「城瘾用户」, real names are masked as 「周*」, and every check-in needs a typed reason (C-05, C-06). The same ticket shows three different refund rules (C-07). Cancelled members get their money back but no message (C-08).
3. At meeting time the core breaks. Member self check-in fails for every club session (「集合点未配置，请联系领队」, C-03). The leader's 「解锁下一章节」 is refused by the server (C-04). The workbench's 「开始活动」 fails with a bare 「准备未完成，暂时不能开局」 and hides the reason (C-09). Members, the roster and the recap give three different arrival counts at the same moment (C-10).
4. Ending works and is properly warned, but the recap tiles are blank (a real binding bug, C-11). Members mid-route aren't told it ended, and the executing club can't end its free-exploration session at all (C-12).
5. A volunteer would give up at the first paid session (C-01/C-02). If they got past that, they would give up at the meeting point, where nobody can check in and the leader can't release a chapter. Money can't be reconciled from any club screen: no ¥ amount anywhere and 「结算」 shows ¥0.00 (C-13).

## 2. The journey, step by step

Evidence labels: **UI** = VERIFIED-UI (real page in the rig browser runtime, real backend, screenshot). **API** = VERIFIED-API (real HTTP plus database read-back). Screenshots are in `shots/`.

### A. Getting in

| Step | What I wanted / did | What I saw | Felt | Evidence |
|---|---|---|---|---|
| A1 | New account, open 发现 › 俱乐部 | 「创建你自己的俱乐部」 + two existing clubs | Clear entry. Good. | UI `01-club-tab-newuser` |
| A2 | 「创建俱乐部」 → 成为主理人 (4 short steps) | 「填完身份立即生效，不用等审核」, done screen 「你已成为俱乐部主理人，身份立即生效。」 | Quick and honest. Good. | UI `02`…`07` |
| A3 | Create club, deliberately reuse an existing name 「城瘾跑团」 | Name typed on step 3 of 4; rejection appears only after 「创建俱乐部」 on step 4: 「创建没有完成 · 俱乐部名称已被使用，请换一个 · 重试」. 「重试」 resends the same name. Going back to step 3 keeps the stale error box after I type a new name. | Mild confusion (C-14) | UI `12-create-dup-result`, `13-create-back` |
| A4 | Rename to 「沪上慢走社」 | 「俱乐部已创建 · 沪上慢走社 · 上海 · 进入俱乐部管理」 | Good | UI `14-club-created`; DB `club` id 990004 |
| A5 | Cover / logo | Upload fails with 「安全检查服务异常，请稍后重试」 | HARNESS (WeChat image check unreachable) | UI `09a-crop` |
| A6 | 管理 tab | 「现在要做 · 发布主题 · 先发布一个俱乐部项目」 with primary 「发布主题」 and 「开一场」 | Very clear next step. Good. | UI `15-club-manage` |
| A7 | 「发布主题」 | Sheet 「城市定向 · 顺序探索 / 自由探索 · 全点开放」 → editor | Clear | UI `19-publish-choice`, `20-fabu-open` |
| A8 | Author the city-orienteering theme 「外滩源 · 迟到百年的电报」 (3 chapters, 8 real stations, 8 games: text, choice, photo, ¥39 ticket, capacity 20) | Done through the same V2 create API the editor posts to (the editor's continuous location picker looped in the runtime: HARNESS). First attempt returned only 「主题发布失败，请稍后重试」; the log said 「模板尚未通过审核,不能引用」 (C-15) | — | API; `tools/author_co*.py` |
| A9 | Wait for review | 「现在要做」 says 「邀请商家 · 选择一个项目，开始对接商家」 for a theme with no shops; the project card doesn't show 审核中 | Wrong next step (C-16) | UI `21-manage-pending`, `22` |
| A10 | After approval, 「开一场」 | 「活动运营」 page: 日期清单 / 系列 / 重复方式 / 集合时刻 / 容量（可选）「沿用票种」/ 候补. **No price, no meeting place.** The default time 09:00 is already past; submitting gives 「集合时间必须晚于当前时间」 (good). | What am I committing to? The page never shows price, place or what players will see (C-17) | UI `24-open-session`, `26-eventops-pasttime` |
| A11 | Create the session for 13:45 | Created. Re-submitting the same form made a second identical 13:45 session with no warning | (C-18) | UI `28`; DB `cms_activity` 10 and 11 |
| A12 | Member's side: join and look | After 「加入」 a good welcome sheet 「欢迎加入「沪上慢走社」 … 去看活动」; event card 「周一 11月2日 · 已开始」 (the session hadn't started; that is the theme date range) | Welcome sheet is good. 「已开始」 is wrong (C-19) | UI `31-member-welcome`, `33-member-events` |
| A13 | Member opens the theme | 「定价中 · 未开售」; only a 「自玩通行证」 in the order sheet; the 13:45 club session isn't offered. Quote API: 「该主题尚未开售，开售审核通过后才可报名」 | Member cannot sign up; organiser sees 「已确认」 + 「接下来 11月2日 13:45」 (C-01) | UI `34`, `37-lead-topic-detail`; API |
| A14 | Organiser looks for "go on sale" | No entry from the club workbench. The pricing page exists (reached by URL); it starts at 「你的终价 ¥0」, ignoring the ¥39 already set → 「终价已确认，请等待开售审核」 | (C-01) | UI `41-lead-pricing`, `42-lead-pricing-filled` |
| A15 | Platform side (played by me as the admin): sale review for 990016 | `PUT organizer-actor` → `SALE_ORGANIZER_ONLY_FREE_EXPLORE`; canonical draft → `SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR`; sale-submit → 「缺少 canonical15 草稿，拒绝开售提审」 | A club-owned paid city-orienteering theme can't reach sale at all (C-01) | API; `tools/platform_sale.py`, `tools/canon.py` |
| A16 | Work-around for the rest of the walk | (a) A free copy 「外滩源 · 迟到百年的电报（社员场）」 (¥0, theme 990018). Free UGC skips the sale pipeline. (b) The platform publishes free-exploration theme 「武康路 · 午后四店漫游」 (990017, publisher club 城瘾跑团), 4 merchants join after publish, and my club is invited as executing club | — | API; `tools/chains/*.log` |
| A17 | Co-op invitation | Not surfaced in 「现在要做」 (still 「邀请商家」); found under 「收到的合作邀约 · 待确认 · 城瘾跑团」. The card is clear: 「来自 城瘾跑团 … 武康路 · 午后四店漫游 … 引流型 · 无分成 · 想请沪上慢走社来带这条武康路的下午场 · 拒绝 / 接受合作」. One tap accepts | Good card; 「无分成」 is all it says about money (C-20) | UI `46-lead-invite-open`, `48` |
| A18 | Paid member sign-up on the club session | Quote: 「该产品暂不支持在线支付(平台正在办理支付资质,预计 2 个月内开放);如需报名请联系主办方」 | (C-02). To continue I applied the player chain's admin step (content slot → PLATFORM) | API; `tools/slot.py` |
| A19 | Open today's free-exploration session 13:50, capacity 6 | Created. The theme's own dates are 11/12–12/12; a session on 11/2 was accepted silently | (C-21) | UI `49`, `50`; DB activity 16 |

### B. Before the session

| Step | What I did | What I saw | Evidence |
|---|---|---|---|
| B1 | Member finds the session | Session picker 「选择场次 · 周一 11.2 13:50 · 武康路 · 午后四店漫游」. It doesn't name the club or the leader. The theme page credits 「参与品牌 · 城瘾跑团」 and says 「场次内自由顺序，不带队」 | UI `53-member-fe-session-picker`, `52-member-fe-bottom` |
| B2 | Checkout | 「集合点 · 人民广场1号口 · 上海市徐汇区武康路100号」: the meeting point from the ticket template, wrong for a Wukang Road walk, and the organiser never had a field to set it (C-17). 「退改 · 开场前24小时可退」 | UI `55-member-baoming` |
| B3 | 6 members sign up and pay ¥69 (real callback path) | All `payment_status=2`. Quote for each: `refundableNow:false`, `refundDeadline 2026-11-01 13:50` | API `tools/signpay.py` |
| B4 | 7th member | 「票种已售罄」; checkout shows 「候补状态 · 本票种已满 … 重新加入候补」 (「重新」 though never joined) → 「候补排队中」 | UI `56`, `57` |
| B5 | Same member wants a later session of the same theme | 「已达到该主题的购票上限」 | API (C-22) |
| B6 | Organiser: who signed up and paid? | 「报名参与者 · 已售 9 · 待核销 9」, rows 「周* · 2026-11-02 13:50 · 待核销」. Masked names, all sessions mixed, the unpaid sign-up and the waitlisted member are not listed, 「报名趋势获取失败 · 重试」 | UI `59-lead-customers`, `60` |
| B7 | Per-session roster | 「本场名册 · 6 人 · 已报名 6 · 候补 1」, six rows all 「城瘾用户 · 标记到场」 | UI `61-lead-roster-16` (C-05) |
| B8 | Change time after sign-up (session 18) | Row shows 「修改」; the sheet says 「已有报名，集合时间和地点不可改」 but offers 「保存」; saving → 「该场次已售出，集合时间与地点不能修改；当日备注仍可改」; only then the row flips to 「已售锁定（备注仍可改）」. There is no 备注 field | UI `76-lead-edit-time`, `78` (C-23) |
| B9 | Cancel session 11/13 (2 paid) | Top row 「取消本场 · 会下架本场、处理退款并终止候补」 → dialog 「取消本场活动 · 请输入取消原因（至少 2 个字）· 取消 / 确认取消」 → second confirm → 「本场已取消 · 原因：… · 原取消结果：退款 2 单；人工跟进 0 单；关闭待付 0 单」. Good receipt | UI `64`, `67-lead-cancel-done`; DB `payment_status=4` ×2, `refund_application` 1–2 |
| B10 | Did the cancelled members hear about it? | Their inbox only has 「报名成功」; no cancellation or refund message. The club cancellation campaign's deliveries ended `SKIPPED_INELIGIBLE / RECIPIENT_NO_LONGER_ELIGIBLE` | UI `86-refunded-member-messages`; DB (C-08) |
| B11 | Member refunds themselves after the 13:50 start | 「报名已取消，现金退款已受理，尚未派发」 although the quote said non-refundable. The stored policy is `EXPLORE_END_100_0`, deadline 23:59:59 that day | API; DB (C-07) |
| B12 | Waitlist after that refund | DB: `OFFERED`, expires 11/03 13:53. Member screen: 「候补名额已过期 · 本票种仍已满」, sign-up closed | UI `104a…`; DB (C-24) |
| B13 | Post an announcement in the club | 「新建帖文 · 设为公告 · 发布」 → shows in the club and in members' 发现 › 帖文 as 「城瘾用户 · 沪上慢走社」 | UI `92`, `93`, `94-member-discover-posts`. Good |

### C. Session running (core)

Session 18, 「外滩源 · 迟到百年的电报（社员场）」, 14:00, 8 members, 2 teams (team formation done by API because my sign-ups bypassed the team step; see harness notes).

| Moment | Leader saw | Member saw | Evidence |
|---|---|---|---|
| Before start | Workbench: 「本场 8 人 · 集合时间 · 暂无节点状态 / 节点数据加载中 · 开始准备」 | Play page 「队伍集合中 0/9 · 已到集合点 · 我到了 · 等队长开始行程…」 | UI `75-lead-director-pre-1`, `87` |
| Member self check-in 「我到了」 | — | 「集合签到定位 … 集合前60分钟起可签到」 then server 「集合点未配置，请联系领队」. Every club session has NULL `cms_activity.latitude/longitude` | API + DB (C-03) |
| 「开始准备」 | Dialog 「进入后将开始收集节点和队伍 READY 状态。」 → 「准备总览 · 节点状态 8/8 站 READY · 开始活动」 | — | UI `78-lead-preparing` |
| 「开始活动」 with no teams | Toast 「准备未完成，暂时不能开局」. The server's reason `blockers: ["尚无有效报名队伍"]` is not shown | — | UI; API (C-09) |
| Start | 「确认开局 · 开局后玩家将按当前角色和节点状态进入活动。」 → 「进行中」. Footer primary is now 「结束活动」. Team rows read 「1队 · 0/8 节点 · PREPARING」 (raw enum) | 「带领中 · 0/9 人 · 队长带队中」 | UI `109`, `110-lead-running-1`, `112` |
| Who is here | Roster after manual marking: 「已到场 6」 | Header 「带领中 · 4/9 人」; recap API `arrivedPlayers 2` | UI `131`, `134`; API (C-10) |
| Check-in by leader | Each person = dialog 「更正为已到场 · 请输入更正原因（至少 2 个字）」, about 5 s each, rows indistinguishable | — | UI `98-lead-mark-arrived`, `99` (C-06) |
| Chapter release (「等队长放行」, OWNER DECISION) | Play-page tools 「解锁下一章节」 → 「确认放行选定的下一章节？」 → server 「请使用本场导演的章节命令」. Workbench has no chapter action (`canUnlockChapter:false`, server `chapterOptions:0`) | Lead model `currentChapterId:null, nextChapterId:49` the whole session | UI `121-leader-tools`, `122-leader-unlock`; API (C-04) |
| Member plays station 1 | — | 「门楣上的年份」; wrong answer 「答案不正确」; hint 「在正门拱券的正上方。」 with 「当前最高 70 解谜分」; then 「答对了！」 | UI `121-member-wrong`…`123-member-right` |
| Stuck member: where's the answer? | 「打开剧情与玩法 › 玩法 › 查看答案」: answer, both hints, and 「答案只对俱乐部开放…玩家端看不到这一段。」 | — | UI `130-lead-answer`. Good, three levels deep |
| Team progress | Sheet 「队伍进度 · 1队 2/8 节点 RUNNING · 6队 1/8 节点 RUNNING」: no names, no current station, no idle time | — | UI `127-lead-team-progress` |
| Latecomer | Marked via roster with reason 「迟到 12 分钟」 | — | API (same endpoint as the button) |
| Early leaver | Member quits team: 「已退出队伍」; leader gets no signal, team row silently becomes 2 | — | API |
| Notice to paid members | 「群发通知 · 本场已报名 8 人 · 已选」 → preview 「8 人」 → sent; DB: exactly the 8 registered, all `SENT` (after the harness clock fix); no waitlisted or other-session member received it | Inbox 「系统通知 · 14:00 外滩源集合提醒 …」; the sender is not shown as the club | UI `81`, `82`, `84-member-messages`; DB |
| Notice to the missing | 「本场未到场 · 0 人」 / 「该分群暂无成员」 while the roster shows 2 not arrived | — | UI `132` (C-25) |
| Leader's phone dies | Every rig run is a fresh install with the same login; the workbench restores from the server every time (state, teams, leaderboard). Good. Signal loss mid-command: NOT VERIFIED (the page has 「确认结果 / 重试原操作」 code paths) | — | UI (all runs) |

Free-exploration session 16 (13:50) as executing club: 「去选一场」 leads only to the roster (`canDirect` is granted to the theme's owner club only). No workbench, no team progress, no notices from the theme page, no way to end it (C-12). Merchants redeemed: player 1 at 4 shops (badge 「武康路 · 午后四店漫游 · 通关」), player 2 at 3, player 3 at 2. The leader's list says 「已核销 3」 with 周*/吴*/郑* 「已核销」 and 王*/冯*/陈* 「已接洽」. 冯 and 陈 were never checked in (C-10).

### D. Ending

- 「结束活动」 → 「结束这场活动？ 结束后不能再核销、不能再改队伍与角色；已产生的结算事实会保留。此操作不可撤销。 再想想 / 结束活动」. Clear warning. It doesn't say that 2 teams are still mid-route. UI `135`.
- After the end: 「已结束」, primary 「查看结算报告」, 「复制复盘数据」, 「活动复盘 · 活动漏斗」. **The funnel tiles show labels without numbers** and 「节点完成」 rows are empty (C-11). 「接下来 11月2日 14:00」 and 「修改」 still show. UI `137-lead-ended-1`, `137-lead-ended-2`.
- Member mid-route after the end: the play page looks unchanged (「开始游戏」 → 「继续探索」); submitting gives 「本场带队尚未开局或已结束」 (C-12). UI `138`, `139`; API.
- Group result for members: none shown. Photos: the photo task failed to upload (HARNESS). Club feed: the end posts nothing to the club; my manual 公告 is the only post. Club medals: 「俱乐部勋章 · 创建勋章 · 还没有俱乐部勋章」 (manual, not tied to the session). Theme badge was auto-awarded for free exploration (DB `player_badge` TOPIC_FINISH_990017).

### E. After

Money for theme 990017, from the database (fen exact):

| Session | Paid | Refunded | Net | What the organiser can see |
|---|---|---|---|---|
| 16 · 11/2 13:50 | 6 × ¥69 = ¥414 | 1 × ¥69 (member, after start) | ¥345 (5 × ¥69) | 「已报名 5 人 · 已支付 5 人 · 可退款 2 人」 under a 「2026-11-12」 header; no ¥ |
| 12 · 11/12 | 1 × ¥69 | 1 × ¥69 | ¥0; plus 1 unpaid (`payment_status=1`) | 「还没有人报名」 |
| 13 · 11/13 (cancelled) | 2 × ¥69 | 2 × ¥69 | ¥0 | Not listed in the 报名名册 |
| **Total** | **¥621** | **¥276** (4 `refund_application`, status 1) | **¥345** | 「俱乐部结算」: 「待结清 ¥0.00 · 已结清 ¥0.00 · 可提现 ¥0.00 · 还没有平台分润记录」 |

Counts reconcile between the DB and the 报名名册 (5 paid). Nothing reconciles to the fen on any club screen because no club screen shows an amount (C-13). The ORGANIZER slot is PLATFORM and my club is DISTRIBUTION (`product_role`), so the volunteer can't learn what, if anything, they earn.

History and re-running: 「日期清单（含已过去与已取消的场次）」 lists series as 「外滩源 · 迟到百年的电报（主题 #990016 · 系列 #2） · 单次」 (internal ids, no dates or headcounts collapsed). Re-running the same theme reuses the content: pick it in 「添加日期」. Capacity, time and waitlist are re-entered. Returning members of the same theme are blocked by the per-account cap (C-22).

## 3. Issues, ranked

| ID | Sev | User-facing problem | Reproduction | Expected | Shot | Evidence | Likely location | Smallest change |
|---|---|---|---|---|---|---|---|---|
| C-01 | S1 | A club's own **paid** city-orienteering theme can never take a sign-up. The organiser sees 「已确认」 and a scheduled session; the member sees 「定价中 · 未开售」 | Club publishes CO theme with ¥39 ticket → approved → 开一场 → member quote | Either it sells after approval, or the organiser is told at publish time that paid club CO isn't supported yet | `37`, `34`, `41` | UI + API (`SALE_ORGANIZER_ONLY_FREE_EXPLORE`) | `CmsRegistrationServiceImpl.assertCommercialSaleOpen`; organizer-actor gate; `pages/club/topic-detail` status chip | Show the real state on the workbench (「未开售 · 待平台开售审核」) and say at publish/pricing time that paid club CO can't sell yet |
| C-02 | S1 | Paid club-led free exploration: 「该产品暂不支持在线支付(平台正在办理支付资质,预计 2 个月内开放);如需报名请联系主办方」. The organiser is told nothing; the member is sent to the organiser | Executing club session on a selling FE theme, CONTENT slot not PLATFORM | The organiser knows before opening sessions that online payment is off | — | API | `assertCollectible` (code comment: 2026-07-16 decision) | If deliberate, show it on 「开一场」 / workbench; otherwise OWNER to decide |
| C-03 | S1 | Member self check-in never works in club sessions: 「集合点未配置，请联系领队」 | Any session from 「开一场」 or `open-session` → 「我到了」 → 定位并签到 | Check-in against the session's meeting point | `105` | API + DB (`cms_activity.latitude/longitude` NULL for 10–18) | Session creation (`ClubEventOps` series materialise / `open-session`) doesn't copy ticket `gather_lat/lng`; `ClubGatherArrivalPolicy` reads activity lat/lng | Copy the ticket's gather point into the activity at creation |
| C-04 | S1 | The leader can't release a chapter: play-page 「解锁下一章节」 → 「请使用本场导演的章节命令」; the workbench has no chapter command | Director-run CO session → leader play page 「更多 › 解锁下一章节 › 确认解锁」 | One working release control, naming the chapter and who is waiting | `121`, `122` | UI + API (`chapterOptions:0`, `canUnlockChapter:false`) | `pages/play` `leadUnlockFromTools` vs `/api/club/lead/unlock-chapter`; `GameSessionRuntimeServiceImpl` chapter options for ROUTE | Hide the play-page item when a director owns the session, or route it to the director command; the dialog should name the chapter |
| C-05 | S2 | The roster can't identify people: six 「城瘾用户」 rows; the CRM masks 「周*」 though members typed real names for 「报名核验」 | Sign-up with realName → roster / 报名参与者 | Sign-up name (and phone tail) on the roster | `61`, `59` | UI + DB | `/api/club/event-ops/roster` returns `nickname` only | Show the registration real name on the roster |
| C-06 | S2 | Check-in needs a typed reason per person (「更正为已到场 · 至少 2 个字」), ~5 s each; 20 people ≈ 2 min of typing on a street corner | Roster 「标记到场」 | One tap; reason only for corrections after the fact | `98` | UI | `pages/club/event-ops` attendance | Preset reason chip 「现场到达」 |
| C-07 | S2 | Three refund rules for one ticket: checkout 「开场前24小时可退」; quote `refundableNow:false` (deadline yesterday); stored `EXPLORE_END_100_0`; a refund after start succeeded | Same-day session, sign up, then cancel after start | One rule, shown at checkout | `55` | API + DB | ticket `refundRule` text vs quote vs `refund_policy_code` | Show the stored policy's deadline at checkout |
| C-08 | S2 | Members of a cancelled session get the refund but no message; the club cancellation notice to them is `SKIPPED_INELIGIBLE` | Cancel a session with paid members | Message with the reason, as 「取消原因…会随通知一起发给已报名的人」 promises | `86` | UI + DB (caveat: delivery ran late because of the harness clock bug) | Eligibility recheck in `ClubEventOpsMapper.xml` (same mechanism as PR #701) | Treat refunded-by-cancellation recipients as eligible for that campaign |
| C-09 | S2 | 「开始活动」 fails with 「准备未完成，暂时不能开局」; the reason (「尚无有效报名队伍」) is returned but not shown | Start with members not in teams | Show the blocker and what to do | — | UI + API | `pages/club/topic-detail` onPrimary toast | Toast `readiness.blockers[0]` |
| C-10 | S2 | Arrival counts disagree at the same moment: member 「带领中 · 4/9 人」, roster 「已到场 6」, recap `arrivedPlayers 2`; CRM marks never-checked-in 冯/陈 「已接洽」 | Mark arrivals in roster, members answer stations | One arrival fact | `131`, `134`, `102` | UI + API | lead team-progress vs event-ops attendance vs game recap | Feed all three from attendance |
| C-11 | S2 | Recap tiles show labels with no numbers; 「节点完成」 rows empty | End a session → 活动复盘 | 持票 6 · 到场 2 … | `137-2` | UI; code-confirmed (data has `valueText:"6"`) | `pages/club/topic-detail` builds `recapMetrics[].valueText`; `pages/club/components/club-director-recap/index.wxml:9` renders `{{item.value}}`; `completion-rows="{{recap.stations}}"` passes raw station objects | Render `valueText`/`unit`; map stations to title/subtitle |
| C-12 | S2 | After 「结束活动」 members mid-route see nothing; their next submit fails 「本场带队尚未开局或已结束」. The executing club can't end (or direct) its free-exploration session at all | End with teams mid-route; executing club on 990017 | Members see 「本场已结束」 and their result; executing leader has the workbench | `138`, `139` | UI + API (`canDirect` = topic owner only) | `ClubCrmAppServiceImpl:273`; play page | Banner on play page when session FINISHED; grant director to the executing club for its own sessions (owner decision) |
| C-13 | S2 | Money can't be reconciled from club screens: no ¥ anywhere, cancelled session absent, 「结算」 ¥0.00 | See section E | Per-session paid / refunded / net in ¥ | `142`, `95` | UI + DB | `pages/club/enroll`, `pages/club/settlement` | Add the ¥ line per session to 报名名册 |
| C-14 | S3 | Duplicate club name caught only on the last step; 「重试」 repeats the same request; stale error after going back | Create club with a taken name | Check on step 3 or after rename clear the error | `12`, `13` | UI | `pages/club/create` | Clear `submitError` on name input; hide 「重试」 for 409-type errors |
| C-15 | S3 | Publishing with unreviewed games returns 「主题发布失败，请稍后重试」 | V2 create referencing a new template | 「玩法待审核」 | — | API + log | `ApiTopicController.createTopic` catch-all | Pass the RuntimeException message |
| C-16 | S3 | 「现在要做」 says 「邀请商家」 for a no-shop CO theme under review, and ignores a pending co-op invitation | Publish CO theme; receive invite | 「审核中」 / 「有 1 条合作邀约待确认」 | `21`, `44` | UI | `pages/club/detail` ownerStage | Prefer pending review / pending invite stages |
| C-17 | S2 | 「开一场」 has no price and no meeting place; checkout shows a template meeting point (「人民广场1号口」) for a Wukang Road walk | Open session; member checkout | Set or at least see price and meeting point when opening | `24`, `55` | UI + DB | `pages/club/event-ops` | Read-only line 「票价 ¥69 · 集合点 …」 with an edit link |
| C-18 | S3 | Same session can be created twice (identical time) without warning | Submit 「创建系列场次」 twice | 「这个时间已有一场」 | `28` | UI + DB (activities 10, 11) | `series/create` | Warn on same topic+start |
| C-19 | S3 | Club event card says 「周一 11月2日 · 已开始」 before the session starts | Member 活动 tab | Next session time | `33` | UI | `pages/club/detail` tagText | Use the next session's start |
| C-20 | S3 | Invitation says 「引流型 · 无分成」; the organiser can't tell what they earn | Accept co-op | One line on what the club gets | `46` | UI | `pages/coop/list` | — (copy) |
| C-21 | S3 | A session before the theme's own start date (11/2 vs 11/12) is accepted silently | 开一场 on 990017 | Warn | `49` | UI + DB | `series/create` | Warn outside theme window |
| C-22 | S2 | Returning member can't book another session of the same theme: 「已达到该主题的购票上限」 | Sign up for 13:50, then 11/12 | Monthly re-runs allow the same member | — | API | `maxTicketPerAccount` / registration cap | Cap per session, or explain on checkout |
| C-23 | S3 | Time edit offers 「保存」 while saying 「不可改」; the row flips to 「已售锁定」 only after a failed save; promised 备注 field doesn't exist | 修改 after sign-ups | Locked state shown before | `76`, `78` | UI + API (`CLUB_OPS_SOLD_LOCKED`) | `pages/club/topic-detail` `opsTimeLocked` | Compute lock from headcount on load |
| C-24 | S3 | Waitlist: 「重新加入候补」 to a first-timer; later 「候补名额已过期 · 本票种仍已满」 while DB holds a live OFFERED seat | See B4/B12 | Consistent state | `56`, `104a` | UI + DB | `pages/activity/baoming` waitlist texts | Use state NONE copy 「加入候补」; mirror OFFERED |
| C-25 | S2 | Mid-session 「本场未到场 0 人」 while 2 haven't arrived | Notify during session | The 2 missing members | `132` | UI + API | NO_SHOW audience | Include registered-not-arrived while running |
| C-26 | S3 | Team rows show raw `PREPARING` / `RUNNING`; 「暂无节点状态 / 节点数据加载中」 together; long title overlaps the date line | Workbench | 中文 states, one state line | `110-1`, `75-1`, `108` | UI | `pages/club/topic-detail` | Map enums; truncate title to one line |
| C-27 | S3 | 「玩家可到店核销」 and 「核销 · 8 待核销」 on a no-shop city walk | CO workbench | Hide 核销 for CO | `109` | UI | topic-detail | Hide when no merchant stations |

## 4. Gameplay feel (only what the club touches)

| Moment | Rating | What I saw | Smallest change |
|---|---|---|---|
| Gathering screen (member) | works but awkward | Big 「0/9 · 已到集合点 · 我到了」 is clear and calm, but it can never count up (C-03), so members stare at 0 | Fix C-03; show leader name |
| Leader start | works but flat | Two leader surfaces (workbench 「开始活动」 and play-page 「更多」), different vocabularies (开始准备 / 开局 / 行程) | One verb: 「开始」 |
| Chapter release (OWNER DECISION) | works but awkward | Leader can't see who is waiting; the dialog doesn't name the chapter; it fails (C-04). Members never see a 「等队长放行」 state | Name the chapter and waiting count in the dialog |
| Station 1 answer and hint | works and feels good | 「答案不正确」 inline, hint lowers the cap 100→70 visibly, 「答对了！」; quick | — |
| Leader answer lookup | works and feels good | Answer card is clean with hints and the privacy note | Link it from 队伍进度 |
| End and recap | works but flat | Honest warning, then blank recap tiles (C-11) and nothing for members | Fix C-11; member end banner |

Timing in the rig is not proof of real-phone animation.

## 5. Cross-role seams

| Seam | Observation | Evidence |
|---|---|---|
| Organiser → member (listing) | Organiser sees 「已确认 · 接下来 13:45」; member sees 「定价中 · 未开售」 | UI (C-01) |
| Organiser → member (who leads) | Session picker and theme page never name 沪上慢走社 or 林小舟; they credit the publishing club | UI `53`, `52` |
| Organiser → member (cancel) | Refund arrives, no message | DB (C-08) |
| Organiser → member (notice) | Reaches exactly the 8 paid members of this session, no one else; shows as 「系统通知」 without the club name | DB + UI |
| Member → organiser (arrival) | Three different counts (C-10) | UI + API |
| Member → organiser (leaves team) | No signal | API |
| Leader ↔ server (release) | Leader control refused (C-04) | API |
| Merchant → leader (free exploration) | Redemptions appear in the leader's list only as 「已核销」 per person (any one shop), not per shop; a double scan returns 「该章节已核销，请勿重复提供权益」 to the merchant only. When a perk fails at the counter the path is the player's refund/after-sales, which goes to the merchant's 「售后」; the club has no view of it and isn't notified. Who is responsible is not stated anywhere the leader can see | API + UI `102` |
| Platform → organiser | Sale-review steps (payee, hours, costs, canonical draft) are invisible to the organiser | API |

Known, fix pending:
- **PR #701** (club notice delivery for self-run sessions with `club_id` NULL): my sessions created through 「开一场」 have `club_id` set, and delivery reached all 8. But the 「场次工具 › 选择场次」 sheet also offers the auto-created self-run shell session (`club_id` NULL, 「11月2日 13:33」 that I never created), which is exactly #701's case. C-08 (refunded members skipped) is the same recheck mechanism. #701 as described would not cover refunded recipients.
- **PR #704** says the club workbench 「去核销」 was turned into 「结束活动」 (H6). Consistent with what I saw: right after start the footer primary is 「结束活动」. #704's fix (scan directly) would remove a risky thumb target.
- **PR #702 / #703**: merchant/home items, not hit in this lane.
- **Fix branch `fix/merchant-home-role-resync-20261101`**: not found on the remote (`git fetch` → 「couldn't find remote ref」).

## 6. Not verified and harness notes

Injected / NOT VERIFIED as real behaviour: WeChat login (rig `jscode2session`), payment (`pay.py` → real `/api/registration/callback`), GPS (`loc`), `getPhoneNumber`, subscribe messages (they fail in the log: 「access_token 获取失败,本条不发」), native map, share cards, camera, keyboard, real signal loss.

HARNESS issues and every local change (none committed to product code; patches in `tools/harness-patches.diff`):
1. **Rig runtime patch** `rig/mpweb/src/runtime.js`: register nested `<template name>` definitions (the club 活动 tab defines `clubEventCard` inside a view; without this the tab was blank).
2. **Rig compiler patch** `rig/mpweb-seed/tools/compile.mjs`: keep `wx:for`/`wx:if` on `<template is>` (without it the event card rendered once, empty). The bundle was rebuilt with `build.sh`.
3. **MySQL `time_zone` set to `+08:00`** at 13:39, then app restart. Under the rig's `+00:00`, the club notice outbox writes `next_attempt_at` in Shanghai wall time (Java) but compares with MySQL `now()`, so nothing was ever delivered. Production presumably runs `+08:00`. Times written before the switch show as UTC (e.g. 「05:30」 in an inbox).
4. Quartz jobs are paused as in CI. I ran `clubEventOpsTask` (job 138) and `imNotificationDeliveryTask` (125) via the admin 「执行一次」 endpoint to stand in for the per-minute scheduler.
5. Docker Hub returned 429 on first `up.sh`; images pulled on retry.
6. Image upload (club cover, photo task) fails: the WeChat image security check is unreachable (「安全检查服务异常，请稍后重试」).
7. Taps: some footer or behind-sheet taps didn't register in the runtime. I then called the bound handler (`onPrimary`, `onSessionPick`, `bmClick`, `leadUnlockFromTools`, `openTeamSheet`, `onStartTimeChange` for the time wheel, `onSliderChange` for the price slider, `onTopicChange`). Each is the handler the visible button binds.
8. The editor's continuous location picker looped (42 points) because `chooseLocation` returns instantly; theme authoring was done through the same V2 create API.

Fixtures (all through real APIs unless noted):
- Identities: `walk-club-lead` (990005), `walk-player-1…12` (990006–990017), merchants `e2e-openid-9002/9005/9006/9007`.
- Publisher identity: 林小舟 with a dummy ID number. Games: 8 templates approved by the admin review endpoint.
- Themes: 990016 (paid CO, never sellable), 990018 (free CO copy), 990017 (FE via copies of the candidate's chain scripts with my club as executing club; `tools/chains/`). The recruit deadline shift in that chain is the candidate script's own SQL.
- Content slot → PLATFORM via admin endpoint. Payee, hours, cost via admin endpoints.
- Members' sign-ups and payments via quote/create plus `pay.py`. Teams via `/api/team/create` and `/api/team/join`, because API sign-ups skip the team step (the UI checkout would offer it).
- Player check-in via `/api/play/checkin` failed in my scripts (「必须提供动作标识、期望版本和原运行时身份」): my call shape, not reported as a bug.

Scripts used are in `tools/` (no secrets; the admin fixture password is the public one from `scripts/e2e/chain_lib.py`).

STATUS: PARTIAL - photo tasks and club cover untestable (image security harness); free-exploration session 16 not ended (executing club cannot end it, ends 23:59); club medal award and member group result not exercised; leader signal loss not verifiable
