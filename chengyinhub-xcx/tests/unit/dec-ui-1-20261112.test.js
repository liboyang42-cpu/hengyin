'use strict'
// DEC-UI-1(业主 2026-11-11 决定页):D-63 失败图落地不抖 · D-56 翻页卡可左右滑。
// D-38 / D-46 / D-78 / D-119 的合同分别在 f-compliance-launch、l-play-20261110 / chapter-flip-round3、fabu-server-draft、blank-value-display-contract 等文件里。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('D-63:失败插图落地后不再抖(e1–e3 抖动规则与时间表都去掉),落地与裂开保持', () => {
  for (const dir of ['pages/play', 'subpackageJourney', 'subpackageMember', 'subpackageP3']) {
    const css = read(dir + '/components/cy-illus/index.wxss')
    assert.doesNotMatch(css, /\.ib--fail\.e[23] /, dir + ' 没有抖动阶段')
    assert.doesNotMatch(css, /\.ib--fail\.e1 \.x-tile/, dir + ' 方砖落地后不再摆动')
    assert.match(css, /\.ib--fail\.is-on \.x-tile \{ opacity: 1; transform: none;/, dir + ' 落地保留')
    assert.match(css, /\.ib--fail\.e1 \.x-hl/, dir + ' 裂开保留')
    assert.match(read(dir + '/components/cy-illus/art-b.js'), /fail: \{ enter: \[1000\],/, dir + ' 时间表只剩裂开')
  }
})

function accusation() {
  let def
  global.Component = (d) => { def = d }
  global.Behavior = (o) => o; global.wx = global.wx || {}
  delete require.cache[require.resolve('../../pages/play/components/playkit-accusation/index.js')]
  require('../../pages/play/components/playkit-accusation/index.js')
  const timers = []
  const realSet = global.setTimeout
  global.setTimeout = (fn) => { timers.push(fn); return timers.length }
  const inst = Object.assign({ data: { suspects: [{ id: 1 }, { id: 2 }, { id: 3 }], cur: 0, confirmOpen: false, reducedMotion: true }, setData(p) { Object.assign(this.data, p) } }, def.methods)
  return { inst, timers, restore: () => { global.setTimeout = realSet } }
}
const touch = (x, y) => ({ touches: [{ clientX: x, clientY: y }], changedTouches: [{ clientX: x, clientY: y }] })

test('D-56:翻页卡左滑 = 下一位,右滑 = 上一位;短滑与竖向滑动不翻,点按照旧', () => {
  const { inst, timers, restore } = accusation()
  try {
    const swipe = (from, to) => { inst.onDeckTouchStart(touch(...from)); inst.onDeckTouchEnd(touch(...to)); while (timers.length) timers.shift()() }
    swipe([300, 200], [200, 205])
    assert.equal(inst.data.cur, 1, '左滑翻到下一位')
    swipe([100, 200], [220, 190])
    assert.equal(inst.data.cur, 0, '右滑翻回上一位')
    swipe([100, 200], [110, 200])
    assert.equal(inst.data.cur, 0, '位移不够不翻')
    swipe([200, 100], [140, 260])
    assert.equal(inst.data.cur, 0, '竖向为主(卡内滚动)不翻')
    swipe([100, 200], [20, 200]); swipe([100, 200], [20, 200]); swipe([100, 200], [20, 200])
    assert.equal(inst.data.cur, 0, '到末尾回到第一位')
    inst.onFlipNext(); while (timers.length) timers.shift()()
    assert.equal(inst.data.cur, 1, '点按仍翻下一位')
  } finally { restore() }
  const wxml = read('pages/play/components/playkit-accusation/index.wxml')
  assert.match(wxml, /class="ac__deck"[^>]*bindtouchstart="onDeckTouchStart" bindtouchend="onDeckTouchEnd"/)
  assert.match(wxml, /bindtap="onFlipNext"/)
  assert.doesNotMatch(read('pages/play/components/playkit-accusation/index.wxss'), /@keyframes/)
})
