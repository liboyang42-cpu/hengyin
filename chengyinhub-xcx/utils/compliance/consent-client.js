const { VERSIONS } = require('./usage-consent.js');

function createConsentClient(options) {
  options = options || {};
  const sendRequest = options && options.sendRequest;
  const now = (options && options.now) || Date.now;
  const random = (options && options.random) || function () {
    return Math.random().toString(36).slice(2, 10);
  };
  // 兜底截止:本 Promise 只在 success/fail 回调时 settle,而 sendRequest 走的是通用请求通道 ——
  // 撞上隐私授权闸时那两个回调一个都不会来(见 app.js openPrivacyAuthorizationPage 的注释:
  // 系统返回退页时没有任何 resolve 被调用 ⇒ 后续接口静默挂起),于是 Promise 永不 settle。
  // 全站 8 个 recordConsent 调用方里 6 个把关键 UX 挂在它 resolve 上(privacy-gate 挂了整个 app
  // 进不去、deregister 申请永不发出、漫游点了「同意并出发」连定位弹窗都不出来),所以兜底放在
  // 这一处,而不是让每个调用方各自设防。超时按 reject 走:调用方的 .catch 已有「请检查网络后重试」
  // 提示,而 resolve 会谎称同意已落库 —— 合规记录上那比卡住更坏。
  const settleTimeoutMs = (options && options.settleTimeoutMs) || 10000;

  const pending = new Map();
  const locationIntents = new Map();
  function record(input) {
    const docType = input && input.docType;
    const scene = input && input.scene;
    const eventType = input && input.eventType;
    if (!docType || !scene || (eventType !== 'AGREE' && eventType !== 'REVOKE')) {
      return Promise.reject(new Error('同意事件不合法'));
    }
    const owner = options && options.getUserID ? String(options.getUserID() || '') : 'fixture';
    if (!owner) return Promise.reject(new Error('请先登录后记录同意'));
    const key = [owner, docType, scene, eventType, input.scopeType || '', input.scopeId || ''].join('|');
    const homeScene = docType === 'privacy_policy' && scene === 'home_city_location';
    const locationScene = docType === 'privacy_policy' && (scene === 'roam_location' || homeScene);
    const intentKey = [owner, docType, scene].join('|');
    if (locationScene && input._locationIntent == null) {
      const generation = (locationIntents.get(intentKey) || 0) + 1;
      locationIntents.set(intentKey, generation);
      input = Object.assign({}, input, { _locationIntent: generation });
      if (eventType === 'REVOKE') {
        const agreeKey = [owner, docType, scene, 'AGREE', '', ''].join('|');
        pending.delete(agreeKey); if (options.setPending) options.setPending(agreeKey, null);
      }
    }
    const intentCurrent = function () { return !locationScene || locationIntents.get(intentKey) === input._locationIntent; };
    const locationAgree = locationScene && eventType === 'AGREE';
    const saved = pending.get(key) || options && options.getPending && options.getPending(key);
    if (locationAgree && !input._locationBaseline) {
      // Freeze the preflight revision with the idempotency key. A timed-out old AGREE never adopts a later REVOKE.
      if (saved && typeof saved === 'object' && typeof saved.expectedRequestId === 'string' && saved.requestId) {
        return record(Object.assign({}, input, { _locationBaseline: saved }));
      }
      return new Promise(function (resolve, reject) {
        let settled = false;
        const finish = function (error, row) {
          if (settled) return; settled = true; clearTimeout(timer);
          if (error || !intentCurrent() || options.getUserID && owner !== String(options.getUserID() || '')) {
            reject(error || new Error('账号已变化')); return;
          }
          const baseline = { requestId: 'consent-' + now() + '-' + random(), expectedRequestId: row ? row.requestId : '' };
          pending.set(key, baseline); if (options.setPending) options.setPending(key, baseline);
          record(Object.assign({}, input, { _locationBaseline: baseline })).then(resolve, reject);
        };
        const timer = setTimeout(function () { finish(new Error('同意记录超时')); }, settleTimeoutMs);
        try { sendRequest({
          url: '/api/compliance/consents/latest', method: 'POST', data: JSON.stringify({ docType, scene }),
          header: { 'Content-Type': 'application/json' }, hideLoading: true, autoErrorToast: false,
          success: function (res) {
            const row = res && res.data;
            if (!res || Number(res.code) !== 200 || row && (row.docType !== docType || row.scene !== scene
                || typeof row.requestId !== 'string' || !row.requestId || !['AGREE', 'REVOKE'].includes(row.eventType)
                || homeScene && (typeof row.docVersion !== 'string' || !row.docVersion.trim()
                    || row.scopeType != null || row.scopeId != null))) {
              finish(new Error('定位授权状态读取失败')); return;
            }
            finish(null, row || null);
          },
          successStatusAbnormal: function () { finish(new Error('定位授权状态读取失败')); },
          fail: function () { finish(new Error('定位授权状态读取失败')); },
        }); } catch (error) { finish(new Error('定位授权状态读取失败')); }
      });
    }
    let requestId = locationAgree ? input._locationBaseline.requestId : saved;
    if (requestId && typeof requestId !== 'string') requestId = null;
    if (!requestId) {
      requestId = 'consent-' + now() + '-' + random();
      pending.set(key, requestId);
      if (options && options.setPending) options.setPending(key, requestId);
    }
    const data = { docType, scene, eventType, requestId };
    if (locationAgree) data.expectedRequestId = input._locationBaseline.expectedRequestId;
    if (input.scopeType) data.scopeType = input.scopeType;
    if (input.scopeId != null) data.scopeId = input.scopeId;
    return new Promise(function (resolve, reject) {
      let settled = false;
      const settle = function (fn, value) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        fn(value);
      };
      // SC-PRIV-03:撤回超时/回包丢失 = 结果未知(服务端可能已落库)。先用既有 latest 回读服务端真状态:
      // 只有精确回读本次原请求才能确认；历史撤回不能证明本次审计已落库。
      let checking = false;
      const unknown = function (error) {
        if (settled || checking) return;
        if (eventType !== 'REVOKE') { settle(reject, error); return; }
        checking = true;
        clearTimeout(timer);
        const giveUp = function () { settle(reject, error); };
        const readTimer = setTimeout(giveUp, settleTimeoutMs);
        const done = function (fn) { clearTimeout(readTimer); if (!settled) fn(); };
        const readData = { docType, scene };
        if (data.scopeType != null) readData.scopeType = data.scopeType;
        if (data.scopeId != null) readData.scopeId = data.scopeId;
        try { sendRequest({
          url: '/api/compliance/consents/latest', method: 'POST', data: JSON.stringify(readData),
          header: { 'Content-Type': 'application/json' }, hideLoading: true, autoErrorToast: false,
          success: function (res) {
            const row = res && res.data;
            if (res && Number(res.code) === 200 && row && row.docType === docType && row.scene === scene
                && row.eventType === 'REVOKE' && row.requestId === requestId
                && typeof row.docVersion === 'string' && row.docVersion.trim()
                && (!VERSIONS[docType] || row.docVersion === VERSIONS[docType])
                && (data.scopeType == null ? null : data.scopeType) === (row.scopeType == null ? null : row.scopeType)
                && (data.scopeId == null ? null : data.scopeId) === (row.scopeId == null ? null : row.scopeId)
                && intentCurrent() && (!options.getUserID || owner === String(options.getUserID() || ''))) {
              done(function () {
                pending.delete(key);
                if (options.setPending) options.setPending(key, null);
                settle(resolve, { requestId: requestId, response: res, readback: row });
              });
              return;
            }
            done(giveUp);
          },
          successStatusAbnormal: function () { done(giveUp); },
          fail: function () { done(giveUp); },
        }); } catch (readError) { done(giveUp); }
      };
      const timer = setTimeout(function () {
        unknown(new Error('同意记录超时'));
      }, settleTimeoutMs);
      try { sendRequest({
        // 后端 /api/compliance/consents 是 @RequestBody 端点,必须发 JSON;缺 header 会被当 urlencoded,
        // 后端抛 HttpMediaTypeNotSupportedException 被全局兜成 200+code500,同意记录静默不落库
        url: '/api/compliance/consents',
        method: 'POST',
        data: JSON.stringify(data),
        header: { 'Content-Type': 'application/json' },
        hideLoading: true,
        autoErrorToast: false,
        success: function (res) {
          const row = res && res.data;
          if (res && Number(res.code) === 200 && row && row.requestId === requestId
              && row.docType === docType && row.scene === scene && row.eventType === eventType
              && typeof row.docVersion === 'string' && row.docVersion.trim()
              && (!homeScene || row.docVersion === VERSIONS.privacy_policy)
              && (data.scopeType || null) === (row.scopeType || null) && (data.scopeId || null) === (row.scopeId || null)
              && intentCurrent() && (!options.getUserID || owner === String(options.getUserID() || ''))) {
            pending.delete(key);
            if (options.setPending) options.setPending(key, null);
            settle(resolve, { requestId: requestId, response: res });
            return;
          }
          if (locationScene && res && Number(res.code) === 409) {
            const stored = pending.get(key) || options.getPending && options.getPending(key);
            const storedId = stored && typeof stored === 'object' ? stored.requestId : stored;
            if (storedId === requestId) {
              pending.delete(key); if (options.setPending) options.setPending(key, null);
            }
          }
          settle(reject, new Error((res && res.msg) || '同意记录失败'));
        },
        successStatusAbnormal: function () { settle(reject, new Error('同意记录失败')); },
        fail: function () {
          unknown(new Error('同意记录失败'));
        },
      }); } catch (error) { settle(reject, new Error('同意记录失败')); }
    });
  }

  return { record: record };
}

module.exports = { createConsentClient: createConsentClient };
