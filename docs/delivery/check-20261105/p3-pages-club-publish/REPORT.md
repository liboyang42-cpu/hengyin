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
| F4 | event-ops with no theme showed a dead schedule builder | **Gone.** New club 990005: only 「暂无可开场的主题 · 发布主题」. With a theme the builder shows, which is right, but its captions remain (see N11). | `z06`, `s03` |
| F5 | 管理 tab repeated 发布主题 four times | **Gone.** No-theme club shows one 现在要做 card: 「发布主题」 title and button. | `z08`, `z09` |
| F6 | 确认终价: pink error block on open | **Gone.** Nothing on open. After pressing confirm without 成团人数, plain red text under the field. Slider end reads 「最高 ¥100.00」. Captions 「终价不可低于此值」 and 「样本不足，暂不展示参考价」 remain (N11). | `p01`, `p04`, `p05` |
| F7 | Taglines and helper lines | **Mostly gone.** Gone: apply and create intros, eyebrows, 「标 * 的是必填」, option second lines, 「用于后续等级评定」, edit's 「头像 1:1 · 封面 16:9」, enroll's 托管 caption, join-requests' caption, notify's three disabled rows, 快速配置's unlabeled bubbles (they now say 咖啡、美食…). **Still there:** publish/activity 「用于地图与到场导航，拒绝不影响填写」; group-code now has a new caption 「团码仅限本场次；组队邀请码不能核销」; 快速配置 「本机保留记录」; club/detail 「选好日期和集合时间」 under 开一场. See N11. | `a01`…`a05`, `f1-*`, `e01`, `k02`, `r01`, `k10`, `h01`, `y03`, `k05`, `d02` |
| F8 | Half-width punctuation in create | **Gone.** 「哪些最符合你的活动方向？」, 「你的俱乐部在哪座城市？」, 「（选填）」. | `f1-01`, `f1-05` |
| F9 | Owner under two names and 主理人/群主 | **Gone on club/detail** (林测试 · 主理人 everywhere there). But the naming problem moved to the member screens: see N4. | `d01`, `z07` |
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
  at 0.0300, because it had already frozen 0.1000. **r1 must confirm production has these rows.** See N2.
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
| W11 | **确认终价并提交开售审核** | PASS server side; frozen rate was 10% before FIX (N2). The success toast lasts 0.5 s before the page closes (N14) | p06 | 「终价已确认，已提交开售审核…」, audit task 9 |
| W12 | Platform sale review (real admin endpoint) | PASS | — | lifecycle 3, `fee_platform_rate` 0.0300 |
| W13 | Club 管理 tab with a theme on sale | PASS | d02–d04 | — |
| W14 | 开一场 → event-ops with defaults, 创建系列场次 | **FAIL N8**: defaults are today 09:00, already past; tap gives a toast only | s04, s05 | no request |
| W15 | Pick 22:00 (wheel: HARNESS), 创建系列场次 | PASS | s10–s12 | series 1, occurrence 1, activity 15 22:00 |
| W16 | 编辑资料: switch 入会需要审批 on, 保存修改 | PASS | e03, e04 | `club.join_policy` 1 |
| W17 | 4 players 申请加入 with a message (empty message refused first) | PASS | j01–j05 | 4 pending rows |
| W18 | 入会申请 list (full) | PASS with **N4** (3 of 4 named 「城瘾用户」) | r01 | — |
| W19 | 拒绝 one: confirm → reason sheet → 确认拒绝 | PASS, 3 steps (N13) | r02, r03, r04 | 990008 status 2 |
| W20 | 通过 three, each with a confirm | PASS | r05, r06, r07 「暂无待审申请」 | status 1 ×3 |
| W21 | 角色与权限: pick member, role 俱乐部运营, 分配 | PASS server side; **FAIL N4** (picker shows 「成员#990006」) | o03–o07 | `club_role_assignment` 1 ACTIVE |
| W22 | 3 players buy the ¥39 ticket (API, j2/chain_player's route; payment through the rig's real-callback seam) | API + HARNESS | — | regs 75–77 `payment_status` 2 |
| W23 | 报名名册 (enroll) full | PASS with **N5** (phantom 0/20 block) | k02 | 实收 ¥117.00 |
| W24 | 客户 list and detail (full) | PASS with N4, N12 | k03, k08 | — |
| W25 | 团码 session picker | **FAIL N5**: a second, non-existent session 「11月5日 10:18」 | k05 | only activity 15 exists |
| W26 | 主题导演台 (club/topic-detail) and 剧情与玩法 | PASS | k01, k04 | — |
| W27 | 群发通知: fill, 预览受众, 发送站内通知 | PASS | n01–n04 | campaign 1, 4 recipients, `IN_APP` |
| W28 | 核销详情 → 清退并退款 → 退款并清退 on reg 77 | PASS server side; **N7** (no amount before, 已退款 vs 「尚未确认到账」, 「已核销订单不可退款」 caption) | q01–q04 | reg 77 `payment_status` 4, ¥39 refund dispatching |
| W29 | Settlement before the session ended | PASS: 待结算 ¥113.49 (= ¥117 × 97%) | k06 | — |
| W30 | Real jobs (auto-verify 139, settlement 145) after the time-shift FIX | **PASS, to the fen**: ¥78.00 sold (77 voided as refunded), 主理人 9009 +¥75.66, platform 9001 +¥2.34 (3%) | m01–m03 | `settlement_detail` 1–4 status 1, 5–6 「退款作废」; balances 75.66 / 2.34 |
| W31 | 俱乐部结算 with money | PASS with N10 (money formats) | m01 | `sessions[0]` ¥78.00 / ¥2.34 / ¥75.66 |
| W32 | Post-settlement refund (「¥ -2.00」 lead) | NOT VERIFIED: orders auto-verified, no refund action | q05 | — |
| W33 | 成员治理: pick member, 填写原因并封禁, 确认封禁 | PASS server side; **N4** (picked 「林一帆」, record says 「玩家小城」) | g01, g03, g04, g05 | ban 1 ACTIVE, 9004 removed |
| W34 | 我的项目 → 另存为模板 (long-press: HARNESS, handler called) | PASS | v02–v04 | `topic-template/save-as` 200 |
| W35 | 模板 tab → theme template → preview | PASS with **N3** (preview describes a structure the theme doesn't have) | t05, t06 | — |
| W36 | 查看详情 → 应用 (use the template) | PASS | t09, t12 | draft 990016 for 9003 |
| W37 | Editor on the copy: 下一步 → 预览 → **完成 (publish)** | **FAIL N6**: 「请选择路线归属的俱乐部」, though the club field already shows the lead's only club | u03, u07 | `formData.clubId` "", `clubIndex` 0, `myClubs.length` 1 |
| W38 | 玩法模板: pick 选项问答, **发布玩法** | Validation PASS (inline errors). Category sheet was 「暂无分类」 until FIX; description box doesn't take typing in the rig (editor pop-up, post-launch). Publish NOT VERIFIED | w03, w05, w06, w08 | — |
| W39 | publish/activity as merchant 9002 | PASS: 「当前身份不能发布活动 · 发布单场活动需要俱乐部主理人身份。」 then back | y01 | — |
| W40 | publish/activity as lead 9009 | Form opens; submit NOT VERIFIED (date wheel and type sheet). Date sheet defaults to today 09:00, already past (N8) | y03, y06 | — |
| W41 | 快速配置 历史起草记录 | FAIL F11 (unchanged) | h02 | no request |
| W42 | Second lead p3-pc: apply, create, event-ops, 管理 | PASS (F1, F4, F5 gone) | z06–z09 | club 990005 |
| W43 | club/topic-detail after the session was settled | **N19**: 开始准备 shown and does nothing (time-shifted fixture state) | x01, x02 | no request |
| W44 | 成员治理 → 转让给所选成员 (周小雨), 交接原因, 确认转让 | PASS server side; N9 (no feedback) | g06–g10 | `club` 990004 owner 990006; roles swapped (9009 → player, 990006 → club) |
| W45 | New owner: club page, 管理, 编辑资料, 俱乐部结算 | PASS; settlement row 「已入原主理人账户」. N4 (two names in the header) | g11–g13 | `/api/club/access/me` CLUB_OWNER |
| W46 | Former owner opens 俱乐部结算 | Sent home with no message (N9) | g14 | — |

### Every p3 page (sweep: opened, then every bound control tapped; writes skipped and walked by hand above)

All 32 pages opened from home by navigateTo and every tap-bound control was tapped once, with the page reopened between taps. Page errors: **0 on all 32**. Console errors: only missing images (OSS stand-in, HARNESS). Writes were skipped by handler name and walked by hand above. "No visible effect" is a hint, not a verdict: already-selected tabs (概览, 全部, 详情, 路线, 有人带, 主题), filter chips that only re-filter, and preset chips that fill an input aren't caught by the text diff. The ones that matter are in the findings: 团码 重试 (N5), 历史起草记录 (F11), and 添加日期 after N1. This sweep ran 10:55–11:15. The ownership transfer (W44) happened at 11:01, so rows after it saw the new owner. Rows c04 and c19 redirect by design (c04 sends a non-lead to 成为主理人; c19 is an alias of club/detail).

| Page | Path | As | Route | First screen text | Controls | Page errors | Taps with no visible effect | Shot |
|---|---|---|---|---|---|---|---|---|
| c01-club-detail | `pages/club/detail/index?id=990004` | 9009 | opened | 夜行者二测社 / 兴趣社群 · 3 名成员 / 需审核加入 / 帖文 / 活动 / 概览 / 管理 / 暂无简介 / 林测试 / 主理人 / | 24 / 21 tapped / 2 skipped | 0 | 概览 | r2-c01-club-detail-a-open |
| c02-club-apply | `pages/club/apply/index` | 9004 | opened | 把你带的人， 变成你的俱乐部 / 成为主理人 | 3 / 2 tapped / 0 skipped | 0 | — | r2-c02-club-apply-a-open |
| c03-club-badges | `pages/club/badges/index?clubId=990004` | 9009 | opened | 俱乐部勋章 / 夜行者二测社 / 创建勋章 / 还没有俱乐部勋章 | 3 / 1 tapped / 1 skipped | 0 | — | r2-c03-club-badges-a-open |
| c04-club-create | `pages/club/create/index` | 9004 | went to pages/club/apply/index | 把你带的人， 变成你的俱乐部 / 成为主理人 | 3 / 2 tapped / 0 skipped | 0 | — | r2-c04-club-create-a-open |
| c05-club-customer-detail | `pages/club/customer-detail/index?clubId=990004&memberId=990006` | 9009 | opened | 客户 / 周小雨 / 周 / 最近互动 11月5日 / 累计实付 / ¥39 / 客户概览 / 1 / 到店次数 / 0 / 待核销 / 0 | 4 / 3 tapped / 0 skipped | 0 | 周 | r2-c05-club-customer-detail-a-open |
| c06-club-customers | `pages/club/customers/index?clubId=990004` | 9009 | opened | 客户 / 3 位 · 本月新增 3 / 全部 / 回头客 / 新客 / 有备注 / 陈 / 陈小满 / 核销 0 次 · 最近 11-05  | 11 / 10 tapped / 0 skipped | 0 | 全部, 新客 | r2-c06-club-customers-a-open |
| c07-club-edit | `pages/club/edit/index?id=990004` | 9009 | opened | 编辑资料 / 俱乐部形象 / 头像 / 封面 / 未上传 / 基础信息 / 名称 / * / 城市 / * / 关键词 / 风格 / 俱乐部 | 8 / 5 tapped / 2 skipped | 0 | — | r2-c07-club-edit-a-open |
| c08-club-enroll | `pages/club/enroll/index?clubId=990004&topicId=990015` | 9009 | opened | 报名名册 / 我的俱乐部 · 0 个团 / 还没有城市定向团 | 1 / 0 tapped / 0 skipped | 0 | — | r2-c08-club-enroll-a-open |
| c09-club-checkin-detail | `pages/club/checkin-detail/index?clubId=990004&registrationId=75` | 9009 | opened | 核销详情 / 林一帆 / 2026-10-27 22:00 / 13900000004 / 已核销 / 02:44 / 2026-10-27 | 3 / 2 tapped / 0 skipped | 0 | onTap | r2-c09-club-checkin-detail-a-open |
| c10-club-event-ops | `pages/club/event-ops/index?clubId=990004` | 9009 | opened | 活动运营 / 日期清单 / 仅可修改无报名的未来场次 / 主题 #990015 · 单次 / 候补开启 / 编辑未来场次 / 日期清单（含已 | 4 / 3 tapped / 0 skipped | 0 | 添加日期 按重复方式批量 | r2-c10-club-event-ops-a-open |
| c11-club-governance | `pages/club/governance/index?clubId=990004` | 9009 | opened | 成员治理 / 治理记录 / 成员「玩家小城」 / 俱乐部治理 · 2026-11-05 10:48 · 至 2026-12-05 10:48 | 6 / 2 tapped / 3 skipped | 0 | — | r2-c11-club-governance-a-open |
| c12-club-group-code | `pages/club/group-code/index?topicId=990015&topicName=x` | 9009 | opened | 活动核验团码 / 该场次不可带队核销 / 重试 / 人民广场 · 喷泉报时 · 11月5日 10:18 | 5 / 4 tapped / 0 skipped | 0 | 活动核验团码, 重试 | r2-c12-club-group-code-a-open |
| c13-club-join-requests | `pages/club/join-requests/index?clubId=990004` | 9009 | opened | 入会申请 / 暂无待审申请 | 1 / 0 tapped / 0 skipped | 0 | — | r2-c13-club-join-requests-a-open |
| c14-club-notify | `pages/club/notify/index?clubId=990004` | 9009 | opened | 群发通知 / 选择受众 / 全部成员 / 3 人 / 已选 / 管理员 / 2 人 / 选择 / 近 90 天未活跃 / 2 人 / 选择  | 6 / 4 tapped / 1 skipped | 0 | 全部成员 3 人 已选, 管理员 2 人 选择, 近 90 天未活跃 2  | r2-c14-club-notify-a-open |
| c15-club-roles | `pages/club/roles/index?clubId=990004` | 9009 | opened | 角色与权限 / 当前委派 / 林测试 / 全部权限，不可撤销 / 主理人 / 周小雨 / 可管理内容、入会审核与成员通知；活动与现场权限按场 | 5 / 3 tapped / 1 skipped | 0 | 分配 | r2-c15-club-roles-a-open |
| c16-club-settlement | `pages/club/settlement/index?clubId=990004` | 9009 | opened | 俱乐部结算 / 商家计酬 / 0 人次 / 俱乐部直接付款 / 待结清 / ¥0.00 / 已结清 / ¥0.00 / 待退回 / ¥0.0 | 2 / 1 tapped / 0 skipped | 0 | — | r2-c16-club-settlement-a-open |
| c17-club-topic-detail | `pages/club/topic-detail/index?topicId=990015&clubId=990004` | 9009 | opened | 已确认 / 人民广场 · 喷泉报时 / 11月5日 – 1月4日 / 商家 / 报名参与者 / 团码 / 更多 / 主题 / 人民广场 ·  | 8 / 7 tapped / 0 skipped | 0 | — | r2-c17-club-topic-detail-a-open |
| c18-club-topic-story | `pages/club/topic-story/index?topicId=990015&clubId=990004` | 9009 | opened | 剧情与玩法 / 路线 / 玩法 / 第一章 广场的回声 / 2 站 / 1 / 人民广场音乐喷泉 / 上海市黄浦区人民大道 / 5min 步 | 5 / 4 tapped / 0 skipped | 0 | 路线 | r2-c18-club-topic-story-a-open |
| c19-club-workbench | `pages/club/workbench/index?clubId=990004` | 9009 | went to pages/club/detail/index | 夜行者二测社 / 兴趣社群 · 3 名成员 / 需审核加入 / 帖文 / 活动 / 概览 / 管理 / 扫码 / 出示二维码 / 分享 /  | 16 / 14 tapped / 1 skipped | 0 | 管理 | r2-c19-club-workbench-a-open |
| p01-publish-fabu | `pages/publish/fabu/index?clubId=990004&mode=1` | 9009 | opened | * / 已存本机 / 主题详情 / 还有 4 项未填 / * / 模式 / 城市定向 / 切换 / 票务 / 票务设置 / 还有 5 项未填 | 12 / 9 tapped / 2 skipped | 0 | switchViewMo, switchViewMo | r2-p01-publish-fabu-a-open |
| p02-publish-simple | `pages/publish/simple/index` | 9009 | opened | 快速配置 / 城市定向 · 集合式路线 / 嗨，想做一条什么样的路线？ / 咖啡 / 美食 / 拍照 / 老建筑 / 夜游 / 散步 / 公 | 15 / 14 tapped / 0 skipped | 0 | __onTap, 历史起草记录, 老城建筑与咖啡, 周末亲子探险, 雨天室内漫游 | r2-p02-publish-simple-a-open |
| p03-publish-temp | `pages/publish/temp/index` | 9009 | opened | 编辑玩法模板 / 上传玩法封面 / * / 模板信息 / 玩法看点 / * / 0/30 / 所需材料 / 商铺类型 / 请选择 / 保存草 | 53 / 37 tapped / 2 skipped | 0 | 上传玩法封面, 选择玩法 选择, 移除, __onTap, 查看预览, cgDismiss, 退出, 完成, 问答, 推理, 互动, 挑战, 九宫格 | r2-p03-publish-temp-a-open |
| p04-publish-activity | `pages/publish/activity/index` | 9002 | opened | 发布活动 | 1 / 0 tapped / 0 skipped | 0 | — | r2-p04-publish-activity-a-open |
| p05-publish-topicadd | `pages/publish/topicadd/topicadd` | 9009 | went to pages/publish/fabu/index | * / 已存本机 / 主题详情 / 还有 4 项未填 / * / 模式 / 城市定向 / 切换 / 票务 / 票务设置 / 还有 5 项未填 | 12 / 9 tapped / 2 skipped | 0 | switchViewMo, switchViewMo | r2-p05-publish-topicadd-a-open |
| p06-publish-template-intro | `pages/publish/template-intro/index` | 9009 | opened | 暂无精选示例 / 把你玩过的一段路 / 做成别人也能走的玩法 / 开始创建 | 3 / 1 tapped / 1 skipped | 0 | — | r2-p06-publish-template-intro-a-open |
| t01-template | `pages/template/index` | 9009 | opened | 主题 / 玩法 / 搜索主题与玩法模板 / 封面暂不可用 / 城市定向 / 人民广场 · 喷泉报时 / 实验模板 · 2 章 · 3 个玩法 | 15 / 10 tapped / 0 skipped | 0 | 主题, 全部 | r2-t01-template-a-open |
| t02-templatedetail | `pages/templatedetail/templatedetail?id=6005&scope=my` | 9009 | opened | 喷泉的水柱 / 玩家二号 / 继续编辑 / 详情 / 能力 / 简介 / 人民广场音乐喷泉中央最高的那根水柱旁，有几盏地灯围成一圈？（填数字 | 5 / 4 tapped / 0 skipped | 0 | 详情 | r2-t02-templatedetail-a-open |
| t03-topic-preview | `pages/templatedetail/topic-preview/index?id=990015` | 9009 | opened | 封面暂不可用 / 人民广场 · 喷泉报时 / 1 / 喷泉的水柱 / 2 / 博物馆的鼎 / 3 / 大世界的塔楼 / 45 分钟 / 封面 | 6 / 5 tapped / 0 skipped | 0 | 封面暂不可用 人民广场 , 封面暂不可用 喷泉的水柱, 封面暂不可用 博物馆的鼎, 封面暂不可用 大世界的塔 | r2-t03-topic-preview-a-open |
| t04-tpl-topic-detail | `pages/templatedetail/topic-detail/index?id=990015` | 9009 | opened | 人民广场 · 喷泉报时 / 城瘾 / 应用 / 详情 / 能力 / 剧情 / 90 分钟城市定向：人民广场出发，经上海博物馆，在大世界收尾。 | 5 / 4 tapped / 0 skipped | 0 | 详情 | r2-t04-tpl-topic-detail-a-open |
| t05-pricing | `pages/topic/pricing/index?topicId=990015` | 9009 | opened | 确认终价 / 有人带 / 无人带 / 最低价 / ¥0.00 / 终价不可低于此值 / 你的终价 / ¥39.00 / 地板 ¥0.00 / | 5 / 3 tapped / 1 skipped | 0 | 有人带 | r2-t05-pricing-a-open |
| t06-pricing-partner | `pages/topic/pricing/partner/index?topicId=990015&toType=MERCHANT&toId=8001` | 9009 | opened | 合作方 / 合作链接已失效 / 链接已失效，请返回重新进入 / 返回上一页 | 2 / 1 tapped / 0 skipped | 0 | — | r2-t06-pricing-partner-a-open |
| t07-mytemplate | `subpackageMember/mytemplate/mytemplate` | 9009 | opened | 我的节点玩法 / 喷 / 0 / 0 次引用 / 喷泉的水柱 / 人民广场音乐喷泉中央最高的那根水柱旁，有几盏地灯围成一圈？（填数字） /  | 7 / 6 tapped / 0 skipped | 0 | — | r2-t07-mytemplate-a-open |


## 2. Findings (new in this pass, or still present)

**N1 — BLOCKER (dead end, silent unpublish). 「另存为模板」 takes a theme that is on sale off sale, with no way back.**
Repro: as 主理人 9009, 我的项目 → hold the card of theme 990015 (on sale, 3 paid orders) → 另存为模板 → confirm. The
confirm only says 「「人民广场 · 喷泉报时」会出现在主题模板货架上，其他人可以复制它作为草稿起点。」 After it:
- the public theme list (`/api/topic/list` as player p3-pa) no longer has 990015;
- the club's theme list for players (`/api/club/topics`) is empty;
- 开一场 says 「暂无可开场的主题 · 先发布主题或完成商家合作」, while its date list still shows the old series as
  「主题 #990015 · 单次」 (a raw id), and 添加日期 now does nothing;
- 报名名册 says 「我的俱乐部 · 0 个团 · 还没有城市定向团」, so the paid roster disappears.

Cause: `saveAsTemplate` (`CmsTopicServiceImpl.java:2034`) runs `updateTopicIsTemplate` (`CmsTopicMapper.xml:1249`).
That sets `is_template = 1` on the **live** row instead of copying it, and the public and openable lists filter
`is_template = 0` (`CmsTopicMapper.xml:743`). No action in the app sets it back. Shots: `v03`, `v04`,
`r2-c10-club-event-ops-a-open`, `r2-c08-club-enroll-a-open`. Read-back: `cms_topic` 990015 has `is_template` 1 and
lifecycle 3.

**N2 — BLOCKER if production is like the rig; otherwise a release check. Missing `settlement_rule` rows mean a silent 10%
price.** In a fresh database, `PricingServiceImpl.platformRate()` falls back to 0.10 (`PricingServiceImpl.java:49`) and
only logs an error. 确认终价 then **froze `fee_platform_rate` 0.1000** on theme 990015. The launch rate is 3%. I fixed
the rig (see FIX above). r1/release should confirm that production has the three `merchant_id = 0` rows at 0.0300
before go-live. The owner may also want this fallback to refuse instead of pricing. `cms_setting` id 1 and the h8
points migration follow the same pattern: without them the settlement job refuses or fails (j2 F-03).

**N3 — HIGH (misleading). The theme-template preview promises things the template doesn't have.** Theme 990015 has
2 chapters, 3 stations, no ending choice and no named medal. Its preview says 「3 关剧情，每关一个玩法」 and
「最后一关三选一结局」. Its cards say 「读档案 · 做任务，拿证据 · 解锁下一关」 and 「汇总证据 · 三选一结局 · 领取勋章」
(`t06`). These are fixed strings per product type (`pages/templatedetail/topic-preview/index.js:12-15, 46-49`), not
the template's data. The next page, 详情, shows the truth: 「2 章节 · 3 节点」 (`t09`). The layout is the owner's
2026-10-28 prototype, but the claims should come from the data or be removed.

**N4 — HIGH. The organiser can't tell members apart, and one person has up to four names.** These are the same four
players in the same club, as shown on the owner screens:
- 入会申请: 「玩家小城」 and three 「城瘾用户」. The reject dialog says 「拒绝「城瘾用户」的入会申请?」 (`r01`, `r02`).
- 角色与权限: the member picker shows 「成员#990007」 and 「成员#990006」, which are raw database ids. The assigned row
  reads 「成员#990006」 (`o03`, `o07`).
- 报名名册: 「玩家小城」, 「城瘾用户」, 「城瘾用户」 (`k02`).
- 客户, 成员治理, 核销详情: 「林一帆」, 「周小雨」, 「陈小满」, the names typed at sign-up (`k03`, `g01`, `q01`).
- Banning 「林一帆」 creates a record for 「玩家小城」 on the same screen (`g04`).
- After the transfer, the club header reads 「城瘾用户 · 主理人」 next to the member row 「周小雨 · 主理人」 (`g11`).

So the owner approves, assigns a role, bans and transfers without knowing who the person is. The fix is one display
name per member across all owner screens: the sign-up name where there is one, otherwise the nickname, and never an id.

**N5 — HIGH (misleading). The theme's own ticket shows up as a session.** Theme 990015 has one theme-level ticket
(`oms_ticket` 81, `owner_type` 1, start 11-05 10:18) and one real session (activity 15, ticket 83, 22:00).
- 报名名册 shows 「领队场 0 / 20 · 还没有人报名」 above 「11月5日 22:00 · 领队场 3 / 20」 for the same ticket (`k02`).
- 团码 lists two sessions, 「11月5日 22:00」 and 「11月5日 10:18」. The second doesn't exist (`k05`). After the real session
  had passed, 团码 opened the phantom on its own and showed 「该场次不可带队核销 · 重试」. That 重试 can't succeed
  (`r2-c12-club-group-code-a-open`).

Caveat: the theme ticket's start time came from j2's create body, which uses the editor's shape. The real editor may
set it differently.

**N6 — HIGH (dead end). Publishing a theme copied from a template asks for a club that already shows as chosen.**
Repro: 9003 (owns one club, 7001) → 模板 → theme template → 查看详情 → 应用. Draft 990016 opens in the editor →
下一步 → 预览 → 完成. The toast says 「请选择路线归属的俱乐部」 (`u07`).

Page data: `myClubs.length` 1 and `clubIndex` 0, so the club row in the 主题详情 sheet shows 城瘾跑团. But
`formData.clubId` is "". The check is at `pages/publish/fabu/index.js:7508`. `loadMyClubs` (`index.js:3950`) only
writes `formData.clubId` when the page has no club yet, so something later on this path clears it. The user is asked
to choose something that already looks chosen. The copy also dropped the theme's category and tickets, so 主题详情
shows 「还有 1 项未填」. Each tap on 应用 creates another draft with no confirm: the sweep made 990017 and 990018.

**N7 — HIGH (misleading). 清退并退款.**
- The confirm has four helper lines and no amount (`q02`), so the organiser can't see that ¥39.00 goes back.
- After the refund, the status chip says 「已退款」 while the message says 「…现金退款正在派发，尚未确认到账…」 (`q03`).
- Before the refund, 「已核销订单不可退款」 sits under the 清退并退款 button on an order that is 待核销 (`q01`).

The read-back is right: reg 77 has `payment_status` 4 and `cashRefundAmount` 39.00, and its settlement rows were
voided with 「退款作废」.

**N8 — LOW. Date pickers default to a time that has already passed.** 开一场 defaults to today 09:00, and 发布活动's
time sheet to 11月5日 09:00. I walked them at about 10:20. 创建系列场次 then only shows the toast
「集合时间必须晚于当前时间」 (`s04`, `s05`, `y06`). The default should be the next valid slot.

**N9 — LOW. Ownership transfer gives no feedback, and the former owner loses the screen that shows their money.**
After 确认转让, the former owner lands on the player home with no message (`g09`). Opening 俱乐部结算 again sends them
home silently (`g14`), though their ¥75.66 可提现 was only ever shown there. The new owner's page correctly says
「已入原主理人账户」 (`g12`). On 成员治理, one 选择成员 picker drives both 封禁 and 转让, so a member picked for one action is
preselected for the other (`g06`).

**N10 — LOW. Money on 俱乐部结算.** The same screen writes 「¥75.66」 (可提现) and 「¥ 75.66」 in a monospace font (the
session row). That is because the backend builds the session texts as 「¥ x」 (`settlement/summary` `netText`), which
is the cause behind the Round 7 「¥ -2.00」 lead. The negative case itself is NOT VERIFIED (W32). An all-zero
商家计酬 card with 「还没有计酬核销记录」 also tops the page for a club that has no merchant terms (`m01`).

**N11 — LOW. Taglines, helper lines and secondary labels still on p3 screens.** Each comes with a layout fix:
- 开一场 (event-ops): 「仅可修改无报名的未来场次」, 「按重复方式批量生成」, 「最多排未来 90 天（64 期）」, 「退款窗口按「集合时刻前 24
  小时」计算。」, 「满员后按报名顺序候补」, 「自定义容量只适用于恰有一个票种的合作主题；多票种继续沿用原票种容量。」, 「当前为 1 天。」,
  「空位为候补首位保留，最晚至报名截止」, 「先发布主题或完成商家合作」 (`s03`, `s04`). Fix: keep the labels and values, put the
  refund rule in the 退款 row's value, and hide 容量 when a theme has several tickets instead of explaining it.
- 确认终价: 「终价不可低于此值」 and 「样本不足，暂不展示参考价」 (`p01`). Fix: the 最低价 label already says this; drop the
  empty reference box.
- 团码: 「团码仅限本场次；组队邀请码不能核销」, new since the first pass (`k05`).
- 发布活动: 「用于地图与到场导航，拒绝不影响填写」 (`y03`).
- club/detail 管理: 「选好日期和集合时间」 under 开一场 (`d02`).
- 快速配置: 「本机保留记录」 next to 历史起草记录 (`h01`).
- 角色与权限: a role description under each row, 「全部权限，不可撤销」, and 撤销 set inside the caption text (`o07`).
- 入会申请 reject dialog: 「对方会收到未通过通知;下一步可选填原因一并告知」 and 「对方之后仍可以重新提交申请」 (`r02`).
- club/topic-detail: a lone 「已确认」 pill on the hero, with nothing saying what is confirmed (`k01`).
- 合作链接已失效 page: the same thing said twice, 「合作链接已失效」 and 「链接已失效，请返回重新进入」 (`r2-t06-pricing-partner-a-open`).
- Consequence warnings on danger actions (解散; 转让 「转让后你将失去主理人权限」; 封禁) are left for the owner to decide.

**N12 — LOW, needs owner. A customer's account phone shows on 客户 and 核销详情.** It shows only for the one customer
whose account has a bound phone (林一帆 → 「13900000004」). It is the account phone, not the phone typed at sign-up
(13800000101) (`k03`, `k08`, `q01`). Decide whether owners see phones and, if they do, which one, and then show it
consistently.

**N13 — LOW. Rejecting a join request takes three taps:** 拒绝, then a confirm with two helper lines, then a reason
sheet with 确认拒绝. Each 通过 also has its own confirm (`r02`, `r03`, `r05`). One sheet with an optional reason would do.

**N14 — LOW. 确认终价's success message lasts 0.5 s.** On success the page shows
「终价已确认，已提交开售审核，审核通过后开售」 and goes back after 500 ms (`pages/topic/pricing/index.js:327-328`), so the next
step is never read.

**N15 — LOW, needs owner. 确认终价 and the theme editor are light pages in the club lead's otherwise dark flow**
(`p01`, `t13` vs `d02`).

**N16 — LOW. 群发通知 counts look wrong.** It shows 「近 90 天未活跃 2 人」 out of 3 members, all of whom joined and paid
today (`r2-c14-club-notify-a-open`).

**N17 — LOW. 玩法模板: the error under 玩法看点 says 「请填写玩法描述」**, a different name for the same field (`w03`).

**N18 — LOW, may be the rig. The editor's 预览 sheet:** after scrolling, the 画面风格 heading runs under the status bar,
and the ✕ / 完成 buttons sit over the sample (`u04`). The 画面风格 sample also shows an unrelated story
(「提篮桥的方舟」, 「1938年11月…」) instead of this theme's chapter.

**N19 — LOW, time-shifted fixture state. 开始准备 on a session that is over does nothing.** After the time shift and
settlement, club/topic-detail shows only 开始准备. Tapping it sends nothing and shows nothing (`x02`). In real use a
finished session would show its own state, so treat this as a lead.

**F11 (first pass), still present — LOW.** 历史起草记录 gives no response when there is no local history (`h02`). It
should at least show an empty state.

**HARNESS (not product bugs):**
- 核销时间 shows 02:44 for a 10:44 event because the rig's MySQL runs in UTC (`q05`).
- Picker wheels, long-press and image upload are seams or can't run in the rig. Each use is marked.
- The rig seed has no type-4 玩法类别 (W38).
- The sweep's Chinese skip patterns reached it as `????` because of the shell locale. Only the English handler-name
  patterns protected writes. I checked afterwards: no destructive control fired. Only the template 应用 copies
  (990017, 990018) were written.

## 3. What I did not cover

- **Players playing the session** (check-in, director runtime, chapter release, finishing). That is j2's journey. I moved the session into the past and let the real jobs verify and settle it, so the director page during a live session wasn't seen in this pass. W43/N19 is only a fixture state.
- **Post-settlement refund** and the 「¥ -2.00」 text (W32).
- **Publishing a brand-new theme through the editor**: the editor's input pop-ups are post-launch by owner decision, and covers can't be uploaded in the rig. I pressed 发布 on a template copy (N6) and pressed 确认终价, but theme 990015 itself was created through the editor's endpoint (W6).
- **Publishing a 玩法模板** past validation (W38) and **submitting 发布活动** (W40): wheel pickers and editor pop-ups.
- **Merchant 招商 on a club theme**: I built the club session without a merchant station. p2 and j2 own the merchant side.
- **Phone-only behaviour**: keyboard and blur order (F1 is confirmed here only as far as the rig's focus model goes), F10's highlight, subpackage first-load and placeholders, safe areas, native pickers, share cards, real fonts.
- **Guest view of every p3 page**: this pass only re-ran create (F2). The first pass covered detail, fabu, apply and mytemplate.
- **The ownership-transfer side of j2's journey** beyond what W-transfer shows (g06–g14), and the 3% self-play price on a club theme (j2).

STATUS: COMPLETE
