# W7 x-life-games: receipt (hengyin)

Branch `feature/r-x-life-games` from `origin/roadmap/be-integ`. App side: chengyin-app `cloud/r-x-life-games`.
Both bases already contain `x-template-games` and `x-shop-game-runner`, so nothing was merged in.

## Design (written before the code)

R12 asks for real mechanics on the shelf copies of 《生活不掉线》 G0/G3/G4/G6 and the 8 《今天值得庆祝》 games.
Every one of them fits an engine that both clients already play, so **no new section type and no new runner** is added:

| Engine | Used when | Clients |
|---|---|---|
| vm=6 preference group (`PreferenceResultEvaluator`): `single` and `discard` steps, a result card that quotes the player's choices (`{{choices}}`) | there is no right answer | app `PlayPreferenceViews`, mini `pages/play` preference panel |
| vm=3 A–D option, judged on the server against `correct_answer`; `answer_reveal` comes back only after a right answer | there is one right answer (a time window, a budget, a walk limit, a fallback that keeps every condition) | app option cards, mini option cards |
| advanced `timer` (`AdvancedGameConfigValidator`), played before the node's own task; `completeGate` refuses a completion after an `EXPIRED` timer and the player restarts | the card promises a time limit | app `PlayAdvancedView`, mini `advanced-game` |

Per game (shelf rows in `cms_template_library` only):

| Game | Mechanic | Judging |
|---|---|---|
| 生活 G0 30秒生活掉线测试 | vm=6, 4 steps: situation → area (blurred to a district, never an address) → reachable time → top concern. Keeps its 30 s timer | preference; the result names the top concern and quotes all four picks |
| 生活 G3 营业时间拼图 | vm=3: three opening windows, four times; pick the one inside all three | server (C) |
| 生活 G4 去之前准备什么 | vm=6 `discard` step: four prep cards, drop the one you don't need, the three left are your list | preference; each result lists the three kept cards |
| 生活 G6 首选失效怎么办 | vm=3 + new 30 s timer: your usual shop is closed; pick the fallback that still meets the deadline | server (B) |
| 庆祝 G0 30秒今天值得什么 | vm=6, 2 steps: what to celebrate → ritual style. Keeps its 30 s timer | preference |
| 庆祝 G1 每人一个不能 | vm=6, 2 steps: one must-have, one no-go. Solo on the shelf: the copy says each companion plays once and you compare cards (the app does not merge answers) | preference |
| 庆祝 G2 气氛卡碰撞 | vm=6: pick a mood card; the result names the kind of real project that fits it | preference |
| 庆祝 G3 三小时时间方块 | vm=3: workshop hours, restaurant opening, walk time; which start fits in three hours without waiting. Keeps its 3 h timer | server (C) |
| 庆祝 G4 每人预算筹码 | vm=3: three priced parts and a per-person budget; which plan stays inside it | server (B) |
| 庆祝 G5 十分钟步行上限 | vm=3: four unnamed routes with leg times; which keeps every leg within 10 minutes. Keeps its 10 min timer | server (B) |
| 庆祝 G6 主选突然掉线 | vm=3 + new 30 s timer: the booked place is full; pick the replacement that keeps time, budget and the hard condition | server (C) |
| 庆祝 G7 60秒共同落款 | vm=6, 2 steps: a prompt and a short phrase card → a private sign-off card. Keeps its 60 s timer | preference |

Rules for the seed change: one migration, every UPDATE guarded on the seed text (`create_by`, `validation_method=0`, no `preference_json`/`correct_answer`, rule text still the seed's or `x-template-games`' rule-card wording). Merchant-edited rows are left alone.

R10, R11 and the tally screen use what exists: the answer receipt gains `feedbackText` next to `answerReveal`; the preference submit takes the same `longitude/latitude/accuracy` as `/photo` and the same switch and radius; the tally screens read `GET /api/template/preference-tally`.
