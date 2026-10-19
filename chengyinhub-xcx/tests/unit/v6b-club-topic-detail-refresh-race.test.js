// V6b 验收（CL-48 / CX-12 详情部分，F-R2S-16）：pages/club/topic-detail 的 fetchDetail 要有请求序号，
// onShow 与重试并发时旧回包丢弃；已加载内容后刷新失败不能整页变错误态，并给非阻塞的重试提示。
// 前两条是当前 master 已成立的保证（负控：删掉 fetchDetail 里 seq === this._detailSeq 的比较，第一条必红）。
// 标 todo 的两条是 master 上复现出的缺陷，跑得到但不让套件变红，修复后去掉 todo 即成回归用例。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const PAGE_PATH = path.resolve(__dirname, '../../pages/club/topic-detail/index.js')

function loadPage() {
  const requests = []
  const toasts = []
  const identity = { member: 9 }
  let pageConfig = null
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => identity.member,
    sendRequest: (options) => { requests.push(options) },
    tips: () => {},
  })
  global.Page = (config) => { pageConfig = config }
  global.getCurrentPages = () => [{}]
  global.wx = {
    showToast: (o) => { toasts.push(o && o.title) }, navigateTo: () => {}, getStorageSync: () => '',
    setStorageSync: () => {}, removeStorageSync: () => {}, getSystemInfoSync: () => ({}),
  }
  delete require.cache[PAGE_PATH]
  require(PAGE_PATH)
  const vm = Object.assign({}, pageConfig, { setData(patch) { Object.assign(this.data, patch) } })
  vm.data = Object.assign({}, pageConfig.data)
  vm.loadProjection = function () {}
  vm._topicId = 88; vm._clubId = 9
  vm._manageStats = { canDirect: false, canManageSessions: true, canViewVerify: false }
  return { vm, requests, toasts, identity }
}
const details = (requests) => requests.filter(r => r.url === '/api/topic/info-to-user')
const stats = (requests) => requests.filter(r => r.url === '/api/club/crm/topic-manage-stats')
const raw = (name) => ({ status: 1, name, startDate: '2026-10-20 09:00:00', endDate: '2026-10-21 18:00:00', activityList: [] })

function loaded() {
  const h = loadPage()
  h.vm.fetchDetail()
  details(h.requests)[0].success({ code: 200, data: raw('首屏内容') })
  assert.equal(h.vm.data.topic.name, '首屏内容')
  h.vm._hasShown = true
  return h
}

test('V6b CL-48：onShow 与重试并发，先发后到的旧详情不覆盖新详情', () => {
  const h = loaded()
  h.vm.onShow()
  h.vm.onRetry()
  const [, older, newer] = details(h.requests)
  newer.success({ code: 200, data: raw('新内容') })
  older.success({ code: 200, data: raw('旧内容') })
  assert.equal(h.vm.data.topic.name, '新内容', '旧回包晚到覆盖了新数据')
  older.fail({ errMsg: 'late' })
  assert.equal(h.vm.data.loadError, false)
})

test('V6b CL-48：换账号后上一账号的详情回包丢弃', () => {
  const h = loaded()
  h.vm.fetchDetail()
  h.identity.member = 10
  details(h.requests)[1].success({ code: 200, data: raw('上一账号') })
  assert.equal(h.vm.data.topic.name, '首屏内容')
})

test('V6b CX-12：已加载后 onShow 的详情刷新失败，保留旧内容不切整页错误', () => {
  for (const failure of ['business', 'network']) {
    const h = loaded()
    h.vm.onShow()
    const req = details(h.requests).at(-1)
    if (failure === 'business') req.success({ code: 500, msg: '服务繁忙' })
    else req.fail({ errMsg: 'network' })
    assert.equal(h.vm.data.loadError, false, failure)
    assert.equal(h.vm.data.topic.name, '首屏内容', failure)
  }
})

test('V6b CX-12 缺陷复现：详情刷新失败后没有任何「内容可能不是最新」的非阻塞提示', { todo: 'DEFECT pages/club/topic-detail/index.js:331-334 刷新失败且已有 _lastRaw 时直接 return，不提示' }, () => {
  const h = loaded()
  h.vm.onShow()
  details(h.requests).at(-1).fail({ errMsg: 'network' })
  const flagged = Object.keys(h.vm.data).some(k => /stale|refresh(Error|Failed)|retryHint/i.test(k) && h.vm.data[k])
  assert.ok(h.toasts.length > 0 || flagged, '旧内容被当成最新展示，用户看不到刷新失败')
})

test('V6b CX-12 缺陷复现：已加载后 onShow 的管理统计刷新失败，把已展示内容整页换成「活动暂时打不开」', { todo: 'DEFECT pages/club/topic-detail/index.js:298 / 303-306 failManageStats 无条件 loadError:true' }, () => {
  const h = loaded()
  h.vm.onShow()
  stats(h.requests).at(-1).fail({ errMsg: 'network' })
  details(h.requests).at(-1).success({ code: 200, data: raw('首屏内容') })
  assert.equal(h.vm.data.loadError, false, '一次后台刷新失败就把已加载内容替换为整页错误')
})
