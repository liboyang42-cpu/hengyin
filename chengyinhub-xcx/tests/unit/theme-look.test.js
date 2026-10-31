// 主题画面风格：目录两份副本一致、与后端 ThemeLook.java 同口径、读写回落、风格层与转场齐全。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const look = require('../../pages/play/utils/theme-look.js')
const fx = require('../../pages/play/utils/look-fx.js')

test('play 与 publish 两个分包里的 theme-look.js 逐字相同', () => {
  assert.equal(read('pages/publish/utils/publish/theme-look.js'), read('pages/play/utils/theme-look.js'))
})

test('取值域与后端 ThemeLook.java 一致(风格 / 参数 / 转场 / 字体)', () => {
  const java = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/util/ThemeLook.java')
  for (const s of look.STYLES) {
    const block = java.slice(java.indexOf('s.put("' + s.value + '"'), java.indexOf(')));', java.indexOf('s.put("' + s.value + '"')) + 4)
    assert.ok(block.length > 0, s.value)
    for (const p of s.params) {
      const m = block.match(new RegExp('"' + p.key + '", set\\(([^)]*)\\)'))
      assert.ok(m, s.value + '.' + p.key)
      assert.deepEqual(m[1].match(/"([A-Z_]+)"/g).map(v => v.slice(1, -1)).sort(), p.options.map(o => o[0]).sort())
    }
  }
  const setOf = name => java.match(new RegExp(name + ' = set\\(([^;]*)\\);'))[1].match(/"([A-Z_]+)"/g).map(v => v.slice(1, -1)).sort()
  assert.deepEqual(setOf('TRANSITIONS'), look.TRANSITIONS.map(t => t[0]).sort())
  assert.deepEqual(setOf('STORY_FONTS'), look.STORY_FONTS.map(t => t[0]).sort())
})

test('至少 10 种转场(不含跟随风格),每种风格的默认转场都在目录里', () => {
  assert.ok(look.TRANSITIONS.filter(t => t[0] !== 'AUTO').length >= 10)
  for (const s of look.STYLES) assert.ok(look.TRANSITIONS.some(t => t[0] === s.transition), s.value)
})

test('未配置 / 读不懂 → 默认星尘 + 跟随风格(光圈) + 手写,老主题外观不变', () => {
  const def = look.normalizeLook(null)
  assert.equal(def.style, 'STARDUST')
  assert.equal(def.transition, 'AUTO')
  assert.equal(def.storyFont, 'HAND')
  assert.equal(look.resolveTransition(null), 'IRIS')
  assert.deepEqual(look.normalizeLook('{bad'), def)
  assert.deepEqual(look.normalizeLook({ style: 'SUN', transition: 'BOOM', storyFont: 'COMIC' }), def)
  assert.equal(look.serializeLook(def), '')
})

test('参数只认本风格的键,错值回落该参数默认;换风格不串参数', () => {
  const n = look.normalizeLook({ style: 'cyber', params: { neon: 'cyan_pink', current: 'NOPE', climate: 'STORM' }, transition: 'warp', storyFont: 'kai' })
  assert.deepEqual(n, { style: 'CYBER', params: { neon: 'CYAN_PINK', current: 'MID', glitch: 'TRANSITION' }, transition: 'WARP', storyFont: 'KAI' })
  assert.equal(look.resolveTransition(n), 'WARP')
  assert.equal(look.resolveTransition({ style: 'SURVIVAL' }), 'GUST')
  assert.equal(look.lookClass(n), 'look--cyber look-neon--cyan_pink look-current--mid look-glitch--transition story-font--kai')
})

test('每种风格的风格层都能连续画帧(假 canvas),末世返回风速', () => {
  const ctx = new Proxy({}, { get: (t, k) => (k in t ? t[k] : /^create(Radial|Linear)Gradient$/.test(k) ? () => ({ addColorStop() {} }) : () => {}), set: (t, k, v) => { t[k] = v; return true } })
  for (const s of look.STYLES) for (const p of s.params) for (const o of p.options) {
    const n = look.normalizeLook({ style: s.value, params: { [p.key]: o[0] } })
    const state = fx.create(n, 390, 844)
    let w = 0
    for (let t = 0; t < 120; t++) w = fx.draw(state, ctx, 390, 844, 1000 + t * 250)
    assert.equal(typeof w, 'number')
    const m = fx.dustMod(n)
    assert.ok(m.n >= 0 && m.speed > 0, s.value)
  }
  assert.equal(fx.dustMod(look.normalizeLook({ style: 'SURVIVAL' })).n, 0)
})

test('转场组件:除跟随风格外每一种都有进出两段动画', () => {
  const wxss = read('pages/play/components/look-transition/index.wxss')
  for (const [kind] of look.TRANSITIONS) {
    if (kind === 'AUTO') continue
    const k = kind.toLowerCase()
    assert.ok(wxss.includes('.lt--' + k + '.is-in'), k + ' 缺盖上动画')
    assert.ok(wxss.includes('.lt--' + k + '.is-out'), k + ' 缺揭开动画')
  }
})

test('玩家页:根节点挂风格类、转场层已注册、两张远景画布后都有纹理层', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="play \{\{lookClass\}\}/)
  assert.match(wxml, /<cy-look-transition id="lookTx" kind="\{\{lookTransition\}\}"/)
  assert.equal((wxml.match(/id="stBack" class="fx-story__dust"><\/canvas><view class="look-tex"><\/view>/g) || []).length, 2)
  assert.ok(JSON.parse(read('pages/play/index.json')).usingComponents['cy-look-transition'])
  const js = read('pages/play/index.js')
  assert.match(js, /that\._applyLook\(d\.look\)/)
  assert.match(js, /this\._withLookTransition\(\(\) => this\.startGame\(node\)\)/)
})

test('发布页:保存上送 look,回读核对画面风格', () => {
  const js = read('pages/publish/fabu/index.js')
  assert.match(js, /look: this\._lookPayload\(\)/)
  assert.match(js, /'formData\.look': detail\.look \|\| ''/)
  assert.match(read('pages/publish/utils/publish/server-draft.js'), /云端保存的画面风格与本机不一致/)
})

test('发布页预览用的风格层与转场是玩家页的逐字副本(作者看到的就是玩家看到的)', () => {
  assert.equal(read('pages/publish/utils/publish/look-fx.js'), read('pages/play/utils/look-fx.js'))
  for (const f of ['index.js', 'index.json', 'index.wxml', 'index.wxss']) {
    assert.equal(read('pages/publish/components/look-transition/' + f), read('pages/play/components/look-transition/' + f), f)
  }
})

test('画面风格(2026-10-30 业主,对齐原型 v7):预览常驻;风格 → 参数 → 主题配色 → 字体;转场系统配好', () => {
  // 主题配色 = 章节配色同五档,存进 look.palette;不设 = 各章沿用自己的章节配色
  assert.deepEqual(look.PALETTES.map(p => p[0]), ['DEFAULT', 'BLUE', 'RED', 'YELLOW', 'WHITE'])
  assert.equal(look.normalizeLook({ style: 'PAPER' }).palette, undefined, '老主题没有主题配色')
  assert.equal(look.normalizeLook({ style: 'PAPER', palette: 'yellow' }).palette, 'YELLOW')
  assert.equal(look.normalizeLook({ style: 'PAPER', palette: 'GREEN' }).palette, undefined)
  assert.match(look.lookClass({ style: 'PAPER', palette: 'YELLOW' }), /look-palette--yellow/)
  assert.ok(look.STORY_FONTS.some(f => f[0] === 'BOLD'), '新增粗斜字体')
  const java = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/util/ThemeLook.java')
  assert.match(java, /STORY_FONTS = set\("HAND", "SONG", "KAI", "HEI", "BOLD"\)/)
  assert.match(java, /PALETTES = set\("DEFAULT", "BLUE", "RED", "YELLOW", "WHITE"\)/)

  const sheet = read('pages/publish/fabu/look-sheet.wxml')
  assert.match(sheet, /<cy-look-preview class="look-pin" embedded="\{\{true\}\}" show="\{\{lookSheetShow\}\}" look="\{\{lookView\.look\}\}" \/>/, '预览常驻、钉在顶部')
  const at = (k) => sheet.indexOf(k)
  assert.ok(at('cy-look-preview') < at('onLookStyle') && at('onLookStyle') < at('onLookParam') && at('onLookParam') < at('onLookPalette') && at('onLookPalette') < at('onLookFont'), '预览 → 风格 → 参数 → 主题配色 → 字体')
  assert.match(sheet, /<text>主题配色<\/text>/)
  for (const gone of ['onLookTransition', 'lookPreview.show']) assert.ok(!sheet.includes(gone), '作者面板不再有 ' + gone)
  assert.match(read('pages/publish/fabu/index.wxml'), /class="chapter-atmosphere" wx:if="\{\{formData\.productType === 1 && !lookView\.themed\}\}"/, '设了主题配色,章节里不再单设')

  const page = { data: { formData: { look: '', chapters: [{ atmospherePreset: 'BLUE' }] } }, setData(patch) { for (const k of Object.keys(patch)) { const ks = k.split('.'); let o = this.data; while (ks.length > 1) o = o[ks.shift()]; o[ks[0]] = patch[k] } } }
  Object.assign(page, require('../../pages/publish/fabu/theme-look-editor.js').methods)
  page._refreshLookView()
  assert.equal(page.data.lookView.palettes.find(p => p.on).value, 'BLUE', '没设主题配色:预览先按第一章的配色')
  assert.equal(page.data.formData.look, '', '只是打开不写')
  const tap = (m, dataset) => page[m]({ currentTarget: { dataset } })
  tap('onLookStyle', { value: 'PAPER' })
  tap('onLookParam', { key: 'paper', value: 'XUAN' })
  tap('onLookPalette', { value: 'YELLOW' })
  tap('onLookFont', { value: 'BOLD' })
  assert.deepEqual(JSON.parse(page.data.formData.look), { style: 'PAPER', params: { paper: 'XUAN', grain: 'MEDIUM', stamp: 'VERIFIED' }, transition: 'AUTO', storyFont: 'BOLD', palette: 'YELLOW' })
  assert.equal(page.data.lookView.summary, '纸本 · 黄 · 粗斜')
  tap('onLookStyle', { value: 'CYBER' })
  const n = JSON.parse(page.data.formData.look)
  assert.equal(n.palette, 'YELLOW', '换风格:主题配色保留'); assert.equal(n.storyFont, 'BOLD', '换风格:字体保留'); assert.equal(n.params.neon, 'YELLOW_CYAN', '换风格:参数回新风格默认')

  // 玩家页:主题配色设了就统一各章配色;底色表按风格映射,参数不再改底色
  const play = read('pages/play/index.js')
  assert.equal((play.match(/atmosphereClass\(this\._themePalette\(/g) || []).length, 3)
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.look--paper \.atmosphere--yellow\{ --atmo-bg:#E6DCC4;/, '纸本 × 黄 = 牛皮纸(原型 v7)')
  assert.ok(!/look-climate--\w+ \{ --look-bg/.test(wxss) && !/look-paper--(xuan|card) \{ --look-bg/.test(wxss), '参数不改底色')
  assert.match(wxss, /\.story-font--bold \.cy-story-hand \{[^}]*font-weight: 900; font-style: italic;/)

  assert.match(read('pages/publish/components/look-preview/index.wxml'), /<view wx:if="\{\{embedded\}\}" class="lp lp--embedded">/)
  assert.ok(!/lookPreview/.test(read('pages/publish/fabu/index.js')), '返回键不再处理已删的弹出预览')
})

test('星尘与末世优化(2026-10-30 业主):字处在环境里、浅底深色粒子、末世四种天气 + 每章时间段', () => {
  const surv = look.STYLES.find(s => s.value === 'SURVIVAL')
  assert.deepEqual(surv.params[0].options.map(o => o[0]), ['CLEAR', 'RAIN', 'STORM', 'SAND'], '天气:晴 / 雨 / 雷暴 / 沙尘暴')
  assert.deepEqual(Object.keys(fx.CLIMATE), ['CLEAR', 'RAIN', 'STORM', 'SAND'])
  assert.ok(fx.CLIMATE.STORM.every(p => p.bolt), '雷暴每一段都会打闪')
  // 每章时间段:只在末世里,按章节顺序,尾部空的不存
  assert.deepEqual(look.normalizeLook({ style: 'SURVIVAL', times: ['dawn', '', 'dusk', ''] }).times, ['DAWN', '', 'DUSK'])
  assert.equal(look.normalizeLook({ style: 'PAPER', times: ['DAWN'] }).times, undefined)
  assert.match(read('../chengyinhub-system/src/main/java/com/chengyinhub/business/util/ThemeLook.java'), /TIMES = set\("DAWN", "NOON", "DUSK", "NIGHT"\)/)

  // 白底:粒子画深色;星尘不跟场景的风
  // 漂浮的东西只在星尘里:其它风格不撒浮尘;星尘背景干净(不叠场景光),整片银河自己画;老主题(没配风格)浮尘照旧
  for (const st of ['PAPER', 'SURVIVAL', 'CYBER']) assert.equal(fx.dustMod({ style: st, params: {} }).n, 0, st + ' 不撒浮尘')
  assert.equal(fx.dustMod({ style: 'STARDUST', params: {} }).clean, true)
  assert.deepEqual(fx.dustMod({ style: 'STARDUST', params: {}, legacy: true }), { n: 1, speed: 1, col: null }, '老主题外观不变')
  assert.equal(fx.create({ style: 'STARDUST', params: {}, legacy: true }, 390, 844).kind, 'none')
  assert.match(read('pages/play/utils/play-look.js'), /raw \? \{\} : \{ legacy: true \}/)
  assert.match(read('pages/play/index.js'), /if \(!near && !lookFx\.dustMod\(that\._look\)\.clean\) that\._paintSceneLight/)
  const ctx = new Proxy({}, { get: (t, k) => (/^create(Radial|Linear)Gradient$/.test(k) ? () => ({ addColorStop() {} }) : () => {}), set: () => true })
  const storm = fx.create({ style: 'SURVIVAL', params: { climate: 'STORM', volatility: 'VIOLENT', edge: 'HP' }, palette: 'WHITE' }, 390, 844)
  assert.equal(storm.light, true)
  let now = 1e6
  for (let i = 0; i < 1500; i++) { now += 16; fx.draw(storm, ctx, 390, 844, now); fx.drawFront(storm, ctx, 390, 844, now) }
  assert.ok(storm.flashAt > 0, '雷暴打过闪,页面据此把字照亮')
  assert.ok(storm.G.some(d => d.born), '字前面有玻璃上的水珠')
  const dust = fx.create({ style: 'STARDUST', params: { density: 'NORMAL', drift: 'SLOW', tone: 'WHITE' } }, 390, 844)
  assert.equal(dust.kind, 'stardust')
  assert.ok(dust.back.length > dust.front.length && dust.front.length > 0, '字后几颗、字前几颗')
  assert.ok(dust.back.concat(dust.front).every(q => q.shape === 0 || q.shape === 1), '只有方块和三角')
  assert.ok(dust.back.concat(dust.front).every(q => q.s <= 4.4), '都很小')
  assert.ok(new Set(dust.back.map(q => q.c)).size >= 3, '颜色多样')
  const dense = fx.create({ style: 'STARDUST', params: { density: 'DENSE' } }, 390, 844)
  assert.ok(dense.back.length > dust.back.length, '浮尘参数决定数量')
  const y0 = dust.back[0].y; fx.draw(dust, ctx, 390, 844, now); fx.drawFront(dust, ctx, 390, 844, now)
  assert.notEqual(dust.back[0].y, y0, '慢慢漂')
  assert.equal(read('pages/publish/utils/publish/look-fx.js'), read('pages/play/utils/look-fx.js'), '发布页预览用同一份')

  // 玩家页接线:字前面那张画 drawFront、打闪照亮字、章节类带时间段
  const js = read('pages/play/index.js'), wxml = read('pages/play/index.wxml'), wxss = read('pages/play/index.wxss')
  assert.match(js, /lookFx\.drawFront\(that\._lookFx, ctx, W, H, Date\.now\(\)\); that\._lookLightUp\(that\._lookFx\.flashAt\);/)
  assert.equal((js.match(/\+ this\._chapterTime\(/g) || []).length, 3)
  assert.equal((wxml.match(/\{\{lookLit \? 'is-lit' : ''\}\}/g) || []).length, 2)
  assert.match(wxss, /\.look--survival \.look-time--dusk\{ background-image:/)
  assert.ok(!/look-climate--(winter|heat|bunker)/.test(wxss), '旧气候的样式已清掉')

  // 编辑器:末世才出现每章时间段,再点一次取消
  const page = { data: { formData: { look: '', chapters: [{ name: '早班渡轮' }, { name: '日落之前' }] } }, setData(patch) { for (const k of Object.keys(patch)) { const ks = k.split('.'); let o = this.data; while (ks.length > 1) o = o[ks.shift()]; o[ks[0]] = patch[k] } } }
  Object.assign(page, require('../../pages/publish/fabu/theme-look-editor.js').methods)
  page._refreshLookView(); assert.equal(page.data.lookView.times.length, 0, '星尘没有时间段')
  page.onLookStyle({ currentTarget: { dataset: { value: 'SURVIVAL' } } })
  assert.deepEqual(page.data.lookView.times.map(t => t.name), ['早班渡轮', '日落之前'])
  page.onLookTime({ currentTarget: { dataset: { index: 1, value: 'DUSK' } } })
  assert.deepEqual(JSON.parse(page.data.formData.look).times, ['', 'DUSK'])
  page.onLookTime({ currentTarget: { dataset: { index: 1, value: 'DUSK' } } })
  assert.equal(JSON.parse(page.data.formData.look).times, undefined, '再点一次取消')
  assert.match(read('pages/publish/fabu/look-sheet.wxml'), /bindtap="onLookTime"/)
  assert.match(read('pages/publish/components/look-preview/index.wxml'), /id="lpfront"/)
})

test('整流程走查修正(2026-10-30):预览带风格、故事流铺主题底色、浅底状态栏只在故事流里换黑、雷达真的变大', () => {
  // 作者从编辑器点「预览」:草稿预览数据带上 look,玩家页预览链在建章节之前落风格
  const { buildTopicPreview } = require('../../pages/publish/utils/publish/topic-draft-preview.js')
  const src = read('pages/publish/utils/publish/topic-draft-preview.js')
  assert.match(src, /look: form\.look \|\| ''/)
  assert.equal(typeof buildTopicPreview, 'function')
  const js = read('pages/play/index.js')
  assert.match(js, /that\._applyLook\(d\.look\); const chapter = that\.buildChapter\(d, nodes\);/, '预览链先落风格再建章节')

  // 故事流根节点:选过风格的主题才带章节 / 主题配色类(老主题保持纯黑故事背景)
  const wxml = read('pages/play/index.wxml')
  assert.equal((wxml.match(/\{\{lookOn \? chapter\.atmosphereClass : ''\}\}/g) || []).length, 2, '城市定向 chfull 与自由探索 fx-story 两处')
  const look = read('pages/play/utils/play-look.js')
  assert.match(look, /lookOn: !look\.legacy/)
  assert.match(read('pages/play/index.wxss'), /\.fx-story\[class\*="atmosphere--"\]\{ background:var\(--atmo-bg\);/)

  // 浅底状态栏:只在故事流开着时换黑字;封面 / 卡包页仍是深底白字;相册关掉回到故事流的颜色
  assert.match(look, /this\._storyLight = !look\.legacy && \(look\.palette === 'WHITE' \|\| \(look\.style === 'PAPER' && look\.palette === 'YELLOW'\)\)/)
  const applyBody = look.slice(look.indexOf('_applyLook(raw) {'), look.indexOf('\n    },', look.indexOf('_applyLook(raw) {')))
  assert.ok(applyBody.length > 0 && !applyBody.includes('setNavigationBarColor'), '落风格时不直接改状态栏')
  assert.match(js, /_startStoryDust\(\) \{\n    this\._stopStoryDust\(\);\n    this\._storyStatusBar\(true\);/)
  assert.match(js, /_stopStoryDust\(\) \{\n    clearInterval\(this\._oddTimer\); this\._storyStatusBar\(false\);/)
  assert.match(js, /light = light \|\| !!this\._storyLight;/)

  // 地图档雷达:本文件在 index.wxss 顶部 @import,选择器要压过后面的 .radar__disc(88rpx);右上角按钮不跟着下移
  const tb = read('pages/play/play-topbar.wxss')
  assert.match(tb, /\.radar__disc\.radar__disc--big\{ position:relative; width:264rpx;/)
  assert.match(tb, /\.chfull\.is-navmap \.fx-story__hd\{ align-items:flex-start; \}/)
  assert.ok(read('pages/play/index.wxss').indexOf('@import "./play-topbar.wxss"') < read('pages/play/index.wxss').indexOf('.radar__disc{'), '顶栏样式先于 .radar__disc 引入,所以必须提高权重')
})

test('故事文字动效(2026-10-30 业主定稿原型 v3):每段滑进来播一次、按风格轮换、作者可在字体下面选一种', () => {
  // 目录:13 种,去掉了翻页 / 盖印 / 逐字;后端同口径
  assert.deepEqual(look.TEXT_FX.map(t => t[0]), ['rise', 'type', 'ink', 'gather', 'glow', 'wipe', 'drop', 'wind', 'signal', 'fog', 'neon', 'scan', 'decode'])
  for (const gone of ['page', 'stamp', 'char']) assert.equal(look.normalizeLook({ style: 'PAPER', textFx: gone }).textFx, undefined, gone + ' 已取消')
  assert.match(read('../chengyinhub-system/src/main/java/com/chengyinhub/business/util/ThemeLook.java'), /TEXT_FX = new HashSet<>\(Arrays\.asList\("rise", "type", "ink", "gather", "glow", "wipe",\s*"drop", "wind", "signal", "fog", "neon", "scan", "decode"\)\)/)
  // 池子:星尘 聚星 / 浮现 / 呼吸光;纸本 打字机 / 墨晕 / 落定;轮换时相邻两段不重复
  assert.deepEqual(look.STYLE_TEXT.STARDUST.pool, ['gather', 'rise', 'glow'])
  assert.deepEqual(look.STYLE_TEXT.PAPER.pool, ['type', 'ink', 'drop'])
  for (const s of look.STYLES) { const seq = [0, 1, 2, 3, 4, 5].map(i => look.textFxFor({ style: s.value }, i)); seq.slice(1).forEach((f, i) => assert.notEqual(f, seq[i], s.value + ' 相邻两段不重复')) }
  assert.equal(look.textFxFor({ style: 'CYBER', textFx: 'ink' }, 3), 'ink', '作者选了一种就全用它')
  assert.equal(look.textFxChars('decode'), true); assert.equal(look.textFxChars('scan'), false)

  // 玩家页:正文段带 fx(逐字类带 chars,标点粘前一字);滑进屏幕(is-in)才播;解码滑进来时才逐拍还原;老主题不带
  const mixin = require('../../pages/play/utils/play-look.js').methods()
  const host = Object.assign({ data: { chapterParas: [] }, setData() {} }, mixin)
  host._look = look.normalizeLook({ style: 'PAPER' })
  const a = host._textFx({ kind: 'image' }, 0), b = host._textFx({ kind: 'text', text: '名单上的字迹已经褪色,' }, 1), c = host._textFx({ kind: 'text', text: '最后一行。' }, 2)
  assert.deepEqual(a, {}); assert.equal(b.fx, 'type'); assert.equal(c.fx, 'ink', '轮换只数正文段')
  assert.equal(b.chars[b.chars.length - 1].ch, '色,', '标点粘在前一个字上')
  host._look = Object.assign(look.normalizeLook(null), { legacy: true })
  assert.deepEqual(host._textFx({ kind: 'text', text: '老主题' }, 0), {}, '没配风格的老主题不加动效')
  const js = read('pages/play/index.js'), wxml = read('pages/play/index.wxml'), wxss = read('pages/play/index.wxss')
  assert.match(js, /\}, item, this\._textFx\(item, i\)\)\),/)
  assert.match(js, /if \(chapterFull && ln\.fx === 'decode'\) that\._decodeLine\(i\);/)
  assert.match(wxml, /\{\{item\.fx \? 'tfx tfx--' \+ item\.fx : ''\}\}/)
  assert.match(wxml, /class="tfx__c \{\{c\.raw \? 'is-raw' : ''\}\}" style="--i:\{\{c\.i\}\};--r:\{\{c\.r\}\};--dx:\{\{c\.dx\}\}px;--dy:\{\{c\.dy\}\}px" aria-hidden="true"/)
  assert.match(wxml, /<text wx:if="\{\{item\.chars\}\}" class="tfx__sr">\{\{item\.text\}\}<\/text>/, '拆成单字后整句仍给读屏')
  for (const fx of ['type', 'ink', 'gather', 'drop', 'wind', 'signal']) assert.match(wxss, new RegExp('\\.tfx--' + fx + '\\.is-in \\.tfx__c\\{'), fx + ' 在 is-in 时显形')
  for (const fx of ['glow', 'wipe', 'fog', 'neon', 'scan']) assert.match(wxss, new RegExp('\\.chfull__para\\.tfx--' + fx + '\\.is-in\\{'), fx)
  const tfxCss = wxss.slice(wxss.indexOf('/* ===== 故事文字动效'))
  assert.ok(!/@keyframes|animation\s*:/.test(tfxCss), '只用过渡,不新增关键帧')
  assert.match(tfxCss, /\.play--reduced-motion \.tfx__c\{ transition:none; opacity:1;/)

  // 编辑器:画面风格面板「字体」下面一行「文字动效」:跟随风格 + 13 种;预览示例正文跟着播
  const sheet = read('pages/publish/fabu/look-sheet.wxml')
  assert.ok(sheet.indexOf('>字体<') < sheet.indexOf('>文字动效<') && sheet.indexOf('>文字动效<') > 0, '在字体下面')
  const page = { data: { formData: { look: '' } }, setData(patch) { for (const k of Object.keys(patch)) { const ks = k.split('.'); let o = this.data; while (ks.length > 1) o = o[ks.shift()]; o[ks[0]] = patch[k] } } }
  Object.assign(page, require('../../pages/publish/fabu/theme-look-editor.js').methods)
  page._refreshLookView()
  assert.equal(page.data.lookView.textFxs.length, 14); assert.equal(page.data.lookView.textFxs[0].label, '跟随风格'); assert.equal(page.data.lookView.textFxs[0].on, true)
  page.onLookTextFx({ currentTarget: { dataset: { value: 'decode' } } })
  assert.equal(JSON.parse(page.data.formData.look).textFx, 'decode')
  page.onLookTextFx({ currentTarget: { dataset: { value: '' } } })
  assert.equal(page.data.formData.look, '', '回到跟随风格 = 不存')
  assert.match(read('pages/publish/components/look-preview/index.wxml'), /class="lp__story tfx tfx--\{\{fx\}\} \{\{fxIn \? 'is-in' : ''\}\}"/)
})
