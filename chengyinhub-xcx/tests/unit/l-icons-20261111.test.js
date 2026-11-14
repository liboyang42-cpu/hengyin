// l-icons(2026-11-11 launch 波次,业主 2026-11-11 批准):C 波点只给章节号 + 章节印;A 3D 玻璃只给奖励时刻(通行证 / 勋章 / 本局完成),
// 以预渲染 WebP 序列帧进分包,由 cy-frames 播;通行证钥匙开场(proto ps-key)进 subpackagePass;玻璃只给星尘主题内卡片 / 弹窗,通行证页不做玻璃;
// 商家没有通行证。这里钉住数据合同、文件位置与体积,画面由 l-icons/shots 截图证明。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const LC = require('../../pages/play/utils/look-colors.js')

test('look-colors 是 look-tokens.wxss 四种外观 × 五种配色 --lk-a / --lk-ink / --lk-bg 的逐字镜像', () => {
  const css = read('pages/play/look-tokens.wxss')
  const re = /\.look--(\w+)\.look-palette--(\w+), \.look--\w+ \.atmosphere--\w+ \{([^}]*)\}/g
  let m, n = 0
  while ((m = re.exec(css))) {
    const v = {}
    m[3].replace(/--lk-(a|ink|bg):(#[0-9A-Fa-f]{6})/g, (x, k, c) => { v[k] = c })
    assert.deepEqual(LC.LOOK_COLORS[m[1].toUpperCase()][m[2].toUpperCase()], v, m[1] + '/' + m[2])
    n++
  }
  assert.equal(n, 20)
  assert.deepEqual(LC.colorsFor({ style: 'CYBER', palette: 'BLUE' }), { a: '#2DE2E6', ink: '#E2F1FF', bg: '#06101C' })
  assert.deepEqual(LC.colorsFor({ style: 'PAPER' }), LC.LOOK_COLORS.PAPER.DEFAULT, '没设配色按黑')
  assert.deepEqual(LC.colorsFor({ style: 'STARDUST', legacy: true }), LC.LEGACY, '老主题回落原型金')
  assert.deepEqual(LC.colorsFor(null), LC.LEGACY)
})

function loadFlip() {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  const file = path.join(ROOT, 'pages/play/components/chapter-flip/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  global.Component = prev
  return def
}
function flipInstance(def, props) {
  const timers = []
  const inst = {
    data: Object.assign({}, def.data, { mode: 'gate', faces: [], endInfo: null, info: { number: '01', title: '钟楼背后' }, reducedMotion: false, look: { style: 'SURVIVAL', palette: 'RED' } }, props),
    events: [], sets: [], assembled: [],
    setData(patch) { Object.assign(this.data, patch); this.sets.push(patch) },
    triggerEvent(name) { this.events.push(name) },
  }
  Object.keys(def.methods).forEach((k) => { inst[k] = def.methods[k].bind(inst) })
  inst._at = (fn) => { timers.push(fn) }
  inst._decode = () => {}
  inst._scatter = () => { inst.scattered = (inst.scattered || 0) + 1 }
  inst._assemble = (o) => { inst.assembled.push(o) }
  inst._ctx = {}; inst._S = 1; inst._W = 390; inst._H = 844; inst._cx = 195; inst._cy = 281; inst._timers = []; inst._tiles = []; inst._stars = [{ x: 1, y: 1 }]
  inst.flush = () => { while (timers.length) timers.shift()() }
  return inst
}

test('章节门:开场 / 完成屏的章节号都按圆点拼(dots),开场屏在文字块下面放章节印;颜色读外观主色;通关标志仍是方块', () => {
  const def = loadFlip()
  assert.ok(def.properties.look, '组件收 look')
  const c = flipInstance(def)
  c._gate()
  assert.deepEqual(c.assembled.map((o) => [o.text, !!o.dots]), [['01', true]], '开场章节号 = 圆点')
  c.flush()
  const seal = c._tiles.filter((t) => t.seal)
  assert.ok(seal.length >= 44 + 28, '章节印至少两圈点(外 44 + 内 28;「章」字点阵在没有离屏画布时可省)')
  assert.equal(seal.filter((t) => t.ring === 1).length, 44)
  assert.equal(seal.filter((t) => t.ring === 2).length, 28)
  assert.ok(seal.every((t) => t.c === '#7FE6C4' || t.c === '#F3E3E0'), '末世 · 红:外环主色 / 内环与字墨色')
  assert.ok(c._seal && c._seal.cy > 415 && c._seal.cy < 844 - 168, '章节印在文字块与「读下去」之间')
  const end = flipInstance(def, { endInfo: { number: '01', kicker: '第 1 章完成', title: '邮局旧址', rows: [] } })
  end._gate()
  assert.deepEqual(end.assembled.map((o) => [o.text, !!o.dots]), [['01', true]], '完成屏章节号也是圆点')
  const src = read('pages/play/components/chapter-flip/index.js')
  assert.match(src, /_assembleLogo\(instant\) \{[\s\S]*silver: true, cell: 5, tile: 4\.3/, '通关银色标志仍是方块,不走 dots')
  assert.match(read('pages/play/index.wxml'), /<cy-chapter-flip[^>]*look="\{\{chapterGate\.look\}\}"/)
  assert.match(read('pages/play/index.js'), /look: this\._look \? \{ style: this\._look\.style, palette: this\._look\.palette \|\| '', legacy: !!this\._look\.legacy \} : null/)
})

test('章节印小屏不挤:文字块到按钮不足 80px 就不放;减少动态效果没有亮波', () => {
  const def = loadFlip()
  const c = flipInstance(def)
  c._H = 600
  c._gate(); c.flush()
  assert.equal(c._tiles.filter((t) => t.seal).length, 0, '600 高的屏放不下章节印')
  const r = flipInstance(def, { reducedMotion: true })
  r._gate(); r.flush()
  assert.equal(r._dotShineT, undefined, '减少动态效果:_assemble 被桩掉时不该有亮波计时;真实现里 reducedMotion ⇒ _dotShineT = 0')
  assert.match(read('pages/play/components/chapter-flip/index.js'), /this\._dotShineT = dots && !this\.data\.reducedMotion \? now \+ 1800 : 0/)
})

// ---------- cy-frames ----------
function loadFrames() {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  const file = path.join(ROOT, 'pages/play/components/cy-frames/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  global.Component = prev
  return def
}
function framesInstance(def, props) {
  const vm = { properties: Object.assign({ sheets: [{ src: 'a.webp', n: 6 }, { src: 'b.webp', n: 6 }, { src: 'c.webp', n: 6 }, { src: 'd.webp', n: 6 }], cols: 3, fw: 100, fh: 100, w: 200, h: 200, fps: 10, mode: 'loop', loopFrom: 0, still: -1, delay: 0, enter: 'pop', reducedMotion: false }, props), data: Object.assign({}, def.data), events: [] }
  vm.setData = (o) => Object.assign(vm.data, o)
  vm.triggerEvent = (n) => vm.events.push(n)
  Object.assign(vm, def.methods)
  return vm
}

test('cy-frames:多张雪碧图按帧切(transform),同时只挂三张;once 播完抛 end;减少动态效果停在 still 帧且不起计时器', () => {
  const def = loadFrames()
  const timers = []
  const realST = global.setTimeout, realCT = global.clearTimeout
  global.setTimeout = (fn) => { timers.push(fn); return timers.length }
  global.clearTimeout = () => {}
  try {
    const v = framesInstance(def)
    def.lifetimes.attached.call(v)
    assert.equal(v.data.on, false)
    assert.equal(v.data.slots.length, 3, '当前 + 后两张')
    assert.equal(v.data.slots[0].src, 'a.webp'); assert.equal(v.data.slots[0].cur, true)
    assert.equal(v.data.pos, 'translate(0.0rpx,0.0rpx)')
    timers.shift()()                       // 进场
    assert.equal(v.data.on, true)
    for (let i = 0; i < 7; i++) timers.shift()()   // 推 7 帧 → 第 7 帧 = 第二张图第 1 格
    assert.equal(v._i, 7)
    assert.equal(v.data.slots[0].src, 'b.webp')
    assert.equal(v.data.pos, 'translate(-200.0rpx,0.0rpx)')
    assert.deepEqual(v.data.slots.map((s) => s.src), ['b.webp', 'c.webp', 'd.webp'])
    for (let i = 0; i < 17; i++) timers.shift()()  // 跑到头回第 0 帧(loop)
    assert.equal(v._i, 0)
    def.lifetimes.detached.call(v)

    timers.length = 0   // 上一个实例 detached 后排队里的尾巴不算
    const once = framesInstance(def, { mode: 'once', sheets: [{ src: 'a.webp', n: 3 }] })
    def.lifetimes.attached.call(once)
    timers.shift()()
    timers.shift()(); timers.shift()(); timers.shift()()
    assert.deepEqual(once.events, ['end'])
    assert.equal(once._i, 2, 'once 停在最后一帧')

    const r = framesInstance(def, { reducedMotion: true, mode: 'once', sheets: [{ src: 'a.webp', n: 6 }] })
    const before = timers.length
    def.lifetimes.attached.call(r)
    assert.equal(r.data.reduced, true); assert.equal(r.data.on, true)
    assert.equal(r._i, 5, 'once 的终态 = 最后一帧')
    assert.equal(timers.length, before, '不起计时器')
    const r2 = framesInstance(def, { reducedMotion: true, still: 2 })
    def.lifetimes.attached.call(r2)
    assert.equal(r2._i, 2)
  } finally { global.setTimeout = realST; global.clearTimeout = realCT }
  const css = read('pages/play/components/cy-frames/index.wxss')
  assert.doesNotMatch(css, /@keyframes|steps\(/)
  assert.match(css, /\.fr\.fr--reduced, \.fr\.fr--reduced\.fr--pop \{ transition: none; opacity: 1; transform: none; \}/)
})

test('cy-frames 只有一份规范实现(pages/play);subpackageMember / subpackagePass 的副本逐字相同', () => {
  const canon = path.join(ROOT, 'pages/play/components/cy-frames')
  for (const c of ['subpackageMember/components/cy-frames', 'subpackagePass/components/cy-frames']) {
    // 副本只允许改 motion-preference 的相对路径(分包副本比 pages/play 少一层)
    const norm = (t) => t.replace(/require\('[./]+utils\/motion-preference\.js'\)/, "require('utils/motion-preference.js')")
    for (const f of ['index.js', 'index.json', 'index.wxml', 'index.wxss']) assert.equal(norm(read(path.join(c, f))), norm(fs.readFileSync(path.join(canon, f), 'utf8')), c + '/' + f)
  }
})

// ---------- 资产与分包 ----------
test('玻璃序列帧在用它的分包里,每枚 24 帧 112px、6 列,单枚不超过 64 KB;主包没有新资产', () => {
  const list = [['pages/play/images/frames/glass-badge.webp', 'pages/play/index.wxml'], ['pages/play/images/frames/glass-success.webp', 'pages/play/index.wxml'], ['subpackageMember/images/frames/glass-key.webp', 'subpackageMember/market/membership/index.wxml']]
  for (const [f, user] of list) {
    const st = fs.statSync(path.join(ROOT, f))
    assert.ok(st.size > 10000 && st.size <= 65536, f + ' ' + st.size)
    assert.ok(read(user).includes('/' + f), user + ' 用 ' + f)
  }
  // qx-final2 合入 l-iconsB:通行证页英雄区改为 ps-pass 实色卡,glass-pass 在 ps-pass 里没有位置,资产随之删除;卡里的钥匙仍是 glass-key。
  assert.ok(!fs.existsSync(path.join(ROOT, 'subpackageMember/images/frames/glass-pass.webp')), 'glass-pass 已无调用者,不留孤儿资产')
  assert.ok(!fs.existsSync(path.join(ROOT, 'images/frames')), '主包里没有序列帧')
  const idx = read('pages/play/index.wxml')
  assert.match(idx, /<cy-frames sheets="\{\{\[\{ src: previewEnding\.reward \? '\/pages\/play\/images\/frames\/glass-badge\.webp' : '\/pages\/play\/images\/frames\/glass-success\.webp', n: 24 \}\]\}\}" cols="\{\{6\}\}" fw="\{\{112\}\}" fh="\{\{112\}\}" w="\{\{340\}\}" h="\{\{340\}\}"/)
  assert.equal(JSON.parse(read('pages/play/index.json')).usingComponents['cy-frames'], '/pages/play/components/cy-frames/index')
})

test('钥匙开场:subpackagePass 一页 + 序列帧清单里的每张图都在、总量 ≤ 2 MB;完整版只在首次 / 开通成功;商家只给一句原因;减少动态效果直接终态', () => {
  const app = JSON.parse(read('app.json'))
  const sp = (app.subPackages || app.subpackages).find((s) => s.root === 'subpackagePass')
  assert.deepEqual(sp.pages, ['reveal/index'])
  const FR = require('../../subpackagePass/reveal/frames.js')
  let total = 0
  for (const s of FR.intro.concat(FR.idle)) { assert.ok(s.src.startsWith('/subpackagePass/frames/')); total += fs.statSync(path.join(ROOT, s.src)).size }
  assert.ok(total <= 2 * 1024 * 1024, '序列帧合计 ' + total)
  assert.equal(FR.intro.reduce((a, s) => a + s.n, 0), 83, '0–4.6s @ 18fps')
  assert.equal(FR.idle.reduce((a, s) => a + s.n, 0), 72, '6s 待机 @ 12fps')
  assert.equal(FR.introFps, 18); assert.equal(FR.idleFps, 12); assert.equal(FR.fw, 585); assert.equal(FR.fh, 780)
  const json = JSON.parse(read('subpackagePass/reveal/index.json'))
  assert.equal(json.usingComponents['cy-frames'], '/subpackagePass/components/cy-frames/index')
  const css = read('subpackagePass/reveal/index.wxss')
  assert.doesNotMatch(css, /@keyframes|filter: blur|backdrop-filter/)
  assert.match(css, /\.ky--reduced \.ky__grad, \.ky--reduced \.ky__in[^{]*\{ transition: none; \}/)
  const wxml = read('subpackagePass/reveal/index.wxml')
  assert.match(wxml, /mode="once"[^>]*bind:end="onIntroEnd"/)
  assert.match(wxml, /mode="loop"[^>]*still="\{\{0\}\}"/)
  assert.match(wxml, /class="ky__skip[^"]*" wx:if="\{\{full && !idleOn\}\}"/, '「跳过」只在完整版里')
  assert.match(wxml, /商家账号没有通行证/)
  assert.doesNotMatch(wxml, /ky-dots|ky__dots/, '没有第二页就不画分页点')

  // 页面逻辑:首次 → 完整版;再次 → 待机;商家 → 不播;减少动态效果 → 终态
  let def = null, store = {}
  global.Page = (d) => { def = d }
  global.getApp = () => ({ globalData: { statusBarHeight: 47, navBarHeight: 44 }, getUserID: () => 'u1' })
  global.wx = { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v }, redirectTo: () => {}, navigateBack: () => {} }
  global.getCurrentPages = () => [1, 2]
  const file = path.join(ROOT, 'subpackagePass/reveal/index.js')
  const rg = path.join(ROOT, 'utils/roleGuard.js'), mp = path.join(ROOT, 'utils/motion-preference.js')
  require.cache[require.resolve(rg)] = { id: rg, filename: rg, loaded: true, exports: { isMerchantView: () => global.__merchant } }
  require.cache[require.resolve(mp)] = { id: mp, filename: mp, loaded: true, exports: { readReducedMotion: () => global.__reduced } }
  delete require.cache[require.resolve(file)]
  const realST = global.setTimeout; const timers = []; global.setTimeout = (fn) => { timers.push(fn); return timers.length }
  try {
    require(file)
    const make = () => { const p = { data: Object.assign({}, def.data), setData(o) { Object.keys(o).forEach((k) => { if (k.startsWith('step.')) this.data.step[k.slice(5)] = o[k]; else this.data[k] = o[k] }) } }; Object.keys(def).forEach((k) => { if (typeof def[k] === 'function') p[k] = def[k].bind(p) }); return p }
    global.__merchant = false; global.__reduced = false
    const first = make(); first.onLoad({})
    assert.equal(first.data.full, true); assert.equal(first.data.introOn, true); assert.equal(first.data.idleOn, false)
    assert.equal(store.pass_reveal_seen_v1.uid, 'u1')
    const again = make(); again.onLoad({})
    assert.equal(again.data.full, false); assert.equal(again.data.idleOn, true); assert.equal(again.data.step.btn, true, '再次进入直接待机 + 按钮')
    const paid = make(); paid.onLoad({ mode: 'paid', count: '12', until: encodeURIComponent('2027-11-10') })
    assert.equal(paid.data.full, true)
    assert.deepEqual(paid.data.copy, { eb: '通行证 · 已开通', h: '12 个主题，全部可玩', p: '到 2027-11-10 · 价格都是 ¥0', btn: '进入可玩主题库' })
    const noFacts = make(); noFacts.onLoad({ mode: 'paid' })
    assert.equal(noFacts.data.copy.p, '', '没有服务端日期就不写那一行')
    global.__reduced = true
    const r = make(); r.onLoad({})
    assert.equal(r.data.full, false); assert.equal(r.data.idleOn, true); assert.equal(r.data.reduced, true)
    global.__reduced = false; global.__merchant = true
    const m = make(); m.onLoad({})
    assert.equal(m.data.merchant, true); assert.equal(m.data.introOn, false); assert.equal(m.data.idleOn, false)
  } finally {
    global.setTimeout = realST
    delete global.Page; delete global.getApp; delete global.wx; delete global.getCurrentPages; delete global.__merchant; delete global.__reduced
    delete require.cache[require.resolve(rg)]; delete require.cache[require.resolve(mp)]; delete require.cache[require.resolve(file)]
  }
})

test('通行证页 / 主题库 / 通行证列表:不做玻璃、通行证入口保留商家限制、公开主题库不依赖通行证', () => {
  for (const f of ['subpackageMember/market/membership/index.wxss', 'subpackageMember/market/passes/index.wxss', 'subpackageMember/market/library/index.wxss', 'subpackagePass/reveal/index.wxss']) {
    assert.doesNotMatch(read(f), /backdrop-filter/, f + ' 不做玻璃(业主「里面不需要是玻璃的 就是正常的样式」)')
  }
  assert.match(read('subpackageMember/market/membership/index.wxml'), /<view class="pp__key"><cy-frames sheets="\{\{\[\{ src: '\/subpackageMember\/images\/frames\/glass-key\.webp', n: 24 \}\]\}\}"/, 'qx-final2:ps-pass 卡里的钥匙用 l-icons 的玻璃序列帧')
  assert.doesNotMatch(read('subpackageMember/market/membership/index.wxml'), /class="ok /, '旧的 ps-success 层已退场')
  assert.match(read('subpackageMember/market/passes/index.js'), /wx\.navigateTo\(\{ url: '\/subpackagePass\/reveal\/index'/)
  for (const f of ['subpackageMember/market/membership/index.js', 'subpackageMember/market/passes/index.js']) {
    assert.match(read(f), /roleGuard\.isMerchantView\(\)/, f)
  }
  assert.match(read('subpackageMember/market/membership/index.wxml'), /state === 'merchant'[^>]*商家账号没有通行证/)
  const library = read('subpackageMember/market/library/index.js')
  assert.match(library, /url: '\/api\/public-catalog\/list', method: 'POST'/)
  assert.doesNotMatch(library, /roleGuard\.isMerchantView\(\)|state: '(?:merchant|off)'/)
  assert.doesNotMatch(read('subpackageMember/market/library/index.wxml'), /商家账号没有通行证|通行证暂未开放/)
  // 钥匙开场页的商家空态:父级是 flex 列,cy-fill-state 才能占满剩余高度(否则插图被导航压住)
  assert.match(read('subpackagePass/reveal/index.wxml'), /class="theme-dark ky \{\{merchant \? 'ky--plain' : ''\}\}/)
  assert.match(read('subpackagePass/reveal/index.wxss'), /\.ky--plain \{ display: flex; flex-direction: column;/)
  // 已有的两个入口仍按商家视角隐藏
  assert.match(read('subpackageMember/components/cy/profile/index.wxml'), /wx:if="\{\{!isMerchantView\}\}">\s*<cy-cell title="通行证与礼品"/)
  assert.match(read('pages/talent/list/index.js'), /if \(this\.data\.isMerchantViewer\)/)
})

test('玻璃配方只给星尘:模糊 22px(44rpx)· 饱和 1.7 · 高光边 · 斜向反光 · 噪点;纸本 / 末世 / 夜城没有 backdrop-filter', () => {
  const css = read('pages/play/look-tokens.wxss')
  assert.match(css, /\.look--stardust \.lk-pop, \.look--stardust \.lk-card, \.look--stardust \.lk-btn--ghost, \.look--stardust \.lk-chip \{[^}]*backdrop-filter: blur\(44rpx\) saturate\(1\.7\)/)
  assert.match(css, /\.look--stardust \.lk-pop::before, \.look--stardust \.lk-card::before \{[^}]*feTurbulence[^}]*opacity='\.03'/)
  assert.match(css, /\.look--stardust \.lk-pop::before[^{]*\{[^}]*linear-gradient\(115deg/)
  for (const st of ['paper', 'survival', 'cyber']) {
    const rules = css.split('\n').filter((l) => l.includes('.look--' + st + ' ') && !l.includes('.look--stardust'))
    assert.ok(rules.length && rules.every((l) => !/backdrop-filter/.test(l)), st + ' 不做玻璃')
  }
  assert.doesNotMatch(css, /@keyframes/)
})
