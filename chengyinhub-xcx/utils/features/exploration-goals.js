// Non-UI client. getIdentity must include member ID AND login-session epoch.
// Unknown creates retain their original payload in memory; never store private goals on disk.
function createExplorationGoalsClient(options) {
  if (!options || typeof options.sendRequest !== 'function' || typeof options.getIdentity !== 'function') throw new Error('需要请求通道和登录会话标识');
  let epoch = 0; let readEpoch = 0; let pending = null; let flight = null; let disposed = false;
  let bound = String(options.getIdentity() || '');
  const cancellations = new Map();
  const confirmedCancelled = new Set();
  const metrics = ['DISTINCT_ROUTES', 'EXPLORATION_DAYS'];
  function sync() {
    if (disposed) throw new Error('客户端已销毁，请重新打开目标');
    const owner = String(options.getIdentity() || '');
    if (owner !== bound) { epoch++; readEpoch++; pending = null; flight = null; cancellations.clear(); confirmedCancelled.clear(); bound = owner; }
    if (!owner) throw new Error('请先登录');
    return owner;
  }
  function identifier(value) { return typeof value === 'string' && /^[1-9]\d*$/.test(value); }
  function calendar(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const date = new Date(value + 'T00:00:00Z');
    return !isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
  }
  function goal(value) {
    if (!value || !identifier(value.id) || !/^[A-Za-z0-9_-]{16,64}$/.test(value.requestId || '') || !metrics.includes(value.metric)
      || !Number.isInteger(value.target) || value.target < 1 || value.target > 100
      || !calendar(value.startDate) || !calendar(value.endDateExclusive) || value.startDate >= value.endDateExclusive
      || !Number.isSafeInteger(value.progress) || value.progress < 0
      || !['ACTIVE', 'SCHEDULED', 'ENDED', 'ACHIEVED', 'CANCELLED'].includes(value.state)
      || typeof value.privateCommemoration !== 'boolean' || value.privateCommemoration !== (value.state === 'ACHIEVED')
      || (value.state === 'ACHIEVED' && value.progress < value.target)
      || value.timeZone !== 'Asia/Shanghai' || value.countingVersion !== 'COMPLETED_SESSION_AGGREGATE_V1'
      || !Number.isSafeInteger(value.asOfMs) || !Number.isSafeInteger(value.createdAtMs)) throw new Error('目标响应未确认');
    // Explicit projection prevents stray route details/media from being cached or returned.
    const clean = {};
    for (const key of ['id', 'requestId', 'metric', 'target', 'startDate', 'endDateExclusive', 'progress', 'state', 'privateCommemoration', 'timeZone', 'countingVersion', 'asOfMs', 'createdAtMs']) clean[key] = value[key];
    // Cancellation is monotonic on the server. Preserve the creation receipt without reviving
    // a goal when its earlier create/receipt response arrives after a confirmed cancellation.
    if (confirmedCancelled.has(clean.id)) { clean.state = 'CANCELLED'; clean.privateCommemoration = false; }
    return clean;
  }
  function matches(value, input) { return ['requestId', 'metric', 'target', 'startDate', 'endDateExclusive'].every(key => value[key] === input[key]); }
  function request(url, method, data, readToken) { const path = url.slice(url.lastIndexOf('/') + 1);
    let owner;
    try { owner = sync(); } catch (error) { return Promise.reject(error); }
    const token = epoch;
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => finish(new Error('请求超时，结果待确认')), options.timeoutMs || 15000);
      function finish(error, response) {
        if (settled) return;
        settled = true; clearTimeout(timer);
        if (disposed || owner !== String(options.getIdentity() || '') || token !== epoch || (readToken !== undefined && readToken !== readEpoch)) return reject(new Error('请求上下文已变化'));
        if (error) return reject(error);
        if (!response || response.code !== 200 || !response.data) {
          const failure = new Error((response && response.msg) || '请求结果未确认');
          failure.code = response && [400, 409].includes(response.code) ? 'REJECTED' : 'UNKNOWN';
          return reject(failure);
        }
        resolve({ data: response.data, check() {
          if (disposed || owner !== String(options.getIdentity() || '') || token !== epoch || (readToken !== undefined && readToken !== readEpoch)) throw new Error('请求上下文已变化');
        } });
      }
      try { options.sendRequest({ url: url, method,
        header: { 'Content-Type': path === 'create' ? 'application/json' : 'application/x-www-form-urlencoded' },
        data: path === 'create' ? JSON.stringify(data) : data, hideLoading: true, autoErrorToast: false,
        success: response => finish(null, response), fail: () => finish(new Error('网络异常，结果待确认')) }); }
      catch (error) { finish(error); }
    });
  }
  function create(input) {
    try { sync(); } catch (error) { return Promise.reject(error); }
    if (!input || !/^[A-Za-z0-9_-]{16,64}$/.test(input.requestId || '') || !metrics.includes(input.metric)
      || !Number.isInteger(input.target) || input.target < 1 || input.target > 100 || !calendar(input.startDate)
      || !calendar(input.endDateExclusive) || input.startDate >= input.endDateExclusive) return Promise.reject(new Error('目标设置无效'));
    const draft = {};
    for (const key of ['requestId', 'metric', 'target', 'startDate', 'endDateExclusive']) draft[key] = input[key];
    const fingerprint = JSON.stringify(draft);
    if (pending && pending.fingerprint !== fingerprint) return Promise.reject(new Error('请先完成上一个目标的操作'));
    if (flight) return flight;
    const attempt = pending || { input: draft, fingerprint, unknown: false }; pending = attempt;
    const promise = request('/api/exploration-goals/create', 'POST', draft).then(response => {
      response.check(); const clean = goal(response.data);
      if (!matches(clean, draft)) throw new Error('创建结果异常，请重试');
      if (pending === attempt) pending = null;
      return clean;
    }).catch(error => {
      if (pending === attempt) { if (error.code === 'REJECTED' && !attempt.unknown) pending = null; else attempt.unknown = true; }
      throw error;
    });
    flight = promise;
    promise.then(() => { if (flight === promise) flight = null; }, () => { if (flight === promise) flight = null; });
    return promise;
  }
  return {
    create,
    retry() { try { sync(); } catch (error) { return Promise.reject(error); } return pending ? create(pending.input) : Promise.reject(new Error('没有待确认请求')); },
    receipt(requestId) {
      try { sync(); } catch (error) { return Promise.reject(error); }
      if (!/^[A-Za-z0-9_-]{16,64}$/.test(requestId || '')) return Promise.reject(new Error('请求编号不合法'));
      const attempt = pending;
      return request('/api/exploration-goals/receipt', 'GET', { requestId }).then(response => {
        response.check(); const clean = goal(response.data);
        if (clean.requestId !== requestId || (attempt && attempt.input.requestId === requestId && !matches(clean, attempt.input))) throw new Error('操作结果异常，请重试');
        if (pending === attempt && attempt && attempt.input.requestId === requestId) pending = null;
        return clean;
      });
    },
    detail(id) {
      try { sync(); } catch (error) { return Promise.reject(error); }
      if (!identifier(id)) return Promise.reject(new Error('目标编号须为字符串'));
      return request('/api/exploration-goals/detail', 'GET', { id }, ++readEpoch).then(response => { response.check(); const clean = goal(response.data); if (clean.id !== id) throw new Error('操作结果异常，请重试'); return clean; });
    },
    list(beforeId, limit) {
      try { sync(); } catch (error) { return Promise.reject(error); }
      if ((beforeId != null && !identifier(beforeId)) || (limit != null && (!Number.isInteger(limit) || limit < 1 || limit > 50))) return Promise.reject(new Error('加载失败，请重试'));
      return request('/api/exploration-goals/list', 'GET', Object.assign({ limit: limit || 20 }, beforeId != null ? { beforeId } : {}), ++readEpoch).then(response => {
        response.check(); const value = response.data;
        if (!Array.isArray(value.items) || value.items.length > (limit || 20) || typeof value.hasMore !== 'boolean'
          || (value.hasMore ? !identifier(value.nextBeforeId) || !value.items.length : value.nextBeforeId !== null)) throw new Error('目标列表响应未确认');
        const items = value.items.map(goal);
        if (new Set(items.map(item => item.id)).size !== items.length || (value.hasMore && value.nextBeforeId !== items[items.length - 1].id)) throw new Error('加载失败，请重试');
        return { items, hasMore: value.hasMore, nextBeforeId: value.nextBeforeId };
      });
    },
    cancel(id) {
      try { sync(); } catch (error) { return Promise.reject(error); }
      if (!identifier(id)) return Promise.reject(new Error('目标编号须为字符串'));
      if (cancellations.has(id)) return cancellations.get(id);
      readEpoch++;
      const promise = request('/api/exploration-goals/cancel', 'POST', { id }).then(response => {
        response.check(); const clean = goal(response.data); if (clean.id !== id || clean.state !== 'CANCELLED') throw new Error('取消尚未确认');
        confirmedCancelled.add(id); readEpoch++; return clean;
      });
      cancellations.set(id, promise);
      const done = () => { if (cancellations.get(id) === promise) cancellations.delete(id); };
      promise.then(done, done); return promise;
    },
    pendingRequestId() { sync(); return pending ? pending.input.requestId : null; },
    dispose() { disposed = true; epoch++; readEpoch++; pending = null; flight = null; cancellations.clear(); confirmedCancelled.clear(); }
  };
}
module.exports = { createExplorationGoalsClient };
