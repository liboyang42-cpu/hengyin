// One unresolved reply command per actor. Only recovery identifiers are persisted.
const id = value => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
  && (value.length < 19 || value <= '9223372036854775807') ? value
  : Number.isSafeInteger(value) && value > 0 ? String(value) : '';
const actions = ['MERCHANT_REVIEW_REPLIED', 'MERCHANT_REVIEW_REPLY_UPDATED', 'MERCHANT_REVIEW_REPLY_DELETED'];
function createReplyRecovery({ app, storage, changed, applied, timer = setTimeout, cancel = clearTimeout }) {
  let owner = '', token = '', pending = null, generation = 0, deadline, busy = false, blocked = false;
  const identity = () => id(app.getUserID());
  const credential = () => typeof app.getAuthorization === 'function' ? app.getAuthorization() : '';
  const key = () => 'merchant-review-reply:' + owner;
  const emit = message => changed({ pending: !!pending || blocked, busy, requestId: pending && pending.requestId || '', message: message || '' });
  function sync() {
    if (owner === identity() && token === credential()) return;
    generation++; cancel(deadline); owner = identity(); token = credential(); busy = false; blocked = false; pending = null;
    if (owner) try {
      const row = storage.getStorageSync(key());
      if (row) {
        if (row.owner !== owner || !id(row.reviewId) || !actions.includes(row.action) || !/^[A-Za-z0-9._:-]{1,64}$/.test(row.requestId)) throw Error();
        pending = row;
      }
    } catch (_) { blocked = true; }
    emit(blocked ? '恢复记录无法读取，请保留现场；禁止新回复' : pending ? '原回复结果待确认，请读取原请求' : '');
  }
  function clear() {
    try { storage.removeStorageSync(key()); pending = null; return true; }
    catch (_) { emit('结果已返回，但恢复记录未清理，请再次读取原请求'); return false; }
  }
  function request(send, receiptOnly) {
    const epoch = ++generation, actor = owner, auth = token, original = pending;
    busy = true; emit('正在确认原回复请求');
    let settled = false;
    const current = () => epoch === generation && actor === identity() && auth === credential();
    const unknown = () => { if (!current() || settled) return; settled = true; cancel(deadline); busy = false; emit('回复结果未知；输入已冻结，请读取原请求'); };
    deadline = timer(unknown, 15000);
    const success = res => {
      if (!current() || settled) return;
      const row = res && res.data;
      const positive = String(res && res.code) === '200' && row && id(row.reviewId) === original.reviewId
        && (receiptOnly ? row.state === 'APPLIED' && row.requestId === original.requestId && row.action === original.action
          : ['VISIBLE', 'HIDDEN', 'PENDING_REVIEW'].includes(row.status) && Number.isSafeInteger(row.version));
      if (positive) {
        settled = true; cancel(deadline); busy = false;
        if (clear()) { emit('原回复已确认，正在回读评价'); applied(original.action); }
      } else if (!receiptOnly && ['400', '403', '404', '409'].includes(String(res && res.code))) {
        settled = true; cancel(deadline); busy = false;
        if (clear()) emit(res.msg || '回复已拒绝，请核对后重新提交');
      } else unknown();
    };
    try { send({ success, fail: unknown, successStatusAbnormal: unknown }); } catch (_) { unknown(); }
  }
  return {
    sync,
    pending() { sync(); return !!pending || blocked; },
    suspend() { generation++; cancel(deadline); busy = false; },
    resume() { sync(); emit(pending ? '原回复结果待确认，请读取原请求' : blocked ? '恢复记录无法读取，禁止新回复' : ''); },
    submit(action, payload) {
      sync(); if (!owner || pending || blocked || !actions.includes(action) || !id(payload.reviewId)) return;
      const frozen = JSON.parse(JSON.stringify(payload));
      pending = { owner, reviewId: id(payload.reviewId), action, requestId: payload.requestId };
      try { storage.setStorageSync(key(), pending); }
      catch (_) { pending = null; blocked = true; emit('无法保存恢复记录，未发出回复'); return; }
      request(callbacks => {
        const options = { ...callbacks, method: 'POST', header: { 'Content-Type': 'application/json' }, data: JSON.stringify(frozen) };
        if (action === 'MERCHANT_REVIEW_REPLIED') app.sendRequest({ ...options, url: '/api/merchant/reviews/reply' });
        else if (action === 'MERCHANT_REVIEW_REPLY_UPDATED') app.sendRequest({ ...options, url: '/api/merchant/reviews/reply/update' });
        else app.sendRequest({ ...options, url: '/api/merchant/reviews/reply/delete' });
      }, false);
    },
    recover() {
      sync(); if (!pending || busy) return;
      request(callbacks => app.sendRequest({ ...callbacks, method: 'POST', header: { 'Content-Type': 'application/json' },
        url: '/api/merchant/reviews/reply/receipt', data: JSON.stringify({ reviewId: pending.reviewId, action: pending.action, requestId: pending.requestId }) }), true);
    },
  };
}
module.exports = { createReplyRecovery };
