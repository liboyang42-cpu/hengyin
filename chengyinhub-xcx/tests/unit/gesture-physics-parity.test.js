// qx-u1:WXS 里的物理是 utils/gesture-physics.js 的镜像(WXS 不能 require JS)。
// 两边在同一组输入上逐值比对;改了一边忘了另一边就判红。负控:改掉 WXS 的一个常数,比对必须失败。
const assert = require('node:assert/strict')
const test = require('node:test')
const P = require('../../utils/gesture-physics.js')
const { loadWxs } = require('./helpers/gesture-wxs-harness.js')

const FNS = ['resist', 'velocity', 'detentOffsets', 'nearest', 'sheetRelease', 'swipeRelease']

function cases() {
  const out = []
  for (const raw of [-300, -40, -1, 0, 1, 50, 179, 181, 400, 900]) {
    for (const reduced of [false, true]) out.push(['resist', [raw, 0, reduced]])
  }
  for (const [a, b, c, d] of [[null, null, 0, 10], [0, 0, 30, 16], [100, 5, 90, 5], [10, 0, 60, 100]]) {
    out.push(['velocity', [a, b, c, d]])
  }
  for (const lv of ['', 'full', 'half', 'full,half', 'half,peek', 'peek, half ,x']) {
    for (const [ph, wh] of [[0, 800], [480, 800], [640, 800], [700, 844]]) out.push(['detentOffsets', [lv, ph, wh]])
  }
  out.push(['nearest', [[0, 160, 400], 79]], ['nearest', [[0, 160, 400], 81]], ['nearest', [[0], -30]])
  const dets = [[0], [0, 160], [0, 160, 400]]
  for (const det of dets) {
    for (const off of [-30, 0, 40, 100, 170, 300, 420, 500]) {
      for (const v of [-1, -0.5, 0, 0.3, 0.5, 0.51, 2]) {
        for (const h of [0, 600, 640]) out.push(['sheetRelease', [off, v, h, det]])
      }
    }
  }
  for (const dx of [0, 30, 116, 118, 300]) {
    for (const v of [-1, 0, 0.5, 0.6]) for (const w of [0, 390]) out.push(['swipeRelease', [dx, v, w]])
  }
  return out
}

function compare(wxs) {
  for (const [fn, args] of cases()) {
    // vm 里造出来的数组 / 对象原型不同,先 JSON 归一再比
    const norm = (v) => JSON.parse(JSON.stringify(v === undefined ? null : v))
    assert.deepEqual(norm(wxs[fn](...args)), norm(P[fn](...args)), `${fn}(${JSON.stringify(args)}) 两边不一致`)
  }
}

test('WXS 镜像导出了全部物理函数', () => {
  const wxs = loadWxs()
  for (const fn of FNS) assert.equal(typeof wxs[fn], 'function', `gesture.wxs 缺 ${fn}`)
})

test('WXS 与 utils/gesture-physics.js 在同一组输入上逐值相等', () => {
  compare(loadWxs())
})

test('WXS 与 JS 的常数逐字一致', () => {
  const fs = require('node:fs')
  const path = require('node:path')
  const wxsSrc = fs.readFileSync(path.resolve(__dirname, '../../utils/wxs/gesture.wxs'), 'utf8')
  const jsSrc = fs.readFileSync(path.resolve(__dirname, '../../utils/gesture-physics.js'), 'utf8')
  for (const k of ['DISMISS_VELOCITY', 'DISMISS_RATIO', 'RUBBER', 'TAP_SLOP', 'EDGE', 'LEVEL_RATIO']) {
    const re = new RegExp(`var ${k} = ([^;]+);`)
    const a = re.exec(wxsSrc); const b = re.exec(jsSrc)
    assert.ok(a && b, `两边都要有 var ${k}`)
    assert.equal(a[1], b[1], `${k} 两边不一致`)
  }
})

test('负控:WXS 的阻尼系数被改掉时比对必须判红', () => {
  const wxs = loadWxs((s) => s.replace('var RUBBER = 0.25;', 'var RUBBER = 0.5;'))
  assert.throws(() => compare(wxs))
})

test('负控:WXS 的速度闸被改掉时比对必须判红', () => {
  const wxs = loadWxs((s) => s.replace('var DISMISS_VELOCITY = 0.5;', 'var DISMISS_VELOCITY = 0.55;'))
  assert.throws(() => compare(wxs))
})
