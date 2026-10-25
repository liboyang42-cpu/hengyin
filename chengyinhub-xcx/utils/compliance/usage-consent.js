'use strict';
// This stores the device user's choice, never substitutes for server consent readback.
const KEY = 'cy_usage_choice_v1';
const PENDING_KEY = 'cy_usage_pending_v1:';
const VERSIONS = { privacy_policy: 'v2.1', user_service_agreement: 'v2.2' };
function createUsageConsent(options) {
  const waiters = [];
  let choice = null, generation = 0, flight = null;
  function granted() {
    if (!choice) { try { choice = options.wx.getStorageSync(KEY); } catch (e) {} }
    return !!choice && choice.privacy_policy === VERSIONS.privacy_policy
      && choice.user_service_agreement === VERSIONS.user_service_agreement;
  }
  function ensure() {
    if (granted()) return Promise.resolve();
    return new Promise((resolve, reject) => { waiters.push({resolve,reject}); options.openGate(); });
  }
  function refuse() {
    generation++;
    choice = null;
    try { options.wx.removeStorageSync(KEY); } catch (e) {}
    waiters.splice(0).forEach(w => w.reject(new Error('USAGE_CONSENT_DECLINED')));
  }
  function retryKey(owner, docType) {
    return 'cy_consent_retry:' + [owner,docType,'app_launch','AGREE','',''].join('|');
  }
  async function syncPending() {
    if (!granted()) return;
    const owner = String(options.getUserID() || '');
    const authorization = options.getAuthorization();
    if (!(Number(owner) > 0) || !authorization || authorization === 'dev-token') return;
    const currentGeneration = generation;
    if (flight && flight.owner === owner && flight.authorization === authorization && flight.generation === currentGeneration) return flight.promise;
    const isCurrent = () => granted() && generation === currentGeneration
      && owner === String(options.getUserID() || '') && authorization === options.getAuthorization();
    let state = options.wx.getStorageSync(PENDING_KEY + owner);
    if (!state || !state.pending || typeof state.pending !== 'object' || Array.isArray(state.pending)
        || Object.keys(VERSIONS).some(docType => !state.versions || state.versions[docType] !== VERSIONS[docType]
          || state.pending[docType] && (state.pending[docType].docVersion !== VERSIONS[docType]
            || typeof state.pending[docType].requestId !== 'string' || !state.pending[docType].requestId))) {
      state = {versions:Object.assign({},VERSIONS),pending:{}};
      for (const docType of Object.keys(VERSIONS)) {
        const saved = options.wx.getStorageSync(retryKey(owner,docType));
        const requestId = typeof saved === 'string' && saved ? saved : 'consent-' + Date.now() + '-' + Math.random().toString(36).slice(2,10);
        state.pending[docType] = {docVersion:VERSIONS[docType],requestId};
      }
      // Persist both original UUIDs and frozen versions before any HTTP attempt.
      options.wx.setStorageSync(PENDING_KEY + owner,state);
    }
    const active = {owner,authorization,generation:currentGeneration};
    const perform = async () => {
      for (const docType of Object.keys(VERSIONS)) {
        const item = state.pending && state.pending[docType];
        if (!item) continue;
        if (!isCurrent()) throw new Error('同意记录失败，账号或选择已变化');
        options.wx.setStorageSync(retryKey(owner,docType),item.requestId);
        const saved = await options.record({docType,scene:'app_launch',eventType:'AGREE'});
        if (!isCurrent()) throw new Error('同意记录失败，账号或选择已变化');
        const row = saved && saved.response && saved.response.data;
        if (!row || saved.requestId !== item.requestId || row.requestId !== item.requestId
            || row.docType !== docType || row.scene !== 'app_launch' || row.eventType !== 'AGREE') throw new Error('同意记录失败');
        if (row.docVersion !== item.docVersion) { refuse(); throw new Error('协议版本已更新，请重新阅读'); }
        const latest = options.wx.getStorageSync(PENDING_KEY + owner);
        if (!latest || !latest.pending || !latest.pending[docType]
            || latest.pending[docType].requestId !== item.requestId) throw new Error('同意记录失败，待补传记录已变化');
        // Clone rather than mutate the stored object: a failed write keeps it pending.
        const pending = Object.assign({},latest.pending); delete pending[docType];
        state = Object.assign({},latest,{pending});
        options.wx.setStorageSync(PENDING_KEY + owner,state);
      }
    };
    active.promise = perform(); flight = active;
    const finish = () => { if (flight === active) flight = null; };
    active.promise.then(finish,finish);
    return active.promise;
  }
  async function agree(event, serviceChecked) {
    if (!serviceChecked) throw new Error('请先阅读并勾选用户服务协议');
    // Native authorization must settle before wx.login or an authenticated consent write.
    options.resolveNative({event:'agree', buttonId:event && event.detail && event.detail.buttonId});
    choice = Object.assign({}, VERSIONS);
    try { options.wx.setStorageSync(KEY, choice); } catch (e) {}
    waiters.splice(0).forEach(w => w.resolve());
    const login = await options.login();
    // owner 2026-10-24:游客同意后保持游客(不建号);服务端留痕等首次登录后由 syncPending 补传。
    if (login && login.guest === true) return login;
    if (!login || login.ok === false || !(Number(options.getUserID()) > 0)
        || !options.getAuthorization() || options.getAuthorization() === 'dev-token') throw new Error('登录未完成，请重试');
    await syncPending();
  }
  return {granted, ensure, refuse, agree, syncPending};
}
module.exports = {createUsageConsent, VERSIONS};
