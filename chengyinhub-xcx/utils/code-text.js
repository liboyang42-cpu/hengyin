/**
 * utils/code-text.js —— 打卡码/团码「二维码不可用时的文本兜底」
 *
 * 仅用于已登录的商家/组织者页面(据点打卡码、团码)。码图缺失但服务端给了 code 时,
 * 页面显示等宽文本 + 长按复制,而不是整块报错。不在玩家端/公开页展示。
 */
function pickCode(data) {
  const v = data && data.code
  if (typeof v === 'number' && Number.isFinite(v)) return String(v)
  return typeof v === 'string' ? v.trim() : ''
}

/** 二维码可用,或无图但有文本码 ⇒ 可视为「出码成功」 */
function usable(data) {
  const url = data && typeof data.qrcodeUrl === 'string' ? data.qrcodeUrl.trim() : ''
  return !!(url || pickCode(data))
}

function copy(code, env) {
  const w = env || (typeof wx !== 'undefined' ? wx : null)
  const text = typeof code === 'string' ? code.trim() : ''
  if (!text || !w || typeof w.setClipboardData !== 'function') return false
  w.setClipboardData({ data: text })
  return true
}

module.exports = { pickCode, usable, copy }
