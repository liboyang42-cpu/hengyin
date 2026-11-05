# j5-posts-moderation: second pass

- **Commit tested:** `573c463b3b457b77544e63be4271a07d5d565e09`, exactly. Branch `claude/check-1105-j5-posts-moderation`, cut from it.
- **First pass:** `claude/check-1104-j5-posts-moderation`, tested `8b78f5ca`.
- **Rig:** `claude/cloud-walk-20261101-rig` plus the club lane's `harness-patches.diff`, with `up.sh` pinned to the commit above, `LANG=C.UTF-8`, and `mirror.gcr.io` as the Docker mirror. It runs the real jar, MySQL 8.0.45, Redis 7.4 and the real mini-program code in a headless browser.
- **This is not a phone.** Shots are 390×844 at 2x, JPEG under 200 KB, in `shots/`. Read-backs are in `evidence/`. Scripts are in `tools/`.

## Rig seams (harness only, no product code changed)

Same seams as the first pass. The sources are in `tools/`.

| Seam | Why | What stays real |
|---|---|---|
| `RigWxOutboundStub` replaces the e2e content-security stub. It overrides only the HTTP call to WeChat `msg_sec_check`; `/tmp/rig-wxsec-mode` sets the answer (pass or busy). | The e2e stub skips the word library. | Word library, normalisation, gate, hold and recheck, errcode handling |
| `RigImgSecStub` + `imgsec_server.py`: the upload-time `img_sec_check` goes to a local stub. The OSS put is written locally. Fake `CHENGYIN_OSS_*` properties are passed via `RIG_EXTRA_ARGS`. | No WeChat and no OSS. | Known-bad image library (SHA-256 and dHash), media guard |
| `wx.chooseLocation` returns a POI name we choose (`a11_activity.mjs`). | Stands in for the WeChat map picker. A real POI name is chosen on the map, not typed. | The page's `choosePoiForNode` and everything after it |
| `seed_activity_category.py` adds two made-up `sys_category type=2` rows. | The fixture has no activity types, so 活动类型 showed 「暂无分类」 and no activity could be published (HARNESS). | — |
| `apply_jobs.py` applies the two `INSERT`s of `migration_20261104_content_safety_jobs_post_deploy.sql`. | The rig schema leaves POST_DEPLOY files out, so the recheck and rescan jobs did not exist. | The real Quartz jobs run via `/monitor/job/run` |
| On the theme page only, the card, star and 发布评价 taps are sent as a DOM click on the element itself (`a16_review.mjs`). | In the rig, a mouse tap there lands on the inner text and the `bindtap` never fires (the first pass saw the same). | `voteClick`, `changePic`, `submitComment` and `/api/comment/add` |
| `next_attempt_at` is moved to NOW() before a recheck run. | So we don't wait 5 minutes. | The recheck job itself |

- All words are made up:
  - blocked: `zqvxlm`;
  - allow-listed: `zqvxlmpark`;
  - added to the library later: `wqzrtk` and `kxjwvb`;
  - rescan word: `qwpkzt`.
- The "known-bad" image is a generated gradient. Only its fingerprint is registered, through the real admin endpoint.
- **HARNESS:**
  - Uploaded images don't render (fake OSS host).
  - Times show UTC.
  - Theme pages have black heroes (the covers don't load).

## 1. Steps

| # | Step (real screen unless marked) | Result | Shot | Read-back |
|---|---|---|---|---|
| 03 | Club lead 9003, theme flow node game (`pages/publish/temp?from=fabu`), 文字问答: library word in name, rule and question → 保存并绑定 | **PASS**: refused, 「玩法没有发布成功 / 内容包含违规信息，请修改后重试」 | `03-nodegame-word-c-after-save` | `/api/template/draft` 500, receipt REJECTED; no row |
| 03 | Same, split word `z.q-v x*l_m` | **PASS**: refused | `03-nodegame-split-c-after-save` | no row |
| 03 | Same, allow-listed phrase | **PASS**: saved | `03-nodegame-allow-c-after-save` | `cms_member_template` 6005 |
| 07 | Node game saved with a word not yet in the library (`wqzrtk`); word then imported; theme bound to it is published (HTTP, the editor's DTO) | **PASS**: refused, 「主题内容包含违规信息…（字段：节点玩法「桥边问答 wqzrtk」.title）」. Saving the theme as a draft is refused too. | `07-nodegame-late-c-after-save` | `evidence/07-theme-bound-node-game.txt` |
| 08 | Theme draft 990014 saved with node game 6007, word `kxjwvb` imported afterwards. Draft published (a) over `/api/topic/publish-draft` and (b) from the editor (fabu → 预览 → 完成) | **PASS**: both refused with the field named. Screen shows the reason. See N5 for its wording. | `08-theme-draft-word-1-after-publish` | `evidence/07-theme-bound-node-game.txt` |
| 04 | Merchant 9002: 营销 → 发活动 → three-step form → 发布. Venue (map POI) named with the word | **PASS**: refused, 「活动尚未发布 / 活动内容包含违规信息，请修改后重试」 | `04-act-venue-1..5` | `evidence/04-activity.txt`; no `cms_activity` row |
| 04 | Same, ticket name with the word | **PASS**: refused | `04-act-ticket-3-ticket`, `-4-after` | no row |
| 04 | Same, ticket name with the word in spaced capitals `Z Q V X L M` | **PASS**: refused | `04-act-ticket-split-4-after` | no row |
| 04 | Same, allow phrase in venue and ticket | **PASS**: published, lands on 我的项目 | `04-act-allow-3-ticket`, `-4-after` | `cms_activity` 10 status 1 |
| 05 | Merchant edits live activity 10: ticket name → word; venue → word | **PASS**: both refused, activity unchanged | `05-actedit-ticket-2-after`, `05-actedit-venue-2-after` | 10 still 「zqvxlmpark 咖啡角」 |
| 06 | Merchant publishes with WeChat busy | **PASS**: 「活动发布成功，请等待审核」, held (status 0). Recheck queued with venue + ticket text. Real Quartz recheck with WeChat back → status 1 | `06-act-busy-4-after` | `evidence/04-activity.txt` |
| 09 | Held-theme receipt, create path (HTTP, club lead 9008, WeChat busy) | **PASS**: 「已提交审核」, `published:false`, `reviewState:PENDING` | — | `evidence/09-held-create-receipt.txt` |
| 09 | Held-theme receipt from the editor: draft → 预览 → 完成 with WeChat busy | **NOT VERIFIED on screen.** The server answers 「已提交审核」 / `published:false` to the editor's own request. Then the editor's save-verify step stops with 「云端保存的主封面与本机不一致」. That is HARNESS: `server-draft.js:236-240` only recognises the production OSS host. See also N3. | `09-held-receipt-1-sheet` | `evidence/09-editor-held-receipt.txt` |
| 10 | Held theme 990022: author opens it | **PASS** (first-pass H2): 审核中 chip next to the CTA on the detail screen. See N10 for layout. | `10-held-theme-author-2-detail` | `ownerStateText`=审核中 |
| 10 | Held theme: another player opens the link | **PASS** (first-pass H1): 「主题活动不存在 · 返回」, no blank page | `10-held-theme-player` | API 「主题活动不存在」 |
| 11 | Community post outside clubs: the roam finish → 分享 → 发到广场 composer | **NOT VERIFIED on screen**: after 同意并开启 the roam stays at 「开启定位 · 开始点亮」 / 00:00 in the rig, so no session to share. Over HTTP with the client's exact call sequence (`community-submit-progress.js`): word refused, split refused, allow published, WeChat busy → CHECKING (hidden) | — | `evidence/11-community-http.txt` |
| 12 | 广场 post detail: player 9009 comments word / split / allow | **PASS**: refused / refused / posted | `12-sqreply-*` | `evidence/12-sqreply-readback.txt` (only the allow row) |
| 13 | 广场 post detail: author edits own post to include the word (··· → 编辑 → 保存 → 同意发布) | **FAIL**: not refused. The post goes to CHECKING and waits for a person. See N1. | `13-sqedit-word-a2-guideline`, `-b-after` | `evidence/13-sqedit-readback.txt` |
| 13 | Same, a clean edit | **FAIL**: the post also goes offline to a human queue. See N1. | `13-sqedit-clean-b-after` | post 7 CHECKING, audit_task 13 |
| 14 | Theme 评价 on screen (theme 990015 → 评价 card → 评价路线 → 5 星 → 发布评价): word / full-width `ｚｑｖｘｌｍ` / allow | **PASS**: toast 「内容包含违规信息，请修改后重试」 ×2; allow posted, card shows 5 · 基于1条评论. The taps needed the dispatched-click seam. | `14-review-*-b0-toast`, `14-review-allow-b-after` | `evidence/14-review-readback.txt` (`ums_comment` 60 only) |
| 15 | Published club post; word imported; real `sensitiveContentRescanTask` in report mode, then remove mode | **PASS**: reported, then removed (`del_flag=2`). Notice says 「原因：文字未通过内容安全检测」 (first-pass H3). Switch set back to false. | `15-rescan-2-notice` | `evidence/15-rescan.txt` |
| 16 | 我的 → 帖文 for club lead 9003, who has live club post 1 | **FAIL** (first-pass H4 still): 「还没有帖文」. See N2. | `16-mine-posts-9003` | `club_post` 1 live |
| 16 | 我的 → 帖文 for 9004 (community posts) | **PASS**: lists own posts with 审核中 / 草稿 | `16-mine-posts-9004` | — |
| 17 | Profile edit page | **PASS** (first-pass H5): 昵称 「玩家小城」 | `17-profile-page` | — |
| 18 | `pages/club/edit?id=7001` as the lead | **FAIL** (first-pass H6 still): 「暂时不能查看 / 当前账号无权进入该俱乐部」 | `18-clubedit-deeplink` | — |
| 19 | Club detail 概览 | **PASS** (first-pass L1): 俱乐部类型 「—」, no longer the city | `19-club-detail` | — |
| 20 | 9004 opens lead 9003's homepage | **FAIL** (first-pass L7 still): light page, merchant name 「主理人策划工作室」, 「还没有帖文」 despite a live club post | `20-userinfo-9003` | — |
| 21 | Club post composer, word | **PASS**: refused (toast). Composer still clipped (L10). | `21-clubpost-1b-after` | no row |
| 22 | Private message word / allow | **PASS**: refused with red mark / delivered. Order still wrong (L2). | `22-im-5-thread` | `im_message` 4 |
| 23 | Player picks known-bad image, then a near copy, in a club post | **PASS**: both refused at upload, 「图片未通过内容安全检查，请更换后重试」 | `23-img-1a-image`, `23-img-2a-image` | 2 IMAGE/BLOCK hit rows |
| — | Hit log after all of the above | **FAIL** (LOW): refusals on receipt-wrapped paths leave no hit row. See N4. | — | `evidence/16-hit-log.txt` |

## 2. First-pass findings: really gone?

| First pass | Now | Proof |
|---|---|---|
| **B1** Theme-bound node game text public with no machine check | **Gone.** Checked at three points, each walked or proven: the node-game save (screen, step 03), theme create/edit (HTTP, step 07) and publish-draft (HTTP and editor screen, step 08). A word added to the library after the game was saved is still caught when the theme is published. 「将随主题一起审核」 is now true. | steps 03, 07, 08 |
| **B2** Activity ticket and venue text unchecked | **Gone**, walked on screen as a merchant: venue, ticket, split ticket, edit of ticket and venue, and a busy hold with release | steps 04–06 |
| H1 Held theme by link is a black page | **Gone** | `10-held-theme-player` |
| H2 Author not told the theme is held | **Gone for the theme page and the create receipt.** The editor's result sheet is NOT VERIFIED (HARNESS, step 09). | `10-held-theme-author-2-detail`, `evidence/09-*` |
| H3 Wrong reason in the rescan takedown | **Gone** | `15-rescan-2-notice` |
| **H4** 我的 → 帖文 misses club posts | **Not gone.** See N2. The fix is there but its request is rejected by the backend. | `16-mine-posts-9003` |
| H5 Profile 昵称 「未填写」 | **Gone** | `17-profile-page` |
| **H6** Club edit deep link dead end | **Not gone** | `18-clubedit-deeplink` |
| L1 Club type shows the city | **Gone** (shows 「—」) | `19-club-detail` |
| L2 Chat order | **Not gone** (delivered message above the earlier failed one) | `22-im-5-thread` |
| L3 WeChat busy refuses instead of holding (posts, comments, chat, profiles) | Unchanged, as built. Owner call. | sanity in `tools/p_sanity.py` |
| L4 Errors shown twice or three times, as blocks | Profile: partly fixed per commit `6ab06d67`, not re-walked. Merchant 品牌故事: not re-walked. The same block pattern is on the new screens; see N6, N7. | — |
| L5 系统通知 cards don't name the content | **Partly.** The takedown names the post. The recheck 「审核通过」 card still reads 「你提交的内容已通过审核」 with no theme name, and the actions are still 「查看生成时记录 / 查看当前原单」. | `15-rescan-2-notice` |
| L6 Conversation titled 「系统通知」 | Unchanged (may be fixture data) | `15-rescan-1-inbox` |
| L7 Other user's homepage light, merchant name | **Not gone** | `20-userinfo-9003` |
| L8 「帖子」 | Club tab now 「帖文」. The takedown notice says 「俱乐部动态」. | `15-rescan-2-notice` |
| L9 Taglines | Empty-state subtitles are gone, and the duplicate 评价 line is gone. New ones are listed under N13. | — |
| L10 Composer clipped at the left edge | **Not gone**: 「取消」 reads 「収消」, avatars are cut at the left and 「发布」 is cut at the right | `21-clubpost-1b-after` |
| L11 Template editor layout bleeds | Not re-checked | — |

## 3. Findings (new or still open)

### BLOCKER

None found. Every entry I could reach stops the library word before it is public. The one gap (N1) hides the content rather than showing it.

### HIGH

**N1. Editing a 广场 post skips the machine check and takes the post offline until a person looks at it.**
- **What happens:**
  - Creating a post refuses a library word: 「内容未通过安全检查」.
  - Editing a published post does not refuse it. The edit is saved, the post goes to `CHECKING`, and a human `EDIT_REVIEW` audit task is opened.
  - A clean edit ("补一句：风很大") does exactly the same.
  - Other players lose the post (404 「帖文不存在或不可见」), along with its likes and comments.
  - The author's detail page shows the edited text as if it were live, with no 审核中. Only 我的 → 帖文 shows 审核中.
  - The owner's rule is automatic detection, not people.
- **Repro:**
  1. As 9004, open `pages/square/detail/index?id=<own published post>`.
  2. Tap ··· → 编辑 and type 「改成 zqvxlm 了」.
  3. Tap 保存 → 同意发布.
- **Code:** `CommunityPostServiceImpl.edit` (`chengyinhub-system/.../CommunityPostServiceImpl.java:222-254`) never calls `safetyGate`. The publish path at `:275-302` does.
- **Shots:** `13-sqedit-word-b-after`, `13-sqedit-clean-b-after`. Read-back: `evidence/13-sqedit-readback.txt`.

**N2. First-pass H4 is not fixed: club posts never load on 我的 → 帖文 or on anyone's homepage 帖文 tab.**
- **What happens:** the profile component sends `/api/club/post/feed` as a form-encoded POST. The backend takes `@RequestBody` JSON and answers 「Content-Type 'application/x-www-form-urlencoded;charset=UTF-8' is not supported」. The tab then shows 「还没有帖文」.
- **Code:**
  - Client: `subpackageMember/components/cy/profile/index.js:77-86` (`req`, no header) and `:2170`, falling through `utils/community-post-api.js:219` to `app.sendRequest`, whose POST default is form-encoded (`utils/transport/request-client.js:118-121`).
  - Server: `ApiClubController.java:1972-1973`.
  - `pages/talent/list/index.js:166-168` calls the same endpoint correctly with a JSON header.
- **Not rig:** this is the product's own request client.
- **Shots:** `16-mine-posts-9003`, `20-userinfo-9003`.

**H6 (carried).** Club edit deep link shows 「当前账号无权进入该俱乐部」 to the lead, as before. Owner p3/j2. Shot: `18-clubedit-deeplink`.

### LOW

- **N3. A theme draft saved while WeChat is busy is answered 「已提交审核」 and put in the review hold.**
  - **What happens:** the editor's save step sends `saveDraft:1`, and the server holds the draft (`check_status=2`, recheck queued). When the author later publishes with WeChat healthy, the theme still answers 「已提交审核」 and waits for the next recheck run, up to 5 minutes. It is not published at once.
  - Nothing goes public early: the recheck answers `NO_OPEN_TASK` while it is still a draft.
  - **Code:** the update path holds and words the receipt with no `saveDraft` guard (`ApiTopicController.java:825-841`). The create path checks `!saveDraft` before it marks the receipt held (`:589`).
  - **Evidence:** `evidence/09-editor-held-receipt.txt`.
- **N4. Refusals on write-receipt paths roll back their own hit log row.**
  - **What happens:** `AuthorWriteReceiptService.execute` (`:51`) marks the transaction rollback-only on a refusal, and `recordHit` runs inside it. Blocked activity publish and edit (5 refusals), node-game drafts and community publish and replies leave no `content_sensitive_hit` row. Only TOPIC and plain `checkText` callers are counted.
  - **Effect:** admin hit statistics undercount.
  - **Evidence:** `evidence/16-hit-log.txt`.
- **N5. Theme editor refusal shows a raw field key, and a link overlaps the status bar.**
  - The refusal reads 「…（字段：节点玩法「桥边问答 kxjwvb」.title）」. `.title` is an internal key.
  - On the same screen the 「为什么会这样 ↗」 link sits on top of the 9:41 status bar.
  - The orange block's 「重试」 resends the same blocked text.
  - Shot: `08-theme-draft-word-1-after-publish`.
- **N6. Activity refusal wording and layout.**
  - Editing a **live** activity that is refused shows 「活动尚未发布」, as if it had been unpublished.
  - The block is blue info, not red text under the field, and doesn't name the field. A venue name comes from the map, so the merchant can't fix it by typing and needs to be told to pick another place.
  - The 发布前摘要 / 修改摘要 panel covers the 「添加票单」 button.
  - Shots: `04-act-ticket-4-after`, `05-actedit-ticket-2-after`.
- **N7. Node-game editor:** a refused 保存并绑定 says 「玩法没有发布成功」, but nothing was being published. Its 「重试」 resends the blocked text. Shot: `03-nodegame-word-c-after-save`.
- **N8. 广场 detail times read 「刚刚前」** (just now + ago), for both post and comment. Shot: `12-sqreply-word-b-after`.
- **N9. After a merchant publishes an activity, the app lands on 我的项目 → 主题 「还没有主题 · 去发布」**, not on the 活动 tab where the new activity is. Shot: `04-act-allow-4-after`.
- **N10. The author's 审核中 chip wraps to two lines** (「审核 / 中」) next to the CTA. Shot: `10-held-theme-author-2-detail`.
- **N11. A merchant opening `pages/publish/activity/index` without `scope=MERCHANT` gets a blank page** with only the title. No merchant entry links there without scope (营销 and 装修 pass it), so this is only a direct-link case. Owner p2.
- **N12. Code-read, not walked:** an activity's bound 玩法 (选择模板) is not part of `activitySafetyText` (`ApiActivityController`). The theme path re-checks bound games at publish; the activity path relies on the game's own save-time check. A word added to the library later is caught only by rescan.
- **N13. Taglines and helper lines** (owner rule: cut them and fix the layout):
  - Activity form: 「用于地图与到场导航，拒绝不影响填写」 under 活动地点. The location permission prompt already says this.
  - Activity edit: orange 「本次保存只更新上面这些内容，不会改变活动的公开范围。」 in 修改摘要.
  - Theme 评价: 「评论积分须符合参与及首评条件，以实际积分记录为准」 under the card. This may be a points-rule disclosure; owner to mark it as compliance or cut it.
  - Chat footer: 「系统通知不支持回复」. Hiding the input box would say the same thing.
- **Carried LOW:** L2, L5 (partly), L6, L7, L8 (notice wording) and L10 are as in the table above.

### HARNESS (not product bugs)

- Fixture has no activity categories (type 2); seeded.
- POST_DEPLOY job rows are missing from the rig schema; applied.
- A disabled `<input>` swallows the tap on 选择活动地点 in the rig; a tap on the container edge works.
- Theme-page taps don't reach `bindtap`; dispatched clicks were used.
- The roam doesn't start tracking.
- The editor's cover-identity check only knows the production OSS host.
- An HTTP-made published theme can't be opened by the editor: 「请更新小程序后编辑该主题」, because it has no version binding.
- Images don't render; times show UTC.

## 4. Every user-content entry and the protection it reaches (rebuilt at `573c463b`)

**How each entry was proven:**
- Walked = through the screens in this pass.
- HTTP = proven over HTTP in this pass.
- 1st = walked in the first pass and unchanged in code since.
- Code = traced in code only.

**Protections:**
- CT = `checkText`: word library + WeChat; fails closed when WeChat is busy.
- GATE = `ContentSafetyGate`: library + WeChat, with hold and recheck when WeChat is busy.
- UP = upload check: known-bad image library + WeChat `img_sec_check`.
- MRG = own-media guard.
- MCA = async media check.
- RES = published-text rescan.

| Entry | Text | Images | Evidence |
|---|---|---|---|
| Club post / announcement (create, update) | CT + RES | UP + MRG + MCA | Walked (word, step 21; images, step 23; rescan, step 15) |
| Club post comment | CT | — | 1st |
| Private message (`/api/im/send`) | CT | MCA (hidden until checked) | Walked (step 22) |
| Profile nickname, intro, case, website, wechat | CT | MRG + MCA | 1st |
| Club profile (create, update, welcome, join message, badge) | CT | MRG + MCA | 1st (intro); others Code |
| Merchant shop profile, decor, coop, AI NPC, perks, review reply, CRM broadcast | CT | MRG + MCA | 1st (品牌故事); others Code |
| Theme create, update, publish-draft (name, description, chapters, nodes, story, runtime, tickets, medal) | GATE (hold → recheck) | MCA + publication guard | HTTP + screens (held receipt, author 审核中, player link) |
| **Theme-bound node game (`/api/template/draft`)** | **CT at save, and GATE again at theme create, update and publish-draft** | publication guard | **Walked + HTTP (steps 03, 07, 08). First-pass B1 gone.** |
| Standalone template publish, city-node template | CT + journey texts | MCA | Code |
| **Activity publish and update (name, description, venue name and address, ticket name, description, meeting point, refund rule)** | **GATE (hold → recheck)** | MRG + MCA | **Walked as a merchant (steps 04–06). First-pass B2 gone.** Bound 玩法 is not re-checked here (N12). |
| Theme / activity / POI 评价 (`/api/comment/add`) | CT | MRG + MCA | Walked (step 14, theme); HTTP in the first pass for the image |
| Community post create → publish (roam 发到广场) | GATE (pre-review; busy → CHECKING) | upload receipt + hold + MCA | HTTP with the client's call sequence (step 11). Screen NOT VERIFIED (roam won't start in rig). |
| **Community post edit** | **none: straight to a human EDIT_REVIEW hold** | hold | **Walked (step 13): N1, HIGH** |
| Community reply (`/posts/{id}/comments`) | GATE | — | Walked (step 12) |
| Coupon | GATE | — | Code |
| Team name / apply, stamps, object card, roam check-in name, share snapshot, circle note, coop invite, club notify / broadcast / cancel | CT | stamp: MCA | Code |
| Player merchant review | human pre-review only | own-bucket URL only | Code (not public before approval) |
| City-node placement | human pre-review only | raw URL | Code |
| New since the first pass (`/api/activity/publish_precheck`, `/operators/invite/preview`, `/trail/consent`, `/trail/sync`) | carry no user text | — | Code |

No entry reaches no protection while being public. Community post edit (N1) reaches none, but the result is hidden until a person approves it, so it is HIGH, not BLOCKER.

**Unchanged follow-ups** (privileged roles only, or no client sends them; as in the first pass):
- Club theme end `reason`: no CT. It reaches registrants (`ClubTopicSettingServiceImpl.java:107`, via `ApiClubTopicSettingController.java:61`).
- Official event pause texts: no CT (`TeamEventCommandService`).
- Club governance ban reason.
- Merchant aftercare reply.
- Merchant SKU name and hangout address.

## 5. Not covered

- **Roam → 发到广场 composer on screen:** roam tracking doesn't start in the rig. The same call sequence was proven over HTTP.
- **Held-theme result sheet in the editor:** HARNESS (cover identity check). The server receipt was read from the editor's own request.
- **A brand-new theme published end to end through the full editor:** only draft → 预览 → 完成 was walked.
- **Merchant theme publishing** (发主题 with `scope=MERCHANT`): same code path, not walked.
- **WeChat async image callbacks:** no callback in the rig.
- **Re-walks not done:** profile and merchant 品牌故事 error display (L4), template editor layout (L11), club welcome sheet.
- **Real-phone behaviour:** taps, keyboard, safe areas, map POI names, subscribe messages.

STATUS: PARTIAL - roam 发到广场 composer and the editor's held-receipt sheet not reachable in the rig (proven over HTTP / from the editor's own request instead); no BLOCKER found; N1 (community post edit skips the machine check) and N2 (H4 still broken) are HIGH
