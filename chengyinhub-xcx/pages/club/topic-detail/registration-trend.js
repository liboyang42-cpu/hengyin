'use strict'

/* BL-98 报名按日趋势(只读)。
 * POST /api/registration/count_by_date(owner_type=1 主题, owner_id=topicId,form 绑定)
 * 回 [{ participateDate, totalCount }]:participateDate 是 cms_registration.participate_date
 * (varchar yyyy-MM-dd,参与日期,不是下单日期);totalCount = SUM(IFNULL(order_num,1)),
 * 只算 registration_status=2 且带参与日期的单。后端已按日期升序,这里原样保序,不补零日。 */
function presentTrendRows(data) {
  if (!Array.isArray(data)) return null
  const rows = []
  for (let i = 0; i < data.length; i++) {
    const item = data[i]
    if (!item || typeof item !== 'object') return null
    const date = typeof item.participateDate === 'string' ? item.participateDate.trim() : ''
    const count = Number(item.totalCount)
    if (!date || !Number.isFinite(count)) return null
    rows.push({ key: date, dateText: date, countText: String(count) + ' 人' })
  }
  return rows
}

module.exports = { presentTrendRows }
