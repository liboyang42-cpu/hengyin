'use strict'
// 系统 tab 的「举报进度」:取 GET /api/v1/community/notifications 里的举报进度通知。
// 来源:CommunityTrustServiceImpl.enqueueReportProgress → COMMUNITY_NOTIFICATION outbox → community_notification,
//   notification_type=SYSTEM_GOVERNANCE,payload_json 含 reportId / action(REPORT_*)/ publicStage / publicNote。
// 同类型里还有帖子审核结果(无 reportId),这里只取举报进度;同一举报只留最新一条(接口按 id 倒序)。
// 放在本分包:小程序不允许跨分包 require,举报详情页在 subpackageMember。
const REPORT_DETAIL_ROUTE = '/subpackageMember/report-detail/index'

function text(value) {
  return value == null ? '' : String(value).trim()
}

function positiveId(value) {
  const n = Number(value)
  return Number.isSafeInteger(n) && n > 0 ? n : 0
}

function parsePayload(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value
  if (typeof value !== 'string' || !value) return null
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
  } catch (e) { return null }
}

// items 不是数组 → null(整份不可信,页面显示读取失败);合法但没有举报进度 → []
function reportNoticeRows(items) {
  if (!Array.isArray(items)) return null
  const seen = {}
  const rows = []
  for (let i = 0; i < items.length; i += 1) {
    const item = items[i]
    if (!item || text(item.notification_type) !== 'SYSTEM_GOVERNANCE') continue
    const payload = parsePayload(item.payload_json)
    const reportId = positiveId(payload && payload.reportId)
    if (!reportId || !/^REPORT_/.test(text(payload.action)) || seen[reportId]) continue
    seen[reportId] = 1
    rows.push({
      key: String(item.id || reportId),
      // 标记已读用(POST /api/v1/community/notifications/{id}/read);没有通知 id 就不发
      notificationId: positiveId(item.id) ? String(positiveId(item.id)) : '',
      reportId: String(reportId),
      title: text(payload.publicNote) || '举报进度有更新',
      createTime: item.create_time == null ? '' : item.create_time,
      unread: item.read_at == null || item.read_at === '',
    })
  }
  return rows
}

function reportDetailUrl(reportId) {
  const id = positiveId(reportId)
  return id ? REPORT_DETAIL_ROUTE + '?reportId=' + id : ''
}

// 标记已读的幂等键:后端要求 8–96 字符,同键重放只记一次回执
function makeReadRequestId(notificationId, nowMs, randomPart) {
  const id = positiveId(notificationId)
  const now = Number(nowMs) || Date.now()
  const rand = text(randomPart) || Math.random().toString(36).slice(2, 10)
  return ('ntr-' + id + '-' + now.toString(36) + '-' + rand).slice(0, 96)
}

module.exports = { reportNoticeRows, reportDetailUrl, makeReadRequestId }
