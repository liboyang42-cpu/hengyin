const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

const PAGE = '../../pages/index/index.js'
let pageConfig
let sent
let userId
let reloginCalls
let roleUpdates
let isDev

global.getApp = () => ({
  isDevEnv: () => isDev,
  getUserID: () => userId,
  getUserType: () => 0,
  getUserRole: () => 'user',
  getImgUrl: (value) => value,
  sendRequest: (request) => { sent.push(request) },
  reLogin: () => { reloginCalls += 1 },
  setUserRole: (role) => { roleUpdates.push(role) },
  setUserType() {},
  setOpenID() {},
  tips() {},
  // 首页 onShow 会挂在"登录已落地"信号上复判身份(见 index-identity-recheck-after-login)。
  // 本文件的 storage 桩恒返回空 ⇒ 复判判定为玩家、当场返回,不影响下面这些用例。
  waitForAppReady: () => Promise.resolve(),
})

global.wx = {
  getStorageSync: () => '',
  setStorageSync() {},
  removeStorageSync() {},
  showToast() {},
  showLoading() {},
  hideLoading() {},
  hideTabBar() {},
  setNavigationBarColor() {},
  navigateTo() {},
  redirectTo() {},
  switchTab() {},
  getWindowInfo: () => ({ statusBarHeight: 20, screenHeight: 812, windowHeight: 812, windowWidth: 375 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20, screenHeight: 812, windowHeight: 812, windowWidth: 375 }),
  getMenuButtonBoundingClientRect: () => ({ top: 24, height: 32, right: 360, left: 280 }),
}

global.Page = (config) => { pageConfig = config }

beforeEach(() => {
  sent = []
  userId = 'member-1'
  reloginCalls = 0
  roleUpdates = []
  isDev = false
  pageConfig = null
  delete require.cache[require.resolve(PAGE)]
  require(PAGE)
})

function makePage() {
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = (patch, callback) => {
    Object.keys(patch).forEach((key) => { page.data[key] = patch[key] })
    if (callback) callback()
  }
  return page
}

test('旧账号的用户资料响应不能覆盖新账号', () => {
  const page = makePage()
  page.getUserData()
  assert.equal(sent.length, 1)

  userId = 'member-2'
  sent[0].success({ code: '200', data: { nickname: '旧账号', phone: '13800000000' } })

  assert.notEqual(page.data.userInfo.nickname, '旧账号')
})

test('当前账号的用户资料响应可以正常写入', () => {
  const page = makePage()
  page.getUserData()

  sent[0].success({ code: '200', data: { nickname: '当前账号', phone: '13800000000' } })

  assert.equal(page.data.userInfo.nickname, '当前账号')
})

test('未绑定手机号的账号回到首页不会被主动弹窗索取手机号(F-L5C-04)', () => {
  const page = makePage()
  for (let i = 0; i < 3; i += 1) {
    sent = []
    page.getUserData()
    sent[0].success({ code: '200', data: { nickname: '无手机号', phone: '' } })
    assert.equal(page.data.userInfo.nickname, '无手机号')
    assert.equal(page.data.showPhoneModal, false)
  }
})

test('当前账号的角色响应可以更新身份快照', () => {
  const page = makePage()
  page.fetchCityEvent = () => {}

  page.onShow()
  assert.equal(sent.length, 1)
  sent[0].success({ code: '200', data: { role: 'user', userType: 0 } })

  assert.deepEqual(roleUpdates, [{ role: 'user', userType: 0 }])
})

test('旧账号的角色响应不能更新新账号身份快照', () => {
  const page = makePage()
  page.fetchCityEvent = () => {}

  page.onShow()
  assert.equal(sent.length, 1)
  userId = 'member-2'
  sent[0].success({ code: '200', data: { role: 'merchant', userType: 2 } })

  assert.deepEqual(roleUpdates, [])
})

test('换号时立即清空已显示的旧账号资料，后续请求失败也不恢复', () => {
  const page = makePage()
  page._privateDataMemberId = 'member-1'
  page._privateDataEpoch = 1
  page.data.userInfo = { avatar: 'old.png', nickname: '旧账号', point: '88' }
  page.data.showPhoneModal = true
  userId = 'member-2'

  page.getUserData()

  assert.deepEqual(page.data.userInfo, { avatar: '', nickname: '', point: '0' })
  assert.equal(page.data.showPhoneModal, false)
  sent[0].fail({ errMsg: 'request:fail timeout' })
  assert.deepEqual(page.data.userInfo, { avatar: '', nickname: '', point: '0' })
})

test('开始重新登录后，同账号的旧响应也不能回写', () => {
  const page = makePage()
  page.getUserData()
  page.firstLogin()
  assert.equal(reloginCalls, 1)

  sent[0].success({ code: '200', data: { nickname: '旧会话', phone: '13800000000' } })

  assert.notEqual(page.data.userInfo.nickname, '旧会话')
})

test('首页卸载后私人资料的迟到响应不能继续写页', () => {
  const page = makePage()
  page.getUserData()
  const profileRequest = sent[0]

  page.onUnload()
  profileRequest.success({ code: '200', data: { nickname: '卸载后资料', phone: '13800000000' } })

  assert.notEqual(page.data.userInfo.nickname, '卸载后资料')
})

// 2026-10-27 真机走查 P-08:owner 要求玩家首页删掉「继续探索 / 继续游戏」卡，
// 首页不再请求 /api/registration/my-joined 与 /api/play/run-session/list,也不再持有 continueExplore。
test('P-08:首页不再加载继续旅程卡', () => {
  const page = makePage()
  assert.equal(page.loadContinueExplore, undefined)
  assert.equal(page.goContinueExplore, undefined)
  assert.equal('continueExplore' in page.data, false)
})

test('首页再次显示时刷新当前账号资料(继续旅程卡已随 P-08 删除)', async () => {
  const page = makePage()
  const calls = []
  page.data.isAppReady = true
  page.fetchCityEvent = () => {}
  page.getUserData = () => calls.push('profile')

  page.onShow()
  await new Promise(resolve => setTimeout(resolve, 120))

  assert.deepEqual(calls, ['profile'])
})

test('旧会话手机号响应不能写入当前账号', () => {
  const page = makePage()
  page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:ok', code: 'phone-code' } })
  assert.equal(sent.length, 1)

  userId = 'member-2'
  sent[0].success({ code: '200', msg: '13800000000' })

  assert.equal(page.data['userInfo.phone'], undefined)
})

test('当前会话手机号响应可以正常写入', () => {
  const page = makePage()
  page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:ok', code: 'phone-code' } })

  sent[0].success({ code: '200', msg: '13800000000' })

  assert.equal(page.data['userInfo.phone'], '13800000000')
})

test('当前会话从 data 写入绑定手机号,不把 msg 当号码', () => {
  const page = makePage()
  page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:ok', code: 'phone-code' } })

  sent[0].success({ code: '200', msg: '操作成功', data: '13912345678' })

  assert.equal(page.data['userInfo.phone'], '13912345678')
  assert.equal(page.data.showPhoneModal, false)
})

test('消毒后的操作失败不能当成手机号写入', () => {
  const page = makePage()
  page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:ok', code: 'phone-code' } })

  sent[0].success({ code: '200', msg: '操作失败' })

  assert.equal(page.data['userInfo.phone'], undefined)
})

test('开发环境首页也不注入示例用户或假公开列表', () => {
  const page = makePage()
  isDev = true
  userId = ''

  page._buildRecoHero = () => {}
  page.onReady()

  assert.deepEqual(page.data.userInfo, { avatar: '', nickname: '', point: '0' })
  assert.equal(page.data.nearbyActivityList.length, 0, '真实列表为空不能伪造公开内容')
})

test('游客态不重试私人资料请求，也不显示登录过期错误', () => {
  const page = makePage()
  userId = ''
  let scheduled = 0
  const originalSetTimeout = global.setTimeout
  global.setTimeout = () => { scheduled += 1 }
  try {
    page.getUserData()
  } finally {
    global.setTimeout = originalSetTimeout
  }

  assert.equal(scheduled, 0)
  assert.equal(sent.length, 0)
  assert.equal(page.data.isLoading, false)
})

test('取消授权必须关掉手机号弹窗，遮罩不能挡死首页', () => {
  const page = makePage()
  page.data.showPhoneModal = true
  page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:fail user deny' } })
  assert.equal(page.data.showPhoneModal, false)
})

test('推荐卡报名数读 signupCount，不读对不上后端的 enrollCount', () => {
  const page = makePage()
  page.data.recommendedTopicList = [{
    id: 9,
    name: '外滩夜行',
    signupCount: 28,
    enrollCount: undefined,
    joinCount: undefined,
    signUpCount: undefined,
  }]
  page._buildRecoHero()
  assert.match(page.data.recoCards[0].metaLine, /28 人已报名/)
})

// 需求变更(F-L5C-04 / F-W1-14):首页不再主动弹窗索取手机号,原"稍后 24 小时再弹"的口径作废。
test('首页真实资料回读不按稍后期限再弹，任何账号、任何次数都不弹', () => {
 const store = {}; wx.getStorageSync = key => store[key]; wx.setStorageSync = (key,value) => {store[key]=value};
 userId='7'; const page=makePage(); page.getUserData();sent[0].success({code:200,data:{phone:''}});
 assert.equal(page.data.showPhoneModal,false);
 store['phone-reminder:snooze:7']=0;
 page.getUserData();sent[1].success({code:200,data:{phone:''}});assert.equal(page.data.showPhoneModal,false);
 userId='8';page.getUserData();sent[2].success({code:200,data:{phone:''}});assert.equal(page.data.showPhoneModal,false);
 wx.getStorageSync=()=>'';wx.setStorageSync=()=>{};
});
