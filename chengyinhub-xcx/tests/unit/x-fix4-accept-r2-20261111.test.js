// x-fix4(2026-11-11 验收第二轮 ACCEPT-ISSUES-R2-20261111)回归
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '')

test('N-1 故事流「继续探索」避开 Home 条:收尾区与底垫都含安全区', () => {
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.chfull__pad\{height:calc\(440rpx \+ env\(safe-area-inset-bottom\)\);\}/)
  assert.match(wxss, /\.chfull__end\{ padding:42rpx 46rpx env\(safe-area-inset-bottom\); \}/)
  assert.match(read('pages/play/play-topbar.wxss'), /\.chfull\.is-navmap \.chfull__pad\{ height:calc\(560rpx \+ env\(safe-area-inset-bottom\)\); \}/)
})

test('N-6 骰子内嵌块与整屏台面读外观色,不再是暗色外观里的白矩形', () => {
  const w = read('pages/play/components/playkit-diceroll/index.wxss')
  const dzi = w.match(/\.dzi \{[^}]+\}/)[0]
  assert.match(dzi, /background: var\(--pk-embed-bg, radial-gradient/)
  assert.match(dzi, /color: var\(--pk-embed-ink, #2a2540\)/)
  assert.match(dzi, /border-radius: var\(--pk-qt-radius, 0\)/)
  assert.match(w.match(/\.dz \{[^}]+\}/)[0], /background: var\(--pk-embed-bg, radial-gradient/)
  assert.match(w.match(/\.dzi__roll \{[^}]+\}/)[0], /background: var\(--pk-embed-ink, #2a2540\)/)
})

test('N-7 玩家页根在 look 下给出 --pk-embed-*;抽卡屏/详情读外观色,不再亮蓝/浅灰', () => {
  for (const f of ['pages/play/look-tokens.wxss', 'pages/publish/fabu/look-tokens.wxss']) {
    const t = read(f)
    assert.match(t, /\.play\[class\*="look--"\] \{ --pk-embed-bg:var\(--lk-bg\); --pk-embed-ink:var\(--lk-ink\);/)
    assert.match(t, /--pk-embed-accent:var\(--lk-a\);/)
  }
  const r = read('pages/play/components/playkit-random/index.wxss')
  assert.doesNotMatch(r, /background: #0b6adc;/)
  assert.doesNotMatch(r, /background: #(e6e3e4|f1f0f0|e0dede);/)
  assert.match(r, /background: var\(--lk-card, #e6e3e4\)/)
  assert.match(r, /background: var\(--lk-card, #f1f0f0\)/)
  assert.match(read('pages/play/style/play-surface.wxss'), /--ci-scrim:var\(--pk-embed-scrim,/)
  // 其余弹层与玩法台面一并读外观色
  assert.match(read('pages/play/components/playkit-bingo/index.wxss'), /\.bg__cell\.pale \{ background: var\(--lk-ink/)
  assert.match(read('pages/play/components/fragment/index.wxss'), /var\(--lk-pop,/)
  assert.doesNotMatch(read('pages/play/components/playkit-stopwatch/index.wxss'), /background: rgba\(255, 255, 255, \.92\);/)
})

test('N-9 结算页没带 topicName 时按主题 id 补主题名,标题不落「本局」', () => {
  const js = read('subpackageJourney/pages/settle/index.js')
  assert.match(js, /_fillTopicName\(report\.topicId \|\| this\._query\.topicId\)/)
  assert.match(js, /url: '\/api\/topic\/info-to-user'/)
  const calls = []
  const stub = { sendRequest: (o) => calls.push(o) }
  const ctx = { data: { topicName: '', report: { x: 1 } }, _gone: false, setData(d) { Object.assign(this.data, d) } }
  // 取页面对象里的方法在受限沙箱里执行
  let page
  const Module = require('node:module')
  const m = new Module(path.join(ROOT, 'subpackageJourney/pages/settle/index.js'))
  m.filename = path.join(ROOT, 'subpackageJourney/pages/settle/index.js')
  m.paths = Module._nodeModulePaths(path.dirname(m.filename))
  global.Page = (o) => { page = o }
  global.getApp = () => stub
  m._compile(js, m.filename)
  delete global.Page; delete global.getApp
  page._fillTopicName.call(ctx, '9912702')
  assert.equal(calls.length, 1)
  calls[0].success({ code: 200, data: { name: '纸本旧书' } })
  assert.equal(ctx.data.topicName, '纸本旧书')
  const none = { ...ctx, data: { topicName: '已有' } }
  page._fillTopicName.call(none, '9912702')
  assert.equal(calls.length, 1, '已有主题名不再请求')
})
