'use strict'
// §18 I 推广来源凭据(M1):只做「拿到分享凭据」和「把凭据挂到刚下的单」,不展示任何金额/佣金/折扣。
// - 分享:POST /api/marketing/referral/token → token 拼进分享 path 的 ref;失败静默降级为普通分享。
// - 落地:读 query ref,按登录账号隔离存本地(换号失效);不显示「来自 XX 的推荐」,不参与报价。
// - 下单成功后旁路 claim:失败不上屏、最多重试 1 次;成功或任何终态码都清掉,避免挂到下一单。
const STORAGE_KEY = 'cy_marketing_ref_v1'
const MAX_TOKEN_LEN = 512

function currentMemberId() {
  try {
    const v = String(wx.getStorageSync('user_id') || '').trim()
    return /^\d{1,18}$/.test(v) && Number(v) > 0 ? v : null
  } catch (e) { return null }
}

function capture(options) {
  const raw = options && options.ref
  if (!raw) return false
  let token = ''
  try { token = decodeURIComponent(String(raw)).trim() } catch (e) { return false }
  if (!token || token.length > MAX_TOKEN_LEN) return false
  try { wx.setStorageSync(STORAGE_KEY, JSON.stringify({ token, memberId: currentMemberId() })) } catch (e) { return false }
  return true
}

function read() {
  let raw = ''
  try { raw = wx.getStorageSync(STORAGE_KEY) } catch (e) { return null }
  if (!raw) return null
  try {
    const rec = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!rec || !rec.token) return null
    // 绑定到别的账号 ⇒ 当前身份没有来源;登录前捕获的由当前登录身份认领
    if (rec.memberId && rec.memberId !== currentMemberId()) return null
    return rec
  } catch (e) { return null }
}

function drop() {
  try { wx.removeStorageSync(STORAGE_KEY) } catch (e) { /* ignore */ }
}

/** 分享 path 加 ref;不改原 path 的其它参数 */
function withRef(path, token) {
  if (!path || !token) return path
  return path + (path.indexOf('?') >= 0 ? '&' : '?') + 'ref=' + encodeURIComponent(token)
}

/**
 * 分享时异步要凭据。返回 Promise<{path}>,失败一律 resolve 原 path(静默降级为普通分享)。
 * targetType 1=主题 2=活动。
 */
function sharePathPromise(app, targetType, targetId, path) {
  return new Promise((resolve) => {
    if (!app || !app.sendRequest || !targetId || !currentMemberId()) { resolve({ path }); return }
    app.sendRequest({
      url: '/api/marketing/referral/token', method: 'POST', header: { 'Content-Type': 'application/json' },
      data: JSON.stringify({ targetType, targetId: Number(targetId) }),
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        const token = res && (res.code == 200) && res.data && (res.data.token || (typeof res.data === 'string' ? res.data : ''))
        resolve({ path: token ? withRef(path, token) : path })
      },
      fail: () => resolve({ path })
    })
  })
}

/** 下单拿到 registrationId 后调用;不影响下单结果页 */
function claimAfterOrder(app, registrationId, attempt) {
  const rec = read()
  if (!rec || !registrationId || !app || !app.sendRequest) return
  app.sendRequest({
    url: '/api/marketing/attribution/claim', method: 'POST', header: { 'Content-Type': 'application/json' },
    data: JSON.stringify({ orderType: 1, orderId: Number(registrationId), token: rec.token }),
    hideLoading: true, autoErrorToast: false,
    success: (res) => {
      // 成功与所有带 reasonCode 的拒绝都是终态:清掉,不挂到下一单
      if (res && (res.code == 200 || res.reasonCode)) { drop(); return }
      if (!attempt) claimAfterOrder(app, registrationId, 1)
    },
    fail: () => { if (!attempt) claimAfterOrder(app, registrationId, 1) }
  })
}

module.exports = { STORAGE_KEY, capture, read, drop, withRef, sharePathPromise, claimAfterOrder }
