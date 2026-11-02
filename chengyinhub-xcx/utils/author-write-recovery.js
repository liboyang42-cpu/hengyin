'use strict';

const PREFIX = 'author_write_v1:';
const clone = value => JSON.parse(JSON.stringify(value));
const ownerOf = app => String(app.getUserID && app.getUserID() || '');
const keyOf = (owner, bucket) => PREFIX + encodeURIComponent(owner) + ':' + encodeURIComponent(bucket);
function pending(storage, owner, bucket) {
  const value = storage.getStorageSync(keyOf(owner, bucket));
  if (!value) return null;
  if (value.owner !== owner || value.bucket !== bucket || !value.body || typeof value.body !== 'object'
      || typeof value.requestId !== 'string' || !value.requestId) throw Error('上次提交的内容读不出来，请重新填写后提交');
  return clone(value);
}
function run(options) {
  const { app, storage, bucket } = options;
  const owner = ownerOf(app);
  let intent;
  const unknown = () => options.onUnknown('还没确认是否保存成功，内容已保留。再提交一次即可，不会重复保存');
  const visible = () => owner === ownerOf(app) && (!options.current || options.current());
  try {
    if (!owner) throw Error('请先登录');
    intent = pending(storage, owner, bucket);
    if (!intent) {
      const requestId = 'aw-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
      intent = { owner, bucket, requestId, operation: options.operation, endpoint: options.endpoint,
        action: options.action || '', body: Object.assign(clone(options.body), { requestId }) };
      storage.setStorageSync(keyOf(owner, bucket), clone(intent));
      if (JSON.stringify(pending(storage, owner, bucket)) !== JSON.stringify(intent)) throw Error('内容没能存到本机，请稍后再试');
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
  } catch (error) { if (visible()) options.onUnknown(error.message || '内容没能存到本机，请稍后再试'); return; }
  send();
  function clear() {
    const saved = pending(storage, owner, bucket);
    if (!saved || saved.requestId !== intent.requestId) throw Error('这份内容在别处改过了，请重新打开后再提交');
    storage.removeStorageSync(keyOf(owner, bucket));
    if (pending(storage, owner, bucket)) throw Error('已提交，请重新打开页面查看结果');
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
        options.onRejected(response || { msg: '提交没通过，请检查内容后重试' }); return;
      }
      unknown();
    };
    try {
      options.send(intent, { success: settle, fail, successStatusAbnormal: fail });
    } catch (error) { unknown(); }
  }
}
module.exports = { pending, run, ownerOf };
