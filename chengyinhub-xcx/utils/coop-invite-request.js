'use strict';
const { parseInviteId } = require('./coop-invite-view.js');
const validRequestId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
const responseCode = value => typeof value === 'number' && Number.isInteger(value) && value >= 100 && value <= 599
  || typeof value === 'string' && /^[1-5][0-9]{2}$/.test(value) ? Number(value) : 0;
const exactId = value => typeof value === 'string' && parseInviteId(value) === value;
function context(payload) {
  return { inviteType: Number(payload.inviteType), topicId: parseInviteId(payload.topicId),
    toType: payload.toType, toId: parseInviteId(payload.toId),
    scope: Number(payload.inviteType) === 0 && payload.scope === 'MERCHANT' ? 'MERCHANT' : '' };
}
function writeKey(owner, payload) {
  const c = context(payload);
  return owner + ':coop-invite:' + JSON.stringify([c.inviteType, c.scope, c.topicId, c.toId]);
}
function receipt(value) {
  if (!value || typeof value !== 'object' || !validRequestId(value.requestId)) return null;
  if (value.state === 'NOT_FOUND' || value.state === 'UNAVAILABLE') {
    return Object.keys(value).every(k => k === 'state' || k === 'requestId') ? { state: value.state, requestId: value.requestId } : null;
  }
  if (value.state !== 'CREATED' || !exactId(value.inviteId) || !exactId(value.topicId) || !exactId(value.toId)
      || ![0, 1].includes(value.inviteType) || value.toType !== (value.inviteType === 1 ? 'club' : 'merchant')
      || !['', 'MERCHANT'].includes(value.scope) || value.inviteType === 1 && value.scope !== '') return null;
  return { state: value.state, requestId: value.requestId, inviteId: value.inviteId,
    inviteType: value.inviteType, topicId: value.topicId, toType: value.toType, toId: value.toId, scope: value.scope };
}
function matches(row, payload) {
  const c = context(payload);
  return row && row.state === 'CREATED' && Object.keys(c).every(k => row[k] === c[k]);
}
function classify(res, httpStatus, requestId) {
  const status = responseCode(httpStatus), code = responseCode(res && res.code), unknown = { status: 'unknown' };
  if (httpStatus != null && !status || !res || res.reason === 'sessionChanged' || status === 409 || code === 409) return unknown;
  if (status && status !== 200) return status >= 400 && status < 500 && status !== 408 ? { status: 'failed', response: res } : unknown;
  // Server contract: POST success data = CoopInviteRequestResult.created(receipt, replayed).
  const created = code === 200 ? receipt(res.data) : null;
  if (created && created.state === 'CREATED' && created.requestId === requestId
      && typeof res.data.replayed === 'boolean') return { status: 'success', response: res };
  if (code >= 400 && code < 500 && code !== 408 || code === 500 && /^招商写入已关闭/.test(String(res.msg || ''))) return { status: 'failed', response: res };
  return unknown;
}
// One explicit GET per click. Raw cursors advance even when every row is unavailable.
function createReadback(options) {
  let busy = false, rows = [], cursor = null, hasMore = false, sequence = 0;
  function query(requestId, more) {
    if (busy || !options.current() || more && !hasMore) return;
    if (requestId !== null && !validRequestId(requestId)) return;
    busy = true;
    const seq = ++sequence, before = more ? cursor : null;
    options.change({ receiptLoading: true, receiptError: '' });
    let settled = false;
    function finish(error, data) {
      if (settled || seq !== sequence || !options.current()) return;
      settled = true; busy = false;
      if (error) { options.change({ receiptLoading: false, receiptError: error }); return; }
      const decoded = requestId === null ? data.rows.map(receipt) : [receipt(data)];
      const valid = decoded.every(Boolean) && (requestId === null || decoded[0].requestId === requestId);
      if (!valid) { options.change({ receiptLoading: false, receiptError: '发送结果无法确认，请勿重复发送' }); return; }
      if (requestId === null) {
        const next = data.nextCursor;
        if (typeof data.hasMore !== 'boolean' || !(next === null || exactId(next)) || data.hasMore && next === null
            || !data.hasMore && next !== null || before && next !== null && (next.length > before.length || next.length === before.length && next >= before)) {
          options.change({ receiptLoading: false, receiptError: '原请求分页无法确认，请重新查询' }); return;
        }
        if (!more) rows = [];
        cursor = next; hasMore = data.hasMore;
      }
      let settlementError = '';
      decoded.forEach(row => {
        settlementError = options.received(row) || settlementError;
        const index = rows.findIndex(r => r.requestId === row.requestId);
        if (index < 0) rows.push(row); else rows[index] = row;
      });
      options.change({ receiptLoading: false, receiptRows: rows.slice(), receiptHasMore: hasMore,
        receiptError: settlementError || (rows.length ? '' : '未查到本次发送记录；仍可能已经发出，请勿重复发送') });
    }
    const data = requestId === null ? Object.assign({ limit: 20 }, before ? { before } : {}) : { requestId };
    const request = { data,
      success(res) {
        if (!res || responseCode(res.code) !== 200 || !res.data || requestId === null && (!Array.isArray(res.data.rows) || res.data.rows.length > 20)) {
          finish('原请求暂无法查询，请勿重复发送'); return;
        }
        finish('', res.data);
      }, fail() { finish('原请求暂无法查询，请勿重复发送'); },
      successStatusAbnormal() { finish('原请求暂无法查询，请勿重复发送'); },
    };
    try {
      if (requestId === null) options.requestList(request);
      else options.requestExact(request);
    } catch (error) { finish('原请求暂无法查询，请勿重复发送'); }
  }
  return { exact: requestId => query(requestId, false), list: () => query(null, false), more: () => query(null, true),
    destroy() { sequence += 1; busy = false; } };
}
module.exports = { context, writeKey, receipt, matches, classify, createReadback };
