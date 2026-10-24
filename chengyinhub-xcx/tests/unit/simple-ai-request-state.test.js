'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { harness, journal, view, DRAFT, RID } = require('./simple-ai-v2-guards.test.js');

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
