'use strict'
/**
 * 到点定位规则的端上常量。
 *
 * 业主 2026-11-13 拍板（取代 2026-11-11 D-69 的「逐站 50m」）：
 *   “大概就是 300 米，开头需要注意，游戏中就不需要了，开始的时候稍微停一下。”
 *   - 开局（本局第一次到达）校验一次定位：玩家须在首站（或集合点）约 300 米内，
 *     与后端 ArrivalPolicy.START_RADIUS_METERS 同值；
 *   - 开局之后各站到点不再要求定位，端上也不再逐站申请定位、不再按距离禁用按钮。
 *
 * ARRIVAL_RADIUS_METERS（50m）不再是规则：只剩「人恰好已在站点旁时自动点亮」的便利提示半径，
 * 服务端不再以它拒绝任何到达。
 */
const ARRIVAL_START_RADIUS_METERS = 300
const ARRIVAL_START_RADIUS_TEXT = ARRIVAL_START_RADIUS_METERS + ' 米'
const ARRIVAL_RADIUS_METERS = 50

module.exports = { ARRIVAL_START_RADIUS_METERS, ARRIVAL_START_RADIUS_TEXT, ARRIVAL_RADIUS_METERS }
