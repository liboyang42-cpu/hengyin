// BL-200:商家「取消参与」确认后无在途保护,连点会再发一次取消、排两个 navigateBack,
// 第二个把上一页也退掉。照同文件 _chapterWithdrawing 写法加实例标记 _cancelingRegistration。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const PAGE = 'pages/topic/merchantinfo/merchantinfo'

function loadPage() {
  const requests = []
  const tips = []
  const timers = []
  let backs = 0
  let config = null
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: (options) => requests.push(options),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    tips: (msg) => tips.push(msg),
  }
  const modalStub = { show: (opts) => opts.success({ confirm: true }) }
  const toastStub = Object.assign(() => {}, { success() {}, fail() {} })
  const wx = {
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    showToast() {}, showLoading() {}, hideLoading() {}, showModal() {},
    navigateBack() { backs += 1 }, navigateTo() {}, redirectTo() {}, reLaunch() {}, switchTab() {}, pageScrollTo() {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  }
  const dir = path.resolve(ROOT, PAGE.split('/').slice(0, -1).join('/'))
  const context = {
    console,
    getApp: () => app,
    getCurrentPages: () => [],
    Page: (value) => { config = value },
    setTimeout: (fn) => { timers.push(fn) },
    require: (id) => {
      const full = id.startsWith('.') ? path.resolve(dir, id) : id
      if (full.endsWith(path.join('utils', 'modal.js'))) return modalStub
      if (full.endsWith(path.join('utils', 'toast.js'))) return toastStub
      return require(full)
    },
    module: { exports: {} },
    exports: {},
    wx,
  }
  vm.runInContext(fs.readFileSync(path.join(ROOT, PAGE + '.js'), 'utf8'), vm.createContext(context), { filename: path.join(ROOT, PAGE + '.js') })
  const page = Object.assign({}, config, { data: Object.assign(JSON.parse(JSON.stringify(config.data)), { regId: 9 }) })
  page.setData = (patch) => Object.assign(page.data, patch)
  return { page, requests, tips, timers, backs: () => backs }
}

const cancelReqs = (requests) => requests.filter(r => r.url === '/api/registration/merchant/cancel')

test('确认后连调两次只发 1 个取消请求,成功后窗口内再点也只排 1 个 navigateBack', () => {
  const t = loadPage()
  t.page.cancelRegistration()
  t.page.cancelRegistration()
  assert.equal(cancelReqs(t.requests).length, 1)
  cancelReqs(t.requests)[0].success({ code: 200 })
  t.page.cancelRegistration()
  assert.equal(cancelReqs(t.requests).length, 1, '成功后 1.5 秒窗口内不得再发取消')
  t.timers.forEach(fn => fn())
  assert.equal(t.backs(), 1)
})

test('业务失败与网络失败后可以再次取消,网络失败有非裸文案提示', () => {
  const t = loadPage()
  t.page.cancelRegistration()
  cancelReqs(t.requests)[0].success({ code: 500, msg: '已中标不可取消' })
  assert.deepEqual(t.tips, ['已中标不可取消'])
  t.page.cancelRegistration()
  assert.equal(cancelReqs(t.requests).length, 2)
  cancelReqs(t.requests)[1].fail({})
  assert.equal(t.tips[1], '网络异常，请重试')
  t.page.cancelRegistration()
  assert.equal(cancelReqs(t.requests).length, 3)
})
