# qx-a4 结果:漫游、护照、集邮(2026-11-09)

分支 `claude/qx-a4-20261109`,基于 `origin/master` 2bb81143。截图在 `shots/`(390 px 宽,JPEG q70,走查 rig 实拍)。

## 逐项

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机上仍未验证 |
|---|---|---|---|---|---|
| V3-26 | fixed | 删掉头像旁边的「城市漫游清单」图标按钮;头部行保留 88rpx 最小高度,标题位置不动 | `pages/roam/index.wxml`、`index.wxss`、`index.js` | rig:`V3-26-before/after.jpg`;`roam-city-catalog.test.js` 断言按钮与处理函数不在 | 无 |
| V3-35 | fixed | 删除清单组件 `subpackageRoam/components/roam-city-guide` 及入口。目录地点(已上架、type=1、带导入种子标签 `roam:seed:v1:*`、有坐标)由入口地图另读 `/api/roam/pois`(同地图页端点与权限档)画成图钉;点图钉开入口弹层:分类、地址(纯文字;漫游红线 R4 禁止漫游页唤起外部导航)、目录里核对过的理由(沿用分包 `roam-place-note`,换卡后迟到回填丢弃)。读失败走请求层默认提示,不并进附近活动错误态 | `utils/roam-entry-map.js`、`pages/roam/index.*`、删 `roam-city-guide/*` | rig:在测试库插两条虚构点位(种子标签指向真实目录条目),`V3-35-after.jpg` 地图两枚点、`V3-35-after-place.jpg` 弹层带目录理由;单测 `roam-entry-map-contract.test.js`(只认目录点、图层开关不影响、点图钉回同一点)、`roam-recovery-flow.test.js` | 原生地图上图钉图标与点击;33 个真实目录地点仍没有核验坐标(导入工具要求高德/腾讯核验),**上线前地图上没有目录图钉**,见 needs owner |
| V3-27 | fixed(勋章段 already fixed) | 收集册页本来就有「勋章 / 邮票 / 藏品」三段(album-head),勋章在里面;本次删掉护照上单独的「勋章墙」方卡及其只为方卡服务的静默读取 | `pages/roam/index.js` | rig:`V3-27-before/after.jpg`(护照)、`V3-27-after-album.jpg`(收集册三段)、`V3-27-after-medal.jpg`(勋章段) | 无 |
| V3-34 | fixed | 收集册三叠小卡:去掉灰色卡底与描边;叠层放进 `preserve-3d` 容器(透视 640rpx,静止俯仰 10° / 侧转 −12°),三层不同 Z 深度与透明度,顶层同图提亮做斜向镜面高光(形状随图,不出方框);按下(hover-class)转正浮起、高光扫过;`prefers-reduced-motion` 时无过渡。邮票 / 藏品两叠后两层用同一张图叠出厚度 | `subpackageMember/components/cy/profile/index.wxml`、`index.wxss` | rig:`V3-34-before/after.jpg`;`press-feedback-resolves-contract` 通过 | 3D 透视与 mask 高光在真机 WebView 的效果、按下动效;rig 不出 mask 高光 |
| V3-36 | fixed | 漫游记录 / 集邮册方卡、官方活动大卡在没有自己的图时显示实景照片。照片放 `subpackageRoam/assets/photos/*.webp`(14–38 KB),由分包组件 `roam-photo` 经 `componentPlaceholder` 异步引用,主包不增图;原 `images/roam/tile-*.svg` 三张删除 | `subpackageRoam/components/roam-photo/*`、`subpackageRoam/assets/photos/*`、`pages/roam/index.*` | rig:`V3-36-before/after.jpg`;`roam-intro-passport.test.js` | 分包未下载时占位为空(view),首屏可能先空再出图 |
| V3-40, V1-P37 | fixed | 漫游记录卡上半有轨迹坐标时换成真实底图:原生 `map`(不可拖动缩放旋转、无 POI、深色玩家底图同一 subkey/layer-style),轨迹折线、起点空心 / 终点实心 / 途经店铺圆点,`include-points` 框住全程并按范围预算缩放级;透明点击层让点卡片仍进详情。没有坐标(换手机 / 30 天位置已清)退回原示意图 | `utils/roam-route-geo.js`、`components/cy/route-thumb/*`、`components/cy/scene-roam-history/*` | rig:本机记录注入 3 次漫游(walk eval 写本机存储),`V3-40-before/after.jpg`;单测 `roam-route-geo-v3-40.test.js` 5 条 | rig 的 map 是画布替身,不按 include-points 取景、圆点很小;真机底图、取景与列表里多张原生地图的滚动性能未验证 |
| V3-42 | fixed | 护照「时光胶囊」方卡与跳转删除;`app.json` 去掉 `subpackageTools/capsule/index` 注册并删除该页文件(未注册页会被页面清理审计判为孤页);`utils/features/capsule-client.js` 与后端不动 | `app.json`、`pages/roam/index.js`、删 `subpackageTools/capsule/*` | rig:`V3-42-before/after.jpg`;路由矩阵 / 标题盘点 / 页面清理审计按 156 页重建 | 无 |
| V1-P32 | fixed | 漫游入口地图上活动 / 主题点(原 14px 小点)与招募中队伍点(原 14px)都加常显深色小卡(#1C1C1E,圆角 12):第一行标题,第二行服务端事实(距离或场次时间;队伍 `已加入/上限 人 · 招募中`,缺人数只写「招募中」不写 0);点放大到 20/22px | `utils/roam-entry-map.js` | rig:定位在主题节点 585 m 外,`V1-P32-before/after.jpg`;单测 `roam-entry-map-contract.test.js` | rig 把 callout 画成纯文字标签,原生卡片外观、多卡重叠时的避让未验证;rig 库里没有可招募队伍,队伍卡只有单测证据 |

## needs owner

1. **V3-36 照片来源。** 施工环境的网络策略挡住了 Unsplash / Pexels / Openverse / Wikimedia,拿不到新图。默认做法:用仓里已入库、已登记许可的 CC0 照片(`pages/templatedetail/art/covers/` 的街市、上海夜景、外滩),只裁切转 webp。来源与许可写在 `subpackageRoam/assets/photos/SOURCES.md`。其中街市和夜景两张在仓里只记了「Openverse CC0」、没记单张原链接;外滩一张是 Flickr「Shanghai Bund」CC0。要换成更贴题的照片,把图放进同一目录同名即可。
2. **V3-35 目录地点上地图要先导入坐标。** 33 个目录地点在仓里没有坐标,地图图钉只来自导入工具 `scripts/roam-catalog-import.js` 生成并经运营上架的 `roam_poi`。默认做法:前端已能画,数据上线前地图上不会出现目录图钉。清单删了以后,这些地点在导入前没有别的入口。
3. **V1-P32 范围。** 业主原话「地图上放的活动 组队要都能看到」。默认只改了漫游首页的入口地图(活动、主题、队伍三类点)。`subpackageRoam/nearby` 附近地图本来就有头像队伍图钉和底部卡片带,没动。「ashdesignin」的卡片参考图我看不到,样式按现有深色浮层做。
4. **V3-34 跟手倾斜。** 小程序的 WXS 触摸绑定过不了台账构建器(`build-action-ledger.js` 解析不了 `{{tilt.start}}`),所以没做跟着手指转的效果,改成按下时转正浮起(纯 CSS)。要做跟手倾斜,得先给台账工具加 WXS 绑定的支持。

## Cross-lane

- 无文件冲突。共享文件只改了 `app.json`:删掉 `subpackageTools` 里的 `capsule/index` 一行,V3-42 需要。
- `scripts/uiaudit/action-ledger.json`、`route-matrix.json` 已重建,其他 lane 合并时要再重建一次。
- `tests/lint/ui-integrity-baseline.js` 只改了这些:roam 锚点按行号位移重新对齐,删掉一条失效登记,u5Silent 从 44 降到 43,digest 重算。没有新增登记。
- 收集册「勋章」段在 rig 里是空态,页面上没有「收集册」标题和三段切换(`V3-27-after-medal.jpg`)。这一页是 webgl 勋章墙,不在本 lane 范围,可能是 rig 降级造成的,请 badge-wall 的 owner 在真机上看一眼。

## 验证

- 小程序单测:全量 `node --test "tests/unit/**/*.test.js"` 16770 通过。剩下 3 条失败(台账 local 计数、标题间距页数、hover-class 定义)已修复,对应文件单独重跑全绿。`action-ledger.test.js` 48/48 通过。
- `node chengyinhub-xcx/tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`。
- 台账已用 `build-action-ledger.js --write` 重建(5724 个控件),路由矩阵已用 `build-route-matrix.js --json` 重建(156 条)。
- 主包源码 3142 KB,按 ×0.633 约 1989 KiB,低于 2048。
- Rig 情况:
  - 后端用 master,在本容器里跑,MySQL/Redis 走 mirror.gcr.io 镜像,因为 Docker Hub 返回 429。
  - 前端 bundle 是本分支构建的。
  - `chains.py player` 里 sale_close 一段失败(主题没进入 SELLING),跟本 lane 无关,player 链没跑。
- 测试库(用完即弃)里做过的处理:
  - 两个虚构 `roam_poi`。
  - 主题 990013/990014 的 `img_url` 清空,因为 rig 没配 OSS,`media-asset:1` 会让附近读取返回 500。
  - 收集册区块是用 eval 把 `profileMetricState` 置为 ready 才显示出来的,数据都是真实的 0。

STATUS: COMPLETE
