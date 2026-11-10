'use strict'
// r2-play(2026-11-10 业主第二轮评审):游玩页各条的合同。截图证据见 docs/delivery/questo-ui-20261109/r2-play/。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.join(__dirname, '../..')
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8')
const kitCard = require('../../pages/play/utils/story-kit-card.js')

test('a1-v3-10-53 入口卡:封面 + 类型 + 名字,不写「打开」;类型名与作者玩法目录同名', () => {
  const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js')
  const byKey = { album: 'album', ballshake: 'shake', reaction: 'react', countdown: 'countdown', predict: 'predict', random: 'random', stopwatch: 'stopwatch', hidden: 'hidden' }
  for (const type of kitCard.KIT_FULLSCREEN_FROM_CARD) {
    const item = catalog.ALL.find((x) => x.key === byKey[type])
    assert.ok(item, type)
    assert.equal(kitCard.KIT_CARD_META[type].label, item.label, type + ' 与作者看到的玩法名一致')
    assert.equal(kitCard.KIT_CARD_META[type].icon, item.icon, type + ' 图标同目录')
    const s = kitCard.playKitState({}, { type }, true, false)
    assert.equal(s.cardLabel, item.label)
  }
  const wxml = read('pages/play/index.wxml')
  const card = wxml.slice(wxml.indexOf('class="chfull__kitcard"'), wxml.indexOf('<cy-playkit wx:else'))
  assert.doesNotMatch(card, />打开</)
  assert.match(card, /img\.first\(advancedPlay\.node\.imgUrl\)/)
  assert.match(card, /\{\{playKit\.cardLabel\}\}/)
})

test('a1-v3-10-53 同一时刻只有一个返回:入口卡在流里时不出玩法返回,玩法自己的返回在屏上时预览返回让开', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="fx-story__act fx-story__kitback" wx:if="\{\{advancedPlay\.show && playKit\.kit && !playKit\.card\}\}"/)
  assert.match(wxml, /class="preview-back glass" wx:if="\{\{isPreview && !playKit\.show && !album\.show && !\(chapterFull && advancedPlay\.show && playKit\.kit && !playKit\.card\)\}\}"/)
})

function ballHost() {
  let def
  const listeners = new Set(); const starts = []; const stops = []; const haptics = []
  const wx = {
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 700 }),
    onAccelerometerChange(fn) { listeners.add(fn) }, offAccelerometerChange(fn) { listeners.delete(fn) },
    startAccelerometer(o) { starts.push(o) }, stopAccelerometer() { stops.push(1) },
  }
  vm.runInNewContext(read('pages/play/components/playkit-ballshake/index.js'), {
    Component: (x) => { def = x }, wx, Date, Math, Number, setTimeout, clearTimeout, setInterval, clearInterval,
    require: (name) => {
      if (name.endsWith('/play-shake.js')) return require('../../pages/play/utils/play-shake.js')
      if (name.endsWith('/sensor-contract.js')) return require('../../pages/play/utils/sensor-contract.js')
      if (name.endsWith('/motion.js')) return { haptic: (o) => haptics.push(o.type) }
      if (name.endsWith('/toast.js')) return () => {}
      return {}
    },
  })
  const data = {}
  for (const [k, v] of Object.entries(def.properties)) data[k] = v.value
  Object.assign(data, JSON.parse(JSON.stringify(def.data)), { show: true })
  const ball = Object.assign({ data, setData(p, cb) { Object.assign(data, p); if (cb) cb() }, selectComponent: () => null }, def.methods)
  def.lifetimes.attached.call(ball)
  let started = 0
  ball.onStart = () => { started++ }
  return { def, ball, listeners, starts, stops, haptics, wx, started: () => started }
}

test('a1-v3-10-53 弹球:露出整屏时听摇一摇,真摇(同抛硬币阈值)= 开始 + 震动;静止和走路不触发', () => {
  const h = ballHost()
  h.def.observers.show.call(h.ball, true)
  assert.equal(h.listeners.size, 1)
  assert.equal(h.starts[0].interval, 'game')
  const feed = [...h.listeners][0]
  for (const rest of [{ x: 0, y: 0, z: 1 }, { x: 0.6, y: -0.4, z: 1.3 }]) feed(rest)
  assert.equal(h.started(), 0)
  feed({ x: 1.4, y: -0.8, z: 0.7 })
  assert.equal(h.started(), 1)
  assert.deepEqual(h.haptics, ['medium'])
  // 隐藏 / 关掉整屏:监听撤掉,传感器关掉
  h.def.pageLifetimes.hide.call(h.ball)
  assert.equal(h.listeners.size, 0)
  assert.ok(h.stops.length >= 1)
})

test('a1-v3-10-53 弹球:关不掉监听的环境不开传感器;跑动中摇一下只改速度(踢一脚)并震动', () => {
  const h = ballHost()
  delete h.wx.offAccelerometerChange
  h.def.observers.show.call(h.ball, true)
  assert.equal(h.listeners.size, 0)
  const k = ballHost()
  assert.equal(k.ball._kick(), false, '没在跑时不踢')
  k.ball._attempt = { phase: 'running' }; k.ball.data.running = true
  k.ball._vx = 0; k.ball._vy = 0
  assert.equal(k.ball._kick(() => 0), true)
  assert.ok(k.ball._vx > 0 && Math.abs(k.ball._vy) < 1e-9)
  assert.deepEqual(k.haptics, ['medium'])
})

test('a1-v3-13-14 标题居中;左上地图放大时顶部数据行让开,收回罗盘再出现', () => {
  assert.match(read('pages/play/theme-backpack.wxss'), /\.chfull \.fx-story__t\.theme-chapter-nav\{position:absolute;left:210rpx;text-align:center;right:210rpx;/)
  const css = read('pages/play/play-topbar.wxss')
  assert.match(css, /\.chfull\.is-navmap \.hud2\{ opacity:0; transform:translateY\(-12rpx\); pointer-events:none; \}/)
  assert.match(css, /prefers-reduced-motion: reduce\)\{ \.chfull \.hud2\{ transition:none; \}/)
})

test('a1-v3-15 延伸任务:入口行带旗标与计数徽章;半屏每站一张任务卡(配图或序号 + 地址)', () => {
  assert.match(read('pages/play/index.wxml'), /class="chfull__tasks"[^>]*hover-class="chfull__tasks--press"[^>]*><view class="chfull__tasks-ico"/)
  // l-play 2026-11-10(业主第二轮「改成弹窗」「翻页卡」,proto pop-ext):半屏列表改成居中弹窗里的翻页卡,一次一张,仍是每站一张(配图或序号 + 地址)
  const sheet = read('pages/play/play-topbar.wxml')
  assert.doesNotMatch(sheet, /<cy-sheet show="\{\{storyTasks\.show\}\}"/)
  assert.match(sheet, /<view class="lk-popwrap" wx:if="\{\{storyTasks\.show\}\}"/)
  assert.match(sheet, /class="xd__c lk-card stq__card [^"]*" wx:for="\{\{storyTasks\.rows\}\}"/)
  assert.match(sheet, /<text wx:else class="xd__no stq__no">\{\{item\.no\}\}<\/text>/)
  assert.match(sheet, /wx:if="\{\{item\.address\}\}">地点<text>\{\{item\.address\}\}/)
})

test('a1-v3-16 背包:装载空格是灰方块;随身物品一行两格,空格灰方块至少两行', () => {
  const css = read('pages/play/theme-backpack.wxss')
  assert.match(css, /\.tb-slot\{[^}]*background:var\(--cy-comp-sheet-player-surface-strong\)/)
  assert.match(css, /\.tb-items\{display:grid;grid-template-columns:repeat\(2,minmax\(0,1fr\)\)/)
  assert.match(css, /\.tb-item--empty\{background:var\(--cy-comp-sheet-player-surface-strong\)/)
  assert.match(read('pages/play/theme-backpack.wxml'), /class="tb-item tb-item--empty" wx:for="\{\{tbAttr\.pad\(themeBag\.items\.length\)\}\}"/)
})

test('a1-v3-17 入场码:没有外层白卡,标题与到期行用弹层 token 色;码块仍是白底', () => {
  const css = read('pages/play/components/scene-qr-ticket/index.wxss')
  const card = css.match(/\.voucher__card \{[^}]*\}/)[0]
  assert.doesNotMatch(card, /background/)
  assert.match(css, /\.voucher__title \{[^}]*color: var\(--cy-comp-sheet-player-text\)/)
  assert.match(css, /\.voucher__exp \{[^}]*color: var\(--cy-comp-sheet-player-text-secondary\)/)
  assert.match(css, /\.voucher__qr \{[^}]*background: #fff;/)
})

test('a1-v3-06 / 规则 1:游玩页半屏不再用纯黑页底当面,背包页用实底', () => {
  for (const f of ['pages/play/theme-backpack.wxml', 'pages/play/theme-player-ops.wxml', 'pages/play/theme-plan-sheet.wxml']) {
    assert.doesNotMatch(read(f), /--cy-comp-sheet-role-glass:var\(--cy-comp-sheet-player-page\)/, f)
  }
  assert.match(read('pages/play/theme-backpack.wxss'), /\.tbp\{[^}]*background:var\(--cy-comp-sheet-player-bg\)/)
})

test('a1-v3-21 / c4-ug-05:提示是居中弹窗(关闭右上);背包是整屏页面(左上返回);城市定向站点整屏打开', () => {
  const help = read('pages/play/play-help.wxml')
  assert.doesNotMatch(help, /<cy-sheet/)
  assert.match(help, /<view class="phl-pop" wx:if="\{\{helpPanel\.show\}\}">/)
  assert.match(read('pages/play/play-help.wxss'), /\.phl-pop\{[^}]*z-index:var\(--cy-z-modal\);[^}]*align-items:center;justify-content:center/)
  const bag = read('pages/play/theme-backpack.wxml')
  assert.match(bag, /<view class="tbp" wx:if="\{\{themeBackpackOpen\}\}"/)
  assert.match(bag, /class="tbp__back" bindtap="closeThemeBackpack"/)
  assert.match(bag, /<view class="phl-pop" wx:if="\{\{!!coopHelp\}\}">/)
  const css = read('pages/play/theme-backpack.wxss')
  assert.match(css, /\.tb-close\{[^}]*margin-left:auto;/)
  assert.match(css, /\.tb-close \+ \.tb-title\{[^}]*margin-top:calc\(-1 \* var\(--cy-btn-h\)\)/)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="sheet \{\{chapterFull \? 'sheet--story' : ''\}\} \{\{mode!=2 \? 'sheet--game' : ''\}\}/)
  assert.match(wxml, /class="sheet-x" wx:if="\{\{mode!=2\}\}"[^>]*bindtap="closeSheet"/)
  assert.match(read('pages/play/index.wxss'), /\.sheet\.sheet--game\{ top:0; border-radius:0; min-height:100vh;/)
})

test('a1-v3-21 定位:站点卡不再写定位提示句;问过没开定位时主按钮是「扫码到达」,绑原有 onScanArrive', () => {
  const wxml = read('pages/play/index.wxml').replace(/<!--[\s\S]*?-->/g, '')
  assert.doesNotMatch(wxml, /\{\{gpsHint\}\}<text class="sheet-note-action"/)
  assert.match(wxml, /<cy-btn wx:elif="\{\{mode!=2 && sheet\.node\.lat && !gpsOk && !isPreview && locAsked\}\}"[^>]*bindtap="onScanArrive">扫码到达<\/cy-btn>/)
})

test('a1-v3-21 定位一局只问一次;系统拒绝时给「怎么打开」并能去设置,打开后静默取位置', () => {
  const once = require('../../pages/play/utils/play-location-once.js')
  const shown = []; const opened = []; const relocated = []
  const prev = global.wx
  global.wx = { showModal: (o) => shown.push(o), openSetting: (o) => { opened.push(1); o.success({ authSetting: { 'scope.userLocation': true } }) } }
  try {
    const page = Object.assign({ data: { gpsOk: false }, setData(p) { Object.assign(this.data, p) } }, once.methods())
    page.requestPlayLocation = function () { relocated.push(this._locSilent) }
    assert.equal(page._locationAskedOnce(), false, '第一次放行弹说明')
    assert.equal(page.data.locAsked, true)
    assert.equal(page._locationAskedOnce(), true, '之后不再弹')
    assert.equal(once.deniedBySystem({ errMsg: 'getLocation:fail auth deny' }), true)
    assert.equal(once.deniedBySystem({ errMsg: 'getLocation:fail timeout' }), false)
    assert.equal(page._locationGuide({ errMsg: 'getLocation:fail timeout' }), false)
    assert.equal(page._locationGuide({ errMsg: 'getLocation:fail auth deny' }), true)
    assert.equal(page._locationGuide({ errMsg: 'getLocation:fail auth deny' }), false, '怎么打开只给一次')
    assert.equal(shown.length, 1)
    assert.equal(shown[0].title, '打开定位')
    assert.equal(shown[0].confirmText, '去设置')
    shown[0].success({ confirm: true })
    assert.deepEqual(opened, [1])
    assert.deepEqual(relocated, [true], '设置里打开后静默取一次')
  } finally { global.wx = prev }
})

test('s0-fixture-play-entry 光圈转场揭开时不再缩回中心一块黑斑;发布预览副本逐字一致', () => {
  const css = read('pages/play/components/look-transition/index.wxss')
  assert.match(css, /\.lt--iris\.is-out \.lt__circle \{ transform: scale\(1\.15\); opacity: 0; \}/)
  assert.doesNotMatch(css, /\.lt--iris\.is-out \.lt__circle \{ transform: scale\(0\); \}/)
  assert.equal(read('pages/publish/components/look-transition/index.wxss'), css)
})
