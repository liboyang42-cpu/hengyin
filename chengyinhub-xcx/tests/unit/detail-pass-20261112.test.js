'use strict'
// 细节打磨(2026-11-12):共享底座与各屏细节的合同。动效只用 animate / 过渡,不新增 @keyframes;减少动态效果一律不播。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (f) => fs.readFileSync(path.join(__dirname, '../..', f), 'utf8')

test('G2 按压:.cy-pressed 压到 .97 / .92,按下 80ms,松手走 220ms 弹性 token', () => {
  const css = read('style/components.wxss'), tokens = read('style/tokens.wxss')
  const rule = /\.cy-pressed\s*\{[^}]*\}/.exec(css)[0]
  assert.match(rule, /transform:\s*scale\(\.97\)/); assert.match(rule, /opacity:\s*\.92/)
  assert.match(rule, /var\(--cy-motion-press\)/)
  assert.match(tokens, /--cy-tr-press:\s*transform var\(--cy-motion-standard\) var\(--cy-ease-spring\), opacity var\(--cy-motion-standard\)/)
  const btn = read('components/cy/btn/index.wxss')
  assert.match(btn, /\.btn--press\s*\{\s*transform:\s*scale\(0\.97\)/)
})

test('G4 读数滚动:init 首帧是 0,时间段不滚,减少动态效果直接终值', () => {
  const cu = require('../../pages/play/utils/count-roll.js')
  assert.deepEqual(cu.init({ dist: '120 米', walk: '2', hours: '09:00-17:00' }), { dist: '0 米', walk: '0', hours: '09:00-17:00' })
  assert.deepEqual(cu.init({ dist: '120 米' }, true), { dist: '120 米' })
  const page = { data: {}, setData(o) { Object.assign(this.data, o) } }
  cu.roll(page, 'shown', { dist: '120 米' }, { reduced: true })
  assert.deepEqual(page.data.shown, { dist: '120 米' })
  const calls = []; const p2 = { data: {}, setData(o) { calls.push(o) } }
  cu.roll(p2, 'shown', { dist: '1' }); cu.clear(p2)
  assert.equal(Object.keys(p2._cuRollTimers).length, 0)
})

test('G7 入场节奏:按文档顺序错开 40ms、12px 上浮、420ms;减少动态效果不播', async () => {
  const er = require('../../utils/entry-rhythm.js')
  const got = []
  const ctx = { data: {}, animate(sel, frames, dur) { got.push([sel, frames[0].translateY, frames[0].ease, dur]) } }
  er.play(ctx, ['.a', '', '.b', '.c'])
  await new Promise((r) => setTimeout(r, 120))
  assert.deepEqual(got, [['.a', 12, 'cubic-bezier(.32,.72,0,1)', 420], ['.b', 12, 'cubic-bezier(.32,.72,0,1)', 420], ['.c', 12, 'cubic-bezier(.32,.72,0,1)', 420]])
  er.play({ data: { reducedMotion: true }, animate() { throw new Error('不该播') } }, ['.a'])
  await new Promise((r) => setTimeout(r, 20))
})

test('S5 视差:0.5×、只在 0..limit,负值(回弹)与减少动态效果不动', () => {
  const px = require('../../pages/topic/utils/parallax.js')
  assert.equal(px.offsetFor(200), 100); assert.equal(px.offsetFor(-30), null); assert.equal(px.offsetFor(5000), 300)
  const got = []; const page = { data: {}, animate(sel, f, d) { got.push([sel, f[0].translateY, d]) } }
  assert.equal(px.follow(page, '.bgt-swiper', 100), true); assert.deepEqual(got[0], ['.bgt-swiper', 50, 1])
  const anchored = { data: {}, _pxTop: 400, animate(sel, f) { got.push([sel, f[0].translateY]) } }
  assert.equal(px.follow(anchored, '.x', 300, { anchor: '#heroTop' }), false, '头图还没滚到屏幕顶,不动')
  assert.equal(px.follow(anchored, '.x', 800, { anchor: '#heroTop' }), true); assert.deepEqual(got[got.length - 1], ['.x', 200])
  const off = { data: { reducedMotion: true }, animate() { throw new Error('不该动') } }
  assert.equal(px.follow(off, '.bgt-swiper', 100), false)
})

test('S2/S3/S4/S9 环境细节:帧计划合法,星尘不透明度 ≤10%,减少动态效果不起', () => {
  const amb = require('../../pages/play/components/chapter-flip/ambient.js')
  assert.equal(amb.SPARKLES >= 6 && amb.SPARKLES <= 10, true); assert.equal(amb.SPARK_MAX_OPACITY <= 0.1, true)
  for (let i = 0; i < amb.SPARKLES; i++) assert.ok(amb.sparkFrames(i).every((f) => f.opacity <= 0.1))
  const up = amb.haloFrames(true); assert.deepEqual([up[0].opacity, up[1].opacity, up[1].scale[0]], [0.4, 1, 1.12])
  assert.equal(amb.HALO_HALF_MS * 2, 3000)
  amb.start({ data: { reducedMotion: true }, animate() { throw new Error('不该起') } }, { cx: 1, cy: 1 })
  const pk = require('../../pages/play/utils/pack-detail.js')
  assert.equal(pk.SHEEN_MS, 3000); assert.equal(pk.FLOAT_PX, 6)
  assert.deepEqual(pk.squashFrames().map((f) => f.scale[0]), [1, 0.96, 1.04, 1])
  const mo = require('../../pages/play/utils/menu-open.js')
  assert.equal(mo.DURATION, 420); assert.equal(mo.EASE, 'cubic-bezier(.32,.72,0,1)')
  assert.equal(mo.open({ data: { reducedMotion: true }, animate() { throw new Error('不该起') } }), false)
})

test('不新增 @keyframes;S9 菜单行 30ms 错开;细节样式都在故事文字动效段之前', () => {
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.tools--corner \.drow:nth-child\(2\) \{ animation-delay: 30ms; \}/)
  assert.ok(wxss.indexOf('细节打磨 2026-11-12 · S9') < wxss.indexOf('/* ===== 故事文字动效'))
  for (const f of ['pages/play/components/chapter-flip/index.wxss', 'pages/play/play-stage.wxss', 'subpackageMember/signup/index.wxss', 'pages/index/index.wxss', 'pages/merchant/index/index.wxss']) {
    const tail = read(f).split('细节打磨')[1] || ''
    assert.ok(!/@keyframes/.test(tail), f + ' 不新增 @keyframes')
  }
})
