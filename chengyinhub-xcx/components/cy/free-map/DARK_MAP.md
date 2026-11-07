# free-map 暗色底图接入说明(S9)

## 现状
`free-map` 目前用的是腾讯默认**浅色**底图。spec 要暗色,但暗色需要在**腾讯位置服务(LBS)控制台**
建一个"个性化地图样式",这是一步**外部依赖**,必须由项目负责人(不是代码)在控制台完成。

本次改动只做了**代码侧预留**:`subkey` / `layerStyle` 两个属性已经从
`free-map` 组件 → `pages/play` 打通,默认值让底图**逐字节**保持现在的样子。控制台建好样式后,
只需要把两个值填进一个地方,不用再碰其它代码。

## 已核实的微信 `<map>` 属性(不是猜的)

查了微信官方 `<map>` 组件文档(developers.weixin.qq.com/miniprogram/dev/component/map.html),
个性化地图用这两个属性:

| WXML 属性名 | 类型 | 官方默认值 | 最低基础库版本 | 作用 |
|---|---|---|---|---|
| `subkey` | String | `''` | 2.3.0 | 腾讯 LBS 控制台申请的"个性化地图"专用 key,**不支持运行时动态修改**(改了要重新渲染 map 才生效) |
| `layer-style` | Number | `1` | 2.3.0 | 个性化样式编号(控制台建好样式后分配的 style ID) |

同一份配置也能通过 `<map>` 的统一 `setting` 对象下发(驼峰名 `subKey` / `layerStyle`),
官方文档给的默认值同样是 `subKey:''` / `layerStyle:1`——两处默认值一致,互相印证。

本项目 `chengyinhub-xcx/project.config.json` 里 `libVersion` 是 `3.7.3`,远高于 2.3.0,版本上没有障碍。

**关键点:官方默认值是 `layerStyle: 1`,不是 0。** 如果代码把默认值写成 0,而 0 不是一个已定义的
样式编号,底图可能不会跟"不传这个属性"时完全一致——这就破坏了回归安全。所以本次代码把默认值
定成 `subkey:''` / `layerStyle:1`,和微信 `<map>` 组件自己的默认值完全对齐。

## 代码预留改了什么

1. `components/cy/free-map/index.js`:properties 加了 `subkey`(String,默认 `''`)、
   `layerStyle`(Number,默认 `1`),纯透传,没有 observer/副作用。
2. `components/cy/free-map/index.wxml`:`<map>` 标签加了
   `subkey="{{subkey}}" layer-style="{{layerStyle}}"`。
3. `pages/play/index.js`:顶部加了一个集中配置常量

   ```js
   const MAP_DARK = { subkey: '', layerStyle: 1 };
   ```

   `data` 里加了 `fmSubkey: MAP_DARK.subkey, fmLayerStyle: MAP_DARK.layerStyle`。
4. `pages/play/index.wxml`:`<free-map>` 标签加了
   `subkey="{{fmSubkey}}" layer-style="{{fmLayerStyle}}"`。

默认值 `subkey:''` / `layerStyle:1` 时,`<map>` 收到的属性值和微信组件自身的默认值一模一样,
渲染出来的底图和这次改动之前没有任何差别(回归安全)。

## 用户接下来要做的事(真正点亮暗色底图)

1. 登录腾讯位置服务控制台(https://lbs.qq.com/),在自己的应用下新建一个"个性化地图样式",
   选 Monochrome/暗色基调,按需调整道路、POI、水系等图层颜色,发布。
2. 控制台会给这个样式分配一个 **styleId**(数字),同时你的小程序在 LBS 控制台需要绑定/申请
   一个**个性化地图 subkey**(和小程序 `AppID` 关联,不是普通的 REST API key)。
3. 把这两个值填进 `pages/play/index.js` 顶部的 `MAP_DARK` 常量:

   ```js
   const MAP_DARK = { subkey: '你的subkey', layerStyle: 你的styleId };
   ```

4. 保存、重新编译/预览——`pages/play` 的地图会用新样式渲染。因为 `subkey` 不支持运行时动态改,
   改完这个常量后要让页面重新加载(冷启动或重新进入该页面),不能指望热更新生效。

## roam 同坑(本任务未改 roam,写在这里供用户自行套用)

`pages/roam/index.wxml` 里也有一个独立的原生 `<map>`(不经过 `free-map` 组件)。**本任务没有碰
`pages/roam/*` 任何文件**——用户正在那边并行改动约 1000 行未提交代码,动了会冲掉。

同样的道理可以直接套到 roam 自己的 `<map>` 上:

- 给 roam 的 `<map>` 标签加 `subkey="{{...}}" layer-style="{{...}}"`(两个属性名和这里完全一样,
  官方默认值同样是 `''` / `1`,回归安全逻辑不变)。
- roam 页面自己维护一份 `subkey`/`layerStyle` 的 data 字段(或者也 require 这份 `MAP_DARK` 常量,
  如果想和 play 共用同一个暗色样式的话)。
- 同一个控制台样式(同一个 subkey + styleId)可以被 play 和 roam 两处地图同时复用,不需要建两份。

用户可以在自己方便的时候把这段逻辑加到 roam 里,不影响这次提交。

## 2026-11-03 第九轮:发布时填值,一个 key 两套样式

- 仓库里 `utils/map-style.js` 永远 `subkey: ''`。上传前对**上传用的拷贝**运行
  `node scripts/release-map-style.js --dir <拷贝目录> --require`(变量名见下一节「四套样式」),
  缺任何一项都失败;脚本拒绝改仓库本身,也不打印 key。
- 微信 `<map>` 文档:同一个小程序里的 map 组件要用**同一个 subkey**,样式只靠 `layer-style`(该 key 下绑定样式的序号)区分。
  所以控制台要在同一个 key 上绑两套样式:暗色(玩家)和浅色(商家 / 编辑器)。只给 key 不给浅色序号,脚本直接失败 ——
  否则商家地图会跟着变暗。
- 玩家地图(`playerMapStyle()`):`pages/roam`、`pages/play`、`subpackageRoam/nearby`、玩家进入的 `pages/searchmap`。
  商家 / 编辑器地图(`lightMapStyle()`):`pages/publish/fabu`、`pages/topic/index`、`pages/square/components/route-map`、商家视角进入的 `pages/searchmap`。
- 只有真机能确认:绑定序号与控制台一致、浅色序号下底图确实是浅色、同一 key 两套样式在同一次启动里切页不串。

## 2026-11-04 第九轮 m4:一个 key 四套样式(owner 定稿)

| 样式 | 用在 | 环境变量 |
|---|---|---|
| black | `pages/roam`、`subpackageRoam/nearby`、玩家进入的 `pages/searchmap`;主题外观 星尘 `STARDUST`、末世 `SURVIVAL`、没设外观的主题 | `CY_MAP_STYLE_BLACK`(旧名 `CY_MAP_LAYER_STYLE` 仍认) |
| white | 商家与编辑器地图 | `CY_MAP_STYLE_WHITE`(旧名 `CY_MAP_LIGHT_STYLE` 仍认) |
| paper | 主题外观 纸本 `PAPER` | `CY_MAP_STYLE_PAPER` |
| 2077 | 主题外观 夜城 `CYBER` | `CY_MAP_STYLE_2077` |

- 上传前:`CY_MAP_SUBKEY=… CY_MAP_STYLE_BLACK=1 CY_MAP_STYLE_WHITE=2 CY_MAP_STYLE_PAPER=3 CY_MAP_STYLE_2077=4 node scripts/release-map-style.js --dir <拷贝目录> --require`。
  序号是控制台「样式应用列表」里这四套绑到同一个 key 的序号。给了 key 却缺任何一个序号、或两个序号相同,脚本失败。
- `pages/play` 按主题的 `look.style` 选样式(`kindForLook`)。`<map>` 的 `layer-style` 不能运行时改,所以 play 的三张地图
  等主题外观落定(`fmStyleReady`)才渲染;同一页里外观真变了,先撤地图再按新样式重建,不在旧图上改属性。
- 浅底(white、paper)上原来的白线、白圈看不见:`free-map` 的 `tone` 把我走过的路、路线预览换成墨色并换浅描边,
  队伍线保留队色只加深描边,节点光圈换成墨色(`toneLines` / `toneCircle`)。没配 key 时 `tone` 为空,配色与之前完全一样。
- 只有真机能确认:四个序号与控制台一致;纸本 / 夜城底图上墨色线、队色、店名小标签确实清楚;进主题时没有先闪一下别的底图。

## 附:原 `utils/map-style.js` 文件头说明(2026-11-04 移到这里,注释不进主包)

```
'use strict';

// 个性化地图(暗色底图)集中配置 —— play / roam 两页共用同一份值。
//
// 为什么抽出来:两页各自的 <free-map> 都要透传这两个属性,分开写就会出现"改了 play 忘了 roam"
// 的半暗半亮。改这一处两页同时生效。
//
// subkey     腾讯 LBS 控制台的个性化地图 key。<map> 不支持运行时动态修改,改了要重新渲染 map。
//            ⚠️ 它会打包进前端、可被反编译取到 —— 控制台上应只给它开"微信小程序个性化地图",
//            WebService/REST 另用一个不下发前端的 key,否则额度会被盗刷。
// layerStyle ★ 不是样式自身的 ID,而是「该样式绑定到本 key 之后的序号」,从 1 开始。
//            所以控制台的样式详情页看不到它 —— 要先在「个性化地图 → 我的样式 → 使用/应用」里
//            把样式绑到这个 key(即这个应用/小程序)上,绑定列表里才有序号。只绑一个就是 1。
//            ★★ 因此 subkey 一旦非空,layerStyle:1 就不再等于"腾讯官方标准浅色底图",
//            而是"本 key 绑定的第 1 个样式" —— 底图会跟着变。空 subkey 时才是官方默认。
//
// 接入步骤与已核实的属性表见 components/cy/free-map/DARK_MAP.md。
// ★ 当前状态(2026-08-12 决策):个性化地图【暂不启用】,subkey 保持空。
//   月费约 400 元,而遮住地名这件事屏幕级 Canvas 迷雾已经能做(它盖在 <map> 之上,连原生文字
//   一起遮;groundOverlay 那套在底图文字之下,才是遮不住的那个)。所以调试阶段走零成本方案,
//   等上线/融资演示前再买一个月做最终视觉验收;若真机验证下来 Canvas 迷雾稳(手势不冲突 /
//   同层渲染一致 / 不掉帧),这 400 元可以永远不花。
//
//   ⚠️ 为什么这里是空字符串而不是把 key 填上:
//   ① 空 subkey 才等于微信 <map> 的官方默认值,底图与接线之前**逐字节一致**;一旦填了非空 key,
//      layerStyle:1 的含义就从「官方标准浅色底图」变成「本 key 绑定的第 1 个样式」,
//      play 页三张地图会跟着变 —— 那是没验证过的外观改动,也可能开始计量。
//   ② key 是付费服务凭据,会打包进前端、可被反编译取到,不该在用不到的时候躺在 master 里。
//
//   启用步骤(那时才需要 key):
//   1. 腾讯 LBS 控制台 → 个性化地图 → 我的样式 → 建样式 → **「使用/应用」到本小程序的 key**;
//      序号在「样式应用列表」里才看得到,样式详情页没有。只绑一套就是 1。
//   2. 控制台上只给这个 key 开「微信小程序个性化地图」,WebService/REST 另用一个不下发前端的 key。
//   3. 把 key 填进 subkey、序号填进 layerStyle。接线(play 3 处 + roam 3 处)已经就位,不必再改。
//   详见 components/cy/free-map/DARK_MAP.md。
//
// 2026-11-03 第九轮 m1 第 1 项:仓库里永远是空 subkey。上传前由 scripts/release-map-style.js 从环境变量
// 往「上传用的那份拷贝」里填值,不写回仓库。
// 一个小程序里的所有 <map> 共用同一个 subkey(微信 map 文档),样式只靠 layer-style 区分。
//
// 2026-11-04 第九轮 m4 第 2 项(owner 定稿四套样式),四个序号都是同一个 key 下的绑定序号:
//   black  漫游、附近、玩家进入的搜索地图;主题外观 星尘(STARDUST)、末世(SURVIVAL)和没设外观的主题
//   white  商家与编辑器地图
//   paper  主题外观 纸本(PAPER)
//   cyber  主题外观 夜城(CYBER),即「2077」那套
// 0 = 没填。没填 key 时四种都回到微信官方默认底图(与接线之前逐字节一致)。
```
