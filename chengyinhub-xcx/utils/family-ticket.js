'use strict'
/* F12 亲子票(1 大 1 小,照搬携程/美团门票「亲子票」):一张票、一个码、一次核销两个人;
 * 票种写明构成与儿童年龄段;下单只登记儿童年龄,不要姓名。首版只有 1+0 / 1+1,服务端 FamilyTicketRules 同口径。 */
const FAMILY_NAME = '亲子票（1 大 1 小）'
const AGE_FLOOR = 0
const AGE_CEILING = 17

function isFamily(ticket) { return !!ticket && Number(ticket.childCount) === 1 }

/* 「亲子票」模板按钮:新票种直接带好构成与常见年龄段(3–12 岁),作者可改 */
function familyTicketDraft() {
  return { name: FAMILY_NAME, adultCount: 1, childCount: 1, childAgeMin: 3, childAgeMax: 12 }
}

function intOrNull(v) {
  if (v === '' || v == null) return null
  const n = Number(v)
  return Number.isInteger(n) ? n : NaN
}

/* 编辑器里亲子票的拦截原因;普通票返回空串 */
function familyBlockReason(ticket) {
  if (!isFamily(ticket)) return ''
  const min = intOrNull(ticket.childAgeMin), max = intOrNull(ticket.childAgeMax)
  if (min === null || max === null) return '请填写儿童年龄段'
  if (Number.isNaN(min) || Number.isNaN(max) || min < AGE_FLOOR || max > AGE_CEILING || min > max) {
    return '儿童年龄段需在 ' + AGE_FLOOR + '–' + AGE_CEILING + ' 岁之间，且起始不大于结束'
  }
  return ''
}

/* 存票种时写给服务端的人数构成字段;普通票不带年龄段 */
function familyFields(ticket) {
  if (!isFamily(ticket)) return { adultCount: 1, childCount: 0, childAgeMin: null, childAgeMax: null }
  return { adultCount: 1, childCount: 1, childAgeMin: intOrNull(ticket.childAgeMin), childAgeMax: intOrNull(ticket.childAgeMax) }
}

/* 列表 / 票夹 / 订单上的构成短标签 */
function familyLabel(ticket) {
  if (!isFamily(ticket)) return ''
  const band = ticket.childAgeMin != null && ticket.childAgeMax != null ? '（儿童 ' + ticket.childAgeMin + '–' + ticket.childAgeMax + ' 岁）' : ''
  return '1 大 1 小' + band
}

/* 购票页儿童年龄选项:年龄段内每一岁 */
function childAgeOptions(ticket) {
  if (!isFamily(ticket)) return []
  const min = intOrNull(ticket.childAgeMin), max = intOrNull(ticket.childAgeMax)
  if (min === null || max === null || Number.isNaN(min) || Number.isNaN(max) || min > max) return []
  const out = []
  for (let a = min; a <= max; a++) out.push(a)
  return out
}

/* 购票页年龄选择的初始状态:换票种时重置,非亲子票为空(不出选择行) */
function familyAgeState(ticket) {
  const ages = childAgeOptions(ticket)
  return { childAgeOptions: ages, childAgeLabels: ages.map(a => a + ' 岁'), childAge: null }
}

module.exports = { familyAgeState, FAMILY_NAME, isFamily, familyTicketDraft, familyBlockReason, familyFields, familyLabel, childAgeOptions }
