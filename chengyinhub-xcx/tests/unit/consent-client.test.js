const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createConsentClient } = require('../../utils/compliance/consent-client.js');

test('同意请求生成幂等标识并提交到合规接口', async () => {
  let request;
  const client = createConsentClient({
    now: () => 1720000000000,
    random: () => 'abc123',
    sendRequest: (options) => {
      request = options;
      options.success({ code: 200, data: Object.assign(JSON.parse(options.data), { docVersion: 'v2.1' }) });
    },
  });

  await client.record({ docType: 'privacy_policy', scene: 'app_launch', eventType: 'AGREE' });

  assert.equal(request.url, '/api/compliance/consents');
  assert.equal(request.method, 'POST');
  // 后端是 @RequestBody:必须显式 JSON header + 手动 stringify,否则 request-client 默认发 urlencoded 被后端拒收
  assert.equal(request.header['Content-Type'], 'application/json');
  assert.equal(typeof request.data, 'string');
  const body = JSON.parse(request.data);
  assert.equal(body.docType, 'privacy_policy');
  assert.equal(body.scene, 'app_launch');
  assert.equal(body.eventType, 'AGREE');
  assert.equal(body.requestId, 'consent-1720000000000-abc123');
});

// sendRequest 一个回调都不回(隐私授权闸把请求吞了)时,Promise 必须自己 settle ——
// 否则 privacy-gate / deregister / 漫游出发这些把 UX 挂在它 resolve 上的调用方全部静默挂死。
test('sendRequest 永不回调时按超时 reject，不会挂死调用方', async () => {
  const client = createConsentClient({
    settleTimeoutMs: 5,
    sendRequest: () => {},   // 既不 success 也不 fail,模拟被隐私闸吞掉
  });

  await assert.rejects(
    client.record({ docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE' }),
    /同意记录超时/
  );
});

// 超时兜底不能反过来把已经成功的请求也判超时,也不能 settle 两次。
test('正常回调早于超时时，超时兜底不改变结果', async () => {
  const client = createConsentClient({
    settleTimeoutMs: 5,
    sendRequest: (options) => setTimeout(() => options.success({ code: 200, data: options.url.endsWith('/latest') ? null : Object.assign(JSON.parse(options.data), { docVersion: 'v2.1' }) }), 1),
  });

  const result = await client.record({ docType: 'privacy_policy', scene: 'roam_location', eventType: 'AGREE' });
  assert.match(result.requestId, /^consent-/);
  await new Promise((r) => setTimeout(r, 12));  // 跨过超时点,确认没有第二次 settle 抛未捕获拒绝
});

test('拒绝不支持的同意事件，且不发请求', async () => {
  const client = createConsentClient({
    sendRequest: () => assert.fail('不应发送请求'),
  });

  await assert.rejects(
    client.record({ docType: 'privacy_policy', scene: 'app_launch', eventType: 'UNKNOWN' }),
    /同意事件不合法/
  );
});


test('漫游同意先读取实际版本，超时重试保持同一请求和旧CAS基线', async () => {
  const persisted = new Map(); let writes = []; let latestReads = 0;
  const client = createConsentClient({ settleTimeoutMs: 5, getUserID: () => 9,
    getPending: key => persisted.get(key), setPending: (key, value) => value ? persisted.set(key,value) : persisted.delete(key),
    sendRequest(options) {
      if (options.url.endsWith('/latest')) { latestReads++; options.success({code:200,data:{docType:'privacy_policy',scene:'roam_location',eventType:'AGREE',requestId:'initial'}}); return; }
      writes.push(JSON.parse(options.data));
    }
  });
  const input = {docType:'privacy_policy',scene:'roam_location',eventType:'AGREE'};
  await assert.rejects(client.record(input), /超时/);
  await assert.rejects(client.record(input), /超时/);
  assert.equal(latestReads,1); assert.equal(writes[0].requestId,writes[1].requestId);
  assert.equal(writes[1].expectedRequestId,'initial');
});

test('旧同意响应晚于本机撤回不能重新启动定位，下一次明确同意读取撤回基线', async () => {
  let late; let row = null; let nonce = 0; const requests = [];
  const client = createConsentClient({getUserID:()=>9,random:()=>String(++nonce),sendRequest(options) {
    const data=JSON.parse(options.data);requests.push(data);
    if(options.url.endsWith('/latest')) {options.success({code:200,data:row});return;}
    if(data.eventType==='AGREE' && !late) {late=()=>options.success({code:200,data:{...data,docVersion:'v2.1'}});return;}
    row={...data,docVersion:'v2.1'}; options.success({code:200,data:row});
  }});
  const old=client.record({docType:'privacy_policy',scene:'roam_location',eventType:'AGREE'});
  await client.record({docType:'privacy_policy',scene:'roam_location',eventType:'REVOKE'});
  late(); await assert.rejects(old,/同意记录失败/);
  const revokeId=row.requestId;
  await client.record({docType:'privacy_policy',scene:'roam_location',eventType:'AGREE'});
  assert.equal(requests.at(-1).expectedRequestId,revokeId);
});

test('CAS冲突只拒绝并清旧重试，不自动再次同意；换号前置回包不发写请求', async () => {
  let writes=0; const client=createConsentClient({sendRequest(options) {
    if(options.url.endsWith('/latest'))options.success({code:200,data:null});
    else {writes++;options.success({code:409,msg:'状态已变化'});}
  }});
  await assert.rejects(client.record({docType:'privacy_policy',scene:'roam_location',eventType:'AGREE'}),/状态已变化/);
  assert.equal(writes,1);
  let owner=9, callback; const switched=createConsentClient({getUserID:()=>owner,sendRequest(options){callback=options.success;}});
  const pending=switched.record({docType:'privacy_policy',scene:'roam_location',eventType:'AGREE'});
  owner=10;callback({code:200,data:null});await assert.rejects(pending,/账号已变化/);
});


function locationInput(scene, eventType = 'AGREE') {
  return { docType: 'privacy_policy', scene, eventType };
}
function locationRow(scene, requestId, eventType = 'AGREE') {
  return { ...locationInput(scene, eventType), requestId, docVersion: 'v2.1' };
}
function succeedWrite(request) {
  request.success({ code: 200, data: { ...JSON.parse(request.data), docVersion: 'v2.1' } });
}

test('首页同意先读取基线，旧同意响应不能越过本机撤回', async () => {
  const writes = []; let row = null; let nonce = 0;
  const client = createConsentClient({ getUserID: () => 9, random: () => String(++nonce),
    sendRequest(request) {
      if (request.url.endsWith('/latest')) { request.success({ code: 200, data: row }); return; }
      writes.push(request);
      const data = JSON.parse(request.data);
      if (data.eventType === 'REVOKE' || writes.length > 2) {
        row = { ...data, docVersion: 'v2.1' }; succeedWrite(request);
      }
    } });
  const old = client.record(locationInput('home_city_location'));
  assert.equal(JSON.parse(writes[0].data).expectedRequestId, '');
  await client.record(locationInput('home_city_location', 'REVOKE'));
  succeedWrite(writes[0]);
  await assert.rejects(old, /同意记录失败/);
  const revokeId = row.requestId;
  await client.record(locationInput('home_city_location'));
  assert.equal(JSON.parse(writes.at(-1).data).expectedRequestId, revokeId);
});

test('首页撤回不清漫游pending或意图，两个用途同时同意互不覆盖', async () => {
  const requests = []; const persisted = new Map();
  const client = createConsentClient({ getUserID: () => 9,
    getPending: key => persisted.get(key),
    setPending: (key, value) => value ? persisted.set(key, value) : persisted.delete(key),
    sendRequest: request => requests.push(request) });
  const roam = client.record(locationInput('roam_location'));
  const home = client.record(locationInput('home_city_location'));
  assert.equal(requests.length, 2);
  requests[0].success({ code: 200, data: null });
  requests[1].success({ code: 200, data: null });
  const roamWrite = requests[2]; const homeWrite = requests[3];
  const roamKey = '9|privacy_policy|roam_location|AGREE||';
  assert.ok(persisted.has(roamKey));
  const revoke = client.record(locationInput('home_city_location', 'REVOKE'));
  assert.ok(persisted.has(roamKey));
  succeedWrite(requests[4]); await revoke;
  succeedWrite(roamWrite); await roam;
  succeedWrite(homeWrite); await assert.rejects(home, /同意记录失败/);
});

test('首页未知写跨client恢复保留原请求ID和旧基线，不重新读取或借漫游基线', async () => {
  const persisted = new Map(); const writes = []; const reads = [];
  const options = { getUserID: () => 9, settleTimeoutMs: 5,
    getPending: key => persisted.get(key),
    setPending: (key, value) => value ? persisted.set(key, value) : persisted.delete(key),
    sendRequest(request) {
      const body = JSON.parse(request.data);
      if (request.url.endsWith('/latest')) {
        reads.push(body.scene);
        request.success({ code: 200, data: locationRow(body.scene, body.scene + '-before') });
      } else writes.push(body);
    } };
  await assert.rejects(createConsentClient(options).record(locationInput('home_city_location')), /超时/);
  await assert.rejects(createConsentClient(options).record(locationInput('roam_location')), /超时/);
  await assert.rejects(createConsentClient(options).record(locationInput('home_city_location')), /超时/);
  assert.deepEqual(reads, ['home_city_location', 'roam_location']);
  assert.equal(writes[0].requestId, writes[2].requestId);
  assert.equal(writes[2].expectedRequestId, 'home_city_location-before');
  assert.equal(writes[1].expectedRequestId, 'roam_location-before');
});

test('首页409只清本用途旧pending，下一次明确同意读取新基线', async () => {
  const persisted = new Map([['9|privacy_policy|roam_location|AGREE||',
    { requestId: 'roam-unknown', expectedRequestId: 'roam-before' }]]);
  const writes = []; let nonce = 0;
  const client = createConsentClient({ getUserID: () => 9, random: () => String(++nonce),
    getPending: key => persisted.get(key),
    setPending: (key, value) => value ? persisted.set(key, value) : persisted.delete(key),
    sendRequest(request) {
      if (request.url.endsWith('/latest')) {
        request.success({ code: 200, data: locationRow('home_city_location', 'latest-revoke', 'REVOKE') });
      } else {
        writes.push(JSON.parse(request.data));
        if (writes.length === 1) request.success({ code: 409, msg: '状态已变化' });
        else succeedWrite(request);
      }
    } });
  await assert.rejects(client.record(locationInput('home_city_location')), /状态已变化/);
  assert.equal(persisted.has('9|privacy_policy|home_city_location|AGREE||'), false);
  assert.equal(persisted.get('9|privacy_policy|roam_location|AGREE||').requestId, 'roam-unknown');
  await client.record(locationInput('home_city_location'));
  assert.notEqual(writes[0].requestId, writes[1].requestId);
  assert.equal(writes[1].expectedRequestId, 'latest-revoke');
});

test('首页旧预检迟到不能越过撤回发出同意写入', async () => {
  const requests = [];
  const client = createConsentClient({ getUserID: () => 9,
    sendRequest: request => requests.push(request) });
  const old = client.record(locationInput('home_city_location'));
  assert.ok(requests[0].url.endsWith('/latest'));
  const revoke = client.record(locationInput('home_city_location', 'REVOKE'));
  succeedWrite(requests[1]); await revoke;
  requests[0].success({ code: 200, data: null });
  await assert.rejects(old, /账号已变化/);
  assert.equal(requests.length, 2);
});

test('首页预检基线缺少文档版本时不能写入', async () => {
  let writes = 0;
  const invalidBaseline = createConsentClient({ getUserID: () => 9, sendRequest(request) {
    if (request.url.endsWith('/latest')) request.success({ code: 200,
      data: { ...locationRow('home_city_location', 'old'), docVersion: '' } });
    else { writes++; succeedWrite(request); }
  } });
  await assert.rejects(invalidBaseline.record(locationInput('home_city_location')), /状态读取失败/);
  assert.equal(writes, 0);
});

test('首页旧版本写回执不能授权定位', async () => {
  const invalidWrite = createConsentClient({ getUserID: () => 9, sendRequest(request) {
    if (request.url.endsWith('/latest')) request.success({ code: 200, data: null });
    else request.success({ code: 200, data: { ...JSON.parse(request.data), docVersion: 'v1.0' } });
  } });
  await assert.rejects(invalidWrite.record(locationInput('home_city_location')), /同意记录失败/);
});

test('首页旧409迟到不能清除撤回后新同意的未知请求', async () => {
  const requests = []; const persisted = new Map(); let nonce = 0;
  const client = createConsentClient({ getUserID: () => 9, random: () => String(++nonce),
    getPending: key => persisted.get(key),
    setPending: (key, value) => value ? persisted.set(key, value) : persisted.delete(key),
    sendRequest(request) {
      if (request.url.endsWith('/latest')) request.success({ code: 200, data: null });
      else requests.push(request);
    } });
  const old = client.record(locationInput('home_city_location'));
  const revoke = client.record(locationInput('home_city_location', 'REVOKE'));
  succeedWrite(requests[1]); await revoke;
  const fresh = client.record(locationInput('home_city_location'));
  const freshId = JSON.parse(requests[2].data).requestId;
  requests[0].success({ code: 409, msg: '状态已变化' });
  await assert.rejects(old, /状态已变化/);
  const saved = persisted.get('9|privacy_policy|home_city_location|AGREE||');
  assert.equal(saved && saved.requestId, freshId);
  succeedWrite(requests[2]); await fresh;
});


test('首页旧版latest只作CAS基线，新同意必须写回当前版本', async () => {
  let write;
  const client = createConsentClient({ getUserID: () => 9, sendRequest(request) {
    if (request.url.endsWith('/latest')) request.success({ code: 200,
      data: { ...locationRow('home_city_location', 'prior-version'), docVersion: 'v1.0' } });
    else { write = JSON.parse(request.data); succeedWrite(request); }
  } });
  const saved = await client.record(locationInput('home_city_location'));
  assert.equal(write.expectedRequestId, 'prior-version');
  assert.equal(saved.response.data.docVersion, 'v2.1');
});

test('首页意图按账号隔离，切回各自账号后同一用途请求各自完成', async () => {
  let owner = 9; const requests = [];
  const client = createConsentClient({ getUserID: () => owner,
    sendRequest: request => requests.push(request) });
  const first = client.record(locationInput('home_city_location'));
  owner = 10;
  const second = client.record(locationInput('home_city_location'));
  owner = 9;
  requests[0].success({ code: 200, data: null });
  owner = 10;
  requests[1].success({ code: 200, data: null });
  owner = 9;
  succeedWrite(requests[2]); await first;
  owner = 10;
  succeedWrite(requests[3]); await second;
});

test('首页撤回回包丢失时沿用主线真实状态回读，不重放写请求', async () => {
  let writes = 0, reads = 0, receipt
  const client = createConsentClient({ getUserID: () => 9, settleTimeoutMs: 5,
    sendRequest(request) {
      if (request.url.endsWith('/latest')) {
        reads++
        request.success({ code: 200, data: receipt })
        return
      }
      writes++
      receipt = { ...JSON.parse(request.data), docVersion: 'v2.1' }
      request.fail({ errMsg: 'reply lost' })
    } })
  const result = await client.record(locationInput('home_city_location', 'REVOKE'))
  assert.equal(writes, 1)
  assert.equal(reads, 1)
  assert.equal(result.readback.requestId, receipt.requestId)
  assert.equal(result.readback.scene, 'home_city_location')
})

test('首页撤回未知时，其他用途或其他账号的回读不能冒充当前撤回成功', async () => {
  for (const changed of ['scene', 'owner']) {
    let owner = 9
    const client = createConsentClient({ getUserID: () => owner, settleTimeoutMs: 5,
      sendRequest(request) {
        if (request.url.endsWith('/latest')) {
          if (changed === 'owner') owner = 10
          request.success({ code: 200, data: locationRow(changed === 'scene' ? 'roam_location' : 'home_city_location', 'server-revoke', 'REVOKE') })
          return
        }
        request.fail({ errMsg: 'reply lost' })
      } })
    await assert.rejects(client.record(locationInput('home_city_location', 'REVOKE')), /同意记录失败/)
  }
})
