// l-pass 通行证开通编排(从视图模块拆出,台账按调用点归属写接口)。
// 「已开通」只认服务端订单状态 PAID;wx.requestPayment / requestVirtualPayment 的成功回调不算已付。
'use strict'

function orderPaid(order) { return !!(order && order.status === 'PAID') }

function newRequestId() {
  return 'pp' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}

/**
 * 开通流程。返回 Promise<{ state: 'paid'|'pending'|'cancelled'|'failed', order?, message? }>。
 * deps: { request(opts)->Promise<res>, wx }
 */
function purchase(deps, planCode, channel) {
  const request = deps.request
  const sendRequest = deps.request // 轮询读单:与 app.sendRequest 同形(GET 权限矩阵按调用点登记)
  const wxApi = deps.wx
  const pollTimes = deps.pollTimes || 4
  const wait = deps.wait || ((ms) => new Promise((r) => setTimeout(r, ms)))
  const bizOk = (res) => res && String(res.code) === '200' && res.data
  let orderNo = ''

  function poll(n) {
    return sendRequest({ url: '/api/platform-pass/orders/' + orderNo, method: 'GET' }).then((res) => {
      const order = bizOk(res) ? res.data : null
      if (orderPaid(order)) return { state: 'paid', order }
      if (n <= 1) return { state: 'pending', order }
      return wait(1500).then(() => poll(n - 1))
    }, () => (n <= 1 ? { state: 'pending' } : wait(1500).then(() => poll(n - 1))))
  }

  function payJsapi(params) {
    return new Promise((resolve) => {
      wxApi.requestPayment(Object.assign({}, params, {
        success: () => resolve('ok'),
        fail: (e) => resolve(e && /cancel/.test(String(e.errMsg || '')) ? 'cancel' : 'fail')
      }))
    })
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

  return request({ url: '/api/platform-pass/orders', method: 'POST', data: { planCode, channel, requestId: newRequestId() } })
    .then((res) => {
      if (!bizOk(res)) return { state: 'failed', message: res && res.msg }
      orderNo = res.data.orderNo
      return (channel === 'XPAY' ? login() : Promise.resolve(''))
        .then((jsCode) => request({ url: '/api/platform-pass/orders/' + orderNo + '/pay', method: 'POST', data: jsCode ? { jsCode } : {} }))
        .then((payRes) => {
          if (!bizOk(payRes)) return { state: 'failed', message: payRes && payRes.msg }
          const d = payRes.data
          const run = channel === 'XPAY' ? (d.virtualPayment ? payXpay(d.virtualPayment) : Promise.resolve('fail'))
            : (d.payParams ? payJsapi(d.payParams) : Promise.resolve('fail'))
          return run.then((r) => {
            if (r === 'cancel') return { state: 'cancelled' }
            // 失败也回查一次:SDK 失败不等于没扣款;成功也必须等服务端 PAID
            const confirm = channel === 'XPAY'
              ? request({ url: '/api/platform-pass/orders/' + orderNo + '/confirm', method: 'POST' }).then((c) => (bizOk(c) && orderPaid(c.data) ? { state: 'paid', order: c.data } : null), () => null)
              : Promise.resolve(null)
            return confirm.then((done) => done || (r === 'ok' ? poll(pollTimes) : poll(1).then((x) => (x.state === 'paid' ? x : { state: 'failed' }))))
          })
        })
    }, () => ({ state: 'failed' }))
}

module.exports = { purchase, newRequestId, orderPaid }
