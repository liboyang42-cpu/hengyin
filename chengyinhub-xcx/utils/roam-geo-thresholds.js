'use strict'

// 漫游/定位相关距离与精度阈值的客户端唯一出处(C:U-120 / C:U-110)。
// 服务端是判定真源;这里的值是「镜像」,由 tests/unit/roam-geo-thresholds.test.js 逐个对照后端源码常量,
// 后端改值而这里没跟 → 单测判红,不会再出现「提示说能打卡,服务端说没到」。
//
// 三组阈值管的是三件不同的事,不能合并成一个数:
//   ① 打卡/到点半径(后端 ApiRoamController):判定「人在不在店/点附近」
//   ② 定位精度门槛(本端 roam-track-filter):丢掉精度太差的定位点,不进轨迹也不揭雾
//   ③ 玩法地理围栏(后端 ApiPlayProgressController.GEOFENCE_METERS):主题玩法任务点到达判定,与漫游无关,只列出备查
const SHOP_VISIT_RADIUS_M = 100       // ApiRoamController.SHOP_VISIT_RADIUS_M:探店入账到点半径
const ROAM_GPS_TOLERANCE_M = 30       // ApiRoamController.ROAM_GPS_TOLERANCE_M:到点判定叠加的 GPS 抖动容差
const POI_DISCOVER_RADIUS_M = 120     // ApiRoamController.POI_DISCOVER_RADIUS_M:POI 未配 radius_m 时的发现半径
const CHECKIN_NEAR_M = SHOP_VISIT_RADIUS_M + ROAM_GPS_TOLERANCE_M // 打卡按钮可用半径 = 后端拒收分界点
const ROAM_MAX_ACCURACY_M = 80        // roam-track-filter:定位 accuracy 超过它的点直接丢弃
const PLAY_GEOFENCE_M = 50            // ApiPlayProgressController.GEOFENCE_METERS(备查,漫游不用)

module.exports = {
  SHOP_VISIT_RADIUS_M,
  ROAM_GPS_TOLERANCE_M,
  POI_DISCOVER_RADIUS_M,
  CHECKIN_NEAR_M,
  ROAM_MAX_ACCURACY_M,
  PLAY_GEOFENCE_M,
}
