'use strict'

// 「本城推荐」查表:目录数据只在分包,主包入口地图经占位异步引用本组件;不自绘,查到后把整条交回页面。
const { nearestCity, stripFor, defaultCity } = require('../../utils/roam-city-guide.js')
Component({
  options: { virtualHost: true },
  properties: {
    lat: { type: null, value: null, observer() { this._emit() } },
    lng: { type: null, value: null, observer() { this._emit() } },
  },
  lifetimes: { attached() { this._emit() } },
  methods: {
    _emit() {
      if (this._pending) return
      this._pending = true
      Promise.resolve().then(() => { this._pending = false; this._run() })
    },
    _run() {
      const near = nearestCity({ lat: this.data.lat, lng: this.data.lng })
      // inCity:地图正对着这座城;没有就退回默认城市的推荐,但不写「<城市> · N 处」横幅
      const strip = stripFor(near || defaultCity())
      this.triggerEvent('strip', { inCity: !!near, strip })
    },
  },
})
