'use strict';

// unknown 不是失败：写入可能已在服务端提交。当前台账没有能关联“这一次请求”的
// 服务端事实键，所以只能打开记录供人工核对，绝不能拿任意第一页成功响应释放业务锁。
function offerVerificationReadback(key, workflow, adapters) {
  key = String(key || '');
  if (!key || !workflow) return false;
  adapters = adapters || {};
  // 默认走全站出口 utils/modal.js(与 wx.showModal 同形),不直接依赖原生
  var showModal = adapters.showModal || require('./modal.js').show;
  var navigateTo = adapters.navigateTo || wx.navigateTo;
  showModal({
    title: '核销结果待确认',
    content: '请打开核销记录确认结果；记录暂未出现也不能证明未核销，请勿退出后重新提交。无法确认时请联系平台处理。',
    confirmText: '查看记录',
    cancelText: '稍后处理',
    success: function (result) {
      if (!result || !result.confirm) return;
      navigateTo({
        url: '/pages/merchant/ledger/index?view=redemptions'
      });
    }
  });
  return true;
}


// 沿用 template-binding 的 SHA-256 实现。该文件位于发布分包，根工具不能反向 require 分包；
// 这里只复用其纯摘要实现，并在测试中同时对照既有实现和 Node 标准向量，不引入依赖。
const HASH = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];
const rotate = (x, n) => (x >>> n) | (x << (32 - n));

function sha256(value) {
  const bytes = [];
  for (let i = 0; i < value.length; i++) {
    let cp = value.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff) {
      const low = value.charCodeAt(i + 1);
      if (!(low >= 0xdc00 && low <= 0xdfff)) throw Error('invalid UTF-16');
      cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
      i += 1;
    } else if (cp >= 0xdc00 && cp <= 0xdfff) throw Error('invalid UTF-16');
    if (cp < 0x80) bytes.push(cp);
    else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 63));
    else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
    else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
  }
  const bitLength = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  for (let i = 7; i >= 0; i--) bytes.push(Math.floor(bitLength / 2 ** (i * 8)) & 255);
  const h = HASH.slice();
  for (let offset = 0; offset < bytes.length; offset += 64) {
    const w = new Array(64);
    for (let i = 0; i < 16; i++) w[i] = (bytes[offset + i * 4] << 24) | (bytes[offset + i * 4 + 1] << 16) | (bytes[offset + i * 4 + 2] << 8) | bytes[offset + i * 4 + 3];
    for (let i = 16; i < 64; i++) {
      const x = w[i - 15], y = w[i - 2];
      w[i] = (w[i - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3)) + w[i - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10))) | 0;
    }
    let [a, b, c, d, e, f, g, z] = h;
    for (let i = 0; i < 64; i++) {
      const s1 = rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25);
      const t1 = (z + s1 + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      const s0 = rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22);
      const t2 = (s0 + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      z = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    [a, b, c, d, e, f, g, z].forEach((v, i) => { h[i] = (h[i] + v) | 0; });
  }
  return h.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
}

const { createWriteActionWorkflow } = require('./write-action-workflow.js');
let verificationSequence = 0;
const unknownResult = () => ({ status: 'unknown', errMsg: '核销结果待确认，请勿重复核销', response: { code: 202, msg: '核销结果待确认，请勿重复核销' } });
const missing = row => row === undefined || row === null || row === '';
function createVerificationWorkflow(options) {
  options = options || {};
  const storage = options.storage;
  const workflow = createWriteActionWorkflow(options);
  let destroyed = false;
  function metadata(key) {
    const owner = String(options.getOwner && options.getOwner() || '');
    if (!owner || !String(key).startsWith(owner + ':')) throw Error('unbound');
    const ownerDigest = sha256('verification-owner:' + owner);
    const targetDigest = sha256('verification-target:' + String(key).slice(owner.length + 1));
    return { key: 'verification_unknown_v1:' + sha256(ownerDigest + ':' + targetDigest), ownerDigest, targetDigest };
  }
  function read(meta) {
    const row = storage.getStorageSync(meta.key);
    if (missing(row)) return null;
    if (!row || typeof row !== 'object' || Array.isArray(row) || row.version !== 1 || row.state !== 'UNKNOWN'
        || row.ownerDigest !== meta.ownerDigest || row.targetDigest !== meta.targetDigest
        || typeof row.attemptId !== 'string' || !/^[a-z0-9_-]{16,96}$/.test(row.attemptId)
        || row.requestIdentityVersion !== undefined && row.requestIdentityVersion !== 1
        || !Number.isSafeInteger(row.createdAt) || row.createdAt <= 0) throw Error('unreadable');
    return row;
  }
  function removeOriginal(meta, row) {
    try {
      const current = read(meta);
      if (!current || current.attemptId !== row.attemptId
          || current.requestIdentityVersion !== row.requestIdentityVersion) return false;
      storage.removeStorageSync(meta.key);
      return missing(storage.getStorageSync(meta.key));
    } catch (error) { return false; }
  }
  function blocked(callback, message) {
    callback({ status: 'blocked', response: { code: 400, msg: message } });
    // 终态提示已由 callback 处理；true 不表示已经发出了 POST。
    return true;
  }
  function requestIdentity(row) {
    return { requestId: row.requestIdentityVersion === 1 ? row.attemptId : null,
      legacyRequest: row.requestIdentityVersion !== 1 };
  }
  function withIdentity(result, row) {
    return options.requestIdentity === true ? Object.assign({}, result, requestIdentity(row)) : result;
  }
  function launch(key, meta, row, start, callback) {
    let entered = false, closed = false;
    const accepted = workflow.run(key, function (done) {
      entered = true;
      function finish(result) {
        if (closed || destroyed) return;
        closed = true;
        result = result || unknownResult();
        // 迟回包只能结算捕获的原待办，绝不按当前账号或新请求删除。
        // 已证实的回执不因本机清理异常改报 UNKNOWN：删除可能已经完成。
        // 未清掉的原记录仍会在下一次 run/重建时阻止 POST。
        if (result.status === 'success' || result.status === 'failed') removeOriginal(meta, row);
        done(result);
      }
      try { return start(finish, options.requestIdentity === true ? { requestId: row.attemptId } : undefined); }
      catch (error) { finish(unknownResult()); return null; }
    }, function (result) { closed = true; callback(withIdentity(result, row)); });
    return { accepted, entered };
  }
  return {
    isBusy: key => workflow.isBusy(key),
    hold: key => workflow.hold(key),
    pendingRequest(key) {
      if (destroyed || options.requestIdentity !== true) return null;
      const row = read(metadata(key));
      return row ? { requestId: requestIdentity(row).requestId, legacy: row.requestIdentityVersion !== 1 } : null;
    },
    // 调用方须先核对服务端原 requestId 的已创建回执；负向/列表快照不允许调用。
    confirmRequest(key, requestId) {
      if (destroyed || options.requestIdentity !== true || typeof requestId !== 'string') return false;
      try {
        const meta = metadata(key), row = read(meta);
        if (!row || row.requestIdentityVersion !== 1 || row.attemptId !== requestId || !removeOriginal(meta, row)) return false;
        workflow.reset(key);
        return true;
      } catch (error) { return false; }
    },
    run(key, start, callback) {
      if (destroyed) return false;
      let meta, row;
      try { meta = metadata(key); row = read(meta); }
      catch (error) { return blocked(callback, '无法读取核销待办，本次未提交，请检查本机存储及登录状态'); }
      if (row) {
        workflow.hold(key);
        callback(withIdentity(Object.assign(unknownResult(), { recovered: true }), row));
        return true;
      }
      // POST 前按原账号+入口/原码目标摘要占住待办；即使此刻被系统杀死也不能重放。
      verificationSequence += 1;
      row = { version: 1, state: 'UNKNOWN', ownerDigest: meta.ownerDigest, targetDigest: meta.targetDigest,
        attemptId: 'vr_' + Date.now().toString(36) + '_' + verificationSequence.toString(36) + '_' + Math.random().toString(36).slice(2),
        createdAt: Date.now() };
      row.attemptId = row.attemptId.padEnd(32, '0');
      if (options.requestIdentity === true) row.requestIdentityVersion = 1;
      try {
        storage.setStorageSync(meta.key, row);
        const stored = read(meta);
        if (!stored || Object.keys(stored).length !== Object.keys(row).length || !Object.keys(row).every(field => stored[field] === row[field])) throw Error('not saved');
      } catch (error) {
        // start 尚未调用：只能清理仍属于本次、明确未发出的占位；清理失败就继续留存。
        removeOriginal(meta, row);
        return blocked(callback, '核销待办未能保存，本次未提交，请检查本机存储后再试');
      }
      const launched = launch(key, meta, row, start, callback);
      if (!launched.accepted && !launched.entered) removeOriginal(meta, row);
      return launched.accepted;
    },
    // 仅当调用方已按原 requestId 精确回读到「服务端无此原请求」(NOT_FOUND)时调用：
    // 沿用同一 requestId 重发（服务端按原请求号去重/回放），不新建待办、不换号。
    resumeRequest(key, requestId, start, callback) {
      if (destroyed || options.requestIdentity !== true || typeof requestId !== 'string' || workflow.inFlight(key)) return false;
      let meta, row;
      try { meta = metadata(key); row = read(meta); } catch (error) { return false; }
      if (!row || row.requestIdentityVersion !== 1 || row.attemptId !== requestId) return false;
      workflow.reset(key);
      return launch(key, meta, row, start, callback).accepted;
    },
    // 销毁只销毁内存工作流；磁盘 UNKNOWN 只允许原请求的明确回执结算，不能随离页清除。
    destroy() { destroyed = true; workflow.destroy(); },
  };
}

module.exports = { offerVerificationReadback, createVerificationWorkflow };
