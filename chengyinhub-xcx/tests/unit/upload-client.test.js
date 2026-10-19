// Phase 1.3 上传通道 upload-client —— 先写失败测试(TDD RED)。
//
// 不可破坏契约(current-api-inventory.md 7.3):
//  #21 url=/api/common/uploadOSS、name='file'、chooseDocument 的 formData{fileType,fileName}、
//      成功判定 data.code==200、URL 取 data.url —— 全部保持。
//  #24 独立 multipart 传输路径同样必须携带 Authorization。
//  #23 全部成功才回调完整结果数组;修复后不得中途泄漏/卡死 loading。
// 修复(line32 记录的 bug):多文件部分失败时,旧代码计数永远到不了总数 → callback 不触发;
//      新模块保证「所有文件 settle(成功或失败)后,onDone 恰好触发一次」,返回失败列表。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createUploadClient, MAX_UPLOAD_FILE_SIZE, oversizeMessage } = require('../../utils/transport/upload-client.js');

function makeAppUploader() {
  const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
  const root = path.join(__dirname, '../..');
  const calls = [], aborted = [];
  let app;
  const sandbox = {
    App: config => { app = config; },
    wx: { uploadFile: request => {
      calls.push(request);
      return { abort() { aborted.push(request.filePath); request.fail({ errMsg: 'abort' }); } };
    } },
    require: request => require(path.join(root, request)),
    console,
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), sandbox, { filename: 'app.js' });
  app.globalData = { siteBaseUrl: 'https://fixture.example' };
  const values = new Map();
  app._session = require('../../utils/session/session-store.js').createSessionStore({
    target: app.globalData,
    storage: { setStorageSync: (key, value) => values.set(key, value),
      getStorageSync: key => values.get(key), removeStorageSync: key => values.delete(key) },
  });
  app.getSession().applySnapshot({ user_id: 9, authorization: 'fixture-A', role: 'player', user_type: 1 });
  return { app, calls, aborted, client: app.getUploadClient() };
}

test('真实 app + session-store: 切账号取消在途三文件与排队第四，迟到回包不重复完成', () => {
  const { app, client, calls, aborted } = makeAppUploader();
  let done, count = 0;
  client.uploadAll(['a', 'b', 'c', 'd'], { onDone: result => { done = result; count++; } });
  assert.equal(calls.length, 3);
  app.getSession().applySnapshot({ user_id: 10, authorization: 'fixture-B', role: 'player', user_type: 1 });
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/a.jpg' }) });
  assert.equal(calls.length, 3, '旧批次的第四个文件不以新身份发起');
  assert.equal(done.ok, false);
  assert.deepEqual(done.results, []);
  assert.equal(done.failures.length, 4);
  assert.equal(done.failures[0].reason, 'identity_changed');
  assert.equal(aborted.length, 3);
  calls.forEach(request => {
    request.success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/late.jpg' }) });
    request.fail({ errMsg: 'late failure' });
  });
  assert.equal(count, 1);
});

test('真实 app + session-store: 切账号后丢弃批次内已成功的旧账号结果', () => {
  const { app, client, calls } = makeAppUploader();
  let done;
  client.uploadAll(['a', 'b'], { onDone: result => { done = result; } });
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/a.jpg' }) });
  app.setUserID(10);
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/b.jpg' }) });
  assert.equal(done.ok, false);
  assert.deepEqual(done.results, []);
});

test('真实 app + session-store: 正常 token 刷新与数字/字符串同账号继续上传', () => {
  const { app, client, calls } = makeAppUploader();
  let done;
  client.uploadAll(['a', 'b', 'c', 'd'], { onDone: result => { done = result; } });
  app.setAuthorization('fixture-refreshed');
  app.setUserID('9');
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/a.jpg' }) });
  assert.equal(calls.length, 4);
  assert.equal(calls[3].header.Authorization, 'Bearer fixture-refreshed');
  calls.slice(1).forEach((request, index) => request.success({
    statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/' + index + '.jpg' }),
  }));
  assert.equal(done.ok, true);
  assert.equal(done.results.length, 4);
});

test('真实 app + session-store: 清空账号拒绝单文件旧成功，abort同步回调与迟到结果只收尾一次', () => {
  const { app, client, calls, aborted } = makeAppUploader();
  let done, count = 0;
  client.uploadOne('voice', { onDone: result => { done = result; count++; } });
  app.getSession().clearSession();
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, answer: 'old account' }) });
  assert.equal(done.ok, false);
  assert.equal(done.reason, 'identity_changed');
  assert.equal(done.data, undefined);
  assert.equal(aborted.length, 1);
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, answer: 'late' }) });
  assert.equal(count, 1);
});

test('切账号后 watchdog/deadline 也拒绝整批，清理全部任务与计时器', () => {
  for (const time of [1000, 3000]) {
    let userId = 9, done, count = 0, aborted = 0;
    const timers = new Map();
    const { client } = makeUploader({
      getUserID: () => userId,
      wxUploadFile: request => ({ abort() { aborted++; request.fail({ errMsg: 'abort' }); } }),
      setTimer: (fn, ms) => { const id = {}; timers.set(id, { fn, ms }); return id; },
      clearTimer: id => timers.delete(id),
    });
    client.uploadAll(['a', 'b', 'c', 'd'], { perFileTimeoutMs: 1000, totalDeadlineMs: 3000,
      onDone: result => { done = result; count++; } });
    const pending = [...timers.values()];
    userId = 10;
    pending.find(timer => timer.ms === time).fn();
    assert.equal(done.ok, false);
    assert.deepEqual(done.results, []);
    assert.equal(done.failures[0].reason, 'identity_changed');
    assert.equal(aborted, 3);
    assert.equal(timers.size, 0);
    pending.forEach(timer => timer.fn());
    assert.equal(count, 1);
  }
});

test('身份源读取故障关闭上传，故障发生在途时不返回旧成功结果', () => {
  for (const single of [false, true]) {
    let failRead = true, done;
    const { client, calls } = makeUploader({ getUserID: () => {
      if (failRead) throw new Error('store unavailable');
      return 9;
    } });
    const start = () => single
      ? client.uploadOne('a', { onDone: result => { done = result; } })
      : client.uploadAll(['a'], { onDone: result => { done = result; } });
    assert.doesNotThrow(start);
    assert.equal(calls.length, 0);
    assert.equal(done.ok, false);
    failRead = false;
    start();
    assert.equal(calls.length, 1);
    failRead = true;
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/a.jpg' }) });
    assert.equal(done.ok, false);
    assert.equal(single ? done.reason : done.failures[0].reason, 'identity_changed');
  }
});

test('单文件切账号后超时只返回身份失败，主动abort后依旧不回调', () => {
  let userId = 9, done, count = 0, aborted = 0;
  const timers = new Map(), calls = [];
  const client = createUploadClient({
    getUserID: () => userId, getAuthorization: () => 'fixture', getBaseUrl: () => 'https://fixture.example',
    wxUploadFile: request => {
      calls.push(request);
      return { abort() { aborted++; request.fail({ errMsg: 'abort' }); } };
    },
    setTimer: (fn, ms) => { const id = {}; timers.set(id, { fn, ms }); return id; },
    clearTimer: id => timers.delete(id),
  });
  client.uploadOne('voice', { onDone: result => { done = result; count++; } });
  userId = 10;
  [...timers.values()][0].fn();
  assert.equal(done.ok, false);
  assert.equal(done.reason, 'identity_changed');
  assert.equal(aborted, 1);
  assert.equal(timers.size, 0);
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, answer: 'late' }) });
  assert.equal(count, 1);
  const control = client.uploadOne('cancelled', { onDone: () => count++ });
  userId = 11;
  control.abort();
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, answer: 'late' }) });
  assert.equal(count, 1);
  assert.equal(timers.size, 0);
});

function makeUploader(over) {
  const calls = [];
  const deps = {
    wxUploadFile: (opts) => { calls.push(opts); },   // 测试手动驱动 success/fail
    getBaseUrl: () => 'https://base',
    getAuthorization: () => 'token-123',
  };
  const client = createUploadClient(Object.assign(deps, over));
  return { client, calls };
}

test('单文件语音接受实际HTTP和业务成功，拒绝坏正文且取消后不回调', () => {
  for (const data of ['invalid-json', JSON.stringify({ code: 403 }), JSON.stringify({ code: 200, answer: 'voice result' })]) {
    const { client, calls } = makeUploader();
    let completed = 0, done;
    client.uploadOne('voice.mp3', { path: '/voice', onDone: r => { completed++; done = r; } });
    assert.equal(calls[0].url, 'https://base/voice');
    assert.equal(calls[0].header.Authorization, 'Bearer token-123');
    calls[0].success({ statusCode: 200, data });
    assert.equal(done.ok, data.includes('voice result'));
    if (done.ok) assert.equal(done.data.answer, 'voice result');
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, answer: 'late' }) });
    assert.equal(completed, 1);
  }
  const { client, calls } = makeUploader();
  let completed = 0;
  const control = client.uploadOne('voice.mp3', { onDone: () => completed++ });
  control.abort();
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, answer: 'late' }) });
  assert.equal(completed, 0);
});

test('文件超时后迟到成功不调用映射、不改变已确认失败的批次', () => {
  const timers = new Map();
  const { client, calls } = makeUploader({
    setTimer: (fn, ms) => { const id = {}; timers.set(id, { fn, ms }); return id; },
    clearTimer: id => timers.delete(id),
  });
  let done, mapped = 0;
  client.uploadAll(['a', 'b'], {
    perFileTimeoutMs: 1000,
    mapResult: data => { mapped++; return data.url; },
    onDone: result => { done = result; },
  });
  [...timers.values()].find(timer => timer.ms === 1000).fn();
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'late-A' }) });
  assert.equal(mapped, 0, '已超时文件的晚回包不再进入映射');
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'B' }) });
  assert.equal(done.ok, false);
  assert.equal(done.failures[0].status, 'unknown');
  assert.equal(done.results[0], undefined);
  assert.equal(done.results[1], 'B');
  const snapshot = JSON.stringify(done);
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'later-A' }) });
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'other-B' }) });
  assert.equal(JSON.stringify(done), snapshot);
  assert.equal(mapped, 1);
});

test('主动取消批量上传后迟到成功不映射或触发完成', () => {
  const { client, calls } = makeUploader();
  let mapped = 0, completed = 0;
  const control = client.uploadAll(['a'], {
    mapResult: data => { mapped++; return data.url; },
    onDone: () => completed++,
  });
  control.abort();
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'late' }) });
  assert.equal(mapped, 0);
  assert.equal(completed, 0);
});

test('上传启动同步抛错及时结束失败并继续后续文件，单文件也清理计时器', () => {
  for (const fault of ['upload', 'base', 'auth']) {
    const timers = new Set(), calls = [];
    let first = true, done, singleDone;
    const maybeThrow = type => {
      if (fault === type && first) { first = false; throw new Error('unavailable'); }
    };
    const client = createUploadClient({
      wxUploadFile: opts => { maybeThrow('upload'); calls.push(opts); },
      getBaseUrl: () => { maybeThrow('base'); return 'https://base'; },
      getAuthorization: () => { maybeThrow('auth'); return 'token'; },
      setTimer: () => { const id = {}; timers.add(id); return id; },
      clearTimer: id => timers.delete(id),
    });
    assert.doesNotThrow(() => client.uploadAll(['a', 'b'], { concurrency: 1, onDone: r => { done = r; } }));
    assert.equal(calls.length, 1, '同步失败后立即启动剩余文件');
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'B' }) });
    assert.equal(done.ok, false);
    assert.equal(done.failures[0].index, 0);
    assert.equal(done.results[1], 'B');
    assert.equal(timers.size, 0);
    first = true;
    assert.doesNotThrow(() => client.uploadOne('a', { onDone: r => { singleDone = r; } }));
    assert.equal(singleDone.ok, false);
    assert.equal(timers.size, 0);
  }
});

test('HTTP失败或缺失状态不能被业务200冒充成功，批量与单文件一致', () => {
  for (const statusCode of [undefined, 0, 201, 401, 403, 500, 503, '200']) {
    const { client, calls } = makeUploader();
    let batch, single;
    client.uploadAll(['a'], { onDone: r => { batch = r; } });
    calls[0].success({ statusCode, data: JSON.stringify({ code: 200, url: 'invalid' }) });
    assert.equal(batch.ok, false, String(statusCode));
    assert.equal(batch.results[0], undefined);
    client.uploadOne('a', { onDone: r => { single = r; } });
    calls[1].success({ statusCode, data: JSON.stringify({ code: 200, text: 'invalid' }) });
    assert.equal(single.ok, false, String(statusCode));
  }
});

test('multipart 工厂同步异常只拒绝该文件，整批仍完成且清理计时器', () => {
  for (const factory of ['formData', 'bizType']) {
    const timers = new Set();
    const { client, calls } = makeUploader({
      setTimer: () => { const id = {}; timers.add(id); return id; },
      clearTimer: id => timers.delete(id),
    });
    let done, count = 0;
    const failingFactory = index => { if (index === 0) throw new Error('invalid metadata'); return factory === 'bizType' ? 'image' : {}; };
    assert.doesNotThrow(() => client.uploadAll(['a', 'b'], {
      concurrency: 1, [factory]: failingFactory,
      onDone: result => { done = result; count++; },
    }));
    assert.equal(calls.length, 1);
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '/profile/b.jpg' }) });
    assert.equal(done.ok, false);
    assert.equal(done.failures[0].index, 0);
    assert.equal(done.results[1], '/profile/b.jpg');
    assert.equal(count, 1);
    assert.equal(timers.size, 0);
  }
});

test('默认上传结果不能把本地临时文件当作服务器证据', () => {
  for (const url of ['wxfile://tmp_a.jpg', 'http://tmp/a.jpg', 'https://tmp/a.jpg',
    '/tmp/a.jpg', 'file:///tmp/a.jpg', ' /tmp/a.jpg ', ' WXFILE://tmp_a.jpg ',
    '/private/tmp/a.jpg', './tmp/a.jpg', '../tmp/a.jpg', 'HTTP://TMP/a.jpg']) {
    const { client, calls } = makeUploader();
    let done;
    client.uploadAll(['/tmp/a.jpg'], { onDone: result => { done = result; } });
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url }) });
    assert.equal(done.ok, false, url);
    assert.equal(done.results[0], undefined);
    assert.match(done.failures[0].msg, /临时文件/);
    client.uploadAll(['/tmp/a.jpg'], {
      mapResult: data => ({ url: data.url, filename: 'a.jpg' }),
      onDone: result => { done = result; },
    });
    calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, url }) });
    assert.equal(done.ok, false, '文档/通用图片映射不能绕过临时 URL 校验: ' + url);
  }
});

test('上传参数:url=/api/common/uploadOSS、name=file、携带 Authorization', () => {
  const { client, calls } = makeUploader();
  client.uploadAll(['a.png'], { onDone: () => {} });
  assert.equal(calls[0].url, 'https://base/api/common/uploadOSS');
  assert.equal(calls[0].name, 'file');
  assert.equal(calls[0].filePath, 'a.png');
  assert.deepEqual(calls[0].header, { Authorization: 'Bearer token-123' });
});

test('全部成功:onDone ok=true,结果按 mapResult 提取', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['a.png', 'b.png'], { onDone: (r) => { done = r; } });
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'A' }) });
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'B' }) });
  assert.equal(done.ok, true);
  assert.deepEqual(done.results, ['A', 'B']);
  assert.equal(done.failures.length, 0);
});

test('R3 图片上传:业务200缺必需 URL 必须回调失败', () => {
  for (const url of [undefined, '', '   ']) {
    const { client, calls } = makeUploader();
    let done;
    client.uploadAll(['a.png'], { onDone: (r) => { done = r; } });
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url }) });

    assert.equal(done.ok, false, `URL=${String(url)} 不得当作上传成功`);
    assert.deepEqual(done.results, []);
    assert.equal(done.failures.length, 1);
    assert.equal(done.failures[0].msg, '上传结果缺少文件地址');
  }
});

test('R3 文档上传:结构化结果缺必需 URL 必须回调失败', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['d.pdf'], {
    mapResult: (data) => ({ url: data.url, filename: 'd.pdf' }),
    onDone: (r) => { done = r; },
  });
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200 }) });

  assert.equal(done.ok, false);
  assert.deepEqual(done.results, []);
  assert.equal(done.failures[0].msg, '上传结果缺少文件地址');
});

test('mapResult 自定义:返回结构化结果(chooseDocument 形态)', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['d.pdf'], {
    formData: () => ({ fileType: 'pdf', fileName: 'd.pdf' }),
    mapResult: (data, i) => ({ url: data.url, filename: 'd.pdf' }),
    onDone: (r) => { done = r; },
  });
  assert.deepEqual(calls[0].formData, { fileType: 'pdf', fileName: 'd.pdf' });
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'U' }) });
  assert.deepEqual(done.results, [{ url: 'U', filename: 'd.pdf' }]);
});

test('敏感上传:服务端不返回 URL 时可用本地临时路径预览', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['/tmp/evidence.jpg'], {
    bizType: 'merchant_aftercare_evidence',
    mapResult: (data, i, filePath) => ({
      fileName: data.fileName,
      url: filePath,
    }),
    onDone: (r) => { done = r; },
  });

  calls[0].success({
    statusCode: 200, data: JSON.stringify({
      code: 200,
      fileName: 'upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg',
    }),
  });

  assert.equal(done.ok, true);
  assert.deepEqual(done.results, [{
    fileName: 'upload/merchant-aftercare-evidence/0123456789abcdef0123456789abcdef.jpg',
    url: '/tmp/evidence.jpg',
  }]);
});

test('bizType 与既有 formData 合并,不能覆盖文档上传字段', () => {
  const { client, calls } = makeUploader();
  client.uploadAll(['cover.png'], {
    bizType: 'image_16_9',
    formData: () => ({ fileName: 'cover.png' }),
    onDone: () => {},
  });
  assert.deepEqual(calls[0].formData, { fileName: 'cover.png', bizType: 'image_16_9' });
});

test('部分失败(code!=200):onDone 仍触发一次,ok=false,带失败列表', () => {
  const { client, calls } = makeUploader();
  let done; let count = 0;
  client.uploadAll(['a.png', 'b.png'], { onDone: (r) => { done = r; count++; } });
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'A' }) });
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 500, msg: '太大' }) });
  assert.equal(count, 1, 'onDone 恰好一次');
  assert.equal(done.ok, false);
  assert.equal(done.results[0], 'A');
  assert.equal(done.failures.length, 1);
  assert.equal(done.failures[0].msg, '太大');
});

test('上传 fail 回调:计入失败,onDone 仍触发(不卡死)', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['a.png', 'b.png'], { onDone: (r) => { done = r; } });
  calls[0].fail({ errMsg: 'uploadFile:fail' });
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'B' }) });
  assert.equal(done.ok, false);
  assert.equal(done.failures.length, 1);
  assert.equal(done.results[1], 'B');
});

test('非法 JSON:计为失败,不抛异常', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['a.png'], { onDone: (r) => { done = r; } });
  calls[0].success({ statusCode: 200, data: 'not-json{' });
  assert.equal(done.ok, false);
  assert.equal(done.failures.length, 1);
});

test('onDone 在所有文件 settle 后只触发一次', () => {
  const { client, calls } = makeUploader();
  let count = 0;
  client.uploadAll(['a', 'b', 'c'], { onDone: () => { count++; } });
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '1' }) });
  assert.equal(count, 0, '未全部 settle 不触发');
  calls[1].fail({ errMsg: 'x' });
  assert.equal(count, 0);
  calls[2].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: '3' }) });
  assert.equal(count, 1);
});

test('上传池最多并发 3 个，settle 后才启动队列下一项', () => {
  const { client, calls } = makeUploader();
  client.uploadAll(['a', 'b', 'c', 'd', 'e'], { onDone() {} });
  assert.equal(calls.length, 3);
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'A' }) });
  assert.equal(calls.length, 4);
  calls[1].fail({ errMsg: 'uploadFile:fail' });
  assert.equal(calls.length, 5);
});

test('逐文件超时会 abort、保留失败项并继续队列；destroy 屏蔽 onDone', () => {
  const timers = [];
  const calls = [];
  let aborted = 0;
  const client = createUploadClient({
    wxUploadFile(opts) {
      calls.push(opts);
      return { abort() { aborted += 1; } };
    },
    getBaseUrl: () => 'https://base',
    getAuthorization: () => 'token',
    setTimer(fn, ms) { const t = { fn, ms, cleared: false }; timers.push(t); return t; },
    clearTimer(t) { t.cleared = true; },
  });
  let done;
  client.uploadAll(['a', 'b'], {
    concurrency: 1,
    perFileTimeoutMs: 1000,
    totalDeadlineMs: 5000,
    onDone: value => { done = value; },
  });
  const watchdog = timers.find(t => t.ms === 1000);
  watchdog.fn();
  assert.equal(aborted, 1);
  assert.equal(calls.length, 2);
  calls[1].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'B' }) });
  assert.equal(done.ok, false);
  assert.equal(done.failures[0].index, 0);
  assert.equal(done.failures[0].status, 'unknown');

  let called = 0;
  const control = client.uploadAll(['c'], { onDone() { called += 1; } });
  control.destroy();
  assert.equal(called, 0);
  assert.equal(aborted, 2);
});

test('上传 abort 同步触发 fail 时 watchdog 仍归类 unknown', () => {
  const timers = [];
  let request;
  let done;
  const client = createUploadClient({
    wxUploadFile(opts) {
      request = opts;
      return { abort() { request.fail({ errMsg: 'uploadFile:fail abort' }); } };
    },
    getBaseUrl: () => 'https://base',
    getAuthorization: () => 'token',
    setTimer(fn, ms) { const timer = { fn, ms }; timers.push(timer); return timer; },
    clearTimer() {},
  });
  client.uploadAll(['a'], {
    perFileTimeoutMs: 1000,
    totalDeadlineMs: 5000,
    onDone(value) { done = value; }
  });

  timers.find(timer => timer.ms === 1000).fn();

  assert.equal(done.failures[0].status, 'unknown');
});

// ---- R9-48 真实共享上传入口按已知 size 前置拒绝(不发起注定被容器截断的连接) ----

test('已知超限:不发起 wx.uploadFile,回调可操作提示(压缩/换文件)', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['big.mp3'], {
    fileSizes: [MAX_UPLOAD_FILE_SIZE + 1],
    onDone: (r) => { done = r; },
  });

  assert.equal(calls.length, 0, '已知超限不得发起上传');
  assert.equal(done.ok, false);
  assert.equal(done.failures.length, 1);
  assert.equal(done.failures[0].reason, 'oversize');
  assert.match(done.failures[0].msg, /文件过大/);
  assert.match(done.failures[0].msg, /压缩|更换/);
  assert.match(done.failures[0].msg, /10MB/);
});

test('恰好等于单文件限额:放行上传(边界不误伤)', () => {
  const { client, calls } = makeUploader();
  client.uploadAll(['edge.mp3'], {
    fileSizes: [MAX_UPLOAD_FILE_SIZE],
    onDone: () => {},
  });
  assert.equal(calls.length, 1);
});

test('size 未知:放行上传,由服务端拦截(不能前置断言)', () => {
  const { client, calls } = makeUploader();
  client.uploadAll(['unknown.mp3'], {
    fileSizes: [undefined],
    onDone: () => {},
  });
  assert.equal(calls.length, 1);
});

test('混合批量:只拒已知超限项,其余照常上传', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadAll(['ok.mp3', 'big.mp3'], {
    concurrency: 1,
    fileSizes: [1024, MAX_UPLOAD_FILE_SIZE + 5],
    onDone: (r) => { done = r; },
  });
  assert.equal(calls.length, 1, '只应发起未超限那一项');
  calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: 'OK' }) });
  assert.equal(done.ok, false);
  assert.equal(done.failures[0].index, 1);
  assert.equal(done.failures[0].reason, 'oversize');
});

test('uploadOne 已知超限:不发起上传并回调可操作提示', () => {
  const { client, calls } = makeUploader();
  let done;
  client.uploadOne('voice.mp3', {
    path: '/api/ai/npc/voice-chat',
    fileSize: MAX_UPLOAD_FILE_SIZE + 1,
    onDone: (r) => { done = r; },
  });
  assert.equal(calls.length, 0);
  assert.equal(done.ok, false);
  assert.equal(done.reason, 'oversize');
  assert.match(done.msg, /文件过大/);
});

test('oversizeMessage 文案与限额同源', () => {
  assert.match(oversizeMessage(MAX_UPLOAD_FILE_SIZE), /10MB/);
});

test('OSS 签名地址升级 HTTPS，保留 query 和结构化元数据，其他地址不改写', () => {
  const signed = 'http://sample-bucket.oss-cn-shanghai.aliyuncs.com/a.jpg?Expires=123&Signature=a%2Fb%2B&OSSAccessKeyId=sample';
  for (const structured of [false, true]) {
    const { client, calls } = makeUploader();
    let done;
    client.uploadAll(['a.jpg'], { onDone: value => { done = value; },
      ...(structured ? { mapResult: data => ({ url: data.url, filename: 'a.jpg' }) } : {}) });
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url: signed }) });
    assert.equal(done.ok, true);
    assert.deepEqual(done.results[0], structured
      ? { url: signed.replace('http:', 'https:'), filename: 'a.jpg' }
      : signed.replace('http:', 'https:'));
  }
  for (const url of ['https://example.com/a.jpg', 'http://example.com/a.jpg',
    'http://sample.oss-cn-shanghai.aliyuncs.com.evil.example/a.jpg', '/profile/a.jpg']) {
    const { client, calls } = makeUploader();
    let done;
    client.uploadAll(['a.jpg'], { onDone: value => { done = value; } });
    calls[0].success({ statusCode: 200, data: JSON.stringify({ code: 200, url }) });
    assert.equal(done.results[0], url);
  }
});

function ownerUploadFixture() {
  let owner = '7', authorization = 'A', mapped = 0, aborted = 0;
  const calls = [], timers = [], done = [];
  const client = createUploadClient({
    getBaseUrl: () => 'https://fixture', getAuthorization: () => authorization, getUserID: () => owner,
    wxUploadFile: value => { calls.push(value); return { abort() { aborted++; value.fail({ errMsg: 'abort' }); } }; },
    setTimer: (fn, ms) => { const timer = { fn, ms, cleared: false }; timers.push(timer); return timer; },
    clearTimer: timer => { timer.cleared = true; }
  });
  return { client, calls, timers, done, mapped: () => mapped, aborted: () => aborted,
    switchOwner(id = '9', token = 'B') { owner = id; authorization = token; },
    startPool() { return client.uploadAll(['A-1', 'A-2', 'A-3', 'A-4'], {
      perFileTimeoutMs: 1000, totalDeadlineMs: 5000,
      mapResult: body => { mapped++; return body.url; }, onDone: result => done.push(result) }); },
    complete(index) { calls[index].success({ data: JSON.stringify({ code: 200, url: 'https://fixture/A-' + (index + 1) }) }); }
  };
}
for (const phase of ['success', 'fail', 'watchdog', 'deadline']) {
  test('换号后上传池 ' + phase + ' 不补位、不映射旧结果，取消并清理整批资源', () => {
    const f = ownerUploadFixture(), control = f.startPool();
    try {
      assert.equal(f.calls.length, 3); f.switchOwner();
      if (phase === 'success') f.complete(0);
      else if (phase === 'fail') f.calls[0].fail({ errMsg: 'fixture failure' });
      else f.timers.find(timer => timer.ms === (phase === 'watchdog' ? 1000 : 5000)).fn();
      assert.equal(f.calls.length, 3); assert.equal(f.mapped(), 0); assert.deepEqual(f.done, []);
      assert.equal(f.aborted(), 3); assert.ok(f.timers.every(timer => timer.cleared));
    } finally { control.abort(); }
  });
}
test('同会员token续期仍可补位上传剩余文件且只完成一次', () => {
  const f = ownerUploadFixture(), control = f.startPool();
  try {
    f.switchOwner(7, 'A-new'); f.complete(0);
    assert.equal(f.calls.length, 4); assert.equal(f.calls[3].header.Authorization, 'Bearer A-new');
    for (let i = 1; i < 4; i++) f.complete(i);
    assert.equal(f.done.length, 1); assert.equal(f.done[0].ok, true);
    assert.equal(f.done[0].results.length, 4); assert.ok(f.timers.every(timer => timer.cleared));
  } finally { control.abort(); }
});
for (const phase of ['success', 'fail', 'watchdog']) {
  test('uploadOne换号后旧 ' + phase + ' 不回调新身份', () => {
    const f = ownerUploadFixture();
    const control = f.client.uploadOne('A-voice', { timeoutMs: 1000, onDone: result => f.done.push(result) });
    try {
      f.switchOwner();
      if (phase === 'success') f.calls[0].success({ data: JSON.stringify({ code: 200, data: { safeText: 'A reply' } }) });
      else if (phase === 'fail') f.calls[0].fail({ errMsg: 'fixture failure' });
      else f.timers[0].fn();
      assert.deepEqual(f.done, []); assert.ok(f.timers.every(timer => timer.cleared));
    } finally { control.abort(); }
  });
}
test('uploadOne同会员正常语音响应无需文件URL', () => {
  const f = ownerUploadFixture();
  const control = f.client.uploadOne('A-voice', { onDone: result => f.done.push(result) });
  try {
    f.calls[0].success({ data: JSON.stringify({ code: 200, data: { safeText: 'A reply' } }) });
    assert.equal(f.done.length, 1); assert.equal(f.done[0].ok, true);
    assert.equal(f.done[0].data.data.safeText, 'A reply');
  } finally { control.abort(); }
});

test('formData回调同步换号时不启动上传并清理队列计时器', () => {
  const f = ownerUploadFixture();
  const control = f.client.uploadAll(['A-1', 'A-2'], {
    formData() { f.switchOwner(); return {}; }, onDone: result => f.done.push(result)
  });
  try {
    assert.equal(f.calls.length, 0);
    assert.deepEqual(f.done, []);
    assert.ok(f.timers.every(timer => timer.cleared));
  } finally { control.abort(); }
});

test('pending media never maps a premature URL, retry reads original asset and accepts only real approval', () => {
  const reads = []
  const { client, calls } = makeUploader({ getUserID: () => '9', sendRequest: options => reads.push(options) })
  let first
  client.uploadAll(['voice.mp3'], { onDone: value => { first = value } })
  calls[0].success({ data: JSON.stringify({ code: 200, assetId: 12, reviewState: 'HOLD', url: 'premature-public' }) })
  assert.equal(first.ok, false)
  assert.equal(first.failures[0].status, 'review_pending')
  assert.deepEqual(first.results, [])
  let second
  client.uploadAll(['voice.mp3'], { onDone: value => { second = value } })
  assert.equal(calls.length, 1, 'retry must not upload a second copy')
  assert.equal(reads[0].url, '/api/media/asset/12')
  reads[0].success({ code: 200, data: { assetId: 12, reviewState: 'APPROVED', url: 'approved-real-url' } })
  assert.equal(second.ok, true)
  assert.deepEqual(second.results, ['approved-real-url'])
})
test('rejected original media can resubmit its owner version without publishing rejection reason URL', () => {
  const reads = []
  const { client, calls } = makeUploader({ getUserID: () => '9', sendRequest: options => reads.push(options) })
  client.uploadAll(['model.glb'])
  calls[0].success({ data: JSON.stringify({ code: 200, assetId: 13, reviewState: 'HOLD' }) })
  let done
  client.uploadAll(['model.glb'], { onDone: value => { done = value } })
  reads[0].success({ code: 200, data: { assetId: 13, reviewState: 'REJECTED', reviewReason: 'risk' } })
  assert.equal(reads[1].url, '/api/media/asset/13/retry')
  assert.equal(reads[1].method, 'POST')
  reads[1].success({ code: 200, data: { assetId: 13, reviewState: 'HOLD' } })
  assert.equal(done.ok, false)
  assert.equal(done.failures[0].assetId, '13')
  assert.equal(calls.length, 1)
})
