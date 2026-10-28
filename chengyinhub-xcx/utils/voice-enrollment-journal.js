'use strict';

// Same session-generation guard used by seat recovery; no token/sample/provider input is persisted.
const { captureLogin, loginCurrent } = require('./registration-seat.js');
const LIMIT = 20;
const id = value => (typeof value !== 'number' || Number.isSafeInteger(value)) && /^[1-9][0-9]*$/.test(String(value || ''));
const request = value => typeof value === 'string' && /^(?:sample:[a-f0-9]{64}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$/.test(value);
const states = ['PREPARED', 'IN_FLIGHT', 'UNKNOWN', 'NOT_SENT', 'PENDING', 'READY'];
function key(member, scope) {
  if (!id(member) || !/^(?:merchant|node:[1-9][0-9]*)$/.test(scope)) throw Error('声音恢复身份无效');
  return 'npc_voice_v1:' + member + ':' + scope;
}
function valid(value) {
  return value && Object.keys(value).sort().join(',') === 'active,records,version' && value.version === 1 && Array.isArray(value.records) && value.records.length <= LIMIT
    && (value.active === null || request(value.active))
    && value.records.every(row => row && Object.keys(row).sort().join(',') === 'createdAt,intentId,previousIntentId,requestId,state'
      && request(row.requestId) && (row.intentId === null || id(row.intentId))
      && (row.previousIntentId === null || id(row.previousIntentId)) && states.includes(row.state)
      && Number.isSafeInteger(row.createdAt) && row.createdAt >= 0)
    && new Set(value.records.map(row => row.requestId)).size === value.records.length
    && (value.active === null || value.records.some(row => row.requestId === value.active));
}
function load(storage, member, scope) {
  const raw = storage.getStorageSync(key(member, scope));
  if (raw === '' || raw == null) return { version: 1, active: null, records: [] };
  if (!valid(raw)) throw Error('声音恢复记录不可用，请保留记录核对');
  return JSON.parse(JSON.stringify(raw));
}
function write(storage, member, scope, previous, next) {
  if (!valid(next) || JSON.stringify(load(storage, member, scope)) !== JSON.stringify(previous))
    throw Error('声音待办已变化，请先刷新原请求');
  storage.setStorageSync(key(member, scope), next);
  if (JSON.stringify(load(storage, member, scope)) !== JSON.stringify(next)) throw Error('声音待办保存失败，未发起生成');
  return JSON.parse(JSON.stringify(next));
}
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.floor(Math.random() * 16); return (c === 'x' ? r : (r & 3) | 8).toString(16);
  });
}
function begin(storage, member, scope, expectedRequest, confirmedRisk) {
  const prior = load(storage, member, scope), active = prior.records.find(row => row.requestId === prior.active);
  if (prior.active !== expectedRequest || prior.records.length >= LIMIT) throw Error('请保留原声音待办；记录已变化或已达20条上限');
  const uncertain = active && ['IN_FLIGHT', 'UNKNOWN'].includes(active.state);
  if ((uncertain && (!confirmedRisk || !active.intentId)) || (active && !uncertain && !['READY', 'NOT_SENT'].includes(active.state))
      || (confirmedRisk && !uncertain)) throw Error('请先查询原任务并确认重复音色或额度占用风险');
  const row = { requestId: uuid(), intentId: null, previousIntentId: uncertain ? active.intentId : null,
    state: 'PREPARED', createdAt: Date.now() };
  const next = { version: 1, active: row.requestId, records: prior.records.concat([row]) };
  write(storage, member, scope, prior, next);
  return row;
}
function dispatched(storage, member, scope, row) {
  const prior = load(storage, member, scope);
  const original = prior.records.find(item => item.requestId === row.requestId);
  if (prior.active !== row.requestId || !original || original.state !== 'PREPARED') throw Error('原请求已发出，请查询状态');
  const next = JSON.parse(JSON.stringify(prior));
  next.records.find(item => item.requestId === row.requestId).state = 'UNKNOWN';
  write(storage, member, scope, prior, next); // Durable ambiguity BEFORE sending. Recovery never dispatches PREPARED either.
}
function observe(storage, member, scope, expectedRequest, result) {
  if (!result || !request(result.voiceRequestId) || (expectedRequest && result.voiceRequestId !== expectedRequest)
      || !id(result.voiceIntentId) || !states.includes(result.voiceIntentState)) throw Error('无法确认这次声音请求的返回结果');
  if (result.voiceSelected !== false && (typeof result.voiceStatus !== 'number'
      || ![0, 1, 2, 3].includes(result.voiceStatus))) throw Error('声音状态无法确认');
  const prior = load(storage, member, scope), next = JSON.parse(JSON.stringify(prior));
  let row = next.records.find(item => item.requestId === result.voiceRequestId);
  if (!row) {
    if (next.active !== null || next.records.length >= LIMIT) throw Error('返回结果不属于当前声音请求');
    row = { requestId: result.voiceRequestId, intentId: null, previousIntentId: result.voicePreviousIntentId || null,
      state: result.voiceIntentState, createdAt: Date.now() };
    next.records.push(row); next.active = row.requestId;
  }
  if (row.intentId !== null && String(row.intentId) !== String(result.voiceIntentId)) throw Error('这次声音请求的返回结果身份不一致');
  if (['READY', 'NOT_SENT'].includes(row.state) && row.state !== result.voiceIntentState)
    throw Error('较早返回的结果不能覆盖已确认的声音状态');
  row.intentId = result.voiceIntentId; row.state = result.voiceIntentState;
  write(storage, member, scope, prior, next);
  return { selected: next.active === row.requestId && result.voiceSelected !== false, row, records: next.records };
}
function recover(storage, member, scope, rows) {
  if (!Array.isArray(rows)) return load(storage, member, scope);
  const prior = load(storage, member, scope), next = JSON.parse(JSON.stringify(prior));
  for (const value of rows) {
    if (!value || !request(value.requestId) || !id(value.intentId) || !states.includes(value.state))
      throw Error('声音恢复列表身份无效');
    if (next.records.some(row => row.requestId === value.requestId)) continue;
    if (next.records.length >= LIMIT) break; // Bounded local index, never evict UNKNOWN; server identities remain queryable.
    next.records.push({ requestId: value.requestId, intentId: value.intentId, previousIntentId: value.previousIntentId || null,
      state: value.state, createdAt: Date.now() });
  }
  return write(storage, member, scope, prior, next);
}
function context(app, storage, pageCurrent) {
  const login = captureLogin(app, storage);
  if (!login || !login.owner) throw Error('登录状态不可确认，请重新进入');
  return { member: login.owner, current: () => pageCurrent() && loginCurrent(app, storage, login) };
}
module.exports = { load, begin, dispatched, observe, recover, context, LIMIT };
