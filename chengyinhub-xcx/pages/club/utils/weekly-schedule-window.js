/**
 * 每周排期的期数上限 · 与 ClubEventScheduleMaterializer 的 90 天窗口同口径。
 *
 * 后端对「首次日期起 90 天内放不下的期数」直接拒绝(不再静默少排);
 * 表单先用同一个上海日历算出上限,提交前就把「最多 N 期」说清楚。
 * 日期一律是 yyyy-MM-dd 上海日历键,不经设备时区。
 */
const MAX_FUTURE_DAYS = 90
const MAX_OCCURRENCES = 64
const DAY_MS = 24 * 60 * 60 * 1000

function dayNumber(dateKey) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(dateKey || ''))
  if (!m) return NaN
  return Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) / DAY_MS
}

/** 首次日期 startDate 起、今天 todayKey + 90 天之内能排的每周期数;日期不在窗口内返回 0。 */
function maxWeeklyOccurrences(startDate, todayKey) {
  const start = dayNumber(startDate)
  const today = dayNumber(todayKey)
  if (!Number.isFinite(start) || !Number.isFinite(today)) return 0
  const horizon = today + MAX_FUTURE_DAYS
  if (start < today || start > horizon) return 0
  return Math.min(MAX_OCCURRENCES, Math.floor((horizon - start) / 7) + 1)
}

module.exports = { MAX_FUTURE_DAYS, MAX_OCCURRENCES, maxWeeklyOccurrences }
