import request from '@/utils/request'
import { tansParams } from '@/utils/ruoyi'

export function scoringId(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('编号精度已丢失，请重新读取')
  const id = String(value)
  if (!/^[1-9]\d{0,18}$/.test(id) || (id.length === 19 && id > '9223372036854775807')) throw new Error('请输入有效的完整编号')
  return id
}

export function parseScoringResponse(raw) {
  const response = typeof raw === 'string' ? JSON.parse(raw.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
    token => /^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token)) ? `"${token}"` : token)) : raw
  if (response && response.code !== 200 && response.errorCode) {
    const error = new Error(response.msg || '计分请求被拒绝')
    error.scoringCode = response.errorCode
    throw error
  }
  return response
}

export function formatCenti(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '分值精度异常'
  const raw = String(value)
  if (!/^-?\d+$/.test(raw)) return '未提供'
  const digits = raw.replace(/^-/, '').replace(/^0+(?=\d)/, '').padStart(3, '0')
  const sign = /^0+$/.test(digits) ? '' : raw.startsWith('-') ? '-' : '+'
  return `${sign}${digits.slice(0, -2)}.${digits.slice(-2)} 分`
}

export function adjustmentCenti(value) {
  const text = String(value).trim()
  if (!/^-?\d+$/.test(text)) throw new Error('修正分值必须是有符号整数 centi')
  const amount = Number(text)
  if (!Number.isInteger(amount) || amount === 0 || amount < -2147483647 || amount > 2147483647) {
    throw new Error('修正分值必须是非零 int32 整数 centi（-2147483647 至 2147483647）')
  }
  return String(amount)
}

export function listScoringAppeals(status) {
  return request({ url: '/business/orient/scoring/appeals', method: 'get', params: { status: status === '' ? undefined : status, limit: 200 }, transformResponse: [parseScoringResponse] })
}

export function listScoringDisputes(status) {
  return request({ url: '/business/orient/scoring/disputes', method: 'get', params: { status: status === '' ? undefined : status, limit: 200 }, transformResponse: [parseScoringResponse] })
}

export function getScoringSession(sessionId) {
  return request({ url: `/business/orient/scoring/session/${scoringId(sessionId)}`, method: 'get', transformResponse: [parseScoringResponse] })
}

export function verifyScoringSession(sessionId) {
  return request({ url: `/business/orient/scoring/session/${scoringId(sessionId)}/verify`, method: 'get', transformResponse: [parseScoringResponse] })
}

export function resolveScoringAppeal(id, data) {
  return request({ url: `/business/orient/scoring/appeals/${scoringId(id)}/resolve`, method: 'post', data: tansParams(data), headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, transformResponse: [parseScoringResponse] })
}

export function resolveScoringDispute(id, data) {
  return request({ url: `/business/orient/scoring/disputes/${scoringId(id)}/resolve`, method: 'post', data: tansParams(data), headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, transformResponse: [parseScoringResponse] })
}

export function reverseScoringEntry(id, data) {
  return request({ url: `/business/orient/scoring/entries/${scoringId(id)}/reverse`, method: 'post', data: tansParams(data), headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, transformResponse: [parseScoringResponse] })
}

export function listScoringRecoveries(status) {
  return request({ url: '/business/orient/scoring/recoveries', method: 'get', params: { status: status === '' ? undefined : status, limit: 200 }, transformResponse: [parseScoringResponse] })
}

export function reviewScoringRecovery(id, data) {
  return request({ url: `/business/orient/scoring/recoveries/${scoringId(id)}/review`, method: 'post', data: tansParams(data), headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, transformResponse: [parseScoringResponse] })
}
