// 第十轮 f3 第 4 项(p3 F10):选择玩法半屏入场时(缩放动画中)量到的分组位置偏小,
// 滚动高亮必须按真实滚动宽换算,不得把高亮推到后面的分组。
const test = require('node:test')
const assert = require('node:assert/strict')
const { methods } = require('../../pages/publish/temp/r5-editor.js')

function ctx(extra) {
  return Object.assign({ data: { sheetGroup: 0 }, setData(patch) { Object.assign(this.data, patch) } }, extra)
}

test('入场缩放时量到的位置按真实滚动宽换算:停在第 2 列就亮第 2 列', () => {
  // 半屏 0.5 倍缩放时量到:三列左边 0/100/200,内宽 300;真实滚动宽 600
  const self = ctx({ _tblockLefts: [0, 100, 200], _tstripInnerWidth: 300, _tstripWidth: 150 })
  methods.onGameStripScroll.call(self, { detail: { scrollLeft: 200, scrollWidth: 600 } })
  assert.equal(self.data.sheetGroup, 1)
})

test('负控:量到的就是真实尺寸时不换算,同一滚动位置亮第 3 列', () => {
  const self = ctx({ _tblockLefts: [0, 100, 200], _tstripInnerWidth: 600, _tstripWidth: 150 })
  methods.onGameStripScroll.call(self, { detail: { scrollLeft: 200, scrollWidth: 600 } })
  assert.equal(self.data.sheetGroup, 2)
})
