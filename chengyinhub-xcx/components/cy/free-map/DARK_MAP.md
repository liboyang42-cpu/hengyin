# free-map 个性化底图(绑定序号模型)

## 一句话
所有 `<map>` 共用同一个 `subkey`,样式只靠 `layer-style` 区分。`layer-style` 是**该 key 下的绑定序号**(从 1 起),不是腾讯控制台里的 styleId。

| 样式 | 绑定序号 | 用在 | 上传前环境变量 |
|---|---|---|---|
| white | 1 | 商家与编辑器地图;创作者本人、商家视角预览主题路线;自由图里玩家选「白」 | `CY_MAP_STYLE_WHITE` |
| black | 2 | 漫游、附近的默认底图;玩家搜索地图;星尘 / 末世 / 没设外观的主题 | `CY_MAP_STYLE_BLACK` |
| paper | 3 | 主题外观「纸本」;自由图里玩家选「纸本」 | `CY_MAP_STYLE_PAPER` |
| 2077 | 4 | 主题外观「夜城」;自由图里玩家选「2077」 | `CY_MAP_STYLE_2077` |

序号只在控制台把四套样式**依次绑定到同一个 key** 之后才存在:「个性化地图 → 我的样式 → 使用/应用」,序号看「样式应用列表」。样式详情页看不到它。绑定顺序就是上表的 1–4;顺序变了,环境变量跟着改。

## 仓库里怎么放
- `utils/map-style.js` 的 `MAP_STYLE`:`subkey: ''`,四个序号都是 `0`。**仓库里永远不放 key。** 没填 key 时四种都回到微信官方默认底图。
- 上传前对**上传用的拷贝**运行:

  ```
  CY_MAP_SUBKEY=… CY_MAP_STYLE_WHITE=1 CY_MAP_STYLE_BLACK=2 CY_MAP_STYLE_PAPER=3 CY_MAP_STYLE_2077=4 \
    node scripts/release-map-style.js --dir <拷贝目录> --require
  ```

  `scripts/release-map-style.js` 只改拷贝、拒绝改仓库本身、不打印 key。给了 key 却缺任何一个序号,或两个序号相同,脚本失败。
- key 会打包进前端、可被反编译,控制台上只给它开「微信小程序个性化地图」;WebService / REST 另用一个不下发前端的 key。

## 谁用哪一套
- 自由图(`pages/roam`、`subpackageRoam/nearby`):玩家可在「设置 → 地图样式」(漫游)和右下角地图样式按钮(附近)里选白 / 黑 / 纸本 / 2077,默认黑;选择用 `wx.setStorageSync('cy_map_kind')` 记在本机(带 try/catch,读不到就是黑)。漫游记录里的路线缩略图(`components/cy/route-thumb`)与分享足迹卡的底图跟这个选择。
- 城市定向路线与自由探索模板:用**发布时**选的主题外观,`kindForLook` / `kindForLookJson` 映射(纸本 paper、夜城 2077、其余 black):`pages/play`(含左上雷达)、玩家看的 `pages/topic/index` 路线地图。创作者本人或商家视角看自己的主题路线时用 white(`routeMapKind(look, preview)`)。
- 商家与编辑器:一律 white(`lightMapStyle()`):`pages/publish/fabu`、广场发布里的路线预览 `pages/square/components/route-map`。
- 浅底(white、paper)上白线白圈看不见:`free-map` 的 `tone` 把折线、光圈换成墨色(`toneLines` / `toneCircle` / `mapToneFor`)。没配 key 时 `tone` 为空,配色不变。

## 不能热改
`subkey` 与 `layer-style` 都不支持运行时修改。需要换底图时必须先撤掉 `<map>`、下一拍再按新样式重建:

- `pages/play`:`fmStyleReady`(`pages/play/utils/play-look.js`)。
- 漫游 / 附近:同一模式,见 `utils/map-style-switch.js`(`fmStyleReady` + `wx.nextTick`)。

## 分享图里的地图
canvas 不能截原生 `<map>`。分享足迹卡的做法是:页面里挂一张真实、不可操作的 `<map>`(`include-points` + polyline),`bindupdated` 之后用 `MapContext.takeSnapshot` 取图,再 `drawImage` 进分享 canvas(`utils/map-snapshot.js`)。截不到就显示重试,不会退回画线冒充真图。

## 只有真机能确认
- 四个序号与控制台一致;纸本 / 夜城底图上墨色线、队色、店名小标签清楚。
- 个性化 subkey 下 `takeSnapshot` 是否带样式;安卓 / iOS 同层渲染差异;被 `cy-sheet` 遮挡时是否截成空白。
- 进主题时没有先闪一下别的底图。

## 微信 `<map>` 属性
| WXML 属性 | 类型 | 官方默认 | 最低基础库 | 作用 |
|---|---|---|---|---|
| `subkey` | String | `''` | 2.3.0 | 个性化地图专用 key,不支持运行时修改 |
| `layer-style` | Number | `1` | 2.3.0 | 该 key 下的绑定序号 |

空 `subkey` 才等于官方默认底图;`subkey` 一旦非空,`layer-style: 1` 就是「本 key 绑定的第 1 个样式」(white)。
