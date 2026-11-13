# 到点规则改为开局一次 300 米定位校验

业主拍板（2026-11-13，已确认）：“大概就是 300 米，开头需要注意，游戏中就不需要了，开始的时候稍微停一下。”

## 新规则
1. 开局（本局第一次到达）做一次定位校验：玩家须在首站 300 米内，过远给明确业务提示“离起点太远，请到起点 300 米内再开始”。定位权限只在开局问一次。
2. 开局之后，所有站点到点不要求定位、不做围栏，各游戏类型一致（取代 D-69 的逐站 50 米）。
3. 保留：归属/报名/范围校验、幂等、顺序规则、防重放（开局那次到达仍过 ArriveLocationGuard 节流/速度/窗口）。

## 后端
- `ArrivalPolicy`：新增 `START_RADIUS_METERS = 300` 与 `startViolation()`（精度闸 80m 沿用，先于距离、互不抵扣）。
- `IShopDayProofService/Impl`：新增 `runStarted(memberId, activityId)`，以本局是否已有进度行为“已开局”依据，复用既有 Mapper 语句，未改任何 SQL，无表结构变更。
- `PlayBasicCommandApplication`：
  - `arrive`：本局未开局且站点有坐标时才校验定位（缺定位/无效/精度/超 300 米均拒绝，服务端强制）；已开局的站不带定位也接受。未开局的到达必带合格定位，因此“没过开局校验就到站”被拒。
  - 老路径作答随带定位：同样只在未开局时按 300 米判，`answerFenceError` 改为 `startFenceError`；已开局不再要定位。
  - 删除 `GEOFENCE_METERS` 引用。
- `ArriveLocationGuardPolicy`：仅更新说明（守卫现只在开局那次到达被调用）；未新增 `com.chengyinhub.business.service` 下的类。
- 漫游打卡（roam）是独立功能，未改动。`ArrivalPolicy.radiusMeters` 作者覆盖项不再用于站点判定，仅保留冻结格式兼容。

## 小程序
- `pages/play/index.js`：新增 `_startLocationNeeded()`（本局无完成站）。到点按钮、作答、进页/开局/续玩的定位申请都只在未开局时才要定位；开小地图、进入导航不再申请定位；定位说明文案改为“仅在开局检查 300 米”。
- `arrival-radius.js`：新增 300 米常量；50 米只剩“人已在站点旁时自动点亮”的便利提示半径，不再是规则，罗盘/距离显示保留。

## 测试（引用业主 2026-11-13 决定）
- Java（本地按仓库约束未跑，交 CI）：`ApiPlayGpsGeofenceValidationTest` 新增 250 米开局通过、302 米被拒、开局后无定位站点通过、未开局无定位被拒；`ApiPlayProgressCompletionTruthTest` 补开局后作答免定位；`ArrivalPolicyTest.startCheckAllowsUpToThreeHundredMetres`；既有断言文案同步。
- 小程序：D-69 契约测试重写为开局 300 米规则，同步 P-26、play-ui-contract、小地图契约；UI-GATE-0 仅有 U5 行号位移（8923→8926，条数不变，无新增登记，digest 重算），动作台账与路由矩阵已重建。

## 门禁
PR #814 在提交 be7657e 上 35 项检查全绿（后端 18962 项测试、小程序 20 分片与静态门禁、迁移门禁、gitleaks）。后端首轮曾有一项无关的俱乐部通知测试（ClubEventNotificationTransactionBoundaryTest）失败，重跑通过，未改动该测试。未改 `ci/architecture/*`、`.github/workflows/*`，未跑 `ci/xcx-check.sh`。

STATUS: DONE
