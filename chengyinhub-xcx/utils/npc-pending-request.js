const STORAGE_KEY = 'npc_pending_requests_v1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ID = /^[1-9][0-9]*$/;

function context(owner, scope, bizId, session) {
  return { owner: String(owner || ''), scope, bizId: String(bizId || ''), session: session || '' };
}

function validContext(value) {
  return value && typeof value.owner === 'string' && ID.test(value.owner)
    && typeof value.bizId === 'string' && ID.test(value.bizId)
    && typeof value.session === 'string'
    && (value.scope === 'roam' || value.scope === 'merchant' || value.scope === 'shop')
    && (value.scope === 'shop' ? /^(activity|topic):[1-9][0-9]*$/.test(value.session) : value.session === '');
}

function sameContext(left, right) {
  return left && right && ['owner', 'scope', 'bizId', 'session'].every(key => left[key] === right[key]);
}

function readState(owner) {
  const stored = wx.getStorageSync(STORAGE_KEY + ':' + owner);
  if (stored == null || stored === '') return { owner, requests: [] };
  if (!stored || typeof stored.owner !== 'string' || !ID.test(stored.owner) || !Array.isArray(stored.requests)
      || stored.requests.some(item => !validContext(item) || item.owner !== stored.owner || typeof item.requestId !== 'string' || !UUID.test(item.requestId)
        || Object.keys(item).some(key => !['owner', 'scope', 'bizId', 'session', 'requestId'].includes(key)))
      || stored.requests.some((item, index) => stored.requests.some((other, otherIndex) => otherIndex < index && sameContext(item, other)))) {
    throw new Error('Invalid NPC pending storage');
  }
  if (stored.owner !== owner) throw new Error('NPC pending owner mismatch');
  return stored;
}

function read(target) {
  try {
    if (!validContext(target)) return { error: true, pending: null };
    const state = readState(target.owner);
    return { error: false, pending: state.requests.find(item => sameContext(item, target)) || null };
  } catch (e) {
    return { error: true, pending: null };
  }
}

function save(pending) {
  try {
    if (!validContext(pending) || !UUID.test(pending.requestId)) return false;
    const state = readState(pending.owner);
    const existing = state.requests.find(item => sameContext(item, pending));
    if (existing && existing.requestId !== pending.requestId) return false;
    const metadata = Object.assign(context(pending.owner, pending.scope, pending.bizId, pending.session), { requestId: pending.requestId });
    wx.setStorageSync(STORAGE_KEY + ':' + pending.owner, { owner: pending.owner,
      requests: state.requests.filter(item => !sameContext(item, pending)).concat(metadata) });
    return read(pending).pending.requestId === pending.requestId;
  } catch (e) {
    return false;
  }
}

function clear(pending) {
  try {
    // A late response must never delete a new account's or a newer request's record.
    const state = wx.getStorageSync(STORAGE_KEY + ':' + pending.owner);
    if (!state || state.owner !== pending.owner) return true;
    const checked = readState(pending.owner);
    wx.setStorageSync(STORAGE_KEY + ':' + pending.owner, { owner: pending.owner,
      requests: checked.requests.filter(item => !sameContext(item, pending) || item.requestId !== pending.requestId) });
    const remaining = read(pending);
    return !remaining.error && !remaining.pending;
  } catch (e) {
    return false;
  }
}

function isTerminal(result) {
  return result && typeof result.safeText === 'string' && !!result.safeText.trim()
    && ['PASS', 'BLOCKED', 'NOT_RUN'].includes(result.safetyDecision)
    && (result.outcomeStatus === 'SUCCEEDED' ? result.safetyDecision === 'PASS'
      : ['REJECTED', 'FAILED'].includes(result.outcomeStatus) && typeof result.errorCode === 'string' && !!result.errorCode)
    && !['REQUEST_STATE_UNAVAILABLE', 'REQUEST_NOT_FOUND', 'REQUEST_CONFLICT', 'INVALID_REQUEST', 'UNAUTHENTICATED'].includes(result.errorCode);
}

module.exports = { context, sameContext, read, save, clear, isTerminal };
