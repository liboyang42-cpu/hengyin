# r4-motion · 动效系统与视觉冲击收口 — RESULT（2026-11-10）

分支 `claude/r4-motion-20261110`，基线 `origin/staging/questo-review-20261110` 的 `941898f5`（qx-s2 写出 `STATUS:` 之后切出）。草稿 PR #770 合入 staging。没有合并、没有标 ready；没有推 master / integrate / staging / 其它 lane 分支；没有碰生产、VPS 或私有仓；没有改支付 / 退款 / 结算逻辑，没有改鉴权；没有新增或修改 migration；没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有提交密钥（夹具里的推广凭据在 rig 脚本里已脱敏）。

约束来源：`MOTION.md`（§2 token、M1–M18、§4 节拍、§5 门禁）、`IMPACT.md`、`ROUND2.md`、`ROUND3.md`、`BRIEF.md`、根 `AGENTS.md`、`chengyinhub-xcx/AGENTS.md`。

## 一、集成后 staging 的起点（先核再做）

- qx-s2 合完 15 条 lane 之后，`MOTION.md` §2 的 token、`utils/motion-spring.js`、M14 的触感四档、`utils/wxs/tilt.wxs` **一样都没有**：r2-visual 做了弹层面 / 灰字 / 圆角 token 和收集册的 `pile-tilt.wxs`，没有做 §2 那一组。
- 动效棘轮红：当前树 169 组 `@keyframes` > 上限 146。多出来的 23 组全部是四条 lane 新加的（r2-finish 3、r3-share 3、r2-join 9、r2-merchant 8），`reduced-motion-coverage-contract` 和 `theme-look` 也因此红。qx-s2 把它写进「待你决定 4」，默认是「上限不动，lane 改成 transition」——本 lane 按这个默认做了。
- 主包 2030.7 KiB，余量 17.3 KiB。所以本 lane 进主包的只有 token、`utils/` 两个小文件、`cy-celebrate` 的两个属性和 `utils/motion.js` 的四个函数；其余全在分包。

## 二、做了什么

### 1. 地基（MOTION.md §5.5 phase 0）— 提交 `135ccd6b`

| 项 | 内容 | 文件 |
|---|---|---|
| §2.1–2.4 token | 10 个时长（micro / reveal / roll / curl / flip / dissolve / stagger / stagger-hero / hold / ambient）、4 条缓动（settle / spring-soft / flip / roll）、10 个位移缩放档（rise-sm/md/lg、scale-under、shift-under、scale-lift、scale-press、tilt-max、stack-rot、stack-rot-mid）、抬起 / 叠层投影两条。玩家暗底值在 `page{}`；浅色值进 `.theme-light` 与 `.theme-topic-editor`，`merchant-light.wxss` 镜像（`ds-theme-sync-check.sh` 通过）。 | `style/tokens.wxss`、`style/merchant-light.wxss` |
| `utils/motion-spring.js` | 四个弹簧预设的 CSS 近似 `curve(name)`（与四条缓动 token 同值，单测钉住）、半隐式欧拉 `step(state, dt)`（dt > 16ms 自动拆子步，落定钉在目标）、松手落位 `releaseToTransition(x, v, target, name)`（时长按 `|v|/2` 夹在 [.6, 1.4] 倍：快甩落得快）。纯函数，不读减动效。 | 新文件 3.4 KB |
| M14 触感四档 | `hapticTap`（light）/ `hapticConfirm`（medium）/ `hapticHit`（heavy）/ `hapticMiss`（medium → 120ms → light）。没有 `wx.vibrateShort` 时空操作；**不读**减动效开关（§1.7 触感照给）；老接口 `haptic({reducedMotion})` 门控不变。 | `utils/motion.js` |
| M7 收勾触感 | `morph-success.settle()` 在勾出现的那一帧给一次 confirm；减动效下没有勾也不震。所有已接 morph 的成功时刻（领席位、领礼物、提示解锁、采用、发布）自动获得。 | `utils/morph-success.js` |
| 向导形变读开关 | `wizard-morph.runIntroMorph()` 自己读 `readReducedMotion()`，开着直接切表单态（之前全靠三个页面各自判一遍）。 | `utils/wizard-morph.js` |
| 单测 | `motion-spring.test.js`（6 条：四预设 2s 内收敛、firm 过冲 / snappy 不过冲、大步长不发散、纯函数、时长边界、与 token 同值）；`r4-motion-foundation.test.js`（6 条：token 齐全与值、浅色镜像一致、触感四档与减动效无关、settle 触感、向导读开关）。 | `tests/unit/` |

没有新建 `utils/wxs/tilt.wxs`：r2-visual 的 `subpackageMember/components/cy/profile/pile-tilt.wxs` 已经是 M16 的实现（WXS 跟手、`data-reduced` 返回、台账放行），本波没有第二个消费方，按 §5.3「不为了以防万一上提」不动。

### 2. 动效棘轮回到 146 — 提交 `9079dbac`

四条 lane 的 23 组关键帧全部改成 transition（或静态），每处补了 `prefers-reduced-motion` 与应用内开关的兜底：

| 来源 | 关键帧 | 改成 |
|---|---|---|
| r2-finish 结算总览 | `st-pop` `st-ring` | 印章 `opacity/transform` 过渡；两圈光环各自外扩淡出（第二圈晚一拍），由 `st--in` 驱动。顺手按 §4.6 第 1 步排了整屏节拍（见 3）。 |
| r2-finish 游玩结局 | `pv-ending-ring` | 同上，两圈 ring 用 transition-delay 错开；`theme-look`「故事文字段之后只用过渡」复绿。 |
| r3-share 分享层 | `sc-rise` `sc-pop` `sc-up` | 组件挂 `reduced-motion` behavior；`show` 后下一帧挂 `sc--in`，卡（rise-md + settle）→ 徽标（spring，延 2×stagger-hero）→ 文字（延 3×stagger-hero）。 |
| r2-join 落地页 | `jl-rise` `jl-pop` `jl-ring` `jl-flow` | 入场改走 `utils/page-motion.js` 的 `--pm` 约定（同行两端 → 昵称 → 药丸按 stagger-hero；玩法卡 / 规则行读 `.jl-play` 的 `--pm-p` 按 `--i` 错峰，封顶 8）；光环与连线光点不再循环（§1 落地页不放装饰循环），改静态；领到席位 `.6 → 1` 的 spring-soft 弹起用 `is-pop` 两帧实现（§4.12）。 |
| r2-join 队伍详情 / 加入 | `tm-seat-in` `tm-seat-breathe` `tj-seat-in` | 席位圈 `--pm` 约定按 `--i` 错峰（两条数据路径都接：`/api/team/info` 与定向队伍详情）；空位不再呼吸。 |
| r2-join 领取礼品 | `gf-in` `gf-glow` | 圆章 `--pm` 入场（filter / scale / rotate），光环静态一圈；「领取」接 `cy-btn morph` + `settle()`（M7，IMPACT #25 的那一句）。 |
| r2-merchant AI 店铺角色介绍 | `ni-rise` `ni-twinkle` `ni-wave` `ni-pop` `ni-drop` `ni-swing` `ni-flip` `ni-turn` | 每步两个物件各自的「未上场起点 → 上场终点」transition；挥两下 / 摆两下 / 闪两下由页面按拍翻 `.ni-stage--p1…p4`（220/440/660/880ms 四次 setData，不逐帧）；气泡、面具用 transition-delay「随后出现」。应用内减动效加 `.ni--reduced`，不翻拍。 |

四份 lane 单测跟着改了断言（`r3-share-cards` 沙箱补 `Behavior` 桩、`qx-a6-merchant-grow-intro` 改成核对每步起点两两不同与拍子、两处 `prefers-reduced-motion` 规则）。`motion-property-ratchet`、`reduced-motion-coverage-contract`、`theme-look` 三条门禁全绿，上限 146 没有动。

### 3. 按 §4 节拍逐个时刻核对 — 提交 `710575ba`（含 2 的部分）

| §4 时刻 | 集成后 staging 的样子 | 本 lane 补的 | 没补 / 记下 |
|---|---|---|---|
| 4.1 开局 | r2-play 已做标题居中、HUD 行地图档让位、故事段文字动效、尘粒画布。HUD 行没有入场。 | 故事屏第一次亮起时 HUD 两行（生命值 / 背包 → 角色属性）从上方按 stagger-hero 落下（`--pm` 约定，once）。 | CTA 的 M7 收勾未接（开局按钮不是 cy-btn morph 宿主，改它要动 r2-play 的开局链路；见待你决定 3）。 |
| 4.2 到站 | 双 medium 震动；HUD 进度 morph-pill（M17）没有做。 | 到站改成一次 `hapticConfirm`（M14 规定单次）。 | M17 morph-pill 与雷达环 1→1.12 脉冲未做（需要新组件进 play 分包 + HUD 结构改动，记入待你决定 4）。 |
| 4.3 提示解锁 | u3 已做行尾收勾 + 内容浮出；没有触感。 | 勾亮起 `hapticConfirm`（重看不亮勾也不震；减动效 hold=0 不震）。 | 「剩余免费」计数 old→new 滚动未做。 |
| 4.4 答对 / 答错 | r2-kits 已做结果屏动图；没有触感。 | 全局判定屏 `cy-play-verdict`：win = confirm，fail = miss（两下，不抖屏）。 | 分数 / HP count-up 依赖服务端 delta，未动。 |
| 4.5 章末 | r2-finish 已做完成卡 + 粒子溶解 + 三棱锥（顺序是卡 → 溶解 → 下一章，与参考图一致，与 MOTION 4.5 的「先溶解再来卡」次序不同）。 | 溶解开始 `hapticConfirm`。 | 次序没有翻（业主参考图就是卡溶解；要改需业主定）。 |
| 4.6 结算 | r2-finish 新页四步；总览是印章关键帧 + 彩旗同时放，其余静止。 | 第 1 步整屏节拍：0 印章弹入（spring）→ 眉题 / 标题 / 名次 / 数据按 stagger-hero 上浮 → 约 600ms 分数 `count.wxs` 从 0 数到服务端分数（只在计分时）→ 900ms 一次彩旗 + `hapticHit`（只在通关时，一局一次）→ 约 1400ms 按钮上浮。第 2 步榜单行第一次到齐按 `--i` 错峰上浮，自己那一行 1.02 落定；名次数字滚动是 r2-finish 原有的。 | 第 2 步「自己那行离屏时钉在底部」与「页签切换横滑」未做；第 3 步星星逐颗填充触感未做；第 4 步分享卡由 r3-share 的 transition 版承担。 |
| 4.8 徽章到手 | `stampIn` 与 `cy-celebrate` 原有。 | `cy-celebrate` 新增 `count`（≤44，「六成」场景传 26）和 `palette`（brand）属性，默认不变。 | 还没有宿主传 count=26（第二次庆祝的判定要 run id 围栏，留给 r2-finish）。 |
| 4.12 / 4.13 席位 | u3 收勾已在。 | 席位号 `.6 → 1` spring-soft 弹起；收勾触感由 settle() 统一给。 | 计数器 N/M 滚动未做（落地页没有 N/M）。 |
| M14 迁移 | 仓库里 20 处页面直调 `wx.vibrateShort`，强度各自挑。 | 全部换成四档：play（到站 confirm、券 / 撕包 / 长按结束 hit、其余 tap、「咔哒」由 heavy 降为 confirm——一屏只留一处 heavy）、roam、fabu 拖块（抬起 tap / 换槽 tap / 落下 confirm）、落地页选 chip tap、券码核销（页 + 场景）confirm、城市签 tap。`fabu/index.js` 行数不变（基线行锚不动）。台账构建器认得这四个名字，按同一能力 `vibrateShort` 登记，external 分母不变。 | — |

## 三、条目表

截图在 `shots/`：`<ID>-before-strip.jpg` / `<ID>-after-strip.jpg` 是 8 帧并排的时间条（每帧下方标着动作后的毫秒数），`<ID>-reduced-strip.jpg` 是打开「减少动态效果」后的 4 帧；`<ID>-after-metrics.json` 是同一次截屏的帧率与布局指标。before 用 staging `941898f5` 的小程序包，after 用本分支的包，同一个后端、同一个库、同一身份、同一状态。每张条我都逐帧看过。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| 地基 §2 / §2.6 / M14 / M7 | fixed | 见二.1 | `style/tokens.wxss`、`style/merchant-light.wxss`、`utils/motion-spring.js`、`utils/motion.js`、`utils/morph-success.js`、`utils/wizard-morph.js` | 单测 12 条；`ds-theme-sync-check.sh` ✓；hardcode gate 对 staged diff ✓ | 真机震动手感（rig 没有马达） |
| 棘轮 169 → 146 | fixed | 见二.2 | 七个页面 / 组件的 wxss / wxml / js | `motion-property-ratchet` ✓（146 = 146）、`reduced-motion-coverage-contract` ✓、`theme-look` ✓ | — |
| M-SETTLE（4.6 第 1 步） | fixed | 印章 / 光环 transition + 整屏节拍 + 分数数上去 + 900ms 一次彩旗与 hit 触感 | `subpackageJourney/pages/settle/*` | `M-SETTLE-before/after-strip.jpg`（after：+397 印章与眉题、+696 光环外扩 + 分数 0、+999 分数 88、+1297 分数 100、+1600 按钮上浮、+1997 彩旗）；`M-SETTLE-reduced-strip.jpg` 四帧静止终态；metrics after 60.1 fps、0 处 >50ms 断帧；真实通关（9004 在 990014 两站答对「3」，战报 100 分、第 1 名） | 彩旗与触感同一拍在真机上的观感 |
| M-SETTLE-RANK（4.6 第 2 步） | fixed | 榜单行 `--i` 错峰上浮，自己那行 1.02 落定 | `subpackageJourney/components/rank-board/*` | `M-SETTLE-RANK-before/after-strip.jpg`（after：+255 行半透明、+407 到位，名次 0 → 1 滚动是原有的） | 队内 / 队伍页签（rig 没有组队主题，r2-finish 同样没截到） |
| M-ENDING（游玩结局光环） | fixed | 两圈光环 transition | `pages/play/index.wxss` | `M-ENDING-before-strip.jpg`（staging 关键帧版）/ `M-ENDING-after-strip.jpg`（新主题 990016 真实通关） | — |
| M-SHARE（分享层入场） | fixed | 卡 → 徽标 → 文字 transition | `components/cy/share-card/*` | `M-SHARE-before/after-strip.jpg`（两版 +62…+712ms 的进场帧一致，after 没有关键帧）；`M-SHARE-reduced-strip.jpg` | 小程序码（rig 出码接口回 500，与 r3-share 一样） |
| M-LANDING（分享落地页） | fixed | `--pm` 入场 + 静态光环 / 光点 + 席位弹起 | `subpackageJourney/pages/landing/*` | `M-LANDING-before-strip.jpg`（关键帧 420ms 内播完，截屏窗口里已是终态）/ `M-LANDING-after-strip.jpg`（+92 只有事实行、+248 同行两端浮起、+396 昵称与药丸、+549…+697 玩法卡与规则行依次到位）；`M-LANDING-reduced-strip.jpg`；metrics after 47 fps、1 处 219ms 断帧（玩法卡是 `/choices` 回包后才挂上的,那一帧是渲染新节点,不是动画掉帧） | 领到席位的弹起（rig 没有席位码夹具；u3 同样只有桩） |
| M-TEAM（席位圈） | fixed | `--pm` 错峰入场，空位不呼吸 | `pages/team/detail/*`、`pages/team/join/*` | `M-TEAM-before/after-strip.jpg`、`M-TEAM-reduced-strip.jpg`（真实队伍 501，`/api/team/info`） | 加入页（非成员 9002 打开，未另截） |
| M-GIFT（领取礼品） | fixed（未截图） | `--pm` 入场 + 静态光环 + 领取 M7 | `subpackageMember/market/gift/*` | 单测 `r2-join`、`qx-u3-motion` ✓ | **rig 没有礼物码夹具**（r2-join、u3 同样没有），没有截图 |
| M-AINPC（AI 店铺角色四步介绍） | fixed | 八组关键帧 → 每步起点 / 终点 transition + 四拍 | `pages/merchant/decor/ai-npc/*` | `M-AINPC-open-before/after-strip.jpg`（打开）、`M-AINPC-before/after-strip.jpg`（点「下一步」进第 2 步：after +298 手在 -22°、+507 8°、+802 -18°、+1099 回 -6°，气泡 +507 后冒出）；`M-AINPC-open-reduced-strip.jpg`；metrics after 61.1 fps、0 断帧 | 真机手感 |
| M-CHAPTER（章末） | 核对（只加触感） | 溶解开始 `hapticConfirm` | `pages/play/components/chapter-flip/index.js` | `M-CHAPTER-before-strip.jpg`（两章主题 990015 真实到站：+1208 完成卡、+5803 卡在、+6402 溶解、+7204 三棱锥）；触感 rig 看不见 | 真机 |
| M-HUD（4.1） | fixed | HUD 两行入场 | `pages/play/index.{js,wxml}`、`pages/play/play-topbar.wxss` | 单测 `play-ui-contract`、`r2-play-20261110`、`theme-look` ✓；截屏条里 M-ENDING-after 的开局段可见（HUD 行与故事屏同时亮起） | 真机 |
| M14 迁移 | fixed | 见二.3 | 11 个 js | 相关单测 787 条通过（`qr-scene-page-parity` 沙箱改为把 `utils/motion.js` 接回震动记录器）；台账重建计数不变 | 真机震动 |
| IMPACT 其余行 | 未做 | — | — | — | 见待你决定 |

## 四、门禁（最终头 `1b120d7b`，台账 `e5649344` 重建）

| 门禁 | 结果 |
|---|---|
| 小程序全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | **17150 条，17143 通过，7 失败**。7 条全部是 qx-s2 Cross-lane 已记、lane 自身分支上就红的:`all-page-back-stack-contract`、`component-wxss-selector-lint`、`custom-nav-page-surface-contract`、`no-system-actionsheet-contract`、`no-system-toast-contract`、`page-cleanup-audit`、`press-feedback-resolves-contract`（都是 r2-finish 结算页 / rank-board 与 r2-play 的 toast）。qx-s2 时的另外 3 条（`motion-property-ratchet`、`reduced-motion-coverage-contract`、`theme-look`）本 lane 修绿。 |
| 触碰到的单测 | 地基 12 条;棘轮批 83 条;触感批 787 条;台账 `action-ledger.test.js` 48/48;沙箱补桩后 `guest-browse-login-on-write` / `merchant-npc-voice-size-contract` / `qx-a7-share-tint` / `nav-theme-surface-contract` 159 条——全部通过 |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | `UI-GATE-0:PASS`（U1–U5、A11Y、Legacy 全 PASS;没有新增基线条目;`fabu` / `play` / `coupon-qr` 行数与基线一致,行锚不动） |
| 台账 | `build-action-ledger.js --write`:5936 个控件不变;构建器把触感四档按 `vibrateShort` 能力登记后,走 `settle()` 的 5 个 tap 与 3 条事件合同从 state / read / write 归 external（367 → 372、local tap 2612 → 2607、local 事件合同 2861 → 2858），`action-ledger.test.js` 计数按重建实测更新并附日期注释;重建后 `git status` 无 diff |
| 主包 | `mainpkg-size-report.js`:3,297,650 字节 × 0.633 ≈ **2038.5 KiB**（基线 2030.7,+7.8 KiB:token 3.2 KB、motion-spring 3.4 KB、motion.js 2.0 KB、celebrate / morph-success / wizard-morph 小改;< 2048,余量 9.5 KiB） |
| keyframes 棘轮 | 实测 146 = 上限 146（集成后是 169） |
| `scripts/ds-theme-sync-check.sh` / `ds-hardcode-gate.sh` | 一致 / 通过（新增 wxss 行只用 token;唯一字面是彩旗里的白色,带 `ds-ok`） |
| 后端 | 没有改动,没有跑 Java |

## 五、rig 与证据怎么来的

- 走查 rig：`claude/cloud-walk-20261101-rig` 独立 worktree，叠 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（占位 OSS 值，不是凭据）、club 分支的 `harness-patches.diff`、u1 的 `rig-wxs-event-shim.diff`。后端 jar 用 staging `941898f5` 构建（本 lane 不改后端）；before 包是 staging 的小程序包，after 包是本分支的（`rig/rebuild-bundle.sh <sha>` 只重打小程序包、重启 rig web）。
- 夹具（`rig/fixtures.sh`，全部真实接口，例外同 qx-s0 标注）：staging 的 `free_orient_theme.py` 建自玩主题 990014（before 通关）与 990016（after 通关）；r2-finish 的 `two_chapter_theme.py` 建两章主题 990015；qx-s1 的 `issue_referral.py` 签真实推广凭据（已从脚本里脱敏）；r2-join 的 `seed-team.sql.txt` 建队伍 501（RIG-DB-ONLY，同 r2-join）。
- 帧条：u3 的 `rig/frames.mjs`（CDP 截屏流）加了 `tryTap` / `type` / `loc` 三个步骤和 `Performance.getMetrics` 前后差值 + 合成帧间隔 > 50ms 计数，写成 `<ID>-metrics.json`。场景在 `rig/scenarios/`，运行脚本 `rig/run-before.sh`、`run-before2.sh`、`run-after.sh`。
- 通关路径：开启定位 → 开始游戏 → 第 1 站「我已到达此站」→ 继续探索 → GPS 移到南京东路（31.238, 121.484）→ 第 2 站「开始任务」→ 答「3」→ 提交。`rig/play-driver.mjs` 是探路用的步进器。
- 帧率是 headless Chrome 的合成帧数 / 时间窗（60 封顶），只能说明「没有掉帧的编排」，不能替代中端安卓真机。

## 待你决定

1. **AI 店铺角色介绍的多拍动效**（挥两下 / 摆两下 / 闪两下）：关键帧不能新增，我用页面按拍翻 class（四次 setData）来做。默认保留这个做法；如果你想要更顺的循环（比如一直挥），就要批准放宽棘轮或改 Lottie。
2. **落地页光环与连线光点**：原来是无限循环，MOTION.md §1 不许列表 / 表单 / 落地页放装饰循环，我改成静态一圈 + 三颗固定光点。想要它动起来同样需要放宽棘轮。
3. **开局 CTA 的收勾（4.1 第 0 拍）**：开局按钮不是 `cy-btn morph` 宿主，接上要改 r2-play 的开局链路（开始 → 定位 → 第一屏）。默认没接；建议 r2-play 回合接。
4. **到站的 HUD 进度药丸 morph（M17）与雷达脉冲（4.2）**：需要在 play 分包新建 `morph-pill` 组件并改 HUD 结构，主包不受影响但 play 分包接近上限；默认没做，列为下一波 r2-play 的工单。
5. **IMPACT 两条无主项**（结算后的「下一个主题」推荐卡、分享的回顾视频）：服务端没有推荐接口、没有视频生成器，按「没有服务端事实就不做」没有动。
6. **结算第 2 步「自己那行离屏钉在底部」与页签横滑**、第 3 步星星逐颗触感、提示「剩余免费」计数滚动：都是小项，默认留给下一波；如果你要本波补，告诉我优先级。

STATUS: PARTIAL - 动效地基、棘轮回 146、§4 节拍核对与 M14 迁移都已做完并有 rig 帧条;未做的是 M17 到站进度药丸 / 雷达脉冲、开局 CTA 收勾、榜单钉底与横滑、礼品页截图(rig 无夹具),见待你决定;全量单测剩下的 7 条红都是 lane 自身的旧问题
