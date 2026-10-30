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
  const ctx = new Proxy({}, { get: (t, k) => (k in t ? t[k] : () => {}), set: (t, k, v) => { t[k] = v; return true } })
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

test('选风格先弹底部预览,点「使用这个风格」才写回;返回键先收预览', () => {
  const editor = read('pages/publish/fabu/theme-look-editor.js')
  assert.match(editor, /onLookStyle\(e\) \{[\s\S]*?lookPreview: \{ show: true/)
  assert.doesNotMatch(editor.match(/onLookStyle\(e\) \{[\s\S]*?\n  \},/)[0], /_patchLook/, '点风格不许直接写回')
  assert.match(editor, /onLookPreviewUse\(e\) \{[\s\S]*?_patchLook/)
  assert.match(read('pages/publish/fabu/look-sheet.wxml'), /<cy-look-preview show="\{\{lookPreview\.show\}\}" look="\{\{lookPreview\.look\}\}" bind:use="onLookPreviewUse" bind:close="onLookPreviewClose" \/>/)
  assert.ok(JSON.parse(read('pages/publish/fabu/index.json')).usingComponents['cy-look-preview'])
  const preview = read('pages/publish/components/look-preview/index.wxml')
  assert.match(preview, /bindtap="onPlayTransition"/)
  assert.match(preview, /bindtap="onUse"/)
  assert.match(read('pages/publish/fabu/index.js'), /lookPreview\.show\)\s*\{\s*this\.onLookPreviewClose\(\);/)
})
