'use strict';

function scope(context) {
  if (!context || !/^[1-9]\d*$/.test(context.memberId) || !/^[1-9]\d*$/.test(context.nodeId)) throw Error('照片提交记录暂不可用，请重新进入核对');
  return 'advanced_photo_pending:' + [context.memberId, context.activityId || '0', context.topicId || '0', context.nodeId]
    .map(encodeURIComponent).join(':');
}
function valid(command) {
  return command && Number.isSafeInteger(command.sessionId) && command.sessionId > 0
    && Number.isSafeInteger(command.version) && command.version >= 0
    && typeof command.idempotencyKey === 'string' && command.idempotencyKey.length > 0 && command.idempotencyKey.length <= 64
    && command.action === 'SUBMIT_PHOTO_CHECK' && command.payload
    && Object.keys(command.payload).length === 1 && /^media-asset:[1-9]\d{0,18}$/.test(command.payload.imageUrl);
}
function save(storage, context, command) {
  if (!valid(command)) throw Error('照片尚未准备好，请重新上传');
  const existing = read(storage, context);
  if (existing) {
    if (JSON.stringify(existing.value.command) === JSON.stringify(command)) return existing;
    throw Error('已有照片结果待确认，请先核对原提交');
  }
  const index = scope(context), key = index + ':s' + command.sessionId + ':' + encodeURIComponent(command.idempotencyKey);
  const value = { memberId: context.memberId, activityId: context.activityId, topicId: context.topicId,
    nodeId: context.nodeId, command: JSON.parse(JSON.stringify(command)) };
  const pending = { index, key, value };
  storage.setStorageSync(key, value);
  storage.setStorageSync(index, key);
  if (storage.getStorageSync(index) !== key || JSON.stringify(storage.getStorageSync(key)) !== JSON.stringify(value)) throw Error('照片提交记录未保存，请稍后重试');
  return pending;
}
function read(storage, context) {
  const index = scope(context), key = storage.getStorageSync(index);
  if (!key) return null;
  if (typeof key !== 'string' || !key.startsWith(index + ':s')) throw Error('照片提交记录暂不可用，请稍后核对');
  const value = storage.getStorageSync(key);
  if (!value || !valid(value.command) || value.memberId !== context.memberId || value.nodeId !== context.nodeId
      || value.topicId !== context.topicId || value.activityId !== context.activityId
      || key !== index + ':s' + value.command.sessionId + ':' + encodeURIComponent(value.command.idempotencyKey)) {
    throw Error('照片提交记录暂不可用，请稍后核对');
  }
  return { index, key, value };
}
function clear(storage, pending) {
  if (!pending) return;
  // Captured original keys: a late A response must never remove B's command or A's later attempt.
  try {
    if (storage.getStorageSync(pending.index) === pending.key) storage.removeStorageSync(pending.index);
    storage.removeStorageSync(pending.key);
    return true;
  } catch (_) {
    // The verified terminal result remains valid; any retained row can only replay that original receipt.
    return false;
  }
}
module.exports = { save, read, clear, valid };
