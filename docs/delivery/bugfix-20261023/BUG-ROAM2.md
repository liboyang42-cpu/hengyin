# BUG-ROAM2 上线前 bug 修复(漫游与定位 第二部分)

分支 `fix/bug-roam2-20261023`,基于 master `b84ed673`。每个编号都在当前 master 代码上重新核对后分类。

## 汇总

| 分类 | 数量 |
|---|---|
| FIX | 7 |
| ALREADY_FIXED | 2 |
| NOT_REPRODUCIBLE | 3 |
| OWNER | 6 |
| DEFER | 18 |
| 合计 | 36 |

## 明细

| id | 分类 | 改动(文件) | 测试 | 说明 |
|---|---|---|---|---|
| C:U-038 | OWNER | — | — | 后端已能写入帖子地点字段;小程序发帖要不要采集、上报地点属于隐私与产品决策,本次不改 |
| C:U-058 | FIX | `ApiRoamController.java`(`clampDistanceM`) | `RoamFinishDistanceCapTest` | 结算里程不再整段采信客户端:上限为 会话开始至今秒数 × 35m/s,缺开始时间记 0。里程会出现在广场分享卡副标题里 |
| C:U-071 | ALREADY_FIXED | — | 现有 `ApiRoamShareControllerTest` / `roam-route-privacy.test.js` | 服务端 `RoamSharePrivacyService.normalize` 在落库和读取时都把坐标量化到 3 位小数;小程序侧裁掉首尾。要不要更粗的精度见 U-104 |
| C:U-072 | NOT_REPRODUCIBLE | — | 现有 `roam-npc-coordinator.test.js` | `roam_poi_first_light` 是 NPC 提示的事件类型,不是埋点事件名;上报走 `npc_bubble_impression`(在白名单内),`eventType` 放在属性里。没有事件被丢 |
| C:U-085 | FIX | `RoamMapper.xml`(`selectPoisNear`) | `RoamPoiPlayerVisibilityMyBatisIT#nearPoisAreOrderedByDistanceAndCapped` | 按离包围盒中心(即玩家定位)由近到远排序,`limit 200`,id 兜底保证稳定 |
| C:U-086 | DEFER | — | — | 所有调用方都用默认上限 180,轨迹点数有界;只剩一条误导性注释 |
| C:U-087 | NOT_REPRODUCIBLE | — | — | `share.idx` 是卡片版式下标(1=方卡),与渠道顺序无关;滑动条已移除,始终为 0 |
| C:U-088 | FIX | `pages/roam/index.js`(`_exportAndSave`),新增 `utils/roam-share-loading.js` | `roam-bug-roam2-20261023.test.js` | 画卡途中作用域失效(换号/离开漫游)提前返回时也收起 loading;按层计数配对,`_cancelShareWork` 已收过的不重复收 |
| C:U-089 | DEFER | — | — | 死代码清理,用户看不到 |
| C:U-090 | DEFER | — | — | 常量抽取/重构 |
| C:U-091 | DEFER | — | — | 补可注入定位源/抽纯函数属于重构与补测;`_realTick` 已有测试 |
| C:U-092 | DEFER | — | — | 朝向只随 ≥3m 的真实定位点更新,每个点最多多一次 marker 同步;性能优化,不影响功能 |
| C:U-093 | DEFER | — | — | 回放帧数/时长是视觉打磨 |
| C:U-094 | OWNER | — | — | 清空前已有确认弹窗;30 秒撤销属于新产品规则,需拍板 |
| C:U-096 | FIX | 同 U-058 | `RoamFinishDistanceCapTest` | 与 U-058 是同一处修复 |
| C:U-097 | ALREADY_FIXED | — | `roam-bug-roam2-20261023.test.js`(补测) | `evictedSavedPhotos` 已先收集保留会话引用的路径并排除;补上两条足迹共用同一张照片的回归用例 |
| C:U-098 | FIX | `utils/roam-share-snapshot.js` | `roam-bug-roam2-20261023.test.js` | 没有账号(`''`/`0`/`'0'`/`undefined`/`null`)时待办键为空,不读不写、不发请求,不再落到全局键 |
| C:U-100 | FIX | `pages/roam/index.js`(`onShareAppMessage`),新增 `utils/roam-menu-share.js` | `roam-bug-roam2-20261023.test.js` | 右上角转发固定返回漫游页路径和仓内一张不含位置的插画(`images/route_city_cover.png`,在包内),不再让微信截取实时地图;活动分享按钮不变 |
| C:U-102 | DEFER | — | — | 坐标列类型/索引迁移;现有解析可用,属于性能与结构 |
| C:U-103 | DEFER | — | — | 加 CHECK 约束/常量属于结构加固 |
| C:U-104 | OWNER | — | — | 现实现量化到 3 位小数;旧 migration 注释里的整数度平移未实现。是否更粗/平移是隐私精度决策;已有 migration 不能改 |
| C:U-106 | FIX | `pages/roam/index.js`(`shareTo`) | `roam-bug-roam2-20261023.test.js` | 缺渠道参数时静默拦下,不再弹「当前可见范围不发布到」残缺提示 |
| C:U-107 | DEFER | — | — | 两个分组函数结果一致,合并属于重构 |
| C:U-109 | DEFER | — | — | 日期格式统一是打磨 |
| C:U-110 | DEFER | — | — | 三套阈值各管各的场景,常量共享是重构 |
| C:U-112 | OWNER | — | — | 探店入账没有瞬移速度校验;加上会改变反作弊口径,可能误伤真实用户,阈值需拍板。到店距离校验已在 |
| C:U-115 | NOT_REPRODUCIBLE | — | — | 小程序没有删除单条足迹的入口;唯一删除是清空本机足迹,会调用 `/api/roam/share/revoke` 作废全部分享;服务端按会话清理位置时同时删除该会话的分享快照 |
| C:U-117 | DEFER | — | — | 两套回放几何合并是重构 |
| C:U-118 | OWNER | — | — | 入口地图无历史定位时的默认城市与提示文案需要产品决定;开始漫游用真实定位 |
| C:U-120 | DEFER | — | — | 客户端 130m 与后端 100+30 一致;常量下发是重构 |
| C:U-123 | DEFER | — | — | 写失败已保留脏标记、30 天清理已有;分片/容量上限属于加固 |
| C:U-124 | DEFER | — | — | 注释过时 |
| C:U-126 | DEFER | — | — | 调试探针没有任何界面入口 |
| C:U-127 | OWNER | — | — | 跳点后补插值/补里程会改里程口径,也牵涉后台定位这项未决的决定;当前做法是少算不多算 |
| C:U-149 | DEFER | — | — | 三处兜底分别校验各自配置域(章节结局/剧情结局/主题结局)「恰好一个兜底」,彼此不冲突;合并成单一决策点是重构 |
| C:U-171 | DEFER | — | — | 分享完成/失败回执是埋点需求,不影响功能 |

## 需要拍板的事项(OWNER)

- U-038:发帖是否采集并上报地点(精度、是否可选)。
- U-094:清空本机足迹是否需要撤销窗口。
- U-104:分享快照坐标精度是否改为更粗(或平移锚点)。
- U-112:探店入账是否加与发现地点同口径的瞬移速度校验及阈值。
- U-118:入口地图首次无定位时显示什么(默认城市 + 提示 / 先请求定位)。
- U-127:后台恢复后的长距离跳点是否补算里程(与后台定位决定一起)。

## 验证

- 后端:`RoamFinishDistanceCapTest`、`RoamFinishCasTest`、`RoamXpTest`、`RoamFinishOnceAcceptanceTest`、`RoamAiContentApiGateTest`、`ApiRoamCoordinateBoundaryTest` 本地通过;MyBatis IT 需要 MySQL,由 CI 跑。
- 小程序:`node --test "tests/unit/**/*.test.js"` 本地全绿(13765 通过,0 失败);新测试在撤掉修复后 7 条中 7 条变红(U-097 为补测,本就通过)。
- 静态门:js-scope / wxml-handler / orphan-handler / setdata-dead-field / coverage / route-matrix / ui-integrity / ui-validity / roam-redline / api-existence / silent-error 本地通过。
- 动作台账:`build-action-ledger.js --check` 通过,控件数 5009 → 5009,没有重新生成台账。页面里的修改保持行数不变,逻辑放到新 util 里,台账与 UI 基线都不受影响。没有改动 C00 保留文件。

## CI

PR #560,head `78aed085`,全部通过:

- GitHub CI:39962589433(Backend tests 含 `RoamPoiPlayerVisibilityMyBatisIT` 2/2 真库通过;小程序 20 个单测分片、静态检查与静态门全部通过)
- Mini followup focused regression:39962589270
- Isolated authenticated Redis recovery:39962589282
- Funds MySQL IT:39962589249

本提交只改本文档,之后的 CI 运行见 PR 检查。
