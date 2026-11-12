'use strict'
// l-iconsB(2026-11-11,业主「both ok」):cy-illus 风格 B v2 精致插画 + 通行证页标准样式。
// 这里钉:原型逐字、分层能拼回整枚、时间表与样式阶段一一对应、不新增关键帧、减少动态效果直接终态、组件按时间表加类。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const XCX = path.join(__dirname, '..', '..')
const DIR = path.join(XCX, 'pages/play/components/cy-illus')
const art = require('../../pages/play/components/cy-illus/art.js')
const B = require('../../pages/play/components/cy-illus/art-b.js')
const CSS = fs.readFileSync(path.join(DIR, 'index.wxss'), 'utf8')
const SRC = fs.readFileSync(path.join(DIR, 'art-b.js'), 'utf8')

test('风格 B 图形逐字来自原型 icons-b.js 第 7–80 行(sha256 与原型分支同一段一致)', () => {
  const m = /\/\/ ---- 原型逐字\(icons-b\.js 第 7–80 行\)----\n([\s\S]*?)\/\/ ---- 原型逐字结束/.exec(SRC)
  assert.ok(m, '原型逐字段落标记还在')
  const sha = crypto.createHash('sha256').update(m[1]).digest('hex')
  assert.equal(sha, '923ccaa05d25e4ee0f52870e5a416bae993a9accd501673de4b9c5d47f3d9f3c')
  assert.deepEqual(B.LIST, ['clue', 'badge', 'key', 'success', 'fail', 'wait', 'team', 'pass', 'points', 'seal'])
  assert.deepEqual(Object.keys(B.C).filter((k) => !/D$/.test(k)), ['lime', 'sky', 'coral', 'gold', 'vio', 'paper', 'grey'])
})

test('十枚名字不变,调用处自动换图;D-118:其余 15 枚也是风格 B(旧画法已删);未知名仍回落线索', () => {
  assert.equal(art.LIST.length, 25)
  for (const n of B.LIST) { assert.ok(art.isB(n), n); assert.equal(art.layers(n).b2, true, n) }
  // D-118:另外 15 枚补画成风格 B,不再有旧画法
  for (const n of B.LIST2) { assert.ok(art.has(n)); assert.equal(art.layers(n).b2, true, n) }
  assert.equal(B.LIST2.length, 15)
  assert.deepEqual([...B.LIST, ...B.LIST2].sort(), [...art.LIST].sort())
  assert.equal(art.layers('nope').name, 'clue')
  assert.equal(art.layers('nope').b2, true)
  // 只要一张图的宿主(分享卡等)照样拿 fg
  assert.ok(art.layers('pass').fg.startsWith('data:image/svg+xml;charset=utf8,'))
})

test('分层后逐层拼回去 = 原型整枚(只差会动的组外面那层 <g> 包装),每层都是合法 XML', () => {
  for (const n of B.LIST) {
    const strip = (s) => s.replace(/<g class="[^"]*">|<\/g>/g, '')
    const joined = B.split(n).map((l) => l.parts.map((p) => p.svg).join('')).join('')
    assert.equal(strip(joined), strip(B.D[n]), n)
    for (const l of art.layers(n).layers) {
      for (const p of l.parts) {
        const svg = decodeURIComponent(p.src.split(',').slice(1).join(','))
        for (const m of svg.matchAll(/<([a-zA-Z][\w-]*)((?:\s+[\w:-]+="[^"]*")*)\s*\/?>/g)) {
          const names = [...m[2].matchAll(/\s+([\w:-]+)=/g)].map((x) => x[1])
          assert.equal(new Set(names).size, names.length, n + ' <' + m[1] + '> 有重复属性')
        }
      }
    }
  }
})

test('原型里会动的部件都单独成层', () => {
  const keys = (n) => B.split(n).flatMap((l) => [l.k].concat(l.parts.map((p) => p.k))).filter(Boolean)
  const want = {
    clue: ['gd', 'note', 'mg', 'glint', 'found', 'found-f2'], badge: ['rib', 'md', 'burst', 'burst-0', 'burst-3'], key: ['kb', 'gem', 'click', 'click-f2'],
    success: ['tile', 'ck', 'conf', 'conf-4'], fail: ['tile', 'hl', 'hr', 'cloud', 'drop'], wait: ['hg', 'sand-t', 'sand-b', 'grain', 'grain-g2', 'grain-gr3', 'glint'],
    team: ['p1', 'p2', 'p3', 'heart'], pass: ['card', 'valid', 'lan'], points: ['c1', 'c2', 'c3', 'plus'], seal: ['stp', 'imp', 'ink', 'ink-i2', 'stamped'],
  }
  for (const n of B.LIST) for (const k of want[n]) assert.ok(keys(n).includes(k), n + ' 缺 ' + k)
})

test('时间表与样式一一对应:每个阶段类都有规则,样式里没有时间表之外的阶段', () => {
  for (const n of B.LIST) {
    const m = B.MOTION[n]
    assert.ok(m, n + ' 有时间表')
    const used = (p) => new Set([...CSS.matchAll(new RegExp('\\.ib--' + n + '\\.' + p + '(\\d+)\\b', 'g'))].map((x) => +x[1]))
    const ps = used('p'), es = used('e')
    assert.deepEqual([...ps].sort((a, b) => a - b), (m.steps || []).map((_, i) => i + 1), n + ' 待机阶段')
    assert.deepEqual([...es].sort((a, b) => a - b), (m.enter || []).map((_, i) => i + 1), n + ' 进场阶段')
    if (m.steps) {
      assert.ok(m.T > m.steps[m.steps.length - 1], n + ' 周期长于最后一个阶段')
      for (let i = 1; i < m.steps.length; i++) assert.ok(m.steps[i] > m.steps[i - 1], n + ' 阶段递增')
    }
  }
})

test('只用过渡:不新增关键帧、不用 transition: all;只动 transform / opacity / clip-path', () => {
  assert.doesNotMatch(CSS, /@keyframes/)
  assert.doesNotMatch(CSS, /animation\s*:/)
  assert.doesNotMatch(CSS, /transition:\s*all/)
  const props = new Set()
  for (const m of CSS.matchAll(/transition:\s*([^;}]+)/g)) for (const part of m[1].split(/,(?![^(]*\))/)) { const p = part.trim().split(/\s+/)[0]; if (p !== 'none') props.add(p) }
  assert.deepEqual([...props].sort(), ['clip-path', 'opacity', 'transform'])
  for (const m of CSS.matchAll(/transition-property:\s*([^;}]+)/g)) assert.fail('不该单写 transition-property: ' + m[1])
})

test('减少动态效果:每一层直接终态(不透明、无变换、无裁切、无过渡),闪光 / 纸屑 / + 也静态显示', () => {
  assert.match(CSS, /\.ib\.is-reduced \.ib__l \{ transition: none; opacity: 1; transform: none; clip-path: none; \}/)
  const wxml = fs.readFileSync(path.join(DIR, 'index.wxml'), 'utf8')
  assert.match(wxml, /\{\{on && !reduced \? 'is-on' : ''\}\}/, '减少动态效果时不挂 is-on,阶段规则全部不命中')
  assert.match(wxml, /wx:if="\{\{!bare \|\| item\.k !== 'gd'\}\}"/, 'bare 时不画脚下软影')
})

// node 的假时钟一次 tick 不会触发 tick 途中新排的计时,这里按 10ms 小步走
function run(t, ms) { for (let i = 0; i < ms; i += 10) t.mock.timers.tick(Math.min(10, ms - i)) }
function loadComponent() {
  let def
  global.Component = (d) => { def = d }
  const p = require.resolve('../../pages/play/components/cy-illus/index.js')
  delete require.cache[p]
  require(p)
  delete global.Component
  return def
}
function make(def, props) {
  const vm = { properties: Object.assign({ name: 'key', n: null, delay: 0, loop: true, reducedMotion: false, bare: false, size: 128 }, props), data: JSON.parse(JSON.stringify(def.data)) }
  vm.setData = (o) => Object.assign(vm.data, o)
  Object.assign(vm, def.methods)
  return vm
}

test('组件:进场后按 enter 加 e1…,idleAt 后每圈逐个累加 p1…,周期到了撤掉重来;页面隐藏停计时', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const def = loadComponent()
  const vm = make(def, { name: 'badge' })
  def.lifetimes.attached.call(vm)
  assert.ok(vm.data.layers.length > 0)
  assert.equal(vm.data.on, false)
  run(t, 34)
  assert.equal(vm.data.on, true)
  run(t, 620)
  assert.equal(vm.data.ec, 'e1')
  run(t, 900 - 620)
  assert.equal(vm.data.idle, true)
  assert.equal(vm.data.pc, '')
  run(t, 40)
  assert.equal(vm.data.pc, 'p1')
  run(t, 520 - 40)
  assert.equal(vm.data.pc, 'p1 p2')
  assert.equal(vm.data.ec, 'e1 e2 e3', '爆星三段在 1390ms 前走完')
  run(t, B.MOTION.badge.T - 520)
  assert.equal(vm.data.pc, '', '一圈结束撤掉全部阶段')
  def.pageLifetimes.hide.call(vm)
  const pc = vm.data.pc
  run(t, 20000)
  assert.equal(vm.data.pc, pc, '隐藏后不再走')
  def.pageLifetimes.show.call(vm)
  run(t, 40)
  assert.equal(vm.data.pc, 'p1')
  def.lifetimes.detached.call(vm)
})

test('组件:loop=false 只进场不待机;成功没有待机;减少动态效果不起任何计时', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const def = loadComponent()
  const a = make(def, { name: 'key', loop: false })
  def.lifetimes.attached.call(a)
  run(t, 10000)
  assert.equal(a.data.on, true)
  assert.equal(a.data.idle, false)
  assert.equal(a.data.pc, '')
  const s = make(def, { name: 'success' })
  def.lifetimes.attached.call(s)
  run(t, 10000)
  assert.equal(s.data.ec, 'e1 e2')
  assert.equal(s.data.idle, false)
  const r = make(def, { name: 'wait', reducedMotion: true })
  def.lifetimes.attached.call(r)
  assert.equal(r.data.reduced, true)
  assert.deepEqual([r._enterT, (r._fx || []).length, (r._idle || []).length], [undefined, 0, 0])
  run(t, 10000)
  assert.equal(r.data.pc, '')
  for (const vm of [a, s, r]) def.lifetimes.detached.call(vm)
})

test('组件:宿主把名字从调查中换成成功时,按新图的时间表从进场重来', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const def = loadComponent()
  const vm = make(def, { name: 'wait' })
  def.lifetimes.attached.call(vm)
  run(t, 3000)
  assert.equal(vm.data.idle, true)
  vm.properties.name = 'success'
  vm._paint()
  assert.equal(vm.data.kind, 'success')
  assert.equal(vm.data.on, false)
  assert.equal(vm.data.pc, '')
  run(t, 34 + 850 + 10)
  assert.equal(vm.data.ec, 'e1')
  def.lifetimes.detached.call(vm)
})

const read = (p) => fs.readFileSync(path.join(XCX, p), 'utf8')

test('通行证页按 proto ps-pass:实色卡 + 标准列表行 + 底部固定药丸;黑底 token,不做玻璃、不再用暖橙渐变', () => {
  const wxml = read('subpackageMember/market/membership/index.wxml')
  const css = read('subpackageMember/market/membership/index.wxss')
  assert.match(wxml, /<view class="pp__card">[\s\S]*?<text class="pp__eb">城瘾通行证<\/text>/)
  assert.match(wxml, /<text class="pp__k" wx:if="\{\{view\.active && view\.untilText\}\}">有效期至 \{\{view\.untilText\}\}<\/text>/, '有效期只写服务端给的')
  assert.match(wxml, /<view class="pp__key"><cy-frames sheets="\{\{\[\{ src: '\/subpackageMember\/images\/frames\/glass-key\.webp', n: 24 \}\][^>]*w="\{\{184\}\}" h="\{\{184\}\}"/, 'qx-final2:按本 lane 跨 lane 建议,卡里的钥匙换成 l-icons 的 glass-key 序列帧,尺寸不变')
  assert.match(wxml, /<cy-illus name="seal" size="72" bare \/>[\s\S]*?可玩主题库[\s\S]*?wx:if="\{\{view\.count !== null\}\}">\{\{view\.count \|\| 0\}\} 个主题 · 持证 ¥0/)
  assert.doesNotMatch(wxml, /邀请队友|通行证勋章|已用 .* 天/, '服务端没给的事实(队友权益 / 勋章数 / 已用天数)不画')
  assert.match(wxml, /<view class="pp__foot" wx:if="\{\{state === 'ready' && view\.enabled\}\}">/)
  assert.match(wxml, /wx:if="\{\{view\.active\}\}" class="pp__pill" bindtap="goLibrary"[^>]*>去主题库<\/view>/)
  assert.match(wxml, /wx:else class="pp__pill[^"]*" bindtap="onBuy"/)
  const top = css.slice(0, css.indexOf('/* ps-success'))
  assert.match(top, /\.pp \{[^}]*background: var\(--cy-color-bg-page\); \}/)
  assert.match(top, /\.pp__card \{[^}]*background: var\(--cy-color-bg-surface\);[^}]*border: 1px solid var\(--cy-color-border-subtle\);/)
  assert.doesNotMatch(top.replace(/\/\*[\s\S]*?\*\//g, ''), /backdrop-filter|radial-gradient/)
})

test('商家看不到通行证:我的里没有入口;通行证页 / 主题库 / 通行证与礼品页按商家视角直接收住', () => {
  assert.match(read('subpackageMember/components/cy/profile/index.wxml'), /<view class="pc-team-entry" wx:if="\{\{!isMerchantView\}\}">\s*<cy-cell title="通行证与礼品"/)
  for (const p of ['subpackageMember/market/membership/index.js', 'subpackageMember/market/library/index.js', 'subpackageMember/market/passes/index.js']) {
    assert.match(read(p), /try \{ merchant = !!roleGuard\.isMerchantView\(\) \} catch \(e\) \{ merchant = false \}/, p)
  }
  assert.match(read('subpackageMember/market/membership/index.wxml'), /wx:elif="\{\{state === 'merchant'\}\}"[^>]*title="商家账号没有通行证"/)
  let def
  global.Page = (d) => { def = d }
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserRole: () => 'merchant', getUserType: () => '', sendRequest: () => assert.fail('商家不该请求通行证接口') })
  global.wx = { getStorageSync: () => '', getDeviceInfo: () => ({ platform: 'devtools' }) }
  const p = require.resolve('../../subpackageMember/market/membership/index.js')
  delete require.cache[p]
  try {
    require(p)
    const vm = { data: JSON.parse(JSON.stringify(def.data)) }
    vm.setData = (o) => Object.assign(vm.data, o)
    Object.assign(vm, def)
    def.onLoad.call(vm)
    assert.equal(vm.data.state, 'merchant')
  } finally { delete global.Page; delete global.getApp; delete global.wx }
})
