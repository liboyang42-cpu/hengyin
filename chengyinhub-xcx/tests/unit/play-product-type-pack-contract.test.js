const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const PLAY = path.resolve(__dirname, '../../pages/play/index.js')

function setByPath(target, keyPath, value) {
  const parts = keyPath.split('.')
  let cursor = target
  parts.slice(0, -1).forEach(part => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

// Load the real Page with host APIs only, without mounting a UI renderer.
// Keep normalization, rebuild/allDone and _openStory real; isolate unrelated
// clocks, HUD/network refreshes, layout measurement and the finish renderer.
function loadPage(t) {
  const requests = []
  const visibleStates = []
  const calls = []
  const errors = []
  const timers = new Set()
  let timerStarts = 0
  let haptics = 0
  const storage = new Map()
  const app = {
    globalData: { user_id: 9, features: {} },
    getUserID: () => 9,
    getAvatar: () => '',
    isDevEnv: () => false,
    sendRequest(options) { requests.push(options) },
  }
  const wxHost = {
    getStorageSync: key => storage.get(key) || '',
    setStorageSync(key, value) { storage.set(key, value) },
    removeStorageSync(key) { storage.delete(key) },
    getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    nextTick(callback) { callback() },
    vibrateShort() { haptics++ },
  }
  const savedGlobals = ['Page', 'getApp', 'wx'].map(key => ({
    key, present: Object.prototype.hasOwnProperty.call(global, key), value: global[key],
  }))
  t.after(() => {
    timers.forEach(id => { clearTimeout(id); clearInterval(id) })
    timers.clear()
    savedGlobals.forEach(({ key, present, value }) => {
      if (present) global[key] = value
      else delete global[key]
    })
  })
  const schedule = (repeat, callback, delay, ...args) => {
    timerStarts++
    const id = (repeat ? setInterval : setTimeout)(callback, delay, ...args)
    timers.add(id)
    return id
  }
  const cancel = id => {
    clearTimeout(id)
    clearInterval(id)
    timers.delete(id)
  }
  let config
  global.Page = value => { config = value }
  global.getApp = () => app
  global.wx = wxHost
  const localRequire = id => require(id.startsWith('.') ? path.resolve(path.dirname(PLAY), id) : id)
  const factory = new Function('Page', 'getApp', 'wx', 'require', 'module', 'exports',
    'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'console', fs.readFileSync(PLAY, 'utf8'))
  factory(global.Page, global.getApp, wxHost, localRequire, { exports: {} }, {},
    (...args) => schedule(false, ...args), cancel,
    (...args) => schedule(true, ...args), cancel,
    { ...console, error: (...args) => errors.push(args) })
  assert.ok(config, 'real play Page must be registered')
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.data.topicId = '10'
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => setByPath(page.data, key, value))
    visibleStates.push({ loading: page.data.loading, packOpen: page.data.packOpen, headIn: page.data.headIn })
    if (callback) callback()
  }
  for (const method of [
    '_applyLook', '_probeCasebook', '_loadCompletedHistory', '_ensureSessionClock',
    '_syncIdleStats', '_syncRunState', '_restoreRunSession', 'loadTeamProgress',
    '_loadPlayerGameModule', 'loadHudCharacter', 'loadSessionStats',
    '_refreshNavRim', '_measureDeal', 'buildJournal',
  ]) page[method] = () => {}
  page._consumePendingStart = () => calls.push('loaded')
  page._startThemeBgm = () => calls.push('bgm')
  const openStory = page._openStory
  page._openStory = function () {
    calls.push('story')
    return openStory.call(this)
  }
  page.openFinish = function () {
    calls.push('finish')
    this.setData({ screen: 'finish' })
  }
  return {
    page, requests, visibleStates, calls, errors,
    timerStarts: () => timerStarts,
    haptics: () => haptics,
  }
}

function payload(extra = {}) {
  return {
    registered: true, topicId: 10, topicName: 'Pack regression', mode: 2,
    chapters: [{ id: 7, name: 'Opening', blocks: [{ type: 'text', text: 'Actual chapter content' }] }],
    nodes: [{ nodeId: 1, chapterId: 7, name: 'First stop', sortId: 1, done: false }],
    ...extra,
  }
}

async function deliverNodes(ctx, data) {
  ctx.page.loadData(true)
  assert.deepEqual(ctx.requests.map(request => request.url), ['/api/play/nodes'])
  ctx.requests[0].success({ code: 200, data })
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(ctx.errors, [], 'success post-processing must not silently throw')
  assert.equal(ctx.page.data.emptyTip, '')
  assert.equal(ctx.page.data.loading, false)
  assert.ok(ctx.calls.includes('loaded'), 'real success callback must reach its post-processing tail')
  assert.deepEqual(ctx.requests.map(request => request.url), ['/api/play/nodes'], 'product type must use the nodes payload, not a topic lookup')
  assert.equal(ctx.page.data.mode, data.mode, 'product type must not rewrite gameplay mode')
  assert.equal(ctx.page.data.serverMode, data.mode)
}

function assertFirstVisiblePack(ctx, open) {
  const firstVisible = ctx.visibleStates.find(state => state.loading === false)
  assert.ok(firstVisible, 'success must expose a visible content state')
  assert.equal(firstVisible.packOpen, open, 'pack visibility must already be correct when loading first becomes false')
  assert.equal(firstVisible.headIn, open, 'header must not wait for a later callback or pack animation')
  assert.equal(ctx.page.data.packOpen, open)
  assert.equal(ctx.page.data.headIn, open)
}

function assertStory(ctx, expected) {
  assert.equal(ctx.calls.filter(call => call === 'story').length, expected ? 1 : 0)
  assert.equal(ctx.page.data.storyIntro.show, expected, 'real _openStory must expose chapter content entry')
  if (expected) {
    assert.equal(ctx.page.data.storyIntro.title, 'Pack regression')
    assert.equal(ctx.page.data.chapter.blocks[0].text, 'Actual chapter content')
  }
}

test('mode 2 / numeric productType 2 keeps the unopened pack and does not enter story', async t => {
  const ctx = loadPage(t)
  await deliverNodes(ctx, payload({ productType: 2 }))
  assertFirstVisiblePack(ctx, false)
  assertStory(ctx, false)
  assert.ok(!ctx.calls.includes('finish'))
})

for (const productType of [1, 0, 3, -1, null]) {
  test(`mode 2 / explicit productType ${productType} bypasses the pack before first visible content`, async t => {
    const ctx = loadPage(t)
    await deliverNodes(ctx, payload({ productType }))
    assertFirstVisiblePack(ctx, true)
    assertStory(ctx, true)
    assert.ok(!ctx.calls.includes('finish'))
  })
}

for (const productType of [1, 0, 3, null]) {
  test(`completed mode 2 / productType ${productType} uses finish rather than restarting story`, async t => {
    const ctx = loadPage(t)
    await deliverNodes(ctx, payload({ productType, nodes: [{ nodeId: 1, chapterId: 7, name: 'Done stop', done: true }] }))
    assertFirstVisiblePack(ctx, true)
    assert.equal(ctx.page.data.allDone, true, 'real rebuild must derive completion from loaded nodes')
    assert.equal(ctx.calls.filter(call => call === 'finish').length, 1)
    assert.equal(ctx.page.data.screen, 'finish')
    assertStory(ctx, false)
  })
}

for (const mode of [1, 2]) {
  test(`missing productType preserves the existing mode ${mode} entry without a topic request`, async t => {
    const ctx = loadPage(t)
    await deliverNodes(ctx, payload({ mode }))
    assertFirstVisiblePack(ctx, false)
    assertStory(ctx, mode === 1)
  })
}

test('explicit undefined productType retains the legacy mode 2 pack path', async t => {
  const ctx = loadPage(t)
  await deliverNodes(ctx, payload({ productType: undefined }))
  assertFirstVisiblePack(ctx, false)
  assertStory(ctx, false)
})

test('numeric productType 2 does not change the existing mode 1 direct story path', async t => {
  const ctx = loadPage(t)
  await deliverNodes(ctx, payload({ mode: 1, productType: 2 }))
  assertFirstVisiblePack(ctx, false)
  assertStory(ctx, true)
})

test('nonfree load makes real tap and drag pack entries inert without haptics or timers', async t => {
  const ctx = loadPage(t)
  await deliverNodes(ctx, payload({ productType: 1 }))
  assertFirstVisiblePack(ctx, true)
  assert.equal(ctx.page.data.reducedMotion, false, 'exercise packOpen guard, not reduced motion')
  const before = {
    timers: ctx.timerStarts(), haptics: ctx.haptics(), calls: ctx.calls.slice(),
    patches: ctx.visibleStates.length,
  }
  ctx.page.openPack()
  ctx.page.onPackTouchStart({ touches: [{ clientX: 10 }] })
  ctx.page.onPackTouchMove({ touches: [{ clientX: 200 }] })
  ctx.page.onPackTouchEnd({ type: 'touchend' })
  assert.equal(ctx.timerStarts(), before.timers)
  assert.equal(ctx.haptics(), before.haptics)
  assert.deepEqual(ctx.calls, before.calls, 'guard must also precede starting theme BGM')
  assert.equal(ctx.visibleStates.length, before.patches, 'guarded entries must not alter tear/pack state')
  assert.ok(!ctx.page._tearActive)
  assert.ok(!ctx.page.data.packShake)
  assert.ok(!ctx.page.data.packTear)
  assert.ok(!ctx.page.data.packBurst)
})

for (const scenario of [
  { label: 'free exploration product', mode: 2, productType: 2, open: false, story: false },
  { label: 'nonfree mode 2', mode: 2, productType: 1, open: true, story: true },
  { label: 'explicit null', mode: 2, productType: null, open: true, story: true },
  { label: 'legacy mode 2', mode: 2, open: false, story: false },
  { label: 'legacy mode 1', mode: 1, open: false, story: true },
  { label: 'free preview with explicit startStory', mode: 2, productType: 2, startStory: true, open: false, story: true },
  { label: 'legacy preview with explicit startStory', mode: 2, startStory: true, open: false, story: true },
]) {
  test(`applyPreviewData preserves ${scenario.label} entry`, t => {
    const ctx = loadPage(t)
    ctx.page.data.isPreview = true
    const { label, open, story, ...extra } = scenario
    ctx.page.applyPreviewData(payload(extra))
    assertFirstVisiblePack(ctx, open)
    assertStory(ctx, story)
    assert.equal(ctx.page.data.mode, scenario.mode)
    assert.equal(ctx.page.data.serverMode, scenario.mode)
    assert.deepEqual(ctx.requests, [], 'preview must remain local')
    assert.deepEqual(ctx.errors, [])
  })
}
