// U03-04：统一核对 status / checkStatus / userStatus / lifecycle / publishStatus 口径，保证同对象跨页展示一致。
const TOPIC_LIFECYCLE_MAP = {
  0: { label: '草稿', tone: 'neutral', tagType: 'info' },
  1: { label: '招募中', tone: 'warning', tagType: 'warning' },
  2: { label: '待定价', tone: 'warning', tagType: 'warning' },
  3: { label: '售票中', tone: 'brand', tagType: 'primary' },
  4: { label: '进行中', tone: 'success', tagType: 'success' },
  5: { label: '结算中', tone: 'warning', tagType: 'warning' },
  6: { label: '已关闭', tone: 'neutral', tagType: 'info' }
}

const CHECK_STATUS_MAP = {
  0: { label: '待审核', tone: 'warning', tagType: 'warning' },
  1: { label: '审核通过', tone: 'success', tagType: 'success' },
  2: { label: '审核驳回', tone: 'danger', tagType: 'danger' },
  3: { label: '升级审核', tone: 'brand', tagType: 'warning' },
  4: { label: '已取消', tone: 'neutral', tagType: 'info' }
}

const USER_STATUS_MAP = {
  0: { label: '用户端未上架', tone: 'neutral', tagType: 'info' },
  1: { label: '用户端已上架', tone: 'success', tagType: 'success' }
}

export function topicLifecycleMeta(value) {
  const n = value === null || value === undefined || value === '' ? NaN : Number(value)
  return Object.prototype.hasOwnProperty.call(TOPIC_LIFECYCLE_MAP, n)
    ? TOPIC_LIFECYCLE_MAP[n]
    : { label: '阶段未知', tone: 'neutral', tagType: 'info' }
}

export function topicCheckStatusMeta(value) {
  const n = value === null || value === undefined || value === '' ? NaN : Number(value)
  return Object.prototype.hasOwnProperty.call(CHECK_STATUS_MAP, n)
    ? CHECK_STATUS_MAP[n]
    : { label: '状态待确认', tone: 'warning', tagType: 'warning' }
}

export function topicUserStatusMeta(value) {
  const n = value === null || value === undefined || value === '' ? NaN : Number(value)
  return Object.prototype.hasOwnProperty.call(USER_STATUS_MAP, n)
    ? USER_STATUS_MAP[n]
    : { label: '用户端状态未定义', tone: 'neutral', tagType: 'info' }
}

export function templateAuditMeta(value) {
  const n = value === null || value === undefined || value === '' ? NaN : Number(value)
  if (n === 0 || n === 1 || n === 2) return CHECK_STATUS_MAP[n]
  return { label: '待确认', tone: 'warning', tagType: 'warning' }
}

export function templatePublishMeta(value) {
  const n = value === null || value === undefined || value === '' ? NaN : Number(value)
  if (n === 1) return { label: '已上架', tone: 'success', tagType: 'success' }
  if (n === 0) return { label: '未上架', tone: 'neutral', tagType: 'info' }
  return { label: '状态待确认', tone: 'warning', tagType: 'warning' }
}
