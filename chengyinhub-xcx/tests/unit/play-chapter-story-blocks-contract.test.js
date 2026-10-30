const assert = require('node:assert/strict')
const { test, beforeEach } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const PLAY = '../../pages/play/index.js'
const ROOT = path.resolve(__dirname, '../..')
const read = relativePath => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

// 章节剧情屏读的是 /api/play/nodes 下发的章节 blocks(文字 / 图片 / 音频)。
// ⚠️ 2026-09-06 用户裁决:**两种音频并存** ——
//    · chapter.audioUrl = 整章背景旁白,打开本章自动播,没有位置(key='chapter');
//    · blocks 里的 audio = 带位置的片段,读到那一段才响,要点(key='ch<i>')。
//    两者共用同一个播放器,后播的把前面那段顶掉。
// 这一屏此前只读 chapter.description —— blocks 后端发了三个版本没人消费。
// 所以这里必须是**行为测**:真调 openChapterFull,断言它铺出来的 chapterParas。
// 静态匹配挡不住「读到了但铺错顺序 / 把图片 url 当正文打出来」这类。

let pageConfig
let audio
let requests = []

global.getApp = () => ({ globalData: {}, getUserID: () => 1, sendRequest: options => { requests.push(options) } })

global.wx = {
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
  getStorageSync: () => null,
  setStorageSync: () => {},
  removeStorageSync: () => {},
  showToast: () => {},
  getSetting: ({ success }) => success({ authSetting: {} }),
  createSelectorQuery: () => ({
    selectAll: () => ({ boundingClientRect: () => {} }),
    select: () => ({ boundingClientRect: () => {}, scrollOffset: () => {} }),
    exec: () => {},
  }),
  createInnerAudioContext: () => {
    audio = {
      src: '', played: 0, paused: 0, stopped: 0,
      play() { this.played += 1 }, pause() { this.paused += 1 }, stop() { this.stopped += 1 },
      onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {}, onWaiting() {}, onCanplay() {},
      destroy() {},
    }
    return audio
  },
}

global.Page = config => { pageConfig = config }

function setAtPath(target, rawPath, value) {
  const parts = rawPath.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

function makePage(chapter, nodes) {
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.data.chapter = chapter
  page.data.nodes = nodes || []
  page.setData = function (patch, callback) {
    Object.keys(patch).forEach(key => setAtPath(page.data, key, patch[key]))
    if (callback) callback()
  }
  page.measureChapter = () => {}
  return page
}

beforeEach(() => {
  audio = null
  requests = []
  pageConfig = null
  delete require.cache[require.resolve(PLAY)]
  require(PLAY)
})

test('故事玩法在正文位置启动，完成收尾不索要定位，地点仍要求定位', () => {
  const page = makePage({ blocks: [
    { type: 'text', content: '护士递来一张表。' },
    { type: 'node', nodeId: 51, locationRequired: false },
    { type: 'text', content: '下一段尚未解锁' },
  ] }, [{ nodeId: 51, name: '角色建档', hasAdvanced: true, vm: 0, done: false }])
  page.openChapterFull()
  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['text', 'game'])
  let started, located = 0, arrived
  page.startGame = node => { started = node.nodeId }
  page.startStoryGame({ currentTarget: { dataset: { nodeid: 51 } } })
  assert.equal(started, 51)
  page.data.sheet.node = page.data.nodes[0]
  page.requestPlayLocation = () => { located++ }
  page.arrive = id => { arrived = id }
  page.onSheetGoTap()
  assert.equal(arrived, 51)
  assert.equal(page.data.playKit.show, false, '权威收尾后必须关闭旧玩法结果层')
  assert.equal(page.data.playKit.kit, null)
  assert.equal(located, 0)
  page.data.chapter.blocks[1].locationRequired = true
  page.onSheetGoTap()
  assert.equal(located, 1)
})

// 2026-09-22 走查:整屏玩法在流里只是一颗白药丸「新生儿登记 · 开始」,紧挨着「出发」那颗,读成两个按钮。
// 用户裁决「就要故事流的样子」—— 照原型 fsEntry:入口卡(封面 + 右上「进入 · 全屏」+ 标签 / 玩法名 / 一句说明)。
test('故事玩法自动展开，流中只有标题而没有入口方框', () => {
  const page = makePage({ blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] },
    [{ nodeId: 51, name: '新生儿登记', hasAdvanced: true, vm: 0, done: false }])
  page.openChapterFull()
  assert.equal(page.data.chapterParas[0].auto, true)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="chfull__game-cue"/)
  assert.doesNotMatch(wxml, /class="chfull__fsentry /)
})

// 原型:故事流停在玩法入口卡,卡就是唯一的下一步。旧版底部还亮着「出发」,点了只会弹「请先完成开局玩法」。
test('流停在未玩的故事玩法时,底部不再摆「出发 / 继续探索」', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="chfull__end" wx:if="\{\{\(chapterParas\[chapterParas\.length - 1\]\.kind !== 'game' && /)
})

// 原型:做完的入口卡留在原位,右上变成半透明「已完成」(.fsentry.done)。旧版做完卡整张消失,读者找不到刚玩过什么。
test('做完的故事玩法不留已完成占位卡，也不能重复进入', () => {
  const page = makePage({ blocks: [
    { type: 'node', nodeId: 51, locationRequired: false },
    { type: 'node', nodeId: 52, locationRequired: false },
  ] }, [
    { nodeId: 51, name: '新生儿登记', hasAdvanced: true, vm: 0, done: true },
    { nodeId: 52, name: 'BIOS', hasAdvanced: true, vm: 0, done: false },
  ])
  page.openChapterFull()
  const games = page.data.chapterParas.filter(p => p.kind === 'game')
  assert.deepEqual(games.map(g => [g.text, !!g.done]), [['BIOS', false]])
  let started = 0
  page.startGame = () => { started++ }
  page.startStoryGame({ currentTarget: { dataset: { nodeid: 51 } } })
  assert.equal(started, 0)
  assert.equal(storyEndVisible({ kind: 'game', done: true }), true, '已完成卡仍允许继续');
})

// 原型 gainThought:读到伏笔块时出一张「得到一个念头」卡(光球 / 名字 / 说明 / 边走边想)。
// 旧版流里整块跳过。真玩家只认服务端已下发的念头(没得到的不露名字,防剧透);试玩认草稿里配的。
test('读到伏笔块出「得到一个念头」卡;没得到的念头不露名字', () => {
  const blocks = [{ type: 'text', content: '我想——' }, { type: 'thought', thoughtKey: 'tmud' }, { type: 'text', content: '后面' }]
  const preview = makePage({ blocks }, [])
  preview.data.isPreview = true
  preview._previewThoughts = [{ key: 'tmud', name: '我很幸运', desc: '所有人都这么说', need: 3000 }]
  preview.openChapterFull()
  const card = preview.data.chapterParas.find(p => p.kind === 'thought')
  assert.deepEqual([card.name, card.desc, card.need], ['我很幸运', '所有人都这么说', 3000])
  assert.deepEqual(preview.data.chapterParas.map(p => p.kind), ['text', 'thought', 'text'], '伏笔卡在原位，后文照常')

  const player = makePage({ blocks }, [])
  player._routeState = { thoughts: [] }
  player.openChapterFull()
  assert.equal(player.data.chapterParas.some(p => p.kind === 'thought'), false, '没下发的念头提前露名字就是剧透')
  player._routeState = { thoughts: [{ key: 'tmud', name: '我很幸运', need: 3000, progress: 1200, done: false }] }
  player.openChapterFull()
  assert.equal(player.data.chapterParas.find(p => p.kind === 'thought').name, '我很幸运')

  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /wx:elif="\{\{item\.kind === 'thought'\}\}" class="chfull__thought"/)
  assert.match(wxml, /得到一个念头/)
})

// 伏笔(2026-09-22 用户裁决):授权在开局弹;读到伏笔块就认领,不依赖微信运动;到站同步步数。
function stubWeRun(granted) {
  const calls = { authorize: [], login: 0 }
  Object.assign(global.wx, {
    getSetting: ({ success }) => success({ authSetting: granted === undefined ? {} : { 'scope.werun': granted } }),
    authorize: (o) => { calls.authorize.push(o.scope) },
    login: ({ success }) => { calls.login += 1; success({ code: 'js-code' }) },
    getWeRunData: ({ success }) => success({ encryptedData: 'cipher', iv: 'iv' }),
  })
  return calls
}
const flush = () => new Promise((r) => setImmediate(r))

test('自由探索剧情只认领未完成玩法之前的伏笔，服务端回读后展示且重复打开不重领', async () => {
  stubWeRun(false)
  const page = makePage({}, [{ nodeId: 51, done: false }])
  page.data.hero.node = { chapterId: 7, chapName: '第一章', chapDesc: '章节正文' }
  page.data.chapterCards = [{ id: 7, blocks: [
    { type: 'thought', thoughtKey: 'before' },
    { type: 'node', nodeId: 51 },
    { type: 'thought', thoughtKey: 'after' },
  ] }]
  page._routeState = { thoughts: [], thoughtCount: 2 }
  page._applyRouteState = function (state, cb) { this._routeState = state; cb && cb(true) }
  page._settleStory = () => {}
  page._startStoryDust = () => {}
  page.openChapStory()
  clearTimeout(page._storyTimer)
  assert.equal(page.data.chapStory.lines.some(line => line.thoughtKey), false)
  await flush()
  const sync = requests.find(r => r.url === '/api/play/journey/thought/sync')
  assert.deepEqual(sync.data.claim, ['before'])
  sync.success({ code: 200, data: { thoughts: [{ key: 'before', name: '已获得的伏笔', desc: '这段说明', done: false }], thoughtCount: 2 } })
  await flush()
  assert.deepEqual(page.data.chapStory.lines.filter(line => line.thoughtKey).map(line => line.title || line.text), ['已获得的伏笔', '这段说明'])
  page.openChapStory()
  clearTimeout(page._storyTimer)
  await flush()
  assert.equal(requests.filter(r => r.url === '/api/play/journey/thought/sync').length, 1)
  page._routeState.thoughts[0].done = true
  page._refreshChapStoryThoughts()
  assert.equal(page.data.chapStory.lines.find(line => line.title === '已获得的伏笔').meta, '伏笔 · 已应验')
  page.data.nodes[0].done = true
  page.openChapStory()
  clearTimeout(page._storyTimer)
  await flush()
  assert.deepEqual(requests.filter(r => r.url === '/api/play/journey/thought/sync')[1].data.claim, ['after'])
})

test('没有概述但有可读伏笔的自由探索章节仍可打开，试玩不会认领服务端', () => {
  const page = makePage({}, [])
  page.data.isPreview = true
  page._previewThoughts = [{ key: 'only', name: '念头' }]
  page.data.hero.node = { chapterId: 7, chapName: '第一章', chapDesc: '' }
  page.data.chapterCards = [{ id: 7, blocks: [{ type: 'thought', thoughtKey: 'only' }] }]
  page._settleStory = () => {}
  page._startStoryDust = () => {}
  page.openChapStory()
  clearTimeout(page._storyTimer)
  assert.equal(page.data.chapStory.show, true)
  assert.equal(page.data.chapStory.lines.find(line => line.thoughtKey).title, '念头')
  assert.equal(requests.length, 0)
})

test('自由探索详情保留章节关联，纯内容不展示商家核销区域，真实商家保留', () => {
  const page = makePage({}, [])
  page.data.chapterCards = [{ id: 7, title: '第一章', description: '正文' }]
  assert.equal(page._heroView({ nodeId: 51, chapterId: 7, merchantId: 0 }).isMerchant, false)
  const merchant = page._heroView({ nodeId: 52, chapterId: 7, merchantId: 9002 })
  assert.equal(merchant.chapterId, 7)
  assert.equal(merchant.isMerchant, true)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="fx-shop fx-hero__in"[^>]*wx:if="\{\{hero.node.isMerchant\}\}"/)
  assert.match(wxml, /class="fx-step3 fx-hero__in"[^>]*wx:if="\{\{hero.node.isMerchant\}\}"/)
})

test('读到还没得到的伏笔块就认领;没授权微信运动也认领,只是不带步数密文', async () => {
  stubWeRun(false)
  const page = makePage({ blocks: [{ type: 'text', content: '我想——' }, { type: 'thought', thoughtKey: 'tmud' }] }, [])
  page.data.topicId = 10
  page._routeState = { thoughts: [], thoughtCount: 1 }
  page._applyRouteState = function (state, cb) { this._routeState = state; cb && cb(true) }
  page.openChapterFull()
  await flush()
  const sync = requests.find((r) => r.url === '/api/play/journey/thought/sync')
  assert.ok(sync, '读到伏笔块没有认领')
  assert.deepEqual(sync.data.claim, ['tmud'])
  assert.equal(sync.data.encryptedData, undefined, '没授权就不该去要微信运动数据')

  sync.success({ code: 200, data: { thoughts: [{ key: 'tmud', name: '那扇窗', need: 0, progress: 0, done: false }], thoughtCount: 1 } })
  await flush()
  assert.equal(page.data.chapterParas.find((p) => p.kind === 'thought').name, '那扇窗', '认领回来后卡片要出现')
  page.openChapterFull()
  await flush()
  assert.equal(requests.filter((r) => r.url === '/api/play/journey/thought/sync').length, 1, '已认领的不重复认领')
})

test('授权过微信运动时,认领顺带交步数密文(先 login 再 getWeRunData)', async () => {
  const calls = stubWeRun(true)
  const page = makePage({ blocks: [{ type: 'thought', thoughtKey: 'tmud' }] }, [])
  page._routeState = { thoughts: [], thoughtCount: 1 }
  page.openChapterFull()
  await flush()
  const sync = requests.find((r) => r.url === '/api/play/journey/thought/sync')
  assert.equal(sync.data.encryptedData, 'cipher')
  assert.equal(sync.data.code, 'js-code')
  assert.equal(calls.login, 1)
})

test('这局配了伏笔:开局请求一次微信运动授权;问过(允许或拒绝)的不再打扰', () => {
  const fresh = stubWeRun(undefined)
  const page = makePage({}, [])
  page._askWeRunOnce(); page._askWeRunOnce()
  assert.deepEqual(fresh.authorize, ['scope.werun'], '开局要问，且只问一次')
  const denied = stubWeRun(false)
  makePage({}, [])._askWeRunOnce()
  assert.deepEqual(denied.authorize, [], '拒过的不追问')
  const js = read('pages/play/index.js')
  assert.match(js, /if \(routeState\.thoughtCount > 0\) that\._askWeRunOnce\(\);/, '首屏加载成功就问(开局),不是等读到伏笔')
})

test('试玩最后一章读完点继续探索看结局:默认兜底结局,能切换看每一个;没配结局照旧返回编辑', () => {
  const page = makePage({ id: 1 }, [])
  page.data.isPreview = true
  page._previewChapters = [{ id: 1 }]
  page._previewChapterIndex = 0
  page._previewEndings = [{ code: 'awake', title: '醒来', summary: '你推开了窗', cond: '想通「那扇窗」' },
    { code: 'default', title: '预制的一生', summary: '', cond: '其它结局都不中时', fallback: true }]
  page.continueChapterStory()   // 试玩:本章读完点「继续探索」就接结局(顶条的推进钮已删)
  assert.equal(page.data.previewEnding.show, true)
  assert.equal(page.data.previewEnding.list[page.data.previewEnding.idx].title, '预制的一生', '默认看保底那条')
  page.pickPreviewEnding({ currentTarget: { dataset: { idx: 0 } } })
  assert.equal(page.data.previewEnding.list[page.data.previewEnding.idx].title, '醒来')

  const bare = makePage({ id: 1 }, [])
  bare.data.isPreview = true
  bare._previewChapters = [{ id: 1 }]; bare._previewChapterIndex = 0; bare._previewEndings = []
  let back = 0; bare.previewGoBack = () => { back += 1 }
  bare.continueChapterStory()
  assert.equal(back, 1)
  assert.match(read('pages/play/index.wxml'), /class="pv-ending [^"]*" wx:if="\{\{previewEnding\.show\}\}"/)
})

// 2026-09-23 整本《预制人生》试玩:点入口卡进内嵌问答后,题干被顶到顶栏底下 ——
// 锚点值和入口卡时一样(chapter-game-<id>),setData 同值不再触发 scroll-into-view,滚动停在旧位置。
test('入口卡换成内嵌玩法时重新对准一次锚点(先清空再设),动画结束后再校正一次', async () => {
  const page = makePage({ blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] },
    [{ nodeId: 51, name: '站起来回答', hasAdvanced: true, vm: 0, done: false }])
  page.openChapterFull()
  page.data.chapterGameAnchor = 'chapter-game-51'
  const seen = []
  const origin = page.setData
  page.setData = function (patch, cb) { if ('chapterGameAnchor' in patch) seen.push(patch.chapterGameAnchor); return origin.call(this, patch, cb) }
  page._playKitNodeId = 51
  page.data.advancedPlay = { show: true, node: page.data.nodes[0] }
  page._kit = () => null
  page._inlineKit = () => ({ present: 'inline' })
  page.onAdvancedSession({ detail: { sessionId: 1, version: 1, present: 'inline', playKit: { qa: { mode: 'PICK', title: '站起来回答', options: [] } } } })
  assert.deepEqual(seen.slice(-2), ['', 'chapter-game-51'], '同值 setData 不会让 scroll-view 再对准一次')
  const before = seen.length
  page.data.chapterFull = true
  await new Promise((r) => setTimeout(r, 1300))
  assert.deepEqual(seen.slice(before), ['', 'chapter-game-51'],
    '第一次长距离滚动途中上方还在重排，实测停在顶栏底下 95px —— 要再对准一次')
})

test('卡包打开内嵌问答时展示独立题面，故事流打开时继续内嵌', () => {
  const node = { nodeId: 51, name: '文字答题', hasAdvanced: true, vm: 1, done: false }
  const page = makePage({ blocks: [] }, [node])
  page.data.advancedPlay = { show: true, node }
  const view = { nodeId: 51, sessionId: 1, version: 0, present: 'inline',
    playKit: { qa: { mode: 'TYPE', title: '请输入答案', attempts: 0, finished: false } } }
  page.onAdvancedSession({ detail: view })
  assert.equal(page.data.playKit.kit.type, 'qa')
  assert.equal(page.data.playKit.show, true, '卡包没有故事流容器，必须展示题面')
  page.data.chapterFull = true
  page.onAdvancedSession({ detail: view })
  assert.equal(page.data.playKit.show, false, '故事流已经打开时仍在正文位置作答')
  assert.equal(page.data.playKit.kit.type, 'qa')
})

function storyEndVisible(last) {
  const match = read('pages/play/index.wxml').match(/<view class="chfull__end" wx:if="\{\{([^}]+)\}\}">/);
  assert.ok(match, '故事末尾必须绑定真实显示条件');
  return require('node:vm').runInNewContext(match[1], { chapterParas: [last] });
}

test('未完成的普通玩法和encounter检定下面不摆「继续探索」', () => {
  for (const kind of ['game', 'kit', 'journeyCheck']) assert.equal(storyEndVisible({ kind, done: false }), false);
  assert.equal(storyEndVisible({ kind: 'text' }), true);
  assert.equal(storyEndVisible({ kind: 'game', done: true }), true);
})

// 2026-09-23 整本试玩:作者给章节选了「白 / 红 / 蓝 / 黄」、正文里插了氛围块,故事流里一律还是黑底 ——
// 氛围色板只接到了游戏页 .gp2,玩家真正在读的故事流 .chfull 没接。
test('正式故事继续探索打开下一站详情，不退回已删除的地图舞台', async () => {
  const node = { nodeId: 224, name: '展示馆', done: false }
  const page = makePage({ id: 2, name: '出生' }, [node])
  page.data.chapterFull = true
  page.data.nextNode = { nodeId: 224 }
  let opened
  page.openSheet = n => { opened = n }
  await page.continueChapterStory()
  assert.equal(opened, node)
  assert.equal(page.data.chapterFull, true)
})

test('章节故事始终使用纯黑背景', () => {
  assert.match(read('pages/play/index.wxss'), /\.chfull\{[^}]*background:#000/)
  assert.match(read('pages/play/index.wxml'), /class="chfull[^"\n]*"/)
})

// 2026-09-23 用户裁决(优化顺序第 1 项):左上角罗盘点开 = 真实小地图(这一站 / 下一站 / 我),
// 再点收回故事流;下一站给「导航过去」—— 调微信外部地图,站内不接管导航。
test('罗盘点开小地图;没有带坐标的地点时照旧回卡包;导航交给微信地图', () => {
  const page = makePage({}, [])
  let closed = 0
  let locationRequests = 0
  page.requestPlayLocation = () => { locationRequests += 1 }
  page.closeChapterFull = () => { closed += 1 }
  page.data.fmNodes = []
  page.openStoryMap()
  assert.equal(page.data.storyMap, false)
  assert.equal(closed, 1, '没有地点可画就别开一张空地图')
  assert.equal(locationRequests, 0)

  page.data.fmNodes = [{ nodeId: 7, lat: 31.2304, lng: 121.4737 }]
  page.data.nextNode = { nodeId: 7, lat: 31.2304, lng: 121.4737 }
  page.openStoryMap()
  assert.equal(page.data.storyMap, true)
  assert.deepEqual(page.data.storyMapCenter, { lat: 31.2304, lng: 121.4737 }, '以下一站为中心')
  assert.equal(locationRequests, 1, '没有定位时走现有定位授权入口')
  page.closeStoryMap()
  assert.equal(page.data.storyMap, false)
  page.data.gpsOk = true
  page.openStoryMap()
  assert.equal(locationRequests, 1, '已有定位时不重复请求')
  page.closeStoryMap()

  const opened = []
  global.wx.openLocation = (o) => opened.push(o)
  page.data.nextNode = { nodeId: 7, name: '上海城市规划展示馆', address: '人民大道 100 号', lat: 31.2304, lng: 121.4737 }
  page.openNextStopNav()
  assert.equal(opened.length, 1)
  assert.deepEqual([opened[0].latitude, opened[0].longitude, opened[0].name], [31.2304, 121.4737, '上海城市规划展示馆'])

  // 2026-10-30 业主定稿:左上角两种显示 —— 罗盘点一下切地图(GTA III 式雷达),地图点一下进大地图,右下角收回罗盘
  const stored = {}
  global.wx.setStorageSync = (k, v) => { stored[k] = v }
  page.data.navMap = false
  page.onNavTap()
  assert.equal(page.data.navMap, true, '罗盘点一下切到地图')
  assert.equal(stored.cy_play_nav_mode, 'map', '选过的显示记在本机')
  page.data.storyMap = false
  page.data.fmNodes = [{ nodeId: 7, lat: 31.2304, lng: 121.4737 }]
  page.onNavTap()
  assert.equal(page.data.storyMap, true, '地图点一下进大地图')
  page.closeStoryMap()
  page.foldNavMap()
  assert.equal(page.data.navMap, false)
  assert.equal(stored.cy_play_nav_mode, 'compass')

  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="fx-story__compass \{\{navMap \? 'fx-story__radar is-big' : ''\}\}" bindtap="onNavTap"/)
  assert.match(wxml, /<free-map class="fx-story__radar-map"[^>]*rotate="\{\{navHeading\}\}"/, '雷达跟朝向转')
  assert.match(wxml, /class="radar__fold" wx:if="\{\{navMap\}\}"[^>]*catchtap="foldNavMap"/)
  assert.match(wxml, /<free-map class="story-map__map" wx:if="\{\{storyMap\}\}"/)
})

test('故事流开章和换景不再创建合成环境音或音效', () => {
  let contexts = 0
  const original = global.wx.createWebAudioContext
  global.wx.createWebAudioContext = () => { contexts++; throw new Error('不应创建合成声音') }
  try {
    const page = makePage({ id: 3, name: '学习', atmosphereClass: 'atmosphere--yellow' }, [])
    page.data.chapterCards = [{ id: 3 }]
    page.showChapterGate()
    page._syncStoryScene()
    assert.equal(page._sceneTarget, 'afternoon', '声音删除后仍按章节氛围换景')
    assert.equal(contexts, 0)
    assert.equal(page.toggleStorySound, undefined)
    assert.equal(Object.hasOwn(page.data, 'soundOn'), false)
    assert.doesNotMatch(read('pages/play/index.js'), /createWebAudioContext|story-audio|_storySound/)
    assert.doesNotMatch(read('pages/play/index.wxml'), /toggleStorySound|环境音与音效/)
  } finally {
    global.wx.createWebAudioContext = original
  }
})

test('故事玩法入口卡不拿站点兜底文案当说明(「走到这里,留意四周」是地点卡的话)', () => {
  const page = makePage({ blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] }, [])
  page.data.nodes = [page.normNode({ nodeId: 51, name: '新生儿登记', validateMethod: 1 }, 0)]
  page.openChapterFull()
  const game = page.data.chapterParas.find(p => p.kind === 'game')
  assert.equal(game.sub, '')
})

test('正文走真实高级玩法入口，内嵌完成继续调用权威收尾；换地点不会串用旧玩法位置', () => {
  const game = { nodeId: 51, name: '建档', hasAdvanced: true, vm: 0, done: false }
  const place = { nodeId: 52, name: '车站', hasAdvanced: true, vm: 0, done: false, lat: 31, lng: 121 }
  const page = makePage({ blocks: [
    { type: 'node', nodeId: 51, locationRequired: false },
    { type: 'text', content: '走向车站' },
    { type: 'node', nodeId: 52 },
  ] }, [game, place])
  page.openChapterFull()
  page.startStoryGame({ currentTarget: { dataset: { nodeid: 51 } } })
  assert.equal(page.data.advancedPlay.node.nodeId, 51)
  assert.equal(page.data.advancedPlay.show, true)
  const view = { sessionId: 11, version: 1, present: 'inline', readyForBase: true,
    playKit: { qa: { mode: 'PICK', title: '已经完成', options: [] } } }
  page.onAdvancedSession({ detail: view })
  assert.equal(page.data.playKit.show, false)
  assert.equal(page.data.chapterParas[0].kind, 'kit')
  assert.equal(page.data.chapterGameAnchor, 'chapter-game-51')
  page.data.chapterGameAnchor = ''
  page.onAdvancedSession({ detail: view })
  assert.equal(page.data.chapterGameAnchor, '', '后续回包不把阅读位置拉回玩法')
  page._storyTop = 500
  let arrived
  page.arrive = id => { arrived = id }
  // 真实 selectComponent 对同一个已挂载组件每次返回同一实例
  const advancedGame = { continueBase: () => page.onAdvancedReady({ detail: { nodeId: 51 } }) }
  page.selectComponent = () => advancedGame
  page.continueStoryGame()
  assert.equal(arrived, 51)
  assert.equal(page.data.playKit.show, false, '权威收尾后必须关闭旧玩法结果层')
  assert.equal(page.data.playKit.kit, null)
  assert.equal(page.data.chapterFull, true)
  assert.equal(page._storyTop, 500)
  game.done = true
  page.startGame(place)
  page.onAdvancedSession({ detail: { ...view, sessionId: 12 } })
  // 做完的建档卡留在原处(原型 .fsentry.done),新地点的玩法接在「走向车站」之后
  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['text', 'kit'])
  assert.equal(page.data.chapterParas[0].text, '走向车站')
  assert.equal(page.data.chapterParas[1].nodeId, 52)
})

test('普通模板从故事启动并退出后保持原章节，任务层不被章节遮挡', () => {
  const page = makePage({ blocks: [{ type: 'node', nodeId: 61, locationRequired: false }] },
    [{ nodeId: 61, name: '输入答案', vm: 1, done: false }])
  page.data.isPreview = true
  page.openChapterFull()
  page.startStoryGame({ currentTarget: { dataset: { nodeid: 61 } } })
  assert.equal(page.data.screen, 'gamePlay')
  assert.equal(page.data.chapterFull, true)
  assert.match(read('pages/play/index.wxml'), /class="chfull[^"\n]*"[^>]*hidden="\{\{screen === 'gamePlay'/)
  page.closeGame()
  assert.equal(page.data.screen, '')
  assert.equal(page.data.chapterFull, true)
})

test('快速打字提交等待开始请求确认，不被高级玩法在途锁吞掉', async () => {
  const page = makePage({}, [])
  page.data.playKit = { kit: { type: 'typein', sessionId: 2 } }
  page.data.advancedPlay = { show: true }
  const actions = []
  let confirmStart
  // 真实 selectComponent 对同一个已挂载组件每次返回同一实例
  const advancedGame = { action(name) {
    actions.push(name)
    return name === 'START_CHALLENGE' ? new Promise(resolve => { confirmStart = resolve }) : Promise.resolve(true)
  } }
  page.selectComponent = () => advancedGame
  page._submitPlayKitAction('start', {})
  page._submitPlayKitAction('submit', { text: 'hello world', elapsedMs: 2000 })
  assert.deepEqual(actions, ['START_CHALLENGE'])
  confirmStart(true)
  await Promise.resolve()
  assert.deepEqual(actions, ['START_CHALLENGE', 'SUBMIT_TYPE_IN'])
});

test('故事玩法只在服务端确认完成后自动衔接，重复回包不重复提交', async () => {
  const node = { nodeId: 51, name: '开机', vm: 0, hasAdvanced: true, done: false }
  const page = makePage({ blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] }, [node])
  page.data.chapterFull = true
  page._storyOpenNode = true
  page.data.advancedPlay = { show: true, node }
  let continued = 0
  page.continueStoryGame = () => { continued++ }
  const detail = { nodeId: 51, readyForBase: false, playKit: { typeIn: { title: '开机' } } }
  page.onAdvancedSession({ detail })
  assert.equal(continued, 0)
  page.onAdvancedSession({ detail: { ...detail, readyForBase: true } })
  page.onAdvancedSession({ detail: { ...detail, readyForBase: true } })
  await new Promise(resolve => setTimeout(resolve, 950))
  assert.equal(continued, 1)
});

test('关闭内嵌玩法错误面板后恢复开始入口，不留无法收尾的旧 kit', () => {
  const node = { nodeId: 51, name: '建档', vm: 0, hasAdvanced: true, done: false }
  const page = makePage({ blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] }, [node])
  page.data.chapterFull = true
  page.data.advancedPlay = { show: true, node }
  page.data.playKit = { show: false, kit: { type: 'profile', present: 'inline' } }
  page.onAdvancedClose()
  assert.equal(page.data.playKit.kit, null)
  assert.equal(page.data.sheet.show, false)
  assert.equal(page.data.chapterParas[0].kind, 'game')
  page.onAdvancedSession({ detail: { nodeId: 51, present: 'inline', readyForBase: true,
    playKit: { profile: { title: '迟到的回包', done: true } } } })
  assert.equal(page.data.playKit.kit, null)
  assert.equal(page.data.chapterParas[0].kind, 'game')
})

test('上一章末尾玩法完成后，即使重建已切到下一章也刷新服务端解锁内容', () => {
  const node = { nodeId: 51, name: '建档', vm: 0, done: false }
  const page = makePage({ blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] }, [node])
  page.data.chapterFull = true
  page.data.mode = 1
  page._routeState = { routeMode: 'LINEAR', attributes: {} }
  page.rebuild = () => { page.data.chapter = { id: 2, blocks: [{ type: 'node', nodeId: 52 }] } }
  page._triggerCelebration = () => {}
  page._animateCompletedSegment = () => {}
  global.wx.vibrateShort = () => {}
  let refreshed = 0
  page.loadData = first => { assert.equal(first, false); refreshed++ }
  page.onComplete(51, { xp: 0 })
  assert.equal(page.data.nodes[0].done, true)
  assert.equal(refreshed, 1)
})

test('章节块流按作者编排的顺序铺开,文字与图片都到得了玩家眼前', () => {
  // ⚠️ 节点必须是 done —— 这条测的是「三类媒体按序渲染」,不是截断闸。
  //    节点留 undone 的话「收尾」会被截断闸正确地挡掉,这条会红在一个它不负责的原因上。
  const page = makePage({
    description: '开场',
    blocks: [
      { type: 'text', content: '开场' },
      { type: 'image', url: 'https://cdn/x.jpg' },
      { type: 'node', nodeId: 501 },
      { type: 'text', content: '收尾' },
    ],
  }, [{ nodeId: 501, done: true, story: '' }])

  page.openChapterFull()

  // node 块不在这屏展开:站点有自己的卡片,再摆一遍会把剧情读成目录
  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['text', 'image', 'text'])
  assert.equal(page.data.chapterParas[0].text, '开场')
  assert.equal(page.data.chapterParas[1].url, 'https://cdn/x.jpg')
  assert.equal(page.data.chapterParas[2].text, '收尾')
})

// ★ description 就是块流里首个节点之前那些文字块的后端投影。两个都推 = 开头整段重复,
//   而且只在「章节确实有块流」时才复现 —— 存量无块章节看不出来。
test('有块流时不再叠加 description,开头不会重复一遍', () => {
  const page = makePage({
    description: '开场',
    blocks: [{ type: 'text', content: '开场' }],
  })

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['开场'])
})

test('没有块流的存量章节回退到 description,老数据不能变成空屏', () => {
  const page = makePage({ description: '第一段\n第二段', blocks: null })

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['第一段', '第二段'])
  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['text', 'text'])
})

test('缺 url 的媒体块整块丢掉,不铺一个点了没反应的空条', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'image', url: '' },
      { type: 'audio' },
      { type: 'text', content: '正文还在' },
    ],
  })

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['text'])
})

// 2026-09-04 拍板:进本章即播,不要玩家点。所以这里没有 tap 入口可测 ——
// 断言必须挂在「打开章节屏」这个动作上。
test('章节背景旁白在打开本章时自动起播,audioNodeId 用 chapter 不与块/节点相撞', () => {
  const page = makePage({
    description: '正文',
    audioUrl: 'https://cdn/x.mp3',
    blocks: [{ type: 'text', content: '正文' }],
  })

  page.openChapterFull()

  assert.equal(audio.src, 'https://cdn/x.mp3')
  assert.equal(audio.played, 1)
  assert.equal(page.data.audioNodeId, 'chapter')
})

// ★ 两种音频共用一个 InnerAudioContext。key 撞了就会出现「点块却把旁白当成同一段」这种
//   看不出来的错 —— 旁白是 'chapter',块是 'ch<i>',字符串不相等。
test('故事流音频块能点着播,且与整章旁白的 key 不相撞', () => {
  const page = makePage({
    description: '',
    audioUrl: 'https://cdn/narration.mp3',
    blocks: [{ type: 'audio', url: 'https://cdn/clip.mp3' }],
  })
  page.openChapterFull()
  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['audio'])
  assert.equal(page.data.audioNodeId, 'chapter', '进本章先起播旁白')

  page.toggleChapterAudio({ currentTarget: { dataset: { i: 0 } } })
  assert.equal(page.data.audioNodeId, 'ch0', '点块之后换成块')
  assert.equal(audio.src, 'https://cdn/clip.mp3')
})

// ★ 上面那条会假绿:makePage 是直接给 page.data.chapter 赋值的,绕过了 buildChapter。
//   而 buildChapter 是**白名单**映射 —— 后端下发了、它不列,页面就永远拿不到。
//   E1 第一版就漏在这:audioUrl 没进白名单,自动播放是死链,上面那条却照样绿。
//   所以必须单独钉「后端下发的那个字段真的走得进 data.chapter」。
test('buildChapter 必须把后端下发的 audioUrl 映射进 chapter —— 白名单漏一个就是死链', () => {
  const page = makePage({}, [])
  const built = page.buildChapter(
    { chapters: [{ name: '第一章', audioUrl: 'https://cdn/narration.mp3', blocks: [] }] },
    [],
  )

  assert.equal(built.audioUrl, 'https://cdn/narration.mp3',
    'buildChapter 漏了 audioUrl:playChapterAudio 读到的永远是 undefined,自动播放整条不发生')
})

test('没配旁白的章节不碰播放器,不要为了统一而空放一次', () => {
  const page = makePage({ description: '正文', blocks: [{ type: 'text', content: '正文' }] })

  page.openChapterFull()

  assert.equal(audio, null)
})

// 滚动/重测都会再进 playChapterAudio,重播会把旁白剁成开头一秒。
test('已经在放同一章旁白时不重播', () => {
  const page = makePage({
    description: '正文',
    audioUrl: 'https://cdn/x.mp3',
    blocks: [{ type: 'text', content: '正文' }],
  })
  page.openChapterFull()
  page.data.audioPlaying = true

  page.playChapterAudio()

  assert.equal(audio.played, 1)
})

test('收起章节屏要停掉章节音频,不留一段在后台继续念', () => {
  const page = makePage({
    description: '正文',
    audioUrl: 'https://cdn/x.mp3',
    blocks: [{ type: 'text', content: '正文' }],
  })
  page.openChapterFull()

  page.closeChapterFull()

  assert.equal(audio.stopped >= 1, true)
  assert.equal(page.data.audioNodeId, null)
  assert.equal(page.data.chapterFull, false)
})

// ===== 故事流截断闸(设计文档 §7.1「其后的块:完全不渲染」)=====
// ★ 这组是防回归的:master 上这屏只读 chapter.description,而后端 description 投影
//   本来就在首个 node 块处停,所以旧代码天然安全。是「开始消费完整块流」这件事
//   把潜在泄漏变成真泄漏 —— 没有这道闸,节点之后的剧情会在玩家走到之前就全看见。

test('未完成的节点块之后,一个块都不渲染 —— 否则就是剧透', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'text', content: '开场' },
      { type: 'node', nodeId: 501 },
      { type: 'text', content: '这段是走到第一站之后才该看见的' },
      { type: 'image', url: 'https://cdn/spoiler.jpg' },
    ],
  }, [{ nodeId: 501, done: false, story: '' }])

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['开场'])
  assert.equal(page.data.chapterParas.some(p => p.kind === 'image'), false,
    '未完成节点之后的图片同样不许出现')
})

test('节点完成后,它的手记就地接上,后面的块继续往下放', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'text', content: '开场' },
      { type: 'node', nodeId: 501 },
      { type: 'text', content: '第一站之后的剧情' },
      { type: 'node', nodeId: 502 },
      { type: 'text', content: '第二站之后的剧情' },
    ],
  }, [
    { nodeId: 501, done: true, story: '你在门牌下找到了刻痕。' },
    { nodeId: 502, done: false, story: '' },
  ])

  page.openChapterFull()

  // 501 已完成 ⇒ 开场 + 它的手记 + 它后面那段;502 未完成 ⇒ 到此为止
  assert.deepEqual(page.data.chapterParas.map(p => p.text),
    ['开场', '你在门牌下找到了刻痕。', '第一站之后的剧情'])
})

test('节点块自身不占一段 —— 站点有自己的卡片,再摆一遍会读成目录', () => {
  const page = makePage({
    description: '',
    blocks: [{ type: 'node', nodeId: 501 }, { type: 'text', content: '走完之后' }],
  }, [{ nodeId: 501, done: true, story: '' }])

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['走完之后'])
})

// nodeId 后端下发是数字、页面上归一后也可能是字符串,类型不一致会让闸「永远判未完成」
// —— 那样整章只剩开场一段,是另一个方向的静默故障。
test('nodeId 数字与字符串混用仍能配上,不会把已完成误判成未完成', () => {
  const page = makePage({
    description: '',
    blocks: [{ type: 'node', nodeId: 501 }, { type: 'text', content: '走完之后' }],
  }, [{ nodeId: '501', done: true, story: '' }])

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['走完之后'])
})

test('块流里引用了页面上不存在的节点时按未完成处理,宁可少显示不可剧透', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'text', content: '开场' },
      { type: 'node', nodeId: 999 },
      { type: 'text', content: '不该出现' },
    ],
  }, [{ nodeId: 501, done: true, story: '' }])

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['开场'])
})

test('章节剧情复用探店日故事流并连接章节开场', () => {
  const wxml = read('pages/play/index.wxml')
  const wxss = read('pages/play/index.wxss')
  const tokens = read('style/tokens.wxss')
  const driver = read('scripts/_verify_play_roadmap_shots.js')
  const storyStart = wxml.search(/<view class="chfull(?: |")/)
  const storyEnd = wxml.indexOf('<!-- ===================== 底部运行栈', storyStart)
  const storyWxml = wxml.slice(storyStart, storyEnd)

  assert.doesNotMatch(storyWxml, /chfull__eyebrow/)
  // 2026-10-30 业主:顶栏标题要带主题名 ——「主题名 · 章节名」,主题名为空或与章节同名时只显示章节名
  assert.match(storyWxml, /class="fx-story__t[^"\n]*"[^>]*>\{\{topicName && topicName !== chapter\.name \? topicName \+ ' · ' : ''\}\}\{\{chapter\.name\}\}/)
  assert.match(storyWxml, /item\.kind === 'cover'[^>]*class="chfull__cover"/,
    '主物件进入与正文相同的滚动衰减序列')
  assert.match(storyWxml, /class="chfull__continue"[^>]*bindtap="continueChapterStory"/,
    '参考稿最终 CTA 映射到现有收起并继续玩法动作')
  assert.match(wxml, /!chapterFull/, '章节叙事打开时必须隐藏底部黑色运行栏')
  assert.match(wxss, /\.chfull\s*\{[^}]*background:#000/,
    '采用现有探店日故事流底色');
  assert.match(storyWxml, /chfull__para st-line/, '正文必须共用探店日显形样式');
  assert.match(storyWxml, /cy-chapter-flip[^>]*mode="gate"/, '每章开场使用 2026-10-28 定稿的章节翻转过场');
  assert.match(tokens, /--cy-color-play-story-page:\s*#FFFFFF;/, '浅色 token 留着给 roadmap / session')
  assert.doesNotMatch(storyWxml, /passport|invite code|护照|邀请码/i,
    '只复用 Infinity 结构，不把 Atlys 业务对象带入城瘾')
  assert.equal((wxss.match(/\.chfull\s*\{/g) || []).length, 1,
    '章节全屏样式只能保留一份，避免同一改版被迫双改')

  const page = makePage({
    cover: '/images/route_city_cover.png',
    description: '',
    blocks: [{ type: 'text', content: '正文' }],
  })
  page.openChapterFull()
  assert.deepEqual(page.data.chapterParas.map(item => item.kind), ['cover', 'text'])
  assert.equal(page.data.chapterParas[0].url, '/images/route_city_cover.png')
  assert.match(driver, /page\.callMethod\('openChapterFull'\)/,
    'DevTools 必须从页面公开 handler 打开真实章节滚动态')
  assert.match(driver, /play-infinity-story-figma-2026-08-23\.png/)
})

// 2026-09-18 UI-20 返修:发布端历史默认章节名是英文占位「Chapter N」,截图里 topicId=10
// 的起始页标题就是原样上屏的「Chapter 1」。占位必须显示成中文序数,不能再露给玩家。
test('buildChapter 把英文占位章节名显示成中文「第 N 章」', () => {
  const page = makePage({}, [])
  const built = page.buildChapter({ topicName: '哈哈哈', chapters: [{ name: 'Chapter 1' }] }, [])

  assert.equal(built.name, '第 1 章', '英文占位要换成中文序数，且不能落回主题名')
  assert.equal(built.idxLabel, '', '眉标和标题重复同一个序数时不再重复')
})

test('buildChapter 保留真实章节名,空名仍回落主题名', () => {
  const page = makePage({}, [])

  assert.equal(page.buildChapter({ topicName: '哈哈', chapters: [{ name: '雨夜的第一站' }] }, []).name,
    '雨夜的第一站')
  assert.equal(page.buildChapter({ topicName: '哈哈', chapters: [{}] }, []).name, '哈哈')
})

test('buildChapterCards 的占位章节不把 Chapter N 或重复序数露给玩家', () => {
  const page = makePage({}, [])
  const cards = page.buildChapterCards({
    chapters: [{ name: 'Chapter 2' }, { name: '真实的第二章' }],
  }, { idxLabel: '', name: '' })

  assert.equal(cards[0].title, '第 2 章', '占位里的序号优先于数组下标')
  assert.equal(cards[0].meta, '', '序数标签与标题重复时不出')
  assert.equal(cards[1].title, '真实的第二章')
  assert.equal(cards[1].meta, '第 2 章', '真实章节名仍带序数标签')
})

// 2026-09-22 走查:序章开场印了三遍「序章」(眉标 + 标题 + 副标题),故事流顶栏也是「序章 / 序章」。
test('序章:顶栏眉标与标题同名时不出,开场门不把序数标签再印一遍', () => {
  const page = makePage({}, [])
  const ch = page.buildChapter({ chapters: [{ id: 9, opening: true, name: '序章', idxLabel: '序章' }] }, [])
  assert.equal(ch.name, '序章')
  assert.equal(ch.idxLabel, '', '顶栏会印成「序章 / 序章」')
  const real = page.buildChapter({ chapters: [{ id: 10, name: '展示馆', idxLabel: '第一章' }] }, [])
  assert.equal(real.idxLabel, '第一章', '负控:真实章节名仍带序数眉标')

  page.data.chapter = { id: 10, name: '展示馆', idxLabel: '第一章' }
  page.data.chapterCards = [{ id: 10 }]
  page.showChapterGate()
  assert.equal(page.data.chapterGate.subtitle, '', '开场门眉标已是「第 1 章 / 共 1 章」,副标题再印「第一章」')

  const gate = read('components/cy/chapter-gate/index.wxml')
  assert.match(gate, /class="gate__meta" wx:if="\{\{number !== 0 \|\| title !== '序章'\}\}"/, '序章标题下又印一个「序章」眉标')
})

// 显示层兜住旧数据,发布端也不该再制造英文占位(只影响新发布,不动存量)。
test('发布端不再把英文占位 Chapter N 写进新章节', () => {
  assert.doesNotMatch(read('pages/publish/simple/index.js'), /name:\s*'Chapter/,
    'simple 发布默认章节名必须是中文')
  assert.doesNotMatch(read('pages/publish/fabu/index.js'), /name:\s*(ch\.name\s*\|\|\s*)?'Chapter/,
    'fabu 发布/AI 草稿默认章节名必须是中文')
})



test('每章首次阅读有开场，继续后同章不重播，下一章仍有开场', () => {
  const page = makePage({ id: 51, name: '第一站', description: '正文' });
  page.data.chapterCards = [{ id: 51 }, { id: 52 }];
  page.openChapterFull();
  assert.equal(page.data.chapterGate.show, true);
  assert.equal(page.data.chapterGate.number, 1);
  page.continueChapterGate();
  page.closeChapterFull(); page.openChapterFull();
  assert.equal(page.data.chapterGate.show, false);
  page.data.chapter = { id: 52, name: '下一站', description: '下一段' };
  page.openChapterFull();
  assert.equal(page.data.chapterGate.show, true);
  assert.equal(page.data.chapterGate.number, 2);
});


test('开场未继续不播放旁白，关闭章节停止播放', () => {
  const page = makePage({ id: 51, name: '第一站', audioUrl: 'https://cdn/chapter.mp3', blocks: [{type: 'text', content: '正文'}] });
  page.openChapterFull();
  assert.equal(audio, null);
  page.continueChapterGate();
  assert.equal(audio.played, 1);
  page.closeChapterFull();
  assert.ok(audio.stopped > 0);
});

test('草稿试玩先拦截上传和订阅，正式会话不接受作者本地kit', () => {
  const node = { nodeId: 51, name: '建档', hasAdvanced: true, vm: 0, previewKit: { type: 'profile', present: 'inline' } }
  const page = makePage({ blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] }, [node])
  page.data.isPreview = true
  page.data.advancedPlay = { show: true, node }
  page.data.playKit = { kit: node.previewKit }
  const events = []
  page.selectComponent = () => ({ previewKitAction: event => { events.push(event.action); return true } })
  page._submitKitPhoto = () => { throw new Error('preview uploaded') }
  page._subscribePlayKit = () => { throw new Error('preview subscribed') }
  for (const action of ['avatar', 'shoot', 'submit', 'subscribe', 'sync']) page.onPlayKitAction({ detail: { action } })
  assert.deepEqual(events, ['avatar', 'shoot', 'submit', 'subscribe', 'sync'])
  page.onAdvancedSession({ detail: { nodeId: 51, previewKit: { ...node.previewKit, done: true }, readyForBase: true } })
  assert.equal(page.data.playKit.kit.done, true)
  assert.equal(page.data.nodes[0].previewKit.done, true)
  page.data.isPreview = false
  page.onAdvancedSession({ detail: { nodeId: 51, previewKit: { ...node.previewKit, title: '不可用于正式游戏' } } })
  assert.equal(page.data.playKit.kit, null)
})


test('旧偏好题入口在草稿中不按临时节点ID读写正式接口', () => {
  const page = makePage({}, [{ nodeId: 1, vm: 6 }])
  page.data.isPreview = true
  page.data.game = { nodeId: 1 }
  page.loadPreference(page.data.nodes[0])
  assert.equal(requests.length, 0)
  assert.equal(page.data.preference.loading, false)
  assert.match(page.data.preference.error, /预览/)
  page.submitPreference({ choices: {} })
  page.data.preference.pendingTag = { id: 99, tagValue: 'old' }
  page.data.preference.availableTagValues = ['new']
  page.confirmPreferenceTag()
  page.onTagSheetSelect({ detail: { index: 0 } })
  assert.equal(requests.length, 0)
})


test('带坐标高级玩法在预览完成后可继续，不索取定位、不发奖', () => {
  const node = { nodeId: 1, chapterId: 1, vm: 0, hasAdvanced: true, lat: 31, lng: 121 };
  const page = makePage({ id: 1, blocks: [{ key: 'a', type: 'node', nodeId: 1 }] }, [node]);
  page.data.isPreview = true;
  page.data.mode = 2;
  page.data.advancedPlay = { show: true, node };
  page.rebuild = () => {};
  page.openChapterFull = () => {};
  page.startNav = () => { throw new Error('preview navigation'); };
  page.requestPlayLocation = () => { throw new Error('preview location'); };
  page.onAdvancedReady({ detail: { nodeId: 1 } });
  assert.equal(page.data.nodes[0].done, true);
  assert.equal(page.data.flyScore, false);
  assert.equal(requests.length, 0);
});

test('预览提示与揭晓只读当前配置，不发解谜请求或积分解锁', () => {
  const page = makePage({}, []);
  page.data.isPreview = true;
  page.data.mode = 1;
  page.data.game = { nodeId: 1, puzzleScoring: true, hintLocked: true, hint1: '作者当前提示', reveal: '作者当前答案' };
  page.useHint();
  assert.equal(requests.length, 0);
  assert.deepEqual(page.data.shownHints, ['作者当前提示']);
  page.unlockHint();
  page.revealAnswer();
  assert.equal(requests.length, 0);
  assert.equal(page.data.revealed, true);
});

test('草稿检定结束后原位保留作者配置的结果文案', () => {
  const node = { nodeId: 1, chapterId: 1, previewKit: { mode: 'd20', result: { text: '暗门没有打开' } } };
  const page = makePage({ id: 1, blocks: [{ type: 'node', nodeId: 1 }] }, [node]);
  page.data.isPreview = true;
  page.rebuild = () => {};
  page._completePreviewNode(1);
  assert.equal(page.data.chapterParas[0].text, '暗门没有打开');
  assert.equal(requests.length, 0);
});


test('真实草稿映射保留开局标识，玩家开场序章为零且第一章不抢号', () => {
  const page = makePage()
  const d = { chapters: [{ id: 10, opening: true, name: '序章', idxLabel: '序章' },
    { id: 11, name: '出发', idxLabel: '第 1 章' }] }
  page.data.chapterCards = page.buildChapterCards(d, {})
  page.data.chapter = page.buildChapter(d, [])
  page.showChapterGate()
  assert.equal(page.data.chapterGate.number, 0)
  page.data.chapter = page.buildChapter({ chapters: [d.chapters[1]] }, [])
  page.showChapterGate()
  assert.equal(page.data.chapterGate.number, 1)
  assert.equal(page.data.chapterGate.total, 1)
})

test('开局出发使用当前局和版本，失败保留故事，成功重新读取第一章', async () => {
  const page = makePage({ id: 90, opening: true }, [])
  page.data.topicId = 10
  page.data.activityId = 21
  page.data.chapterFull = true
  page._routeState = { version: 7, opening: { chapterId: 90, complete: false, ready: true } }
  let reloaded = 0
  page.loadData = () => { reloaded++ }
  const failed = page.continueChapterStory()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/play/journey/opening/complete')
  assert.deepEqual(requests[0].data, { topicId: 10, activityId: 21, chapterId: 90, expectedVersion: 7 })
  await page.continueChapterStory()
  assert.equal(requests.length, 1, '连点不能并发提交')
  requests[0].fail()
  await failed
  assert.equal(page.data.chapterFull, true)
  assert.equal(reloaded, 0)
  const passed = page.continueChapterStory()
  requests[1].success({ code: 200, data: { opening: { complete: true } } })
  await passed
  assert.equal(page.data.chapterFull, false)
  assert.equal(reloaded, 1)
})

test('未完成开局玩法不能出发；草稿预览不写正式开局存档', async () => {
  const page = makePage({ id: 90, opening: true }, [])
  page.data.chapterFull = true
  page._routeState = { currentNodeId: 12, opening: { chapterId: 90, ready: false } }
  await page.continueChapterStory()
  assert.equal(requests.length, 0)
  assert.equal(page.data.chapterFull, true)
  assert.equal(page.data.chapterGameAnchor, 'chapter-game-12')
  page.data.isPreview = true
  let back = 0
  page.previewGoBack = () => { back++ }
  await page.continueChapterStory()
  assert.equal(back, 1, '试玩:没有下一章也没配结局 ⇒ 回编辑')
  assert.equal(requests.length, 0, '试玩不写正式开局存档')
})

test('开局玩法已完成但尚未出发时，重进不能清除暂停存档', () => {
  const page = makePage({ id: 90, opening: true }, [{ nodeId: 1, done: true }])
  page.data.topicId = 10
  let cleared = 0
  page._clearRunSessionEverywhere = () => { cleared++ }
  page._restoreRunSession({}, { status: 'ACTIVE', opening: { complete: false, ready: true } }, page.data.nodes)
  assert.equal(cleared, 0)
})

// ==================== 氛围块(mood) ====================

test('氛围块自己不占一段 —— 它是标记不是内容,占一段就把正文顶错位', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'text', content: '开场' },
      { type: 'mood', key: 'm1', mood: 'YELLOW' },
      { type: 'text', content: '后面' },
    ],
  }, [])

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['开场', '后面'],
    'items 必须与 .chfull__para 一一对应,mood 多出一段就整屏错位')
})

test('氛围跟着正文流走:标记之后的段落带上新基调,之前的不受影响', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'text', content: '之前' },
      { type: 'mood', key: 'm1', mood: 'YELLOW' },
      { type: 'text', content: '之后' },
      { type: 'mood', key: 'm2', mood: 'WHITE' },
      { type: 'text', content: '再之后' },
    ],
  }, [])

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.mood), ['', 'YELLOW', 'WHITE'],
    '滚到哪一段就是哪一段的氛围；第一个标记之前没有基调')
})

test('氛围不认的预设名不往下带 —— 服务端白名单兜底，客户端也不渲染野值', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'mood', key: 'm1', mood: '随便写的' },
      { type: 'text', content: '正文' },
    ],
  }, [])

  page.openChapterFull()

  assert.deepEqual(page.data.chapterParas.map(p => p.mood), [''])
})

// ==================== 内心声音(voice)与越往后越不正常(odd) ====================

test('异常块是标记:之后的段落带档位;内心声音占一段带颜色,第 3 档时规矩改名「进程」', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'text', content: '之前' },
      { type: 'voice', key: 'v0', who: '心', content: '你还记得吗' },
      { type: 'odd', key: 'o1', level: 3 },
      { type: 'text', content: '之后' },
      { type: 'voice', key: 'v1', who: '规矩', content: '回到推荐路线上。' },
      { type: 'voice', key: 'v2', who: '旁白', content: '不认识的声音不出' },
    ],
  }, [])

  page.openChapterFull()

  const paras = page.data.chapterParas
  assert.deepEqual(paras.map(p => p.kind), ['text', 'voice', 'text', 'voice'])
  assert.deepEqual(paras.map(p => p.odd), [0, 0, 3, 3])
  assert.equal(paras[1].who, '心')
  assert.equal(paras[1].color, '#FF9F80')
  assert.equal(paras[3].who, '进程', '第 3 档:声音越来越像机器')
  assert.equal(paras[3].text, '回到推荐路线上。')
})

test('反转块:对面的话按行成气泡,占一段;开章时对话框复位', () => {
  const page = makePage({
    description: '',
    blocks: [
      { type: 'text', content: '你看。我现在站得直直的。' },
      { type: 'reveal', key: 'r1', who: '王倩', content: '你在跟谁说话?\n\n我一直在这儿。' },
    ],
  }, [])
  page.data.storyChat = true

  page.openChapterFull()

  const reveal = page.data.chapterParas.find(p => p.kind === 'reveal')
  assert.deepEqual(reveal.lines, ['你在跟谁说话?', '我一直在这儿。'])
  assert.equal(reveal.who, '王倩')
  assert.equal(page.data.storyChat, false, '重开本章要从故事流开始读，不能直接是对话框')
})

test('拍照类玩法的入口卡是相机、动作词是「打开相机」(2026-09-23 用户)', () => {
  const page = makePage({ blocks: [{ type: 'node', nodeId: 61, locationRequired: false }] }, [])
  // 走真实归一(normNode):类型从玩法配置里读出来
  page.data.nodes = [page.normNode({ nodeId: 61, name: '拍下展示馆的招牌', advancedConfigJson: JSON.stringify({ photoCheck: { enabled: true } }) }, 0, 1)]
  page.openChapterFull()
  const game = page.data.chapterParas.find(p => p.kind === 'game')
  assert.deepEqual([game.icon, game.go], ['camera', '打开相机'])
})

// ==================== 存档:中途退出回到原处(2026-09-24) ====================
function withStore(fn) {
  const store = {}
  const saved = { get: global.wx.getStorageSync, set: global.wx.setStorageSync }
  global.wx.getStorageSync = k => store[k]
  global.wx.setStorageSync = (k, v) => { store[k] = JSON.parse(JSON.stringify(v)) }
  try { fn(store) } finally { global.wx.getStorageSync = saved.get; global.wx.setStorageSync = saved.set }
}

test('读到哪一段、开着哪个玩法记在本地;重进先重开那个玩法,没开就滚回读到的那一段', () => withStore(() => {
  const chapter = { id: 8, blocks: [
    { type: 'text', content: '第一段' }, { type: 'text', content: '第二段' }, { type: 'text', content: '第三段' },
    { type: 'node', nodeId: 61, locationRequired: false }] }
  const nodes = [{ nodeId: 61, name: '入职登记', hasAdvanced: true, vm: 0, done: false }]
  const page = makePage(chapter, nodes)
  page.data.topicId = 5
  page.openChapterFull()
  page._saveReadingPos(page.data.chapterParas[2])
  // 重进(新页面实例):滚回第三段
  const again = makePage(chapter, nodes)
  again.data.topicId = 5
  again.openChapterFull()
  assert.equal(again.data.chapterGameAnchor, 'chapter-block-2')
  // 开着玩法退出:重进直接重开它
  let started = null
  const third = makePage(chapter, nodes)
  third.data.topicId = 5
  third.startGame = n => { started = n.nodeId }
  third.openChapterFull()
  third.startStoryGame({ currentTarget: { dataset: { nodeid: 61 } } })
  started = null
  const fourth = makePage(chapter, nodes)
  fourth.data.topicId = 5
  fourth.startGame = n => { started = n.nodeId }
  fourth.openChapterFull()
  assert.equal(started, 61, '退出时开着的玩法，重进直接回到它')
  // 主动关掉玩法:不再自动打开
  fourth.closePlayKit()
  started = null
  const fifth = makePage(chapter, nodes)
  fifth.data.topicId = 5
  fifth.startGame = n => { started = n.nodeId }
  fifth.openChapterFull()
  assert.equal(started, null)
}))

test('看过的章节开场门记在本地,重进不再重播;试玩不存', () => withStore(() => {
  const chapter = { id: 9, name: '学习', blocks: [{ type: 'text', content: '正文' }] }
  const page = makePage(chapter, [])
  page.data.topicId = 5
  page.data.chapterCards = [{ id: 9 }]
  page.showChapterGate()
  assert.equal(page.data.chapterGate.show, true)
  page.continueChapterGate()
  const again = makePage(chapter, [])
  again.data.topicId = 5
  again.data.chapterCards = [{ id: 9 }]
  again.showChapterGate()
  assert.notEqual(again.data.chapterGate && again.data.chapterGate.show, true, '看过的门不重播')
}))

test('咔哒那句随 /nodes 回来:重进后故事流里照样接在那一站后面', () => {
  const page = makePage({ blocks: [{ type: 'node', nodeId: 61, locationRequired: false }, { type: 'text', content: '后文' }] },
    [{ nodeId: 61, name: '入职登记', hasAdvanced: true, vm: 0, done: true }])
  page._applyStoryVars({ storyVoices: { 61: [{ picked: '画师', assigned: '会计', voice: '表格上写的是{assigned}。' }] } })
  page.openChapterFull()
  const voice = page.data.chapterParas.find(p => p.kind === 'voice')
  assert.ok(voice, '咔哒那句要在')
  assert.equal(voice.text, '表格上写的是会计。')
})

// ==================== 开始与结束的动画(2026-09-24) ====================
test('开局第一道门先放「开机」,放完自己收掉;第二道门、重进、减弱动态都不放', (t) => withStore(() => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = makePage({ id: 1, name: '出生', blocks: [{ type: 'text', content: '正文' }] }, [])
  page.data.topicId = 5
  page.data.chapterCards = [{ id: 1 }, { id: 2 }]
  page.showChapterGate()
  assert.equal(page.data.bootStage, 'on')
  t.mock.timers.tick(600)
  assert.equal(page.data.bootStage, 'open')
  t.mock.timers.tick(1500)
  assert.equal(page.data.bootStage, '', '放完整层卸掉，不挡开场门')
  assert.equal(page.data.chapterGate.show, true, '开机期间开场门不被自动跳过')
  page.continueChapterGate()
  page.data.chapter = { id: 2, name: '学习', blocks: [] }
  page.showChapterGate()
  assert.equal(page.data.bootStage, '', '第二道门不再开机')
  const again = makePage({ id: 2, name: '学习', blocks: [] }, [])
  again.data.topicId = 5
  again.data.chapterCards = [{ id: 1 }, { id: 2 }]
  again.showChapterGate()
  assert.equal(again.data.bootStage, '', '重进(已看过门)不开机')
  const reduced = makePage({ id: 1, name: '出生', blocks: [] }, [])
  reduced.data.topicId = 6
  reduced.data.reducedMotion = true
  reduced.data.chapterCards = [{ id: 1 }]
  reduced.showChapterGate()
  assert.equal(reduced.data.bootStage, '')
}))

test('结局卡依次入场;点继续先「关机」再进完成页,连点只进一次;减弱动态直接进', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = makePage({ blocks: [] }, [])
  let finished = 0
  page.openFinish = () => { finished++ }
  page._showEnding({ show: true, real: true, idx: 0, list: [{ code: 'a', title: '结局' }], archive: {}, hasArchive: false })
  assert.equal(page.data.previewEnding.in, false)
  t.mock.timers.tick(40)
  assert.equal(page.data.previewEnding.in, true)
  page.continueLifeEnding()
  page.continueLifeEnding()
  assert.equal(page.data.previewEnding.off, 'off')
  assert.equal(finished, 0, '关机动画放完之前不跳走')
  t.mock.timers.tick(500)
  assert.equal(page.data.previewEnding.off, 'gone')
  t.mock.timers.tick(400)
  assert.equal(page.data.previewEnding.show, false)
  assert.equal(finished, 1)
  const reduced = makePage({ blocks: [] }, [])
  let n = 0
  reduced.openFinish = () => { n++ }
  reduced.data.reducedMotion = true
  reduced._showEnding({ show: true, real: true, idx: 0, list: [{ code: 'a', title: '结局' }], archive: {}, hasArchive: false })
  assert.equal(reduced.data.previewEnding.in, true)
  reduced.continueLifeEnding()
  assert.equal(n, 1)
})

test('故事主题开局先看开场页:主题名 + 简介第一句;开始游戏后才进故事流,这一局只看一次;非故事主题直接进', (t) => withStore(() => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const chapter = { id: 3, name: '出生', blocks: [{ type: 'text', content: '正文' }] }
  const page = makePage(chapter, [])
  page.data.topicId = 5
  page.data.topicName = '预制人生'
  page.data.topicDesc = '是你选择了命运，还是命运选择了你？后面还有很多话。'
  page._openStory()
  assert.equal(page.data.storyIntro.show, true)
  assert.equal(page.data.storyIntro.title, '预制人生')
  assert.equal(page.data.storyIntro.line, '是你选择了命运，还是命运选择了你？')
  assert.equal(page.data.chapterFull, false, '开始游戏之前不进故事流')
  page.startStoryIntro()
  assert.equal(page.data.storyIntro.out, true)
  t.mock.timers.tick(450)
  assert.equal(page.data.storyIntro.show, false)
  assert.equal(page.data.chapterFull, true)
  const again = makePage(chapter, [])
  again.data.topicId = 5
  again._openStory()
  assert.notEqual(again.data.storyIntro.show, true, '同一局重进不再看开场页')
  assert.equal(again.data.chapterFull, true)
  const classic = makePage({ id: 4, name: '第一站' }, [])
  classic.data.topicId = 6
  classic._openStory()
  assert.notEqual(classic.data.storyIntro.show, true, '没有故事流的经典路线不出开场页')
}))

test('建档交上去之后出初始状态页:只列作者开了的精力/幸运 + 可见属性(回执当前值);一局只出一次', (t) => withStore(() => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const make = () => {
    const node = { nodeId: 51, name: '建档', vm: 0, done: false }
    const page = makePage({ id: 2, blocks: [{ type: 'node', nodeId: 51, locationRequired: false }] }, [node])
    page.data.topicId = 5
    page.data.chapterFull = true
    page.data.mode = 1
    page._routeState = { routeMode: 'LINEAR', attributes: [] }
    page.rebuild = () => {}
    page._triggerCelebration = () => {}
    page._animateCompletedSegment = () => {}
    page.loadData = () => {}
    global.wx.vibrateShort = () => {}
    page._vitalsOn = { hp: true, luck: false }
    page._profileNodeId = '51'
    return page
  }
  const page = make()
  page.onComplete(51, { xp: 0, routeState: { routeMode: 'LINEAR', hp: 10, luck: 3, attributes: [{ label: '想象力', value: 1 }] } })
  assert.equal(page.data.startState.show, true)
  assert.deepEqual(page.data.startState.rows.map(r => r.label + r.value), ['精力10', '想象力1'], '幸运没开，不列')
  page.closeStartState()
  t.mock.timers.tick(450)
  assert.equal(page.data.startState.show, false)
  const again = make()
  again.onComplete(51, { xp: 0, routeState: { routeMode: 'LINEAR', hp: 10, attributes: [] } })
  assert.equal(again.data.startState.show, false, '这一局已经看过')
  const other = make()
  other.data.topicId = 6
  other._vitalsOn = null
  other.onComplete(51, { xp: 0, routeState: { routeMode: 'LINEAR', hp: 10, luck: 3, attributes: [] } })
  assert.equal(other.data.startState.show, false, '没开初始状态、也没有属性:不出空页')
}))

test('内嵌玩法读到就直接铺开(不出「进入」),每站只自动开一次;整屏玩法读到时自动打开', () => {
  const chapter = { id: 4, blocks: [{ type: 'text', content: '护士递来一张表。' }, { type: 'node', nodeId: 71, locationRequired: false }] }
  const inline = makePage(chapter, [{ nodeId: 71, name: '新生儿登记', hasAdvanced: true, vm: 0, done: false, advancedConfig: { present: 'inline' } }])
  const started = []
  inline.startGame = n => { started.push(n.nodeId) }
  inline.openChapterFull()
  const card = inline.data.chapterParas.find(p => p.kind === 'game')
  assert.equal(card.auto, true, '铺开前那一瞬只是占位，没有「进入」')
  // 2026-10-29 业主:正文要读到才展开,开章不再直接跳到第一个内嵌玩法;读到它时由滚动结算(_settleStory)调 _autoInline
  assert.deepEqual(started, [], '开章不提前打开,不跳过正文')
  inline._autoInline(71)
  assert.deepEqual(started, [71])
  inline._autoInline(71)
  assert.deepEqual(started, [71], '同一站不重复自动开')
  const full = makePage(chapter, [{ nodeId: 71, name: '拍下招牌', hasAdvanced: true, vm: 0, done: false, advancedConfig: { present: 'fullscreen' } }])
  const fullStarted = []
  full.startGame = n => { fullStarted.push(n.nodeId) }
  full.openChapterFull()
  assert.deepEqual(fullStarted, [], '未读到整屏玩法时不能提前打开')
  assert.equal(full.data.chapterParas.find(p => p.kind === 'game').auto, true)
})

// ===== 结局章(2026-09-24)=====
test('通关后服务端带回结局章:先当故事流读(不弹开场门、末尾是「继续」),读完才出结局卡;没带章节就直接出卡', async () => {
  const page = makePage({ id: 1, name: '第四章', blocks: [{ type: 'text', content: '最后一段' }] }, [])
  page.data.topicId = 5
  let finished = 0
  page.openFinish = () => { finished++ }
  page._loadLifeEnding(() => {})
  const request = requests.filter(r => r.url === '/api/play/journey/ending').at(-1)
  request.success({ code: 200, data: { code: 'ending1', title: '窗外', summary: '你推开了窗。', archive: {},
    chapter: { id: 88, name: '窗外', blocks: [{ type: 'text', content: '你推开了窗，风很大。' }] } } })
  await new Promise(r => setImmediate(r))   // req 是 Promise 包装,回包后等一拍
  assert.equal(page.data.chapterFull, true)
  assert.equal(page.data.chapter.ending, true)
  assert.equal(page.data.chapter.name, '窗外')
  assert.notEqual(page.data.chapterGate && page.data.chapterGate.show, true, '结局章不弹开场门')
  assert.notEqual(page.data.previewEnding.show, true, '读完之前不出结局卡')
  page.continueChapterStory()
  assert.equal(page.data.chapterFull, false)
  assert.equal(page.data.previewEnding.show, true)
  assert.equal(page.data.previewEnding.list[0].title, '窗外')

  const plain = makePage({ id: 1, blocks: [] }, [])
  plain.data.topicId = 5
  plain._loadLifeEnding(() => {})
  requests.filter(r => r.url === '/api/play/journey/ending').at(-1).success({ code: 200, data: { code: 'd', title: '余波', archive: {} } })
  await new Promise(r => setImmediate(r))
  assert.equal(plain.data.previewEnding.show, true, '老数据没有章节:照旧直接出卡')
})

test('试玩:读完最后一章先播兜底那章;卡上点另一个结局就读那一章', () => {
  const page = makePage({ id: 1 }, [])
  page.data.isPreview = true
  page._previewChapters = [{ id: 1 }]
  page._previewChapterIndex = 0
  page._previewEndings = [
    { code: 'ch1', title: '窗外', cond: '想通「那扇窗」', chapter: { id: 2, name: '窗外', blocks: [{ type: 'text', content: '窗外' }] } },
    { code: 'ch2', title: '余波', cond: '其它结局都不中时', fallback: true, chapter: { id: 3, name: '余波', blocks: [{ type: 'text', content: '余波' }] } },
  ]
  page.continueChapterStory()
  assert.equal(page.data.chapter.name, '余波', '先读保底那章')
  page.continueChapterStory()
  assert.equal(page.data.previewEnding.show, true)
  page.pickPreviewEnding({ currentTarget: { dataset: { idx: 0 } } })
  assert.equal(page.data.chapter.name, '窗外')
  assert.equal(page.data.previewEnding.show, false)
  page.continueChapterStory()
  assert.equal(page.data.previewEnding.show, true)
  assert.equal(page.data.previewEnding.idx, 0, '卡回到刚读的那一个')
})

test('无坐标的高级玩法收尾不依赖剧情块引用，地点与GPS玩法仍要求定位', async () => {
  const node = { nodeId: 223, vm: 0, lat: 0, lng: 0, hasAdvanced: true }
  const page = makePage({ blocks: [{ type: 'node', nodeId: 222, locationRequired: false }] }, [node])
  page.data.gpsOk = false
  page.data.advancedPlay = { show: true, node }
  const arrived = []
  let located = 0
  page.arrive = id => { arrived.push(id) }
  page.requestPlayLocation = () => { located++ }
  await page.onAdvancedReady({ detail: { nodeId: 223 } })
  assert.deepEqual(arrived, [223])
  assert.equal(located, 0)
  for (const target of [Object.assign({}, node, { lat: 31.2, lng: 121.4 }), Object.assign({}, node, { vm: 5 })]) {
    page.data.sheet.node = target
    page.onArriveTap()
  }
  assert.equal(located, 2)
  assert.deepEqual(arrived, [223], '不能放行真实地点或GPS任务')
})

test('正文打开时切到下一章，标题与正文一起更新，同章重建不重铺', () => {
  const page = makePage({ id: 227, blocks: [{ type: 'text', content: '上一章正文' }] }, [
    { nodeId: 223, chapterId: 227, done: true },
    { nodeId: 224, chapterId: 228, done: false, lat: 31.2, lng: 121.4 }
  ])
  page.data.chapterFull = true
  page.data.mode = 1
  page.data.chapterCards = [{ id: 228, title: '出生', blocks: [{ type: 'text', content: '下一章正文' }] }]
  page._routeState = { routeMode: 'LINEAR', attributes: {} }
  page.computeNext = () => ({ nodeId: 224 })
  page.buildJournal = () => {}
  page._measureDeal = () => {}
  const previousNextTick = wx.nextTick
  wx.nextTick = fn => fn()
  let rendered = 0
  const open = page.openChapterFull
  page.openChapterFull = () => { rendered++; open.call(page) }
  try {
    page.openChapterFull()
    rendered = 0
    page.rebuild()
    assert.equal(page.data.chapter.name, '出生')
    assert.equal(page.data.chapterParas[0].text, '下一章正文')
    assert.equal(rendered, 1)
    page.rebuild()
    assert.equal(rendered, 1, '同章定位/状态更新不能重新滚动正文')
  } finally { wx.nextTick = previousNextTick }
})

test('归零挑战呈现题组/弹球组件，章节已打开也不推进普通故事', () => {
  const node = { nodeId: 51, name: '挑战', hasAdvanced: true };
  for (const playKit of [{ qa: { mode: 'PICK', title: '题目', options: [] } }, { ballShake: { goal: 10, timed: false, seconds: 0 } }]) {
    const page = makePage({ blocks: [] }, [node]);
    page.data.advancedPlay = { show: true, node };
    page.data.chapterFull = true;
    page._themeChallengeNodeId = 51;
    page._mergeStoryVars = () => { throw new Error('挑战不可推进普通路线'); };
    let reads = 0; page.refreshThemeBackpack = () => { reads++; return Promise.resolve(true); };
    const challenge = { instanceId: 'one', progress: 6, goal: 10, success: false };
    page.onAdvancedSession({ detail: { sessionId: 51, nodeId: 51, ownerType: 2, version: 1, present: 'inline', readyForBase: true, playKit, challenge } });
    assert.equal(page.data.playKit.show, true);
    assert.equal(page.data.playKit.kit.type, playKit.qa ? 'qa' : 'ballshake');
    assert.deepEqual(page.data.playKit.kit.challenge, challenge);
    assert.equal(page.data.playKit.kit.challengeShared, true);
    assert.equal(page.data.playKit.readyForBase, false);
    assert.equal(reads, 1);
  }
});

test('挑战失败只显示服务端许可的明确重试；次数用完仍不自动重开', () => {
  for (const disabledReason of [null,'CHALLENGE_LIMIT']) {
    const node={nodeId:51,name:'挑战'};const page=makePage({blocks:[]},[node]);
    page.data.advancedPlay={show:true,node};page._themeChallengeNodeId=51;page.refreshThemeBackpack=()=>Promise.resolve(true);
    let starts=0;page.selectComponent=()=>({start(){starts++;}});
    page.onAdvancedSession({detail:{nodeId:51,sessionId:51,ownerType:2,status:'FAILED',canRetry:!disabledReason,version:1,challenge:{instanceId:'one',progress:6,goal:10,retry:true,disabledReason},playKit:{ballShake:{goal:10,timed:true,seconds:12}}}});
    assert.equal(starts,0);
    assert.equal(page.data.playKit.kit.terminal,true);
    assert.equal(page.data.playKit.kit.canRetry,!disabledReason);
    assert.equal(page.data.playKit.readyForBase,false);
  }
});

// #18:图片/音频块的 altText 要带进条目并做无障碍呈现 —— 图片 aria-label,音频可见且读屏可读的文字稿。
test('媒体块 altText 带进章节条目:图片做 aria-label,音频给出可见文字稿', () => {
  const page = makePage({ blocks: [
    { type: 'image', url: 'https://cdn.example/a.jpg', altText: ' 桥洞下的旧船 ' },
    { type: 'audio', url: 'https://cdn.example/b.mp3', altText: '“别回头。”' },
    { type: 'image', url: 'https://cdn.example/c.jpg' },
  ] }, [])
  page.openChapterFull()
  const media = page.data.chapterParas.filter(p => p.kind === 'image' || p.kind === 'audio')
  assert.deepEqual(media.map(p => [p.kind, p.altText]), [['image', '桥洞下的旧船'], ['audio', '“别回头。”'], ['image', '']])
  assert.equal(media.some(p => p.text), false, 'altText 不能当正文打出来')
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="chfull__img"[^>]*aria-label="\{\{item\.altText \|\| '章节配图'\}\}"/)
  assert.match(wxml, /<text wx:if="\{\{item\.altText\}\}" class="chfull__audio-transcript"[^>]*aria-label="音频文字稿：\{\{item\.altText\}\}">文字稿：\{\{item\.altText\}\}<\/text>/)
})

// 2026-10-27 生产实测(南京路附录):无块流的章节里开一个内嵌玩法(盲盒),旧兜底要求 _playKitNodeId 为空,
// 而开站必然记下它 ⇒ 玩法一律不铺,玩家只看见手记和「继续探索」,永远打不开。
test('无块流章节里正开着的内嵌玩法铺在故事流末尾', () => {
  const node = { nodeId: 322, name: '盲盒', hasAdvanced: true, vm: 5, done: false, lat: 31.2, lng: 121.4 }
  const page = makePage({ description: '附录收录了几个小彩蛋。' }, [
    { nodeId: 321, name: '结案', done: true, story: '档案已结案。' }, node])
  page.data.chapterFull = true
  page.data.advancedPlay = { show: true, node }
  page._playKitNodeId = 322
  page.onAdvancedSession({ detail: { nodeId: 322, sessionId: 9, version: 0, present: 'inline',
    playKit: { random: { drawCount: 1, drawn: [], pool: [{ id: 'a', label: '今晚看电影' }] } } } })
  assert.equal(page.data.playKit.kit.type, 'random')
  page.openChapterFull()
  const kinds = page.data.chapterParas.map(p => p.kind + (p.nodeId ? ':' + p.nodeId : ''))
  assert.equal(kinds[kinds.length - 1], 'kit:322', kinds.join(','))
  assert.equal(kinds.filter(k => k.startsWith('kit')).length, 1)
})

// 2026-10-27 生产实测:computeNext 只给精简卡片(没有坐标),rebuild 原样塞进 nextNode,
// openNextStopNav 拿不到 lat/lng 一律静默 return ——「导航过去」一直在,点了什么都不发生。
// 上面那条导航测试手搓了带坐标的 nextNode,所以一直没抓到;这里走真的 rebuild。
test('rebuild 出来的下一站带坐标与地址，「导航过去」真的交给微信地图', () => {
  const page = makePage({ id: 230, blocks: [] }, [
    { nodeId: 337, chapterId: 230, done: false, name: '延伸 · 四张老照片', address: '上海市虹口区提篮桥', lat: 31.2543, lng: 121.5091 }
  ])
  page.data.mode = 1
  page._routeState = { routeMode: 'LINEAR', attributes: {} }
  page.computeNext = () => ({ nodeId: 337, num: 1, name: '延伸 · 四张老照片', imgUrl: '' })
  page.buildJournal = () => {}
  page._measureDeal = () => {}
  const previousNextTick = wx.nextTick, previousOpen = wx.openLocation
  wx.nextTick = fn => fn()
  const opened = []
  wx.openLocation = o => opened.push(o)
  try {
    page.rebuild()
    assert.equal(page.data.nextNode.lat, 31.2543)
    assert.equal(page.data.nextNode.lng, 121.5091)
    page.openNextStopNav()
    assert.equal(opened.length, 1)
    assert.equal(opened[0].name, '延伸 · 四张老照片')
    assert.equal(opened[0].address, '上海市虹口区提篮桥')
  } finally { wx.nextTick = previousNextTick; wx.openLocation = previousOpen }
})

test('下一站是没有坐标的故事玩法时不摆「导航过去」', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="story-map__go" wx:if="\{\{nextNode\.lat && nextNode\.lng\}\}" bindtap="openNextStopNav"/)
})

test('章节翻转:进第二章时带上第一章完成页与三面;通关只在整个主题走完时放', () => {
  const page = makePage({ id: 52, name: '延伸 · 四个小任务', description: '舟山路一带。\n第二行' }, [
    { nodeId: 1, chapterId: 51, done: true }, { nodeId: 2, chapterId: 52, done: false },
  ])
  page.data.chapterCards = [{ id: 51, title: '提篮桥' }, { id: 52, title: '延伸 · 四个小任务' }]
  page.showChapterGate()
  const gate = page.data.chapterGate
  assert.equal(gate.show, true)
  assert.equal(gate.number, 2)
  assert.equal(gate.endInfo.number, '01')
  assert.equal(gate.info.desc, '舟山路一带。', '说明只取第一句')
  assert.equal(gate.faces.length, 3)

  let saved = 0
  page.saveFinishCard = () => { saved++ }
  page._sessionClock = { elapsedSeconds: () => 4044 }
  Object.assign(page.data, { allDone: false, topicName: '海上方舟', doneCount: 2, finishScore: 0 })
  page._openFinale(false)
  assert.equal(page.data.finale.show, false, '没走完不放通关过场')
  page.data.allDone = true
  page._openFinale(true)
  assert.equal(page.data.finale.show, false, '探店日不放')
  page._openFinale(false)
  assert.equal(page.data.finale.show, true)
  assert.equal(page.data.finale.info.title, '海上方舟')
  page.saveFinale()
  assert.equal(page.data.finale.show, false)
  assert.equal(saved, 1)
})
