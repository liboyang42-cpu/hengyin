'use strict'
// R6b money-payout(2026-11-15):提现申请 → 平台审核 → 经既有微信商家转账打款;App 扫码绑定收款微信。
// 只在资金开关「用户提现」(GET /api/app/money-switch 的 withdrawal)为 true 时启用;
// 读不到、出错、为 false 一律按关闭处理,页面保持今天的样子(联系客服弹窗)。
// 服务端每个写接口自己也会再查一次开关,这里只决定页面怎么展示。

function app() { return getApp() }

function jsonHeader() { return { 'content-type': 'application/json' } }

function bodyOk(res) { return !!(res && (res.code === 200 || res.code === '200')) }

function message(res, fallback) {
  var msg = res && typeof res.msg === 'string' ? res.msg.trim() : ''
  // 不把大写机器码(如 COMMON.FEATURE_OFF)给用户看
  if (!msg || /^[A-Z0-9_.]+$/.test(msg)) return fallback
  return msg
}

// cb(open: boolean)。任何失败都回 false。
function withdrawalOpen(cb) {
  var done = false
  function finish(value) { if (done) return; done = true; cb(value === true) }
  try {
    app().sendRequest({
      url: '/api/app/money-switch', method: 'GET', hideLoading: true, silentError: true, auth: false, timeout: 6000,
      success: function (res) { finish(bodyOk(res) && res.data && res.data.withdrawal === true) },
      fail: function () { finish(false) },
      successStatusAbnormal: function () { finish(false) }
    })
  } catch (e) { finish(false) }
}

function call(url, method, data, done) {
  var param = {
    url: url, method: method, hideLoading: true, silentError: true,
    success: function (res) { done(bodyOk(res) ? null : message(res, '操作没有完成，请稍后再试'), res && res.data) },
    fail: function (res) { done(message(res, '网络异常，请稍后再试'), null) },
    successStatusAbnormal: function (res) { done(message(res, '操作没有完成，请稍后再试'), null) }
  }
  if (method === 'POST') { param.header = jsonHeader(); param.data = data || {} }
  else if (data) param.data = data
  app().sendRequest(param)
}

function overview(done) { call('/api/payout/request/overview', 'GET', null, done) }
function submit(amount, done) { call('/api/payout/request/submit', 'POST', { amount: String(amount) }, done) }
function cancel(id, done) { call('/api/payout/request/cancel', 'POST', { id: id }, done) }
// 小程序绑定页:绑定码 + 新鲜的 wx.login code。认领后什么都还没绑定,要回到 App 里确认。
function claimBinding(bindCode, jsCode, done) {
  call('/api/payout/binding/claim', 'POST', { bindCode: bindCode, jsCode: jsCode }, done)
}

// 扫普通链接二维码打开时 options.q 是整条链接(已 encodeURIComponent);也接受 ?bc= 直接传入。
function bindCodeFromOptions(options) {
  var opts = options || {}
  if (opts.bc) return normalizeCode(opts.bc)
  if (opts.q) {
    var url = ''
    try { url = decodeURIComponent(opts.q) } catch (e) { url = '' }
    var match = /[?&]bc=([A-Za-z0-9_-]+)/.exec(url)
    if (match) return normalizeCode(match[1])
  }
  return ''
}

function normalizeCode(raw) {
  var value = String(raw || '').trim()
  return /^[A-Za-z0-9_-]{24}$/.test(value) ? value : ''
}

var STATUS_TEXT = {
  PENDING: '等待平台审核',
  PAYING: '审核通过，打款中',
  SENT: '已打款到微信零钱',
  FAILED: '打款没有成功',
  REJECTED: '未通过审核',
  CANCELLED: '已撤回'
}

function statusText(status) { return STATUS_TEXT[status] || '处理中' }

// 金额输入:正数、最多两位小数。返回规范字符串或 ''。
function normalizeAmount(raw) {
  var value = String(raw || '').trim()
  if (!/^\d+(\.\d{1,2})?$/.test(value)) return ''
  return Number(value) > 0 ? value : ''
}

module.exports = {
  withdrawalOpen: withdrawalOpen,
  overview: overview,
  submit: submit,
  cancel: cancel,
  claimBinding: claimBinding,
  bindCodeFromOptions: bindCodeFromOptions,
  normalizeCode: normalizeCode,
  normalizeAmount: normalizeAmount,
  statusText: statusText
}
