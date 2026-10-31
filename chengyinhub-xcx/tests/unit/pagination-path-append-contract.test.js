const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

// 性能批4(P0-4/P1-6):分页「加载更多」改数组路径追加,定时器 onHide 停 UI 推送。
// 钉三层行为:
// ① appendByIndex 的路径键语义(全局下标、不动存量、非数组入参吞成空 patch);
// ② searchmap 增量建 marker 的 id/activityId 全局偏移与无坐标行跳过;
// ③ play 保留现运行栈会话语义，不重新引入已退役 HUD 心跳;
//    外加各分页点「整表 concat 已绝迹、路径追加已就位」的源码契约。

global.getApp = () => ({
  globalData: {},
  getUserID: () => 1,
  sendRequest: () => {},
  getRequestErrorMessage: () => '',
  getPageSize: () => 10,
  getTotalPage: () => 1,
})
global.wx = {
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
  getStorageSync: () => null,
  setStorageSync: () => {},
  removeStorageSync: () => {},
  showToast: () => {},
  hideLoading: () => {},
  showLoading: () => {},
  createSelectorQuery: () => ({
    selectAll: () => ({ boundingClientRect: () => {} }),
    select: () => ({ boundingClientRect: () => {}, scrollOffset: () => {} }),
    exec: () => {},
  }),
  createInnerAudioContext: () => ({
    play() {}, pause() {}, stop() {}, destroy() {},
    onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {}, onWaiting() {}, onCanplay() {},
  }),
}

let playConfig = null
let searchmapConfig = null
let lastPageConfig = null
global.Page = (config) => { lastPageConfig = config }
global.Component = () => {}

const { appendByIndex } = require('../../utils/list-append.js')
require('../../pages/play/index.js')
playConfig = lastPageConfig
require('../../pages/searchmap/index.js')
searchmapConfig = lastPageConfig

function fakeSetDataStore(page) {
  page.__patches = []
  page.setData = function (patch, callback) {
    page.__patches.push(patch)
    // 同时模拟原生 setData 的数组路径写回，防止“键正确但列表值错误”漏过。
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
      let target = page.data
      for (let i = 0; i < parts.length - 1; i++) {
        if (target[parts[i]] == null) target[parts[i]] = /^\d+$/.test(parts[i + 1]) ? [] : {}
        target = target[parts[i]]
      }
      target[parts[parts.length - 1]] = value
    }
    if (callback) callback()
  }
  return page
}

// ---------- ① helper 单元 ----------

test('appendByIndex 以全局下标生成路径键,只含新增行', () => {
  const patch = appendByIndex('list', 2, [{ id: 3 }, { id: 4 }])
  assert.deepEqual(Object.keys(patch), ['list[2]', 'list[3]'])
  assert.equal(patch['list[3]'].id, 4)
})

test('appendByIndex 空页/非数组入参都归零 patch,绝不回传整表', () => {
  assert.deepEqual(appendByIndex('list', 5, []), {})
  assert.deepEqual(appendByIndex('list', 5, null), {})
  assert.deepEqual(appendByIndex('list', undefined, [{ id: 1 }]), { 'list[0]': { id: 1 } })
})

// ---------- ② searchmap 增量 marker ----------

function makeSmap() {
  const page = Object.assign({}, searchmapConfig)
  page.data = { list: [], activityMarkers: [], noCoordCount: 0, merchantMarkers: [] }
  fakeSetDataStore(page)
  page.__rebuilds = 0
  page.rebuildMarkers = function () { page.__rebuilds += 1 }
  return page
}

test('searchmap 追加 marker 用全局列表下标:id/activityId 偏移正确、无坐标行不落点', () => {
  const page = makeSmap()
  page.data.activityMarkers = [{ id: 1, activityId: 0 }, { id: 2, activityId: 2 }]
  const newActs = [
    { name: 'A', latitude: 30, longitude: 120 },        // 全局下标 5 ⇒ id 6 / activityId 5
    { name: 'B' /* 无坐标 */ },                          // 跳过,但占一个 list 下标
    { name: 'C', latitude: 31, longitude: 121, mapPointKind: 'topic' }, // 全局下标 7 ⇒ id 8
  ]
  page.appendMarkersFromActivities(newActs, 5)
  assert.equal(page.__patches.length, 1)
  const patch = page.__patches[0]
  assert.deepEqual(Object.keys(patch), ['activityMarkers[2]', 'activityMarkers[3]'])
  assert.equal(patch['activityMarkers[2]'].id, 6)
  assert.equal(patch['activityMarkers[2]'].activityId, 5)
  assert.equal(patch['activityMarkers[3]'].id, 8)
  assert.equal(patch['activityMarkers[3]'].activityId, 7)
  assert.equal(page.data.activityMarkers.length, 4)
  assert.equal(page.data.activityMarkers[2].activityId, 5)
  assert.equal(page.data.activityMarkers[3].activityId, 7)
  assert.equal(page.__rebuilds, 1, '追加后必须仍走一次合并图层重建')
})

test('searchmap 整批无坐标时不发 setData,只重建合并图层', () => {
  const page = makeSmap()
  page.appendMarkersFromActivities([{ name: 'X' }], 0)
  assert.deepEqual(page.__patches, [])
  assert.equal(page.__rebuilds, 1)
})

// ---------- ③ 当前 play 会话不能复活已经退役的 HUD 心跳 ----------

test('play 保留当前会话的暂停语义，不重新挂已退役 HUD 定时器', () => {
  const origSet = global.setInterval
  const handles = []
  global.setInterval = (cb, ms) => { const h = { cb, ms }; handles.push(h); return h }
  try {
    const page = Object.assign({}, playConfig)
    page.data = Object.assign({}, playConfig.data, { total: 5, isMock: true })
    fakeSetDataStore(page)
    const clock = {
      paused: false,
      start() {}, pause() { this.paused = true },
      isPaused() { return this.paused }, elapsedSeconds() { return 42 },
    }
    page._sessionClock = clock
    page._ensureSessionClock()
    assert.equal(page._sessionClock, clock, '保留正在使用的当前会话')
    assert.equal(handles.length, 0, '现运行栈没有旧 HUD 消费方，不复活每秒 interval')
    page.onHide()
    assert.equal(clock.paused, true, '隐藏仍走当前会话暂停链路')
  } finally {
    global.setInterval = origSet
  }
})

// ---------- 静态契约:整表 concat 绝迹 + 路径追加就位 ----------

function readSource(rel) {
  return fs.readFileSync(path.resolve(__dirname, '../../', rel), 'utf8')
}

test('分页点整表 concat 已绝迹,改为路径追加', () => {
  const banned = [
    ['pages/index/index.js', /bottomTopicList:\s*that\.data\.bottomTopicList\.concat/],
    ['pages/index/index.js', /bottomActivityList:\s*that\.data\.bottomActivityList\.concat/],
    ['pages/square/detail/index.js', /that\.data\.list\.concat\(newlist\)/],
    ['pages/searchmap/index.js', /that\.data\.list\.concat\(newList\)/],
    ['pages/merchant/customer/index.js', /that\.data\.rows\.concat\(list\)/],
    ['subpackageMember/components/cy/profile/index.js', /that\.data\.list\.concat\(newlist\)/],
  ]
  for (const [file, re] of banned) {
    assert.doesNotMatch(readSource(file), re, `${file} 仍在分页里整表 concat`)
  }
  const required = [
    ['pages/index/index.js', /appendByIndex\('bottomTopicList',\s*that\.data\.bottomTopicList\.length/],
    ['pages/index/index.js', /appendByIndex\('bottomActivityList',\s*that\.data\.bottomActivityList\.length/],
    ['pages/square/detail/index.js', /appendByIndex\('list',\s*oldLen,\s*newlist\)/],
    ['pages/searchmap/index.js', /appendByIndex\('list',\s*oldLen,\s*newList\)/],
    ['pages/searchmap/index.js', /that\.appendMarkersFromActivities\(newList,\s*oldLen\)/],
    ['pages/merchant/customer/index.js', /rowsPatch\['rows\[' \+ \(oldLen \+ i\)/],
    ['subpackageMember/components/cy/profile/index.js', /patch\['list\[' \+ \(oldLen \+ i\)/],
  ]
  for (const [file, re] of required) {
    assert.match(readSource(file), re, `${file} 缺少路径追加写法`)
  }
})

test('roam 隐藏期只短接 UI 推送,不动 GPS 会话语义', () => {
  const src = readSource('pages/roam/index.js')
  assert.match(src, /onHide\(\)\s*\{[\s\S]{0,200}?this\._hidden = true;/, 'onHide 先置 _hidden')
  assert.match(src, /_syncStats\(\)\s*\{[\s\S]{0,200}?if \(this\._hidden\) return;/, '_syncStats 隐藏期短路')
  assert.match(src, /onShow\(\)\s*\{[\s\S]{0,80}?this\._hidden = false;[\s\S]{0,60}?if \(this\._clock\) this\._syncStats\(\);/, 'onShow 复位并立即补一次同步')
  // 时钟本身不许被 onHide 停:停表=吞掉锁屏期真实行程(X10 裁决)
  assert.match(src, /if \(!this\._realOn && this\._sessionClock && !this\._sessionClock\.isPaused\(\)\)/)
})
