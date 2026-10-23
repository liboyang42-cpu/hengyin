const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const config = require('../../pages/publish/utils/publish/advanced-game-config');
const tools = require('../../pages/publish/utils/publish/template-tools');
const fields = require('../../pages/publish/utils/publish/template-author-fields');
const preview = require('../../pages/publish/utils/publish/advanced-game-preview');
const requests = [];
global.getApp = () => ({ globalData: {}, getAuthorization: () => '', sendRequest: request => requests.push(request) });
global.wx = { getStorageSync() {}, showToast() {}, getBackgroundAudioManager: () => ({ stop() {} }) };
let definition;
global.Page = value => { definition = value; };
require('../../pages/publish/temp/index');
function page(raw) {
  const p = { ...definition, data: JSON.parse(JSON.stringify(definition.data)) };
  p.setData = function (patch, cb) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts.at(-1)] = value;
    }
    if (cb) cb();
  };
  p.fillFormWithTemplateData({ title: '寒冬多段线索', advancedConfigJson: raw || '' });
  return p;
}
function save(p) {
  requests.length = 0; p.saveDraft();
  assert.equal(requests.length, 1, JSON.stringify(p.data.errors));
  assert.equal(requests[0].url, '/api/template/draft');
  const payload = JSON.parse(requests[0].data);
  assert.equal(payload.status, 0);
  requests[0].success({ code: 200, data: { id: 81 } });
  return payload;
}
function fourSegments() {
  const a = config.defaultConfig();
  a.qa.enabled = true; a.qa.title = '下一步确认什么'; a.qa.answerText = '授权';
  for (const section of ['sort', 'match', 'classify']) { a[section].enabled = true; a[section].prompt = '确认' + section; }
  a.sort.items.forEach((row, i) => { row.label = '时间' + i; });
  for (const side of ['left', 'right']) a.match[side].forEach((row, i) => { row.label = side + i; });
  a.classify.items.forEach((row, i) => { row.label = '记录' + i; });
  a.classify.bins.forEach((row, i) => { row.label = '来源' + i; });
  return a;
}

test('四段模板经真实草稿请求、详情回填及复制再存保留全部题干与答案，预览逐段', () => {
  const original = config.parse(config.serialize(fourSegments())).value;
  const payload = save(page(config.serialize(original)));
  const reopened = page(payload.advancedConfigJson);
  for (const section of ['qa', 'sort', 'match', 'classify']) {
    assert.equal(reopened.data.advanced[section].enabled, true);
    if (section !== reopened.data.gameSection) assert.equal(reopened.data.segmentOn[section], true);
    assert.deepEqual(reopened.data.advanced[section], original[section]);
  }
  const kit = preview.buildPreviewKit(reopened.data.advanced);
  assert.deepEqual([kit.type, ...kit.queue.map(row => row.type)], ['qa', 'sort', 'match', 'classify']);
  const copy = fields.copyPayload({ ...payload, id: 81 }, '');
  assert.equal(Object.hasOwn(copy, 'id'), false);
  const copied = page(copy.advancedConfigJson);
  const resaved = config.parse(save(copied).advancedConfigJson).value;
  for (const section of ['qa', 'sort', 'match', 'classify']) assert.deepEqual(resaved[section], original[section]);
});

test('线索卡名称与本队或公开范围经工具确认、草稿、重开和复制保留', () => {
  const p = page(config.serialize(fourSegments()));
  p.openToolSheet({ currentTarget: { dataset: { key: 'clue' } } });
  p.updateClueRow({ currentTarget: { dataset: { index: 0, field: 'name' } }, detail: { value: 'EV01 原始采样' } });
  p.addClueRow();
  p.updateClueRow({ currentTarget: { dataset: { index: 1, field: 'name' } }, detail: { value: 'EV04 维护规程' } });
  p.updateClueRow({ currentTarget: { dataset: { index: 1, field: 'public' } }, detail: { value: true } });
  assert.equal(p.saveTool(), true);
  const payload = save(p);
  for (const raw of [payload.advancedConfigJson, fields.copyPayload(payload).advancedConfigJson]) {
    const reopened = page(raw);
    reopened.openToolSheet({ currentTarget: { dataset: { key: 'clue' } } });
    assert.deepEqual(reopened.data.toolDraft.rows.map(row => [row.name, row.public]), [['EV01 原始采样', false], ['EV04 维护规程', true]]);
    const rules = reopened.data.advanced.interactionRules;
    assert.equal(rules.effects[0].var, tools.clueKey('EV01 原始采样'));
    assert.equal(rules.worldEffects[0].var, tools.clueKey('EV04 维护规程'));
  }
});

test('效果对象须已到达开关经保存回填保留，关闭或移除次数工具清理字段并保留线索与所有段', () => {
  const a = fourSegments();
  const clue = tools.apply('clue', { rows: [{ name: 'EV04 维护规程', public: true }] }, a).value;
  const p = page(config.serialize(clue));
  p.openToolSheet({ currentTarget: { dataset: { key: 'uses' } } });
  for (const [field, value] of [['limit', '1'], ['requireArrival', true]]) p.updateToolField({ currentTarget: { dataset: { field } }, detail: { value } });
  assert.equal(p.saveTool(), true);
  const reopened = page(save(p).advancedConfigJson);
  reopened.openToolSheet({ currentTarget: { dataset: { key: 'uses' } } });
  assert.equal(reopened.data.toolDraft.requireArrival, true);
  reopened.updateToolField({ currentTarget: { dataset: { field: 'requireArrival' } }, detail: { value: false } });
  assert.equal(reopened.saveTool(), true);
  assert.equal(Object.hasOwn(config.parse(save(reopened).advancedConfigJson).value.interactionRules, 'requireArrival'), false);
  const removed = page(config.serialize(p.data.advanced));
  removed.removeTool({ currentTarget: { dataset: { key: 'uses' } } });
  const result = config.parse(save(removed).advancedConfigJson).value;
  for (const key of ['limit', 'scope', 'requireArrival']) assert.equal(Object.hasOwn(result.interactionRules, key), false);
  assert.deepEqual(result.interactionRules.worldEffects, clue.interactionRules.worldEffects);
  for (const section of ['qa', 'sort', 'match', 'classify']) assert.equal(result[section].enabled, true);
});

test('模板工具不提供主题交换许可和表决方式入口，旧模板字段经再存兼容保留', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
  assert.equal(/data-field="votePolicy"/.test(wxml), false);
  const fresh = page();
  assert.equal(fresh.data.toolDefs.some(row => row.key === 'trade'), false);
  fresh.openToolSheet({ currentTarget: { dataset: { key: 'trade' } } });
  assert.equal(fresh.data.toolSheetOpen, false);
  const a = fourSegments();
  a.interactionTools = { allowTrade: true, votePolicy: 'UNANIMOUS', plans: [{ title: '继续', materialCost: [] }] };
  const p = page(JSON.stringify(a));
  const saved = config.parse(save(p).advancedConfigJson).value;
  assert.equal(saved.interactionTools.allowTrade, true);
  assert.equal(saved.interactionTools.votePolicy, 'UNANIMOUS');
});
