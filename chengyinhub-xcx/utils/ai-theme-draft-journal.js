'use strict';

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const SERVER_ID = /^td2_[a-f0-9]{32}$/;
const STATES = ['PREPARED', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED'];
const STAGES = ['PREPARE_UNCONFIRMED', 'PREPARED', 'EXECUTE_UNCONFIRMED', 'KNOWN'];
const FIELDS = ['version', 'memberId', 'prepareKey', 'requestId', 'stage', 'state', 'reasonCode', 'createdAt'];
const SHAPE = FIELDS.slice().sort().join(',');
const isUuid = value => typeof value === 'string' && UUID.test(value);

function key(member) {
  const owner = String(member == null ? '' : member);
  return /^[1-9][0-9]*$/.test(owner) ? 'ai_theme_draft_v2:m' + owner + ':active' : '';
}
function valid(value, member) {
  if (!value || Array.isArray(value) || typeof value !== 'object'
      || Object.keys(value).sort().join(',') !== SHAPE || value.version !== 2
      || value.memberId !== String(member) || !key(member)
      || !Number.isSafeInteger(value.createdAt) || value.createdAt < 0
      || STAGES.indexOf(value.stage) < 0
      || (value.state !== null && STATES.indexOf(value.state) < 0)
      || (value.reasonCode !== null && (typeof value.reasonCode !== 'string' || !/^[A-Z][A-Z0-9_]{0,79}$/.test(value.reasonCode)))) return false;
  if (value.requestId === null)
    return isUuid(value.prepareKey) && value.stage === 'PREPARE_UNCONFIRMED' && value.state === null;
  if (typeof value.requestId !== 'string') return false;
  if (SERVER_ID.test(value.requestId)) return isUuid(value.prepareKey) && value.state !== null
    && value.stage !== 'PREPARE_UNCONFIRMED';
  // Historical identities are read-only, never upgraded into an execution permit.
  return UUID.test(value.requestId) && value.prepareKey === null && value.stage === 'KNOWN'
    && value.state !== null && value.state !== 'PREPARED';
}
function copy(value) {
  const result = {};
  FIELDS.forEach(field => { result[field] = value[field]; });
  return result;
}
function load(storage, member) {
  const storageKey = key(member);
  if (!storageKey) return { kind: 'CORRUPT' };
  try {
    const value = storage.getStorageSync(storageKey);
    if (value === '' || value === null || value === undefined) return { kind: 'ABSENT' };
    return valid(value, member) ? { kind: 'READY', value: copy(value) } : { kind: 'CORRUPT' };
  } catch (_) { return { kind: 'UNAVAILABLE' }; }
}
function sameIdentity(left, right) {
  return !!left && !!right && left.version === right.version && left.memberId === right.memberId
    && left.prepareKey === right.prepareKey && left.requestId === right.requestId && left.createdAt === right.createdAt;
}
function allowsUpdate(previous, next) {
  if (previous.prepareKey !== next.prepareKey)
    return ['SUCCEEDED', 'FAILED'].indexOf(previous.state) >= 0 && next.requestId === null
      && next.stage === 'PREPARE_UNCONFIRMED' && next.state === null;
  if (previous.createdAt !== next.createdAt || (previous.requestId !== null && previous.requestId !== next.requestId)) return false;
  if (['SUCCEEDED', 'FAILED'].indexOf(previous.state) >= 0) return next.state === previous.state;
  return !(['PROCESSING', 'UNKNOWN'].indexOf(previous.state) >= 0 && next.state === 'PREPARED');
}
function save(storage, member, value, expected) {
  if (!valid(value, member) || (expected !== null && !valid(expected, member))) return { ok: false };
  if (expected !== null && !allowsUpdate(expected, value)) return { ok: false };
  return writeActive(storage, member, value, expected);
}
function matchesActive(storage, member, expected) {
  const current = load(storage, member);
  return expected === null ? current.kind === 'ABSENT'
    : current.kind === 'READY' && JSON.stringify(current.value) === JSON.stringify(copy(expected));
}
function writeActive(storage, member, value, expected) {
  if (!matchesActive(storage, member, expected)) return { ok: false };
  try {
    storage.setStorageSync(key(member), copy(value));
    const readback = load(storage, member);
    if (readback.kind !== 'READY' || JSON.stringify(readback.value) !== JSON.stringify(copy(value))) return { ok: false };
    return { ok: true, value: copy(readback.value) };
  } catch (_) { return { ok: false }; }
}

function historyPrefix(member) {
  return key(member).replace(/active$/, 'history:');
}
function archive(storage, member, value) {
  const storageKey = historyPrefix(member) + (value.prepareKey || value.requestId);
  try {
    const previous = storage.getStorageSync(storageKey);
    if (previous !== '' && previous !== null && previous !== undefined) {
      if (!valid(previous, member)) return false;
      if (sameIdentity(previous, value)) return true;
      // A failed active write may leave the original prepare archived before its server id is known.
      if (previous.requestId !== null || !SERVER_ID.test(value.requestId || '')
          || previous.prepareKey !== value.prepareKey || previous.createdAt !== value.createdAt) return false;
    }
    storage.setStorageSync(storageKey, copy(value));
    const readback = storage.getStorageSync(storageKey);
    return valid(readback, member) && JSON.stringify(copy(readback)) === JSON.stringify(copy(value));
  } catch (_) { return false; }
}
function selectHistory(storage, member, value, expected) {
  if (!valid(value, member) || !value.requestId || value.stage !== 'KNOWN'
      || (expected !== null && (!valid(expected, member) || ['SUCCEEDED', 'FAILED'].indexOf(expected.state) < 0)))
    return { ok: false };
  if (!matchesActive(storage, member, expected) || (expected !== null && !archive(storage, member, expected)))
    return { ok: false };
  return writeActive(storage, member, value, expected);
}
function beginConfirmedNewAction(storage, member, value, expected) {
  if (!valid(value, member) || value.requestId !== null || !valid(expected, member)
      || value.prepareKey === expected.prepareKey
      || ['SUCCEEDED', 'FAILED'].indexOf(expected.state) >= 0
      || !(expected.state === 'UNKNOWN' || ['PREPARE_UNCONFIRMED', 'EXECUTE_UNCONFIRMED'].indexOf(expected.stage) >= 0))
    return { ok: false };
  // The caller confirms the extra quota risk; retain the old identity before changing the active one.
  if (!matchesActive(storage, member, expected) || !archive(storage, member, expected)) return { ok: false };
  return writeActive(storage, member, value, expected);
}
function history(storage, member) {
  if (!key(member)) return { kind: 'CORRUPT' };
  try {
    const info = storage.getStorageInfoSync();
    if (!info || !Array.isArray(info.keys)) return { kind: 'CORRUPT' };
    const prefix = historyPrefix(member);
    const values = [];
    for (const storageKey of info.keys) {
      if (typeof storageKey !== 'string' || storageKey.indexOf(prefix) !== 0) continue;
      const value = storage.getStorageSync(storageKey);
      if (!valid(value, member) || storageKey !== prefix + (value.prepareKey || value.requestId)) return { kind: 'CORRUPT' };
      values.push(copy(value));
    }
    return { kind: 'READY', values };
  } catch (_) { return { kind: 'UNAVAILABLE' }; }
}

module.exports = { key, load, save, selectHistory, beginConfirmedNewAction, history, sameIdentity, valid,
  isServerId: value => typeof value === 'string' && SERVER_ID.test(value) };
