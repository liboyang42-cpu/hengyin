// 漫游轨迹地图卡。2026-11-09 V3-40 / V1-P37(业主「需要的是真实的地图」「参考 nrc」):
// 有轨迹坐标(geo)时画真实底图(原生 map,不可拖动缩放,深色玩家底图),轨迹折线 + 起点空心 / 终点实心 / 途经店铺打点;
// 没有坐标(换手机 / 30 天位置已清)才退回原来的示意图(route,坐标由 scene-roam-history routeView 按 686×360rpx 算好)。
const mapStyle = require('../../../utils/map-style.js')
// 2026-11-14 owner:缩略图跟玩家在漫游里选的底图(默认 black);浅底把白线换墨色
function look() {
  const kind = mapStyle.freeMapKind(), style = mapStyle.mapStyleFor(kind)
  return { kind, subkey: style.subkey, layerStyle: style.layerStyle, tone: mapStyle.mapToneFor(kind) }
}
Component({
  properties: {
    // { segs: [{i,x,y,len,deg}], dots: [{i,x,y,tone}], start: {x,y} | null, end: {x,y} | null, single: bool }
    route: { type: Object, value: {} },
    // { center:{latitude,longitude}, include:[...], polyline:[...], circles:[...] } | null —— 由 utils/roam-route-geo.js 算好
    geo: { type: Object, value: null },
  },
  data: { subkey: '', layerStyle: 1, lines: [] },
  observers: {
    geo(geo) { this.setData({ lines: mapStyle.toneLines((geo && geo.polyline) || [], this._look().tone) }) },
  },
  lifetimes: {
    // layer-style 不能热改,所以 attached 一次读定;缩略图随历史列表重建,下次进来就是新选择
    attached() {
      const l = this._look()
      this.setData({ subkey: l.subkey, layerStyle: l.layerStyle, lines: mapStyle.toneLines((this.data.geo && this.data.geo.polyline) || [], l.tone) })
    },
  },
  methods: {
    _look() { return this._picked || (this._picked = look()) },
  },
})
