'use strict'

// 漫游地点卡的「推荐理由 + 资料来源」查表。目录数据只在 subpackageRoam,主包地点卡经占位异步引用本组件;
// 本组件不自绘,查到后把文字交回页面,由地点卡沿用自己的 .pc-body 行渲染(深浅卡片配色都跟页面走)。
const { placeNoteForTags } = require('../../utils/roam-city-catalog.js')
Component({
  options: { virtualHost: true },
  properties: {
    tags: { type: null, value: '', observer(value) {
      const key = JSON.stringify(value)
      if (this._noteTags === key) return
      this._noteTags = key
      const note = placeNoteForTags(value)
      this.triggerEvent('note', { tags: value, reason: note ? note.reason : '', sources: note ? note.sourceText : '', sourceList: note ? note.sources : [] })
    } },
  },
})
