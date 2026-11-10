// qx-u1(2026-11-10):全屏层侧滑返回 —— 相册(playkit-album)与剧情预览(fabu .sp)
//
// 左缘起手,横向跟手;松手「速度 > 0.5 px/ms 或位移 > 屏宽 30%」就滑出并在过渡结束时调宿主原来的关闭方法,
// 否则弹回。竖着走或往左 ⇒ 不是返回手势,整段放手(相册左右翻页、预览上下滚动照常)。减动效直接关。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { loadWxs, makeEl, makeOwner, edgeDrag } = require('./helpers/gesture-wxs-harness.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const last = (el) => el.styles[el.styles.length - 1] || {}
const xOf = (s) => { const m = /translate3d\((-?[\d.]+)(px|%)?,0,0\)/.exec(s.transform || ''); return m ? (m[2] === '%' ? m[1] + '%' : Number(m[1])) : null }
const settle = (gs, owner, layer) => gs.edgeSettle({ target: { dataset: { glayer: '1' } }, instance: layer }, owner)

test('慢拖过屏宽 30% ⇒ 滑出,过渡结束才调宿主的关闭方法', () => {
  const gs = loadWxs(); const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { steps: Array(13).fill(10), stepMs: 100 })   // 130px,0.1 px/ms
  assert.equal(xOf(last(layer)), '100%')
  assert.equal(owner.calls.length, 0, '滑出动画没播完不能先关(否则直接消失)')
  settle(gs, owner, layer)
  assert.deepEqual(owner.calls.map((c) => c.name), ['close'])
})

test('快甩 ⇒ 关(速度闸)', () => {
  const gs = loadWxs(); const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { steps: [15, 15], stepMs: 16 })
  assert.equal(xOf(last(layer)), '100%')
})

test('没过阈值 ⇒ 弹回,过渡结束撤掉行内样式,不关', () => {
  const gs = loadWxs(); const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { steps: Array(6).fill(10), stepMs: 100 })
  assert.equal(xOf(last(layer)), 0)
  settle(gs, owner, layer)
  assert.deepEqual(last(layer), { transform: '', transition: '' })
  assert.equal(owner.calls.length, 0)
})

test('不从左缘起手 ⇒ 不是返回手势', () => {
  const gs = loadWxs(); const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { x0: 120, steps: Array(20).fill(20), stepMs: 16 })
  assert.equal(layer.styles.length, 0)
})

test('竖着走(上下滚动)⇒ 整段放手', () => {
  const gs = loadWxs(); const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { steps: [3, 3, 3], dyPerStep: 20, stepMs: 16 })
  assert.equal(layer.styles.length, 0)
})

test('减动效:过阈值直接关,不播滑出', () => {
  const gs = loadWxs(); const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { steps: Array(13).fill(10), stepMs: 100, ds: { rm: true } })
  assert.deepEqual(owner.calls.map((c) => c.name), ['close'])
})

test('负控:拆掉位移闸,慢拖用例必须判红', () => {
  const gs = loadWxs((s) => s.replace('var DISMISS_RATIO = 0.3;', 'var DISMISS_RATIO = 1e9;'))
  const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { steps: Array(13).fill(10), stepMs: 100 })
  assert.equal(xOf(last(layer)), 0)
})

test('负控:不限左缘时,非左缘用例必须判红', () => {
  const gs = loadWxs((s) => s.replace('if (!t || t.clientX > EDGE) return;', 'if (!t) return;'))
  const layer = makeEl({ width: 390 }); const owner = makeOwner({})
  edgeDrag(gs, owner, layer, { x0: 120, steps: Array(20).fill(20), stepMs: 16 })
  assert.ok(layer.styles.length > 0)
})

test('相册接线:根层挂 edge* 处理器,关闭方法就是「收起相册」的 close;读减动效', () => {
  const wxml = read('pages/play/components/playkit-album/index.wxml')
  assert.match(wxml, /<wxs module="gs" src="\.\.\/\.\.\/\.\.\/\.\.\/utils\/wxs\/gesture\.wxs" \/>/)
  const root = /<view class="album" wx:if="\{\{show\}\}"[\s\S]*?>/.exec(wxml)
  assert.ok(root)
  for (const k of ['bindtouchstart="{{gs.edgeStart}}"', 'bindtouchmove="{{gs.edgeMove}}"', 'bindtouchend="{{gs.edgeEnd}}"',
    'bindtouchcancel="{{gs.edgeEnd}}"', 'bindtransitionend="{{gs.edgeSettle}}"', 'data-glayer="1"', 'data-close="close"', 'data-rm="{{reducedMotion}}"']) {
    assert.ok(root[0].includes(k), `相册根层缺 ${k}`)
  }
  assert.match(read('pages/play/components/playkit-album/index.js'), /behaviors: \[reducedMotionBehavior\]/)
})

test('相册翻页让出左缘:从左缘起手不翻页', () => {
  const js = read('pages/play/components/album-glass/index.js')
  assert.match(js, /const \{ EDGE \} = require\('\.\.\/\.\.\/\.\.\/\.\.\/utils\/gesture-physics\.js'\);/)
  assert.match(js, /if \(t\.clientX <= EDGE\) return;/)
})

test('剧情预览接线:.sp 挂 edge* 处理器,关闭方法是左上 ✕ 的 closePublishCheck', () => {
  const wxml = read('pages/publish/fabu/index.wxml')
  assert.match(wxml, /<wxs module="gs" src="\.\.\/\.\.\/\.\.\/utils\/wxs\/gesture\.wxs" \/>/)
  const sp = /<view class="sp" [^>]*wx:if="\{\{publishCheck\.show\}\}"[\s\S]*?>/.exec(wxml)
  assert.ok(sp)
  for (const k of ['catchtouchmove="{{gs.edgeMove}}"', 'bindtransitionend="{{gs.edgeSettle}}"', 'data-close="closePublishCheck"', 'data-rm="{{reducedMotion}}"']) {
    assert.ok(sp[0].includes(k), `.sp 缺 ${k}`)
  }
  assert.match(read('pages/publish/fabu/index.js'), /closePublishCheck\(\) \{/)
})
