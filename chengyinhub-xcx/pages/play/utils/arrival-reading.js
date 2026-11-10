'use strict';
// U05-01..03 · §23.2 / §27.3:普通到达的定位时间、拒绝原因、批准替代与到达读数。
//  - locationTimestamp:端上拿到这次定位的时刻(epoch 毫秒字符串);没有采样时刻就不带,旧指纹不变。
//  - 拒绝:只认服务端 arrivalReasonCode / canRetry / approvedFallbacks;替代站只取本局已下发、尚未完成的真实节点,
//    客户端不标到达、不跳站。
//  - arrivalReading{level,log}:只在真实到达成功回执里出现;按「本人 + 本局 + 节点」去重,同一回执重读不再追加。
const { id } = require('../../../utils/orient-score-view.js');

const STORE = 'play-arrival-readings:v1';
const MAX_LOG = 600;

function timestampOf(loc) {
  const at = loc && loc.capturedAt;
  return Number.isSafeInteger(at) && at > 0 ? String(at) : '';
}

function stamp(loc) {
  return Object.assign({}, loc, { capturedAt: Date.now() });
}

function reading(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const level = Number(raw.level);
  const log = typeof raw.log === 'string' ? raw.log.trim() : '';
  if (!Number.isInteger(level) || level < 0 || level > 3 || !log) return null;
  return { level, log: log.slice(0, MAX_LOG) };
}

function rejection(response, nodes) {
  const r = response || {};
  const code = typeof r.arrivalReasonCode === 'string' && /^[A-Z_]{3,64}$/.test(r.arrivalReasonCode)
    ? r.arrivalReasonCode : '';
  const known = Array.isArray(nodes) ? nodes : [];
  const fallbacks = (Array.isArray(r.approvedFallbacks) ? r.approvedFallbacks : [])
    .map(value => id(value))
    .filter((value, index, all) => value && all.indexOf(value) === index)
    .map(nodeId => known.find(node => id(node && node.nodeId) === nodeId))
    .filter(node => node && node.done !== true)
    .map(node => ({ nodeId: id(node.nodeId), name: String(node.name || '').slice(0, 24) }))
    .filter(node => node.name);
  return { reasonCode: code, canRetry: r.canRetry === true, fallbacks };
}

function key(owner, session, nodeId) {
  return [String(owner || ''), String(session || ''), id(nodeId)].join('|');
}

function readAll(wx) {
  try {
    const value = wx.getStorageSync(STORE);
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch (error) { return {}; }
}

/** 记下一次到达读数;已记过同一键返回 false(不重复播放)。存储失败只影响重进后的回看,不影响本次显示。 */
function record(wx, k, value) {
  const normalized = reading(value);
  if (!normalized || !k) return false;
  const all = readAll(wx);
  if (all[k]) return false;
  all[k] = normalized;
  const keys = Object.keys(all);
  while (keys.length > 200) delete all[keys.shift()];
  try { wx.setStorageSync(STORE, all); } catch (error) { /* 回看不到不影响本次 */ }
  return true;
}

function lookup(wx, k) {
  return reading(readAll(wx)[k]);
}

module.exports = { timestampOf, stamp, reading, rejection, key, record, lookup, STORE };
