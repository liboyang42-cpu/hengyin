'use strict'

/* launch 底座(2026-11-10 qx-base):主题内外观变量 --lk-* 与 cy-illus 插图组件。
 * 规格来源:docs/delivery/questo-ui-20261109/proto/game/look.js(PAL / STYLES)与 icons.js,业主已批准。
 * 这里把原型的色板逐字抄成夹具,防止后续有人改了 wxss 却没对原型。 */

const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const XCX = path.resolve(__dirname, '../..')
const TOKENS = fs.readFileSync(path.join(XCX, 'pages/play/look-tokens.wxss'), 'utf8')
const art = require('../../pages/play/components/cy-illus/art.js')

const PAL = {
  STARDUST: { DEFAULT: ['#0A0A0A', '#F2F2F0', '#9AA0AA', '#E9E2D0', '#9AA0AA'], BLUE: ['#0E1A33', '#F5F8FF', '#A9BBE0', '#CFE0FF', '#A9BBE0'], RED: ['#2A0E12', '#FFF4F5', '#E0AAB1', '#FFB8AC', '#E0AAB1'], YELLOW: ['#2A2208', '#FFFBEF', '#DFCD9A', '#F2D58A', '#DFCD9A'], WHITE: ['#F5F6F8', '#111318', '#5B6270', '#111318', '#5B6270'] },
  PAPER: { DEFAULT: ['#1D1B18', '#ECE6D8', '#9A927F', '#D0503C', '#9A927F'], BLUE: ['#1C2A44', '#EEF0EA', '#A2ADBF', '#E8C66A', '#A2ADBF'], RED: ['#3A1A17', '#F4E6DC', '#C9A596', '#E8C66A', '#C9A596'], YELLOW: ['#E6DCC4', '#2B241A', '#7D715C', '#A3281D', '#7D715C'], WHITE: ['#F4F1EA', '#1E1C19', '#7A756C', '#B0302A', '#7A756C'] },
  SURVIVAL: { DEFAULT: ['#0E1012', '#E4E7E9', '#8A9196', '#FF8A3D', '#8A9196'], BLUE: ['#0B1626', '#EAF3FB', '#8FA6BD', '#FFB35C', '#8FA6BD'], RED: ['#1C0A0A', '#F3E3E0', '#B08A86', '#7FE6C4', '#B08A86'], YELLOW: ['#2A1D0C', '#FFF2DC', '#CDB38A', '#8FD3FF', '#CDB38A'], WHITE: ['#E3E9EE', '#14202B', '#5D6D7B', '#D4561E', '#5D6D7B'] },
  CYBER: { DEFAULT: ['#0B0B0F', '#ECEBE4', '#8A8C96', '#F3E600', '#24E0E8'], BLUE: ['#06101C', '#E2F1FF', '#7E93AC', '#2DE2E6', '#FF3B6B'], RED: ['#12060A', '#FFE8EA', '#A88088', '#FF2E4C', '#2DE2E6'], YELLOW: ['#100E04', '#FFF6CC', '#A99E6E', '#FCEE0A', '#FF2E4C'], WHITE: ['#E6E7EA', '#101216', '#5E636E', '#E0002B', '#008C99'] },
}
const VARS = ['--lk-bg', '--lk-ink', '--lk-mute', '--lk-a', '--lk-b']

function ruleFor(selectorStart) {
  const line = TOKENS.split('\n').find((l) => l.startsWith(selectorStart))
  assert.ok(line, 'missing rule ' + selectorStart)
  return line
}

test('四种外观 × 五种配色都按原型 PAL 落成 --lk-*,章节配色同值覆盖', () => {
  for (const [style, pals] of Object.entries(PAL)) {
    const st = style.toLowerCase()
    for (const [pal, vals] of Object.entries(pals)) {
      const line = ruleFor(`.look--${st}.look-palette--${pal.toLowerCase()}, .look--${st} .atmosphere--${pal.toLowerCase()} {`)
      VARS.forEach((v, i) => assert.ok(line.includes(`${v}:${vals[i]};`), `${style}/${pal} ${v}`))
    }
    const base = ruleFor(`.look--${st} {`)
    VARS.forEach((v, i) => assert.ok(base.includes(`${v}:${pals.DEFAULT[i]};`), `${style} 默认 ${v}`))
    for (const k of ['--lk-fd', '--lk-fw', '--lk-r', '--lk-rb', '--lk-cut', '--lk-card', '--lk-pop', '--lk-ctl', '--lk-dim', '--lk-ink-rgb']) assert.ok(base.includes(k + ':'), `${style} ${k}`)
  }
})

test('星尘是果冻玻璃、末世是锈蚀、结算/平台 .lk-plain 回到玩家黑', () => {
  assert.match(TOKENS, /\.look--stardust \.lk-pop[^{]*\{[^}]*backdrop-filter: blur/)
  assert.match(TOKENS, /\.look--survival \{ --lk-rust-rgb:/)
  assert.match(TOKENS, /\.look--survival \.lk-pop, \.look--survival \.lk-card \{[^}]*rgba\(var\(--lk-rust-rgb\)/)
  assert.match(TOKENS, /\.lk-plain \{ --lk-bg: var\(--cy-color-bg-page\);/)
  assert.match(TOKENS, /\.lk-plain \.lk-pop[^{]*\{[^}]*backdrop-filter: none/)
})

test('玩家页引入外观变量;底座不新增 keyframes、不用 color-mix', () => {
  const idx = fs.readFileSync(path.join(XCX, 'pages/play/index.wxss'), 'utf8')
  assert.ok(idx.startsWith('@import "./look-tokens.wxss";'))
  const illusCss = fs.readFileSync(path.join(XCX, 'pages/play/components/cy-illus/index.wxss'), 'utf8')
  for (const css of [TOKENS, illusCss]) {
    assert.doesNotMatch(css, /@keyframes/)
    assert.doesNotMatch(css.replace(/\/\*[\s\S]*?\*\//g, ""), /color-mix\(/)
    assert.doesNotMatch(css, /transition:\s*all/)
  }
  // D-118:旧 .il 画法删除,减少动态效果规则只剩风格 B 的 .ib
  assert.match(illusCss, /\.ib\.is-reduced \.ib__l \{ transition: none; opacity: 1; transform: none; clip-path: none; \}/)
})

test('cy-illus 插图集与原型同为 25 张,未知名回落线索,章节印数字转义', () => {
  assert.equal(art.LIST.length, 25)
  for (const n of ['clue', 'success', 'fail', 'wait', 'pass', 'seal', 'trophy', 'locked', 'unlocked', 'envelope']) assert.ok(art.has(n), n)
  const x = art.layers('nope')
  assert.equal(x.name, 'clue')
  assert.ok(x.blob.startsWith('data:image/svg+xml;charset=utf8,'))
  assert.ok(x.fg.startsWith('data:image/svg+xml;charset=utf8,'))
  // l-iconsB:章节印换成风格 B v2(印记是白星,不画数字);n 不再进图,也就不会把宿主给的字符串拼进 SVG
  const seal = decodeURIComponent(art.layers('seal', '<3>').fg)
  assert.ok(!seal.includes('<3>') && !seal.includes('</text>'))
})

test('cy-illus 只有一份规范实现;其他分包的副本必须逐字相同', () => {
  const canon = path.join(XCX, 'pages/play/components/cy-illus')
  const files = ['art.js', 'art-b.js', 'index.js', 'index.json', 'index.wxml', 'index.wxss']
  const copies = []
  ;(function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (['node_modules', 'miniprogram_npm', 'tests', 'scripts', 'docs'].includes(e.name)) continue
      const p = path.join(dir, e.name)
      if (e.isDirectory()) { if (e.name === 'cy-illus' && p !== canon) copies.push(p); else walk(p) }
    }
  })(XCX)
  for (const c of copies) {
    for (const f of files) {
      if (f === 'index.js') {
        // 副本只允许改 motion-preference 的相对路径
        const norm = (s) => s.replace(/require\('[./]+utils\/motion-preference\.js'\)/, "require('utils/motion-preference.js')")
        assert.equal(norm(fs.readFileSync(path.join(c, f), 'utf8')), norm(fs.readFileSync(path.join(canon, f), 'utf8')), c + '/' + f)
      } else {
        assert.equal(fs.readFileSync(path.join(c, f), 'utf8'), fs.readFileSync(path.join(canon, f), 'utf8'), c + '/' + f)
      }
    }
  }
})

test('cy-illus 组件:进场后加 is-on,减少动态效果直接终态且不起计时器', () => {
  let def
  global.Component = (d) => { def = d }
  const modPath = require.resolve('../../pages/play/components/cy-illus/index.js')
  delete require.cache[modPath]
  require(modPath)
  delete global.Component
  function make(props) {
    const vm = { properties: Object.assign({ name: 'success', n: null, delay: 0, loop: true, reducedMotion: null }, props), data: Object.assign({}, def.data) }
    vm.setData = (o) => Object.assign(vm.data, o)
    Object.assign(vm, def.methods)
    return vm
  }
  const r = make({ reducedMotion: true })
  def.lifetimes.attached.call(r)
  assert.equal(r.data.reduced, true)
  assert.equal(r.data.on, true)
  assert.equal(r._idleT, undefined)
  def.lifetimes.detached.call(r)

  const m = make({ reducedMotion: false, name: 'key' })
  def.lifetimes.attached.call(m)
  assert.equal(m.data.on, false)
  assert.ok(m.data.layers.length > 0)
  def.lifetimes.detached.call(m)

  // D-118:15 枚补画的图没有 MOTION,只做通用弹出,不起待机计时
  const o = make({ reducedMotion: false, name: 'sparkles' })
  def.lifetimes.attached.call(o)
  assert.ok(o.data.layers.length > 0)
  def.lifetimes.detached.call(o)
})
