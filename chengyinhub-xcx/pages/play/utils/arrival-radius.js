'use strict'
/**
 * 到点校验半径的单一真源（业主 2026-11-11 拍板：三处到点校验统一一个半径）。
 *
 * 三处：
 *   1) 本端 GPS 自动到达（pages/play/index.js 的定位回调，`d <= ARRIVAL_RADIUS_METERS`）
 *   2) 后端 `/api/play/arrive` 围栏（ArriveLocationGuardPolicy.GEOFENCE_METERS）
 *   3) 后端「作答随带定位」的到达判定（ArrivalPolicy.LEGACY_RADIUS_METERS → 同一常量）
 *
 * 取值 50m：这是三处里已经在线上跑、且有既有单测与现场口径的默认值（后端 50m 围栏 / 80m 精度闸），
 * 也是「既严格又可靠」的那一档——再小室内/树下会大面积误判，再大等于把「到点」放宽成「到附近」。
 * 作者在节点 arrivalPolicy 里显式覆盖半径时，以冻结副本为准（BE-09），本常量只做缺省。
 *
 * 精度闸（80m）是独立一层，不参与半径计算：低精度不能扩大可到达范围。
 */
const ARRIVAL_RADIUS_METERS = 50
const ARRIVAL_RADIUS_TEXT = ARRIVAL_RADIUS_METERS + ' 米'

module.exports = { ARRIVAL_RADIUS_METERS, ARRIVAL_RADIUS_TEXT }
