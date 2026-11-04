# Round 8 check, lane p3-pages-club-publish (first pass)

Commit tested: `8b78f5ca3c06f486631e827827e973cbb47e3094`, the tip side of `integ/launch-test-20261103`. Branch:
`claude/check-1104-p3-pages-club-publish`. I find problems and do not fix them. No product code was changed.

**This is the browser rig, not a phone.** I used the rig from `claude/cloud-walk-20261101-rig` with the club lane's
`harness-patches.diff`, and `up.sh` pinned to the commit above. It ran the real jar with e2e stubs, real MySQL 8 and
Redis from the pinned images, and the real mini-program code bundled into headless Chrome at 390×844. Shots are 2x
JPEG under 200 KB, in `shots/`. Tools: `tools/sweep.mjs` opens a page and taps every bound control, `tools/peek.mjs`
runs a single page, and `tools/w-*.mjs` are the hand walks. Raw logs are in `logs/`.

Page list and grouping rule: `pages-p3.md`. Lane p1 did not run, so I made the list myself and it has **32 pages**.

## Identities: who is the club owner in this rig

- Fixture **9003** (「俱乐部主理人」, owner of club 7001) has RBAC role `player`, not `club`.
  `ClubAccessServiceImpl.resolve` (`chengyinhub-system/.../access/club/ClubAccessServiceImpl.java:62-66`) refuses an
  owner whose role is not `ROLE_CLUB`. So every owner-only club page refuses 9003: `/api/club/access/me` returns 403
  「俱乐部身份无效或权限不足」.
- So I made a real owner through the screens. Fixture player **9009** applied through `club/apply` (club_leader 5003,
  status 1) and created club **990004** 「夜行者测试社」, city 上海, through `club/create`. After that,
  `/api/club/access/me` for 9009 returns `CLUB_OWNER` with full permissions. All owner pages below were walked as
  9009 on club 990004.
- Club 990004 has no theme, session, member, customer or settlement data yet, so most club pages show only their
  **empty** state (see "What I did not cover").

## 1. Step table

Legend: **PASS** means it opened and its state reads right; **FAIL** links to a finding; **NOT VERIFIED** gives the
reason. "Empty" means the empty state was seen; "full" means there was data on screen.

### Hand walks (real taps, with DB read-back)

| # | Step | Result | Shots | Read-back |
|---|---|---|---|---|
| W1 | 9009 opens 成为主理人 intro | PASS (helper lines, F7) | h01-apply-intro | — |
| W2 | Step 1 empty: 下一步 is disabled | PASS | h02, h03 | — |
| W3 | Step 1 filled → step 2 → pick 1-5场 → step 3, no pick → step 4 | PASS | h04–h08 | — |
| W4 | Step 4 tap 「资质 / 案例图」 label | NOT VERIFIED: tapping the label does nothing; did not retry on the + box | h09 | `cert_images` empty |
| W5 | 成为俱乐部主理人 → done screen | PASS (repeated text, F7) | h10-apply-done | `club_leader` 5003: member 9009, 林测试, 1-5场, status 1 |
| W6 | 创建我的俱乐部 → create intro | PASS (5 taglines, F7) | h11-create-open, h12-create-intro | — |
| W7 | Step 1 type, step 2 directions (0 allowed) | PASS | h13-create-step1, h14-create-s1-picked, h15-create-s2, h16-create-s2-emptynext | — |
| W8 | Step 3 cover upload | NOT VERIFIED (HARNESS): upload returns 503 「安全检查服务异常，请稍后重试」 because the rig cannot reach api.weixin.qq.com | h14-create-s3-cover-done | app log: UnknownHostException api.weixin.qq.com |
| W9 | Step 3 type name, tap 下一步 | **FAIL F1**: first tap does nothing; second tap advances | h14-create-s3-filled, h15-create-s4 (still 3/4), h16-create-s4-next (4/4) | — |
| W10 | Step 4 city 上海 → 创建俱乐部 | PASS | h14-create-s4-filled, h15-create-created 「俱乐部已创建 · 夜行者测试社 · 上海」 | `club` 990004: member 9009, 兴趣社群, prefs 城市定向, city 上海 |
| W11 | Club detail as owner, 概览 | PASS with F9 (two names for one person) | h20-club-detail-owner | — |
| W12 | 管理 tab | PASS with F5 (three 发布主题 entries) | h21, h22, h23 | — |
| W13 | 发布主题 → mode sheet | PASS (helper line, F7) | h24-club-publish-entry | — |
| W14 | 城市定向 → `publish/fabu?clubId=990004&mode=1` | PASS; route picking skipped (map is being built) | h26-club-publish-orient | route options read back |
| W15 | 编辑资料: type intro → 保存修改 | PASS | h51-club-edit-focus, h52-club-edit-typed, h53-club-edit-bottom, h54-club-edit-saving, h55-club-edit-after | `club.description` = 夜里一起走城市的社团, update_time moved |
| W16 | Round 7 lead: `club/edit?id=7001` as 9003 | Explained (see L1) | x01-edit-9003-fixture-0 | `/api/club/access/me` 403 |
| W17 | 编辑玩法模板: open, 保存草稿, 查看预览, 选择玩法 | 选择玩法 sheet auto-opens on new template (owner-decided); F10 | h30-temp, h31, h33 | — |
| W18 | 确认终价: open, 无人带 | 无人带 works; **FAIL F6** (error block shows on open) | t05-pricing-a-open, h61-pricing-unled | — |
| W19 | 快速配置: prompt chip, 历史起草记录 | Chip fills the box: PASS. 历史起草记录: NOT VERIFIED (F11) | h71, h72 | no request sent |
| W20 | Guest on club detail, fabu, apply, create, mytemplate | detail/fabu/apply/mytemplate PASS; **create FAIL F2** | g-club-detail, g-publish-fabu, g-club-apply, g-club-create(-8s), g-mytemplate-mytemplate | — |

### Every p3 page (opened via navigateTo from home, then every bound control tapped by `sweep.mjs`)

"Controls" counts tap-bound elements on the opened screen. After each tap the page was reopened so the next tap
started clean. Back buttons were not tapped. Destructive or write buttons (解散, 发布, 保存, 提交, 发送) were skipped
in the sweep and walked by hand where noted.

| # | Page | How reached / identity | States seen | Controls | Result | Shot |
|---|---|---|---|---|---|---|
| 1 | club/apply | home → navigateTo, 9004; hand walk 9009; guest | intro, form ×4, done, guest login | 3 + walk | PASS, F7 | c02-*, h01–h10, g-club-apply |
| 2 | club/badges | `?clubId=990004`, 9009; from detail 「俱乐部勋章」 | empty | 3 | PASS (create sheet opens) | c03-club-badges-a-open |
| 3 | club/create | 9004 (no lead) → redirects to apply; 9009 walk; guest | intro, steps 1–4, success; guest **blank** | walk | **FAIL F1, F2**, F7 | c04-*, h11–h18, g-club-create-8s |
| 4 | club/customer-detail | `?clubId=990004&memberId=9009` (not a customer) | **blank** (business refusal shown as load failure) | 0 | **FAIL F3** | c05-club-customer-detail-a-open, x03-custdetail-self-late |
| 5 | club/customers | `?clubId=990004`, 9009 | empty 「还没有客户」, filters | 5 | PASS (filters switch) | c06-club-customers-a-open |
| 6 | club/detail | `?id=990004`, 9009 owner; guest; 9003 fixture | full (profile), manage, guest | 15 | PASS with F5, F9; F13 for 9003 | c01-*, h20–h23, g-club-detail |
| 7 | club/edit | `?id=990004`, 9009; 9003 on 7001 | full form, save; denied sheet for 9003 | 8 (2 skipped) | PASS; Round 7 lead explained (L1); F7 | c07-*, h51–h55, x01-* |
| 8 | club/enroll | `?clubId=990004&topicId=990013`, 9009 | empty 「还没有城市定向团」 | 1 | PASS, F7 (「报名费由平台担保托管」 caption) | c08-club-enroll-a-open |
| 9 | club/checkin-detail | `?clubId=990004&registrationId=1` | **blank** | 0 | **FAIL F3** | c09-club-checkin-detail-a-open |
| 10 | club/event-ops | `?clubId=990004`, 9009 | empty (no theme) | 2 | **FAIL F4** | c10-club-event-ops-a-open |
| 11 | club/governance | `?clubId=990004`; also reached from detail 封禁申诉 / 举报俱乐部 | empty 「暂无可治理成员」 | 1 | PASS | c11-club-governance-a-open, c01-club-detail-t05/t06 |
| 12 | club/group-code | `?topicId=990013&topicName=E2E`, 9009 | empty 「暂无可带队的场次」 | 1 | PASS, F7 (subtitle) | c12-club-group-code-a-open |
| 13 | club/join-requests | `?clubId=990004` | empty | 1 | PASS, F7 (「可直接通过或拒绝」) | c13-club-join-requests-a-open |
| 14 | club/notify | `?clubId=990004` | form; event audiences disabled | 9 (send skipped) | PASS, F7 (×3 「从具体活动进入后才可选」) | c14-club-notify-a-open |
| 15 | club/roles | `?clubId=990004` | empty 「暂无成员」 | 1 | PASS | c15-club-roles-a-open |
| 16 | club/settlement | `?clubId=990004` | zeros, empty lists | 1 | PASS (real zeros; no settlement data). 「¥ -2.00」 lead NOT VERIFIED | c16-club-settlement-a-open |
| 17 | club/topic-detail | `?id=990013&clubId=990004` (another club's theme) | **blank** | 0 | **FAIL F3**; own-theme full state NOT VERIFIED (no theme) | c17-club-topic-detail-a-open |
| 18 | club/topic-story | `?topicId=990013&clubId=990004` | empty 「暂无路线」, tabs | 3 | PASS (empty); full NOT VERIFIED | c18-club-topic-story-a-open |
| 19 | club/workbench | `?clubId=990004` | redirects to club/detail | — | PASS (redirect by design) | c19-club-workbench-a-open |
| 20 | publish/fabu | direct, 9003; from club 发布主题, 9009; guest | empty editor, chapter starter, guest login | 12 | PASS; map/route pieces skipped | p01-*, h26, g-publish-fabu |
| 21 | publish/simple | direct, 9003 / 9009 | idle, chip filled | 15 | PASS except F11; AI generate HARNESS | p02-*, h70–h72 |
| 22 | publish/temp | direct, 9003; from template-intro 开始创建 | new template with game sheet open | 53 | F10; sweep taps behind the sheet are not meaningful | p03-*, h30–h33 |
| 23 | publish/activity | 9009 via navigateTo: form; 9003: denied → back | form step 1; denied | 1 | PASS; F7 (「用于地图与到场导航，拒绝不影响填写」) | x02-activity-9009-late, p04-* |
| 24 | publish/topicadd | direct, 9003 | lands on the fabu editor | 12 | PASS (alias of fabu) | p05-* |
| 25 | publish/template-intro | direct | empty examples + 开始创建 | 3 | PASS → temp; F7 (two-line tagline) | p06-* |
| 26 | template/index | tab, 9003 / 9009 | empty 主题 shelf | 10 | PASS (inbox, search, tabs, +) | t01-*, h40-template |
| 27 | templatedetail | `?id=1` (no such template) | **blank** | 0 | **FAIL F3**; full NOT VERIFIED (no templates in rig) | t02-templatedetail-a-open |
| 28 | templatedetail/topic-preview | `?id=990013` | error 「模板不存在 · 重试」 | 2 | PASS (shows a reason), but 重试 is pointless on not-found (F3) | t03-topic-preview-a-open |
| 29 | templatedetail/topic-detail | `?id=990013` | **blank** | 0 | **FAIL F3** | t04-tpl-topic-detail-a-open |
| 30 | topic/pricing | `?topicId=990013`, 9003 | form | 6 (submit skipped) | **FAIL F6** | t05-pricing-a-open, h61 |
| 31 | topic/pricing/partner | `?topicId=990013&toType=MERCHANT&toId=8001` | 「合作链接已失效」 + 返回上一页 | 2 | PASS (clear dead-link state) | t06-pricing-partner-a-open |
| 32 | subpackageMember/mytemplate | direct, 9003; guest | empty; guest login | 1 | PASS | t07-*, g-mytemplate-mytemplate |

Page errors (JS exceptions): **0 on all 32 pages.** Console errors: only 404s for missing banner and profile images
in a fresh database (HARNESS).

## 2. Findings

**F1 — HIGH. 创建俱乐部 step 3: the first 下一步 after typing the name is swallowed.**
Repro: as a 主理人, open `club/create`, 开始创建, pick a type, 下一步 twice, type a club name, then tap 下一步 with the
input still focused. The tap blurs the input first. `onNameBlur → checkName()` sets `_nameCheckPending = name`. Then
`onNext → checkName(cb)` hits `if (this._nameCheckPending === name) return;` (`pages/club/create/index.js:267`) and
returns without calling `cb`, so step 4 never opens. The user taps again and it works. This is the natural order on a
phone, where the keyboard is open while you tap 下一步. Shots: `h14-create-s3-filled` (name typed), `h15-create-s4`
(still on 3/4 after the tap); the second tap reached `h16-create-s4-next`. Phone confirmation still needed, but the
code path is the same.

**F2 — HIGH (dead end). Guest on 创建俱乐部 sees a blank page with no login.**
Repro: no login, `wx.navigateTo('/pages/club/create/index')`. Page data: `bootstrapState=error`,
`bootstrapErrorKind=data`, `bootstrapError=「登录状态已失效，请重新登录」`. This is wrong for someone who never logged
in, and nothing at all is shown: no reason, no login button, no auto-back, still blank after 8 s. The neighbour
`club/apply` shows 「登录后申请成为主理人 · 登录」 in the same case. Fix direction: treat 401 at bootstrap as the
`no-permission`/login state, like apply does. Shots: `g-club-create`, `g-club-create-8s`, `g-club-apply`.

**F3 — HIGH (dead end), needs the owner's rule. A wrong or foreign id gives a blank page on five p3 pages.**
These pages use `cy-error load-failure`, which by its own comment (`components/cy/error/index.wxml:2`) "加载失败保持空白,
不画错误页、不弹半屏、不自动返回". The user is left on an empty dark or white page with only a back arrow:
- `templatedetail/templatedetail?id=1` → blank (`t02`)
- `templatedetail/topic-detail?id=990013` → blank (`t04`)
- `club/topic-detail?id=990013&clubId=990004` (another club's theme) → blank (`c17`)
- `club/checkin-detail?registrationId=1` → only the 核销详情 title (`c09`)
- `club/customer-detail?memberId=9009`: the server answers a business reason, 「这位客户没有在本俱乐部的成交记录」, and the
  page still shows blank (`c05`, `x03-custdetail-self-late`)

The neighbour `templatedetail/topic-preview` shows 「模板不存在 · 重试」 (`t03`), but 重试 can never succeed on not-found.
ROUND8 says "nothing blank". If load-failure-blank is an owner decision, it should still not swallow business
refusals such as not found, not yours or no record. Those should show the reason with a single back action. These
are mostly reached by stale shares or links.

**F4 — HIGH (misleading). 活动运营 with no theme shows a schedule builder that does nothing.**
`club/event-ops?clubId=990004`: under 日期清单 sits 「添加日期 · 按重复方式批量生成 +」. Tapping it changes nothing visible
(`resetSeriesForm`). 「重复方式」 is a heading with only 「最多排未来 90 天（64 期）」 under it. At the bottom, 「暂无可开场的主题 ·
先发布主题或完成商家合作」 is the real state. Layout fix: when there is no theme, show only the empty state with a
发布主题 action, and drop the builder and its three captions (「仅可修改无报名的未来场次」, 「按重复方式批量生成」, 「最多排未来
90 天（64 期）」). Shot `c10-club-event-ops-a-open`.

**F5 — LOW. 管理 tab repeats 发布主题 four times when the club has no theme.**
`club/detail` 管理 (`h21`, `h22`): 「现在要做 · 发布主题」 title, 「先发布一个俱乐部项目」 hint, 「带队进度 · 发布主题」 line, the
发布主题 button, then 「还没有项目 · 发布主题」 pill, then 「+ 发布主题」 row. The stat tiles show 0/0/0/— with the caption
「还没有可统计的项目数据」. Layout: keep the single 现在要做 card and drop the empty project card, the timeline line and
the zero tiles until there is a project.

**F6 — LOW, confirms a Round 7 lead. 确认终价: 成团人数 error is a pink block with an icon, shown before any input.**
`topic/pricing?topicId=990013`: on open, before typing, a large pink box with a warning icon says 「请填写大于 0 的成团人数」
(`t05-pricing-a-open`). The owner's rule is red text under the field, and it should appear only after input or on
submit. Same screen: an unlabeled 「¥100.00」 under 「你的终价 ¥0.00」 (slider max?) reads like a second price; plus
captions 「终价不可低于此值」, 「样本不足，暂不展示参考价」 and 「合作阵容锁定后可定价」, the last explaining a disabled button.

**F7 — LOW. Taglines, helper lines and secondary labels (owner's standing rule).** Each item has a suggested layout
change.
- `club/apply` intro: 「填完身份立即生效，不用等审核」, 「最多可以建 2 个俱乐部」 (h01). Step pages: eyebrow 「成为主理人」 above
  every title, plus 「标 * 的是必填」 (the asterisks already say it) (h03). Step 3: a grey second line under each option,
  e.g. 「选点、排顺序、控制时长」 (h07). Step 4: 「用于后续等级评定」 (h09). Done screen says it three times: subtitle
  「已成为主理人」, pill 「已生效」, sentence 「你已成为俱乐部主理人，身份立即生效。」 (h10). Fix: title plus one status row.
  「联系方式不对外展示」 is a privacy statement; keep it, the owner decides.
- `club/create` intro: 「给你的人 一个固定的落脚点」, 「类型 · 方向 · 资料 · 城市，一步一问」, 「填完立刻可用，不用等审核」, 「每位主理人最多建
  2 个俱乐部」, 「之后可修改」 (h12). Step pages: 「标 * 的是必填」. Step 1 options carry a second line, e.g. 「学生组织 · 校园路线」
  (h13).
- `club/detail` 发布主题 sheet: 「选定后会先进入连续选点，随时取消即可回到编辑器继续填写」 (h24).
- `club/edit`: 「头像 1:1 · 封面 16:9」, 「在主理人资料中修改」 (c07). 「成员将失去俱乐部与群聊入口，已报名不会自动退款。」 under 解散俱乐部 is a
  consequence warning on a danger action; keep it, the owner decides.
- `club/enroll`: 「我的俱乐部 · 0 个团 · 报名费由平台担保托管」 (c08). `club/group-code`: subtitle 「选择本次带队场次」 (c12).
  `club/join-requests`: 「可直接通过或拒绝」 (c13).
- `club/notify`: three disabled audience rows, each with 「从具体活动进入后才可选」 (c14). Fix: hide 本场已报名/本场候补/本场未到场
  unless the page was opened from an event.
- `publish/activity`: 「用于地图与到场导航，拒绝不影响填写」 under 活动地点 (x02-activity-9009-late).
- `publish/simple`: eyebrow 「城市定向 · 集合式路线」 and 「你喜欢什么」 above icon-only bubbles. The bubbles have no labels,
  so this is the reverse case: icons need words or the bubbles go (p02).
- `publish/template-intro`: 「把你玩过的一段路 / 做成别人也能走的玩法」 (p06).
- `publish/temp` 选择玩法 sheet: 「九宫格 在主题里配置」 (h30).

**F8 — LOW. Inconsistent punctuation between the two club sign-up flows.** `club/create` uses half-width 「哪些最符合你的活动
方向?」, 「你的俱乐部在哪座城市?」 and 「(选填)」. `club/apply` uses full-width 「你带过多少场？」 and 「（选填）」 (h13 vs h05).

**F9 — LOW. The owner shows under two names and two titles on 概览.** The info chip says 「林测试 · 主理人」 (from the
application), while the member grid and row say 「玩家二号 · 群主」 (nickname) (h20). Pick one name and one term
(主理人 or 群主). The row 「Lv.1 · 公开资料 → · 创建俱乐部」 is also a run of secondary labels.

**F10 — LOW, needs a phone. 选择玩法 sheet highlights the wrong group.** On a new 玩法模板 the sheet auto-opens (owner
decision 2026-10-22) with chip 「互动」 highlighted while the strip shows the 问答 column (h30, h31). The highlight is
driven by strip-scroll measurement (`pages/publish/temp/r5-editor.js:189`), which the rig may not reproduce, so
confirm on a phone.

**F11 — LOW, NOT VERIFIED. 快速配置 「历史起草记录」 does nothing in the rig.** Tap: no request, no sheet, no message (h71).
`openAiHistory` returns early when `_ensureAiSession()` is false (`pages/publish/simple/index.js:466`). That may be the
rig's session stamp, so check on a phone. If it also happens there, it is a dead button.

**F12 — HARNESS. Image uploads fail closed in the rig.** Club cover/logo upload → 503 「安全检查服务异常，请稍后重试」 in a
system modal, because the rig cannot reach api.weixin.qq.com. Lane j5 owns the WeChat-down behaviour. So covers,
logos, qualification images and template covers are not verified here.

**F13 — needs the owner (data question). An owner without the `club` RBAC role gets a half-working club.**
Fixture 9003 owns club 7001 but has role `player`. `club/detail` still gives them the 管理 tab (owner UI keyed on
`club.memberId`), but every owner API refuses them. Their 现在要做 card sits permanently on 「重试管理进度 · 暂时无法确认当前阶段」,
because `/api/coop/list` returns `{code:403, 商家身份无效或权限不足}` for a member who has a merchant row (8005) without
merchant access (`ApiCoopController.java:1556-1557`). `club/edit`, `club/customers` and the other owner pages close
with 「当前账号无权进入该俱乐部」. This is a fixture state. It matters only if production has club owners whose
`ums_member.role` is not `club` after the user_type → RBAC migration. Recommend a read-only count on production
before launch (owner action).

### Round 7 leads in p3's area

- **L1 `pages/club/edit` 「当前账号无权进入该俱乐部」 for 9003 with `?id=7001` while still rendering its form.** At this
  commit the form is **not** rendered: the page shows the 「暂时不能查看 · 当前账号无权进入该俱乐部」 sheet and goes back
  (`x01-edit-9003-fixture-0`). The refusal itself is correct for the fixture: 9003 is not RBAC `club` (F13). The real
  owner 9009 edits and saves fine (W15).
- **确认终价 pink block:** confirmed, F6.
- **Club settlement 「¥ -2.00」:** NOT VERIFIED. No settlement exists for club 990004 in this pass.

## 3. What I did not cover

- **Full states of the session pages.** Club 990004 has no published theme. Publishing one needs the route editor (map
  and route picking, which are being built and were skipped) plus 主题详情 (4 fields) and 票务 (5 fields). So
  `club/topic-detail` (director, 招商 layer), `club/topic-story`, `club/enroll`, `club/group-code`,
  `club/checkin-detail`, `club/roles` with members, `club/join-requests` with requests, `club/governance` with members,
  `club/customers` with customers, and `club/settlement` with money were seen **empty only**. Lane j2 runs that
  journey end to end.
- **Template full states:** the rig database has no reusable theme or gameplay templates, so the
  `templatedetail/*` full states and 「用这个模板」 were not reached.
- Publish/submit, 解散俱乐部, 群发 send and 确认终价 submit were not pressed. Write paths were proven only for club
  apply, club create and club edit save.
- AI drafting (`publish/simple` generate): it needs an external model (HARNESS).
- **Subpackage first-load:** the rig bundles every subpackage, so first-open download/placeholder behaviour cannot be
  seen. Phone only.
- Real phone: keyboard, safe areas, native pickers, share cards, real fonts. All NOT VERIFIED.
- The sweep's "NO VISIBLE EFFECT" rows are hints, not verdicts. I checked the ones above by hand. The rest are
  explained: 概览 and 全部 are already-selected tabs; 无人带, the notify audiences and the pricing tabs change state the
  text diff does not catch (I checked 无人带 by hand); in `publish/temp` the open sheet covered the controls.

STATUS: PARTIAL - first pass: all 32 pages opened and their controls swept. Club owner pages were seen empty only
because no club theme or session exists yet. Template full states were not reached because there are no templates
in the rig.
