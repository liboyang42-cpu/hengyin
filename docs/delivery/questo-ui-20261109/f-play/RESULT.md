# f-play · 游戏能玩（launch 修复线，2026-11-11）

- 分支 `claude/f-play-20261111`，从 `origin/launch/questo-20261110`（`afa90ffd`）切出。草稿 PR #788 指向 `launch/questo-20261110`，没有合并、没有标记 ready。
- 范围：AUDIT-GAPS 阻断 C2 + 缺口组 5（V3-04、V3-05、V3-07/08/09、V3-11、V3-53、V-03、顶部缺图），在走查 rig 里按三条进入路径走一遍，走到的缺陷都修掉。
- 没有改后端、迁移、支付、生产配置；没有跑 `ci/xcx-check.sh` 与 `ds-hardcode-gate-selftest.sh`；没有密钥。所有改动都在 `pages/play` 分包里。
- 上一次运行在推送前碰到用量上限，origin 上没有 `claude/f-play-20261111`。本次从 launch 头重新开始，上次的结论只作为线索。

## 本线修掉的缺陷

| ID | 现象（rig 实测） | 改了什么 | 文件 | 证据 | 单测 |
|---|---|---|---|---|---|
| V3-04 摇一摇 | 硬币、骰子、弹球三种玩法在 rig 里都能摇（新加了加速度计接口 `MP.shake`）。结构缺陷：每个玩法各自调 `start/stopAccelerometer`。选「全屏」后，流里和整屏会同时挂着同一个硬币 / 骰子：摇一下两份一起抛；整屏关掉时又把传感器整个关了，回到流里再摇没有反应 | `play-shake.holdAccelerometer`：全局只注册一个监听，第一个用的开传感器，最后一个退出才关；读数只交给最上层（最后接入）的那份。硬币、骰子都改走它 | `pages/play/utils/play-shake.js`、`components/playkit-coinflip/index.js`、`components/playkit-diceroll/index.js` | `shots/V3-04-coin.jpg`（内嵌硬币摇出正面）、`V3-04-dice.jpg`（内嵌骰子摇出 5）、`V3-04-pinball.jpg`（入口卡 → 整屏 → 摇一下开始 → 倾斜移动 → 撞到 5 次） | `f-play-20261111`：共用传感器、硬币 / 骰子「内嵌 + 整屏」各一条 |
| V3-05 进度游标 | 顶栏药丸「1/2」下面那条进度线用的是强调色 `--lk-a`。星尘默认外观里它是 #E9E2D0，压在 #F2F2F0 的药丸上对比度只有 1.15；25 套外观里有 20 套低于 2:1，基本看不见 | 进度线改用药丸自己的字色 `--lk-bg`（和数字一样的对比度），线宽 4 → 6rpx | `pages/play/play-stage.wxss` | `V3-05-before.jpg`（0/2、1/2、2/2，看不到线）、`V3-05-after.jpg` / `-after-full.jpg`（1/2 时半条黑线） | `f-play-20261111` V3-05 |
| V3-07 通关后回看 | 城市定向走完后，完成页点「继续」直接回票夹，没有地方往上滑重读故事 | 完成页 / 勋章屏的「继续」先回到故事流的只读回看：打开最后一个读完的章节，滚到末尾，往上滑能读完整个故事。打不开回看时（没走完、自由探索、试玩、一章都没读完）照旧回详情 | `pages/play/utils/play-stage-pops.js`（`_reviewAfterFinish`）、`index.js`（`closeFinish` 改一行） | `V3-07-before.jpg`（继续 → 票夹）、`V3-07-after.jpg`（完成页 → 第 2 章末尾 → 往上滑） | `f-play-20261111` V3-07；`r11-g1-finished-reopen` 合同按新行为更新（注释带日期） |
| V3-08 选章能打开 | 选章弹窗放在 `!allDone \|\| canContinueGames` 守卫块里，走完一局后点「切换章节」，数据是开了，弹窗却不渲染。城市定向的「…」里也没有「切换章节」 | 选章弹窗移到守卫块外面；工具菜单的「切换章节」改成 `mode==2 \|\| chapterCards.length > 1` 时出现 | `pages/play/index.wxml` | `V3-08-09-after.jpg`（工具菜单 → 选章 → 选第 1 章 → 第 1 章只读回看） | `f-play-20261111` V3-08；`play-free-explore-no-gather`、`play-redeemed-game-entry` 按新位置更新 |
| V3-09 通关态 | 选章每一行都没有完成态 | 每行副标题写「已完成 ·」；未解锁的行压暗（.4），点了不进。判据和 `onPickChapter` 放行用的是同一个 `chapterReadState` | `index.wxml`、`index.wxss`、`play-stage-pops.js`（`_chapterPickStates`）、`index.js`（打开选章时一并写状态） | 同上（两章都写「已完成」） | `f-play-20261111` V3-09 |
| V3-11 相册整屏 | 试玩里相册一开局，rig 渲染进程就崩溃（staging 记过，当时当作 HARNESS）。本线在 rig 的 setData 上计数，找到了死循环：`advanced-game` 观察器 `show,node.nodeId` → `start` → `hydrate` → 回传会话 → 页面试玩分支给节点挂上新的 `previewKit` → 重新传 node → 再次 `start` | 同一个打开着的节点只开局一次：打开（show 从假变真）或换节点才重开；关掉后清除记号 | `pages/play/components/advanced-game/index.js` | `V3-11-before.txt`（循环的四条 setData 计数和调用栈）、`V3-11-after.jpg`（入口卡 → 玩法弹窗 → 白色整屏相册 1/3） | `f-play-20261111` V3-11；`advanced-game` 相关 10 个测试文件 175 条全过 |
| 顶部缺图 | 主题开场标题卡（`st-intro`）明明有 `topicCover`，画面却是一整屏纯黑。原型 gp-start 在标题后面压着主题封面 | 加一层封面底图：.55 透明、轻模糊、去饱和，上下两端渐隐到底色；没有封面时退回章节封面链，都没有就不画这一层 | `index.wxml`、`index.wxss` | `top-image-before.jpg` / `top-image-after.jpg` | `f-play-20261111` 顶部缺图 |
| 地点卡距离不刷新（走查中发现） | 走到第 2 站（0 m）时，开着的地点卡还写着出发时的「1.7km · 22 分钟步行」 | 定位每更新一次，就重算开着的地点卡的距离和步行时间；文字没变不 setData | `play-stage-pops.js`（`_refreshSheetFacts`）、`index.js`（定位回调加一行） | `station-dist-before.jpg`（0m 定位、卡上 1.7km）、`station-dist-after.jpg`（卡上 0m、步行时间不再显示） | `f-play-20261111` 地点卡距离 |

V3-53（内嵌玩法是故事流里的一块，往上滑能回看）：上一轮 qx-a1 修过。本次 rig 里内嵌硬币、骰子都长在故事流里，高度跟内容走，上方剧情紧挨着（`V3-04-coin.jpg`、`V3-04-dice.jpg`）。没有新缺陷，没有改动。

V-03 黑屏：根因已由 #671 修复（首屏回包被当成换号丢掉）。本次三段 rig 走查里，「开始游戏 → 章节开场 → 故事流」每一步都正常出画面（`path2-1-entry.jpg`），没有卡在黑屏。故事文字逐段淡入的那约 0.7 秒里，只看得到顶栏，这是 MOTION 规定的入场动画，不是卡住。

## 三条进入路径

### 路径 2 · 自玩通行证（本次完整走通）

- 夹具：`rig/free_orient_theme.py`，复制自 staging，加了 `CHAPTERS=2`（两章、每章一站）和 `SKIP_BUY=1`。真实入口包括：建期 → 内容审核 → 玩家同意 → 报价 → 零价自玩通行证。唯一一处 SQL 是 `self_play=1, self_play_price=0`：没有任何写接口能存 ¥0 的自玩通行证，和 staging 相同。
- 步骤（`rig/mkpath.py <topicId> <前缀>` 生成，`rig/drive.mjs` 执行）：主题详情「去票夹」→ 票夹「开始游戏」→ 开启定位 → 主题开场「开始游戏」→ 第 1 章开场（像素数字）→ 故事流 → 继续探索 → 定位到第 1 站，自动到达（到站读数「广场的鸽子一齐飞起」）→ 第 1 章收尾文字 → 第 1 章完成整屏 → 第 2 章 → 第 2 站地点卡 → 开始任务 → 玩法弹窗 → 问答（答 3）→ 本局完成（绿）→ 通关卡（全部完成 · 2 / 用时 / 100 解谜分）→ 完成页 → 继续 → 故事流只读回看 → 「…」→ 切换章节 → 选第 1 章。
- 结果：通过。截图 `path2-1-entry.jpg`、`path2-2-stations.jpg`、`path2-3-finish.jpg`，加上 V3-07/08/09 的对比图。
- 本路径修掉：顶部缺图、地点卡距离、V3-05、V3-07、V3-08、V3-09。
- 「去票夹」说明：rig 里用鼠标点详情页的主按钮没有触发跳转（落点确实是 `.hero-cta`），所以这一步改用 eval 调页面自己的 `onHeroCta()`。这是 rig 的点击合成问题，需要真机确认一次。

### 路径 1 · 城市定向场次票（本次没有重新走通）

- 上一次运行在 rig 里走通过：票夹 → 开始游戏 → 领队开局 → 各站 → 问答 → 本局完成 → 通关卡。但那次的 rig 和夹具都没有留下来。
- 本次重新铺夹具时卡住了：
  - rig 自带的 `chains.py player` 仍然停在 `sale_close`（「自由探索只开放内容所属俱乐部的场次，不能跨俱乐部售票」），和 qx-a1 / staging 记录的一样。
  - 改用俱乐部 7001 对本线的城市定向主题开团，`/api/club/open-session` 成功了（activity 15）。但主题没有票，开出来的活动也就没有活动票，报名被拒（「请先加入俱乐部再报名」「请先补全参与人姓名」）。要建带场次票的城市定向主题，还得补一套票务夹具，这次没有做完。
- 场次票进入游玩页之后，用的是同一张游玩页：故事流、站点、问答、本局完成、通关卡、完成页。这部分本次在路径 2 走过，本线的修复也都对它生效。场次票特有的环节（领队开局、集合、入场码）这次没有验证。
- 需要真机：一局真实场次票，从领队开局一直走到通关后回看。

### 路径 3 · 自由探索（报名被开售闸挡住；游玩页用试玩入口走）

- 真实报名被 `SALE_SNAPSHOT_MISSING`（「该主题的开售信息还没准备好」，`TopicSaleFinalizationServiceImpl`）挡住。这是 rig 夹具缺开售快照和主理审计，不是产品缺陷。闸没有放宽，本次也没有补齐这份夹具。
- 游玩页本身用页面自己的试玩入口走了一遍（`?preview=1` + `applyPreviewData`，`MODE=2`）：卡包 → 撕开 → 三格探索节点 → 工具菜单（切换章节 / 我的权益 / 旅程手记）→ 节点卡「到店打卡」。截图 `path3-free-explore-preview.jpg`，没有发现缺陷。单章主题点「切换章节」照旧回到卡包。
- 需要真机：真实自由探索的报名、核销和换章。

## 需要真机看的

1. 摇一摇的手感：rig 只模拟了「超过阈值的读数」，阈值（2.65 g、900 ms 防抖）合不合手要在真机上试。重点看：选「全屏」后摇，只有整屏那份在动；关掉整屏后，流里那份还能摇。
2. 弹球的倾斜物理和撞击计数，rig 只喂了正弦倾斜数据。
3. 相册整屏：rig 里玻璃翻页用的是占位图。另外，真机上原来的「死循环」是否也会发生，取决于微信是否会在父页 setData 时重新传入同一个 node。修复对两种情况都成立，但「修前在真机上卡死」这件事没有在真机上复现过。
4. 通关后回看：章节旁白在回看时是否重新播放；从回看退出（返回键）的路径。
5. 进度游标在 25 套外观下的观感（本次只截了星尘默认外观）。
6. 路径 1 场次票全程、路径 3 真实报名全程。

## 门禁

- 新单测 `tests/unit/f-play-20261111.test.js`：10 条，全部通过。修前跑同一文件，共享传感器的 3 条都是红的。
- 按新行为更新的旧断言，每处都带日期注释：
  - `r11-g1-finished-reopen`：closeFinish 先尝试回看。
  - `play-free-explore-no-gather`：「切换章节」的出现条件。
  - `play-redeemed-game-entry`：按守卫块自身的条件定位。
  - `action-ledger`：
    - 选章控件 id 行号 535 → 537。
    - `openStackOrChapters` 的测试夹具补了 `_chapterPickStates`。
    - closeFinish ×2 的 tap 和勋章屏 bind:close 改为 external：external tap 374 → 376，本地 tap 2647 → 2645，本地事件合同 2888 → 2887。
  - `play-mechanisms-contract`（O-01）没有改测试。定位回调里的新调用加了 `typeof` 守卫，单独抽出这个回调运行时不会因为缺这个方法而报错。
- 跑过的测试：
  - `pages/play` 相关 243 个测试文件（名字含 play / chapter / qx-a1 / r2-play / l-play / r11 / overlay / press-feedback / ui-contract）：除 action-ledger 外 2280 条全过；action-ledger 48/48。
  - 动作台账：`build-action-ledger.js --write` 重建，共 6007 个控件。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js`：**UI-GATE-0:PASS**。U5 基线里「已完成里程碑」那条静默读取只是行号位移（8894 → 8895），digest 按同一算法重算，并写了注释。
- 没有新增 `@keyframes`：对底座的 diff 里，新增的 `@keyframes` 只出现在单测的断言字符串里。新的视觉效果全部用过渡或静态样式。
- 主包：`mainpkg-size-report.js --json` 的源码字节和底座相同（3,298,602），因为改动全在 `pages/play` 分包。< 2048 KiB 以 `wechat-release-contract.js` 的编译后口径为准，本线没有改动主包。

## rig

- `claude/cloud-walk-20261101-rig` 单独 worktree，打了这些补丁：
  - club 分支的 `harness-patches.diff`；
  - staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（OSS 用占位值，通过环境变量传入）；
  - qx-c1 的 `rig-app-media-seam.diff`；
  - qx-u1 的 `rig-wxs-event-shim.diff`；
  - r3-share 的 `walk-fake-oss-seam.diff`（`RIG_FAKE_OSS=1`）。
- Docker 镜像源用 `mirror.gcr.io`。jar 从本分支构建（本线没有改后端）。
- 本线新增的 rig 接口（都在 `rig/rig-all-patches.diff` 里，和上面几份补丁合在一起）：
  - 加速度计：`MP.shake(n, gap)` / `MP.tilt(x,y,z)` / walk 步骤 `shake`；只在传感器开着时才派发读数。
  - setData 按微信语义序列化后再写入（`MP.noSetDataClone` 可关闭）。
  - setData 计数探针（`LOOP` 日志），用来定位渲染死循环。
- 脚本：`rig/drive.mjs`（步骤驱动，支持 `previewKind` 试玩数据、`strip` 连拍、`shake`）、`rig/mkpath.py`（路径 2 整局步骤）、`rig/gen.js`（复制自 l-play，加了骰子、相册和 `MODE=2`）、`rig/free_orient_theme.py`（夹具）。各个 `*.json` 是实际跑过的步骤。
- rig 的局限：OSS 图是假图（生成的 SVG，所以有 `getImageData` 跨域报错，属于 HARNESS）；地图是画布替身；点击由鼠标合成。

## 待你决定

1. **完成页「继续」改成先回看**：原来是回主题详情。现在走完的城市定向会先回到故事流末尾，往上滑重读；离开靠返回键。**默认已经这样实现。** 如果你希望「继续」仍然离开，只在「…」里给回看入口，可以把 `closeFinish` 里那一行改回去。
2. **回看时的「继续探索」**：故事流底部的「继续探索」在回看里点下去，会落到走完后的地图（「领取你的城市足迹卡」）。**默认没改。** 你可以选择回看时隐藏它，或者改写成「回到通关卡」。
3. **进度游标用字色**：原型用的是强调色，本线改成药丸字色，保证每套外观都看得见。**默认如此。**

STATUS: PARTIAL — 自玩通行证这条路径完整走通，修掉 V3-04 / V3-05 / V3-07 / V3-08 / V3-09 / V3-11 / 顶部缺图 / 地点卡距离，V3-53、V-03 复核无缺陷。城市定向场次票（夹具缺场次票）和自由探索真实报名（开售快照闸）这次没有重新走通，需要补夹具或真机。
