// 2026-11-02 用户:主理人、副主理人、本场执行方(以及主理人勾了「可见客户手机号」的核销员)
// 游戏中要能给玩家打电话。号码与能不能拨都由服务端判(phoneCallable / 名册 phone),
// 这里只认 11 位手机号,不做任何权限推断,也不打日志。
'use strict'

const MOBILE = /^1\d{10}$/

function callableNumber(value) {
  const phone = String(value == null ? '' : value).trim()
  return MOBILE.test(phone) ? phone : ''
}

function callMember(phone, onFail) {
  const number = callableNumber(phone)
  if (!number) return false
  wx.makePhoneCall({
    phoneNumber: number,
    fail(err) {
      // 用户自己取消拨号不算失败
      const msg = String((err && err.errMsg) || '')
      if (/cancel/i.test(msg)) return
      if (typeof onFail === 'function') onFail()
    },
  })
  return true
}

module.exports = { callableNumber, callMember }
