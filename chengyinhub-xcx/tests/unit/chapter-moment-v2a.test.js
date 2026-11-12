'use strict'
// 动效 v2 lane v2-a:章节开始(时刻 1)与通关(时刻 3)的构建器、时间轴、组件序列。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')
const M = require(path.join(ROOT, 'pages/play/utils/chapter-moment.js'))
const { buildFinale, finaleKicker } = require(path.join(ROOT, 'pages/play/utils/chapter-flip-data.js'))
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('缓动:端点、单调、和 CSS cubic-bezier 的已知值一致', () => {
  const f = M.bezier(...M.EASE.nav)
  assert.equal(f(0), 0); assert.equal(f(1), 1)
  let prev = 0
  for (let i = 1; i <= 20; i++) { const v = f(i / 20); assert.ok(v >= prev); prev = v }
  assert.ok(f(0.5) > 0.85, 'nav 曲线前快后慢')
  assert.ok(M.bezier(...M.EASE.spring)(0.5) > 1, '弹簧曲线会冲过 1')
})

test('sampleFrames:带 offset 的多帧,首尾值精确,多段关键帧按时间线整形', () => {
  const fr = M.sampleFrames({ opacity: [[0, 0], [.3, 1], [1, 0]], scale: [[0, .4], [1, 1.1]] }, null, 10)
  assert.equal(fr.length, 11)
  assert.equal(fr[0].offset, 0); assert.equal(fr[10].offset, 1)
  assert.equal(fr[0].opacity, 0); assert.equal(fr[3].opacity, 1); assert.equal(fr[10].opacity, 0)
  assert.deepEqual(fr[0].scale, [.4, .4]); assert.deepEqual(fr[10].scale, [1.1, 1.1])
  assert.ok(fr.every((k) => k.ease === 'linear'), '只用 wx animate 支持的缓动')
})

test('点阵数字:和原型同几何(格 12、点半径 4.6),次序 o = 列 + 行*2', () => {
  const m = M.dotMatrix('01')
  assert.ok(m.dots.length > 20)
  assert.ok(Math.abs(m.scale - 120 / 132) < 1e-9, '两位数字 viewBox 132×84 缩进 120×90')
  assert.ok(m.dots.every((d) => d.x > 0 && d.x < 220 && d.y > 0 && d.y < 220), '都在 220 方框内')
  assert.ok(Math.min(...m.dots.map((d) => d.o)) <= 2, '最早亮的是左上角')
  assert.deepEqual(M.dotMatrix('').dots, [], '序章没有数字')
  assert.equal(M.dotMatrix('7').dots.length > 0, true)
})

test('章节印外环:28 点,每点错 14ms', () => {
  const r = M.ringDots()
  assert.equal(r.length, 28)
  assert.equal(r[27].delay, 27 * 14)
  assert.equal(r[7].a, 90)
})

test('章名逐字:按「·」与空格切,长词均分,英文不拆', () => {
  assert.deepEqual(M.splitTitle('第一章 · 星尘落点'), ['第一章', ' · ', '星尘', '落点'])
  assert.deepEqual(M.splitTitle('序章'), ['序章'])
  assert.deepEqual(M.splitTitle(''), [])
  assert.deepEqual(M.splitTitle('Old Town'), ['Old', ' ', 'Town'])
  assert.equal(M.splitTitle('人民广场寻字').join(''), '人民广场寻字')
})

test('章节开始时间轴:光点 0 → 环 300 → 光晕 1100 → 眉标 1300 → 逐字 180ms → 读数 → 按钮最后', () => {
  const t = M.startTimes(4)
  assert.equal(M.START.ringAt, 300); assert.equal(M.START.haloAt, 1100)
  assert.deepEqual(t.words, [1480, 1660, 1840, 2020])
  assert.equal(t.stats, 2270); assert.equal(t.cta, 2770)
  assert.ok(t.cta > t.stats && t.stats > t.words[3] && t.words[0] > t.kick, '按钮最后')
  const many = M.startTimes(12)
  assert.ok(many.words[11] <= M.START.kickAt + M.START.wordSpan + 60, '词多了压缩进 720ms')
})

test('读数滚动:mm:ss / a/b / 小数 / 千分位,终值逐字等于原文,起点从 0 起', () => {
  const run = (raw, e) => M.statAt(M.parseStat(raw), e, raw)
  assert.equal(run('05:56', 1), '05:56'); assert.equal(run('05:56', 0), '00:00')
  assert.equal(M.statStart('2/4'), '0/4'); assert.equal(run('2/4', 1), '2/4')
  assert.equal(M.statStart('1.2 km'), '0.0 km'); assert.equal(run('1.2 km', 1), '1.2 km')
  assert.equal(M.statStart('2,680'), '0'); assert.equal(run('2,680', .5), '1,340')
  assert.equal(M.statStart('—'), '—', '读不出数的原样不滚')
  assert.equal(M.statStart('1:07:24'), '1:07:24')
  assert.equal(M.countEase(0), 0); assert.equal(M.countEase(1), 1)
})

test('buildStart:读数最多三格、空值不出;没有章节号就没有点阵', () => {
  const v = M.buildStart({ number: '02', kicker: '第 2 章 / 共 3 章', title: '提篮桥 · 五个地点', stats: [{ v: '5', k: '坐标' }, { v: '', k: '距离' }, { v: '40 分', k: '预计' }, { v: '1', k: 'x' }, { v: '2', k: 'y' }] })
  assert.equal(v.stats.length, 3); assert.deepEqual(v.stats.map((s) => s.k), ['坐标', '预计', 'x'])
  assert.ok(v.digits.length > 0 && v.ring.length === 28)
  assert.equal(M.buildStart({ number: '', title: '序章' }).digits.length, 0)
})

test('通关:粒子 40 颗确定性轨迹,错峰 12ms;时间轴 flash 900 → 勋章 1100 → 眉标 1700 → 按钮 2550', () => {
  const a = M.particles(), b = M.particles()
  assert.equal(a.length, 40); assert.deepEqual(a, b)
  assert.equal(a[39].delay, 39 * 12)
  assert.ok(a.every((p) => { const d = Math.hypot(p.x, p.y); return d >= 179 && d <= 301 }))
  const F = M.FINALE
  assert.ok(F.flashAt < F.medalAt && F.medalAt < F.kickAt && F.kickAt < F.ttlAt && F.ttlAt < F.rowAt && F.rowAt < F.actsAt, '按钮最后')
  assert.equal(F.chargeDur, 900)
})

test('通关数据:眉标「已通关 · M月D日」,标题与合计沿用原构建器', () => {
  assert.equal(finaleKicker(new Date(2026, 10, 12)), '已通关 · 11月12日')
  const f = buildFinale({ cards: [], nodes: [], topicName: '纸本旧书', doneCount: 4, elapsed: '27:00', now: new Date(2026, 10, 12) })
  assert.equal(f.info.kicker, '已通关 · 11月12日'); assert.equal(f.info.title, '纸本旧书')
  const v = M.buildFinaleView(f.info)
  assert.deepEqual(v.tiles, [{ k: '坐标', v: '4' }, { k: '用时', v: '27:00' }])
})

function load() {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  const p = path.join(ROOT, 'pages/play/components/chapter-moment/index.js')
  delete require.cache[require.resolve(p)]; require(p); global.Component = prev
  return def
}
function inst(def, props) {
  const timers = [], played = []
  const o = {
    data: JSON.parse(JSON.stringify(def.data)), events: [], timers, played, cleared: [],
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
    triggerEvent(n) { this.events.push(n) },
    animate(sel, frames, dur, cb) { played.push({ sel, dur, n: frames.length }); if (cb) cb() },
    clearAnimation(sel, opt, cb) { this.cleared.push(sel); if (cb) cb() },
  }
  Object.assign(o.data, props)
  Object.keys(def.methods).forEach((k) => { o[k] = def.methods[k].bind(o) })
  o._timers = []; o._anims = new Set()
  o._at = (fn, ms) => { timers.push({ fn, ms }) }
  return o
}

test('组件序列(开始):按时间轴排好计时器,按钮最后;点任意处直接终态,再点才进正文', () => {
  const def = load(), c = inst(def, { mode: 'start' })
  c.data.view = M.buildStart({ number: '01', kicker: '第 1 章 / 共 2 章', title: '第一章 · 星尘落点', stats: [{ v: '05:56', k: '用时' }] })
  c._playStart()
  const ms = c.timers.map((t) => t.ms)
  assert.deepEqual(ms.slice(0, 2), [300, 500])
  assert.equal(Math.max(...ms), 2770, '最后一个计时器 = 按钮升起')
  assert.ok(c.played.some((p) => p.sel === '#dot' && p.dur === 420))
  assert.equal(c.data.ctaOn, false); assert.ok(!c._settled)
  c.onTap()
  assert.equal(c._settled, true); assert.equal(c.data.ctaOn, true); assert.equal(c.data.wordsOn, 4)
  assert.equal(c.data.shown[0], '05:56', '跳过后读数为终值')
  assert.deepEqual(c.events, [])
  c.onTap()
  assert.deepEqual(c.events, ['go'])
})

test('组件序列(通关):按钮最后才可点;跳过后一样;查看结算 / 保存各发各的事件', () => {
  const def = load(), c = inst(def, { mode: 'finale' })
  c.data.view = M.buildFinaleView({ kicker: '已通关 · 11月12日', title: 'x', stats: [{ v: '1', k: '坐标' }] })
  c._playFinale()
  assert.equal(Math.max(...c.timers.map((t) => t.ms)), 2550)
  assert.ok(c.played.some((p) => p.sel === '#orb' && p.dur === 900))
  assert.equal(c.data.actsOn, false)
  c.onTap(); assert.equal(c.data.actsOn, true); assert.equal(c.data.medalOn, true)
  c.onTap(); assert.deepEqual(c.events, [], '终态后点空白处通关屏不响应')
  c.onSave(); c.onClose(); assert.deepEqual(c.events, ['save', 'continue'])
})

test('减少动态:ready 直接落终态,不排计时器', () => {
  const src = read('pages/play/components/chapter-moment/index.js')
  assert.match(src, /if \(this\.data\.reducedMotion\) \{ this\._end\(false\)/)
})

test('结构:通关只有一个主角,旧的标志 / 残影 / 碎片不再存在;不写 @keyframes', () => {
  const flip = read('pages/play/components/chapter-flip/index.js') + read('pages/play/components/chapter-flip/index.wxml') + read('pages/play/components/chapter-flip/index.wxss')
  for (const dead of ['LOGO_SRC', '_drawPyramid', '_makeShards', '_assembleLogo', 'cf__copy', 'cf--finale .cf__c']) assert.ok(!flip.includes(dead), dead)
  const css = read('pages/play/components/chapter-moment/index.wxss')
  assert.ok(!/@keyframes/.test(css)); assert.ok(!/@keyframes/.test(read('pages/play/components/chapter-flip/index.wxss')))
  const wxml = read('pages/play/components/chapter-moment/index.wxml')
  assert.match(wxml, /aria-label="保存图片"/); assert.match(wxml, /open-type="share"/); assert.match(wxml, /aria-label="查看结算"/)
  assert.match(read('pages/play/index.wxml'), /<cy-chapter-flip wx:if="\{\{showFinish && finale\.show\}\}" mode="finale"/)
  assert.match(read('pages/play/components/chapter-flip/index.wxml'), /cy-chapter-moment/)
})
