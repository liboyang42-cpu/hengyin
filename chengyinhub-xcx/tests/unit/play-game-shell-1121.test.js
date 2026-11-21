// 玩法外壳(2026-11-21 业主:「游戏要顺滑,主题要一致自然」):整屏玩法的抬头 / 引子、玩完才出的「继续故事」、
// 结果屏的故事口吻与淡出回到故事流。纯函数在 pages/play/utils/game-shell.js,组件行为用假 Component 跑真实定义。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const shell = require('../../pages/play/utils/game-shell.js')
const kitCard = require('../../pages/play/utils/story-kit-card.js')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

/** 加载一个组件的真实定义(不进 wx 运行时):捕获 Component({...}) 的参数 */
function loadComponent(rel, extraGlobals) {
  const file = path.join(ROOT, rel)
  delete require.cache[require.resolve(file)]
  let def = null
  const saved = { Component: global.Component, Behavior: global.Behavior, wx: global.wx, getApp: global.getApp }
  global.Component = (d) => { def = d }
  global.Behavior = (d) => d
  global.wx = Object.assign({ getSystemInfoSync: () => ({ statusBarHeight: 20 }) }, extraGlobals && extraGlobals.wx)
  global.getApp = () => ({ globalData: {} })
  try { require(file) } finally {
    global.Component = saved.Component
    global.Behavior = saved.Behavior
    global.wx = saved.wx
    global.getApp = saved.getApp
  }
  return def
}

/** 组件实例的最小桩:data + setData + triggerEvent,方法从定义上绑过来 */
function instance(def, data, extra) {
  const inst = Object.assign({ events: [] }, extra)
  inst.data = Object.assign({}, def.data, data)
  inst.setData = (patch) => { Object.assign(inst.data, patch) }
  inst.triggerEvent = (name, detail) => { inst.events.push({ name, detail }) }
  Object.keys(def.methods || {}).forEach((k) => { inst[k] = def.methods[k].bind(inst) })
  return inst
}

const NODES = [
  { nodeId: 1, chapterId: 9, name: '1 · 老门' },
  { nodeId: 2, chapterId: 9, name: '外滩', hookText: '风从江面上吹过来。后面的话不进引子' },
  { nodeId: 3, chapterId: 9, num: 101, name: '延伸站' },
  { nodeId: 4, chapterId: 8, name: '别章' },
]

test('抬头:第N站按本章主线排序,延伸站与别章不占站序;认不出站序只写站名', () => {
  assert.deepEqual(shell.stationHead(NODES[1], { id: 9, name: '雾起' }, NODES), { station: '第2站 · 外滩', chapter: '雾起' })
  assert.deepEqual(shell.stationHead(NODES[0], { id: 9, name: '雾起' }, NODES), { station: '第1站 · 老门', chapter: '雾起' })
  assert.equal(shell.stationHead(NODES[2], { id: 9 }, NODES).station, '延伸站')
  assert.equal(shell.stationHead(NODES[3], { id: 8 }, NODES).station, '第1站 · 别章')
  assert.equal(shell.stationHead({ nodeId: 99, name: '路过' }, {}, NODES).station, '路过')
  // 章节不是这一站的章 / 结局章:不写章名(不把别处的名字挂上来)
  assert.equal(shell.stationHead(NODES[1], { id: 8, name: '别的章' }, NODES).chapter, '')
  assert.equal(shell.stationHead(NODES[1], { id: 9, name: '尾声', ending: true }, NODES).chapter, '')
  // 没章名退章序
  assert.equal(shell.stationHead(NODES[1], { id: 9, idxLabel: '第一章' }, NODES).chapter, '第一章')
  assert.deepEqual(shell.stationHead(null, {}, NODES), { station: '', chapter: '' })
})

test('引子:钩子 → 引语 → 描述(占位句不算)→ 故事首句 → 玩法 lead;一句一行,与题面同句不重复', () => {
  assert.equal(shell.storyLead(NODES[1], {}), '风从江面上吹过来。')
  assert.equal(shell.storyLead({ description: shell.NODE_DESC_FALLBACK, story: '门半开着。\n里面有人。' }, {}), '门半开着。')
  assert.equal(shell.storyLead({ description: '  灯\n还亮着 ' }, {}), '灯 还亮着')
  assert.equal(shell.storyLead({}, { lead: '先看看墙上的字' }), '先看看墙上的字')
  assert.equal(shell.storyLead({ hookText: '谁在敲门？' }, { title: '谁在敲门' }), '', '与题面同句不重复写在上面')
  assert.equal(shell.storyLead({}, {}), '')
  const long = '一'.repeat(60)
  const cut = shell.storyLead({ hookText: long }, {})
  assert.equal(Array.from(cut).length, shell.LEAD_MAX)
  assert.ok(cut.endsWith('…'))
})

test('shellHead:什么都没有给 null(台面原样);有就带读屏整句', () => {
  assert.equal(shell.shellHead({ nodeId: 5 }, {}, [], {}), null)
  const head = shell.shellHead(NODES[1], { id: 9, name: '雾起' }, NODES, { type: 'qa', title: '几点了' })
  assert.deepEqual(head, { station: '第2站 · 外滩', chapter: '雾起', lead: '风从江面上吹过来。', label: '第2站 · 外滩，雾起，风从江面上吹过来。' })
})

test('「继续故事」只在玩完后出现;问答 / 猜词说「继续」;结果屏回故事流说「继续故事」', () => {
  assert.deepEqual(shell.continueSlot({ readyForBase: false, kit: { type: 'estimate' } }, { show: true }), { ready: false, label: '继续故事' })
  assert.deepEqual(shell.continueSlot({ readyForBase: true, kit: { type: 'estimate' } }, { show: true }), { ready: true, label: '继续故事' })
  assert.deepEqual(shell.continueSlot({ readyForBase: true, kit: { type: 'qa' } }, { show: true }), { ready: true, label: '继续' })
  assert.equal(shell.continueSlot({ readyForBase: true, kit: { type: 'qa' } }, { show: false }).ready, false)
  assert.equal(shell.verdictCta(true), '继续故事')
  assert.equal(shell.verdictCta(false), '继续')
})

test('页面挂钩:会话视图刷新时 playKit 带上抬头;内嵌 / 没 kit 时 head 为 null', () => {
  const api = kitCard.methods()
  const page = { data: { advancedPlay: { show: true, node: NODES[1] }, chapter: { id: 9, name: '雾起' }, nodes: NODES } }
  const state = api._withShellHead.call(page, { show: true, kit: { type: 'estimate', title: '多少级台阶' }, readyForBase: false })
  assert.equal(state.head.station, '第2站 · 外滩')
  assert.equal(state.show, true, '原有字段原样保留')
  assert.equal(api._withShellHead.call(page, { show: false, kit: null }).head, null)
  assert.match(read('pages/play/index.js'), /playKit: this\._withShellHead\(kitCard\.playKitState\(/)
  assert.match(read('pages/play/index.wxml'), /<cy-playkit id="playKit"[^>]*shell-head="\{\{playKit\.head\}\}"/)
})

test('回到故事流:淡入走 this.animate 的交叉淡切(200ms ≤ 240ms);减弱动态 / 不在故事流不动', () => {
  const api = kitCard.methods()
  const calls = []
  const page = { data: { chapterFull: true, reducedMotion: false }, animate: (...a) => calls.push(a), clearAnimation() {} }
  api._storyCrossFade.call(page)
  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], '.chfull')
  assert.deepEqual(calls[0][1], [{ opacity: 0 }, { opacity: 1 }])
  assert.ok(calls[0][2] > 0 && calls[0][2] <= 240, '时长走动效 token,≤240ms')
  api._storyCrossFade.call(Object.assign({}, page, { data: { chapterFull: true, reducedMotion: true } }))
  api._storyCrossFade.call(Object.assign({}, page, { data: { chapterFull: false, reducedMotion: false } }))
  assert.equal(calls.length, 1, '减弱动态 = 直接出现;不在故事流不动')
})

test('整屏收回故事流:淡入 + 没玩完时对准这一站那一段;玩完接着走故事', () => {
  const api = kitCard.methods()
  const mk = (ready) => {
    const page = { calls: [], _kitStartFull: '7', data: { chapterFull: true, reducedMotion: true, advancedPlay: { show: true },
      playKit: { show: true, card: false, readyForBase: ready, kit: { type: 'qa', present: 'inline', nodeId: 7 } },
      chapterParas: [{ kind: 'text' }, { kind: 'kit', nodeId: 7 }], chapterGameAnchor: '' } }
    page.setData = (patch, cb) => { Object.keys(patch).forEach((k) => { page.data[k] = patch[k] }); if (cb) cb() }
    page.continueStoryGame = () => page.calls.push('continue')
    Object.keys(api).forEach((k) => { if (!page[k]) page[k] = api[k].bind(page) })
    return page
  }
  const notReady = mk(false)
  assert.equal(notReady._collapseKitCard(), true)
  assert.equal(notReady.data.chapterGameAnchor, 'chapter-kit-7')
  assert.deepEqual(notReady.calls, [])
  const ready = mk(true)
  assert.equal(ready._collapseKitCard(), true)
  assert.deepEqual(ready.calls, ['continue'])
})

test('整屏台面:抬头沿宿主找 cy-playkit 的 shellHead,与 show 同一拍写进 data(第一帧就画出来,不跳)', () => {
  const def = loadComponent('pages/play/components/play-stage/index.js')
  const head = { station: '第2站 · 外滩', chapter: '雾起', lead: '风从江面上吹过来。', label: 'x' }
  const wrapper = { data: { shellHead: head } }
  const kit = { data: { head: '把物品放进框里' }, selectOwnerComponent: () => wrapper }   // 拍物成卡自己的 head 是字符串,不认
  const stage = instance(def, { reducedMotion: true }, { selectOwnerComponent: () => kit })
  assert.equal(def.methods._shellHeadOf(stage), head)
  def.observers.show.call(stage, true)
  assert.equal(stage.data.head, head)
  def.observers.show.call(stage, false)
  assert.equal(stage.data.head, null)
  // 编辑页试玩 / 故事流内嵌:宿主不带 → 台面原样
  const bare = instance(def, {}, { selectOwnerComponent: () => ({ data: {}, selectOwnerComponent: () => ({ data: {} }) }) })
  assert.equal(def.methods._shellHeadOf(bare), null)
  assert.equal(def.methods._shellHeadOf(instance(def, {}, {})), null, '基础库没有 selectOwnerComponent 也不炸')
  const wxml = read('pages/play/components/play-stage/index.wxml')
  assert.match(wxml, /<view class="st__head" wx:if="\{\{head\}\}" style="top:\{\{topPad \+ 12\}\}px"/, '与返回钮同一行,不挪内容起点')
  assert.match(wxml, /<view class="st__tex" aria-hidden="true"><\/view>/)
  const wxss = read('pages/play/components/play-stage/index.wxss')
  assert.match(wxss, /\.st__head \{[^}]*display: var\(--pk-back-display, flex\);/, '内嵌在故事流里与返回钮一起收起')
  assert.match(wxss, /\.st__tex \{[^}]*background: var\(--lk-tex, none\);/, '纸纹 / 暗角读外观变量,没有外观 = 原样')
  assert.match(wxss, /\.st__head-l \{[^}]*font-family: var\(--cy-story-font, 'CyStoryHand'/, '引子用故事流同一个字')
})

test('外观纹理只有一份:故事流 .look-tex 与整屏台面都读 --lk-tex', () => {
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.look--paper \{ --lk-tex: radial-gradient\(/)
  assert.match(wxss, /\.look--paper \.look-tex \{ background: var\(--lk-tex\); mix-blend-mode: var\(--lk-tex-blend\); \}/)
  assert.match(wxss, /\.look--survival \.look-tex \{ background: var\(--lk-tex\); \}/)
  assert.match(wxss, /\.story-font--song \{ --cy-story-font:/)
  assert.doesNotMatch(wxss, /\.look--survival\.look-climate--sand \.look-tex \{ background:/, '气候只换变量,不再单独写一份 .look-tex 背景')
})

test('故事流里的「继续故事」:玩的时候不可见不可点(不是灰掉的死按钮),玩完淡入;只用过渡', () => {
  const wxml = read('pages/play/index.wxml')
  assert.doesNotMatch(wxml, /wx:elif="\{\{playKit\.readyForBase && advancedPlay\.show\}\}"/)
  assert.match(wxml, /class="chfull__continue chfull__continue--morph \{\{playKit\.readyForBase \? 'is-ready' : ''\}\}" wx:if="\{\{advancedPlay\.show && playKit\.kit\}\}" bindtap="continueStoryGame"/)
  assert.match(wxml, /aria-hidden="\{\{!playKit\.readyForBase\}\}"/)
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.chfull__continue\.chfull__continue--morph:not\(\.is-ready\) \{ opacity: 0;[^}]*visibility: hidden; pointer-events: none;/)
  assert.match(wxss, /\.play--reduced-motion \.chfull__continue\.chfull__continue--morph \{ transition: none;/)
  assert.match(wxss, /chfull__continue--morph \{ transition: opacity var\(--cy-motion-standard\)/)
})

test('高级玩法面板:底栏不再是灰掉的「先完成上面的玩法」,玩完才升起继续钮;故事流里说「继续故事」', () => {
  const wxml = read('pages/play/components/advanced-game/index.wxml')
  assert.doesNotMatch(wxml, /先完成上面的玩法/)
  assert.match(wxml, /class="ag__footer \{\{state\.readyForBase \? 'is-ready' : ''\}\}/)
  assert.match(wxml, /\{\{continueLabel \|\| '继续基础任务'\}\}/)
  const wxss = read('pages/play/components/advanced-game/index.wxss')
  assert.match(wxss, /\.ag__footer:not\(\.is-ready\) \{ transform: translateY\(100%\); opacity: 0; pointer-events: none; \}/)
  const def = loadComponent('pages/play/components/advanced-game/index.js')
  assert.equal(def.properties.continueLabel.value, '')
  assert.equal(def.properties.reducedMotion.value, false)
  assert.match(read('pages/play/index.wxml'), /<cy-advanced-game id="advancedGame"[^>]*reduced-motion="\{\{reducedMotion\}\}"/)
  assert.match(read('pages/play/index.wxml'), /<cy-advanced-game id="advancedGame"[^>]*continue-label="\{\{chapterFull \? '继续故事' : ''\}\}"/)
})

test('结果屏:作者反馈用故事字、「X会记住这件事」留着,点一下先淡出(≤240ms)再回故事;减弱动态直接收', async () => {
  const def = loadComponent('pages/play/components/play-verdict/index.js')
  assert.equal(def.properties.remember.value, '')
  assert.equal(def.properties.ctaLabel.value, '')
  const v = instance(def, { show: true, reducedMotion: false })
  v.onClose()
  v.onClose()   // 连点只抛一次
  assert.equal(v.data.leaving, true)
  assert.deepEqual(v.events, [])
  await new Promise((r) => setTimeout(r, 260))
  assert.deepEqual(v.events.map((e) => e.name), ['close'])
  const still = instance(def, { show: true, reducedMotion: true })
  still.onClose()
  assert.deepEqual(still.events.map((e) => e.name), ['close'])
  const wxml = read('pages/play/components/play-verdict/index.wxml')
  assert.match(wxml, /<view class="pv__remember" wx:if="\{\{remember\}\}">\{\{remember\}\}<\/view>/)
  assert.match(wxml, /aria-label="\{\{ctaLabel \|\| '继续'\}\}"/)
  const wxss = read('pages/play/components/play-verdict/index.wxss')
  assert.match(wxss, /\.pv__text \{[^}]*font-family: var\(--cy-story-font, 'CyStoryHand'/)
  assert.match(wxss, /\.pv--closable \.pv__fill \{ background: var\(--pk-embed-bg, var\(--cy-color-bg-page\)\); \}/, '跟外观,没外观回落原暗底')
  assert.match(wxss, /\.pv \{ transition: opacity var\(--cy-motion-fade-swap\)/)
  const page = read('pages/play/index.wxml')
  assert.match(page, /<cy-play-verdict class="pk-lookhost \{\{lookClass\}\}"[^>]*remember="\{\{r5Verdict\.remember\}\}" cta-label="\{\{r5Verdict\.cta\}\}"/)
})

test('作者结果反馈带上角色信任那一句与「继续故事」', () => {
  const r5 = require('../../pages/play/utils/r5-player-runtime.js')
  const api = r5.methods()
  const page = { data: { chapterFull: true }, _castRemember: (ids) => (ids && ids.length ? '阿梅会记住这件事' : '') }
  assert.deepEqual(api._r5VerdictVoice.call(page, { trustChanged: ['c1'] }), { remember: '阿梅会记住这件事', cta: '继续故事' })
  assert.deepEqual(api._r5VerdictVoice.call({ data: { chapterFull: false } }, {}), { remember: '', cta: '继续' })
  assert.deepEqual(r5.data.r5Verdict, { show: false, kind: 'win', text: '', images: [], remember: '', cta: '' })
})

test('玩法开始弹窗:抬头写「第N站 · 站名」,说明与整屏台面的引子同一份', () => {
  const pops = require('../../pages/play/utils/play-stage-pops.js')
  const api = pops.methods()
  const page = { data: { nodes: NODES, chapter: { id: 9, name: '雾起' }, chapterParas: [{ kind: 'game', nodeId: 2 }], playKit: {} },
    _isStoryGame: () => true }
  page.setData = (patch) => Object.assign(page.data, patch)
  api.openKitStart.call(page, { currentTarget: { dataset: { origin: 'story', nodeid: 2 } } })
  assert.equal(page.data.kitStart.station, '第2站 · 外滩')
  assert.equal(page.data.kitStart.lede, '风从江面上吹过来。')
  assert.match(read('pages/play/play-stage.wxml'), /<text class="ks__t lk-title">\{\{kitStart\.station \|\| '玩法'\}\}<\/text>/)
})
