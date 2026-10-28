'use strict'
// 我的申诉(原型 C1 / X7)纯函数。列表先只放计分申诉与成绩争议;字段按 L8 契约 §5.1(GET /api/orient/scoring/mine):
//   kind(appeal | dispute)、status(open | accepted | rejected)、sessionId、topicId、topicName、nodeId、createTime。
// 结论理由(resolutionNote)不在列表里出,点开落到那一局游玩页的计分全屏层(原型 A)。
const { timeText, positiveId } = require('../report-common/view.js')

const TABS = [
  { key: 'all', label: '全部' },
  { key: 'active', label: '处理中' },
  { key: 'decided', label: '已有结论' },
]
const KIND = { APPEAL: '计分申诉', DISPUTE: '成绩争议' }
const REPUTATION = { NORMAL: { text: '正常', tone: 'green' }, RESTRICTED: { text: '受限', tone: 'mono' } }
const STATUS = { OPEN: 'PENDING', ACCEPTED: 'ACCEPTED', REJECTED: 'REJECTED' }

function text(value) { return value == null ? '' : String(value).trim() }

function dateText(value) {
  const m = /^\d{4}-(\d{2})-(\d{2})/.exec(timeText(value))
  return m ? Number(m[1]) + '月' + Number(m[2]) + '日' : ''
}

function normalizeAppeal(raw) {
  if (!raw || typeof raw !== 'object') return null
  const kind = text(raw.kind).toUpperCase(), status = STATUS[text(raw.status).toUpperCase()]
  const id = positiveId(raw.id), sessionId = positiveId(raw.sessionId)
  if (!KIND[kind] || !id || !status) return null
  const node = positiveId(raw.nodeIndex)
  const decided = status !== 'PENDING'
  return {
    key: kind + ':' + id,
    title: KIND[kind] + (kind === 'APPEAL' && node ? ' · 第 ' + node + ' 个节点' : ''),
    sub: [text(raw.topicName), dateText(raw.createTime)].filter(Boolean).join(' · '),
    decided,
    tagText: decided ? '已有结论' : '处理中',
    tagTone: !decided ? 'blue' : status === 'ACCEPTED' ? 'green' : 'mono',
    sessionId,
    topicId: positiveId(raw.topicId),
  }
}

function normalizeList(list) {
  if (!Array.isArray(list)) return null
  return list.map(normalizeAppeal).filter(Boolean)
}

function filterRows(rows, tab) {
  if (tab === 'active') return rows.filter(row => !row.decided)
  if (tab === 'decided') return rows.filter(row => row.decided)
  return rows
}

function reputation(raw) {
  return REPUTATION[text(raw && raw.status).toUpperCase()] || null
}

module.exports = { TABS, normalizeAppeal, normalizeList, filterRows, reputation, dateText }
