// l-play(2026-11-10 launch 波次):主题内游玩 UI 按业主批准的 proto/game 第二组施工 —— 章节门(开场 / 完成)、城市定向地点卡、玩法开始弹窗、
// 本局完成屏、岔路弹窗、延伸任务翻页卡、背包插图、入场码翻面、顶栏进度药丸。这里钉住数据合同与页面结构,画面由 l-play/shots 截图证明。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const kitCard = require('../../pages/play/utils/story-kit-card.js')
const pops = require('../../pages/play/utils/play-stage-pops.js')
const { buildGateFlip } = require('../../pages/play/utils/chapter-flip-data.js')
const art = require('../../pages/play/components/cy-illus/art.js')

const cards = [{ id: 1, title: '邮局旧址', cover: 'https://x/a.jpg,https://x/a2.jpg' }, { id: 2, title: '老书店', cover: 'https://x/b.jpg', nodeCount: 2 }]
const nodes = [{ nodeId: 11, chapterId: 1, done: true, puzzleScore: 80 }, { nodeId: 21, chapterId: 2, done: false, name: '老书店' }]

test('章节门数据:开场屏带方块章节号,完成屏带本章照片(第一张);序章没有数字', () => {
  const g = buildGateFlip({ cards, chapterId: 2, nodes, elapsed: '12:30' })
  assert.equal(g.info.number, '02')
  assert.equal(g.endInfo.number, '01')
  assert.equal(g.endInfo.cover, 'https://x/a.jpg', '完成屏顶部照片 = 上一章封面的第一张')
  assert.deepEqual(g.endInfo.rows.map((r) => r.k), ['用时', '本章得分', '坐标'])
  const first = buildGateFlip({ cards, chapterId: 1, nodes })
  assert.equal(first.info.number, '01')
  assert.equal(first.endInfo, null)
  const unknown = buildGateFlip({ cards, chapterId: 99, nodes })
  assert.equal(unknown.info.number, '', '找不到的章 / 序章不出数字,不拿 01 凑')
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
    data: Object.assign({}, def.data, { mode: 'gate', faces: [], endInfo: null, info: { number: '01', title: '钟楼背后' }, reducedMotion: false }, props),
    events: [], sets: [],
    setData(patch) { Object.assign(this.data, patch); this.sets.push(patch) },
    triggerEvent(name) { this.events.push(name) },
  }
  Object.keys(def.methods).forEach((k) => { inst[k] = def.methods[k].bind(inst) })
  inst._at = (fn) => { timers.push(fn) }
  inst._decode = () => {}
  inst._scatter = () => { inst.scattered = (inst.scattered || 0) + 1 }
  inst._assemble = (o) => { inst.assembled = (inst.assembled || []).concat(o.text) }
  inst._spawnFineDust = () => { inst.dusted = (inst.dusted || 0) + 1 }
  inst._ctx = {}
  inst._S = 1
  inst._timers = []
  inst.flush = () => { while (timers.length) timers.shift()() }
  return inst
}

test('章节开场:星点锁成方块章节号 → 文字解码 → 「读下去」亮起;点它方块炸散并交回正文;D-46 不再自己进', () => {
  const def = loadFlip()
  const c = flipInstance(def)
  c._gate()
  assert.deepEqual(c.assembled, ['01'], '开场屏拼的是这一章的数字')
  assert.equal(c.data.start, true)
  c.flush()
  assert.equal(c.data.cta, true, '「读下去」在解码后亮起')
  assert.ok(!c.scattered, 'D-46(业主「点了才能进」):不点不自己进,也没有自动离场')
  assert.deepEqual(c.events, [], 'D-46:不再有 3.6s 自动进正文')
  const tap = flipInstance(def)
  tap._gate(); tap.onTap(); tap.flush()
  assert.deepEqual(tap.events, ['continue'], '点一下提前进')
  const opening = flipInstance(def, { info: { number: '', title: '序章' } })
  opening._gate()
  assert.equal(opening.assembled, undefined, '序章没有方块数字')
})

test('章节完成:整屏(照片 + 方块号 + 三格真实数据 + 下一站);点下一站 → 吹散 → 下一章开场;减弱动态直接切、数据直接写', () => {
  const def = loadFlip()
  const endInfo = { number: '01', kicker: '第 1 章完成', title: '邮局旧址', cover: 'https://x/a.jpg', rows: [{ k: '用时', v: '12:30' }, { k: '本章得分', v: '80' }, { k: '坐标', v: '1/2' }], next: '老书店' }
  const realTimeout = global.setTimeout
  const held = []
  global.setTimeout = (fn, ms) => { held.push({ fn, ms }); return held.length }
  try {
    const c = flipInstance(def, { endInfo, info: { number: '02', title: '老书店' } })
    c._gate()
    assert.equal(c.data.endCard.cover, 'https://x/a.jpg')
    assert.equal(c.data.endCard.next, '老书店')
    assert.deepEqual(c.data.endStats.map((s) => s.shown), ['12:30', '80', '1/2'], '首帧就显示真实数据，不能伪报零值')
    assert.deepEqual(c.assembled, ['01'])
    assert.ok(!held.some((h) => h.ms === 5200), 'D-46:完成屏不再有 5.2 秒自动吹散')
    c.onTap()            // 点「下一站」:吹散
    assert.equal(c._stage, 'dissolve')
    assert.equal(c.data.endOut, true)
    assert.equal(c.scattered, 1)
    c.flush()
    assert.equal(c.data.endCard, null)
    assert.equal(c._stage, 'start')
    assert.deepEqual(c.assembled, ['01', '02'], '下一章的数字再锁上来')
    const r = flipInstance(def, { endInfo, info: { number: '02', title: '老书店' }, reducedMotion: true })
    r._gate()
    assert.deepEqual(r.data.endStats.map((s) => s.shown), ['12:30', '80', '1/2'], '减弱动态:数据直接写终值')
    r.onTap()
    assert.equal(r._stage, 'start', '减弱动态:不吹散,直接到下一章开场')
  } finally { global.setTimeout = realTimeout }
})

test('过场组件 WXML / WXSS:完成整屏、读下去、插图;章节门跟 --lk-*,没有新增 keyframes', () => {
  const w = read('pages/play/components/chapter-flip/index.wxml')
  assert.match(w, /<view class="cf__end [^"]*" wx:if="\{\{endCard\}\}">/)
  assert.match(w, /<image wx:if="\{\{endCard\.cover\}\}" class="cf__cover"/)
  assert.match(w, /<cy-illus name="success" size="240"/)
  assert.match(w, /class="cf__st" wx:for="\{\{endStats\}\}"/)
  assert.match(w, /aria-label="读下去"/)
  assert.doesNotMatch(w, /cf__badge|cf__tick|cf__rows/, 'r2-finish 的卡片结构已退场')
  const css = read('pages/play/components/chapter-flip/index.wxss')
  assert.match(css, /\.cf--gate \{ background: var\(--lk-bg, #1F1F21\); color: var\(--lk-ink, #F5F5F2\); \}/)
  assert.equal((css.match(/@keyframes/g) || []).length, 0)
  assert.deepEqual(JSON.parse(read('pages/play/components/chapter-flip/index.json')).usingComponents, { 'cy-illus': '/pages/play/components/cy-illus/index' })
  assert.equal(JSON.parse(read('pages/play/index.json')).usingComponents['cy-illus'], '/pages/play/components/cy-illus/index')
})

test('cy-illus 修:物件层 SVG 不再有重复属性(XML 不合法 → 图片整张不出),按 HTML 取法保留第一个', () => {
  for (const name of art.LIST) {
    const fg = decodeURIComponent(art.layers(name, 3).fg.split(',').slice(1).join(','))
    for (const m of fg.matchAll(/<([a-zA-Z][\w-]*)((?:\s+[\w:-]+="[^"]*")*)\s*\/?>/g)) {
      const names = [...m[2].matchAll(/\s+([\w:-]+)=/g)].map((x) => x[1])
      assert.equal(new Set(names).size, names.length, name + ' 的 <' + m[1] + '> 有重复属性')
    }
  }
  // l-iconsB:钥匙换成风格 B v2,宝石描边原型写成 LN + ' stroke-width="3.5"',HTML 取第一个 4.5
  const key = decodeURIComponent(art.layers('key').fg)
  assert.match(key, /<path d="M40 26l12 12-12 16-12-16z" fill="none" stroke="#121213" stroke-width="4.5" stroke-linejoin="round" stroke-linecap="round" ?\/>/, '重复的 stroke-width 取第一个(与原型 HTML 渲染一致)')
})

test('地点卡数字只写拿得到的;玩法类型名认得出才写;翻页循环', () => {
  assert.deepEqual(pops.sheetFacts({ businessTime: '09:00-17:00' }, '1.2km'), { distText: '1.2km', walkText: '15', hoursText: '09–17' })
  assert.deepEqual(pops.sheetFacts({ businessTime: '00:00-23:59' }, ''), { distText: '', walkText: '', hoursText: '' }, '全天不是有用信息;没有距离就没有步行')
  assert.deepEqual(pops.sheetFacts({}, '80m'), { distText: '80m', walkText: '1', hoursText: '' })
  assert.equal(pops.kitTypeLabel({ previewKit: { type: 'accusation' } }), '判案')
  assert.equal(pops.kitTypeLabel({ advancedConfigJson: JSON.stringify({ qa: { enabled: true }, timer: { enabled: true } }) }), '问答')
  assert.equal(pops.kitTypeLabel({ advancedConfig: { ballShake: { enabled: true } } }), '弹球')
  assert.equal(pops.kitTypeLabel({ advancedConfigJson: '{"mystery":{"enabled":true}}' }), '', '认不出就不写,不拿「玩法」凑')
  assert.equal(pops.nextIndex(2, 3), 0)
  assert.equal(pops.nextIndex(0, 0), 0)
})

function host(extra) {
  const h = Object.assign({
    data: Object.assign({}, pops.data, kitCard.data, { nodes: [], chapterParas: [], sheet: { node: null }, playKit: { show: false, kit: null }, advancedPlay: { show: false, node: null }, reducedMotion: false, chapterFull: true }),
    calls: [],
    setData(patch) {
      Object.keys(patch).forEach((k) => {
        if (k.indexOf('.') < 0) { this.data[k] = patch[k]; return }
        const [a, b] = k.split('.'); this.data[a] = Object.assign({}, this.data[a], { [b]: patch[k] })
      })
    },
    startStoryGame(e) { this.calls.push(['startStoryGame', e.currentTarget.dataset.nodeid]) },
    startGame() { this.calls.push(['startGame']) },
    expandPlayKit() { this.calls.push(['expandPlayKit']) },
    continueStoryGame() { this.calls.push(['continueStoryGame']) },
    openStoryTask(e) { this.calls.push(['openStoryTask', e.currentTarget.dataset.nodeid]) },
    _isStoryGame() { return true },
    _distText() { return '120m' },
  })
  Object.assign(h, pops.methods(), kitCard.methods())
  // 桩在真方法之后盖上:expandPlayKit / openStoryTask 这里只记调用
  Object.assign(h, { expandPlayKit() { this.calls.push(['expandPlayKit']) }, openStoryTask(e) { this.calls.push(['openStoryTask', e.currentTarget.dataset.nodeid]) } }, extra || {})
  return h
}

test('玩法开始弹窗:故事流入口先看弹窗(模板 / 地点 / 一句话 / 嵌入或全屏),「全屏」= 同一个 kit 盖整屏,退出回到流里;地点卡 / 入口卡进来只整屏', () => {
  const h = host()
  const game = { nodeId: 7, name: '谁在钟楼留下了钥匙', imgUrl: 'https://x/k.jpg,https://x/k2.jpg', story: '三个人那晚都在钟楼附近。听完证词,指认一个人。', previewKit: { type: 'accusation' }, placeNodeId: 2 }
  h.data.nodes = [game, { nodeId: 2, name: '邮政大楼', arrived: true }]
  h.data.chapterParas = [{ kind: 'game', nodeId: 7 }]
  h.openKitStart({ currentTarget: { dataset: { nodeid: 7, origin: 'story' } } })
  const ks = h.data.kitStart
  assert.equal(ks.show, true)
  assert.equal(ks.cover, 'https://x/k.jpg')
  assert.equal(ks.type, '判案')
  assert.equal(ks.place, '邮政大楼')
  assert.equal(ks.arrived, true)
  assert.equal(ks.lede, '三个人那晚都在钟楼附近。')
  assert.equal(ks.modes, true)
  h.pickKitMode({ currentTarget: { dataset: { mode: 'full' } } })
  h.confirmKitStart()
  assert.equal(h.data.kitStart.show, false)
  assert.equal(h._kitStartFull, '7')
  assert.deepEqual(h.calls, [['startStoryGame', '7']])
  // kit 到了:选了全屏 → 同一个内嵌 kit 以 show 盖整屏;会话视图再刷新保持;退出只收回到流里
  const full = kitCard.playKitState({ show: false, kit: null }, { type: 'accusation', present: 'inline' }, true, false, true)
  assert.equal(full.show, true); assert.equal(full.card, false)
  const again = kitCard.playKitState(full, { type: 'accusation', present: 'inline' }, true, true, true)
  assert.equal(again.show, true)
  h.data.playKit = again; h.data.advancedPlay = { show: true, node: game }
  assert.equal(h._collapseKitCard(), true)
  assert.equal(h.data.playKit.show, false)
  assert.equal(h._kitStartFull, '')
  assert.deepEqual(h.calls.at(-1), ['continueStoryGame'])
  const plain = kitCard.playKitState({ show: false, kit: null }, { type: 'accusation', present: 'inline' }, true, false, false)
  assert.equal(plain.show, false, '没选全屏照旧真内嵌')
  // 不是故事玩法(或已完成):不弹,直接走老路
  const old = host({ _isStoryGame() { return false } })
  old.data.nodes = [game]; old.data.chapterParas = [{ kind: 'game', nodeId: 7 }]
  old.openKitStart({ currentTarget: { dataset: { nodeid: 7, origin: 'story' } } })
  assert.deepEqual(old.calls, [['startStoryGame', 7]])
  assert.equal(old.data.kitStart.show, false)
  // 地点卡:只有整屏一种,「开始」照旧 startGame
  const sh = host(); sh.data.sheet = { node: { nodeId: 9, name: '邮政大楼', lat: 31, gameTitle: '谁在钟楼留下了钥匙', advancedConfigJson: '{"qa":{"enabled":true}}' } }
  sh.openKitStart({ currentTarget: { dataset: { origin: 'sheet' } } })
  assert.equal(sh.data.kitStart.modes, false); assert.equal(sh.data.kitStart.type, '问答'); assert.equal(sh.data.kitStart.place, '邮政大楼')
  sh.confirmKitStart(); assert.deepEqual(sh.calls, [['startGame']])
  // 入口卡(要整屏的玩法):弹窗 → 整屏;分支入口卡直接进岔路弹窗
  const cd = host(); cd.data.playKit = { show: false, card: true, pop: false, cardLabel: '弹球', kit: { type: 'ballshake', title: '撞够 30 次' } }; cd.data.advancedPlay = { show: true, node: { nodeId: 3, name: '弄堂口' } }
  cd.openKitStart({ currentTarget: { dataset: { origin: 'card' } } })
  assert.equal(cd.data.kitStart.type, '弹球'); assert.equal(cd.data.kitStart.modes, false)
  cd.confirmKitStart(); assert.deepEqual(cd.calls, [['expandPlayKit']])
  const br = host(); br.data.playKit = { show: false, card: true, pop: true, kit: { type: 'branch' } }; br.data.advancedPlay = { show: true, node: { nodeId: 4 } }
  br.openKitStart({ currentTarget: { dataset: { origin: 'card' } } })
  assert.deepEqual(br.calls, [['expandPlayKit']]); assert.equal(br.data.kitStart.show, false)
})

test('岔路:分支剧情在流里是入口卡(钥匙插图),点开是居中弹窗(playKit.pop),不盖整屏', () => {
  const s = kitCard.playKitState({}, { type: 'branch', present: 'inline' }, true, false)
  assert.equal(s.card, true); assert.equal(s.pop, true); assert.equal(s.cardLabel, '分支剧情'); assert.equal(s.cardIllus, 'key')
  assert.equal(kitCard.playKitState({}, { type: 'branch' }, false, false).pop, false, '整屏来的分支不走弹窗')
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /\(playKit\.show && !playKit\.pop\)/, '故事流只在非弹窗整屏时隐去')
  assert.match(wxml, /<cy-playkit id="playKit" class="pk-lookhost \{\{lookClass\}\}" kit="\{\{playKit\.kit\}\}" show="\{\{playKit\.show && !playKit\.pop\}\}"/)
  assert.match(wxml, /class="chfull__kitcard" data-origin="card" bindtap="openKitStart"/)
  assert.match(wxml, /<cy-illus name="\{\{playKit\.cardIllus \|\| 'clue'\}\}" size="110"/)
  const stage = read('pages/play/play-stage.wxml')
  assert.match(stage, /<view class="lk-popwrap lk-popwrap--branch" wx:if="\{\{playKit\.show && playKit\.pop\}\}"/)
  assert.match(stage, /<view class="bp__b chfull__kit"><cy-playkit kit="\{\{playKit\.kit\}\}" show="\{\{true\}\}" inline="\{\{true\}\}" bind:kitaction="onPlayKitAction" \/>/)
  assert.match(stage, /<view class="lk-popwrap" wx:if="\{\{kitStart\.show\}\}"/)
  assert.match(stage, /data-mode="embed" bindtap="pickKitMode"[\s\S]*data-mode="full" bindtap="pickKitMode"/)
  assert.match(stage, /bindtap="confirmKitStart"/)
})

test('延伸任务翻页卡:一次一张,点卡 / 箭头翻到下一张(减弱动态直接切),「去做」打开那一站;打开时从第一张起', () => {
  const h = host()
  h.data.storyTasks = { show: false, rows: [{ nodeId: 101, name: 'a' }, { nodeId: 102, name: 'b' }, { nodeId: 103, name: 'c' }] }
  h.openStoryTasks()
  assert.deepEqual(h.data.extDeck, { idx: 0, flipping: false })
  const realTimeout = global.setTimeout
  let pending = null
  global.setTimeout = (fn) => { pending = fn; return 1 }
  try {
    h.flipExtTask()
    assert.deepEqual(h.data.extDeck, { idx: 0, next: 1, flipping: true })
    h.flipExtTask()
    assert.equal(h.data.extDeck.idx, 0, '翻到一半不叠第二次')
    pending()
    assert.deepEqual(h.data.extDeck, { idx: 1, flipping: false })
    h.goExtTask()
    assert.deepEqual(h.calls.at(-1), ['openStoryTask', 102])
    h.data.reducedMotion = true
    h.flipExtTask(); h.flipExtTask()
    assert.deepEqual(h.data.extDeck, { idx: 0, flipping: false }, '减弱动态:直接切,循环回第一张')
  } finally { global.setTimeout = realTimeout }
  const one = host(); one.data.storyTasks = { show: true, rows: [{ nodeId: 1 }] }; one.flipExtTask()
  assert.deepEqual(one.data.extDeck, pops.data.extDeck, '只有一张不翻')
  const top = read('pages/play/play-topbar.wxml')
  assert.match(top, /class="xd__c lk-card stq__card [^"]*" wx:for="\{\{storyTasks\.rows\}\}" wx:key="nodeId" bindtap="flipExtTask"/)
  assert.match(top, /catchtap="goExtTask"/)
  assert.match(top, /class="xd__arrow" bindtap="flipExtTask"/)
})

test('本局完成屏:整屏泛绿 + 勋章 / 成功插图;「获得勋章」只在真夺到勋章时写', () => {
  const h = host(); h._wonMedals = [{ nodeId: 1, name: '邮差的钥匙', img: '', rank: 1 }, { nodeId: 2, name: '第二枚' }]
  assert.deepEqual(h._endingReward(), { reward: { name: '邮差的钥匙', img: '', count: 2 } })
  assert.deepEqual(host()._endingReward(), { reward: null }, '没夺到就不写')
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /\{\{previewEnding\.real \? 'pv-ending--run' : ''\}\}/)
  // 2026-11-11 l-icons:奖励时刻的插图换成 3D 玻璃序列帧(cy-frames),勋章 / 成功仍按 previewEnding.reward 二选一
  assert.match(wxml, /<cy-frames sheets="\{\{\[\{ src: previewEnding\.reward \? '\/pages\/play\/images\/frames\/glass-badge\.webp' : '\/pages\/play\/images\/frames\/glass-success\.webp', n: 24 \}\]\}\}"[^>]*w="\{\{340\}\}"/)
  assert.match(wxml, /previewEnding\.reward \? '本局完成 · 获得勋章' : \(previewEnding\.archive\.identity\.length \? '这一生的结局' : '本局完成'\)/)
  assert.doesNotMatch(wxml, /pv-ending__seal|pv-ending__tick/)
  const js = read('pages/play/index.js')
  assert.match(js, /Object\.assign\(ending, ending\.real \? this\._endingReward\(\) : \{\}, \{ in: reduced, off: '' \}\)/)
  const css = read('pages/play/index.wxss')
  assert.doesNotMatch(css, /@keyframes pv-ending-ring/, 'r2-finish 的光环 keyframes 已删(改过渡)')
  const stage = read('pages/play/play-stage.wxss')
  assert.match(stage, /\.pv-ending--run \{ background: linear-gradient\(180deg, rgba\(var\(--lk-ok-rgb, 34,197,94\), \.22\)/)
  assert.doesNotMatch(stage, /@keyframes|animation\s*:/, '阶段弹窗 / 整屏全部是过渡')
  assert.match(css, /^@import "\.\/look-tokens\.wxss";\n@import "\.\/play-stage\.wxss";/m, 'play-stage 在故事文字动效段之前引入')
})

test('城市定向地点卡:整屏照片 + 玻璃面板(lk-pop),返回在左上,没有分享;这一站的玩法是一张横卡;底部 导航 + 主动作一排;数字只写拿得到的', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="sheet-x" wx:if="\{\{mode!=2\}\}"[^>]*bindtap="closeSheet"[^>]*><cy-icon name="back" size="32" \/>/)
  assert.match(wxml, /<view class="sheet-body \{\{mode!=2 \? 'lk-pop sheet-glass' : ''\}\}">/)
  assert.match(wxml, /<view class="sheet-dist" wx:if="\{\{mode!=2 && \(sheet\.node\.distText \|\| sheet\.node\.hoursText\)\}\}">/)
  assert.match(wxml, /<view class="sheet-kit lk-card" data-origin="sheet" bindtap="openKitStart"/)
  assert.match(wxml, /<view class="lk-btn lk-btn--primary" data-origin="sheet" bindtap="openKitStart"[^>]*>开始任务<\/view>/)
  assert.match(wxml, /<view class="lk-btn lk-btn--ghost" wx:if="\{\{mode!=2 && sheet\.node\.lat && sheet\.node\.lng\}\}" bindtap="sheetNav"/)
  assert.doesNotMatch(wxml.slice(wxml.indexOf('C. 节点详情卡'), wxml.indexOf('任务页(p03 重做')), /share|分享/, '地点卡没有分享')
  assert.match(read('pages/play/index.js'), /\}, this\._sheetFacts\(node\)\);/)
  assert.match(wxml, /class="chfull__game-cue" data-nodeid="\{\{item\.nodeId\}\}" data-origin="story" bindtap="openKitStart"/)
  assert.match(wxml, /<view class="theme-gpill lk-num" wx:if="\{\{total > 0\}\}"/)
})

test('背包插图与入场码适中码块', () => {
  const bag = read('pages/play/theme-backpack.wxml')
  assert.match(bag, /<view wx:else class="tb-item__illus"><cy-illus name="\{\{item\.illus \|\| 'gem'\}\}" size="150"/)
  assert.match(bag, /<view class="tb-clue__illus"><cy-illus name="clue"/)
  assert.match(bag, /<view class="tb-avatar tb-avatar--illus"><cy-illus name="points"/)
  const bagJs = read('pages/play/utils/theme-backpack.js')
  assert.match(bagJs, /illus: illusFor\(i\.name \|\| gameplay\.valueName\(i\.id, view\)\)/)
  const qr = read('pages/play/components/scene-qr-ticket/index.wxml')
  // D1(2026-11-12):入场码改回普通半屏里一个适中的码,不再是翻面大票卡
  assert.doesNotMatch(qr, /voucher__flip|voucher__rear|toggleFlip/)
  assert.match(qr, /size="\{\{360\}\}"/)
  const qjs = read('pages/play/components/scene-qr-ticket/index.js')
  assert.doesNotMatch(qjs, /autoFlip|FLIP_AFTER_MS|flipped/)
  assert.match(read('pages/play/components/scene-qr-ticket/index.wxss'), /\.voucher__qr \{[^}]*width: 400rpx; height: 400rpx;/)
})
