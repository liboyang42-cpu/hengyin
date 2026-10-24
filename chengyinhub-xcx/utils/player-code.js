'use strict'
// BL-71:个人小程序码只认 POST /api/user/player-code 回包 data.qr(http(s) 地址);不拼地址、不兜底假码。

function readPlayerCode(res) {
  if (!res || (res.code !== 200 && res.code !== '200')) return ''
  const qr = res.data && typeof res.data === 'object' ? res.data.qr : ''
  return typeof qr === 'string' && /^https?:\/\//.test(qr.trim()) ? qr.trim() : ''
}

module.exports = { readPlayerCode }
