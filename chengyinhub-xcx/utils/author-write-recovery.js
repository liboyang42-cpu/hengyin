'use strict';

const PREFIX = 'author_write_v1:';
const clone = value => JSON.parse(JSON.stringify(value));
const ownerOf = app => String(app.getUserID && app.getUserID() || '');
const keyOf = (owner, bucket) => PREFIX + encodeURIComponent(owner) + ':' + encodeURIComponent(bucket);
function pending(storage, owner, bucket) {
  const value = storage.getStorageSync(keyOf(owner, bucket));
  if (!value) return null;
  if (value.owner !== owner || value.bucket !== bucket || !value.body || typeof value.body !== 'object'
      || typeof value.requestId !== 'string' || !value.requestId) throw Error('原请求记录不完整，请保留并核对');
  return clone(value);
}
function run(options) {
  const { app, storage, bucket } = options;
  const owner = ownerOf(app);
  let intent;
  const unknown = () => options.onUnknown('原请求结果待确认，已保留原号和内容，请重试核对');
  const visible = () => owner === ownerOf(app) && (!options.current || options.current());
  try {
    if (!owner) throw Error('请先登录');
    intent = pending(storage, owner, bucket);
    if (!intent) {
      const requestId = 'aw-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
      intent = { owner, bucket, requestId, operation: options.operation, endpoint: options.endpoint,
        action: options.action || '', body: Object.assign(clone(options.body), { requestId }) };
      storage.setStorageSync(keyOf(owner, bucket), clone(intent));
      if (JSON.stringify(pending(storage, owner, bucket)) !== JSON.stringify(intent)) throw Error('原请求没有保存到本机，请检查存储后重试');
    } else {
      // 只有显式重试才会进入；先读原回执，查无时仍只重放受服务端事务幂等保护的原请求。
      app.sendRequest({ url: '/api/author-write/receipt', method: 'GET', hideLoading: true, autoErrorToast: false,
        data: { operation: intent.operation, requestId: intent.requestId },
        success: res => {
          if (!visible()) return;
          const receipt = res && Number(res.code) === 200 && res.data;
          if (!receipt || receipt.operation !== intent.operation || receipt.requestId !== intent.requestId) { unknown(); return; }
          if (receipt.state === 'FOUND') { settle(receipt.response); return; }
          if (receipt.state === 'UNKNOWN') send();
          else unknown();
        }, fail: () => { if (visible()) unknown(); }, successStatusAbnormal: () => { if (visible()) unknown(); } });
      return;
    }
  } catch (error) { if (visible()) options.onUnknown(error.message || '原请求记录无法保存，请稍后重试'); return; }
  send();
  function clear() {
    const saved = pending(storage, owner, bucket);
    if (!saved || saved.requestId !== intent.requestId) throw Error('原请求记录已变化，请重新核对');
    storage.removeStorageSync(keyOf(owner, bucket));
    if (pending(storage, owner, bucket)) throw Error('处理结果未能保存，请重新核对');
  }
  function settle(res) {
    if (!visible()) return;
    const marker = res && res.writeReceipt;
    if (res && Number(res.code) === 200 && options.validResult(res.data, intent, res)) {
      try { clear(); } catch (error) { unknown(); return; }
      options.onSuccess(res, intent); return;
    }
    if (marker && marker.state === 'REJECTED' && marker.requestId === intent.requestId && marker.operation === intent.operation) {
      try { clear(); } catch (error) { unknown(); return; }
      options.onRejected(res); return;
    }
    unknown();
  }
  function send() {
    if (!visible()) return;
    const fail = (response, statusCode) => {
      if (!visible()) return;
      if ([400, 401, 403, 404, 405, 410, 422].includes(Number(statusCode || response && response.statusCode))) {
        try { clear(); } catch (error) { unknown(); return; }
        options.onRejected(response || { msg: '请求被明确拒绝' }); return;
      }
      unknown();
    };
    try {
      options.send(intent, { success: settle, fail, successStatusAbnormal: fail });
    } catch (error) { unknown(); }
  }
}
module.exports = { pending, run, ownerOf };
