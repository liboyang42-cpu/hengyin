'use strict'
// 2026-11-09 qx-a1(业主 2026-11-06 手机走查 V3-06/10/53/11/13/14/15/16/17/21):游玩页逐项修复的契约。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const kitCard = require('../../pages/play/utils/story-kit-card.js')

function host(data) {
  const calls = []
  const page = Object.assign({ data: Object.assign({ chapterFull: true, playKit: {}, advancedPlay: { show: true }, storyTasks: { show: false, rows: [] }, nodes: [], chapter: { id: 1 } }, data) }, kitCard.methods(), {
    setData(patch) {
      Object.keys(patch).forEach((k) => {
        const parts = k.split('.')
        let o = this.data
        while (parts.length > 1) { const p = parts.shift(); o[p] = Object.assign({}, o[p]); o = o[p] }
        o[parts[0]] = patch[k]
      })
    },
    continueStoryGame() { calls.push('continue') },
    openSheet(node) { calls.push('sheet:' + node.nodeId) },
  })
  return { page, calls }
}

test('V3-10/53/11:要整屏的玩法内嵌时只放入口卡,其余玩法真内嵌;整屏 kit 不变', () => {
  const coin = kitCard.playKitState({}, { type: 'coinflip' }, true, false)
  // r2-play a1-v3-10-53:入口卡多带类型名与图标(非卡时为空串)
  assert.deepEqual(coin, { show: false, kit: { type: 'coinflip' }, readyForBase: false, card: false, cardLabel: '', cardIcon: '' })
  for (const type of ['album', 'ballshake', 'reaction', 'countdown']) {
    const s = kitCard.playKitState({}, { type }, true, false)
    assert.equal(s.card, true, type + ' 内嵌时应是入口卡')
    assert.equal(s.show, false, type + ' 入口卡先不弹整屏')
  }
  // 已经从卡点开的整屏:会话视图刷新不把它收回
  assert.equal(kitCard.playKitState({ card: true, show: true }, { type: 'album' }, true, true).show, true)
  // 作者配了整屏:照旧整屏,不是卡
  assert.deepEqual(kitCard.playKitState({}, { type: 'album' }, false, false), { show: true, kit: { type: 'album' }, readyForBase: false, card: false, cardLabel: '', cardIcon: '' })
})

test('V3-10/53/11:入口卡点开整屏;关整屏只收回到卡,能继续就接着走故事', () => {
  const { page, calls } = host({ playKit: { card: true, show: false, kit: { type: 'album' }, readyForBase: false } })
  page.expandPlayKit()
  assert.equal(page.data.playKit.show, true)
  assert.equal(page._collapseKitCard(), true)
  assert.equal(page.data.playKit.show, false)
  assert.deepEqual(calls, [])
  page.data.playKit = { card: true, show: true, kit: { type: 'album' }, readyForBase: true }
  assert.equal(page._collapseKitCard(), true)
  assert.deepEqual(calls, ['continue'])
  // 不是入口卡:交回原 closePlayKit 流程
  page.data.playKit = { card: false, show: true, kit: { type: 'qa' } }
  assert.equal(page._collapseKitCard(), false)
})

test('V3-15:延伸任务收进一行入口 + 半屏,点一行打开那一站;正文不再塞「这一章还没有写下的内容」', () => {
  const nodes = [
    { nodeId: 1, chapterId: 1, num: 1, name: '主线站', done: false },
    { nodeId: 101, chapterId: 1, num: 101, name: '旧书摊', done: false },
    { nodeId: 102, chapterId: 1, num: 102, name: '修表铺', done: true },
    { nodeId: 201, chapterId: 2, num: 101, name: '别章延伸', done: false },
  ]
  const { page, calls } = host({ nodes })
  const items = []
  page._pushStoryTasks(items, nodes, [{ type: 'node', nodeId: 1 }], false, '')
  assert.deepEqual(items, [{ kind: 'tasks', count: 1, mood: '' }])
  // r2-play a1-v3-15:任务卡多带序号、站点配图与地址(服务端节点字段,没有就是空串)
  assert.deepEqual(page.data.storyTasks.rows, [{ nodeId: 101, name: '旧书摊', no: 1, img: '', address: '' }])
  page.openStoryTasks()
  assert.equal(page.data.storyTasks.show, true)
  page.openStoryTask({ currentTarget: { dataset: { nodeid: 101 } } })
  assert.equal(page.data.storyTasks.show, false)
  assert.deepEqual(calls, ['sheet:101'])
  // 有块流、只有一个主线未完成站:不出入口(「继续探索」照旧)
  const none = []
  page._pushStoryTasks(none, nodes.slice(0, 1), [{ type: 'node', nodeId: 1 }], false, '')
  assert.deepEqual(none, [])
  // 回看(只读)不出
  const ro = []
  page._pushStoryTasks(ro, nodes, null, true, '')
  assert.deepEqual(ro, [])
  const js = read('pages/play/index.js')
  assert.doesNotMatch(js, /这一章还没有写下的内容/)
  assert.match(js, /this\._pushStoryTasks\(items, nodes, blocks, readOnly, curMood\);/)
  assert.match(read('pages/play/index.wxml'), /item\.kind === 'tasks'[^>]*bindtap="openStoryTasks"/)
  assert.match(read('pages/play/play-topbar.wxml'), /show="\{\{storyTasks\.show\}\}"[\s\S]*bindtap="openStoryTask"/)
})

test('V3-10/53:故事流内嵌块不再撑满一屏、不再垫台面底色;滚动对准块内锚点', () => {
  const wxss = read('pages/play/index.wxss')
  const layout = wxss.match(/\.chfull__kit\{ position:relative;[^}]*\}/)
  assert.ok(layout, '内嵌块的版式规则还在')
  assert.doesNotMatch(layout[0], /100vh|justify-content:center/)
  const vars = wxss.match(/\.chfull__kit\{ width:100%;[^}]*\}/)[0]
  for (const need of ['--pk-card-h:auto', '--pk-card-h-tall:auto', '--pk-card-bg:transparent']) assert.ok(vars.includes(need), need)
  for (const kit of ['coinflip', 'shout', 'walk']) {
    assert.match(read('pages/play/components/playkit-' + kit + '/index.wxss'), /background: var\(--pk-card-bg, /, kit + ' 内嵌应透明')
  }
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="chfull__kit-anchor" id="\{\{'chapter-kit-' \+ item\.nodeId\}\}"/)
  // r2-play a1-v3-10-53:入口卡换成封面 + 类型 + 名字的横卡
  assert.match(wxml, /wx:if="\{\{playKit\.card\}\}" class="chfull__kitcard" bindtap="expandPlayKit"/)
  assert.match(read('pages/play/index.js'), /const anchor = 'chapter-kit-' \+ this\._playKitNodeId;/)
})

test('V3-06:城市定向地点卡底部不再有「在地图中查看路线」;自由探索保留导航', () => {
  const wxml = read('pages/play/index.wxml')
  assert.doesNotMatch(wxml.replace(/<!--[\s\S]*?-->/g, ''), /在地图中查看路线/)
  assert.match(wxml, /class="sheet-nav" wx:if="\{\{mode==2 && !sheet\.node\.done && sheet\.node\.lat[^"]*\}\}" bindtap="sheetNav"/)
})

test('V3-13/14:雷达收起箭头展开时朝上;雷达用漫游同一套深色底图', () => {
  assert.match(read('pages/play/play-topbar.wxss'), /\.radar__fold\{[^}]*transform:rotate\(90deg\)/)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="fx-story__radar-map"[\s\S]*?subkey="\{\{radarMap\.subkey\}\}" layer-style="\{\{radarMap\.layerStyle\}\}"/)
  assert.match(read('pages/play/index.js'), /radarMap: \{ subkey: MAP_DARK\.subkey, layerStyle: MAP_DARK\.layerStyle \}/)
})

test('V3-16:背包装载一格一块;没有物品时下面是同一张底的空态', () => {
  const wxml = read('pages/play/theme-backpack.wxml')
  assert.match(wxml, /class="tb-slots" wx:if="\{\{themeBag\.capacity > 0 && themeBag\.capacity <= 24\}\}"/)
  assert.match(wxml, /tbAttr\.slots\(themeBag\.used, themeBag\.capacity\)/)
  // r2-play a1-v3-16:随身物品一行两格方块,空格是灰方块(至少两行),不再是图标 + 文案的空态卡
  assert.match(wxml, /class="tb-items"[\s\S]*?wx:for="\{\{tbAttr\.pad\(themeBag\.items\.length\)\}\}"/)
  const wxs = read('pages/play/index.wxml').match(/<wxs module="tbAttr">([\s\S]*?)<\/wxs>/)[1]
  const mod = {}
  new Function('module', wxs)(mod)
  assert.deepEqual(mod.exports.slots(2, 6), [true, true, false, false, false, false])
  assert.equal(mod.exports.slots(0, 40).length, 24)
  assert.equal(mod.exports.pad(0).length, 4)
  assert.equal(mod.exports.pad(3).length, 1)
  assert.equal(mod.exports.pad(5).length, 1)
  assert.equal(mod.exports.pad(6).length, 0)
})

test('V3-17:入场码半屏不再有左下返回钮(关闭走右上 ×)', () => {
  const xml = read('pages/play/components/scene-qr-ticket/index.wxml').replace(/<!--[\s\S]*?-->/g, '')
  assert.doesNotMatch(xml, /voucher__back|voucher__acts/)
  assert.doesNotMatch(read('pages/play/components/scene-qr-ticket/index.json'), /cy-icon/)
})

test('V3-21:地点卡封面与物品图不再垫灰底', () => {
  assert.match(read('pages/play/index.wxss'), /\.sheet-cover\{ position:relative; height:364rpx; background:transparent;/)
  assert.match(read('pages/play/theme-backpack.wxss'), /image\.tb-avatar\{background:transparent\}/)
})
