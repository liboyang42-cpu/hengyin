// 2026-10-23 业主确认的弹层动效规范(原型 https://claude.ai/artifact/2yxBJV1mrTE7wjBuw6yRTg 「动效 · 现在 vs 规范」):
//   ① 按档位定速:peek 260/200 · half 300/250 · full 340/280,曲线慢快慢;确认弹窗 220/140;轻提示 220/150。
//   ② 遮罩与面板同档同曲线。
//   ③ 同一时刻只有一个弹层在动:关 A 开 B 时 B 等 A 退完,遮罩交接不闪。
//   ④ 每个弹层都有退场;弹层不回弹(回弹只留给庆祝元素)。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '')
const tokens = read('style/tokens.wxss')
const ms = (name) => {
  const m = new RegExp(`--cy-motion-${name}:\\s*(\\d+)ms;`).exec(tokens)
  assert.ok(m, `tokens.wxss 缺 --cy-motion-${name}`)
  return Number(m[1])
}

test('① 档位时长:peek 260/200 · half 300/250 · full 340/280,每档退场都比进场快', () => {
  const lv = {
    peek: [ms('sheet-peek-in'), ms('sheet-peek-out')],
    half: [ms('sheet-in'), ms('sheet-out')],
    full: [ms('sheet-full-in'), ms('sheet-full-out')],
  }
  assert.deepEqual(lv, { peek: [260, 200], half: [300, 250], full: [340, 280] })
  for (const [k, [i, o]] of Object.entries(lv)) assert.ok(o < i, `${k} 退场必须短于进场`)
  assert.ok(lv.peek[0] < lv.half[0] && lv.half[0] < lv.full[0], '越高的面板走得越远,时长递增')
  assert.equal(ms('toast-out'), 150)
  assert.ok(ms('toast-out') < ms('standard'), '轻提示收起比出现快')
})

test('① cy-sheet:档位类重定义两条时长,JS 退场计时器与 tokens 一致', () => {
  const wxss = strip(read('components/cy/sheet/index.wxss'))
  assert.match(wxss, /\.sh--lv-peek \{ --cy-motion-sheet-in: var\(--cy-motion-sheet-peek-in\); --cy-motion-sheet-out: var\(--cy-motion-sheet-peek-out\); \}/)
  assert.match(wxss, /\.sh--lv-full \{ --cy-motion-sheet-in: var\(--cy-motion-sheet-full-in\); --cy-motion-sheet-out: var\(--cy-motion-sheet-full-out\); \}/)
  assert.match(wxss, /\.sh\.sh--measure \.sh__panel,\s*\.sh\.sh--measure \.sh__mask \{ animation: none; visibility: hidden; \}/,
    '量高那一帧面板与遮罩都不能起跑')
  const js = read('components/cy/sheet/index.js')
  const m = /LEVEL_EXIT_MS = \{ peek: (\d+), half: (\d+), full: (\d+) \}/.exec(js)
  assert.ok(m)
  assert.deepEqual(m.slice(1).map(Number), [ms('sheet-peek-out'), ms('sheet-out'), ms('sheet-full-out')])
  assert.match(read('components/cy/sheet/index.wxml'), /\{\{_lv \? 'sh--lv-' \+ _lv : ''\}\}/)
})

test('① cy-sheet 档位判定:fixed / full 直接定档,贴内容的按面板占屏比量', () => {
  let def = null
  const prev = { Component: global.Component, Behavior: global.Behavior, getApp: global.getApp, wx: global.wx }
  global.Component = (o) => { def = o }
  global.Behavior = (o) => o
  global.getApp = () => ({ globalData: {} })
  let rectH = 0
  global.wx = { getWindowInfo: () => ({ windowHeight: 800, windowWidth: 375 }) }
  try {
    delete require.cache[path.join(ROOT, 'components/cy/sheet/index.js')]
    require(path.join(ROOT, 'components/cy/sheet/index.js'))
    const vm = Object.assign({}, def.methods, {
      data: { variant: 'bottom', fixed: '', anchorSelector: '', reducedMotion: false, _measuring: false },
      setData(p) { Object.assign(this.data, p) },
      createSelectorQuery() { return { select() { return { boundingClientRect(cb) { return { exec() { cb({ height: rectH }) } } } } } } },
    })
    assert.deepEqual(vm._enterData(), { _lv: '', _measuring: true })
    for (const [h, lv, out] of [[200, 'peek', 200], [470, 'half', 250], [600, 'full', 280]]) {
      rectH = h; vm.data._measuring = true; vm._afterEnter()
      assert.equal(vm.data._lv, lv, `${h}px / 800px 应落 ${lv}`)
      assert.equal(vm._exitMs, out)
      assert.equal(vm.data._measuring, false)
    }
    vm.data.fixed = 'peek'
    assert.deepEqual(vm._enterData(), { _lv: 'peek', _measuring: false })
    vm.data.fixed = ''; vm.data.variant = 'full'
    assert.deepEqual(vm._enterData(), { _lv: 'full', _measuring: false })
  } finally {
    Object.assign(global, prev)
  }
})

test('① cy-scene-sheet:有退场、按档位定速、不再占 keyframes', () => {
  const wxml = read('components/cy/scene-sheet/index.wxml')
  assert.match(wxml, /wx:if="\{\{_render\}\}"/, '不能再直连 show —— 那样关闭瞬间消失')
  assert.match(wxml, /\{\{_in && !_closing \? 'ss--in' : ''\}\}/)
  const js = read('components/cy/scene-sheet/index.js')
  assert.match(js, /behaviors: \[exitMotion\(250\)\]/)
  const m = /LEVEL_EXIT_MS = \{ half: (\d+), full: (\d+) \}/.exec(js)
  assert.deepEqual(m.slice(1).map(Number), [ms('sheet-out'), ms('sheet-full-out')])
  const wxss = strip(read('components/cy/scene-sheet/index.wxss'))
  assert.doesNotMatch(wxss, /@keyframes/)
  assert.match(wxss, /\.ss--full \{ --cy-motion-sheet-in: var\(--cy-motion-sheet-full-in\); --cy-motion-sheet-out: var\(--cy-motion-sheet-full-out\); \}/)
  assert.match(wxss, /\.ss__mask \{[^}]*transition: opacity var\(--cy-motion-sheet-out\) var\(--cy-ease-standard\);/, '遮罩与面板同档同曲线')
})

test('② 时间选择面板 / 轻提示:同档同曲线,计时器镜像', () => {
  const ds = strip(read('components/cy/date-sheet/index.wxss'))
  assert.match(ds, /ds-up var\(--cy-motion-sheet-in\) var\(--cy-ease-standard\)/)
  assert.match(ds, /ds-fade-in var\(--cy-motion-sheet-in\) var\(--cy-ease-standard\)/)
  assert.match(read('components/cy/date-sheet/index.js'), /exitMotion\(250\)/)
  const ct = strip(read('components/cy/toast/index.wxss'))
  assert.match(ct, /\.ct \{[^}]*transition: opacity var\(--cy-motion-toast-out\)/)
  assert.match(ct, /\.ct\.ct--in \{[^}]*transition: opacity var\(--cy-motion-standard\)/)
  assert.match(read('components/cy/toast/index.js'), /\}, 150\);/)
})

test('④ 弹层不回弹:自玩通行证 / 勋章墙详情 / 漫游入口半屏走慢快慢', () => {
  const spring = /cubic-bezier\([^)]*1\.[0-9]+[^)]*\)|--cy-ease-spring/
  const rule = (file, sel) => {
    const code = strip(read(file))
    const m = code.match(new RegExp(sel.replace(/[.]/g, '\\.') + '\\s*\\{[^}]*\\}', 'g'))
    assert.ok(m && m.length, `${file} 找不到 ${sel}`)
    return m.join('\n')
  }
  for (const [f, sel] of [
    ['pages/topic/index/index.wxss', '.bsheet'], ['pages/topic/index/index.wxss', '.bsheet.is-in'],
    ['subpackageP3/pages/badge-wall/index/index.wxss', '.bw-sheet'],
    ['pages/roam/index.wxss', '.entry-sheet'],
  ]) {
    const r = rule(f, sel)
    assert.doesNotMatch(r, spring, `${f} ${sel} 不许回弹`)
  }
  assert.match(read('pages/topic/index/index.js'), /this\._later\(250, \(\) => \{ if \(!this\.data\.buyIn\)/, '卸载计时器 = sheet-out')
})

test('④ 玩法页手动关闭的面板先播退场再卸载', () => {
  const js = read('pages/play/index.js')
  assert.match(js, /closeSheet\(\) \{[\s\S]{0,400}this\._outThen\('sheet'\);/)
  assert.match(js, /closeJournal\(\) \{ this\._outThen\('journal'\); \}/)
  assert.match(js, /_outThen\(which\) \{[\s\S]*?\}, 280\);/, '280 = --cy-motion-sheet-full-out')
  assert.match(js, /'sheet\.show': true, sheetOut: false/, '重新打开要把退场标记清掉,否则会被上一次的计时器关掉')
  const wxss = strip(read('pages/play/index.wxss'))
  assert.match(wxss, /\.sheet\.is-out\{ animation:none; transform:translateY\(105%\); transition:transform var\(--cy-motion-sheet-full-out\)/)
  assert.match(wxss, /\.mask\.is-out\{ animation:none; opacity:0; transition:opacity var\(--cy-motion-sheet-full-out\)/)
})

/* ③ 交接:用假定时器把 behaviors/exit-motion.js 跑一遍 */
function loadExitMotion(t) {
  const file = path.join(ROOT, 'behaviors/exit-motion.js')
  const prev = global.Behavior
  global.Behavior = (o) => o          // 工厂调用时才用到 Behavior,整个用例期间都要在
  t.after(() => { if (prev === undefined) delete global.Behavior; else global.Behavior = prev })
  delete require.cache[file]
  return require(file)
}
function instance(def, extra) {
  const vm = Object.assign({}, def.methods, {
    data: Object.assign({}, def.data, { reducedMotion: false, anchorSelector: '' }, extra),
    setData(p, cb) { Object.assign(this.data, p); if (cb) cb() },
  })
  vm.show = (v) => def.observers.show.call(vm, v)
  vm.detach = () => def.lifetimes.detached.call(vm)
  return vm
}

test('③ 关 A 开 B:B 等 A 退完才挂载,A 的遮罩保持全暗,B 的遮罩不淡入', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const factory = loadExitMotion(t)
  factory._resetHandoff()
  const A = instance(factory(250)), B = instance(factory(140))
  A.show(true); t.mock.timers.tick(0)
  assert.equal(A.data._render, true, '没有别的弹层在动:下一拍直接进场')
  // 同一次 setData 里一关一开(顺序反过来也一样,判断推到下一拍)
  B.show(true); A.show(false)
  t.mock.timers.tick(0)
  assert.equal(A.data._closing, true)
  assert.equal(B.data._render, false, 'A 还在退场,B 不许同时进场')
  assert.equal(A.data._keepMask, true, 'A 的遮罩交给 B,不淡出')
  t.mock.timers.tick(250)
  assert.equal(A.data._render, false)
  assert.equal(B.data._render, true, 'A 退完 B 立刻进')
  assert.equal(B.data._handoffIn, true, 'B 的遮罩直接是暗的')
  t.mock.timers.tick(400)
  assert.equal(B.data._handoffIn, false, '进场播完摘掉,下次自己退场时遮罩照常淡出')
  B.detach(); A.detach()
})

test('③ 叠在仍开着的弹层上不排队;排队中被关掉就什么都不渲染;退场太久不干等', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const factory = loadExitMotion(t)
  factory._resetHandoff()
  const sheet = instance(factory(250)), confirm = instance(factory(140))
  sheet.show(true); t.mock.timers.tick(0)
  confirm.show(true); t.mock.timers.tick(0)
  assert.equal(confirm.data._render, true, '脏数据确认盖在编辑弹窗上:照常进场')
  assert.equal(sheet.data._keepMask, false)

  const a = instance(factory(250)), b = instance(factory(250))
  a.show(true); t.mock.timers.tick(0)
  a.show(false); b.show(true); t.mock.timers.tick(0)
  b.show(false)                        // 排队中就被关掉
  t.mock.timers.tick(250)
  assert.equal(b.data._render, false, '出队,不渲染')

  const slow = instance(factory(250)), next = instance(factory(250))
  slow._exitMs = 2000                  // 假设某层退场异常地长
  slow.show(true); t.mock.timers.tick(0)
  slow.show(false); next.show(true); t.mock.timers.tick(0)
  t.mock.timers.tick(320)
  assert.equal(next.data._render, true, '最多等 320ms,宁可叠也不让用户干等')
  for (const vm of [sheet, confirm, a, b, slow, next]) vm.detach()
})

test('③ 减动效:不排队、不等,直接进出', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const factory = loadExitMotion(t)
  factory._resetHandoff()
  const A = instance(factory(250), { reducedMotion: true }), B = instance(factory(250), { reducedMotion: true })
  A.show(true); t.mock.timers.tick(0)
  A.show(false); assert.equal(A.data._render, false, '减动效立即卸载')
  B.show(true); t.mock.timers.tick(0)
  assert.equal(B.data._render, true)
  A.detach(); B.detach()
})

test('③ 参与交接的弹层都接了遮罩交接类', () => {
  for (const [wxml, wxss, p] of [
    ['components/cy/sheet/index.wxml', 'components/cy/sheet/index.wxss', 'sh'],
    ['components/cy/modal/index.wxml', 'components/cy/modal/index.wxss', 'mo'],
    ['components/cy/date-sheet/index.wxml', 'components/cy/date-sheet/index.wxss', 'ds'],
    ['components/cy/scene-sheet/index.wxml', 'components/cy/scene-sheet/index.wxss', 'ss'],
    ['components/cy/publish-sheet/index.wxml', 'components/cy/publish-sheet/index.wxss', 'ps'],
  ]) {
    const w = read(wxml), s = strip(read(wxss))
    assert.ok(w.includes(`{{_keepMask ? '${p}--keep-mask' : ''}}`), `${wxml}: 缺 keep-mask`)
    assert.ok(w.includes(`{{_handoffIn ? '${p}--handoff-in' : ''}}`), `${wxml}: 缺 handoff-in`)
    assert.match(s, new RegExp(`\\.${p}--keep-mask \\.${p}__mask \\{ opacity: 1 !important; \\}`), `${wxss}: 被接替的遮罩要保持全暗`)
  }
})

test('④ 收尾:漫游主题半屏 / 附近的人 / 模板编辑器 / 游玩工具菜单都有进出场', () => {
  const roamJs = read('pages/roam/index.js'), roamWxml = read('pages/roam/index.wxml')
  assert.match(roamJs, /closeTopicView\(\) \{ this\._topicLeave\(\); \}/)
  assert.match(roamJs, /_topicLeave\(\) \{[\s\S]*?\}, 250\);/, '250 = --cy-motion-sheet-out')
  assert.match(roamWxml, /id="roam-topic-sheet" class="psheet psheet--half \{\{topicIn \? 'is-in' : ''\}\}/, '不能再是静态 is-in')
  assert.match(roamWxml, /class="tstrip \{\{topicIn \? 'is-in' : ''\}\}" style="\{\{tstripStyle\}\}"/, '照片条按半屏真实高度贴上沿')

  const nearby = read('subpackageRoam/nearby/index.js')
  assert.match(nearby, /closeSheet\(\) \{[\s\S]*?this\.setData\(\{ sheetIn: false \}\);[\s\S]*?\}, 250\);/)
  assert.match(nearby, /if \(this\._sheetOutTimer\) \{ this\._pendingSheet = name; return; \}/, '上一层还在落下就等它落完')

  const temp = read('pages/publish/temp/index.wxml')
  const masks = temp.match(/class="cg-mask[^"]*"[^>]*bindtap="cgDismiss"/g) || []
  // 2026-10-28 L4:多一张「添加加值 / 添加代价」半屏(checkSheet),同样走 cgDismiss
  assert.equal(masks.length, 11, '11 个页面自绘半屏的遮罩都走 cgDismiss')
  assert.doesNotMatch(temp, /class="cg-mask[^"]*"[^>]*bindtap="close/, '遮罩不再直接一帧关闭')
  const tw = strip(read('pages/publish/temp/index.wxss'))
  assert.match(tw, /\.cg-sheet \{[^}]*animation: cy-rise var\(--cy-motion-sheet-in\) var\(--cy-ease-standard\);/)
  assert.match(tw, /\.cg-sheet\.is-out \{ animation: none; transform: translateY\(100%\); transition: transform var\(--cy-motion-sheet-out\)/)
  assert.match(read('pages/publish/temp/r5-editor.js'), /cgDismiss\(e\) \{[\s\S]*?\}, 250\);/)

  const play = read('pages/play/index.js')
  assert.match(play, /closeSessionTools\(\) \{ this\._toolsClose\(\); \}/)
  assert.match(play, /_toolsClose\(\) \{[\s\S]*?\}, 140\);/, '140 = --cy-motion-fast')
  assert.match(strip(read('pages/play/index.wxss')), /\.tools--corner\.is-out\{ animation:none; opacity:0; transition:opacity var\(--cy-motion-fast\)/)
})

test('④ 撤销条:页面被盖住 / 切后台时未决的那一笔立即提交', () => {
  let def = null
  const prev = { Component: global.Component, Behavior: global.Behavior, wx: global.wx, getApp: global.getApp }
  global.Component = (o) => { def = o }; global.Behavior = (o) => o; global.wx = {}; global.getApp = () => ({ globalData: {} })
  try {
    delete require.cache[path.join(ROOT, 'components/cy/toast/index.js')]
    require(path.join(ROOT, 'components/cy/toast/index.js'))
  } finally { Object.assign(global, prev) }
  const vm = Object.assign({}, def.methods, { data: Object.assign({ reducedMotion: true }, def.data), setData(p) { Object.assign(this.data, p) } })
  let committed = 0
  vm.show({ title: '已将「小林」移出俱乐部', actionText: '撤销', onCommit: () => { committed += 1 } })
  def.pageLifetimes.hide.call(vm)
  assert.equal(committed, 1)
  def.pageLifetimes.hide.call(vm)
  assert.equal(committed, 1, '只提交一次')
  vm._clearTimers()
})
