const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')

const PLAY = path.resolve(__dirname, '../../pages/play/index.js')
const source = fs.readFileSync(PLAY, 'utf8')

// 故事流取代旧 opacity/scale/blur renderer 后，性能边界仍须成立：
// 真实几何缺失不写；只发送显形变化路径；重复位置/已到位段落不重写；
// scroll 洪峰共用去抖，旧章节测量不能污染新章节。断言运行真实页面方法。
let pageConfig
const app = { globalData: {}, getUserID: () => 1, sendRequest: () => {} }
global.getApp = () => app
global.Page = config => { pageConfig = config }
global.wx = {
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
  getStorageSync: () => null,
  setStorageSync() {}, removeStorageSync() {}, showToast() {}, vibrateShort() {},
  createSelectorQuery() {
    return {
      in(page) { this.page = page; return this },
      select() { return this }, selectAll() { return this }, boundingClientRect() { return this },
      exec(callback) {
        const page = this.page
        page.__queries++
        if (page.__deferQuery) page.__queryCallbacks.push(callback)
        else callback(page.__geometry)
      },
    }
  },
  createInnerAudioContext: () => ({
    play() {}, pause() {}, stop() {}, destroy() {},
    onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {}, onWaiting() {}, onCanplay() {},
  }),
}

function makePage(code = source) {
  pageConfig = null
  const compiled = new Module(PLAY, module)
  compiled.filename = PLAY
  compiled.paths = Module._nodeModulePaths(path.dirname(PLAY))
  compiled._compile(code, PLAY)
  assert.ok(pageConfig, '真实玩法页必须注册')
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.data.chapterFull = true
  page.data.chapStory.barAway = false
  page.data.chapterParas = [
    { kind: 'text', text: '第一段', in: false, dir: 'up', d: 0 },
    { kind: 'audio', url: '/chapter-audio.mp3', in: false, dir: 'up', d: 0 },
    { kind: 'game', nodeId: 7, config: { profile: { required: true } }, in: false, dir: 'up', d: 0 },
  ]
  page.__patches = []
  page.__queries = 0
  page.__queryCallbacks = []
  page.__reading = []
  page.__inline = []
  page.__geometry = [
    { top: 100, bottom: 500, height: 400 },
    [
      { top: 220, bottom: 280, height: 60 },
      { top: 1200, bottom: 1260, height: 60 },
      { top: 320, bottom: 380, height: 60 },
    ],
  ]
  page.setData = function (patch, callback) {
    page.__patches.push(patch)
    // 原生 setData 路径同步写回，重复帧负控必须读到已变更的字段。
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let target = page.data
      for (let i = 0; i < parts.length - 1; i++) target = target[parts[i]]
      target[parts[parts.length - 1]] = value
    }
    if (callback) callback()
  }
  page._saveReadingPos = line => page.__reading.push(line)
  page._syncStoryScene = () => {}
  page._autoInline = nodeId => page.__inline.push(nodeId)
  return page
}

function assertSmallRevealPatch(page, prefix = 'chapterParas') {
  assert.equal(page.__patches.length, 1)
  const patch = page.__patches[0]
  const pattern = prefix === 'chapterParas' ? /^chapterParas\[\d+\]\.(in|dir|d)$/ : /^chapStory\.lines\[\d+\]\.(in|dir|d)$/
  assert.ok(Object.keys(patch).length > 0)
  for (const key of Object.keys(patch)) assert.match(key, pattern, `非显形路径: ${key}`)
  assert.doesNotMatch(JSON.stringify(patch), /第一段|chapter-audio|"required"/)
}

function assertRepeatSettleNoWrite(page) {
  page._settleStory()
  const writes = page.__patches.length
  page._settleStory()
  assert.equal(page.__patches.length, writes, '已到位的段落不能再发 setData，空 patch 也不应发送')
}

test('故事层关闭的旧 scroll 与缺失几何都不写 UI', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = makePage()
  page.data.chapterFull = false
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  t.mock.timers.tick(100)
  assert.equal(page.__queries, 0)
  assert.deepEqual(page.__patches, [])
  page.data.chapterFull = true
  page.__geometry = [null, []]
  page._settleStory()
  assert.deepEqual(page.__patches, [])
})

test('只发新故事 in/dir/d 变化路径，屏外段与正文/玩法配置不重传', () => {
  const page = makePage()
  page._storyDir = 'down'
  page._settleStory()
  assertSmallRevealPatch(page)
  assert.deepEqual(Object.keys(page.__patches[0]).sort(), [
    'chapterParas[0].dir', 'chapterParas[0].in',
    'chapterParas[2].d', 'chapterParas[2].dir', 'chapterParas[2].in',
  ])
  assert.equal(page.data.chapterParas[0].in, true)
  assert.equal(page.data.chapterParas[2].d, 0.35, '同批入场仍逐段错开(2026-10-29 业主:显形放慢,50ms → 350ms)')
  assert.equal(page.data.chapterParas[1].in, false)
  assert.deepEqual(page.data.chapterParas[2].config, { profile: { required: true } })
})

test('同一几何再次 settle 不额外 setData，屏外复位后回来仍可重演', () => {
  const page = makePage()
  assertRepeatSettleNoWrite(page)
  page.__geometry[1][0] = { top: -80, bottom: -20, height: 60 }
  page._settleStory()
  assert.equal(page.data.chapterParas[0].in, false)
  assert.equal(page.data.chapterParas[0].dir, 'down')
  page.__geometry[1][0] = { top: 220, bottom: 280, height: 60 }
  page._settleStory()
  assert.equal(page.data.chapterParas[0].in, true)
})

test('相同位置重复 scroll 不额外写 UI 或重发测量', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = makePage()
  page._storyAt = Date.now()
  page._storyTop = 0
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  assert.deepEqual(page.__patches, [{ 'chapStory.barAway': true }])
  assert.equal(page.__queries, 0)
  t.mock.timers.tick(70)
  assert.equal(page.__queries, 1)
  const writes = page.__patches.length
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  t.mock.timers.tick(100)
  assert.equal(page.__queries, 1)
  assert.equal(page.__patches.length, writes, '静止位置重放不能把底栏收起/弹回一遍')
})

test('真实位移洪峰保留最后一次去抖，逐帧只记私有字段', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = makePage()
  page._storyAt = Date.now()
  page._storyTop = 0
  page.onChapterScroll({ detail: { scrollTop: 100 } })
  t.mock.timers.tick(20)
  page.onChapterScroll({ detail: { scrollTop: 240 } })
  t.mock.timers.tick(69)
  assert.equal(page.__queries, 0)
  assert.equal(page.__patches.length, 1, '洪峰期间只收起一次底栏，不逐帧回传正文')
  t.mock.timers.tick(1)
  assert.equal(page.__queries, 1)
  assert.equal(page._storyTop, 240)
  assert.equal(Object.hasOwn(page.data, '_storyTop'), false)
})

test('隐藏清掉未完成 settle 后，同位置返回仍能重新测量', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = makePage()
  page.data.chapterFull = false
  page.data.chapStory.show = true
  page.data.chapStory.lines = page.data.chapterParas
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  for (const key of ['_stopStoryDust', '_clearPackTimers', 'pauseAudio', '_stopShopNpcVoice', '_stopLeadPoll', '_pauseSessionClock', '_persistRunSession']) page[key] = () => {}
  page.onHide()
  t.mock.timers.tick(100)
  assert.equal(page.__queries, 0, '隐藏期旧 timer 必须已取消')
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  t.mock.timers.tick(100)
  assert.equal(page.__queries, 1, '不能把被取消的测量当成重复位置已处理')
  assert.equal(page.data.chapStory.barAway, false)
})

test('自由探索 chapStory 也用路径显形，减弱动态仍显示屏外内容', () => {
  const page = makePage()
  page.data.chapterFull = false
  page.data.chapStory.show = true
  page.data.chapStory.lines = page.data.chapterParas
  page.data.reducedMotion = true
  page._settleStory()
  assertSmallRevealPatch(page, 'chapStory.lines')
  assert.equal(page.data.chapStory.lines[1].in, true)
  assert.deepEqual(page.__reading, [], '自由探索不误写章节存档')
})

test('旧章节测量回包不能显形新段落或启动旧玩法，后续测量仍可工作', () => {
  const page = makePage()
  page.data.chapterParas[2].auto = true
  page.__deferQuery = true
  page._settleStory()
  const old = page.__queryCallbacks.shift()
  page.data.chapterParas = [{ kind: 'text', text: '新章节', in: false, dir: 'up', d: 0 }]
  old(page.__geometry)
  assert.deepEqual(page.__patches, [])
  assert.deepEqual(page.__reading, [])
  assert.deepEqual(page.__inline, [])
  page._settleStory()
  page.__queryCallbacks.shift()([page.__geometry[0], [page.__geometry[1][0]]])
  assert.equal(page.data.chapterParas[0].in, true)
  assert.equal(page.data.chapterParas[0].text, '新章节')
})

test('显形测量仍保留自动玩法、阅读位置与终章反转链', () => {
  const page = makePage()
  page.data.chapterParas[2].auto = true
  page.data.chapterParas[0].kind = 'reveal'
  let remeasured = 0
  page.measureChapter = () => { remeasured++ }
  page._settleStory()
  assert.deepEqual(page.__inline, [7])
  assert.equal(page.__reading[0], page.data.chapterParas[0])
  assert.equal(page.data.storyChat, true)
  assert.equal(remeasured, 1)
})

test('负控：恢复全数组发送必须被小 patch 断言判红', () => {
  const changed = source.replace('        that.setData(patch);\n      });\n  },\n\n  /** 分层漂浮粒子。',
    "        that.setData(chapterFull ? { chapterParas: next } : { 'chapStory.lines': next });\n      });\n  },\n\n  /** 分层漂浮粒子。")
  assert.notEqual(changed, source, '负控须真实改变源码')
  const page = makePage(changed)
  page._settleStory()
  assert.throws(() => assertSmallRevealPatch(page), assert.AssertionError)
})

test('负控：摘掉已到位短路必须被重复 settle 判红', () => {
  const changed = source.replace('!Object.keys(patch).length || chapterFull !== that.data.chapterFull', 'false || chapterFull !== that.data.chapterFull')
  assert.notEqual(changed, source, '负控须真实摘掉短路')
  assert.throws(() => assertRepeatSettleNoWrite(makePage(changed)), assert.AssertionError)
})

test('负控：摘掉重复位置守卫必须被重复 scroll 判红', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const changed = source.replace('if (this._storyAt != null && top === this._storyTop) return;', 'if (false) return;')
  assert.notEqual(changed, source, '负控须真实摘掉守卫')
  const page = makePage(changed)
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  t.mock.timers.tick(70)
  const writes = page.__patches.length
  page.onChapterScroll({ detail: { scrollTop: 120 } })
  assert.throws(() => assert.equal(page.__patches.length, writes), assert.AssertionError)
})
