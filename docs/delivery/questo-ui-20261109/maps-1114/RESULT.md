# 地图波次 maps-1114 结果

分支 `fix/maps-1114`(自 origin/master),依据 `audit/roam-review-1114` 的 RESULT.md 第 C 节与 owner 2026-11-14 规则。

## 规则落地

- `layerStyle` 一律是**绑定序号**:white 1 / black 2 / paper 3 / 2077 4,不是控制台 styleId。
- 仓库 `utils/map-style.js` 仍是 `subkey: ''`、四个序号 0;序号只在上传拷贝里由 `scripts/release-map-style.js` 填,仓库里没有任何 key。
- 自由图(漫游、附近):玩家可切白 / 黑 / 纸本 / 2077,默认黑。
- 城市定向路线与自由探索模板:用发布时的主题外观(`kindForLook`:纸本 paper、夜城 2077、其余 black)。
- 商家 / 编辑器 / 创作者本人预览:white。

## C1 样式切换

| 位置 | 改动 |
|---|---|
| `utils/map-style.js` | 新增 `KIND_ITEMS`、`freeMapKind`、`saveFreeMapKind`(`wx` 存储 `cy_map_kind`,读写都在 try/catch,读不到或值不认识回 black)、`kindForLookJson`、`routeMapKind(look, preview)` |
| `utils/map-style-switch.js`(新) | 切换的共用实现:先撤图(`fmStyleReady=false`)→ `wx.nextTick` 重建,沿用 play 的 `fmStyleReady` 做法;`fmTone` 同步 `mapToneFor`;`onShow` 里 `_syncMapKindPref` 跟随别页的修改 |
| 漫游 `pages/roam` | 设置页加「地图样式」四选一行(与现有开关行同版式,无说明文字);起始图 / 主图 / 结算图三个 `free-map` 都带 `wx:if="{{fmStyleReady}}"` 与 `tone`;重建后 `_onMapKindRebuilt` 重取 `_mapCtx`,迷雾用新图的 MapContext |
| 附近 `subpackageRoam/nearby` | 右下角加一枚「地图样式」图标控件,点开复用现有 `cy-option-sheet`(同「范围」);底部角控件由两枚变三枚,`map-team-page-contract` 同步改为三枚 |
| `pages/topic/index` | 玩家看路线用 `lookJson`(info-to-user 随主题对象带出)的发布外观;商家视角(`isMerchantView()`)或本人(`isOwner===1`)仍是 white |
| `pages/square/components/route-map` | 新增 `preview`(默认 true = white,即现有预览截图流程不变)与 `look` 属性;玩家展示时传 `preview=false` 才按外观取底。仓库里它目前只有活动选择器的预览截图一个调用方,所以行为不变 |
| `pages/play` 雷达 | 与另两张图一样跟外观(`fmSubkey/fmLayerStyle/fmTone`),去掉 `radarMap`;同步改了 11-06 固定深色的两条旧断言 |
| `components/cy/route-thumb` | 跟玩家在漫游里选的底图;浅底把白线换墨色(`toneLines`);`attached` 时读定 |

## C2 真地图快照

新增 `utils/map-snapshot.js`:`takeMapSnapshot`(等 `bindupdated` → `MapContext.takeSnapshot`)、`loadImage`、`drawCover`(居中 cover 裁剪)。任何一步失败都 reject 并带 `mapSnapshot` 标记,调用方走各自的重试态,**不画假地图顶替**。

- **漫游分享足迹卡**(`pages/roam`):预览里的 `rsh-map` 换成真实不可操作 `<map id="rshMap">`(`include-points` + polyline + 店铺圆点,数据来自 `routeGeo`,与原手画图同一条已过位置隐私闸的轨迹);导出时截该图 `drawImage` 进现有分享 canvas。失败走现有的导出失败提示(「地图未生成，请重试」,再点即重试)。没有足够轨迹时仍是「今日足迹」空态文字。删掉 `rsh-route / seg / poi / end` 手画节点与样式。
- **会话足迹卡**(`subpackageRoam/session`):分享半屏里在生成期间挂出真实 `<map id="ssMap">`,截图后铺进导出卡与卡片预览;失败走页面已有的 `cy-error`「重新生成」,文案「地图未生成，请重试」。`segs / poiDots` 与对应 wxml / wxss 删除,`...this._route(s)` 改为显式 `geo:`。
- **`pages/topic` route-sketch**:已核实 `routeReal = sketchPoints.length > 0`,有坐标时本来就是真 `<map>`,示意图只在没有任何有效坐标时出现,所以未改代码。
- **未碰**:`pages/play/utils/route-card.js`(W4 负责)。

## DARK_MAP.md

整份改写为绑定序号模型(white 1 / black 2 / paper 3 / 2077 4,`utils/map-style.js` + `scripts/release-map-style.js`),删除全部 `MAP_DARK` 与「填 styleId」说法;`docs/发版checklist.md` 地图一条补上序号。

## 门禁与台账

- 新增单测 `tests/unit/maps-1114-style-switch.test.js`(10 条:序号、存储容错、look 映射、切换重建、各页接线、快照失败路径、DARK_MAP);`roam-session-share-card` 补快照失败与「不再手画」两条,并给 mock 加 `createMapContext / createImage / drawImage`。
- 跟随修改的既有断言:`map-team-page-contract`(三枚角控件)、`round9-m4-map-look` / `qx-a1-play-walkthrough`(雷达跟外观)、`release-map-style`(漫游 / 附近改读 `initialData`)、`play-monochrome-contract`(店铺点色不读历史记录)、`mini-static-evidence-d192`(route-map 锚点 49→53)、`scene-twin-allowlist`(`roam-session` 双份债已还,按其 exitIntent 删除)。
- **ui-integrity 基线**:既有 roam / nearby / topic / route-map 登记点只因在其上增行而位移,按 difflib 对 origin/master 逐行映射重锚(调用行逐字相同,理由不变);`session/index.js` 的动态 setData 改为显式字段,那条 u4Dynamic 登记出账 62 → 61(ratchet 同步);**不新增任何登记、不隐藏任何发现**;`u1Variable / u4Dynamic / u5Silent` 三个 `keyDigest` 按门禁 `baselineKeyDigest` 算法重算;文件顶部有 2026-11-14 日期注释。
- 已重新生成 `scripts/uiaudit/action-ledger.json`(6051 控件);`route-matrix` 重生成后无差异,`--check` 与 `coverage-gate` 通过。
- 没有改 `ci/architecture`、工作流;没有运行 `ci/xcx-check.sh` 自证。

## 需要真机 / owner 确认

1. `takeSnapshot` 在个性化 subkey 下是否带样式、安卓 / iOS 同层渲染差异、`cy-sheet` 遮挡时是否截成空白(会进入「地图未生成，请重试」)。
2. `pages/topic/index` 依赖 info-to-user 返回的 `lookJson`(`CmsTopic.lookJson` 未标 `@JsonIgnore`,源码核对会序列化);没带时按 black,真机核一次纸本 / 夜城主题。
3. 附近页角控件由两枚变三枚是本次的取舍(owner 要求放在现有控件里);若要保持两枚,可改为把样式并入「范围」半屏。
4. 重建地图期间漫游主图会短暂空一帧(`layer-style` 无法热改的代价)。

STATUS: DONE
