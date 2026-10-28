// 统一上传池：限制并发、逐文件 watchdog、整批 deadline，并返回可取消控制器。
'use strict';

var safeUserMessage = require('./safe-user-message.js').safeUserMessage;
var normalizeClientVersion = require('../client-version.js').normalizeClientVersion;

// 服务端真实单文件限额,与 application.yml 的 spring.servlet.multipart.max-file-size=10MB 同源。
// 只用于在**共享上传入口**按调用方已经拿到的文件大小提前拒绝,不放宽也不替代服务端拦截:
// 未超限仍照常上传,size 未知则无法前置判断,只能依赖服务端(其响应体能否回到客户端另见边界)。
var MAX_UPLOAD_FILE_SIZE = 10 * 1024 * 1024;

function megabytes(bytes) {
  return Math.round(bytes / (1024 * 1024)) + 'MB';
}

/** 超限的可操作文案:说明压缩/换文件路径,与限额同源。 */
function oversizeMessage(maxBytes) {
  return '文件过大，请压缩或更换文件后上传（单个文件最大' + megabytes(maxBytes) + '）';
}

/** 只有**已知且有限**的大小才可能前置拒绝;未知/非法大小一律放行交给服务端。 */
function isKnownOversize(size, maxBytes) {
  return typeof size === 'number' && isFinite(size) && size > maxBytes;
}

function createUploadClient(deps) {
  deps = deps || {};
  var wxUploadFile = deps.wxUploadFile;
  var getBaseUrl = deps.getBaseUrl;
  var getAuthorization = deps.getAuthorization;
  var getClientVersion = deps.getClientVersion || function () { return ''; };
  var getUserID = deps.getUserID || function () { return ''; };
  var uploadPath = deps.uploadPath || '/api/common/uploadOSS';
  var pendingMedia = {}; // Scope by real member and local file; retry checks the original asset, never reuploads it.
  var setTimer = deps.setTimer || setTimeout;
  var clearTimer = deps.clearTimer || clearTimeout;

  if (!wxUploadFile || !getBaseUrl || !getAuthorization) {
    throw new Error('upload-client: 缺少 wxUploadFile/getBaseUrl/getAuthorization 依赖');
  }

  /** multipart 的认证头。两条上传通道必须用同一份构造,手拼的那份将来换 token 方案会漏改。 */
  function authHeader() {
    var authorization = getAuthorization();
    var header = authorization ? { Authorization: 'Bearer ' + authorization } : {};
    var version = '';
    try { version = normalizeClientVersion(getClientVersion()); } catch (unavailable) {}
    if (version) header['X-Mini-Program-Version'] = version;
    return Object.keys(header).length ? header : undefined;
  }

  function schedule(fn, ms) {
    var handle = setTimer(fn, ms);
    if (handle && typeof handle.unref === 'function') handle.unref();
    return handle;
  }

  function uploadAll(filePaths, opts) {
    opts = opts || {};
    var member = String(getUserID() || '');
    filePaths = Array.isArray(filePaths) ? filePaths.slice() : [];
    var total = filePaths.length;
    var concurrency = Math.max(1, Math.min(3, Number(opts.concurrency) || 3));
    var perFileTimeoutMs = Math.max(1000, Number(opts.perFileTimeoutMs) || 15000);
    var totalDeadlineMs = Math.max(perFileTimeoutMs, Number(opts.totalDeadlineMs) || 60000);
    var mapResult = opts.mapResult || function (data) { return data.url; };
    var onDone = opts.onDone || function () {};
    // 调用方若已拿到每个文件的大小(如 wx.chooseMessageFile 的 tempFiles[i].size),就传进来,
    // 让共享入口在发起 wx.uploadFile 之前拒绝已知超限;数组缺失/该位未知都视为「未知大小」放行。
    var fileSizes = Array.isArray(opts.fileSizes) ? opts.fileSizes : [];
    var maxFileSize = Number(opts.maxFileSize) > 0 ? Number(opts.maxFileSize) : MAX_UPLOAD_FILE_SIZE;
    var results = [];
    var failures = [];
    var nextIndex = 0;
    var settledCount = 0;
    var finished = false;
    var active = {};
    var settled = {};
    var deadlineTimer = null;

    function abortRecord(record) {
      if (!record) return;
      if (record.watchdog !== null) clearTimer(record.watchdog);
      record.watchdog = null;
      try { if (record.task && typeof record.task.abort === 'function') record.task.abort(); } catch (e) {}
    }

    function cleanup() {
      if (deadlineTimer !== null) clearTimer(deadlineTimer);
      deadlineTimer = null;
      Object.keys(active).forEach(function (key) { abortRecord(active[key]); });
      active = {};
    }

    function finish(cancelled) {
      if (finished) return;
      finished = true;
      cleanup();
      if (!cancelled && String(getUserID() || '') === member) onDone({ ok: failures.length === 0, results: results, failures: failures });
    }

    function isCurrentOwner() {
      if (finished) return false;
      if (String(getUserID() || '') === member) return true;
      finish(true);
      return false;
    }

    function settle(index, failure) {
      if (!isCurrentOwner() || settled[index]) return;
      settled[index] = true;
      var record = active[index];
      if (record && record.watchdog !== null) clearTimer(record.watchdog);
      delete active[index];
      if (failure) failures.push(Object.assign({ index: index, filePath: filePaths[index] }, failure));
      settledCount += 1;
      if (settledCount >= total) finish(false);
      else launch();
    }

    function start(index) {
      var filePath = filePaths[index];
      // 已知超限:在共享入口直接拒绝,给出压缩/换文件路径,不发起注定被容器截断的连接。
      // size 未知(如裁剪后/录音临时文件)不在此列,仍走上传,由服务端拦截。
      if (isKnownOversize(fileSizes[index], maxFileSize)) {
        settle(index, { status: 'failed', reason: 'oversize', size: fileSizes[index], msg: oversizeMessage(maxFileSize) });
        return;
      }
      var formData = (opts.formData && opts.formData(index)) || {};
      if (opts.bizType) formData.bizType = typeof opts.bizType === 'function' ? opts.bizType(index) : opts.bizType;
      if (!isCurrentOwner()) return;
      var record = { task: null, watchdog: null };
      active[index] = record;
      record.watchdog = schedule(function () {
        var task = record.task;
        record.task = null;
        // retryable：网络/超时/HTTP 5xx，原文件可原样重传；其余失败由调用方让用户换文件。
        settle(index, { status: 'unknown', retryable: true, msg: '上传超时，请重试此文件' });
        try { if (task && typeof task.abort === 'function') task.abort(); } catch (e) {}
      }, perFileTimeoutMs);

      function accept(data) {
        if (!isCurrentOwner()) return;
          if (!data || data.code != 200) {
            settle(index, { status: 'failed', msg: safeUserMessage(data, '上传失败') });
            return;
          }
          var assetId = String(data.assetId || '');
          if (data.reviewState && data.reviewState !== 'APPROVED') {
            if (/^[1-9][0-9]{0,18}$/.test(assetId)) pendingMedia[member + '|' + filePath] = assetId;
            settle(index, { status: 'review_pending', assetId: assetId, rejected: data.reviewState === 'REJECTED',
              msg: data.reviewState === 'REJECTED' ? (data.reviewReason || '媒体审核未通过，可重新提交审核') : '文件已提交审核，通过后请重试此文件' });
            return;
          }
          delete pendingMedia[member + '|' + filePath];
          var mapped;
          try { mapped = mapResult(data, index, filePath); }
          catch (e) { settle(index, { status: 'failed', msg: '上传结果解析失败' }); return; }
          var resultUrl = typeof mapped === 'string' ? mapped : mapped && mapped.url;
          if (typeof resultUrl !== 'string' || !resultUrl.trim()) {
            settle(index, { status: 'failed', msg: '上传结果缺少文件地址' });
            return;
          }
          // OSS 上传回包可能用 http；媒体表单要求 https。只换 OSS 协议，完整保留签名。
          var secureUrl = resultUrl.replace(/^http:\/\/([a-z0-9-]+\.oss-[a-z0-9-]+\.aliyuncs\.com)(\/)/i, 'https://$1$2');
          results[index] = typeof mapped === 'string' ? secureUrl : Object.assign({}, mapped, { url: secureUrl });
          settle(index);
      }
      var pendingId = pendingMedia[member + '|' + filePath];
      if (pendingId) {
        var sender = deps.sendRequest || (typeof getApp === 'function' && getApp().sendRequest.bind(getApp()));
        if (!sender) { settle(index, { status: 'failed', msg: '媒体审核状态暂时无法读取，请重试' }); return; }
        sender({ url: '/api/media/asset/' + pendingId, method: 'GET', hideLoading: true,
          success: function (reply) {
            if (!isCurrentOwner()) return;
            if (reply && reply.code == 200 && reply.data && ['REJECTED', 'UNAVAILABLE'].indexOf(reply.data.reviewState) !== -1) {
              sender({ url: '/api/media/asset/' + pendingId + '/retry', method: 'POST', hideLoading: true,
                success: function (retry) { accept(retry && retry.code == 200 ? Object.assign({ code: 200 }, retry.data) : retry); },
                fail: function (error) { settle(index, { status: 'failed', msg: safeUserMessage(error, '媒体重新提审失败，请重试') }); } });
            } else accept(reply && reply.code == 200 ? Object.assign({ code: 200 }, reply.data) : reply);
          },
          fail: function (error) { settle(index, { status: 'failed', msg: safeUserMessage(error, '媒体审核状态暂时无法读取，请重试') }); }
        });
        return;
      }

      var task = wxUploadFile({
        url: getBaseUrl() + uploadPath,
        filePath: filePath,
        name: 'file',
        header: authHeader(),
        formData: Object.keys(formData).length ? formData : undefined,
        success: function (res) {
          if (!isCurrentOwner()) return;
          if (res.statusCode == 426) { settle(index, { status: 'failed', reason: 'upgradeRequired', msg: '当前版本需要更新，请先保存内容并重新打开小程序' }); return; }
          if (Number(res.statusCode) >= 500) {
            var body = null;
            try { body = JSON.parse(res.data); } catch (e) {}
            settle(index, { status: 'failed', retryable: true, msg: safeUserMessage(body, '服务暂不可用，请重试此文件') }); return;
          }
          var data;
          try { data = JSON.parse(res.data); }
          catch (e) { settle(index, { status: 'failed', msg: '上传结果解析失败' }); return; }
          if (data && data.code == 426) { settle(index, { status: 'failed', reason: 'upgradeRequired', msg: '当前版本需要更新，请先保存内容并重新打开小程序' }); return; }
          accept(data);
        },
        fail: function (res) {
          settle(index, { status: 'failed', retryable: true, msg: safeUserMessage(res, '上传失败') });
        }
      });
      if (active[index] === record) record.task = task || null;
    }

    function launch() {
      if (!isCurrentOwner()) return;
      while (!finished && Object.keys(active).length < concurrency && nextIndex < total) start(nextIndex++);
    }

    var control = {
      abort: function () { finish(true); },
      destroy: function () { finish(true); }
    };
    if (total === 0) {
      finish(false);
      return control;
    }
    deadlineTimer = schedule(function () {
      if (!isCurrentOwner()) return;
      for (var i = 0; i < total; i++) {
        if (!settled[i]) {
          settled[i] = true;
          failures.push({ index: i, filePath: filePaths[i], status: 'unknown', retryable: true, msg: '上传结果未知，请重试此文件' });
        }
      }
      settledCount = total;
      finish(false);
    }, totalDeadlineMs);
    launch();
    return control;
  }

  /**
   * 单文件上传,把**原始响应体**交回调用方。
   *
   * <p>存在的理由:有的上传通道返回的不是文件地址(语音提问返回的是门店分身的回答),
   * uploadAll 强制要求结果里有 url,套不上。但另起一条裸 wx.uploadFile 会漏掉
   * **认证头**构造 —— 那才是真正的问题,所以把它收在这里共用。
   *
   * @param {string} filePath 本地文件路径
   * @param {{path:string, name?:string, formData?:object, timeoutMs?:number,
   *          fileSize?:number, maxFileSize?:number, onDone:function}} opts
   *        onDone 收 { ok:true, data } 或 { ok:false, msg }
   * @returns {{abort:function}} 可取消控制器;abort 后不再回调
   */
  function uploadOne(filePath, opts) {
    opts = opts || {};
    var member = String(getUserID() || '');
    var path = opts.path || uploadPath;
    var onDone = opts.onDone || function () {};
    var timeoutMs = Math.max(1000, Number(opts.timeoutMs) || 20000);
    var maxFileSize = Number(opts.maxFileSize) > 0 ? Number(opts.maxFileSize) : MAX_UPLOAD_FILE_SIZE;
    var done = false;
    var task = null;
    var watchdog = null;

    function settle(payload) {
      if (done) return;
      done = true;
      if (watchdog !== null) clearTimer(watchdog);
      watchdog = null;
      if (payload && String(getUserID() || '') === member) onDone(payload);
    }

    // 已知超限:同 uploadAll,在共享入口拒绝,不发起注定被容器截断的连接。
    if (isKnownOversize(opts.fileSize, maxFileSize)) {
      settle({ ok: false, reason: 'oversize', size: opts.fileSize, msg: oversizeMessage(maxFileSize) });
      return { abort: function () {} };
    }

    watchdog = schedule(function () {
      var pending = task;
      task = null;
      settle({ ok: false, msg: '上传超时，请重试' });
      try { if (pending && typeof pending.abort === 'function') pending.abort(); } catch (e) {}
    }, timeoutMs);

    task = wxUploadFile({
      url: getBaseUrl() + path,
      filePath: filePath,
      name: opts.name || 'file',
      header: authHeader(),
      formData: opts.formData,
      success: function (res) {
        if (res.statusCode == 426) { settle({ ok: false, reason: 'upgradeRequired', msg: '当前版本需要更新，请先保存内容并重新打开小程序' }); return; }
        var data;
        try { data = JSON.parse(res.data); }
        catch (e) { settle({ ok: false, msg: '上传结果解析失败' }); return; }
        if (data && data.code == 426) { settle({ ok: false, reason: 'upgradeRequired', msg: '当前版本需要更新，请先保存内容并重新打开小程序' }); return; }
        if (!data || data.code != 200) { settle({ ok: false, msg: safeUserMessage(data, '上传失败') }); return; }
        settle({ ok: true, data: data });
      },
      fail: function (res) { settle({ ok: false, msg: safeUserMessage(res, '上传失败') }); }
    });

    return {
      abort: function () {
        var pending = task;
        task = null;
        settle(null);                 // 主动取消不回调:调用方自己知道
        try { if (pending && typeof pending.abort === 'function') pending.abort(); } catch (e) {}
      }
    };
  }

  return { uploadAll: uploadAll, uploadOne: uploadOne };
}

module.exports = {
  createUploadClient: createUploadClient,
  MAX_UPLOAD_FILE_SIZE: MAX_UPLOAD_FILE_SIZE,
  oversizeMessage: oversizeMessage,
};
