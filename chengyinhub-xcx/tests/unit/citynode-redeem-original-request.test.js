'use strict'

// PRIVATE-GROUP-nucleation-original-request:据点核销与 #424 的四个核销入口同一写闸——
// 断网/畸形回执只算「待确认」并持久占住账号+据点+玩家;同一目标再扫先回读、不重放;
// 只有原请求的明确回执才结算;couponGranted 必须是严格布尔。
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const { createSessionStore } = require('../../utils/session/session-store.js')
const { classifyCityNodeRedeem, cityNodeTarget } = require('../../pages/merchant/utils/citynode-redeem-outcome.js')

const CODE = 'v1.12.citynode_34.9999999999999.5.sig'
const OTHER_PLAYER = 'v1.12.citynode_35.9999999999999.6.sig'
const URL = '/api/verify/citynode/redeem'

function mount(storage) {
  const relativePath = 'pages/merchant/citynode/index.js'
  const absolutePath = path.join(ROOT, relativePath)
  const previous = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx }
  const requests = []
  const toasts = []
  const modals = []
  let uid = 7
  const session = createSessionStore({ wx: { getStorageSync() {}, setStorageSync() {}, removeStorageSync() {} }, target: {} })
  session.applySnapshot({ user_id: uid, authorization: "synthetic-city-session", role: "merchant" })
  let cameraCallback
  const app = {
    globalData: { navBarHeight: 44 },
    getUserID: () => uid,
    getSession: () => session,
    getAuthorization: () => session.getAuthorization(),
    getRequestErrorMessage(res, fallback) { return (res && res.msg) || fallback },
    sendRequest(options) { requests.push(options); return { abort() {} } },
  }
  let scanResult = CODE
  global.getApp = () => app
  global.getCurrentPages = () => [{ route: 'citynode' }]
  global.wx = {
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    setNavigationBarColor() {},
    setBackgroundColor() {},
    showToast(options) { toasts.push(options.title) },
    showModal(options) { modals.push(options) },
    vibrateShort() {},
    scanCode(options) { options.success({ result: scanResult }) },
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = JSON.parse(JSON.stringify(value)) },
    removeStorageSync: key => { delete storage[key] },
  }
  let definition
  global.Page = config => { definition = config }
  for (const key of Object.keys(require.cache)) if (key.startsWith(ROOT + path.sep) && !key.includes('node_modules')) delete require.cache[key]
  require(absolutePath)
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.data.merchantAccess = { active: true, canVerify: true }
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb() }
  return {
    page, requests, toasts, modals,
    redeems: () => requests.filter(r => r.url === URL),
    scan(code) { scanResult = code; page.scanRedeem() },
    switchUser(next) { uid = next; session.applySnapshot({ user_id: uid, authorization: "synthetic-city-session", role: "merchant" }) },
    session, app,
    pending: () => Object.keys(storage).filter(k => k.startsWith("verification_unknown_v1:")),
    reads: () => requests.filter(r => r.url === "/api/verify/citynode/receipt"),
    deferCamera() { global.wx.scanCode = options => { cameraCallback = options } },
    cameraSuccess() { cameraCallback.success({ result: CODE }) },
    restore() {
      if (page._redeemWorkflow) page._redeemWorkflow.destroy()
      for (const [name, value] of Object.entries(previous)) {
        if (value === undefined) delete global[name]
        else global[name] = value
      }
    },
  }
}

const receipt = (over) => ({
  code: 200, msg: '核销成功,券已发放玩家',
  data: Object.assign({ memberId: 34, poiId: 12, couponId: 5, couponGranted: true }, over),
})

test('回执分类:绑定码内据点与玩家,couponGranted 严格布尔,事务前/回滚文案才算失败', () => {
  const classify = (res, channel, status) => classifyCityNodeRedeem(res, channel, status, CODE)
  assert.deepEqual(cityNodeTarget(CODE), { poiId: '12', memberId: '34' })
  assert.equal(cityNodeTarget('v1.12.activity.1.2.sig'), null)
  assert.equal(classify(receipt(), 'success', undefined).status, 'success')
  assert.equal(classify(receipt({ couponGranted: false, couponId: 5 }), 'success', undefined).status, 'unknown', 'msg 与 couponGranted 不一致')
  assert.equal(classify({ code: 200, msg: '核销成功,该券已失效或停发,未发放', data: { memberId: 34, poiId: 12, couponId: 5, couponGranted: false } }, 'success', undefined).status, 'success')
  assert.equal(classify(receipt({ couponGranted: 'false' }), 'success', undefined).status, 'unknown')
  assert.equal(classify(receipt({ couponGranted: undefined }), 'success', undefined).status, 'unknown')
  assert.equal(classify(receipt({ memberId: 35 }), 'success', undefined).status, 'unknown', '别的玩家的回执')
  assert.equal(classify(receipt({ poiId: 13 }), 'success', undefined).status, 'unknown', '别的据点的回执')
  assert.equal(classify({ code: 200, msg: '核销成功' }, 'success', undefined).status, 'unknown')
  assert.equal(classify({ code: 500, msg: '玩家未到店打卡,或该券已核销' }, 'success', undefined).status, 'failed')
  assert.equal(classify({ code: 500, msg: '券库存不足,核销失败' }, 'success', undefined).status, 'failed')
  // 验码阶段的后端原话换成商家能做的下一步
  const expired = classify({ code: 500, msg: '已过期' }, 'success', undefined)
  assert.equal(expired.status, 'failed')
  assert.equal(expired.response.msg, '核销码无效或已过期，请让玩家刷新后重扫')
  assert.equal(classify({ code: 500, msg: '据点核销审计写入失败' }, 'success', undefined).status, 'unknown')
  assert.equal(classify({ code: 500 }, 'success', undefined).status, 'unknown')
  assert.equal(classify(undefined, 'fail', undefined).status, 'unknown')
  assert.equal(classify({}, 'fail', 403).status, 'failed')
})

test('断网=待确认:不提示重试,同一据点+玩家再扫不重放,换玩家不受影响', () => {
  const storage = {}
  const env = mount(storage)
  try {
    env.scan(CODE)
    assert.equal(env.redeems().length, 1)
    assert.equal(Object.keys(storage).length, 1, 'POST 前已落持久待办')
    env.redeems()[0].fail()
    assert.equal(env.modals.pop().title, '核销结果待确认')
    assert.ok(!env.toasts.some(t => /重试/.test(t)))

    // 玩家刷新出新码(nonce/exp 不同),同一据点+玩家仍被占住
    env.scan(CODE.replace('.5.sig', '.6.sig2'))
    assert.equal(env.redeems().length, 1)
    assert.equal(env.reads().length, 1, 'UNKNOWN 后只查询原结果')
    env.reads()[0].success({ code: 520, msg: '原据点核销结果待确认，请勿重复核销' })
    assert.equal(env.modals.pop().title, '核销结果待确认')

    env.scan(OTHER_PLAYER)
    assert.equal(env.redeems().length, 2)
  } finally { env.restore() }
})

test('页面销毁重建后同一目标先回读不重放;明确回执才结算', () => {
  const storage = {}
  const first = mount(storage)
  first.scan(CODE)
  first.redeems()[0].success({ code: 200, msg: '核销成功', data: { couponGranted: 'true' } })
  assert.equal(first.modals.pop().title, '核销结果待确认', '畸形成功回执不算成功')
  first.page.onUnload()
  first.restore()

  const second = mount(storage)
  try {
    second.scan(CODE)
    assert.equal(second.redeems().length, 0, '重建后不能重放原请求')
    assert.equal(second.reads().length, 1, '重建后调用授权原结果入口')
    second.reads()[0].success({ code: 520, msg: '原据点核销结果待确认，请勿重复核销' })
    const modal = second.modals.pop()
    assert.equal(modal.title, '核销结果待确认')
    assert.match(modal.content, /没有重新提交/)
    assert.equal(Object.keys(storage).length, 1)
  } finally { second.restore() }
})

test('明确成功与明确失败都清掉本次待办;账号隔离', () => {
  const storage = {}
  const env = mount(storage)
  try {
    env.scan(CODE)
    env.redeems()[0].success(receipt({ couponGranted: false, couponId: 5 }))
    assert.equal(env.modals.pop().title, '核销结果待确认')

    env.scan(OTHER_PLAYER)
    env.redeems()[1].success({ code: 200, msg: '核销成功,该券已失效或停发,未发放', data: { memberId: 35, poiId: 12, couponId: 0, couponGranted: false } })
    assert.equal(env.toasts.pop(), '核销成功，该券已失效或停发，未发放')
    assert.equal(Object.keys(storage).length, 1, '只剩第一笔待确认')

    // 换账号:A 的待办不挡 B
    env.switchUser(8)
    env.scan(CODE)
    assert.equal(env.redeems().length, 3)
    env.redeems()[2].success({ code: 500, msg: '玩家未到店打卡,或该券已核销' })
    const failed = env.modals.pop()
    assert.equal(failed.title, '核销失败')
    assert.equal(failed.content, '玩家未到店打卡,或该券已核销')
    assert.equal(Object.keys(storage).length, 1)
  } finally { env.restore() }
})


test('real session role revision invalidates late city success without clearing original UNKNOWN', () => {
  const storage = {}, h = mount(storage)
  try {
    h.scan(CODE); const original = JSON.stringify(storage)
    h.session.setUserRole('club')
    h.redeems()[0].success(receipt())
    assert.equal(JSON.stringify(storage), original)
    assert.equal(h.modals.length, 0); assert.equal(h.toasts.length, 0)
  } finally { h.restore() }
})
test('account ABA and hidden-page late city callbacks leave original identity untouched', () => {
  for (const invalidate of [h => { h.switchUser(8); h.switchUser(7) }, h => h.page.onHide()]) {
    const storage = {}, h = mount(storage)
    try {
      h.scan(CODE); const original = JSON.stringify(storage)
      invalidate(h); h.redeems()[0].success(receipt())
      assert.equal(JSON.stringify(storage), original); assert.equal(h.toasts.length, 0); assert.equal(h.modals.length, 0)
    } finally { h.restore() }
  }
})
test('camera callback captures the original login revision and page generation before opening', () => {
  const h = mount({})
  try {
    h.deferCamera(); h.scan(CODE); h.session.setUserRole('club'); h.session.setUserRole('merchant'); h.cameraSuccess()
    assert.equal(h.redeems().length, 0); assert.equal(h.pending().length, 0)
  } finally { h.restore() }
})
test('same-page UNKNOWN retry reads original result; concurrent taps never send a second write', () => {
  const h = mount({})
  try {
    h.scan(CODE); h.scan(CODE)
    assert.equal(h.redeems().length, 1); assert.equal(h.reads().length, 0)
    h.redeems()[0].fail(); h.scan(CODE.replace('.5.sig', '.7.sig'))
    assert.equal(h.redeems().length, 1); assert.equal(h.reads().length, 1)
    assert.deepEqual(h.reads()[0].data, { code: CODE.replace('.5.sig', '.7.sig') })
    h.reads()[0].success(receipt())
    assert.equal(h.pending().length, 0); assert.match(h.toasts.at(-1), /核销成功/)
  } finally { h.restore() }
})

test('late readback after a real role revision preserves the original disk row and performs no callback', () => {
  const storage = {}, h = mount(storage)
  try {
    h.scan(CODE); h.redeems()[0].fail(); h.modals.length = 0
    h.scan(CODE); assert.equal(h.reads().length, 1)
    const original = JSON.stringify(storage); h.session.setUserRole('club'); h.reads()[0].success(receipt())
    assert.equal(JSON.stringify(storage), original); assert.equal(h.toasts.length, 0); assert.equal(h.modals.length, 0)
    h.session.setUserRole('merchant'); h.scan(CODE)
    assert.equal(h.reads().length, 2); assert.equal(h.redeems().length, 1)
    h.reads()[1].success(receipt()); assert.equal(h.pending().length, 0)
  } finally { h.restore() }
})
test('readback rejects missing/foreign/malformed or revoked responses without releasing UNKNOWN', () => {
  for (const reply of [{ code: 520 }, { code: 403 }, receipt({ memberId: 35 }), receipt({ couponGranted: 'true' }), { code: 200, msg: '核销成功' }]) {
    const storage = {}, h = mount(storage)
    try {
      h.scan(CODE); h.redeems()[0].fail(); const original = JSON.stringify(storage)
      h.scan(CODE); h.reads()[0].success(reply)
      assert.equal(JSON.stringify(storage), original); assert.equal(h.redeems().length, 1)
      assert.equal(h.modals.at(-1).title, '核销结果待确认')
    } finally { h.restore() }
  }
})
test('consecutive players on the same page settle only their own original pending rows', () => {
  const h = mount({})
  try {
    h.scan(CODE); h.scan(OTHER_PLAYER); assert.equal(h.redeems().length, 2); assert.equal(h.pending().length, 2)
    h.redeems()[1].success(receipt({ memberId: 35 })); assert.equal(h.pending().length, 1)
    h.redeems()[0].success(receipt()); assert.equal(h.pending().length, 0); assert.equal(h.toasts.length, 2)
  } finally { h.restore() }
})
test('unreadable storage prevents a new city POST; receipt reads never fabricate a successful release', () => {
  const storage = {}, h = mount(storage)
  try {
    global.wx.setStorageSync = () => { throw Error('fixture disk full') }
    h.scan(CODE)
    assert.equal(h.redeems().length, 0); assert.equal(h.pending().length, 0)
    assert.equal(h.modals.at(-1).title, '核销失败')
  } finally { h.restore() }
})

test('a second page reads the shared original target and old-page late success cannot consume its UI context', () => {
  const storage = {}, first = mount(storage)
  first.scan(CODE); first.page.onHide()
  const second = mount(storage)
  try {
    second.scan(CODE)
    assert.equal(first.redeems().length, 1); assert.equal(second.redeems().length, 0); assert.equal(second.reads().length, 1)
    first.redeems()[0].success(receipt())
    assert.equal(first.toasts.length, 0); assert.equal(second.toasts.length, 0); assert.equal(second.pending().length, 1)
    second.reads()[0].success(receipt())
    assert.equal(second.pending().length, 0); assert.equal(second.toasts.length, 1)
  } finally { second.restore(); first.restore() }
})
