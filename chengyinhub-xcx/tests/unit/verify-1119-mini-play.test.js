const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('CG-ALL-26 站点距离走 geo.distanceText,不再输出 km / m', () => {
  const js = read('pages/play/index.js')
  assert.doesNotMatch(js, /toFixed\(1\) \+ 'km' : Math\.round\(dm\) \+ 'm'/)
  assert.match(js, /return geo\.distanceText\(dm\);/)
  const geo = require(path.join(ROOT, 'utils/geo.js'))
  assert.equal(geo.distanceText(320), '320 米')
  assert.equal(geo.distanceText(1530), '1.5 公里')
})

test('CG-ALL-24 玩法标题与主题数据条 keep-all + break-word,不再 anywhere / break-all 拆词', () => {
  const play = read('pages/play/index.wxss')
  assert.match(play, /\.fx-story__kitq\{[^}]*word-break:keep-all; overflow-wrap:break-word;/)
  assert.match(play, /\.st-intro__t\{[^}]*word-break:keep-all; overflow-wrap:break-word;/)
  const topic = read('pages/topic/index/index.wxss')
  assert.match(topic, /\.topic \.statbar__value \{[^}]*word-break: keep-all; overflow-wrap: break-word;/)
})

test('CG-ALL-25 章节门开着时弹窗压在门(130)之上并用近乎不透遮罩', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /chapterFull && chapterGate\.show \? 'play--gate' : ''/)
  const css = read('pages/play/index.wxss')
  const mask = css.match(/\.play--gate \.mask,[^{]*\{([^}]*)\}/)
  assert.ok(mask, '遮罩规则存在')
  assert.match(mask[1], /z-index:131/)
  assert.match(mask[1], /rgba\(8,8,8,\.96\)/)
  const sheet = css.match(/\.play--gate \.sheet,[^{]*\{([^}]*)\}/)
  assert.match(sheet[1], /z-index:132/)
  for (const sel of ['.gp2mask', '.finmask', '.chpick-mask']) assert.ok(mask[0].includes('.play--gate ' + sel), sel)
  for (const sel of ['.gp2', '.finsheet', '.chpick']) assert.ok(sheet[0].includes('.play--gate ' + sel), sel)
})

// 从页面源码里按花括号配平取出一个方法,包成对象方法真调(不加载整页)
function methodFrom(rel, name) {
  const src = read(rel)
  const start = src.indexOf('\n  ' + name + '(')
  assert.ok(start > 0, name + ' 存在')
  let i = src.indexOf('{', start), depth = 0
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) break
  }
  return new Function('return ({' + src.slice(start, i + 1) + '})')()[name]
}

test('FE-G3 自由探索(mode 2)工具抽屉「我的权益」关抽屉回格子层,不跳卡包页', () => {
  const fn = methodFrom('pages/play/index.js', 'openRightsFromTools')
  const calls = []
  const page = (mode, heroShow) => ({
    data: { mode, hero: { show: heroShow } },
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
    goCouponWallet() { calls.push('wallet') },
    closeChapStory() { calls.push('story') },
    closeHero() { calls.push('hero') },
  })
  const p2 = page(2, true)
  fn.call(p2)
  assert.equal(p2.data.sessionToolsOpen, false)
  assert.deepEqual(calls, ['story', 'hero'])
  calls.length = 0
  const p1 = page(1, false)
  fn.call(p1)
  assert.deepEqual(calls, ['wallet'], '城市定向仍去卡包')
})

test('FE-G1 无玩法店的店卡标 perkOnly,故事流底栏同口径', () => {
  const js = read('pages/play/index.js')
  assert.match(js, /perkOnly: Number\(this\.data\.mode\) === 2 && Number\(node\.merchantId\) > 0 && !node\.hasGame && !node\.hasAdvanced,/)
  const wxml = read('pages/play/index.wxml')
  const bar = wxml.match(/<cy-btn variant="primary" style="flex:1" bindtap="heroEnter">\{\{([^}]+)\}\}<\/cy-btn>/)
  const vm = require('node:vm')
  assert.equal(vm.runInNewContext(bar[1], { hero: { node: { perkOnly: true, done: false, playName: '' } } }), '我已到店')
  assert.equal(vm.runInNewContext(bar[1], { hero: { node: { perkOnly: true, done: true, playName: '' } } }), '查看权益')
  assert.equal(vm.runInNewContext(bar[1], { hero: { node: { perkOnly: false, done: false, playName: 'X' } } }), '开始 · X')
})

test('CG-ALL-23 逐字打出过的段落本局记住,再进故事流(完成第 1 站后)直接显示全文', () => {
  const store = {}
  global.wx = { getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = JSON.parse(JSON.stringify(v)) }, removeStorageSync: (k) => { delete store[k] } }
  const lookPath = path.join(ROOT, 'pages/play/utils/play-look.js')
  delete require.cache[lookPath]
  const { methods, typedKey } = require(lookPath)
  const make = (sessionId) => Object.assign({
    data: { chapter: { id: 31 }, isPreview: false },
    _look: { style: 'STARDUST', textFx: 'type' },
    _runSessionScope: () => ({ memberId: 7, topicId: 9, routeSessionId: sessionId }),
    setData() {},
  }, methods())
  const item = { kind: 'text', text: '第一段正文' }
  const page = make('s1')
  const first = page._textFx(item, 0)
  assert.equal(first.typedKey, typedKey(31, 0, '第一段正文'))
  assert.ok(first.fx, '第一次照常带动效')
  page._markTyped(first.typedKey)
  assert.deepEqual(page._textFx(item, 0), {}, '同一页再建故事流:全文直出')
  const reopened = make('s1')
  assert.deepEqual(reopened._textFx(item, 0), {}, '重进本局:从续玩存储读回')
  assert.ok(make('s2')._textFx(item, 0).fx, '新一局重新打字')
  assert.ok(page._textFx({ kind: 'text', text: '改过的正文' }, 0).fx, '文字变了算新段')
  const src = read('pages/play/index.js')
  assert.match(src, /if \(chapterFull && ln\.typedKey\) that\._markTyped\(ln\.typedKey\);/)
  delete global.wx
})

test('CG-01-P2-9 / CG-09-P2-6 玩法站过程埋点:只记站 id 与枚举码,六个事件两端白名单都有', () => {
  const tracked = []
  const analyticsPath = require.resolve(path.join(ROOT, 'utils/analytics.js'))
  const toastPath = require.resolve(path.join(ROOT, 'utils/toast.js'))
  const saved = [require.cache[analyticsPath], require.cache[toastPath]]
  require.cache[analyticsPath] = { id: analyticsPath, filename: analyticsPath, loaded: true, exports: { track: (n, p) => { tracked.push([n, p]); return true } } }
  require.cache[toastPath] = { id: toastPath, filename: toastPath, loaded: true, exports: () => {} }
  const modPath = path.join(ROOT, 'pages/play/utils/stage-events.js')
  delete require.cache[modPath]
  const { methods, ISSUE_CATEGORIES } = require(modPath)
  let sheet = null
  global.wx = { showActionSheet: (o) => { sheet = o } }
  const page = Object.assign({ data: { topicId: 9, isPreview: false, game: { nodeId: 5 }, helpPanel: { nodeId: '6' } } }, methods())
  page._stageEvent('stage_enter', 5)
  page.reportStageIssue()
  assert.deepEqual(sheet.itemList, ISSUE_CATEGORIES.map((c) => c.label))
  sheet.success({ tapIndex: 2 })
  assert.deepEqual(tracked, [
    ['stage_enter', { bizType: 'topic', bizId: 9, properties: { nodeId: '5' } }],
    ['stage_issue', { bizType: 'topic', bizId: 9, properties: { nodeId: '6', category: 'UNCLEAR' } }],
  ])
  page.data.isPreview = true
  assert.equal(page._stageEvent('stage_enter', 5), false, '预览不记')
  delete global.wx
  if (saved[0]) require.cache[analyticsPath] = saved[0]; else delete require.cache[analyticsPath]
  if (saved[1]) require.cache[toastPath] = saved[1]; else delete require.cache[toastPath]

  const client = read('utils/analytics.js')
  const server = fs.readFileSync(path.join(ROOT, '../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAnalyticsController.java'), 'utf8')
  for (const ev of ['stage_enter', 'hint_open', 'skip', 'assisted_complete', 'wrong_answer', 'stage_issue']) {
    assert.match(client, new RegExp('\\n  ' + ev + ': true,'), ev)
    assert.match(server, new RegExp('"' + ev + '"'), ev)
  }
  const js = read('pages/play/index.js')
  assert.match(js, /this\._stageEvent\('stage_enter', node\.nodeId\);/)
  assert.match(js, /if \(answerWrong\) that\._stageEvent\('wrong_answer', g\.nodeId\);/)
  assert.match(js, /this\._stageEvent\('assisted_complete', g\.nodeId, \{ via: 'reveal' \}\);/)
  assert.match(js, /this\._stageEvent\('skip', nodeId\); this\._stageEvent\('assisted_complete', nodeId, \{ via: 'skip' \}\);/)
  assert.match(read('pages/play/utils/play-help-panel.js'), /this\._stageEvent\('hint_open', c\.nodeId\)/)
  assert.match(read('pages/play/play-help.wxml'), /bindtap="reportStageIssue"[^>]*aria-label="这一站有问题"/)
})

test('FE-G7/G8/G9 整个主题无玩法:隐藏已完成环、章节读数、故事流、小瘾说、完成横幅;服务端布尔优先', () => {
  const { perkOnlyOf } = require(path.join(ROOT, 'pages/play/utils/perk-only.js'))
  const shop = (o) => Object.assign({ merchantId: 3, hasGame: false, hasAdvanced: false }, o)
  assert.equal(perkOnlyOf(2, [shop(), shop()], null), true)
  assert.equal(perkOnlyOf(2, [shop(), shop({ hasGame: true })], null), false)
  assert.equal(perkOnlyOf(2, [shop({ hasAdvanced: true })], null), false)
  assert.equal(perkOnlyOf(2, [{ merchantId: 0 }], null), false, '没有商家站不算')
  assert.equal(perkOnlyOf(1, [shop()], null), false, '城市定向不适用')
  assert.equal(perkOnlyOf(2, [shop({ hasGame: true })], true), true, '服务端说了算')
  assert.equal(perkOnlyOf(2, [shop()], false), false)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="fx-ring" wx:elif="\{\{!perkOnlyTopic\}\}">/)
  assert.match(wxml, /\{\{perkOnlyTopic \? '' : chapterCards\.length \+ ' 章 · '\}\}\{\{merchantCount\}\} 家商家/)
  assert.match(wxml, /<text class="fx-lhd__t" wx:if="\{\{!perkOnlyTopic\}\}">探索节点<\/text>/)
  assert.match(wxml, /wx:if="\{\{hero\.node\.hook && !perkOnlyTopic\}\}"><text class="fx-hero__hook-k">小瘾说/)
  assert.match(wxml, /=== 'banner' && !perkOnlyTopic\}\}"/)
  const js = read('pages/play/index.js')
  assert.match(js, /if \(this\.data\.perkOnlyTopic\) return;/)
  assert.match(js, /const perkOnlyTopic = perkOnlyOf\(mode, visibleNodes, this\._serverPerkOnly\);/)
})

test('CG-01-P2-7 证据卡回看:收本局已完成且写了文字反馈的站,工具菜单有入口', () => {
  const { evidenceCards, methods } = require(path.join(ROOT, 'pages/play/utils/evidence-cards.js'))
  const nodes = [{ nodeId: 1, name: '一站', done: true, passText: ' 门楣上的字 ' }, { nodeId: 2, name: '二站', done: false, passText: '未完成' }, { nodeId: 3, name: '三站', done: true, passText: '' }]
  assert.deepEqual(evidenceCards(nodes), [{ key: '1', name: '一站', text: '门楣上的字' }])
  const page = Object.assign({ data: { nodes, sessionToolsOpen: true }, setData(p) { for (const k of Object.keys(p)) { if (k.includes('.')) { const [a, b] = k.split('.'); this.data[a][b] = p[k] } else this.data[k] = p[k] } } }, methods())
  page.openEvidenceCards()
  assert.equal(page.data.sessionToolsOpen, false)
  assert.equal(page.data.evidence.items.length, 1)
  page.closeEvidenceCards()
  assert.equal(page.data.evidence.show, false)
  assert.match(read('pages/play/index.wxml'), /wx:if="\{\{evidenceCount > 0\}\}" bindtap="openEvidenceCards"/)
  assert.match(read('pages/play/play-help.wxml'), /<view class="phl-pop" wx:if="\{\{evidence\.show\}\}">/)
  assert.match(read('pages/play/index.js'), /evidenceCount: evidenceKit\.evidenceCards\(nodes\)\.length/)
})
