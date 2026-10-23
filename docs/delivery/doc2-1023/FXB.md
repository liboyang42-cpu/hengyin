# FXB — Doc 2 O-01 收尾：删除 /arrive 空精度放行（2026-10-23）

- 基线：master `f099c5be9eb3ef22214ce81e80303eb851f417dc`
- 分支：`feature/doc2-fxb-20261023`（Draft PR → master）
- 依据：owner 2026-10-23 拍板「小程序尚无真实用户，不设过渡期」。

## 改了什么

### 后端
| 文件 | 改动 |
|---|---|
| `ApiPlayProgressController#arrive` | 坐标判定路径（节点有坐标）下：**未上报精度**（null/空白）→ 拒，`errorCode=ARRIVE_ACCURACY_MISSING`、`appealable=false`；**非法精度**（NaN/Infinity/负数/坏文本）→ 拒，`msg` 仍为「定位无效」，新增 `errorCode=ARRIVE_ACCURACY_INVALID`、`appealable=false`。原「空精度放行并 info 留痕」分支和对 `requireAccuracy()` 的读取删除。>80m、50m 围栏、守卫调用顺序与参数不变。 |
| `ArriveLocationGuard` | 删除 `CONFIG_REQUIRE_ACCURACY` 与 `requireAccuracy()`；新增常量 `ACCURACY_INVALID`；只改类注释/字段注释。`mode()`、`check()`、节流/速度/窗口判定、落表、故障放行、申诉引用号（`guardLogId` + `appealBizType=play_arrive_guard`）**逐字未动**。 |
| `ArriveLocationGuardPolicy` | 只改 javadoc（第 2 层描述、第 7 层「精度缺失仅历史行按 80m 计」）。判定代码未动。 |

错误码沿用既有 `ARRIVE_*` 大写下划线风格（与 `ARRIVE_GUARD_THROTTLE/SPEED/WINDOW_DISTANCE` 并列）。
这两类拒绝不是作弊判定：在守卫之前返回，不写 `play_arrive_guard_log`，不给申诉引用；玩家重新定位或扫码即可。
节点无坐标（到点即完成）的路径不读精度，行为不变。

### 小程序（非 C00 文件）
| 文件 | 改动 |
|---|---|
| `pages/play/utils/play-accuracy.js` | 注释改为「空精度后端必拒」；新增 `canAutoArrive(accuracy)`：精度已知（有限、≥0 的 number）且 ≤80m 才为 true。 |
| `pages/play/index.js`（自动到达那一行，同行替换，行号不漂移） | `!isLowAccuracy(loc.accuracy)` → `canAutoArrive(loc.accuracy)`：拿不到精度时不再自动发 `/arrive`（否则每次定位回调都会被拒一次）。 |

没有改 C00/协调者文件（`app.js`、`tests/lint/ui-integrity-baseline.js` 等），不需要 CY-HANDOFF。

## 端上是否总带精度（核查结论）
- 用到该守卫/精度闸的接口只有 `POST /api/play/arrive`（`/api/club/lead/arrive` 不走此闸，`MerchantDesk` 核销用的是 `RedemptionAnchorPolicy`，均不受影响）。
- 端上唯一调用点：`pages/play/index.js` `arrive()`，请求体 `accuracy: playAccuracy.accuracyOf(loc)`，`loc = this._loc`。
- `this._loc` 只有两个写入点（`wx.getLocation` success、`wx.onLocationChange` 回调），都原样写入 `accuracy: loc.accuracy`；微信这两个 API 的回调都带 `accuracy` 字段。
- 结论：正常真机路径总会上报精度。剩余缺口只是「回调里 accuracy 异常缺失/非法」时：手动到达会收到后端明确拒绝（提示重新定位或扫码）；自动到达已在端上被 `canAutoArrive` 拦下不发。
- `pages/roam` 不再调 `/api/play/arrive`（已有注释与 `roam-redline-gate` 钉住）。

## 测试
| 用例 | 覆盖 |
|---|---|
| `ApiPlayGpsGeofenceValidationTest#blankAccuracyIsRejectedWithMachineCodeWithoutProgressOrReward` | null / "" / "  " / 7 参重载 → `ARRIVE_ACCURACY_MISSING`、`appealable=false`，不落进度不发奖（替换原 `blankAccuracyIsAllowedForLegacyClients`） |
| `#blankAccuracyIsRejectedBeforeGuardEvenWhenGuardInstalled` | 装了守卫也先拒、守卫 `check` 不被调用（替换原「默认开关仍放行」「仅开关开时拒」两条） |
| `#validAccuracyBehaviourIsUnchangedWithGuardInstalled` | 合法精度：守卫按原参数被调用、照常推进 |
| `#guardRejectionReturnsAppealReferenceWithoutProgressOrReward`（既有，保留） | 申诉路径仍在：速度拒绝返回 `guardLogId`、`appealable=true`、`appealBizType=play_arrive_guard` |
| `#invalidAccuracyIsRejectedAsInvalidLocation`（既有，加断言） | 非法精度带 `ARRIVE_ACCURACY_INVALID` |
| 同类其余坐标用例 | `arrive(lng,lat)` 帮助方法改为带合法精度 `15`，原断言不变 |
| `ArriveLocationGuardTest` | 删除 `requireAccuracy` 相关断言与 `requireAccuracyOnlyWhenStrictlyTrue`；enforce 申诉引用、throttle 不可申诉等用例保留 |
| `ApiPlayProgressCompletionTruthTest` | 6 处带坐标的 `arrive` 调用补合法精度（原断言不变） |
| `tests/unit/play-mechanisms-contract.test.js` | 新增 `canAutoArrive` 边界 + 自动到达行用 `canAutoArrive` + `_loc` 两个写入点都带 accuracy |

本地结果见 PR 正文（`mvn -pl chengyinhub-admin test`、`node --test`、`ui-integrity --warn-as-error`）。

## 留给 owner
- `chengyinhub-admin/sql/migration_20261022_location_guard_switch_seed.sql` 种的 `play.arrive.require_accuracy='false'` 现为**死键**（代码不再读）。按规矩不改既有 migration，也没另写删除 migration；要清理可在后台参数页手动删，留着无副作用。
- 文档 `docs/delivery/release-closeout-20261022/integration-batch4.md` 两处 `require_accuracy` 描述已标注废弃。
- 部署：本改动需后端 jar 与小程序一起发；旧版小程序（若有内测机）在有坐标节点上到达会被拒并提示重新定位或扫码。
