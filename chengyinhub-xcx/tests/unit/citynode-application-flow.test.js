const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function loadPage(relativePath = 'pages/merchant/citynode/index.js') {
  const pagePath = path.resolve(__dirname, '../../', relativePath)
  const old = { Page: global.Page, getApp: global.getApp, wx: global.wx }
  const requests = []
  const toasts = []
  const scans = []
  const session = { revision: 1, owner: 9000, authorization: 'fixture-login', issuedAt: 1 }
  const app = {
    globalData: { navBarHeight: 44 },
    getSession: () => ({ getRevision: () => session.revision }),
    getUserID: () => session.owner,
    getAuthorization: () => session.authorization,
    sendRequest(request) { requests.push(request) }
  }
  global.getApp = () => app
  global.wx = {
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    setNavigationBarColor() {},
    setBackgroundColor() {},
    getStorageSync: key => key === 'authorization_issued_at' ? session.issuedAt : undefined,
    showToast(options) { toasts.push(options) },
    scanCode(options) { scans.push(options) },
    showModal() {}
  }
  let definition
  global.Page = config => { definition = config }
  delete require.cache[require.resolve(pagePath)]
  require(pagePath)
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.data.accessState = 'ready'
  page.data.merchantAccess = { active: true, canManageProjects: true, canVerify: true }
  page.setData = function setData(patch) { Object.assign(this.data, patch) }
  return {
    page, requests, toasts, scans, session,
    restore() {
      if (old.Page === undefined) delete global.Page; else global.Page = old.Page
      if (old.getApp === undefined) delete global.getApp; else global.getApp = old.getApp
      if (old.wx === undefined) delete global.wx; else global.wx = old.wx
      delete require.cache[require.resolve(pagePath)]
    }
  }
}

test('据点核销扫码只对用户主动取消静默，系统扫码失败必须提示', () => {
  const env = loadPage()
  try {
    env.page.scanRedeem()
    env.scans[0].fail({ errMsg: 'scanCode:fail cancel' })
    assert.equal(env.toasts.length, 0)

    env.page.scanRedeem()
    env.scans[1].fail({ errMsg: 'scanCode:fail system error' })
    assert.match(env.toasts[0].title, /扫码失败/)
  } finally {
    env.restore()
  }
})

test('据点扫码没有当前登录身份时不调用扫码或提交核销', () => {
  const env = loadPage()
  try {
    env.session.owner = null
    env.page.scanRedeem()
    assert.equal(env.scans.length, 0)
    assert.equal(env.requests.length, 0)
    assert.equal(env.toasts.length, 0)
  } finally {
    env.restore()
  }
})

test('据点扫码返回前登录变化或页面离开后旧回调不得提示或提交核销', () => {
  for (const invalidate of [
    env => { env.session.revision += 1 },
    env => env.page.onHide(),
    env => env.page.onUnload(),
  ]) {
    const env = loadPage()
    try {
      let submitted = 0
      const submit = env.page.submitCityNodeRedeem
      env.page.submitCityNodeRedeem = function (code) { submitted += 1; return submit.call(this, code) }
      env.page.scanRedeem()
      assert.equal(env.scans.length, 1, '有效的原登录必须实际进入扫码')
      invalidate(env)
      env.scans[0].fail({ errMsg: 'scanCode:fail system error' })
      env.scans[0].success({ result: 'v1.9.citynode_10.9999999999.nonce.fixture-signature' })
      assert.equal(env.toasts.length, 0, '旧扫码错误不得污染新页面或会话')
      assert.equal(submitted, 0, '旧扫码结果不得进入核销提交方法')
      assert.equal(env.requests.length, 0, '旧扫码结果不得提交核销')
    } finally {
      env.restore()
    }
  }
})

test('我的据点读取同时保留已生效资产与 roam_poi 审核申请', () => {
  const env = loadPage()
  try {
    env.page.loadMine()
    assert.equal(env.requests.length, 1)
    env.requests[0].success({
      code: 200,
      data: {
        nodes: [{ poiId: 9, name: '外滩据点', status: 1 }],
        applications: [{ id: 11, applicationType: 1, auditStatus: 0 }],
        used: 1,
        max: 2
      }
    })
    assert.equal(env.page.data.nodes.length, 1)
    assert.equal(env.page.data.applications.length, 1)
    assert.equal(env.page.data.applications[0].auditStatus, 0)
  } finally {
    env.restore()
  }
})

test('我的据点缺少名称时不渲染半成品卡', () => {
  const env = loadPage()
  try {
    env.page.loadMine()
    env.requests[0].success({
      code: 200,
      data: { nodes: [{ poiId: 9, status: 1 }], applications: [], used: 1, max: 2 }
    })
    assert.equal(env.page.data.nodes.length, 0)
  } finally {
    env.restore()
  }
})

test('投放提交成功只进入审核态并重新读取，不在客户端伪造已上线节点', () => {
  const env = loadPage('pages/merchant/citynode/create/index.js')
  try {
    let returned = 0
    env.page.onNavBack = () => { returned += 1 }
    env.page.data.tpl = { id: 9, title: '暗号' }
    env.page.data.picked = { lat: 30.1, lng: 120.2, name: '山岚咖啡', address: '湖滨路 1 号' }
    env.page.data.addressConfirmed = true
    env.page.submitNode()
    assert.equal(env.requests.length, 1)
    assert.equal(env.requests[0].data.address, '湖滨路 1 号')
    env.requests[0].success({ code: 200, data: 101 })

    assert.equal(env.requests.length, 2, '成功后必须刷新服务端申请/资产状态')
    assert.match(env.toasts[0].title, /申请已提交/)
    env.requests[1].success({ code: 200, data: { applications: [{ id: 101, auditStatus: 0 }] } })
    assert.equal(returned, 1, '只有刷新到同一申请 id 才能离开创建页')
  } finally {
    env.restore()
  }
})

test('投放刷新没出现新申请时留在原页且禁止重复写入', () => {
  const env = loadPage('pages/merchant/citynode/create/index.js')
  try {
    let returned = 0
    env.page.onNavBack = () => { returned += 1 }
    env.page.data.tpl = { id: 9, title: '暗号' }
    env.page.data.picked = { lat: 30.1, lng: 120.2, name: '山岚咖啡', address: '湖滨路 1 号' }
    env.page.data.addressConfirmed = true
    env.page.submitNode()
    env.requests[0].success({ code: 200, data: 101 })
    env.requests[1].success({ code: 200, data: { applications: [] } })
    assert.equal(returned, 0)
    assert.match(env.page.data.readbackError, /审核状态更新中/)
    env.page.data.submitting = false
    env.page.submitNode()
    assert.equal(env.requests.length, 3)
    assert.equal(env.requests[2].url, '/api/merchant/city-node/list', '重试只能刷新，不能再次提交 /save')
  } finally {
    env.restore()
  }
})

test('认领提交成功刷新 roam_poi 审核态，不直接把目标塞进我的据点', () => {
  const env = loadPage()
  try {
    env.page.data.showClaim = true
    env.page.data.claimable = [{ id: 66, name: '旧码头' }]
    env.page.submitClaim({ currentTarget: { dataset: { poiid: 66 } } })
    assert.equal(env.requests.length, 1)
    env.requests[0].success({ code: 200, data: 102 })

    assert.equal(env.page.data.nodes.length, 0)
    assert.equal(env.page.data.showClaim, false)
    assert.equal(env.requests.length, 2, '认领提交后必须从服务端刷新申请状态')
  } finally {
    env.restore()
  }
})
