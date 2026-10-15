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
