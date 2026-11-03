'use strict'

// 漫游地点卡里的「推荐理由 + 资料来源」两行。目录数据只在 subpackageRoam,主包地点卡经占位异步引用。
const { placeNoteForTags } = require('../../utils/roam-city-catalog.js')
Component({
  options: { virtualHost: true },
  properties: {
    tags: { type: null, value: '', observer(value) { this.setData({ note: placeNoteForTags(value) }) } },
  },
  data: { note: null },
})
