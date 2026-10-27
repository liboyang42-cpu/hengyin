'use strict'

// BL-230:个人主页组件的跳转入口连点只跳一次;complete 回调或页面重新 show 后可再次跳转。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const PROFILE = 'subpackageMember/components/cy/profile/index.js'

const navs = []
global.wx = {
  showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {},
  getStorageSync() { return '' }, setStorageSync() {},
  stopPullDownRefresh() {}, startPullDownRefresh() {},
  navigateTo(o) { navs.push(o) }, navigateBack() {}, reLaunch() {}, switchTab() {},
  setNavigationBarTitle() {}, previewImage() {}, vibrateShort() {}, pageScrollTo() {},
  getSystemInfoSync() { return {} }, getAppBaseInfo() { return {} },
}

function mountProfile() {
  navs.length = 0
  const requests = []
  let definition = null
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => '9',
    getUserRole: () => 'player',
    getUserType: () => 1,
    isDevEnv: () => false,
    getPageSize: () => 10,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(request) { requests.push(request) },
    tips() {},
  }
  global.getApp = () => app
  global.Component = (def) => { definition = def }
  delete require.cache[require.resolve(path.join(ROOT, PROFILE))]
  require(path.join(ROOT, PROFILE))
  const component = Object.assign({}, definition.methods, {
    data: JSON.parse(JSON.stringify(definition.data)),
  })
  component.setData = (patch) => { Object.assign(component.data, patch) }
  component.pageShow = definition.pageLifetimes.show
  component.requests = requests
  return component
}

const SIMPLE = [
  ['goPointsHistory'], ['goSignup'], ['onMoreSetting'], ['goSetting'], ['goEditIntro'], ['goShop'],
  ['goOrder'], ['goProject'], ['goGrowthCenter'], ['goWall'], ['goAlbum'], ['goObjectCards'],
  // 2026-10-27 owner 真机走查 P-53:「探索与更多」只留优惠券与邀请好友,goComplaint / goClub / goBecomeTalent 随入口删除
  ['goEarnings'], ['goMerchantTeam'], ['goClubWorkbench'], ['goCoupon'],
  ['onOrderPreviewTap', { currentTarget: { dataset: { id: 7 } } }],
  ['onProjectPreviewTap', { currentTarget: { dataset: { id: 7 } } }],
  ['onBenefitTap', { currentTarget: { dataset: { route: '/subpackageA/pages/assetcenter/earnings/index', unlocked: true } } }],
]

for (const [name, event] of SIMPLE) {
  test(name + ' 连点只跳 1 次,complete 后可再跳', () => {
    const c = mountProfile()
    c.closeScene = () => {}
    c.closeMoreMenu = () => {}
    c[name](event)
    c[name](event)
    assert.equal(navs.length, 1)
    navs[0].complete()
    c[name](event)
    assert.equal(navs.length, 2)
  })
}

test('不同入口共用一个标记:跳转中点别的入口也不叠页', () => {
  const c = mountProfile()
  c.goSetting()
  c.goOrder()
  assert.equal(navs.length, 1)
})

test('jumpDetail / goFeatured / goMerchantReviews 连点只跳 1 次', () => {
  const c = mountProfile()
  c.data.list = [{ id: 3 }]
  const ev = { currentTarget: { dataset: { index: 0 } } }
  c.jumpDetail(ev); c.jumpDetail(ev)
  assert.equal(navs.length, 1)
  navs[0].complete()
  c.data.featured = { featuredType: 1, featuredId: 5 }
  c.goFeatured(); c.goFeatured()
  assert.equal(navs.length, 2)
  navs[1].complete()
  c.data.featured = { featuredType: 2 }
  c.goFeatured(); c.goFeatured()
  assert.equal(navs.length, 3)
  navs[2].complete()
  c.data.subjectMerchant = { id: 11 }
  c.goMerchantReviews(); c.goMerchantReviews()
  assert.equal(navs.length, 4)
  assert.match(navs[3].url, /merchantRowId=11$/)
})

test('页面重新 show 时兜底清锁', () => {
  const c = mountProfile()
  c._profileAttached = true
  c._profileShown = false
  c._syncProfileReducedMotion = () => {}
  c.goSetting()
  c.pageShow.call(c)
  c.goSetting()
  assert.equal(navs.length, 2)
})
