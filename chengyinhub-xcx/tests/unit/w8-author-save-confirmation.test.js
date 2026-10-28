const test = require('node:test');
const assert = require('node:assert/strict');
const { createServerDraftSaver, readbackIssue } = require('../../pages/publish/utils/publish/server-draft.js');
const draft = { name: '故事', chapters: [] };
const detail = (extra = {}) => ({ isDraft: true, topic: { id: 501, status: 0, name: '故事', ...extra }, chapters: [] });
function saverHarness() {
  const calls = [], outcomes = [];
  const deps = Object.fromEntries(['save', 'readback', 'publish', 'publishCheck'].map(kind =>
    [kind, (arg, cb) => calls.push({ kind, arg, cb })]));
  const saver = createServerDraftSaver(deps);
  const handlers = { onSuccess: r => outcomes.push({ ok: true, ...r }), onFail: r => outcomes.push({ ok: false, ...r }) };
  return { saver, calls, outcomes, handlers };
}

test('UNKNOWN发布读到草稿仍是未知，重进式重试只读，不覆盖也不重发', () => {
  const h = saverHarness();
  h.saver.saveAndPublish(draft, h.handlers);
  h.calls[0].cb({ ok: true, data: 501 }); h.calls[1].cb({ ok: true, data: detail() });
  h.calls[2].cb({ ok: true }); h.calls[3].cb({ ok: false, networkError: true });
  h.calls[4].cb({ ok: true, data: detail() });
  assert.equal(h.outcomes.at(-1).unknown, true);
  h.saver.saveAndPublish({ ...draft, name: '后续本地编辑' }, h.handlers);
  assert.equal(h.calls.at(-1).kind, 'readback');
  h.calls.at(-1).cb({ ok: true, data: detail() });
  assert.equal(h.calls.length, 6, '负向读取后不能保存或再次发布');
  assert.equal(h.outcomes.at(-1).unknown, true);
  h.saver.saveAndPublish(draft, h.handlers);
  h.calls.at(-1).cb({ ok: true, data: { ...detail({ status: 1 }), isDraft: false } });
  assert.equal(h.outcomes.at(-1).ok, true, '正向发布事实可以恢复');
});

test('发布预检重复回调不得重复发布；异常与缺失回执均不放行', () => {
  for (const result of [undefined, {}, { ok: true }]) {
    const h = saverHarness(); h.saver.saveAndPublish(draft, h.handlers);
    h.calls[0].cb({ ok: true, data: 501 }); h.calls[1].cb({ ok: true, data: detail() });
    const check = h.calls[2]; check.cb(result); check.cb({ ok: true });
    assert.equal(h.calls.filter(c => c.kind === 'publish').length, result && result.ok === true ? 1 : 0);
  }
  let failure;
  const saver = createServerDraftSaver({ save: (p, cb) => cb({ ok: true, data: 501 }),
    readback: (id, cb) => cb({ ok: true, data: detail() }), publish: () => assert.fail('预检异常不能发布'),
    publishCheck: () => { throw Error('lost precheck'); } });
  assert.doesNotThrow(() => saver.saveAndPublish(draft, { onFail: r => { failure = r; } }));
  assert.equal(failure.stage, 'publish'); assert.equal(saver.isBusy(), false);
});

for (const field of ['checkEnabled', 'stateEnabled', 'natAuto', 'modCap', 'fumbleExtraDamage',
  'tiers', 'recovery', 'thoughts', 'rpg', 'statLinks']) {
  test('云保存不得忽略作者规则变化：' + field, () => {
    const rules = { schemaVersion: 1, checkEnabled: true, stateEnabled: true, natAuto: false, modCap: 3,
      fumbleExtraDamage: 2, tiers: { hard: { dc: 20, damage: 3 } }, recovery: { exhaustTag: 'tag.tired' },
      thoughts: [{ key: 'clue', name: '线索', need: 10 }], rpg: { enabled: true },
      statLinks: [{ sourceKey: 'counter.food', targetKey: 'HP', threshold: 1 }] };
    const got = structuredClone(rules); delete got[field];
    const sent = { ...draft, journeyRules: JSON.stringify(rules) };
    assert.notEqual(readbackIssue(sent, { ...detail(), journeyRules: JSON.stringify(got) }, true), '');
  });
}

test('属性label/min/visible也必须回读，不能只比较初值/上限', () => {
  const rules = { schemaVersion: 1, attributes: [{ key: 'counter.focus', label: '专注', initial: 2, min: 0, max: 8, visible: false }] };
  for (const field of ['label', 'min', 'visible']) {
    const got = structuredClone(rules); delete got.attributes[0][field];
    assert.notEqual(readbackIssue({ ...draft, journeyRules: JSON.stringify(rules) }, { ...detail(), journeyRules: JSON.stringify(got) }, true), '');
  }
});

test('规则回读允许后端补默认字段和trim，natAuto缺省true，显式false不能丢', () => {
  const sent = { ...draft, journeyRules: JSON.stringify({ schemaVersion: 1, natAuto: true,
    thoughts: [{ key: ' clue ', name: ' 线索 ', need: 10 }] }) };
  const got = { schemaVersion: 1, checkEnabled: false, thoughts: [{ key: 'clue', name: '线索', need: 10 }] };
  assert.equal(readbackIssue(sent, { ...detail(), journeyRules: JSON.stringify(got) }, true), '');
  sent.journeyRules = JSON.stringify({ schemaVersion: 1, natAuto: false });
  assert.notEqual(readbackIssue(sent, { ...detail(), journeyRules: JSON.stringify(got) }, true), '');
});

for (const version of [undefined, 0, -1, 'wrong', 2, 1, 9007199254740992]) {
  test('已提交configVersion=2不能用缺失/旧/非法版本确认云保存：' + version, () => {
    assert.notEqual(readbackIssue({ ...draft, configVersion: 2 }, detail({ configVersion: version }), true), '');
  });
}
test('新建及更新均确认正向版本；旧调用未带版本保持兼容', () => {
  assert.equal(readbackIssue({ ...draft, configVersion: 2 }, detail({ configVersion: 3 }), true), '');
  assert.notEqual(readbackIssue({ ...draft, configVersion: '' }, detail(), true), '');
  assert.equal(readbackIssue({ ...draft, configVersion: '' }, detail({ configVersion: 1 }), true), '');
  assert.equal(readbackIssue(draft, detail(), true), '');
});

for (const field of ['subtitle', 'description', 'imgUrl', 'imgArr', 'startDate', 'endDate', 'categoryIds']) {
  test('封面与顶部设置回读不能只核主题名：' + field, () => {
    const value = field === 'imgArr' ? 'cover-a.jpg,cover-b.jpg' : '作者填写内容';
    const sent = { ...draft, [field]: value };
    assert.notEqual(readbackIssue(sent, detail(), true), '');
    assert.equal(readbackIssue(sent, detail({ [field]: value }), true), '');
    if (field === 'imgArr') assert.notEqual(readbackIssue(sent, detail({ imgArr: 'cover-b.jpg,cover-a.jpg' }), true), '');
  });
}

test('N5云保存按节点位置回读嵌套条件和效果，服务器换ID可确认但丢字段不可确认',()=>{
 const route=require('../../pages/publish/utils/publish/topic-route-graph');
 const g={schemaVersion:1,startNodeId:'a',terminalNodeIds:['b'],variables:{'counter.time':0},fallbacks:[],edges:[
  {id:'choice',fromNodeId:'a',toNodeId:'b',trigger:{type:'CHOICE',outcomeCode:'GO'},conditions:[{op:'AND',conditions:[{op:'VISIT_COUNT',nodeId:'a',value:1}]}],effects:[{op:'GRANT_XP',value:7,id:'read'}]}
 ]};
 const sent={name:'故事',routeMode:'BRANCH_GRAPH',routeGraphJson:route.serializeWithNodeKeys(g),chapters:[{nodes:[{clientNodeKey:'a',name:'起点'},{clientNodeKey:'b',name:'终点'}]}]};
 const got=JSON.parse(route.serialize(g));got.startNodeId='11';got.terminalNodeIds=['12'];got.edges[0].fromNodeId='11';got.edges[0].toNodeId='12';got.edges[0].conditions[0].conditions[0].nodeId='11';
 const back={...detail({routeMode:'BRANCH_GRAPH',routeGraphJson:JSON.stringify(got)}),chapters:[{id:1,topicId:501,cmsTopicNodeList:[{id:11,chapterId:1,topicId:501,name:'起点'},{id:12,chapterId:1,topicId:501,name:'终点'}]}]};
 assert.equal(readbackIssue(sent,back,true),'');
 got.edges[0].effects[0].value=3;back.topic.routeGraphJson=JSON.stringify(got);assert.match(readbackIssue(sent,back,true),/路线/);
 got.edges[0].effects[0].value=7;got.edges[0].conditions=[];back.topic.routeGraphJson=JSON.stringify(got);assert.match(readbackIssue(sent,back,true),/路线/);
});
