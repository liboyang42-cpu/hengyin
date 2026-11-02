# Player walk 2026-11-01: report

Lane: player. Candidate: `master` a1d30fb on the rig from `claude/cloud-walk-20261101-rig` (RIG-STATUS READY). I walked as one person, first as the author of two themes and then as a player. The player is a first-timer out with a friend on a Saturday in Shanghai, outdoors, on a phone. Screenshots are in `shots/` (390×844, JPEG q70). Evidence labels follow the BRIEF.

## 1. Verdict in five lines

1. **City orienteering (城市定向):** a free club-led session can be played end to end, story → 9 stations → finale (VERIFIED-UI). Two of the nine stations need a workaround: one has an unbeatable answer (P-02) and the photo station is a harness gap. The author's ending (尾声) never plays (P-04), and a **paid** city theme can never go on sale (P-01).
2. **Free exploration (自由探索):** pay ¥69 through the real callback → scan the shop code → play the shop game → merchant redeems (VERIFIED-UI/API). After the first redemption the order says 「已完成」 and the ticket disappears from 票夹 (「还没有票」). The player has no way back to the other three shops (P-03).
3. **Money and trust:** checkout warns 「本单不可退」, the order page then says 「可免费取消至 11月3日 23:59」, and cancelling really refunds ¥69 (P-07). On a club-owned paid theme the player is told 「缺少报价签名」 instead of the real reason (P-05).
4. **Where a first-timer gives up:**
   - at the gather screen: 「我到了」 always answers 「集合点未配置，请联系领队」 (P-06);
   - at the second station, where every correct answer is rejected (P-02);
   - in free exploration, after the first shop, when the ticket vanishes (P-03).
5. **What is good:**
   - The story stream, chapter title cards, sort, compass, note and option games read and feel well.
   - Clear refusals: 「还没到这一站附近，到 50 米内再作答」 and 「本单不可退」.
   - Paid → callback → redemption bookkeeping is correct.
   - Unpublishing a theme does not strand a ticket holder.

## 2. The journey, step by step

### A0. Authoring as a real editor (club leader 9003, club 7001 「城瘾跑团」)

| Step | What I did and saw | Shot |
|---|---|---|
| Entry | 发布 tab → 「＋」 → 城市定向 / 自由探索 / 互动 / 返回 → 「快速配置 / 专业手动」. Before that, 「开始创建」 on 「把你玩过的一段路 做成别人也能走的玩法」 lands in **编辑玩法模板**, a single-game editor, not a theme. That is a dead end for someone who wants to make a route. | a01, a02, a06, a07 |
| Theme details | The 「主题详情」 sheet has: 主题描述 (really the 20-character subtitle, 「0/20」), 主题类别, 竖版封面 (required), 横版封面, 画面风格, and 剧情详情 (the long story). The title is typed at the top of the page. Category chips are clear. | a09, a10, a11, a12 |
| Cover upload | 「上传 3:4」 → crop page → 「选取」 → 「安全检查服务异常，请稍后重试」. The WeChat image check is unreachable in the rig (HARNESS). I placed fixture covers in the upload directory and set them into the editor (see §5). | a13, a14 |
| Chapter and story flow | 「＋ 创建章节」 → 「第1章 · 故事流」 with a ＋ that offers 文字 / 图片 / 音频 / 地点. A text block works. The chapter name field is pre-filled with 「第1章」 as real text, not a placeholder, so typing produced 「第1章第一章 · 江海之门」. | a17, a18, a20 |
| Station (节点) | 「新建节点」: 名称, then 地点 「选择节点」 (labelled "pick a node" while I am creating one), 玩法 toggle, 到达与引导, 照片 0/9. The game picker lists 9 types: 相册 / 文字问答 / 选项问答 / 拍照打卡 / 分支剧情 / 排序 / 连线 / 分类 / 猜数字. 「配置」 opens the full 编辑玩法模板 page. | a21, a23, a24 |
| Game config | The 选项问答 config page has 题干, a lead, XP 0–200, and options with 「选中后的反馈」. **It has no hint field.** Hints exist only on the legacy 文字/选项 forms. Typing into this page dropped characters in the rig (HARNESS), so I created the 9 games through the editor's own endpoint (`/api/template/draft`, same fields). | a24, a25 |
| Content | 「外滩钟声：失踪的报时人」: 3 chapters + 尾声 ending chapter, 9 Bund/Nanjing Rd stations, 9 games (legacy option QA with 2 hints and reveal; legacy text QA; photo; estimate; sort; compass; note; option QA with per-option feedback; final typed answer). Chapters 2–4 were placed into the editor's state and serialized by the editor's own `_buildSubmitPayload`, then POSTed to `/api/topic/v2/create`. | a26, a28 |
| Publish | The 人民广场 station at 31.2304,121.4737 was rejected: 「节点仍是默认上海坐标，请重新在地图确认:79」 (P-16). After moving it 100 m, the response was 「主题发布成功」 with `reviewState: PENDING` and 「已提交立项审核，审核与定价完成前尚未开售」. That reads as both published and not published. | — |
| Ticket page | Page 2 「票务设置」: 档期, 支持组队, ticket 「周末带队票 ¥69 · 城市定向 · 30 张」 with 集合时间 / 集合地点 / 退款规则, and 自玩票. Its header scrolls under the status bar and back button. | a29, a31, a32 |
| Selling (paid) | Admin approved both audit tasks. Lifecycle went to 2 = PRICING. The pricing page shows 「确认终价」 with ¥0 floor and ¥0 price even after the author confirmed ¥69 (a40). 「确认终价并提交开售审核」 → 「终价已确认，请等待开售审核」. The sale review can never be submitted for a city theme (P-01). | a40 |
| Workaround an author would take | I republished as a free edition 「外滩钟声：报时人的最后一夜」 (¥0 ticket), and later as a 「雨天版」 without the photo station. Doing this hit 「同时上架的主题已达上限（2）」, so I unpublished the stuck paid edition. As the other club leader, 「主题发布失败，请稍后重试」 hid the real cause 「不能引用他人的模板:6005」 (P-15). | — |
| Free exploration | Helper agent, same rig, real merchant APIs: 「弄堂咖啡地图 · 秋日版」, 4 chapters. Published first (lifecycle 1), **then** 4 merchants applied, were approved, enrolled perks (桂花拿铁 / 书签 + 九折 / 小花束 / 栗子面包), submitted their own shop node and game, and were approved. Execution club 7002, sale-close to SELLING. A 5th merchant joining after SELLING is refused: 「主题已锁价，不能再申请或审核商家承接」. | — |

### A. Discovery as a guest

- **First open** (p01): 「Hi, 旅人!」 and a hero carousel of 「成为俱乐部 / 成为商家 / 和朋友报名活动」. The first thing a player sees is B2B recruitment. The banner GIFs come from OSS and are black here (HARNESS). The privacy gate is fine.
  - For the logged-in author, the city-location dialog was stacked on top of the privacy gate (a03).
- **Home** (p03, p04):
  - 「附近活动」 shows my theme with a **stock photo of a woman, 「Marina」, 「Follow」, 「WINDS OF DESTINY」, 「86.54k」** and a red 「LIVE」. That card is the editor's auto-created two-month window.
  - The theme card shows 「价格 ¥69 起 · 时间 2026年11月2日 周一 上午12:00 · 地点 …外滩源 33 号门前」. A guest reads this as "starts at midnight, ¥69", while the theme cannot actually be bought (P-01, P-17, P-18).
- **Detail** (p05–p08):
  - The hero shows a pin illustration. Covers are withheld because they never passed the harness-blocked image check.
  - 「查看详情与报名」 did nothing visible in the rig.
  - Scrolling shows: 「定价中 · 未开售」; 开放时间 11.2–12.31; 总里程 3.95 公里; 参与商家 0 家; 已参加 0 人.
  - 「节点介绍」 shows **game titles** (「旧邮筒上的年份」) with 「0分钟」 instead of station names and the 15 min the author set.
  - There is no duration and no price on the detail, except what the author wrote into the subtitle.
  - The review area repeats the points disclaimer twice (「…以实际积分记录为准」).
- **Would I understand it?** What it is: yes, the story blurb is good. How long it takes: only because I wrote 「约2.5小时」 in the subtitle. Cost: the home card says ¥69 but the detail says 「定价中」. Where to go: the meeting point is only on the home card.

### B. Commit

**Free city session** (player 9009, VERIFIED-UI):

- 「立即报名」 (the dock CTA) → 「选择场次」 with two identical-looking 「周一 11.2」 entries: 「13:35 · …」 (the club session) and 「00:00 · …」 (the auto two-month window) (p12b).
- Picking 13:35 → 「来自俱乐部的活动，加入后查看 · 入会后可购票 · 去加入俱乐部」 (p13).
- Club page → 「加入」 (a small pill floating over the leaderboard score) → welcome sheet 「欢迎加入「城瘾跑团」」 (p15, p15b).
- Back → the picker is closed, so the player picks again → ticket 「首发免费票 · 集合前 24 小时可全额退;之后或未到场不退 · ¥0」 (p17).
- 结算: 参与人信息 typed (林小舟 / dummy 138…). The consent line shows no checkbox, and after ticking the button still reads 「请先勾选信息共享同意」 (p21, p22; part HARNESS).
- 「免费报名成功 · 票夹已生成，可继续探索或现场核验」 (p23).
- The order page shows 「等待到店核销」, 「商家消息：接收「主理人策划工作室」的活动消息」 and 「主办方：俱乐部主理人」 (p24). This is merchant language on a club walk, and a studio name the player has never seen.

**Paid free-exploration session** (player 9004, VERIFIED-UI + API):

- The detail is good: 「场次 · 秋日漫步场 · 单人票 · 11-02 14:15–18:15 · 集合点 · 武康大楼门口 · 退改 · 开场前24小时可退 · ¥69 · 余席 —」 and 「每人限购 1 张 · 场次内自由顺序，不带队」 (q04).
- But: 「参与商家 0 家」; one 节点介绍 card; the picker lists 11.3 before 11.2 (q05); and the session is really 14:05.
- Club gate → 「执行俱乐部」, a different club from the 「城瘾跑团」 brand on the detail (q07).
- First pay attempt: 「这次报名还没完成 · 缺少报价签名，请重新进入报名页」 under a 「确认价格并付款」 button (q10). The quote had actually said 「该产品暂不支持在线支付(平台正在办理支付资质,预计 2 个月内开放);如需报名请联系主办方」. That gate is an **OWNER DECISION** (platform-owned only); the hidden reason is P-05.
- After the admin set the content slot to PLATFORM (as `chain_player.py` does): 「本单不可退 · 该场次已过「集合前 24 小时」退款截止时间，支付后不可退款。确认继续下单？」 (q11) → 继续下单.
- The rig pay seam → real `/api/registration/callback` 204, `payment_status` 2, `RIG-TXN-75`.
- Order: 「票夹已生成，请按场次时间到集合点开场」 with meeting point and 「查看」. Good (q12).

**Wait state:**

- 票夹 card: 「场次 · 今天过期」 with no start time and no meeting point. The back face 「生成中…」 is the entrance/redemption code, shown only when you tap the card to flip it (p26, d22).
- 「开始游戏」 20 min early → good intro (「读 跟着故事走 / 玩 到站解开玩法 / 开始游戏 · 建议戴耳机，打开声音」) → gather screen 「活动未开始 · 队伍集合中 0/2 · 我到了 · 等队长开始行程…」 (p27, p28b). No start time or countdown is shown.

**Cancel and refund, second run** (walk-player-1, VERIFIED-UI + API):

- Same 「本单不可退」 warning, paid ¥69.
- The order then says 「退款与客服 · 可免费取消至 11月3日 23:59 · 取消并退款」 (b06) → dialog (b07) → 「报名已取消，现金退款已受理，尚未派发」, `cancellationStatus: CANCELLED`, `cashRefundAmount: 69.00`.
- 订单进度 then shows 「等待支付 · 完成后更新」 (b08). See P-07.
- A second purchase by the same account: 「已达到该主题的购票上限」. This matches the stated 「每人限购 1 张」.

### C. City orienteering, start to finish

Sessions: 12 (free edition) and 16 (雨天版). Leader actions were taken through the real `/api/club/lead/*` endpoints as 9003.

| Moment | What happened | Shot |
|---|---|---|
| Gather | At the meeting point (外滩源), 「我到了」 → 「集合点未配置，请联系领队」. It is the same from 2 km away. The leader started anyway: 「出发!」 | c03 |
| Story | The vertical story stream is calm and readable. 「带领中 · 0/2 人 · 第一章」 stays 0/2 all game. | c04 |
| Station card | 「第 1 站 · 外滩源·旧邮筒 · 黄浦区圆明园路… · 邮筒还在，字条的另一半在门楣上。 · 本站任务 旧邮筒上的年份 · 开始任务 · 在地图中查看路线」. Clear. 「在地图中查看路线」 did nothing in the rig (native map: NOT VERIFIED). | c07 |
| Too far | The game opens from 2 km away. The location request comes only at 提交: 「开启定位」, then 「先开启定位，到这一站 50 米内再作答」, then 「还没到这一站附近，到 50 米内再作答」. No distance is shown (ARRIVE_FIRST is an OWNER DECISION). | c08, c09, c11 |
| Hint | 「看提示（1/2）」 → hint 1 shown, but the label stays 「（1/2）」. 「当前最高 100 解谜分」 drops to 70, then 40, with no warning before tapping. | c12 |
| Wrong ×2 | 「答案不正确」, then 「实在解不出，揭示答案」 appears. Good. | c13, c14 |
| Right | The sheet closes straight back to the story. No 「对了」, no reveal text, no fragment 「钥匙不在锁里，在数字里」. | c15 |
| Station 2 | 「6」, then 「第七根」, then 「7」: all 「答案不正确」. The author entered 「7、七、第七根、第7根」 because the editor says 「可填多个，用、分隔」 (P-02). 「揭示答案」 → 「第 7 根。老周在这里留下了第二张字条。」 (0 分). | c16–c19 |
| Station 3 photo | 「拍下外白渡桥的钢桁架 · 站在桥南岸的台阶上，往北拍 · 还能重拍 3 次」. 「还能重拍」 appears before any shot. The author's requirement sentence is not shown. Photo-processing consent (OWNER DECISION) → upload 503 (HARNESS) → back to the same screen with no visible error. | c22–c24 |
| Menu | 「各站奖励 · 本局读数 章节 3 · 已完成 2 · 还有游戏 7 · 同行者榜 · 扫码到达 到店后扫商家的码 · **开始这一局** · 队伍位置 0/2 · 自动记录足迹 · 到点提醒 · 入场码 · 旅程手记 · 动态效果」. 「开始这一局」 is a hold control, though I have been playing for 10 minutes. | c25 |
| Leader releases ch 2 | The player screen does not move. Only the banner changes to 「第二章」. | c27 |
| Kill and relaunch | The intro shows again, the story replays from the top, scroll lands on station 3. Location consent is asked again. Resume is correct. | c28–c30 |
| Station 3 (雨天版) | Advanced typed answer: 「黄浦江」 → nothing at all happens. 「吴淞江」 → 「答对了 · 继续故事」. Alternatives work on this type. | c34–c36 |
| Chapter 2 card | 「第 2 章 / 共 3 章 · 第二章 · 钟楼背后 · 海关大楼的钟面朝着江。 · 3 坐标 · 轻点跳过」. A lovely beat. Chapter 1's closing line was not shown here. | c37 |
| Station 4 estimate | Only a number wheel and 「米」. The question 「海关大楼钟面的直径大约有几米？」 is missing. Submitting 6 → 「继续故事」 with no reveal 「约 5.4 米…」. The chapter was still leader-locked: the server answered 「本章还没开启,等队长放行」, the player saw nothing and the station card came back. After the leader released, the earlier HIT was applied automatically (good). | c39–c43 |
| Station 5 sort | 「按住一行上下拖，或者先点一行再点另一行换位置」 → 「还没排对 · 挪一挪再交」 → 「排好了 · 这一题过了」. | c44–c46 |
| Station 6 compass | 「点开始后转动手机，把红色箭头转到顶上的白线 · 提示 黄浦江在你的正东方向」 → 「往左转 110°」 → 「你已经对准过这个方向了 · 已对准」. (Needed a faster rig heading seam; see §5.) Footer 「本机成绩：只记探索值与徽章，不发券或积分」. | c47–c49 |
| Station 7 note | Presets 「钟会再响的 / 我们找到你的字条了」 → 「这句已经留在这儿了 · 已留下」. | c52, c53 |
| Station 8 option | Wrong 「回到海关大楼」 → 「钟在那里，但钥匙不在。 · 再想想」. Right → 「你的选择 · 正确答案 · 答对了 · 对，老周最后一次出现，是在人民广场的鸽群里。」 | c54–c56 |
| Station 9 final | 「把一路收集的四个字连起来：钟、声、不、停」 → 「钟声不停」 → 「答对了」. | c57, c58 |
| Finale | A stack of overlays:<br>• 「解锁徽章 … · 通关 · 已记在你的收藏里」<br>• 「本章9处旧址已全部点亮」 (says 本章 for the whole theme)<br>• the footprint card 「9 点亮坐标 · 06:54 用时 · 4.0 km · 100 解谜分 · 个人最佳 100」<br>• 「首达 外滩源·旧邮筒」<br>• 「主题通关 … 获得 1 枚勋章」<br>• 「全部完成 … 继续 / 保存通关卡」<br>Behind them, chapter 1's closing line 「桥上的风很大…去钟楼看看。」 finally appears. **The 尾声 chapter (「你说出那句话的时候，远处海关大楼的钟，走了一格。」) never appears**: `/api/play/journey/ending` → 「本局尚未开始」 (P-04). 「继续」 lands back in 票夹 with 「开始游戏」 still offered. | c59, c62 |

### D. Free exploration, start to finish (player 9004, session 13)

- 「开始游戏」 → a gather screen 「队伍集合中 · 等队长开始行程…」 although the ticket said 「不带队」. 「我到了」 → 「集合点未配置，请联系领队」 (d02, d03). The execution leader 9008 started.
- Card pack 「弄堂咖啡地图 · 秋日版 · 4 章 · 4 家商家」: a holographic pack with no hint that it should be tapped (d04).
- The pack opens into a 0/4 ring and 4 merchant cards with 「营业中 / 未到店」 (d05). There is no distance, no map and no suggested order. The leader banner overlaps the title. Two cards do not show the shop name until scrolled.
- Shop card: chapter story, 「梧桐咖啡 · 武康路店 · 营业中 · 今日 08:30-19:00 · 地址 · 导航 · 到店三步」 and the CTA 「开始互动 获得奖励！」 (d06). **The perk (桂花拿铁) is not named anywhere.**
- The CTA opens a check-in page: 「1 扫描门店码 · 仅记录到店时间 / 2 拍摄现场凭证 / 3 商家核销」 (d08).
- Wrong code (URL): 「无效的打卡码」. Another shop's code: **「扫码节点与本次意图目标不一致」** (d10).
- Correct code → 「已记录到店」 → the merchant's option question opens. Wrong → 「差了好些年哦，问问吧台的店员。 · 再想想」; right → 「对！老板说这棵梧桐比房子还早…」 (d12, d13).
  - The merchant's 3-line rule (「…答完给店员看即可领取桂花拿铁」) is in the response but not on screen.
- After the game: location consent pops up again. The navigation strip points at the shop I just finished: 「0 米 梧桐咖啡 · 05:37 到达」. The ETA is in UTC (likely the rig browser time zone) (d16).
- Redemption from the player's side: nothing tells me what to show. The code is on the back of the 票夹 card (d22).
  - The merchant scanned it: 「核销成功」. A repeat scan: 「该章节已核销，请勿重复提供权益」 (VERIFIED-API).
- Order: 「已完成 · 首次扫码成功，订单已完成」 after one of four shops (d18). Then 「权益明细」 lists chapter names only. 「探店图鉴 第一章 · 探店日样板咖啡 · 11-02T13:38」 shows the merchant's account name and a raw timestamp. The refund text becomes 「已核销订单需人工核实,请联系客服」 (consistent).
- **票夹 → 「还没有票」** (d23). 我的 shows no order (d25). The player cannot reach the other three shops (P-03).
- Through a deep link only (a real player has none): the ring shows 1/4. Out-of-order arrival at shop 4 works: scan → 「一炉栗子面包里有几颗栗子？」 estimate with the question shown → a miss gives no feedback (d27, d28). Shop 1 shows 「已核销」 with no scan button (d29).
- **Merchant who joined after publish:** all four did, and their content shows correctly. **Merchant joining after sale opened:** refused (see A0).

### E. Interruptions

| Case | Result |
|---|---|
| Leave mid-game and come back | Kill and relaunch resumes at the right station. The intro and the full story replay first, and location consent is asked again (c28–c30). VERIFIED-UI. |
| Session not yet started | The 「活动未开始」 banner and gather screen are fine. There is no start time or countdown (p27, p28b). VERIFIED-UI. |
| Out of order | City: linear, and the next station needs the current one. With the photo blocked I could not skip; the leader's release did not help (c31). Free exploration: any order works (d27). |
| Leader gate | The server refuses silently after the player has played the game (c39–c42). |
| Theme unpublished mid-session | The ticket and play still work (e01, e02), which is good. 消息 shows that ticket as 「活动暂不可用」 (f05). |
| Session ended while playing / token expiry / network failure during submit / teammate differences | **NOT VERIFIED** (time). The rig has `E2eFaultInjectionFilter` for the network case. Teams: only one player per team was walked, so 「0/2」 was never exercised with two phones. |

### F. Afterwards

- 我的 (f01): 「Lv.3 城市探索者 · 连续探索：1天」. 我的订单 lists both city sessions as **「待核销」** although one is finished.
- 成就 (f02): 「G 0 · 总积分排名 TOP100% · 累计探索 11家店 · 0.0km · 本周 +3930 EXP · 收集册 勋章 1」. That is 11 shops after a walk with no shops, and 0.0 km after the end card said 4.0 km.
- 消息 (f04, f05): 收件箱 has one 「系统通知」 thread, with cards 「发送时：报名成功」, 「活动暂不可用」, 「封面暂不可用」 and a route card 「3.95 km · 9 个点」. There is nothing about the badge, the finish, the leader's chapter releases or the merchant redemption. 发件箱 is empty. There is no read-all (OWNER DECISION).
- 社区 tab (f03): 「执行俱乐部 4 位成员 · 0 个近期团」, although it runs two sessions.
- 漫游 (f06): 「附近活动」 lists both themes with distances. Good.
- Posts and sharing were **NOT VERIFIED**: no 帖文 was written, and share cards are native.
- Would I come back? The finale is satisfying to look at. The numbers I am left with (11 shops, 0.0 km, TOP100%, 「待核销」 on a finished walk) undercut it. A free-exploration player would not come back after the ticket vanished.

### Both modes compared

- **Same in both, and good:** the gather screen, the ticket wallet and the story-to-game sheet.
- **Named differently:**
  - city calls stations 「站」 / 「坐标」 / 「节点」 in different places (「第 1 站」, 「3 坐标」, 「本章9处旧址」, 「节点 9/9」);
  - free exploration calls them 「探索节点」 / 「商家」 / 「店」.
- **Placed differently:**
  - city has a top-right menu (各站奖励, 本局读数, 入场码, 旅程手记…); free exploration has none, so no backpack, no casebook, no 入场码.
  - City progress is the banner 「已完成 2」; free exploration has a ring 0/4.
- **Leaks across modes:** the city menu offers 「扫码到达 · 到店后扫商家的码」; the free-exploration ticket says 「不带队」 and then shows 「等队长开始行程…」.
- Someone who has played one mode would recognise the wallet and the story stream. They would not find the redemption code or the perk in either mode without help.

## 3. Issues, ranked

Each issue lists the evidence label and any screenshots in brackets after the title.

### S1

**P-01. A paid city-orienteering theme can never go on sale** [VERIFIED-API; author and player UI a40, p07, p10]
- **User-facing problem:** The author confirms the price, sees 「终价已确认，请等待开售审核」, and waits forever. Players see 「定价中 · 未开售」, and the checkout says 「该主题尚未开售，开售审核通过后才可报名」. Meanwhile home shows 「¥69 起」 and LIVE.
- **Reproduction:**
  1. Create a city theme with a paid ticket (V2 create) and approve both audit tasks.
  2. Call `pricing/confirm`.
  3. Admin `PUT organizer-actor` → `SALE_ORGANIZER_ONLY_FREE_EXPLORE`.
  4. `sale-canonical-draft` → `SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR`.
  5. `sale-submit` → 「缺少 canonical15 草稿，拒绝开售提审」.
- **User expected:** after confirming the price, either go on sale after review or be told clearly why not.
- **Likely code location:**
  - `ProductRoleServiceImpl.java:117-118` (organizer slot only for FREE_EXPLORE)
  - `TopicSaleCanonicalBuilder.java:174-183`
  - `TopicCommercialReviewServiceImpl.java:85-94` (commercial city themes require the draft)
- **Smallest change:** let the content or club slot stand in as organizer for productType 1 (as is already done for FREE_EXPLORE without the four-store contract). Otherwise, block paid tickets for city themes in the editor with one plain sentence. Owner to confirm which is intended.

**P-02. Legacy 文字问答 with alternatives is unbeatable** [VERIFIED-UI c16–c19; code]
- **User-facing problem:** Every answer is 「答案不正确」, including the right one.
- **Reproduction:** in the editor's 文字问答 form, the 正确答案 placeholder says 「可填多个，用、分隔」; enter 「7、七、第七根、第7根」. The player types 7 → wrong.
- **User expected:** any listed answer passes.
- **Likely code location:** `PlayBasicCommandApplication.java:366-368` compares the whole string; `pages/publish/temp/index.wxml:1046-1047` has the placeholder.
- **Smallest change:** split `questionAnswer` on 、/, and match any item, as the advanced qa already does, or change the placeholder to say one answer only.

**P-03. Free exploration: the ticket disappears after the first shop** [VERIFIED-UI d18, d23, d25; DB `verification_status=1`]
- **User-facing problem:** After the first merchant redemption, the order says 「已完成 · 首次扫码成功，订单已完成」, 票夹 says 「还没有票」, and 我的 has no order. Three paid shops are unreachable.
- **Reproduction:** pay for session 13 → scan shop 1 → merchant `scan_qr_code` → reopen 票夹.
- **User expected:** the ticket stays until all four shops are done or the session ends, and the order shows 1/4.
- **Likely code location:** first `scan_qr_code` sets the registration verified; the 票夹 list (`subpackageMember/signup/index.js:470-500`, `/api/registration/list`) drops verified tickets; the order state reads 「首次扫码成功」 as completion.
- **Smallest change:** for productType 2, treat a registration as active until all required chapters are redeemed, and keep it in 票夹 with the 1/4 progress.

**P-04. The author's ending never plays** [VERIFIED-UI c59, c62; net]
- **User-facing problem:** After all 9 stations the player gets badges and cards but never the 尾声 chapter. 「继续」 goes back to 票夹. Background calls fail 「这一局还没开始」 / 「本局尚未开始」.
- **Reproduction:** play a city theme without holding 「开始这一局」 in the menu (nothing asks you to). Finish the last station. `/api/play/journey/ending` → 「本局尚未开始」.
- **User expected:** the story's ending after the last station.
- **Likely code location:** `pages/play/index.js` loadEnding (~7645) depends on the run session; the run starts only from the menu hold (`orient-lifecycle`).
- **Smallest change:** start the run implicitly at the first arrival or answer, or load the ending from completion when no run exists.

### S2

**P-05. Paid sign-up on a non-platform-owned theme shows the wrong reason** [VERIFIED-UI q10]
- **User-facing problem:** The button 「确认价格并付款」 leads to 「缺少报价签名，请重新进入报名页」. The real reason, 「该产品暂不支持在线支付…如需报名请联系主办方」, is only in the quote response.
- **Reproduction:** club-owned free-exploration theme, before the content slot is set to PLATFORM.
- **User expected:** the real reason before entering details, or no ¥ price shown.
- **Likely code location:** `pages/activity/baoming/baoming.js` quote failure handling; `CmsRegistrationServiceImpl.java:1510-1517`.
- **Smallest change:** show the quote error text in the existing inline error and disable the button. The gate itself is an OWNER DECISION.

**P-06. 「我到了」 always fails on club-opened sessions** [VERIFIED-UI c03, d03]
- **User-facing problem:** At the meeting point, the player gets 「集合点未配置，请联系领队」, and the leader cannot fix it.
- **Reproduction:** `/api/club/open-session` (only date and time) → player 「我到了」 → 「定位并签到」.
- **User expected:** check-in succeeds at the meeting point.
- **Likely code location:** `ClubSessionOpenRequest` has no meeting point; `cms_activity.latitude/longitude` stay null although the ticket has `gather_lat/lng`; `ClubGatherArrivalPolicy.java:21`.
- **Smallest change:** copy the ticket's gather coordinates into the activity at open-session.

**P-07. Refund promise contradicts itself** [VERIFIED-UI q11, b06, b07, b08; API]
- **User-facing problem:** Checkout says 「本单不可退…支付后不可退款」. The order page then says 「可免费取消至 11月3日 23:59」, and cancelling refunds ¥69 in full. The progress shows 「等待支付」 after the refund.
- **Reproduction:** buy the next-day 10:00 session after the 24 h cutoff → cancel.
- **User expected:** one consistent rule.
- **Likely code location:** quote `refundableNow` vs. order-detail cancel window (orderinfo `cancelDeadline` source); orderinfo progress rows.
- **Smallest change:** derive the order page text and the cancel allowance from the same `refundDeadline` that the quote returns.

**P-08. Leader-locked chapter is playable, then silently refused** [VERIFIED-UI c39–c42]
- **User-facing problem:** The chapter 2 title card and game play normally. The server answers 「本章还没开启,等队长放行」, which is not shown, and the station card comes back. The banner says 第一章 while the screen says 第二章.
- **Reproduction:** club session; finish chapter 1 before the leader releases chapter 2.
- **User expected:** 「等队长放行」 shown before the chapter's game (the gate is an OWNER DECISION).
- **Likely code location:** `pages/play/index.js` arrive error handling (`intentTerminal`); the chapter gate check in `showChapterGate`.
- **Smallest change:** show the server message, and hold at the chapter gate screen.

**P-09. Advanced 猜数字 shows no question and no reveal** [VERIFIED-UI c39, c40; net `playKit.estimate` has no title]
- **User-facing problem:** The player sees only 「5 6 7 米 · 就这个数」, then 「继续故事」.
- **Reproduction:** an estimate game created from the editor's advanced config.
- **User expected:** the question, then a hit or miss and the author's 「reveal」.
- **Likely code location:** `AdvancedGamePublicProjection` estimate copy (title and reveal dropped); `playkit-estimate`.
- **Smallest change:** project `title` (and `reveal` after submit).

**P-10. Wrong answers on advanced games give no feedback** [VERIFIED-UI c35, d28]
- **User-facing problem:** A typed wrong answer, or a missed estimate, changes nothing on screen.
- **Reproduction:** advanced qa TYPE wrong answer; merchant estimate miss.
- **User expected:** 「答案不正确」 or 「差一点」 as the legacy sheet does.
- **Likely code location:** `playkit-qa` / `playkit-estimate` handling of `lastCorrect=false` / `tier`.
- **Smallest change:** show a one-line result under the input.

**P-11. A correct legacy answer closes with no confirmation** [VERIFIED-UI c15]
- **User-facing problem:** The author's 「answerReveal」 and 「fragmentText」 are never shown, so the final 「四个字」 puzzle refers to things the player never collected.
- **Reproduction:** answer station 1 correctly.
- **User expected:** a short "right" moment and the fragment.
- **Likely code location:** `pages/play/index.js` submitGame success path.
- **Smallest change:** show the existing reveal line in the sheet for ~1 s, or add it to the story stream.

**P-12. Wrong-shop code shows internal wording** [VERIFIED-UI d10]
- **User-facing problem:** The player sees 「扫码节点与本次意图目标不一致」.
- **Reproduction:** at shop 1, scan shop 2's code.
- **User expected:** 「这是安福路旧书铺的码」.
- **Likely code location:** play checkin intent error mapping.
- **Smallest change:** map this code to a plain sentence naming the scanned shop.

**P-13. Free exploration asks the player to wait for a leader** [VERIFIED-UI d02]
- **User-facing problem:** After the ticket said 「场次内自由顺序，不带队」, the player sees 「队伍集合中 · 等队长开始行程…」.
- **Reproduction:** start the free-exploration session.
- **User expected:** start directly, or the ticket says 「领队开场」.
- **Likely code location:** play lead overlay is shown for any club activity.
- **Smallest change:** hide the lead gather screen for productType 2, or change the ticket text.

**P-14. The perk and the redemption code are hidden** [VERIFIED-UI d06, d08, d20, d22]
- **User-facing problem:** The shop card says 「开始互动 获得奖励！」 but never names 桂花拿铁. The order lists chapter names only. The code to show staff is on the back of the 票夹 card, and nothing points to it.
- **User expected:** "到店出示此码领取：桂花拿铁一杯".
- **Likely code location:** `pages/play/index.js` hero layer (`node.perk`); `merchant/index` step 3.
- **Smallest change:** show the perk name on the shop card, and add 「出示入场码」 linking to 票夹 at step 3.

**P-15. Author sees a generic publish error** [VERIFIED-API + log]
- **User-facing problem:** 「主题发布失败，请稍后重试」.
- **Reproduction:** publish a theme referencing another member's templates. The log says 「不能引用他人的模板:6005」.
- **User expected:** the real cause.
- **Likely code location:** `ApiTopicController` V2 create catch.
- **Smallest change:** pass through `RuntimeException` messages that are already user-safe.

**P-16. A real 人民广场 station is rejected** [VERIFIED-API]
- **User-facing problem:** 「节点仍是默认上海坐标，请重新在地图确认:79」, with a stray 「:79」.
- **Reproduction:** a station at 31.2304,121.4737.
- **User expected:** the station is accepted, or the author is warned when picking.
- **Likely code location:** topic node coordinate guard.
- **Smallest change:** drop the ID suffix and warn at the 选择节点 step, not at publish.

**P-17. Home shows a stock "influencer" image and LIVE** [VERIFIED-UI p03]
- **User-facing problem:** 「附近活动」 card shows someone's profile mock-up (「Marina · Follow · WINDS OF DESTINY · 86.54k」) and a red LIVE.
- **Reproduction:** any activity without a cover.
- **User expected:** the theme cover or a neutral placeholder.
- **Likely code location:** `pages/index/index.wxml:136` fallback `/images/index-figma/nearby-card.jpg`.
- **Smallest change:** use the theme cover or `route_city_cover.png` as the fallback.

**P-18. The editor auto-creates a two-month "session" at 00:00** [VERIFIED-UI p04, p12b]
- **User-facing problem:** Home says 「上午12:00」, and the picker shows two identical 「周一 11.2」 entries, 13:35 and 00:00.
- **Reproduction:** create a city theme with a ticket synced to the theme dates.
- **User expected:** only real sessions, labelled by who runs them.
- **Likely code location:** V2 create creates the activity from the ticket window.
- **Smallest change:** label the window session 「随时可玩 · 至 12.31」 and show the club session as 「俱乐部带队」.

**P-19. Club gate sends the player to another club** [VERIFIED-UI q04, q07]
- **User-facing problem:** The detail brand is 「城瘾跑团」, but the gate opens 「执行俱乐部」.
- **Reproduction:** buy a session run by the execution club.
- **User expected:** the club named on the session.
- **Likely code location:** topic detail 参与品牌 uses the content club; the session belongs to the execution club.
- **Smallest change:** show the executing club on the session card.

**P-20. City order page uses shop wording** [VERIFIED-UI p24]
- **User-facing problem:** 「等待到店核销」, 「商家消息：…「主理人策划工作室」」 and 「主办方：俱乐部主理人」 (a person, not the club).
- **Reproduction:** free club city session order.
- **User expected:** the club name, and a city wording such as 「到集合点开场」.
- **Likely code location:** `orderinfo` progress and the merchant-consent block.
- **Smallest change:** branch the wording by productType; show the club name.

**P-21. Free-exploration detail undercounts** [VERIFIED-UI q03]
- **User-facing problem:** 「参与商家 0 家」 with four shops, and 节点介绍 shows one card.
- **Reproduction:** free-exploration detail after merchants joined.
- **User expected:** 4 家, with 4 cards.
- **Likely code location:** `topic/index` stats come from the topic, not approved nodes.
- **Smallest change:** count approved merchant nodes.

**P-22. Achievements contradict the walk** [VERIFIED-UI f01, f02]
- **User-facing problem:** 「累计探索 11家店 · 0.0km」, 「TOP100%」, and a finished walk listed as 「待核销」.
- **Reproduction:** finish a city theme, then open 我的 → 成就.
- **User expected:** 1 walk, 4.0 km, 已完成.
- **Likely code location:** member stats aggregation; order status for city completion.
- **Smallest change:** show 「—」 where data is missing, and mark city orders complete on 通关.

### S3

| ID | Problem (quote) | Evidence | Smallest change |
|---|---|---|---|
| P-23 | Compass: 「方向读取失败，请重试或退出」. There is no way past the station on a phone without a compass. | Runtime only; NOT VERIFIED on a device | An 「跳过」 after two failures (leader or author can allow). |
| P-24 | A failed photo upload returns to 「拍一张」 with no visible error (cause here is HARNESS). | c24 | Inline 「上传失败，请重拍」. |
| P-25 | The hint label stays 「看提示（1/2）」 after hint 1. The score drops 100→70 with no warning. | c12 | Update the counter; say 「看提示 −30 分」. |
| P-26 | Location consent is asked only after the first answer, and again after every relaunch. | c09, c51, d14 | Ask at 「开始游戏」 once per run (consent text already says 本次). |
| P-27 | 票夹: 「场次 · 今天过期」 instead of the start time and place. 「开始游戏」 is still offered after 通关. | p26, c62 | Show 「13:35 · 外滩源 33 号门前」; 「已通关 · 查看记录」. |
| P-28 | The city menu shows 「扫码到达 · 到店后扫商家的码」. Free exploration has no menu (no 入场码 or 旅程手记). | c25, d17 | Hide merchant rows for city; add the same menu to free exploration. |
| P-29 | Chapter closing text appears out of place (chapter 1's last line shows behind the finale). | c59 | Show a chapter's trailing text before the next chapter card. |
| P-30 | Session picker is not chronological (11.3 before 11.2). The ticket card says 14:15–18:15, the session is 14:05–23:59. | q04, q05 | Sort by start; show the session's own time. |
| P-31 | Consent tick not drawn; the button keeps 「请先勾选信息共享同意」 after ticking. | p22 (`<icon>` partly HARNESS) | Change the label once `hostShareChecked`. |
| P-32 | 「积分抵扣 -¥0」 shown for a ¥0 ticket. | p18 | Hide when the total is 0. |
| P-33 | Editor page 2 「票务设置」 header scrolls under the status bar and back button. | a29, a32 | Add the nav placeholder (same class of fix as #702 item 5). |
| P-34 | Author pricing page shows ¥0 floor and ¥0 price after ¥69 was confirmed. | a40 | Pre-fill from the confirmed price. |
| P-35 | No cue for the player when the leader releases a chapter or a merchant redeems. | c27, d26 | A one-line toast or banner (system notice is OWNER DECISION 城瘾小助手). |
| P-36 | 消息 card prefix 「发送时：报名成功」; an unpublished theme's valid ticket shows 「活动暂不可用」. | f05 | 「报名成功」; keep the card readable for ticket holders. |
| P-37 | Free-exploration navigation strip targets the shop just completed (「0 米」). | d16 | Point to the nearest unfinished shop. |
| P-38 | First open stacks 「首页城市定位」 over the privacy gate. | a03 | Ask for location after the gate closes. |
| P-39 | 「探店图鉴」 shows the merchant account name and raw 「11-02T13:38」. | (orderinfo after redemption) | Shop node name and 「11-02 13:38」. |

### Known, fix pending

- **#702 item 4** (home lists empty for logged-in users):
  - Logged in as 9003, home showed 「编辑精选 暂时没有精选主题 / 附近活动 你附近暂时没有可点亮的节点」 (a04), while the guest saw the activity (p03).
  - This matches the PR's description. The PR's fix (same-value role refill does not bump the session revision) addresses the cause as described. I could not check it against the fix branch.
- **#702 item 5** (free-exploration perks page under the nav bar): I did not reach that page. I saw the same pattern on editor page 2 (P-33).
- **#701 / #703 / #704:** club notification delivery, merchant cards and workbench parity are outside this lane's path. Not re-reported.

## Gameplay feel

Judged from the rig. Animation timing is real-time Chrome, not a phone.

| Moment / game | Rating | What I saw | Smallest change |
|---|---|---|---|
| Intro 「读 / 玩 / 开始游戏 · 建议戴耳机」 | works and feels good | A quiet, confident start. | — |
| Gather screen | works but awkward | 0/2 never moves (P-06). No time or place. I stood there with nothing happening. | Show the start time, the meeting point and who has arrived. |
| Story stream | works and feels good | Large calm text, good pacing between stations. | — |
| Chapter title card (「第 2 章 / 共 3 章 … 3 坐标 · 轻点跳过」) | works and feels good | A real beat between chapters. | Use 「站」 not 「坐标」. |
| Option QA, legacy (station 1) | works but flat | Wrong/hint/reveal feedback is clear, but the right answer just closes the sheet (P-11). It did not feel earned. | Hold 1 s on 「对了」 + fragment. |
| Text QA, legacy (station 2) | broken by P-02 | Fair puzzle, unbeatable answer; the reveal saved it. | P-02 fix. |
| Photo (station 3) | NOT VERIFIED | The setup copy is good. 「还能重拍 3 次」 before any shot reads as a scolding. | 「可拍 3 次」. |
| Typed QA, advanced | works but awkward | Silent on wrong (P-10). 「答对了」 on right is fine. | One result line. |
| Estimate, advanced | works but awkward | No question (P-09). The wheel is pleasant, the result is invisible. | Question + 「差 0.6 米」 style result. |
| Sort | works and feels good | Instructions, 「还没排对 · 挪一挪再交」, 「排好了 · 这一题过了」. It fit the 「字条」 story well. | — |
| Compass | works and feels good | 「往左转 110°」 → 「已对准」. Facing the river at the bank really fits the place. Seam timing caveat (§5). | A skip path (P-23). |
| Note | works and feels good | Presets lower the effort. 「这句已经留在这儿了」 is warm. | Show earlier notes when they exist. |
| Option QA with per-option feedback | works and feels good | Each wrong option has its own line, so wrong answers are part of the story. | — |
| Final typed answer | works but flat | 「钟、声、不、停」 refers to fragments the player never saw (P-11). | Surface the fragments per station. |
| Finale | works but awkward | Six overlays in a row (badge, footprint card, 首达, 主题通关, 全部完成, 保存通关卡), but no story ending (P-04). It was rushed past the emotional moment. | Play the 尾声 first, then one summary card. |
| Free-exploration card pack | works but flat | A beautiful object, but nothing says "tap to open". | 「轻点拆开」 under the pack. |
| Free-exploration shop card → check-in page | works but awkward | 「开始互动 获得奖励！」 leads to a 3-step admin-like page, not a game. | Rename the CTA 「到店打卡」; name the perk. |
| Free-exploration merchant option QA | works and feels good | Shop-voice feedback (「问问吧台的店员」) fits standing at the counter. | — |
| Free-exploration merchant estimate (legacy) | works but flat | A miss gives nothing. | Same as P-10. |

## Theme editor notes

**What an author needs from the product for a theme to land:**

1. **Their ending must play** (P-04). The 尾声 chapter is where a story pays off. Today the only payoff is system badges.
2. **Everything they wrote must reach the player:**
   - per-station `fragmentText` / `answerReveal` (P-11),
   - the estimate question and reveal (P-09),
   - the photo requirement sentence,
   - chapter trailing lines in their place (P-29).

   An author writing a 「collect four characters」 finale needs the characters to be shown.
3. **Hints on every question type.** The modern 选项问答 / 文字问答 editor has no hint field. Hints exist only on the legacy forms, which have the P-02 bug.
4. **One answer rule across game types.** 「、」 alternatives work on the advanced type and silently break the legacy one (P-02).
5. **An honest publish state.** 「主题发布成功」 next to `reviewState: PENDING` and 「尚未开售」 confuses. Paid city themes have no path to sale (P-01). Errors are generic (P-15). The quota message (「同时上架的主题已达上限（2）」) came only at publish time, after all the work.
6. **A preview of the player view per station before publishing.** I could not tell that my estimate would appear without its question.
7. **Session control.** The editor silently creates a two-month 00:00 session (P-18), and club sessions lack a meeting point (P-06). An author thinks in 「一场：周六 14:00 外滩源」.

**Which of my own authoring steps felt wrong:**

- 「开始创建」 on the intro page opened a single-game editor, not a theme editor.
- 「主题描述」 is a 20-character subtitle, while the long story is under 「剧情详情」. I pasted my story into the wrong one first.
- The chapter name field pre-fills 「第1章」 as text, so my title became 「第1章第一章 · 江海之门」.
- The station 地点 row says 「选择节点」 while I am creating the node.
- The game page asks for a separate 「玩法封面」 per game. Nine stations means nine extra images nobody sees in play.
- I put a station on the most famous square in Shanghai and was told it was a default coordinate (P-16).
- I wrote 「约2.5小时」 into the subtitle because there is nowhere else to state the duration, and the station 「15 分钟」 shows as 「0分钟」 on the detail.
- After publishing a paid theme there was no way forward. The natural author move (unpublish and republish free) cost a theme slot.

**Good for authors:**
- The chapter story-flow model (text / place blocks in one column) is the right shape.
- The 9-type picker is approachable.
- Per-option feedback and note presets let the author's voice into the games.

## 4. Cross-role seams

| Seam | What one role does | What the other sees | Evidence |
|---|---|---|---|
| Leader → player | The leader releases a chapter (「已开启:第二章」) | Only the banner text changes; no cue. Before release, the player can play the locked chapter and is silently refused. | c27, c39–c43 |
| Leader → player | open-session (date and time only) | Gather check-in impossible (P-06) | c03, d03 |
| Merchant → player | 「核销成功」 | No notification. The order says 「已完成」 and the ticket disappears (P-03). | d18, d23 |
| Author → player | Unpublishes the theme | Ticket still playable (good). The message card shows 「活动暂不可用」. | e01, f05 |
| Author / organiser → merchant | Theme reaches SELLING | A 5th merchant cannot join: 「主题已锁价，不能再申请或审核商家承接」. The club leader's own merchant 8005: 「商家身份无效或权限不足」 (single RBAC role). | helper-agent API |
| Merchant → player | poster-code: 「个人码生成服务繁忙，请稍后重试」 | The merchant never sees a printable code. The text check-in code exists only in the DB. (WeChat code generation is HARNESS.) | helper-agent API |
| Platform → club author | Only platform-owned content can take payment (OWNER DECISION) | The club author is not told. The player sees 「缺少报价签名」 (P-05). | q10 |
| Content club ↔ execution club | The theme brand is 城瘾跑团 | The gate sends the player to 执行俱乐部 (P-19) | q04, q07 |
| Merchant data → player | The merchant's 3-line 「rule」 (「答完给店员看即可领取桂花拿铁」) | Returned by check-in, never shown to the player (P-14) | d11 net |

## 5. Not verified, harness notes, fixtures and workarounds

**Injected or unavailable (NOT VERIFIED as real behaviour):**
- real WeChat login (rig `jscode2session` seam);
- `getPhoneNumber` (not used);
- real payment channel (rig `/__rig/pay` → real `/api/registration/callback`, `RIG-TXN-75`);
- camera scan (the `scan` seam with the DB `checkin_code`);
- real GPS (the `loc` seam);
- compass (the `heading` seam);
- native map (「在地图中查看路线」 did nothing);
- share cards, subscribe messages, audio and fonts from OSS, safe areas, keyboard.

**HARNESS:**
- **Image upload.**
  - What fails: `/api/common/uploadOSS` calls the WeChat `img_sec_check` directly (`AppUploadService.java:346,467`, hard-coded host), which is unreachable. Every upload returns 503 「安全检查服务异常，请稍后重试」. That covers covers, the photo station and the free-exploration 「拍摄现场凭证」.
  - Cover fixture: I generated cover and station images locally and copied them into the rig upload directory (`/profile/upload/2026/11/02/*.jpg`). I set the cover in the editor with `setData`. The API then withholds these images (`imgUrl: ""`), which is correct for unchecked media, so **no cover appears anywhere**. That is a consequence of the fixture, not a product bug.
  - Attempted fix, not applied: I tried a local WeChat stub (hosts entry + trust-store import). The session's permission policy denied it. Nothing was changed; I verified `/etc/hosts` and the trust store were untouched.
  - Result: the photo station and photo proof are **NOT VERIFIED**. I authored a 「雨天版」 replacing the photo station with a typed question to finish the city walk.
- **Rig taps.**
  - Mouse clicks on several `hover-class` views did not fire their `tap` binding in the rig: the topic dock CTA, the 「同意将…」 consent row, `cy-modal` buttons, the play `cy-btn`.
  - I dispatched a click on the same bound element, or called the exact page handler the tap binds to (`onHeroCta`, `toggleHostShare`). I saw the 「本单不可退」 modal and then called `handlePayment` with `_nonRefundableConfirmed=true`, which is what its 「继续下单」 does.
  - These are runtime artefacts. Real-device taps are NOT VERIFIED.
- **Editor typing:** on 编辑玩法模板, typing into textareas lost characters (re-render).
  - I created the 9 games with `/api/template/draft` as the same author.
  - I put chapters 2–4 and the tickets into the editor's state with `setData` and serialized them with the editor's own `_buildSubmitPayload`, then POSTed to the editor's endpoint `/api/topic/v2/create`.
  - The UI parts actually exercised: the detail sheet, categories, the chapter 1 text block, the node sheet and the game picker.
  - 「预览」 on page 2 did nothing after the injection, likely because its derived validation state was bypassed.
- **Compass seam:** the rig fires headings every 500 ms, exactly the component's `FRAME_GAP_MS`, so it always failed. I drove the same `setHeading` seam every 100 ms.
- **Time zone:** the rig browser shows UTC (「05:37 到达」, inbox 「05:25」). Probably not a product bug on a phone in CST.
- **`<icon>`:** the consent tick is not drawn by the runtime.
- **Docker Hub 429 during `up.sh`:** retried the pull; nothing else changed.

**Fixtures and direct actions (all disclosed):**
- **Time shifts:** `seed-walk.py shift-session` on activities 12, 13 and 16, to start them now.
- **Admin approvals:** `/business/audit/review`. The review needs `expectedVersion` from `audit_task.version` (the chain scripts omit it and would get 「请刷新审核内容后重试」).
- **City theme sale steps (failed, P-01):** payee, work packages, hours, cost, organizer actor.
- **Free-exploration sale close** (helper agent, real admin/merchant/club endpoints).
  - Its only DB write was `UPDATE cms_topic SET recruit_deadline = …` on 990017, as `chain_merchant_club.py` does.
  - It also ran `PUT /business/topic/990017/content-slot {"entityType":"PLATFORM"}`, as `chain_player.py` does.
- **Leader, merchant and other-role actions through real APIs:**
  - `lead/start`, `lead/unlock-chapter`;
  - merchant `scan_qr_code`;
  - `club/join` for walk-player-1 (same body as the UI).
- **Identities:** fixture 9003 / 9008 (club leaders), 9002 / 9005–9007 (merchants), 9004 / 9009 (players), plus walk-player-1 created by real login.
- **Themes:**
  - 990016 「外滩钟声：失踪的报时人」: paid, stuck in PRICING, unpublished by the author.
  - 990018 「…报时人的最后一夜」: free, played to station 3, then unpublished.
  - 990022 「…（雨天版）」: free, played to the finale.
  - 990017 「弄堂咖啡地图 · 秋日版」: free exploration, SELLING.

**Not covered (time):** 帖文 write and share; session ended while playing; token expiry; network failure during submit (the rig has `E2eFaultInjectionFilter`); two phones in one team; 发件箱 contents; free-exploration shops 2–3 and the 通关奖励; city backpack and casebook contents (these calls returned 「这一局还没开始」).

STATUS: PARTIAL - not walked: interruptions (token expiry, network failure on submit, session ending mid-play, two-player team); posts and sharing; free-exploration shops 2–3 and its finish reward. Photo steps NOT VERIFIED (harness upload).
