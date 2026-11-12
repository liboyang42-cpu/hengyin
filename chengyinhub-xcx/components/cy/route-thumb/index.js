// 漫游轨迹地图卡。2026-11-09 V3-40 / V1-P37(业主「需要的是真实的地图」「参考 nrc」):
// 有轨迹坐标(geo)时画真实底图(原生 map,不可拖动缩放,深色玩家底图),轨迹折线 + 起点空心 / 终点实心 / 途经店铺打点;
// 没有坐标(换手机 / 30 天位置已清)才退回原来的示意图(route,坐标由 scene-roam-history routeView 按 686×360rpx 算好)。
const { playerMapStyle } = require('../../../utils/map-style.js')
const STYLE = playerMapStyle()
Component({
  properties: {
    // { segs: [{i,x,y,len,deg}], dots: [{i,x,y,tone}], start: {x,y} | null, end: {x,y} | null, single: bool }
    route: { type: Object, value: {} },
    // { center:{latitude,longitude}, include:[...], polyline:[...], circles:[...] } | null —— 由 utils/roam-route-geo.js 算好
    geo: { type: Object, value: null },
  },
  data: { subkey: STYLE.subkey, layerStyle: STYLE.layerStyle },
})
