// l-pass 通行证开通编排(从视图模块拆出,台账按调用点归属写接口)。
// 「已开通」只认服务端订单状态 PAID;requestVirtualPayment 的成功回调不算已付。
// 2026-11-13:通行证只走虚拟支付(wx.requestVirtualPayment),不再有普通 JSAPI 支付。
'use strict'

function orderPaid(order) { return !!(order && order.status === 'PAID') }

function bizOk(res) { return res && String(res.code) === '200' && res.data }

function newRequestId() {
  return 'pp' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}

/**
 * 开通流程。返回 Promise<{ state: 'paid'|'pending'|'cancelled'|'failed', order?, orderNo?, message? }>。
 * 只走虚拟支付(XPAY),不再有 JSAPI 分支。opts.requestId:同一次购买意图重试沿用同一个,服务端按它幂等。
 * deps: { request(opts)->Promise<res>, wx }
 */
function purchase(deps, planCode, channel, opts) {
  const request = deps.request
  const requestId = (opts && opts.requestId) || newRequestId()
  if (channel !== 'XPAY') return Promise.resolve({ state: 'failed', message: '当前设备暂不支持开通' })
  return request({ url: '/api/platform-pass/orders', method: 'POST', data: { planCode, channel, requestId } })
    .then((res) => {
      if (!bizOk(res)) return { state: 'failed', message: res && res.msg }
      return proceed(deps, res.data)
    }, () => ({ state: 'failed' }))
}

/** 已有订单继续:PAID 直接返回;待支付再拉起支付;其他状态只回查,不再支付。 */
function resume(deps, orderNo, canPay) {
  const sendRequest = deps.request
  return sendRequest({ url: '/api/platform-pass/orders/' + encodeURIComponent(orderNo), method: 'GET' }).then((res) => {
    if (!bizOk(res)) return { state: 'failed', orderNo, message: res && res.msg }
    const order = res.data
    if (orderPaid(order)) return { state: 'paid', order, orderNo }
    if (order.status !== 'PENDING') return { state: 'closed', order, orderNo }
    if (!canPay || order.channel !== 'XPAY') return { state: 'pending', order, orderNo }
    return proceed(deps, order)
  }, () => ({ state: 'failed', orderNo }))
}

function proceed(deps, order) {
  const request = deps.request
  const sendRequest = deps.request // 轮询读单:与 app.sendRequest 同形(GET 权限矩阵按调用点登记)
  const wxApi = deps.wx
  const pollTimes = deps.pollTimes || 4
  const wait = deps.wait || ((ms) => new Promise((r) => setTimeout(r, ms)))
  const orderNo = order.orderNo

  function poll(n) {
    return sendRequest({ url: '/api/platform-pass/orders/' + orderNo, method: 'GET' }).then((res) => {
      const o = bizOk(res) ? res.data : null
      if (orderPaid(o)) return { state: 'paid', order: o, orderNo }
      if (n <= 1) return { state: 'pending', order: o, orderNo }
      return wait(1500).then(() => poll(n - 1))
    }, () => (n <= 1 ? { state: 'pending', orderNo } : wait(1500).then(() => poll(n - 1))))
  }

  function payXpay(vp) {
    return new Promise((resolve) => {
      if (!wxApi.requestVirtualPayment) { resolve('fail'); return }
      wxApi.requestVirtualPayment({
        mode: vp.mode, signData: vp.signData, paySig: vp.paySig, signature: vp.signature,
        success: () => resolve('ok'),
        fail: (e) => resolve(e && (Number(e.errCode) === -2 || /cancel/.test(String(e.errMsg || ''))) ? 'cancel' : 'fail')
      })
    })
  }

  function login() {
    return new Promise((resolve) => wxApi.login({ success: (r) => resolve(r && r.code), fail: () => resolve('') }))
  }

  return login()
    .then((jsCode) => request({ url: '/api/platform-pass/orders/' + orderNo + '/pay', method: 'POST', data: jsCode ? { jsCode } : {} }))
    .then((payRes) => {
      if (!bizOk(payRes)) return { state: 'failed', orderNo, message: payRes && payRes.msg }
      const d = payRes.data
      return (d.virtualPayment ? payXpay(d.virtualPayment) : Promise.resolve('fail')).then((r) => {
        // 取消支付只是这一次没付,订单与已有会员都不动
        if (r === 'cancel') return { state: 'cancelled', orderNo }
        // 失败也回查一次:SDK 失败不等于没扣款;成功也必须等服务端 PAID
        return request({ url: '/api/platform-pass/orders/' + orderNo + '/confirm', method: 'POST' })
          .then((c) => (bizOk(c) && orderPaid(c.data) ? { state: 'paid', order: c.data, orderNo } : null), () => null)
          .then((done) => done || (r === 'ok' ? poll(pollTimes) : poll(1).then((x) => (x.state === 'paid' ? x : { state: 'failed', orderNo }))))
      })
    }, () => ({ state: 'failed', orderNo }))
}

module.exports = { purchase, resume, newRequestId, orderPaid }
