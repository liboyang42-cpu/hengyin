// One unresolved send per administrator and recipient. No token is stored.
export function adminSendIntent(storage, adminId, memberId, content, createId) {
  const key = intentKey(adminId, memberId)
  const raw = storage.getItem(key)
  if (raw) {
    const prior = JSON.parse(raw)
    if (!prior || prior.adminId !== String(adminId) || prior.memberId !== String(memberId) ||
        !/^[A-Za-z0-9_-]{8,96}$/.test(prior.requestId) || typeof prior.content !== 'string' || prior.msgType !== 1) {
      throw new Error('原发送记录无法读取，请保留记录并核查')
    }
    return prior
  }
  if (!content || !content.trim()) return null
  const intent = { adminId: String(adminId), memberId: String(memberId), msgType: 1,
    content: content.trim(), requestId: createId() }
  storage.setItem(key, JSON.stringify(intent))
  if (storage.getItem(key) !== JSON.stringify(intent)) throw new Error('原发送记录未能保存，尚未发送')
  return intent
}
export function clearAdminSendIntent(storage, intent) {
  const key = intentKey(intent.adminId, intent.memberId)
  const raw = storage.getItem(key)
  if (raw && JSON.parse(raw).requestId === intent.requestId) {
    storage.removeItem(key)
    if (storage.getItem(key)) throw new Error('消息已发送，原发送记录尚未清除')
  }
}
function intentKey(adminId, memberId) {
  if (!/^[1-9][0-9]*$/.test(String(adminId)) || !/^[1-9][0-9]*$/.test(String(memberId))) {
    throw new Error('管理员或接收人身份无效')
  }
  return `im-admin-send:${adminId}:${memberId}`
}
