// R10-09 发帖关联:activity-picker 组件只请求可公开关联的完成记录,空态如实说明范围。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')

const PICKER = '../../pages/square/components/activity-picker/index.js'

let pageConfig
let pickerDef
let requests

function setByPath(target, rawPath, value) {
  const parts = rawPath.split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

beforeEach(() => {
  pageConfig = null
  pickerDef = null
  requests = []
  global.getApp = () => ({
    canUseCommunity: () => true, // Current-account server-open fixture.
    globalData: { statusBarHeight: 20, user_id: 1, features: {} },
    getUserID: () => 1,
    getAvatar: () => '',
    getUserRole: () => '',
    getUserType: () => 0,
    isDevEnv: () => false,
    sendRequest(opts) { requests.push(opts) },
    getUploadClient: () => ({
      uploadAll(paths, opts) {
        // 路线预览图上传:直接成功,让 picker 走真实 finish→emitSelect
        opts.onDone({ ok: true, results: ['https://img.example/route.png'] })
        return { abort() {}, destroy() {} }
      },
    }),
    tips() {},
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  })
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    hideTabBar() {},
    getWindowInfo: () => ({ statusBarHeight: 20, windowHeight: 800 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowHeight: 800 }),
    getMenuButtonBoundingClientRect: () => ({ top: 24, height: 32, bottom: 56 }),
    navigateTo() {},
    showToast() {},
    hideToast() {},
    showLoading() {},
    hideLoading() {},
    createSelectorQuery: () => ({ select: () => ({ fields: () => ({ exec() {} }) }), exec() {} }),
  }
  global.Page = (config) => { pageConfig = config }
  global.Component = (definition) => { pickerDef = definition }
})

function loadPicker() {
  delete require.cache[require.resolve(PICKER)]
  require(PICKER)
  const vm = Object.assign({}, pickerDef.methods)
  vm.data = JSON.parse(JSON.stringify(pickerDef.data))
  vm.setData = function (patch, callback) {
    Object.keys(patch || {}).forEach((key) => setByPath(this.data, key, patch[key]))
    if (callback) callback()
  }
  vm._emitted = null
  vm.triggerEvent = function (name, detail) { if (name === 'select') vm._emitted = detail }
  return vm
}

test('公开关联选择器仅用 query 请求可关联完成记录，历史完成调用方保持默认', () => {
  const picker = loadPicker()
  picker.fetchList()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/play/my-completed?for_public_reference=true')
  assert.equal(requests[0].method, 'POST')
  assert.deepEqual(requests[0].data, {}, 'RequestParam 不得塞入 JSON body')
  for (const rel of [
    '../../subpackageP3/pages/growthcenter/index/index.js',
    '../../subpackageMember/components/cy/profile/index.js',
    '../../pages/play/index.js',
  ]) {
    const source = fs.readFileSync(path.resolve(__dirname, rel), 'utf8')
    assert.ok(source.includes('/api/play/my-completed'))
    assert.doesNotMatch(source, /for_public_reference/, `${rel} 必须继续默认历史完成查询`)
  }
})

test('关联空态说明公开可用范围，不把历史已完成记录说成不存在', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname,
    '../../pages/square/components/activity-picker/index.wxml'), 'utf8')
  assert.match(wxml, /title="暂无可关联的已完成活动或主题"/)
  assert.match(wxml, /sub="仅显示已完成的活动"/)
  assert.doesNotMatch(wxml, /还没有已通关的活动/)
})

// 2026-10-27 P-20 owner:广场行内发布框撤掉,发帖统一走全屏发帖弹窗 cy-post-compose(它自带活动选择,
// 关联映射由 square-post-compose-context 覆盖)。原「真实 picker emit → square/list submitFormData」七条
// 端到端用例随页内发布流水一并撤走;本选择器组件当前已无页面挂载,只保留上面两条组件自身的契约。
