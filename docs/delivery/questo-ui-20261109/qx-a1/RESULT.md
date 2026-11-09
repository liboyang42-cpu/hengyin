# qx-a1 · 游玩页（2026-11-09）

Branch `claude/qx-a1-20261109`, base `origin/master` @ `2bb811437`. Draft PR into `master`.

## Items

| ID | Status | What changed | Files | Verified | Not verified on a real phone |
|---|---|---|---|---|---|
| V3-06 | fixed | 城市定向的地点卡底部不再有「在地图中查看路线」。自由探索（站内没有地图）保留「导航去这里」这一行。 | `pages/play/index.wxml` | Rig shots `V3-06-before/after`; unit test `qx-a1-play-walkthrough` | — |
| V3-10/53 | fixed | 内嵌玩法现在是故事流里真正的一块：高度跟内容走，不再撑满一屏、不再竖向居中，也没有台面底色或卡片底。上面的剧情紧接着，往上滑就能回看。滚动对准的是块里一个不可见的锚点，它让出顶栏和渐隐，所以块本身不用再垫一屏高的上边距。抛硬币、罗盘、喊声、计步把底色和整屏上边距改成变量，内嵌时透明。整块屏本身就是玩法的那几个（摇一摇撞球、变色就点、倒计时盖屏、抽卡、预言、秒表、找物、相册）在流里只放一张入口卡（站名 + 「打开」），点开整屏；关掉回到卡，这一站的玩法不退出，能继续就接着走故事。 | `pages/play/index.wxml`, `index.wxss`, `index.js`（只有单行挂钩，行号不变）, new `pages/play/utils/story-kit-card.js`, `utils/r5-player-runtime.js`, `components/playkit-{coinflip,compass,shout,walk}/index.wxss`, `pages/publish/temp/index.wxss`（预览的同组变量，契约要求两边逐条一致） | Rig shots `V3-10-before/after`（硬币）、`V3-10-53-scrollup-*`（往上滑看到前面的剧情）、`V3-10-shake-*`（摇一摇 → 入口卡）; unit tests | 入口卡点开后的整屏样子（rig 用的就是原来的整屏组件，没改）；真机上块内锚点的 `scroll-into-view` 对齐；罗盘、喊声、计步在真机上透明内嵌的样子（rig 只拍了硬币） |
| V3-11 | fixed | 相册不再塞进故事流（原来是 `position:fixed` 的白色全屏被祖先的 transform 困成一张白卡）。现在流里是入口卡，点开整屏白色相册。逻辑和 V3-10 同一条。 | same as V3-10 | Unit tests (`playKitState` album → card) | **Rig 拍不到**：故事流里相册一铺开，无头 Chrome 的渲染进程就崩溃（`Target closed`），master 和本分支都一样（`album-glass` 每帧逐列 drawImage + 4 张离屏画布）。属于 HARNESS 限制。没有 before/after 截图，需要真机看。 |
| V3-13/14 | fixed | 雷达右下角的收起钮展开时箭头朝上（原来朝下）。雷达底图固定用漫游的那套深色样式（`playerMapStyle`），不再跟主题外观换成纸本或 2077；大地图和完成页回放照旧跟外观。 | `pages/play/play-topbar.wxss`, `index.wxml`, `index.js` | Rig shots `V3-13-14-before/after`（箭头）; unit tests | 真实底图样式：rig 的地图是画布替身，看不到瓦片样式 |
| V3-15 | fixed | 故事流只放故事。没写内容的章节不再补「这一章还没有写下的内容。」这句系统提示。本章还没做的延伸站（编号 ≥ 100），以及没有块流的旧章节里两站以上的小任务，不再出现在正文里，而是收进故事流末尾一行「延伸任务 N ›」，点开半屏一行一站，点一行打开那一站的地点卡。 | `pages/play/index.js`（单行挂钩）, `utils/story-kit-card.js`, `index.wxml`, `play-topbar.wxml/.wxss` | Rig shots `V3-15-before/after`, `V3-15-sheet-after`; unit tests | 业主截图里那句提示具体是哪一句没法核对（见 needs owner） |
| V3-16 | fixed | 背包按原型 v10.3 改：装载是一格一块的方块（用掉的白块，满了变警示色），上限 24 格，超过还是进度条。「随身物品」下面没东西时是同一张卡底的空态（背包图标 + 「还没有物品」），不再是一行孤零零的灰字。 | `pages/play/theme-backpack.wxml/.wxss`, `index.wxml`（`tbAttr.slots`） | Rig shots `V3-16-before/after`; unit test (wxs `slots`) | — |
| V3-17 | fixed | 入场码半屏去掉左下角的返回钮，关闭走右上角 × 和下拉。半屏颜色用 a2 的 token，本 lane 不改。 | `pages/play/components/scene-qr-ticket/index.{wxml,wxss,json}` | Rig shots `V3-17-before/after`; unit test | 码在 rig 里只画了中间的 logo（码图用的是占位图） |
| V3-21 | fixed | 黑色半屏里的图不再垫灰底：地点卡封面（没有配图的故事站原来是一条灰带，有图时加载前也是灰块）、背包物品图（透明 PNG 会露出灰圆）、线索图改成贴卡边直接放。 | `pages/play/index.wxss`, `theme-backpack.wxss` | Rig shots `V3-21-before/after`（地点卡）、`V3-21-clue-*`; unit test | 业主截图是哪个半屏无法确认（见 needs owner） |

## How the evidence was made

- **Rig:** `claude/cloud-walk-20261101-rig` from a separate worktree, with `harness-patches.diff` from `claude/cloud-walk-20261101-club` applied. The candidate was `master@2bb811437` for the before shots; the after shots used the same rig with the bundle rebuilt from this branch (`CAND=<repo> mpweb/build.sh`). Docker Hub returned 429, so the pinned MySQL/Redis digests were pulled from `mirror.gcr.io` and tagged. Same digests, rig-only.
- **No real theme run:** the rig's `chains.py player` fails on master at `sale_close` (`自由探索只开放内容所属俱乐部的场次，不能跨俱乐部售票`), so there is no real topic or registration to play. All shots therefore use the play page's own **preview path** (`pages/play/index?preview=1` + `applyPreviewData`, the same entry the editor's 试玩 uses). The data comes from `rig/gen.js`, which uses `buildPreviewKit` from the product.
- **State set by `eval` (rig only, disclosed):**
  - V3-06/21: `isPreview:false` before `openSheet`, because the preview hides the navigation row.
  - V3-16/21-clue: a `themeBag` fixture (capacity 6, used 0, one clue).
  - V3-17: `openScene('qr-ticket')`, then the component state set to `ready` with a placeholder code image, because the code endpoint has no registration.
- The scripts are in `rig/` (`all.sh before|after`, `play.mjs`, `gen.js`).

## Tests

- New: `tests/unit/qx-a1-play-walkthrough.test.js` (9 tests).
- Updated to the new contract, each with the reason in a comment:
  - `play-chapter-story-blocks-contract`, `r12-k8-play-fixes`: anchor `chapter-game-` → `chapter-kit-`.
  - `play-estimate-result`: the top-padding rule now sits on the anchor.
  - `playkit-present-inline-contract`: `cy-playkit wx:else` after the card branch.
  - `playkit-story-vars-dream-path-contract`: no system sentence.
  - `round9-m4-map-look`: the radar uses the roam dark style.
- `node --test` on all 381 unit files that touch `pages/play`, `scene-qr-ticket`, `theme-backpack` or `publish/temp` styles: all pass after the updates. The first run had 11 failures, all fixed: 3 were the stale action ledger and 8 were the pinned contracts above.
- `node scripts/uiaudit/build-action-ledger.js --write`: the ledger was rebuilt.
- `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`.
- **Main package:** +1 small util (`story-kit-card.js`, about 3.6 KB) plus a few CSS lines on the play page. No new assets.

## Cross-lane

- **a5 (`pages/publish/temp`):** `.cg-pv-inline-kit` got the same five variable changes as `.chfull__kit` (`--pk-card-h/-h-tall:auto`, `--pk-card-radius:0`, `--pk-card-pad`, `--pk-card-bg:transparent`). The existing contract test requires the author preview and the player story flow to use identical inline variables. No other line in that file changed.
- **a2 (tokens):** V3-17's sheet colour is a2's `--cy-comp-sheet-bg` change. This lane only removed the back button.

## needs owner

1. **V3-15:** the screenshot behind 「这种提示句」 is not in the brief. I removed the one system sentence the client injects into the story (「这一章还没有写下的内容。」) and moved extension stops into the 「延伸任务」 row. Sentences an author wrote into the chapter text are content and stay as written. Default implemented: the row appears when the chapter has an unfinished stop numbered ≥ 100, or a no-block chapter has two or more unfinished stops.
2. **V3-21:** which sheet the grey rectangle was in is not stated. Default implemented: three places lose their grey backing (node sheet cover, item images, clue images). Point at the screen if it was another one.
3. **V3-13/14 「点击之前是向下的」:** read as "currently it points down, it should point up". Default: the arrow points up while expanded. The compass state still has no fold button; tapping the compass opens the map, as before.
4. **V3-10 which games open full screen from a card:** default is album, ball shake, reaction, countdown, predict, random draw, stopwatch and hidden object. Every other game is inline. The list is one constant in `utils/story-kit-card.js`.

STATUS: PARTIAL - V3-11 album has no rig screenshots (the album scene crashes the headless renderer on master and on this branch); the inline-card logic is covered by unit tests only and needs a phone check.
