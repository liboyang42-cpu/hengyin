// 第十三轮 m1 P1:互斥组(线性路线里两站只能完成一站)的第二站玩家到不了、打不开、走完后屏幕空白。
// 行为测试直接跑 pages/play/index.js 的真实方法(与 k10-immersive-audio 同一套最小宿主)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')

function audioFactory(list) {
  return () => {
    const handlers = {}
    const ctx = {
      src: '', loop: false, volume: 1, paused: true, plays: 0, stops: 0, destroyed: false,
      play() { this.plays += 1; this.paused = false; (handlers.play || []).forEach(f => f()) },
      pause() { this.paused = true; (handlers.pause || []).forEach(f => f()) },
      stop() { this.stops += 1; this.paused = true; (handlers.stop || []).forEach(f => f()) },
      destroy() { this.destroyed = true },
      end() { this.paused = true; (handlers.ended || []).forEach(f => f()) },
    }
    ;['Play', 'Pause', 'Stop', 'Ended', 'Error', 'Waiting', 'Canplay'].forEach((n) => {
      ctx['on' + n] = (f) => { (handlers[n.toLowerCase()] = handlers[n.toLowerCase()] || []).push(f) }
    })
    list.push(ctx)
    return ctx
  }
}

function loadPlay() {
  const requests = []
  const audios = []
  global.getApp = () => ({
    globalData: { user_id: 9, statusBarHeight: 20, navBarHeight: 44, features: {} },
    isDevEnv: () => false, getUserID: () => 9, getAvatar: () => '',
    sendRequest: (options) => { requests.push(options) },
  })
  const storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key) || '', setStorageSync(key, value) { storage.set(key, value) },
    getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    getMenuButtonBoundingClientRect: () => ({ left: 300 }),
    showLoading() {}, hideLoading() {}, showToast() {}, setKeepScreenOn() {},
    createInnerAudioContext: audioFactory(audios),
    createSelectorQuery: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }) }),
  }
  let config = null
  global.Page = (cfg) => { config = cfg }
  const abs = path.join(ROOT, 'pages/play/index.js')
  const src = fs.readFileSync(abs, 'utf8')
  const dir = path.dirname(abs)
  const localRequire = (id) => require(id.startsWith('.') ? path.resolve(dir, id) : id)
  new Function('Page', 'getApp', 'wx', 'require', 'module', 'exports', src)(global.Page, global.getApp, global.wx, localRequire, { exports: {} }, {})
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = (patch, cb) => {
    Object.keys(patch || {}).forEach((key) => {
      const parts = key.split('.'); let o = page.data
      for (let i = 0; i < parts.length - 1; i++) { o[parts[i]] = o[parts[i]] || {}; o = o[parts[i]] }
      o[parts[parts.length - 1]] = patch[key]
    })
    if (cb) cb()
  }
  return { page, audios, requests }
}


const bag = (quests) => ({ sessionId: 7, quests })
const node = (id, extra) => Object.assign({ nodeId: id, chapterId: 50, vm: 5, hasAdvanced: true, done: false, lat: 31.23, lng: 121.47 }, extra)

test('任务局里还差到达的另一站也在定位自动到达的候选里;普通主题不加', () => {
  const { page } = loadPlay()
  page.data.nodes = [node(85), node(86), node(87, { done: true })]
  page.data.themeBag = bag([{ nodeId: 85, disabledReason: 'NOT_ARRIVED' }, { nodeId: 86, disabledReason: 'NOT_ARRIVED' }, { nodeId: 87, disabledReason: 'NOT_ARRIVED' }])
  assert.deepEqual(page._questArrivalCandidates(page.data.nodes[0]).map(n => n.nodeId), [86], '下一站之外、未完成、还差到达的站')
  page.data.themeBag = bag([{ nodeId: 86, disabledReason: '' }])
  assert.deepEqual(page._questArrivalCandidates(null).map(n => n.nodeId), [], '已到达的不再盯')
  page.data.themeBag = {}
  assert.deepEqual(page._questArrivalCandidates(null), [], '没有任务局不加候选')
  const src = fs.readFileSync(path.join(ROOT, 'pages/play/index.js'), 'utf8')
  assert.match(src, /for \(const node of \[primary\]\.concat\(that\._questArrivalCandidates\(primary\)\)\)/)
})

function chapterPage(playKitNodeId) {
  const { page } = loadPlay()
  page.data.chapter = { id: 50, blocks: [{ type: 'text', content: '巷口分成两条路' }, { type: 'node', nodeId: 85 }] }
  page.data.nodes = [node(85), node(86)]
  page._inlineKit = () => ({ present: 'inline' })
  page._playKitNodeId = playKitNodeId
  page.measureChapter = () => {}; page._resumeStory = () => {}; page._syncStoryScene = () => {}
  page.openChapterFull()
  return page.data.chapterParas.map(p => p.kind + (p.nodeId ? ':' + p.nodeId : ''))
}

test('互斥组另一站正在玩时,玩法铺在故事流截断处;后面的剧情照旧不露', () => {
  assert.deepEqual(chapterPage(86), ['text', 'kit:86'])
  assert.deepEqual(chapterPage(85), ['text', 'kit:85'])
  assert.deepEqual(chapterPage(99), ['text'], '不是本章的站不铺')
})

test('线性路线服务端说本局完成就是完成,没走的互斥站不挡通关', () => {
  const src = fs.readFileSync(path.join(ROOT, 'pages/play/index.js'), 'utf8')
  assert.match(src, /: branchFinished \|\| nodes\.length > 0 && nodes\.every\(\(x\) => x\.done\)\);/)
})
