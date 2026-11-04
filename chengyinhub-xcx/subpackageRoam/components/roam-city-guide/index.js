'use strict'

const catalog = require('../../utils/roam-city-catalog.js')
const toast = require('../../../utils/toast.js')
function openAddress() { return require('../../../utils/open-address.js') }
Component({
  properties: { show: { type: Boolean, value: false, observer(value) {
    if (value) this.setData({ selected: null })
  } } },
  data: {
    cities: catalog.cities, categories: catalog.categories, cityIndex: 0,
    cityName: catalog.cities[0].name, activeCategory: 'all',
    places: catalog.listPlaces(catalog.cities[0].id, 'all'), selected: null, reviewNote: catalog.reviewNote,
  },
  methods: {
    onCityChange(event) {
      const index = Number(event.detail.value)
      if (!Number.isInteger(index) || index < 0 || index >= catalog.cities.length) return
      this.setData({
        cityIndex: index, cityName: catalog.cities[index].name, selected: null,
        places: catalog.listPlaces(catalog.cities[index].id, this.data.activeCategory),
      })
    },
    onCategoryTap(event) {
      const id = event.currentTarget.dataset.id
      if (!catalog.categories.some(row => row.id === id)) return
      this.setData({
        activeCategory: id, selected: null,
        places: catalog.listPlaces(catalog.cities[this.data.cityIndex].id, id),
      })
    },
    onPlaceTap(event) {
      const id = event.currentTarget.dataset.id
      if (!this.data.places.some(row => row.id === id)) return
      this.setData({ selected: catalog.findPlace(id) })
    },
    backToList() { this.setData({ selected: null }) },
    // 第九轮 m1-9:目录地点只有文字地址,走缓存的地址解析后开地图
    openPlace(event) { openAddress().openFromEvent(event) },
    copyAddress() {
      const place = this.data.selected
      if (!place) return
      wx.setClipboardData({
        data: place.name + '\n' + place.address,
        fail: () => toast.error('地址未复制，请再试一次'),
      })
    },
    copySources() {
      const place = this.data.selected
      if (!place) return
      wx.setClipboardData({
        data: place.sources.join('\n'),
        fail: () => toast.error('来源未复制，请再试一次'),
      })
    },
    close() { this.triggerEvent('close') },
    blockTouch() {},
  },
})
