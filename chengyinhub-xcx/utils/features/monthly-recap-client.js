function createMonthlyRecapClient(options) {
  if (!options || typeof options.sendRequest !== 'function' || typeof options.getIdentity !== 'function') throw new Error('需要请求通道和登录会话标识');
  let generation = 0;
  let state = null;
  let bound = String(options.getIdentity() || '');
  function sync() {
    const owner = String(options.getIdentity() || '');
    if (owner !== bound) { generation++; state = null; bound = owner; }
    if (!owner) throw new Error('请先登录');
    return owner;
  }
  function fetch(month, kind, beforeId) {
    let owner;
    try { owner = sync(); } catch (e) { return Promise.reject(e); }
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month) || !['routes', 'places', 'stamps'].includes(kind)) return Promise.reject(new Error('月份或记录类型不合法'));
    const token = ++generation;
    return new Promise((resolve, reject) => {
      let settled = false;
      function end(error, response) {
        if (settled) return;
        settled = true; clearTimeout(timer);
        if (token !== generation || owner !== String(options.getIdentity() || '')) return reject(new Error('回顾请求已过期'));
        if (error) { state = null; return reject(error); }
        const value = response && response.data;
        if (!response || response.code !== 200 || !value || value.month !== month || value.kind !== kind || !Array.isArray(value.items) || typeof value.hasMore !== 'boolean' || !value.counts) { state = null; return reject(new Error('回顾响应未确认')); }
        if (beforeId && (!state || state.month !== month || state.kind !== kind || String(state.nextBeforeId) !== String(beforeId))) return reject(new Error('分页上下文已变化'));
        // Replace the visible page: previously loaded titles have not been re-authorized by this response.
        const items = [];
        const seen = new Set(items.map(item => String(item.id)));
        for (const item of value.items) {
          if (!item || !/^[1-9]\d*$/.test(String(item.id))) { state = null; return reject(new Error('回顾记录编号不合法')); }
          if (!seen.has(String(item.id))) { items.push(Object.assign({}, item)); seen.add(String(item.id)); }
        }
        state = Object.assign({}, value, { items });
        resolve(JSON.parse(JSON.stringify(state)));
      }
      const timer = setTimeout(() => end(new Error('回顾请求超时')), options.timeoutMs || 15000);
      try { options.sendRequest({ url: '/api/monthly-recap', method: 'GET', data: Object.assign({ month, kind, limit: 20 }, beforeId != null ? { beforeId } : {}), hideLoading: true, autoErrorToast: false,
        success: value => end(null, value), fail: () => end(new Error('回顾加载失败')) }); }
      catch (e) { end(e); }
    });
  }
  return {
    load(month, kind) { return fetch(month, kind || 'routes', null); },
    next() { try { sync(); } catch (e) { return Promise.reject(e); } return state && state.hasMore ? fetch(state.month, state.kind, state.nextBeforeId) : Promise.reject(new Error('没有下一页')); },
    snapshot() { sync(); return state ? JSON.parse(JSON.stringify(state)) : null; },
    dispose() { generation++; state = null; }
  };
}
module.exports = { createMonthlyRecapClient };
