// 漫游轨迹地图缩略:纯展示,坐标由调用方(scene-roam-history routeView)按 686×360rpx 算好。
Component({
  properties: {
    // { segs: [{i,x,y,len,deg}], dots: [{i,x,y,tone}], start: {x,y} | null, end: {x,y} | null, single: bool }
    route: { type: Object, value: {} },
  },
})
