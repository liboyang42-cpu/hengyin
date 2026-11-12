'use strict'

/**
 * 队伍小程序码 + 加入码(l-misc,2026-11-10 业主「可以的 需要的」)。
 * 后端 POST /api/team/invite-qrcode { teamId } → { teamId, inviteCode, qrState: READY|UNAVAILABLE, qrcodeUrl? }
 *   - READY:码图 + 加入码;
 *   - UNAVAILABLE:微信接口不可用(签发失败 / 频控 / 存储),只给加入码,不算失败;
 *   - 业务拒绝(非本队成员、邀请已失效)与网络失败:只显示原因,不出码也不出加入码。
 * 码图只来自服务端;没有 qrcodeUrl 就不画码。加入码只来自服务端回包,不用页面里缓存的 team.inviteCode 冒充。
 */
const URL = '/api/team/invite-qrcode'

function viewOf(res, imgUrl) {
  const ok = res && (res.code == 200 || res.code == '200') && res.data && typeof res.data === 'object'
  if (!ok) {
    return { state: 'error', qr: '', code: '', message: (res && res.msg) || '加入码没读到' }
  }
  const code = typeof res.data.inviteCode === 'string' ? res.data.inviteCode.trim() : ''
  if (!code) return { state: 'error', qr: '', code: '', message: '邀请已失效' }
  const raw = res.data.qrState === 'READY' && typeof res.data.qrcodeUrl === 'string' ? res.data.qrcodeUrl.trim() : ''
  const qr = raw ? (typeof imgUrl === 'function' ? imgUrl(raw) : raw) : ''
  return { state: qr ? 'ready' : 'code', qr, code, message: '' }
}

function load(app, teamId, done) {
  app.sendRequest({
    url: URL, method: 'POST', hideLoading: true, autoErrorToast: false,
    data: JSON.stringify({ teamId }),
    header: { 'Content-Type': 'application/json' },
    success(res) { done(viewOf(res, app.getImgUrl && app.getImgUrl.bind(app))) },
    fail() { done({ state: 'error', qr: '', code: '', message: '网络不太好，稍后再试' }) },
  })
}

/** 去掉手输空白，六位码不区分大小写；兼容仍未过期的两种旧码。 */
function normalizeJoinCode(text) {
  const code = String(text == null ? '' : text).replace(/\s+/g, '')
  if (/^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}$/i.test(code)) return code.toUpperCase()
  if (/^[A-Za-z0-9_-]{22}$/.test(code)) return code
  if (/^[0-9a-fA-F]{32}$/.test(code)) return code.toLowerCase()
  return ''
}

module.exports = { URL, viewOf, load, normalizeJoinCode }
