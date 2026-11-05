# Round 8 check, lane p3-pages-club-publish (second pass)

Commit tested: `573c463b3b457b77544e63be4271a07d5d565e09` (on `integ/launch-test-20261103`). Branch:
`claude/check-1105-p3-pages-club-publish`. I find problems and do not fix them. No product code was changed.

**This is the browser rig, not a phone.** Rig: `claude/cloud-walk-20261101-rig` plus
`integ/proof/round10-f3-rig-changes.diff` (the club lane's harness patches, the dummy OSS settings, the `nav.js`
`pageLifetimes.show` patch), `up.sh` pinned to the commit above, Docker through `mirror.gcr.io`,
`LANG=C.UTF-8`. Real jar, real MySQL 8 and Redis, the real mini-program bundled into headless Chrome at 390×844.
Shots are 2x JPEG under 200 KB in `shots/`, and I looked at each one I cite. Tools are in `tools/`
(`run.mjs` is the step walker, `sweep.mjs` taps every control on a page, `fx_*.py` are the fixture scripts,
`db.py` reads the database back). Raw sweep logs are in `logs/`.

Page list: the first pass's 32 pages (`pages-p3.md` on `claude/check-1104-p3-pages-club-publish`). At this
commit `app.json` still registers 157 pages, and the same 32 are in p3's group.

All people, phones and names are fictional.

## 0. First-pass findings: are they really gone?

| First pass | What it was | Now | Evidence |
|---|---|---|---|
| **F1** | club/create step 3: first 下一步 after typing the name was swallowed | **Gone.** With the name input still focused (checked: `document.activeElement` is the INPUT), one tap on 下一步 moves to 4/4. Same on step 4: one tap on 创建俱乐部 with the city field focused creates the club. Walked twice, by two different leads (9009 → club 990004, p3-pc → club 990005). | `f1-01`…`f1-03`, `f1-06`, `f1-07`; `club` rows 990004, 990005 |
| **F2** | Guest on club/create saw a blank page | **Gone.** Guest sees 「登录后创建俱乐部 · 登录」. 登录 logs in and lands on 成为主理人 (a new account is not a lead yet), which is the right next step. | `f2-01`, `f2-02` (still there after 9 s), `f2-04` |
| F3 | Wrong or foreign id gave a blank page on five pages | **Gone.** Each shows the server's reason and one 返回: customer-detail 「这位客户没有在本俱乐部的成交记录」, checkin-detail 「这条核销记录不存在，或不属于本俱乐部」, templatedetail and topic-preview 「模板不存在」, club/topic-detail of another club's theme 「主题不存在」. topic-preview no longer offers 重试. | `z01`…`z05` |
| F4 | event-ops with no theme showed a dead schedule builder | **Gone.** New club 990005: only 「暂无可开场的主题 · 发布主题」. With a theme the builder shows, which is right, but its captions remain (see N10). | `z06`, `s03` |
| F5 | 管理 tab repeated 发布主题 four times | **Gone.** No-theme club shows one 现在要做 card: 「发布主题」 title and button. | `z08`, `z09` |
| F6 | 确认终价: pink error block on open | **Gone.** Nothing on open. After pressing confirm without 成团人数, plain red text under the field. Slider end reads 「最高 ¥100.00」. Captions 「终价不可低于此值」 and 「样本不足，暂不展示参考价」 remain (N10). | `p01`, `p04`, `p05` |
| F7 | Taglines and helper lines | **Mostly gone.** Gone: apply and create intros, eyebrows, 「标 * 的是必填」, option second lines, 「用于后续等级评定」, edit's 「头像 1:1 · 封面 16:9」, enroll's 托管 caption, join-requests' caption, notify's three disabled rows, 快速配置's unlabeled bubbles (they now say 咖啡、美食…). **Still there:** publish/activity 「用于地图与到场导航，拒绝不影响填写」; group-code now has a new caption 「团码仅限本场次；组队邀请码不能核销」; 快速配置 「本机保留记录」; club/detail 「选好日期和集合时间」 under 开一场. See N10. | `a01`…`a05`, `f1-*`, `e01`, `k02`, `r01`, `k10`, `h01`, `y03`, `k05`, `d02` |
| F8 | Half-width punctuation in create | **Gone.** 「哪些最符合你的活动方向？」, 「你的俱乐部在哪座城市？」, 「（选填）」. | `f1-01`, `f1-05` |
| F9 | Owner under two names and 主理人/群主 | **Gone on club/detail** (林测试 · 主理人 everywhere there). But the naming problem moved to the member screens: see N3. | `d01`, `z07` |
| F10 | 选择玩法 highlight | NOT VERIFIED: phone only (scroll measurement). | — |
| F11 | 快速配置 历史起草记录 does nothing | **Still the same** in the rig: tap gives no sheet, no message, no request. Next to it now sits 「本机保留记录」. With no local history there should at least be an empty state. | `h01`, `h02` |
| F12 | Uploads fail closed in the rig | HARNESS, unchanged. | — |
| F13 | Owner without the `club` role | Fixture only. I set `ums_member.role='club'` for 9003 and 9008, as f3 did; production count still needs the owner. | — |
| Lead L1 | club/edit 「无权进入」 for 9003 | Not reproducible with the role fixed; owner 9009 edits and saves. | `e01`…`e04` |
| Lead | Club settlement 「¥ -2.00」 | NOT VERIFIED. I could not make a post-settlement refund: once the session's time passed, the real auto-verify job marked the orders 已核销 and 清退并退款 is no longer offered. The positive case is right to the fen (W30). | `q05`, `m01` |

## 1. Step table

Evidence key: **UI** is a real screen and real taps. **FIX** is a write to the throwaway database with its reason.
**API** is the app's own endpoint called because the rig can't drive that screen. **HARNESS** is a rig seam.

### Fixtures and setup (all in the throwaway database)

- FIX `ums_member.role='club'` for 9003 and 9008 (first-pass F13, as f3 did).
- Ran the candidate's own chains through `finance` (`chains.py finance`: all five PASS). That gives the platform 探店日
  theme 990014; it did not give a club-session settlement, so I built one (W17–W30).
- FIX `settlement_rule`: the fresh rig has **no** rows, so pricing fell back to the hard-coded 10% (the app logs an
  error). I inserted the three post-migration rows at 3% (`activity`, `topic`, `topic_club`) and re-froze theme 990015
  at 0.0300, because it had already frozen 0.1000. **r1 must confirm production has these rows.** See N1.
- FIX `cms_setting` id 1 (platform account 9001) and applied `migration_20260815_tandianri_h8_publisher_points_funds.sql`:
  without them the settlement job refuses 「活动结算配置不存在」 and then fails on `rule_version`. Same two gaps j2 (F-03)
  and f2 hit. **r1 must confirm production has both.**
- FIX one `sys_category` row of type 4 (玩法类别). The rig seed has none, so 玩法模板 can't be published (W38).
- FIX time shift: activity 15, its ticket and occurrence moved 9 days back so the real jobs would settle it.

### Walks

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| W1 | Guest opens 创建俱乐部 | PASS (F2 gone) | f2-01, f2-02 | — |
| W2 | Guest taps 登录 | PASS: logged in, sent to 成为主理人 | f2-04 | new member 990005 |
| W3 | 9009 成为主理人, 4 steps | PASS | a01–a05 | `club/become-leader` 200 |
| W4 | 创建俱乐部; step 3 one tap with input focused | **PASS (F1 gone)** | f1-01–f1-03 | `step` = 4 after one tap |
| W5 | Step 4: city typed, one tap on 创建俱乐部 | PASS | f1-06, f1-07 | `club` 990004, 上海 |
| W6 | Theme 990015 via the editor's body (API, j2's `c_author.py` / `c_create.py`): 3 gameplay templates, 2 chapters, 3 stations, ¥39 led ticket | API (editor pop-ups are post-launch; cover upload HARNESS) | — | templates 6005–6007, topic 990015 |
| W7 | Platform approves templates and project (real admin endpoint) | PASS | — | audit tasks 4–8 |
| W8 | 确认终价: open | PASS (F6 gone) | p01 | — |
| W9 | 确认终价 without 成团人数 | PASS: red text under the field | p04 | no request |
| W10 | 重新计算地板 | PASS: floor ¥35.30, max ¥136.00 | p05 | `pricing/preview` 200 |
| W11 | **确认终价并提交开售审核** | PASS server side; frozen rate was 10% before FIX (N1). The success toast lasts 0.5 s before the page closes (N13) | p06 | 「终价已确认，已提交开售审核…」, audit task 9 |
| W12 | Platform sale review (real admin endpoint) | PASS | — | lifecycle 3, `fee_platform_rate` 0.0300 |
| W13 | Club 管理 tab with a theme on sale | PASS | d02–d04 | — |
| W14 | 开一场 → event-ops with defaults, 创建系列场次 | **FAIL N6**: defaults are today 09:00, already past; tap gives a toast only | s04, s05 | no request |
| W15 | Pick 22:00 (wheel: HARNESS), 创建系列场次 | PASS | s10–s12 | series 1, occurrence 1, activity 15 22:00 |
| W16 | 编辑资料: switch 入会需要审批 on, 保存修改 | PASS | e03, e04 | `club.join_policy` 1 |
| W17 | 4 players 申请加入 with a message (empty message refused first) | PASS | j01–j05 | 4 pending rows |
| W18 | 入会申请 list (full) | PASS with **N3** (3 of 4 named 「城瘾用户」) | r01 | — |
| W19 | 拒绝 one: confirm → reason sheet → 确认拒绝 | PASS, 3 steps (N12) | r02, r03, r04 | 990008 status 2 |
| W20 | 通过 three, each with a confirm | PASS | r05, r06, r07 「暂无待审申请」 | status 1 ×3 |
| W21 | 角色与权限: pick member, role 俱乐部运营, 分配 | PASS server side; **FAIL N3** (picker shows 「成员#990006」) | o03–o07 | `club_role_assignment` 1 ACTIVE |
| W22 | 3 players buy the ¥39 ticket (API, j2/chain_player's route; payment through the rig's real-callback seam) | API + HARNESS | — | regs 75–77 `payment_status` 2 |
| W23 | 报名名册 (enroll) full | PASS with **N4** (phantom 0/20 block) | k02 | 实收 ¥117.00 |
| W24 | 客户 list and detail (full) | PASS with N3, N11 | k03, k08 | — |
| W25 | 团码 session picker | **FAIL N4**: a second, non-existent session 「11月5日 10:18」 | k05 | only activity 15 exists |
| W26 | 主题导演台 (club/topic-detail) and 剧情与玩法 | PASS | k01, k04 | — |
| W27 | 群发通知: fill, 预览受众, 发送站内通知 | PASS | n01–n04 | campaign 1, 4 recipients, `IN_APP` |
| W28 | 核销详情 → 清退并退款 → 退款并清退 on reg 77 | PASS server side; **N7** (no amount before, 已退款 vs 「尚未确认到账」, 「已核销订单不可退款」 caption) | q01–q04 | reg 77 `payment_status` 4, ¥39 refund dispatching |
| W29 | Settlement before the session ended | PASS: 待结算 ¥113.49 (= ¥117 × 97%) | k06 | — |
| W30 | Real jobs (auto-verify 139, settlement 145) after the time-shift FIX | **PASS, to the fen**: ¥78.00 sold (77 voided as refunded), 主理人 9009 +¥75.66, platform 9001 +¥2.34 (3%) | m01–m03 | `settlement_detail` 1–4 status 1, 5–6 「退款作废」; balances 75.66 / 2.34 |
| W31 | 俱乐部结算 with money | PASS with N9 (money formats) | m01 | `sessions[0]` ¥78.00 / ¥2.34 / ¥75.66 |
| W32 | Post-settlement refund (「¥ -2.00」 lead) | NOT VERIFIED: orders auto-verified, no refund action | q05 | — |
| W33 | 成员治理: pick member, 填写原因并封禁, 确认封禁 | PASS server side; **N3** (picked 「林一帆」, record says 「玩家小城」) | g01, g03, g04, g05 | ban 1 ACTIVE, 9004 removed |
| W34 | 我的项目 → 另存为模板 (long-press: HARNESS, handler called) | PASS | v02–v04 | `topic-template/save-as` 200 |
| W35 | 模板 tab → theme template → preview | PASS with **N2** (preview describes a structure the theme doesn't have) | t05, t06 | — |
| W36 | 查看详情 → 应用 (use the template) | PASS | t09, t12 | draft 990016 for 9003 |
| W37 | Editor on the copy: 下一步 → 预览 → **完成 (publish)** | **FAIL N5**: 「请选择路线归属的俱乐部」, though the club field already shows the lead's only club | u03, u07 | `formData.clubId` "", `clubIndex` 0, `myClubs.length` 1 |
| W38 | 玩法模板: pick 选项问答, **发布玩法** | Validation PASS (inline errors). Category sheet was 「暂无分类」 until FIX; description box doesn't take typing in the rig (editor pop-up, post-launch). Publish NOT VERIFIED | w03, w05, w06, w08 | — |
| W39 | publish/activity as merchant 9002 | PASS: 「当前身份不能发布活动 · 发布单场活动需要俱乐部主理人身份。」 then back | y01 | — |
| W40 | publish/activity as lead 9009 | Form opens; submit NOT VERIFIED (date wheel and type sheet). Date sheet defaults to today 09:00, already past (N6) | y03, y06 | — |
| W41 | 快速配置 历史起草记录 | FAIL F11 (unchanged) | h02 | no request |
| W42 | Second lead p3-pc: apply, create, event-ops, 管理 | PASS (F1, F4, F5 gone) | z06–z09 | club 990005 |
| W43 | club/topic-detail after the session was settled | **N8**: 开始准备 shown and does nothing (time-shifted fixture state) | x01, x02 | no request |

### Every p3 page (sweep: opened, then every bound control tapped; writes skipped and walked by hand above)

SWEEP_TABLE

## 2. Findings (new in this pass, or still present)

FINDINGS

## 3. What I did not cover

NOTCOVERED

STATUS_LINE
