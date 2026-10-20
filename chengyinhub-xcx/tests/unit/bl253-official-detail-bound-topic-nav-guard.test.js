'use strict'
// BL-253:活动详情「完成绑定主题」任务卡(onMissionTap)与底部主按钮(onCta theme)经 goBoundTopic 跳主题页,
// 连点只跳一次且第二次不弹「请完成绑定主题」toast;与 openOrganizer 共用标记;complete/onShow 后可再跳;
// 无效 topicId 仍返回 false、不上锁。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const URL = '/pages/topic/index/index?id=990028'

function boot() {
  const navs = []
  const toasts = []
  global.getApp = () => ({
    globalData: {},
    getImgUrl: (u) => u,
    sendRequest: () => ({ abort() {} }),
  })
  global.getCurrentPages = () => [{}, {}]
  const base = {
    navigateTo: (o) => navs.push(o), navigateBack() {}, switchTab() {}, stopPullDownRefresh() {}, getStorageSync: () => '',
    showToast: (o) => toasts.push(o && o.title),
  }
  global.wx = new Proxy(base, { get(t, k) { return k in t ? t[k] : () => {} } })
  const file = path.join(__dirname, '..', '..', 'pages/activity/official-detail/index.js')
  let def
  global.Page = (c) => { def = c }
  delete require.cache[require.resolve(file)]
  require(file)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.data.id = 7
  page.data.organizerEntry = true
  page.data.cta = { type: 'theme' }
  page.data.e = {
    id: 7, signed: true,
    missions: [{ missionCode: 't1', missionType: 'THEME_VERIFIED_FINISH', complete: false, _supported: true,
      binding: { bindingType: 'THEME', sourceRefId: 990028 } }],
  }
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  return { page, navs, toasts }
}

const tapMission = { currentTarget: { dataset: { code: 't1' } } }

test('BL-253:任务卡连点只跳 1 次且第二次不弹 toast,complete 后可再跳', () => {
  const { page, navs, toasts } = boot()
  page.onMissionTap(tapMission)
  page.onMissionTap(tapMission)
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
  assert.deepEqual(toasts, [])
  navs[0].complete()
  page.onMissionTap(tapMission)
  assert.equal(navs.length, 2)
})

test('BL-253:底部主按钮(theme)连点只跳 1 次且第二次不弹 toast,onShow 后可再跳', () => {
  const { page, navs, toasts } = boot()
  page.onCta()
  page.onCta()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, URL)
  assert.deepEqual(toasts, [])
  page.onShow()
  page.onCta()
  assert.equal(navs.length, 2)
})

test('BL-253:与 openOrganizer 共用一个标记', () => {
  const { page, navs, toasts } = boot()
  page.openOrganizer()
  page.onMissionTap(tapMission)
  page.onCta()
  assert.equal(navs.length, 1)
  assert.equal(navs[0].url, '/pages/activity/official-organizer/index?id=7')
  assert.deepEqual(toasts, [])
  navs[0].complete()
  page.onMissionTap(tapMission)
  assert.equal(navs.length, 2)
  assert.equal(navs[1].url, URL)
})

test('BL-253:无效 topicId 仍返回 false、不跳也不上锁', () => {
  const { page, navs, toasts } = boot()
  page.data.e.missions[0].binding.sourceRefId = 0
  page.onCta()
  assert.deepEqual(toasts, ['请完成绑定主题，系统会自动核验'], '目标无效时照旧提示(证明 toast 桩生效)')
  page.data.e.missions[0].binding.sourceRefId = 990028
  assert.equal(page.goBoundTopic({ binding: { bindingType: 'THEME', sourceRefId: 0 } }), false)
  assert.equal(page.goBoundTopic({ binding: { bindingType: 'POI', sourceRefId: 5 } }), false)
  assert.equal(page.goBoundTopic(null), false)
  assert.equal(navs.length, 0)
  assert.ok(!page._navigating)
  assert.equal(page.goBoundTopic(page.data.e.missions[0]), true)
  assert.equal(page.goBoundTopic(page.data.e.missions[0]), true, '锁住时仍返回 true,调用方不误弹 toast')
  assert.equal(navs.length, 1)
})
