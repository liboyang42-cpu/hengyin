# 多人报名 + 结算/排行榜 截图集（Questo 6.01.30 · Android · 2026-11-08）

抓取环境：Android 13 arm64 模拟器（`questo_cap33`），账号已登录；本次**实跑通关** Paris 1793（8 章）以取得结算流程。

---

## A. 多人报名（付费前后的报名页）

| 文件 | 界面 | 关键文案 |
|---|---|---|
| `A1-lobby-play-with-friends.png` | 关卡大厅（Play 之前的最后一屏） | `PLAY WITH FRIENDS — Up to 2 players · invite friends`；`How do you want to play?` Team up / Compete；语言 `EN · English · Only language available`；`Playing as …` + Edit |
| `A2-teamup.png` | 选中 **Team up** | `One game for all of you. Anyone answers, everyone moves on.` |
| `A3-teamup-explainer.png` | 展开 **How team play works** | `One team, one game`：同题在每人手机上 / **第一个正确答案算全队** / **提示、跳过、计时共享** / **结束时只有一个分数，算全队** / `Stay online. Lose signal and you catch up when it's back.` |
| `A4-compete.png` | 选中 **Compete** | `A game each` — `Same quest, own phone. Ranked at the end.`；When you play: `Live`（需网络）/ `Anytime`（可离线） |

**要点**：报名分两层——**Crew（12 席，长期）** 与 **票内 seats（按票面人数）**；Team up 模式下提示/跳过/计时**共享**，Compete 模式各自计分、结束时排名。

---

## B. 通关后的结算流程（本次实跑，顺序即真实顺序）

| 文件 | 第几屏 | 内容 |
|---|---|---|
| `B1-chapter-complete.png` | 每章结束 | `Chapter N complete` — **Time / Chapter score** + `Next: <下一站>` |
| `B2-skip-rules.png` | 跳过确认 | `A skipped challenge scores 0 points. This uses your **one free skip for the quest**.`（每关仅 1 次免费跳过） |
| `B3-rewards.png` | 第 1 屏 | `Your rewards` — `+2000 XP`、`Level up. Credit for the new level: $4` |
| `B4-quest-complete.png` | 第 2 屏（主结算） | `Quest complete`、`#3 in this quest`、**Score 2000**、**Chapters 8/8**、**Coins left 10**；按钮 `See the leaderboard` / `Finish` |
| `B5-lb-team.png` | 排行榜 · **Your team** | 队内榜：`#1 You 2000 pts` |
| `B6-lb-everyone.png` | 排行榜 · **Everyone** | `#1 Matteo Saitta 2398`、`#2 Giovanni 2341`、`#3 You 2000`、`#4 Юрий Азанов 0`、`#5 John 0` |
| `B7-lb-friends.png` | 排行榜 · **Friends** | `None of your friends has played this quest yet.` |
| `B8-rate.png` | 评分 | `How was the quest?` 五星；`Not now` 可跳过 |
| `B9-review.png` | 评分后 | `Thanks, that helps.` + `What did you enjoy?`（0/1000 字）+ `Reviews are public, with your first name.` |
| `B10-next-quest-upsell.png` | 推荐位 | `For your next quest`：`City Pass • $39.00`（本城还剩 22 关）；**`Friends play free for 3 days. Share your link… You get $2.50 when they finish their first quest.`** + `Get my link` |
| `B11-share-card.png` | 分享 | `Share your quest`：`Post` / `Story`；卡片 `Solved in Paris · Paris 1793 · 2000 pts • Chapters 8/8 · questoapp.com`；`Share` / `Save image` / `Done` |

> 结算顺序：**奖励 → 总览（排名/分数/章数/金币）→ 排行榜（队内/全体/好友）→ 评分 → 评价 → 下一关推荐（含推荐分润 $2.50）→ 分享卡**。共 8 屏，与用户记忆一致。

---

## C. 排行榜的其他口径与被完成态

| 文件 | 内容 |
|---|---|
| `C1-memories.png` | `Memories`：`Every quest, every photo, who you played with. Only you can see this.`；每条有 `+ Add a photo`、`Invite to Questo`、`Read the story` |
| `C3-lb-weekly-world.png` | 排行榜：**WEEKLY · WORLDWIDE**，`#443`，`Overtake fcolegrave3 +29 XP`，25 RANKED |
| `C4-lb-alltime-world.png` | **ALL-TIME · WORLDWIDE**，`#8742`，榜首 1,017,626 XP |
| `C5-lb-city.png` | **ALL-TIME · LOS ANGELES**（城市榜），未上榜时提示 `Play one quest to enter this week's board. Resets every Monday` |
| `C6-quest-downloaded.png` | 开局前：`8 chapters. Everything is downloaded, so you can play without signal.` |
| `C7-in-game-menu.png` | 游戏内菜单：背景音/音乐/语音音量、`Take a screenshot`、FAQ、`Report a problem`、`Start over from the beginning` |
| `C8-investigation-detail.png` | Investigation 型关卡详情（Zodiac，14 章）：案件叙述 + 章节结构 |

---

## 本次跑通记录（可复现）

- 关卡：`Paris 1793: Escape the Guillotine`（8 章，`visual-v1`）
- 结果：**2000 分 · 8/8 章 · 本关第 3 名 · 剩余金币 10 · +2000 XP**
- 各章答案（来自内容包 `content/en.json` 的 `outdoor.fallback`）：Danton / glass（rev74 新增的第二题）/ 1686 / horse / fleur-de-lis / black and white / Saint-Honoré / 8
- 定位用 `adb emu geo fix <lon> <lat>` 逐站切换；每章 4–5 屏（Directions → Intro → Challenge → Facts）

## 对城瘾的结论

| 观察 | 建议 |
|---|---|
| 报名分 Crew（12 席）/ 票内 seats 两层 | 别混成一个概念 |
| Team up：提示/跳过/计时**全队共享** | 多人同乐必须共享资源池，否则出现"谁的提示"争议 |
| Compete：各自计分、结束时排名 | 需要"同题多端 + 结束后聚合排名"的能力 |
| 每关仅 **1 次免费跳过** | 明确的防滥用上限 |
| 结算 8 屏：奖励→总览→排行榜(3 口径)→评分→评价→推荐→分享 | 城瘾可裁成 4 屏：总览 → 排行榜 → 评价 → 分享 |
| 排行榜三口径：队内 / 全体 / 好友 | 城瘾已有会员榜，缺"好友榜"与"队内榜" |
| 推荐分润 **$2.50/首关** + 3 天免费 | 与已有 Affiliate 条款对应，可直接落到产品文案 |
| `City Pass $39` 卖"全城剩余 22 关" | 城市通票是主力 SKU |

## 注意

