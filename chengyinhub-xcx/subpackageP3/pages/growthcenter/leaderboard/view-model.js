// 完整排行榜的纯判定(无 wx / 无 this):页面与单测共用。
//
// U9(2026-11-19):「我」已经在榜单里(领奖台或第 4 名之后)时,底部不再钉一条重复的「我」行。
// 判据只认 memberId —— 同分并列时 rank 可能重复,按 rank 判会把别人当成我、把我的钉行误删。
// 公开榜(未登录)不下发 memberId 和 me,自然不会命中。

function sameMember(a, b) {
  if (a === null || a === undefined || a === '' || b === null || b === undefined || b === '') return false
  return String(a) === String(b)
}

/** 我是否已出现在当前榜单列表里(list = 接口原始 list) */
function isMeListed(list, me) {
  if (!me || !Array.isArray(list)) return false
  return list.some((row) => row && sameMember(row.memberId, me.memberId))
}

/** 底部钉行是否显示:有我的名次,且我不在列表里 */
function shouldPinMe(list, me) {
  if (!me || me.rank === null || me.rank === undefined) return false
  return !isMeListed(list, me)
}

module.exports = { sameMember, isMeListed, shouldPinMe }
