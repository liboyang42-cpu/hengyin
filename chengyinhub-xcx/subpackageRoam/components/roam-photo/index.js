'use strict'

// V3-36:漫游护照档案卡与官方活动大卡的实景照片。照片只放在 subpackageRoam(主包没有余量),
// 主包页面经 componentPlaceholder 异步引用本组件;来源与许可见 ../../assets/photos/SOURCES.md。
Component({
  options: { virtualHost: true },
  properties: { name: { type: String, value: '' } },
})
