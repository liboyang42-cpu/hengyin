'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const config = require('../../utils/publish/config-draft.js');
const { createServerDraftWorkflow, readbackIssue } = require('../../pages/publish/utils/publish/server-draft.js');
const copy = value => JSON.parse(JSON.stringify(value));
const route = () => ({ id: 7, name: '通用路线', status: '1', delFlag: 0, updateTime: '2026-10-13',
  nodes: [{ id: 10, name: '公园', address: '入口', longitude: '121.1', latitude: '31.1', nodeTime: 5, playTemplateId: 77 }] });
const draft = () => ({ name: '', productType: 2, chapters: [], tickets: [], themeRuntimeConfig: '' });
function saved(payload, topicId = 900, version = 1) {
  const runtime = payload.themeRuntimeConfig ? JSON.parse(payload.themeRuntimeConfig) : null;
  if (runtime && runtime.configSource && runtime.configSource.route) runtime.configSource.templateCopies = { 77: 500 };
  return { isDraft: true, tickets: [], collaboratorIds: [], topic: Object.assign({}, payload, { id: topicId, configVersion: version, themeRuntimeConfigJson: JSON.stringify(runtime) }),
    themeRuntimeConfig: JSON.stringify(runtime), chapters: (payload.chapters || []).map((ch, index) => Object.assign({}, ch, {
      id: 1000 + index, cmsTopicNodeList: (ch.nodes || []).map((node, j) => Object.assign({}, node, { id: 2000 + j, chapterId: 1000 + index, templateId: node.sourceLibraryTemplateId ? 500 : node.templateId }))
    })) };
}
test('route selection is a deep independent draft with new local keys and source library IDs kept separate', () => {
  const source = route(), original = draft();
  const first = config.buildConfigDraft(original, source, 'route');
  const second = config.buildConfigDraft(original, source, 'route');
  assert.deepEqual(original, draft());
  assert.equal(first.chapters[0].nodes[0].id, undefined);
  assert.equal(first.chapters[0].nodes[0].templateId, 0);
  assert.equal(first.chapters[0].nodes[0].sourceLibraryTemplateId, 77);
  assert.notEqual(first.chapters[0].nodes[0]._localId, second.chapters[0].nodes[0]._localId);
  source.nodes[0].name = '新版本'; first.chapters[0].nodes[0].name = '独立改动';
  assert.equal(config.sourceOf(first.themeRuntimeConfig).route.nodes[0].name, '公园');
  assert.equal(second.chapters[0].nodes[0].name, '公园');
});
test('malformed, unavailable, duplicate or missing configs never overwrite current content', () => {
  const original = draft();
  for (const bad of [null, { ...route(), status: '0' }, { ...route(), nodes: [] }, { ...route(), nodes: [{ ...route().nodes[0], latitude: 'no' }] }, { ...route(), nodes: [route().nodes[0], route().nodes[0]] }])
    assert.throws(() => config.buildConfigDraft(original, bad, 'route'));
  assert.deepEqual(original, draft());
  assert.throws(() => config.buildConfigDraft({ ...draft(), chapters: [{ nodes: [{ name: '已有站点' }] }] }, route(), 'route'));
});
test('style uses validated theme tokens and falls back to normal theme for malformed historical data', () => {
  const source = { id: 2, name: '纸色', status: '1', toneTokens: '{"--cy-color-text-primary":"#112233"}', defaultTitle: '标题' };
  const next = config.buildConfigDraft(draft(), source, 'style');
  assert.equal(config.styleOf(next.themeRuntimeConfig), '--cy-color-text-primary:#112233;--cy-text-title:#112233');
  assert.equal(next.name, '标题');
  source.toneTokens = '{}';
  assert.equal(config.styleOf(next.themeRuntimeConfig), '--cy-color-text-primary:#112233;--cy-text-title:#112233');
  assert.equal(config.styleOf('{'), '');
  assert.throws(() => config.toneTokens({ '--cy-color-text-primary': 'url(https://example.test)' }));
  assert.throws(() => config.toneTokens({ '--cy-safe-top': '#112233' }));
});
test('world copies existing introduction and freezes task origins without adding reward runtime', () => {
  const source = { world: { id: 3, status: '0', deleted: '0', worldName: '城市世界', intro: '简介' }, chapters: [], quests: [{ id: 4, questName: '任务', targetBizId: 33, targetBizType: 'TOPIC', rewardExp: 12 }] };
  const next = config.buildConfigDraft(draft(), source, 'world');
  assert.equal(next.name, '城市世界'); assert.equal(next.description, '简介');
  source.quests[0].rewardExp = 99;
  assert.equal(config.sourceOf(next.themeRuntimeConfig).world.quests[0].rewardExp, 12);
  assert.equal(JSON.parse(next.themeRuntimeConfig).grants, undefined);
  assert.equal(next.chapters.length, 0);
});
test('semantic readback requires independent IDs, version, order, ownership, bindings and exact frozen source', () => {
  const payload = config.buildConfigDraft(draft(), route(), 'route');
  const detail = saved(payload);
  assert.equal(readbackIssue(payload, detail, 900), '');
  for (const mutate of [d => { d.isDraft = false; }, d => { d.topic.id = 901; }, d => { d.topic.configVersion = null; }, d => { d.chapters[0].cmsTopicNodeList[0].chapterId = 2; }, d => { d.chapters[0].cmsTopicNodeList[0].templateId = 77; }, d => { d.chapters[0].cmsTopicNodeList[0].name = '新版本'; }, d => { d.themeRuntimeConfig = JSON.stringify({ schemaVersion: 1, capacity: 6 }); }]) {
    const bad = copy(detail); mutate(bad); assert.ok(readbackIssue(payload, bad, 900));
  }
});
test('save creates once, reads independently, then saves same topic and increments version', () => {
  const calls = [], receipts = [], results = [];
  const workflow = createServerDraftWorkflow((url, body, json, callback) => calls.push({ url, body, callback }));
  const payload = config.buildConfigDraft(draft(), route(), 'route');
  const handlers = { receipt: id => receipts.push(id), onSuccess: detail => results.push(detail), onFail: msg => assert.fail(msg) };
  assert.equal(workflow.save(payload, handlers), true);
  assert.equal(workflow.save(payload, handlers), false);
  assert.equal(calls[0].body.saveDraft, 1);
  calls[0].callback({ code: 200, data: 900 });
  assert.equal(results.length, 0);
  assert.equal(calls[1].url, '/api/topic/edit-detail');
  calls[1].callback({ code: 200, data: saved(payload) });
  assert.equal(results.length, 1);
  const next = copy(payload); next.configVersion = 1; next.themeRuntimeConfig = results[0].themeRuntimeConfig;
  next.chapters[0].nodes[0].templateId = 500; delete next.chapters[0].nodes[0].sourceLibraryTemplateId;
  workflow.save(next, handlers);
  assert.equal(calls[2].url, '/api/topic/update'); assert.equal(calls[2].body.id, 900);
  calls[2].callback({ code: 200, data: { topicId: 900 } });
  calls[3].callback({ code: 200, data: saved(next, 900, 2) });
  assert.equal(results.length, 2); assert.deepEqual(receipts, [900, 900]);
});
test('lost create blocks duplicate; readback retry reads only; stale and destroyed callbacks are ignored', () => {
  const calls = [], failures = [], successes = [];
  const request = (url, body, json, callback) => calls.push({ url, callback });
  const handlers = { receipt: () => {}, onSuccess: data => successes.push(data), onFail: msg => failures.push(msg) };
  const payload = { name: '新稿', productType: 2, chapters: [], themeRuntimeConfig: '' };
  const unknown = createServerDraftWorkflow(request);
  unknown.save(payload, handlers); calls[0].callback({ code: 0, networkError: true });
  assert.equal(unknown.getState(), 'unknownCreate'); assert.equal(unknown.save(payload, handlers), false);
  const existing = createServerDraftWorkflow(request, 900);
  existing.save(payload, handlers); calls[1].callback({ code: 200, data: 900 });
  calls[2].callback({ code: 500 });
  assert.equal(existing.retryReadback(handlers), true);
  calls[2].callback({ code: 200, data: saved(payload) });
  assert.equal(successes.length, 0);
  assert.equal(calls[3].url, '/api/topic/edit-detail');
  calls[3].callback({ code: 200, data: saved(payload) }); assert.equal(successes.length, 1);
  existing.save(payload, handlers); existing.destroy(); calls[4].callback({ code: 200, data: 900 });
  assert.equal(calls.length, 5);
});

test('generic 500 and malformed create receipts remain unknown instead of allowing another create', () => {
  for (const response of [{ code: 500, msg: '服务器错误' }, undefined, { code: 200, data: {} }]) {
    let callback, calls = 0;
    const workflow = createServerDraftWorkflow((url, body, json, cb) => { callback = cb; calls++; });
    const handlers = { receipt: () => assert.fail('no ID'), onSuccess: () => assert.fail('no readback'), onFail: () => {} };
    workflow.save({ name: '新稿', productType: 2 }, handlers); callback(response);
    assert.equal(workflow.getState(), 'unknownCreate');
    assert.equal(workflow.save({ name: '另一次' }, handlers), false); assert.equal(calls, 1);
  }
});

test('readback rejects lost tickets, collaborators, blocks, opening, ending and story while accepting legitimate ID regeneration', () => {
  const payload = { name: '完整新稿', productType: 2, collaboratorIds: [42, 43],
    tickets: [{ name: '票', price: 12.5, totalStock: 20, startTime: '2026-10-13 09:00:00', endTime: '2026-10-15 18:00:00', description: '说明', refundSupported: true }],
    chapters: [
      { id: 11, name: '开局', description: '开场', opening: true, blocks: [{ key: 'a', type: 'text', content: '开场' }], nodes: [] },
      { id: 12, name: '路线', description: '剧情', blocks: [{ key: 'b', type: 'text', content: '剧情' }, { key: 'c', type: 'node', nodeIndex: 0 }], nodes: [{ id: 7, name: '站', nodeTime: 0, templateId: 0 }] },
      { id: 13, name: '收尾', description: '归途', ending: { fallback: true }, blocks: [{ key: 'd', type: 'text', content: '归途' }], nodes: [] }],
    journeyStory: JSON.stringify({ schemaVersion: 1, stages: [{ chapterId: 12, title: '阶段' }], ending: { exhibits: ['留存'] } }),
    journeyRules: JSON.stringify({ schemaVersion: 1, hp: { enabled: true, max: 10 } }), themeRuntimeConfig: '' };
  const detail = saved(payload);
  detail.collaboratorIds = [43, 42];
  detail.tickets = [{ ...payload.tickets[0], id: 700, ownerId: 900, ownerType: 1, mode: 2, totalInventory: 20 }];
  detail.chapters.forEach((chapter, index) => {
    delete chapter.opening; delete chapter.ending;
    chapter.blocks = payload.chapters[index].blocks.map(row => row.type === 'node'
      ? { key: row.key, type: 'node', nodeId: chapter.cmsTopicNodeList[row.nodeIndex].id } : { ...row });
  });
  detail.openingChapterId = 1000;
  detail.journeyStory = JSON.stringify({ schemaVersion: 1, openingChapterId: 1000, stages: [{ chapterId: 1001, title: '阶段' }], ending: { exhibits: ['留存'], endings: [{ code: 'ending1', title: '收尾', summary: '归途', chapterId: 1002, fallback: true }] } });
  detail.journeyRules = payload.journeyRules;
  assert.equal(readbackIssue(payload, detail, 900), '');
  for (const mutate of [d => { d.tickets = []; }, d => { d.tickets[0].price = 99; }, d => { d.tickets[0].totalInventory = 19; },
    d => { d.tickets[0].startTime = '2026-10-14 09:00:00'; }, d => { d.tickets[0].ownerId = 901; }, d => { d.collaboratorIds = [42]; },
    d => { d.chapters[1].blocks = []; }, d => { d.chapters[1].blocks[1].nodeId = 999; }, d => { d.chapters[0].blocks[0].content = '丢失'; },
    d => { d.openingChapterId = 1001; }, d => { const story = JSON.parse(d.journeyStory); story.ending.endings = []; d.journeyStory = JSON.stringify(story); },
    d => { const story = JSON.parse(d.journeyStory); story.stages[0].chapterId = 999; d.journeyStory = JSON.stringify(story); },
    d => { d.journeyRules = '{"schemaVersion":1,"hp":{"enabled":true,"max":5}}'; }]) {
    const lost = copy(detail); mutate(lost); assert.ok(readbackIssue(payload, lost, 900));
  }
});
