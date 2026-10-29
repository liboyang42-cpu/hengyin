// Only topic cancellation cursors. No login, authority, timer, automatic retry or money calculation.
function epoch(value) {
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value < 0)) throw new Error('取消轮次无效');
  if (typeof value !== 'number' && typeof value !== 'string') throw new Error('取消轮次缺失，请刷新');
  const out = String(value);
  if (!/^(0|[1-9][0-9]{0,18})$/.test(out) || out.length === 19 && out > '9223372036854775807') throw new Error('取消轮次无效');
  return out;
}
function identity(actor, scope, id) {
  actor = String(actor || ''); id = String(id || ''); scope = String(scope || '');
  if (!/^[1-9][0-9]{0,18}$/.test(actor) || !/^[1-9][0-9]{0,18}$/.test(id)
      || !/^(|MERCHANT|CLUB:[1-9][0-9]{0,18})$/.test(scope)) throw new Error('取消身份不完整，请刷新');
  return { actor, scope, id, key: 'topic-cancel:v1:' + actor + ':' + scope + ':' + id };
}
function peek(actor, scope, id) {
  const who = identity(actor, scope, id);
  const raw = wx.getStorageSync(who.key);
  if (raw === undefined || raw === null || raw === '') return null;
  if (typeof raw !== 'object' || Array.isArray(raw)
      || Object.keys(raw).sort().join('|') !== 'actor|count|epoch|id|scope|version'
      || raw.version !== 1 || raw.actor !== who.actor || raw.scope !== who.scope || raw.id !== who.id
      || epoch(raw.epoch) !== raw.epoch || !Number.isSafeInteger(raw.count) || raw.count < 0)
    throw new Error('原取消请求无法确认，请人工核对，不能另建请求');
  return raw;
}
function remember(actor, scope, id, selectedEpoch, count) {
  const who = identity(actor, scope, id), selected = epoch(selectedEpoch);
  if (!Number.isSafeInteger(count) || count < 0) throw new Error('取消确认范围无效');
  const previous = peek(actor, scope, id);
  if (previous) {
    if (previous.epoch !== selected || previous.count !== count) throw new Error('请先查回原取消结果');
    return previous;
  }
  const value = { version: 1, actor: who.actor, scope: who.scope, id: who.id, epoch: selected, count };
  wx.setStorageSync(who.key, value);
  const readback = peek(actor, scope, id);
  if (!readback || readback.epoch !== selected || readback.count !== count) throw new Error('原取消请求未保存，不发送');
  return readback;
}
function acknowledge(actor, scope, id, selectedEpoch) {
  const who = identity(actor, scope, id), selected = epoch(selectedEpoch), original = peek(actor, scope, id);
  if (!original || original.epoch !== selected) throw new Error('这次结果与上次取消不一致，请重新查看');
  wx.removeStorageSync(who.key);
  if (peek(actor, scope, id) !== null) throw new Error('原取消记录清理未确认，请保留原请求');
}
module.exports = { epoch, peek, remember, acknowledge };
