// Inject app.sendRequest.bind(app) and getIdentity() => memberId + login-session epoch.
// Keeps unknown requests only in memory. Never stores capsule content in wx storage.
function createCapsuleClient(options) {
  if (!options || typeof options.sendRequest !== 'function' || typeof options.getIdentity !== 'function') throw new Error('需要请求通道和登录会话标识');
  let epoch = 0; let pending = null; let flight = null;
  const identity = () => String(options.getIdentity() || '');
  let bound = identity();
  function sync() {
    const current = identity();
    if (current !== bound) { epoch++; pending = null; flight = null; bound = current; }
    if (!current) throw new Error('请先登录');
    return current;
  }
  function request(url, method, data) { const path = url.slice(url.lastIndexOf('/') + 1);
    let owner;
    try { owner = sync(); } catch (e) { return Promise.reject(e); }
    const token = epoch;
    return new Promise((resolve, reject) => {
      let settled = false;
      function end(error, response) {
        if (settled) return;
        settled = true; clearTimeout(timer);
        if (token !== epoch || owner !== identity()) return reject(new Error('登录会话已变化'));
        if (error) return reject(error);
        if (!response || response.code !== 200 || !response.data) {
          const failure = new Error((response && response.msg) || '结果未确认，请重试');
          failure.code = response && response.code === 400 ? 'REJECTED' : 'OUTCOME_UNKNOWN';
          return reject(failure);
        }
        resolve(response.data);
      }
      const timer = setTimeout(() => end(new Error('请求超时，结果待确认')), options.timeoutMs || 15000);
      try {
        options.sendRequest({ url: url, method,
          header: { 'Content-Type': method === 'POST' && path === 'create' ? 'application/json' : 'application/x-www-form-urlencoded' },
          data: method === 'POST' && path === 'create' ? JSON.stringify(data) : data,
          hideLoading: true, autoErrorToast: false,
          success: res => end(null, res), fail: () => end(new Error('网络异常，结果待确认')) });
      } catch (e) { end(e); }
    });
  }
  function redact(value) {
    if (!value || !value.id || !['LOCKED', 'OPEN', 'DELETED'].includes(value.state)) throw new Error('胶囊响应不合法');
    const clean = { id: String(value.id), state: value.state, unlockAtMs: value.unlockAtMs, createdAtMs: value.createdAtMs, timeZone: value.timeZone };
    if (value.state === 'OPEN') { clean.body = value.body; clean.photoUrl = value.photoUrl; }
    return clean;
  }
  function create(input) {
    try { sync(); } catch (e) { return Promise.reject(e); }
    if (!input || !/^[A-Za-z0-9_-]{16,64}$/.test(input.requestId || '')) return Promise.reject(new Error('操作失败，请重试'));
    const draft = { requestId: input.requestId, body: input.body || '', photoUrl: input.photoUrl || '', unlockDate: input.unlockDate };
    const fingerprint = JSON.stringify(draft);
    if (pending && pending.fingerprint !== fingerprint) return Promise.reject(new Error('上一枚胶囊结果待确认，请先重试'));
    if (flight) return flight;
    pending = pending || { input: draft, fingerprint, unknown: false };
    const attempt = pending;
    const promise = request('/api/capsules/create', 'POST', draft).then(result => {
      const clean = redact(result);
      if (pending === attempt) pending = null;
      return clean;
    }).catch(error => {
      if (pending === attempt) {
        if (error.code === 'REJECTED' && !attempt.unknown) pending = null;
        else attempt.unknown = true;
      }
      throw error;
    });
    flight = promise;
    promise.then(() => { if (flight === promise) flight = null; }, () => { if (flight === promise) flight = null; });
    return promise;
  }
  return {
    create,
    retry() { try { sync(); } catch (e) { return Promise.reject(e); } return pending ? create(pending.input) : Promise.reject(new Error('没有待确认请求')); },
    detail(id) { return request('/api/capsules/detail', 'GET', { id }).then(redact); },
    receipt(requestId) {
      const attempt = pending;
      return request('/api/capsules/receipt', 'GET', { requestId }).then(value => {
        const clean = redact(value);
        if (attempt && pending === attempt && attempt.input.requestId === requestId) pending = null;
        return clean;
      });
    },
    list(beforeId, limit) { return request('/api/capsules/list', 'GET', Object.assign({}, beforeId != null ? { beforeId } : {}, limit != null ? { limit } : {})).then(result => {
      if (!Array.isArray(result.items) || typeof result.hasMore !== 'boolean') throw new Error('胶囊列表响应不合法');
      return { items: result.items.map(redact), hasMore: result.hasMore, nextBeforeId: result.nextBeforeId };
    }); },
    remove(id) { return request('/api/capsules/delete', 'POST', { id }).then(redact); },
    pendingRequestId() { sync(); return pending ? pending.input.requestId : null; },
    dispose() { epoch++; pending = null; flight = null; }
  };
}
module.exports = { createCapsuleClient };
