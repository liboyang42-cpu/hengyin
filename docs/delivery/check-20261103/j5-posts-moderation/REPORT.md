# j5-posts-moderation: first pass

- **Commit tested:** `8b78f5ca3c06f486631e827827e973cbb47e3094`, exactly.
- **Branch:** `claude/check-1104-j5-posts-moderation`.
- **Rig:** `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff`, `up.sh` pinned to the commit above, `LANG=C.UTF-8`, and `mirror.gcr.io` as the Docker mirror. It runs the real jar, MySQL 8.0.45, Redis 7.4 and the real mini-program code in a headless browser runtime.
- **This is not a phone.** Screenshots are 390×844 at 2x, JPEG under 200 KB, in `shots/`.

## Rig seams added for this lane (harness only, never product code)

| Seam | Why | Product code still exercised |
|---|---|---|
| `RigWxOutboundStub` (from l8-safety) replaces the e2e content-security stub. It overrides only the outbound HTTP to WeChat `msg_sec_check`. The answer (pass, busy, review or risky) is read from `/tmp/rig-wxsec-mode`. | The e2e stub skips the self-built word library. | The word library, normalisation, switches, openid gate and errcode handling |
| `tools/RigImgSecStub.java`: the upload-time `img_sec_check` goes to a local stub (`tools/imgsec_server.py`), and the OSS put writes bytes locally. The app gets fake OSS bucket, endpoint and key properties so the media guard recognises our own uploads. | The rig cannot reach WeChat or OSS. | The known-bad-image library (SHA-256 and dHash), metadata strip, media-asset staging and `AppMediaReferenceGuard` |
| Camera seam: `MPWEB.samplePhoto` returns our generated images. | To upload a chosen made-up image. | The app's real `chooseImage`, crop and upload flow |
| `walk.mjs` takes shots at 2x. `app.py` accepts extra `--` properties. | Shot format, and the fake OSS settings. | — |

- All words are made up: blocked token `zqvxlm`, allow-listed phrase `zqvxlmpark`, and the rescan token `qwpkzt`.
- The "known-bad" image is a generated gradient. We registered it through the real admin endpoint `/business/sensitive/images`, which stores the fingerprint only.
- Uploaded images do not display in the rig because the OSS host is fake. Blank image cards are HARNESS.
- Times in lists show UTC (for example 04:46) because the rig MySQL runs in UTC while the app writes +08:00. l8 noted the same. This is HARNESS-suspect; check it on staging.

## 1. Steps

| # | Step (real screen unless marked) | Result | Shot | Read-back |
|---|---|---|---|---|
| 01 | Club lead posts in club 帖子: text with the library word | PASS: refused, toast 「内容包含违规信息，请修改后重试」 | `01-clubpost-1a/1b` | `club_post` 0 rows; `content_sensitive_hit` TEXT/BLOCK row, no word stored |
| 01 | Same, word split with symbols `z.q-v x*l_m` | PASS: refused | `01-clubpost-2a/2b` | 0 rows |
| 01 | Same, allow-listed phrase | PASS: published | `01-clubpost-3a/3b`, `01-clubpost-z-list` | `club_post` id 1 |
| 02 | Player joins club 7001 (公开加入) | PASS | `02-playerpost-0-joined`, `02-playerpost-0b-welcome-sheet` | `/api/club/join` joined |
| 02 | Player posts with a clean image (album → crop → 选取) | PASS (image does not render: HARNESS) | `02-playerpost-1a0-crop`, `1a`, `1b` | `club_post` id 2 `images=media-asset:2`; outbox row queued for async check |
| 02 | Player picks the known-bad image | PASS: refused at upload, modal 「图片未通过内容安全检查，请更换后重试」 | `02-playerpost-2a-image` | IMAGE hit row; no asset |
| 02 | Player picks a near copy (resized, +6% brightness, q70) | PASS: refused | `02-playerpost-3a-image` | IMAGE hit row |
| 03 | Club lead writes a 文字问答 node game in the theme flow (`pages/publish/temp?from=fabu`) with the word in name, rule and question, then 保存并绑定 | **FAIL**: saved, 「节点玩法已保存 … 将随主题一起审核」 | `03-template-0..3` | `cms_member_template` 6005 「桥边问答 zqvxlm」 |
| 03 | Theme with that node game is published (HTTP, same DTO the editor sends), and another player opens the theme | **FAIL**: public; player sees 「桥边问答 zqvxlm」 under 节点介绍 | `03-template-4-player-sees-theme-node` | `cms_topic` 990014 `status=1, user_status=1`; only a PENDING human `audit_task` (`evidence/03-template-chain.txt`) |
| 04 | Player likes the lead's post | PASS | `04-clubsocial-2-liked` | `club_post_like` (1, 9004) |
| 04 | Player follows the lead from the post | PASS | `04-clubsocial-3-followed`, `05-follow-1-homepage-light` | `ums_member_follow` 9004→9003; homepage 粉丝 1 / 已关注 |
| 04 | Club post comment: word / split word / allow phrase | PASS: refused / refused / posted | `04-clubsocial-4..7` | `club_post_comment` only the allow-phrase row |
| 06 | Private message to player 9009: word / split / allow | PASS: refused (red warning on the bubble) / refused / delivered | `06-im-1..6` | `im_message` only the allow-phrase row, `check_status=1` |
| 07 | Recipient inbox (收件箱) shows the message; 发件箱 tab | PASS (outbox lists invites only, by design) | `07-inbox-1/2` | — |
| 07 | 城瘾小助手 for a new player | NOT VERIFIED: no system conversation exists until a system message arrives; see 09 | `07-inbox-3` | — |
| 08 | WeChat busy: club post and private message | PASS as built: refused 「内容审核服务繁忙，请稍后重试」, nothing saved (fail-close, no hold) | `08-busy-clubpost-1b`, `08-busy-im-3` | 0 rows |
| 09 | WeChat busy: theme save (HTTP), then other player and author open it | PASS for the hold: held (`user_status=0`, recheck queued) and hidden from the other player. **FAIL** for screens: see findings | `09-hold-1-held-as-9004`, `09-hold-1-held-as-9003` | `content_safety_recheck` state 0 |
| 09 | WeChat back, real Quartz `contentSafetyRecheckTask` run | PASS: released; other player now sees it; author gets 「审核通过」 | `09-hold-2-released-player-sees` | recheck state 1 PASS, `user_status=1` |
| 09 | Recheck finds a violation (risky) | PASS: stays hidden; author gets 「审核未通过 … 自动检测判定内容违规，未公开」 in 系统通知 | `09-hold-3-author-1..3`, `09-hold-4` | recheck state 2 RISKY |
| 10 | Published club post and chat message containing `qwpkzt`; word then imported; real `sensitiveContentRescanTask`, report mode | PASS: club post reported, untouched | `10-rescan-0-*` | `evidence/10-rescan-report.txt` |
| 10 | Removal switch on (real `/system/config`), rerun | PASS: post removed from feed; author notified. **FAIL**: the notice gives a wrong reason, see findings | `10-rescan-1-feed-after-removal`, `10-rescan-2-author-3-assistant` | `del_flag=2`, hit REMOVED (`evidence/10-rescan-remove.txt`); switch set back to false |
| 10 | Private chat message with the rescan word | Not rescanned (private 1:1 chat is outside rescan scope by design) | — | `im_message` 4 unchanged |
| 11 | Profile nickname: word / split / intro with word / allow nickname | PASS: refused ×3, allow saved | `11-profile-1..3`, `11-profile-allow-*` | `ums_member.nickname` 「小城zqvxlmpark」 |
| 12 | Club edit page `pages/club/edit?id=7001` as the club lead | **FAIL**: 「暂时不能查看 / 当前账号无权进入该俱乐部」, cannot save (known lead, owner p3/j2) | `12-clubedit-0..3` | nothing written |
| 12 | Club profile via club detail → 设置 → 编辑资料: intro with word / split / allow | PASS: refused / refused / saved | `12-clubedit-scene-1..3` | `club.description` allow phrase |
| 13 | Merchant 品牌故事 (light side): word / uppercase spaced `Z Q V X L M` / allow | PASS: refused / refused / saved | `13-merchant-1b`, `13-merchant-2-1b`, `13-merchant-3-1b` | `mms_merchant.description` allow phrase |
| 14 | Theme 评价 (`/api/comment/add`) from the theme page | NOT VERIFIED on screen: tapping the empty 「还没有评价」 card did not open the sheet in the rig (`voteShow` stayed false; cause not determined, could be the rig). Over HTTP: word refused, split refused, allow posted, external image refused | `14-review-1-word-b-after` | `evidence/14-review-http.txt` |
| 15 | 发现 → 帖文 feed | PASS: club posts show; the removed post is gone | `15-feed-3-posts-tab` | `/api/club/post/feed` |
| 16 | 我的 → 帖文 | **FAIL**: 「还没有帖文 / 你还没有发布过帖文」 although this player has a live club post | `16-mine-2-my-posts` | `club_post` id 2 by 9004 is live |
| — | Activity publish (title, description, ticket name and description, venue) | NOT VERIFIED: every fixture identity is refused before content checks (「当前身份不可创建活动」 / 「仅俱乐部主理人或商家可发布活动」 / 「商家身份无效或权限不足」) | — | — |

## 2. Findings

### BLOCKER

**B1. Text in theme node games goes public with no machine check.**
- **What happens:** a club lead writes a node game inside the theme flow. A library-blocked word in its name, rules or question is saved, bundled into the theme and shown to every player on the public theme page. The editor tells the author 「将随主题一起审核」. In fact the theme goes live at once (`user_status=1`), and the only review is a PENDING human audit task. The owner's standing rule is automatic detection, not people.
- **Repro:**
  1. As club lead 9003, open `pages/publish/temp/index?from=fabu`.
  2. Choose 文字问答 → 完成, then upload a cover.
  3. Name 「桥边问答 zqvxlm」, rule and question also containing `zqvxlm`, answer 1990. Tap 保存并绑定 → 「节点玩法已保存」.
  4. Publish a club theme whose node has `templateId` = that draft. In the rig this step was done over HTTP with the editor's DTO.
  5. As player 9004, open `pages/topic/index/index?id=990014` → 详情 → 节点介绍 shows 「桥边问答 zqvxlm」.
- **Code:**
  - `TemplatePublishServiceImpl.saveOrUpdateDraft` (`chengyinhub-system/.../TemplatePublishServiceImpl.java:642-720`) only guards media.
  - `ApiTopicController.combinedThemeReviewTexts` (`chengyinhub-admin/.../ApiTopicController.java:332-345`) does not collect bound templates. Its node part is `JourneyReviewTexts.collectChapters`: name, description, address, hookText.
  - The standalone `/api/template/publish` does check text (`ApiTemplateController.java:473`), so the same words are refused on that path.
- **Shots:** `03-template-3-saved-unchecked`, `03-template-4-player-sees-theme-node`.
- **Scope:** I only proved name/title. By the same code path the question, options, hints, feedback and story blocks are also unchecked (code-read, not walked).

**B2. (code-read, NOT VERIFIED in rig) Activity ticket and venue text are not checked.**
- **Code:** `ApiActivityController.java:846` (publish) and `:975` (update) send only `name` and `description` to the gate. The client (`pages/publish/activity/index.js:524-550`) also sends `addressName`, `address` and `tickets[].name/description`. Those are shown to players and never reach `checkText` or the library.
- **Why it matters:** by default, publish-first mode makes them public immediately. Compare theme tickets, which are checked (`ApiTopicController.java:338-343`).
- **Not verified:** the rig's fixture identities cannot create activities. Treat this as a BLOCKER if confirmed. It needs a merchant or club lead with `canCreateEvent`.

### HIGH

**H1. Held or removed theme opened by link is a blank black page.**
- **What happens:** the backend answers 「主题活动不存在」 (`/api/topic/info-to-user`), but the page shows nothing: no message and no way forward. A shared link to a theme that is held for recheck, taken down or rejected is a dead end.
- **Repro:** WeChat busy → theme held → as another player open `pages/topic/index/index?id=990017` as the launch page.
- **Code:** `pages/topic/index/index.js:2060-2075` expects an "auto-back fail 半屏".
- **Shot:** `09-hold-1-held-as-9004`.

**H2. The author is not told a theme is held.**
- **What happens:** `/api/topic/v2/create` answers 「主题发布成功」 / `published: true` while the theme is held (`user_status=0`, `check_status=2`). On the theme page the author sees it exactly like a live theme, with no 审核中 state.
- **Shots:** `09-hold-1-held-as-9003`. Read-back is in the HTTP log in the transcript (`p_hold.py`).

**H3. Wrong reason in the rescan takedown notice.**
- **What happens:** a club post removed for a **text** hit tells the author 「…已被下架。原因：图片未通过内容安全检测」. The post had no image.
- **Code:** `SensitiveContentRescanServiceImpl.java:123` → `takeDownForSafety` → `SystemNoticeCard.java:13/41`, which carries a fixed image reason.
- **Shot:** `10-rescan-2-author-3-assistant`.

**H4. 我的 → 帖文 says 「你还没有发布过帖文」 while the player has a live club post.** The same post shows in 发现 → 帖文 (`/me/posts` covers community posts only). Shots: `16-mine-2-my-posts` vs `15-feed-3-posts-tab`.

**H5. Profile edit says 昵称 「未填写」 although the player is shown everywhere as 「玩家小城」.** The page header also reads 「城瘾玩家」. Shot: `11-profile-0-page`.

**H6. The club edit deep link is a dead end for the club lead.** 「当前账号无权进入该俱乐部」 is shown over the form. This is the known lead from earlier rounds (owner p3/j2), and it still reproduces at this commit. The same lead can edit through club detail → 设置 → 编辑资料. Shots: `12-clubedit-1b-saved`.

### LOW

- **L1. Club 俱乐部类型 shows the city.** After the lead sets city 上海, the 俱乐部类型 card and the header line read 「上海」. `pages/club/detail/index.js:478` falls back to `club.city`. Shot: `12-clubedit-scene-3b-saved`.
- **L2. Chat ordering.** After two refused sends and one delivered send, the delivered message sorts above the two failed bubbles, although it was sent last. Shot: `06-im-6-thread`.
- **L3. WeChat busy refuses instead of holding.** On club posts, comments, chat, profile, club and merchant profiles, users are refused and must retry. Nothing is held. In a long WeChat outage nobody can post or chat. This is as built (`checkText` fails closed); it differs from the hold-and-release flow on themes. Owner call.
- **L4. Error shown two or three times, as blocks.**
  - Profile: toast plus a blue 「资料没有保存 … 重试保存」 banner. 「重试保存」 resends the same blocked text.
  - Merchant 品牌故事: pink 「暂未完成」 block, toast and 「重试保存」 button.
  - The owner's rule is red text under the field.
  - Shots: `11-profile-1b-saved`, `13-merchant-1b-saved`.
- **L5. 系统通知 cards don't name the content.** They read 「你提交的内容已通过审核。原因：你提交的内容已通过审核」 (the reason repeats the title), with no theme name. The two actions are jargon (「查看生成时记录 / 查看当前原单」). Shot: `09-hold-3-author-3-assistant`.
- **L6. The system conversation is titled 「系统通知」, not 「城瘾小助手」.** That is the fallback in `subpackageB/pages/im/list/index.js:134` when the counterparty has no nickname. It may be fixture data: check whether production has the assistant member.
- **L7. The other user's homepage is light on the player side.** `pages/userinfo/userinfo` shows a light background. It also shows the lead as 「主理人策划工作室」 (merchant name), while the club feed shows 「俱乐部主理人」. Shot: `05-follow-1-homepage-light`.
- **L8. Terms.** The club tab says 「帖子」; the agreed term is 帖文. The welcome sheet says 「发帖」.
- **L9. Taglines, helper and secondary lines** (owner rule: cut them and fix the layout):
  - Club join welcome sheet: three helper lines. Cut it, or let the tabs carry it. Shot `02-playerpost-0b-welcome-sheet`.
  - Template saved screen: 「已绑定当前主题，将随主题一起审核。」 is also inaccurate, see B1.
  - Chat footer: 「系统通知不支持回复，有新动态会出现在这里」.
  - Empty states: 「TA 还没有发布帖文」 and 「你还没有发布过帖文」 under the title.
  - Theme 评价 empty card: 「未参与也可以评价…」, plus a second, duplicate disclaimer line under the card 「评论积分须符合参与及首评条件…」.
  - Club overview stat captions 「俱乐部类型 / 所在城市」 are labels on data and can stay.
- **L10. Post composer clipped at the left edge.** 「取消」 and both avatars are cut at x=0 in every composer shot. It may be the rig's sheet transform; check on a phone. Shot: `01-clubpost-2b-after`.
- **L11. Template editor layout bleeds to the edges.** 「可触发的结果」 and the 「完成节点」 field sit flush at the screen edge, and the dashed buttons bleed off both sides. The editor is p3's page.

### HARNESS (not product bugs)

- Uploaded images render as empty cards because the OSS host is fake.
- Times show UTC.
- Before the fake OSS keys were added, the raw message 「Could not write JSON: 对象存储配置缺失: CHENGYIN_OSS_ACCESS_KEY_ID」 reached a toast and a theme page rendered blank. That was rig config; with keys set it is gone. Note anyway that the client shows raw backend 500 text.
- The anonymous `GET /api/city/nodes` probe in `up.sh` still returns 401 (j4's lead).

## User-content entries and the protection each reaches

- Walked = through the screens here. HTTP = proven over HTTP here. Code = code read only (inventory traced client call → controller → service).
- CT = `checkText` (word library + WeChat). GATE = `ContentSafetyGate` (library + WeChat + hold/recheck). MRG = own-media guard. UP = upload check (known-bad image + WeChat `img_sec_check`). MCA = async media check.

| Entry | Text | Images | Evidence |
|---|---|---|---|
| Club post / announcement (`/api/club/post/create`, update) | CT | UP + MRG + MCA outbox | Walked |
| Club post comment | CT | — | Walked |
| Private message text (`/api/im/send`) | CT | IM images: MCA only, hidden until checked | Walked (text) |
| Profile nickname, intro, case, website, wechat | CT | MRG + MCA | Walked (nickname, intro) |
| Club profile (create, update-mine, welcome note, join message, badge) | CT | MRG + MCA | Walked (intro); others Code |
| Merchant shop profile, decor, coop profile, AI NPC, perks, review reply, CRM broadcast | CT | MRG + MCA | Walked (品牌故事); others Code |
| Theme (create/update: name, description, chapters, nodes, story, runtime, tickets, medal) | GATE (hold → recheck) | MCA + publication guard | HTTP + screens (hold, release, reject) |
| **Theme-bound node game drafts (`/api/template/draft`)** | **none** | publication guard only | **Walked + HTTP: BLOCKER B1** |
| Standalone template publish, city-node template | CT + JRT | MCA | Code |
| **Activity ticket name/description, venue name/address** | **none** (name + description only go to GATE) | MRG + MCA | **Code: B2, NOT VERIFIED** |
| Theme / activity / POI 评价 (`/api/comment/add`) | CT | MRG + MCA | HTTP (screen entry did not open in rig) |
| Community posts and replies (`/api/v1/community`) | GATE (pre-review) | upload receipt + hold + MCA | Code (composer outside clubs sits on the roam page, which is still being built; l8 proved it over HTTP) |
| Coupon | GATE | — | Code |
| Team name / apply, stamps, object card, roam check-in name, share snapshot, circle note, coop invite, club notify / broadcast / cancel | CT | stamp: MCA | Code |
| Player merchant review | human pre-review only, no CT | own-bucket URL only | Code (not public before approval) |
| City-node placement | human pre-review only | raw URL | Code |

Unprotected entries that only privileged roles reach, or that the client does not send:
- Club theme end `reason` via API: no CT. It reaches registrants in cancellation messages. `ClubTopicSettingServiceImpl.java:107`.
- Official event pause texts: whitelisted organisers only. `TeamEventCommandService.java:156-202`.
- Club governance ban reason: admins and the target only.
- Merchant aftercare reply: whether players see it is not determined.
- Merchant SKU name and hangout address: no mini-program caller.

Treat these as follow-ups, not blockers, unless the owner disagrees.

## 3. Not covered

- **Community posts outside clubs** (`/api/v1/community/posts`, the roam composer) and their 「帖文」 replies on `pages/square/detail`, through the screens. The composer for non-club posts lives on the roam page, which is still being built. The 发现 feed showed no community posts to test.
- **Activity publishing** through the screens: fixture roles can't create activities (B2 stays unverified).
- **Theme 评价 through the screen:** the entry did not open in the rig. Proven over HTTP only.
- **The full theme editor (`pages/publish/fabu`)** through the screens: the theme in B1 and in the busy hold was created over HTTP with the same DTO.
- **WeChat async image callbacks** (`media_check_async` → risky → takedown): the rig has no callback.
- **Real-phone behaviour,** WeChat itself, subscribe messages, keyboard and safe areas.
- **Coupon hold** (`status=5`), official events and merchant registration.
- **Rescan** of comments, themes, activities, and merchant and club profile text (only club posts were walked; l8 covered comments and community posts over HTTP).

STATUS: PARTIAL - first pass; community posts outside clubs, activity tickets, theme 评价 and the theme editor were not walked on screen (rig limits, listed above); B1 confirmed, B2 needs a rig identity that can create activities
