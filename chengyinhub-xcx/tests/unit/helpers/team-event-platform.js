'use strict';
// Deterministic platform boundary for real recovery-module/Page tests; no app/login fallback.
function platform() {
  const storage = new Map(); let serial = 0;
  return {
    getStorageSync: key => storage.has(key) ? JSON.parse(storage.get(key)) : '',
    setStorageSync: (key, value) => storage.set(key, JSON.stringify(value)),
    removeStorageSync: key => storage.delete(key),
    getRandomValues: opts => opts.success({ randomValues: new Uint8Array(16).fill(++serial).buffer }),
    stopPullDownRefresh() {},
  };
}
function receipt(request, state) {
  return { code: 200, data: { requestId: JSON.parse(request.data).requestId, state, message: state } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
module.exports = { platform, receipt, tick };
