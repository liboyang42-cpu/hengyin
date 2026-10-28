'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { harness, journal, view, DRAFT, RID, OTHER } = require('./simple-ai-v2-guards.test.js');

test('原1迁移：显式起草先可靠保存 UUID prepareKey，再 prepare，serverRID保存后 execute', () => {
  const h = harness(); h.ready(); const prepare = h.begin();
  const body = JSON.parse(prepare.data);
  assert.match(body.prepareKey, /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  assert.equal(body.requestId, undefined); assert.equal(h.values[journal.key('7')].prepareKey, body.prepareKey);
  prepare.success({ code: 200, data: view('PREPARED', { replayed: false }) });
  const execute = h.take('/execute'); assert.deepEqual(JSON.parse(execute.data), { requestId: RID });
  assert.equal(h.values[journal.key('7')].requestId, RID);
  assert.equal(h.values[journal.key('7')].stage, 'EXECUTE_UNCONFIRMED');
  assert.equal(prepare.header.Authorization, undefined); assert.equal(execute.header.Authorization, undefined);
});
test('原2迁移：prepare回包丢失后只GET原key；找回成功不再execute且不采用旧额度', () => {
  const h = harness(); h.ready(); const prepare = h.begin(); const key = JSON.parse(prepare.data).prepareKey;
  prepare.fail({}); prepare.complete(); assert.equal(h.page.data.aiDraftPending, true);
  h.page.generateAiPlan(); const read = h.take('/request'); assert.deepEqual(read.data, { prepareKey: key });
  h.finish(read, { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
  assert.equal(h.page.data.aiPlan.title, '原草稿'); assert.equal(h.page.data.aiRemaining, 3);
  assert.equal(h.values[journal.key('7')].state, 'SUCCEEDED');
  assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  assert.ok(h.take('/quota'));
});
test('原3迁移：额度变化不是执行证据，网络失败仍保留原RID且下一次只GET', () => {
  const h = harness(); h.ready(); const execute = h.prepare(); execute.fail({}); execute.complete();
  h.page.loadAiQuota(); h.finish(h.take('/quota'), { code: 200, data: { limited: true, remaining: 2 } });
  assert.equal(h.page.data.aiPlan, null); assert.equal(h.page.data.aiDraftPending, true);
  h.page.generateAiPlan(); assert.deepEqual(h.take('/request').data, { requestId: RID });
});
test('原4迁移：PROCESSING和UNKNOWN保持原号；改变灵感不再自动新起', () => {
  const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('PROCESSING') });
  h.page.generateAiPlan(); h.finish(h.take('/request'), { code: 409, data: view('UNKNOWN') });
  const key = h.values[journal.key('7')].prepareKey;
  h.page.setData({ aiIdea: '换灵感' }); h.page.generateAiPlan();
  assert.deepEqual(h.take('/request').data, { requestId: RID });
  assert.equal(h.values[journal.key('7')].prepareKey, key);
});
test('原5迁移：关页重开只读恢复，无原输入持久化；明确FAILED保留终态而非删记录', () => {
  const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
  h.page.onUnload(); const reopened = h.createPage();
  const read = h.take('/request'); assert.deepEqual(read.data, { requestId: RID });
  assert.equal(reopened.data.aiIdea, '', '不会恢复持久化raw idea');
  h.finish(read, { code: 200, data: view('FAILED') });
  assert.equal(h.values[journal.key('7')].state, 'FAILED'); assert.equal(reopened.data.aiDraftPending, false);
  assert.equal(h.values[journal.key('7')].requestId, RID);
});
test('原6迁移：generic500不收尾；只有匹配的持久FAILED/SUCCEEDED允许明确新prepare', () => {
  const h = harness(); h.ready(); h.finish(h.prepare(), { code: 500, msg: 'unclassified' });
  assert.equal(h.values[journal.key('7')].state, 'PREPARED');
  h.page.generateAiPlan(); h.finish(h.take('/request'), { code: 200, data: view('FAILED') });
  const old = h.values[journal.key('7')]; h.page.generateAiPlan(); const fresh = h.take('/prepare');
  assert.notEqual(JSON.parse(fresh.data).prepareKey, old.prepareKey);
  assert.equal(h.values[journal.key('7')].stage, 'PREPARE_UNCONFIRMED');
});

// Master W10b additions keep their guards while using the frozen V2 prepare/execute identities.
function pendingRead(h) {
  const execute = h.prepare(); h.finish(execute, {}, 'fail');
  h.page.checkAiDraftRequest(); return h.take('/request');
}
function readResult(state, extra = {}) {
  return { code: 200, data: view(state, extra) };
}
function succeeded(request) {
  request.success(readResult('SUCCEEDED', { resultAvailable: true, result: DRAFT }));
}
function complete(request) { if (request.complete) request.complete(); }

test('过期 NOT_FOUND 保留原 id/pending，不称未送达；同灵感只回读，改灵感不绕过V2明确确认', () => {
  const h = harness(); h.ready(); const read = pendingRead(h);
  const original = JSON.stringify(h.values[journal.key('7')]);
  h.finish(read, readResult('NOT_FOUND'));
  assert.equal(h.page._aiPending.requestId, RID);
  assert.equal(JSON.stringify(h.values[journal.key('7')]), original);
  assert.equal(h.page.data.aiDraftPending, true);
  assert.match(h.page.data.aiError, /结果.*还没确认/);
  assert.doesNotMatch(h.page.data.aiError, /没有送达|重新生成/);
  h.page.generateAiPlan(); const again = h.take('/request');
  assert.deepEqual(again.data, { requestId: RID }); h.finish(again, readResult('NOT_FOUND'));
  h.page.setData({ aiIdea: '新的灵感' }); h.page.generateAiPlan();
  assert.deepEqual(h.take('/request').data, { requestId: RID });
  assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  assert.equal(JSON.stringify(h.values[journal.key('7')]), original);
});

for (const kind of ['提交', '回读']) {
  const start = h => kind === '回读' ? pendingRead(h) : h.prepare();
  test(`${kind}旧回包和 complete 不能覆盖新请求或清新 busy/pending`, () => {
    const h = harness(); h.ready(); const old = start(h);
    h.finish(old, readResult('UNKNOWN'));
    // V2 forbids replacing UNKNOWN just by editing: only a verified terminal read admits the next prepare.
    h.page.checkAiDraftRequest(); h.finish(h.take('/request'), readResult('FAILED'));
    h.page.setData({ aiIdea: '新的灵感' }); const fresh = h.begin();
    const key = JSON.parse(fresh.data).prepareKey;
    succeeded(old); old.fail({}); complete(old);
    assert.equal(h.page.data.aiGenerating, true); assert.equal(h.page.data.aiPlan, null);
    assert.equal(h.page._aiPending.prepareKey, key);
    assert.equal(h.values[journal.key('7')].prepareKey, key);
    h.finish(fresh, readResult('SUCCEEDED', { requestId: OTHER, resultAvailable: true, result: DRAFT }));
    assert.equal(h.page.data.aiGenerating, false);
  });
  test(`${kind}同一请求重复 success/fail 只接受第一次回执`, () => {
    const h = harness(); h.ready(); const request = start(h); succeeded(request);
    const snapshot = JSON.stringify(h.page.data), saved = JSON.stringify(h.values);
    request.fail({}); request.success({ code: 500, msg: '重复失败' });
    assert.equal(JSON.stringify(h.page.data), snapshot); assert.equal(JSON.stringify(h.values), saved);
    complete(request); assert.equal(h.page.data.aiGenerating, false);
  });
  test(`${kind}换号不触碰新账号存储，A→B→A 后仍拒旧回包`, () => {
    const h = harness(); h.ready(); const old = start(h);
    const aPending = JSON.stringify(h.values[journal.key('7')]);
    const bPending = require('./simple-ai-v2-guards.test.js').record({ memberId: '8' });
    h.values[journal.key('8')] = bPending;
    h.switchOwner('8', 'local-token-B'); h.page.onShow();
    succeeded(old); old.fail({}); complete(old);
    assert.equal(JSON.stringify(h.values[journal.key('7')]), aPending);
    assert.deepEqual(h.values[journal.key('8')], bPending);
    h.switchOwner('7', 'local-token-A'); h.page.onShow();
    const current = h.take('/request'); assert.equal(h.page.data.aiGenerating, true);
    succeeded(old); complete(old);
    assert.equal(h.page.data.aiGenerating, true); assert.equal(h.page.data.aiPlan, null);
    h.finish(current, readResult('FAILED')); assert.equal(h.page.data.aiGenerating, false);
  });
  test(`${kind}卸载后所有回调不写页面/存储；重开仅恢复原请求`, () => {
    const h = harness(); h.ready(); const request = start(h); h.page.onUnload();
    const snapshot = JSON.stringify(h.page.data), saved = JSON.stringify(h.values);
    succeeded(request); request.fail({}); complete(request);
    assert.equal(JSON.stringify(h.page.data), snapshot); assert.equal(JSON.stringify(h.values), saved);
    const reopened = h.createPage();
    assert.deepEqual(h.take('/request').data, { requestId: RID });
    assert.equal(reopened.data.aiDraftPending, true);
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  });
}

test('配额仅消费最新请求一次；旧配额不覆盖新起草响应', () => {
  const h = harness(); h.ready(); h.page.loadAiQuota(); const old = h.take('/quota');
  h.page.loadAiQuota(); const fresh = h.take('/quota');
  fresh.success({ code: 200, data: { limited: true, remaining: 2 } });
  fresh.success({ code: 200, data: { limited: true, remaining: 99 } });
  old.success({ code: 200, data: { limited: true, remaining: 98 } });
  assert.equal(h.page.data.aiRemaining, 2);
  h.page.loadAiQuota(); const beforeDraft = h.take('/quota');
  h.finish(h.prepare(), readResult('SUCCEEDED', { replayed: false, resultAvailable: true, result: DRAFT }));
  beforeDraft.success({ code: 200, data: { limited: true, remaining: 3 } }); complete(beforeDraft);
  assert.equal(h.page.data.aiRemaining, 1);
});

test('配额换号往返及卸载后拒绝迟到，complete 不清起草 busy', () => {
  const h = harness(); h.ready(); h.page.loadAiQuota(); const old = h.take('/quota');
  h.switchOwner('8', 'local-token-B'); h.page.onShow();
  h.switchOwner('7', 'local-token-A'); h.page.onShow();
  old.success({ code: 200, data: { limited: true, remaining: 99 } });
  assert.equal(h.page.data.aiRemaining, 3);
  h.finish(h.take('/recovery'), { code: 200, data: { items: [], nextCursor: null } });
  h.page.loadAiQuota(); const quota = h.take('/quota');
  h.prepare(); complete(quota); assert.equal(h.page.data.aiGenerating, true);
  h.page.onUnload(); const snapshot = JSON.stringify(h.page.data);
  quota.success({ code: 200, data: { limited: true, remaining: 98 } });
  assert.equal(JSON.stringify(h.page.data), snapshot);
});

test('在途换号且尚未 onShow，回调也不能写当前账号的 pending key', () => {
  const h = harness(); h.ready(); const post = h.prepare(); const saved = JSON.stringify(h.values);
  h.switchOwner('8', 'local-token-B'); succeeded(post); complete(post);
  assert.equal(JSON.stringify(h.values), saved); assert.equal(h.page.data.aiPlan, null);
});

// Real request-client remains responsible for its one authenticated renewal, never a fake success adapter.
function realTransport(h) {
  const raw = [];
  const { createRequestClient } = require('../../utils/transport/request-client.js');
  const client = createRequestClient({
    wxRequest: request => { raw.push(request); return { abort() {} }; },
    getBaseUrl: () => 'https://example.invalid',
    getUserID: h.app.getUserID, getAuthorization: h.app.getAuthorization,
    reLogin: callback => {
      h.store.setAuthorization('renewed-token');
      const revision = h.store.getRevision();
      callback(true, { isCurrentSession: () => h.store.getRevision() === revision
        && h.app.getUserID() === '7' && h.app.getAuthorization() === 'renewed-token' });
    },
  });
  h.app.sendRequest = request => client.send(request); return raw;
}
function reply(request, data) { request.success({ statusCode: 200, data }); request.complete({}); }
for (const kind of ['提交', '回读', '配额']) {
  test(`${kind}真实 transport 同账号合法续登：新认证头重发，原请求完成且不卡 busy`, () => {
    const h = harness(); h.ready();
    if (kind === '回读') { const execute = h.prepare(); h.finish(execute, {}, 'fail'); }
    const raw = realTransport(h);
    if (kind === '配额') h.page.loadAiQuota();
    else if (kind === '回读') h.page.checkAiDraftRequest();
    else h.page.generateAiPlan();
    raw[0].success({ statusCode: 401, data: { code: 401 } }); raw[0].complete({});
    assert.equal(raw.length, 2); assert.equal(raw[0].header.Authorization, 'local-token-A');
    assert.equal(raw[1].header.Authorization, 'renewed-token');
    assert.deepEqual(raw[1].data, raw[0].data, '原prepareKey/requestId原样重发');
    if (kind !== '配额') assert.equal(h.page.data.aiGenerating, true, '中间401不提前收尾');
    reply(raw[1], kind === '配额' ? { code: 200, data: { limited: true, remaining: 2 } }
      : readResult('SUCCEEDED', { resultAvailable: true, result: DRAFT }));
    assert.equal(h.page.data.aiGenerating, false);
    if (kind !== '配额') {
      assert.equal(h.page.data.aiPlan.title, '原草稿'); assert.equal(h.page.data.aiDraftPending, false);
      assert.equal(h.values[journal.key('7')].state, 'SUCCEEDED');
      assert.equal(h.values[journal.key('7')].requestId, RID, 'V2保留原终态，不删除原记录');
    } else assert.equal(h.page.data.aiRemaining, 2);

    // A successful renewal is not a reusable exemption for a later same-account role revision.
    const changed = harness(); changed.ready();
    if (kind === '回读') { const execute = changed.prepare(); changed.finish(execute, {}, 'fail'); }
    const late = realTransport(changed);
    if (kind === '配额') changed.page.loadAiQuota();
    else if (kind === '回读') changed.page.checkAiDraftRequest();
    else changed.page.generateAiPlan();
    late[0].success({ statusCode: 401, data: { code: 401 } }); late[0].complete({});
    assert.equal(late.length, 2);
    changed.store.setUserRole('merchant');
    const saved = JSON.stringify(changed.values), snapshot = JSON.stringify(changed.page.data);
    reply(late[1], kind === '配额' ? { code: 200, data: { limited: true, remaining: 99 } }
      : readResult('SUCCEEDED', { resultAvailable: true, result: DRAFT }));
    assert.equal(JSON.stringify(changed.values), saved);
    assert.equal(JSON.stringify(changed.page.data), snapshot);
    assert.equal(late.length, 2, '迟到原回包不能自动重发或换号');
  });
  test(`${kind}真实 transport 外部会话变化拒旧；保 pending 并允许重新回读，不锁死`, () => {
    const h = harness(); h.ready();
    if (kind === '回读') { const execute = h.prepare(); h.finish(execute, {}, 'fail'); }
    const raw = realTransport(h);
    if (kind === '配额') h.page.loadAiQuota();
    else if (kind === '回读') h.page.checkAiDraftRequest();
    else h.page.generateAiPlan();
    const saved = JSON.stringify(h.values);
    h.switchOwner('8', 'local-token-B'); h.switchOwner('7', 'external-token-A');
    reply(raw[0], readResult('SUCCEEDED', { resultAvailable: true, result: DRAFT }));
    assert.equal(h.page.data.aiPlan, null); assert.equal(h.page.data.aiGenerating, false);
    assert.equal(JSON.stringify(h.values), saved);
    if (kind === '配额') h.page.loadAiQuota(); else h.page.checkAiDraftRequest();
    assert.equal(raw.length, 2, '外部变化不自动重放，用户仍可查询');
    if (kind !== '配额') {
      const expected = h.page._aiPending.requestId ? { requestId: h.page._aiPending.requestId }
        : { prepareKey: h.page._aiPending.prepareKey };
      assert.deepEqual(raw[1].data, expected);
      assert.equal(raw[1].method, 'GET');
    }
    reply(raw[1], kind === '配额' ? { code: 200, data: { limited: true, remaining: 2 } }
      : readResult('UNKNOWN', { requestId: kind === '提交' ? null : RID,
          reasonCode: kind === '提交' ? 'AI_DRAFT_PREPARE_UNCONFIRMED' : null, contextAvailable: false }));
    assert.equal(h.page.data.aiGenerating, false);
  });
}
