'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const target = require('../../pages/publish/utils/publish/publish-issue-target.js');

const chapters = [{ nodes: [
  { id: 77, templateId: 8, templateInfo: { advancedConfigJson: JSON.stringify({
    interactionRules: { costOptions: [{ hpCost: 1 }, { hpCost: 2 }] },
    interactionTools: { decidesEnding: true, plans: [{ endingKey: 'quiet', title: '安静' }] }
  }) } },
  { id: 88, templateId: 9, templateInfo: { advancedConfigJson: '{}' } },
] }, { nodes: [{ clientNodeKey: 'draft_1', templateId: 9 }] }];

test('后端稳定标识只落到对应资源槽、章节、值域、费用或投票工具', () => {
  const cases = [
    ['节点 77 资源槽 energy：资源未绑定', 'templateBinding:77:energy'],
    ['节点 client:draft_1 资源槽 supply：声明已变化，需要重新确认绑定', 'templateBinding:client:draft_1:supply'],
    ['章节 第1章(9, index=0) 节点 77：任选数量须为1..2', 'chapterRuntime:0'],
    ['zeroChallenge.restoreTo 超出 hp.max', 'themeState'],
    ['方案 quiet 须有对应结局和唯一默认收尾', 'templateTool:77:vote:quiet'],
    ['节点 77：costOptions 须为 2 至 4 组', 'templateTool:77:cost'],
    ['costOptions 须为 2 至 4 组', 'templateTool:77:cost'],
  ];
  cases.forEach(([message, field]) => assert.equal(target.fromMessage(message, chapters).field, field, message));
  assert.equal(target.fromMessage('节点 99 资源槽 energy：资源未绑定', chapters), null);
  assert.equal(target.fromMessage('方案 unknown 须有对应结局和唯一默认收尾', chapters), null);
});

test('模板参数错误指向玩法字段，费用和方案本地检查覆盖服务端口径', () => {
  assert.deepEqual(target.templateField('rewardRulesJson 不合法'), { field: 'reward' });
  assert.deepEqual(target.templateField('请先切换为非传感器验证方式再清空'), { field: 'finish' });
  assert.deepEqual(target.templateField('votePolicy 不支持'), { field: 'advanced', tool: 'vote' });
  assert.deepEqual(target.templateField('costOptions 组数不合法'), { field: 'advanced', tool: 'cost' });
  assert.match(target.costIssue({ interactionRules: { costOptions: [{ hpCost: 1 }] } }), /2 至 4/);
  assert.match(target.costIssue({ interactionRules: { hpCost: 1, costOptions: [{ hpCost: 1 }, { hpCost: 2 }] } }), /不能同时/);
  assert.match(target.costIssue({ interactionRules: { costOptions: [{ hpCost: 1 },
    { resourceCosts: [{ itemId: 'supply', quantity: 0 }] }] } }), /数量不合法/);
  assert.match(target.planIssue({ gameplayRuntime: { plans: [{ endingKey: 'quiet', terminal: true }] }, endings: [] }), /quiet/);
  assert.equal(target.planIssue({ gameplayRuntime: { plans: [{ endingKey: 'quiet', terminal: true }] },
    endings: [{ id: 'quiet' }, { id: 'fallback', fallback: true }] }), '');
});

let fabu;
global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: () => {}, getUserID: () => 42, getNickname: () => '测试作者', getAvatar: () => '', getUserInfo: () => null,
  getToken: () => '' });
global.wx = { getStorageSync: () => '', setStorageSync: () => {}, removeStorageSync: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375 }), showToast: () => {}, showLoading: () => {}, hideLoading: () => {},
  showModal: () => {}, navigateTo: () => {}, pageScrollTo: () => {}, nextTick: f => f() };
global.Page = config => { fabu = config; };
require('../../pages/publish/fabu/index.js');

function page() {
  const p = Object.assign({}, fabu);
  p.data = JSON.parse(JSON.stringify(fabu.data));
  p.data.formData.chapters = chapters;
  p.data.formData.themeRuntimeConfig = '';
  p.setData = (patch, done) => {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.'); let at = p.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts.at(-1)] = value;
    });
    if (done) done();
  };
  p.setEditorState = state => { p._state = state; };
  return p;
}

test('定位打开指定节点资源行、章节设置和投票工具', () => {
  const p = page();
  let opened; let url;
  p.showEditNodes = e => { opened = e.currentTarget.dataset;
    p.data.nodesForm = chapters[0].nodes[e.currentTarget.dataset.nodeindex];
    p.data.nodeBindingRows = [{ key: 'energy' }]; };
  p.showEditChapter = e => { opened = e.currentTarget.dataset; };
  wx.navigateTo = e => { url = e.url; };
  p.locatePublishIssue({ currentTarget: { dataset: { field: 'templateBinding:77:energy', label: '资源未绑定' } } });
  assert.deepEqual(opened, { chapterindex: 0, nodeindex: 0 });
  assert.equal(p.data.nodeBindingErrorKey, 'energy');
  p.locatePublishIssue({ currentTarget: { dataset: { field: 'chapterRuntime:0' } } });
  assert.deepEqual(opened, { index: 0 });
  p._syncRuntimeStats = () => {};
  p._scrollSheetTo = anchor => { opened = anchor; };
  p.locatePublishIssue({ currentTarget: { dataset: { field: 'themeState' } } });
  assert.equal(opened, 'fieldRuntimeState');
  assert.equal(p.data.runtimeEditingStat, 'hp');
  p.locatePublishIssue({ currentTarget: { dataset: { field: 'templateTool:77:vote:quiet', label: '结局未配' } } });
  assert.match(url, /focusTool=vote.*focusPlan=quiet.*focusMessage=/);
});

test('后端拒绝发布显示可定位错误且不清本地草稿', () => {
  const p = page();
  p._draftEnvelope = { marker: '未提交内容' };
  let cleared = false;
  p._clearDraftEnvelope = () => { cleared = true; };
  p.buildPublishCheck = () => ({ blocking: [], advisory: [] });
  p._buildPublishSummary = () => [];
  p._buildPublishPassed = () => [];
  p._buildPublishPreview = () => ({});
  let actions;
  p.getPublishWorkflow = () => ({ submit: (_, callbacks) => { actions = callbacks; return true; } });
  p.sendData({});
  actions.onFail({ msg: '节点 77 资源槽 energy：资源未绑定' });
  assert.equal(p.data.publishCheck.blocking[0].field, 'templateBinding:77:energy');
  assert.equal(p.data.submitError, '节点 77 资源槽 energy：资源未绑定');
  assert.equal(p._draftEnvelope.marker, '未提交内容');
  assert.equal(cleared, false);
});

test('本地发布检查覆盖章节完成、归零挑战、方案结局和费用组选项', () => {
  const p = page();
  p.data.formData.themeRuntimeConfig = JSON.stringify({ chapters: [{ index: 0, completion: { type: 'ANY_N', quantity: 99 } }],
    endings: [] });
  p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson = JSON.stringify({
    interactionRules: { costOptions: [{ hpCost: 1 }] },
    interactionTools: { decidesEnding: true, plans: [{ endingKey: 'quiet' }] }
  });
  p._buildValidationBag = () => ({ errors: {} });
  p.computeCompleteness = () => ({ missing: [] });
  p._chapterRuleIssue = () => '任选数量须在本章站点数内';
  p._validateRuntimeConfig = () => 'zeroChallenge.restoreTo 超出上限';
  const fields = p.buildPublishCheck().blocking.map(item => item.field);
  for (const field of ['chapterRuntime:0', 'themeState', 'templateTool:77:vote:quiet', 'templateTool:77:cost'])
    assert.ok(fields.includes(field), field);
  p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson = JSON.stringify({
    interactionTools: { decidesEnding: true, plans: [{ title: '未配结局' }] }
  });
  assert.ok(p.buildPublishCheck().blocking.some(item => item.field === 'templateTool:77:vote:1'), '旧无方案键时按首个方案序号定位结局选择');
});

test('N011 运行检查报告：问题带跳回定位，D20 规则样例按服务端公式，未覆盖范围永远列出且不宣称全路径', () => {
  const d20 = (dc, modifier) => ({ templateInfo: { advancedConfigJson: JSON.stringify({ diceRoll: { enabled: true, mode: 'd20', dc, modifier } }) } });
  const rows = [{ nodes: [{ id: 5, ...d20(15, 2) }, { id: 6, ...d20(25, 2) }, { id: 7, templateInfo: { advancedConfigJson: JSON.stringify({ interactionRules: { costOptions: [{ hpCost: 1 }] } }) } }] }];
  const broken = target.checkReport({ messages: ['节点 77：costOptions 须为 2 至 4 组', '未知问题'], chapters: rows, version: 3 });
  assert.equal(broken.version, 3); assert.equal(broken.structurePassed, false); assert.equal(broken.fullPathGuarantee, false);
  assert.deepEqual(broken.issues.map(row => row.located), [true, false, true], '无法定位的问题照样列出，不吞掉'); assert.equal(broken.issues[0].target.field, 'templateTool:7:cost');
  assert.equal(broken.issues[2].target.field, 'templateTool:7:cost');
  assert.deepEqual(broken.samples.map(row => [row.nodeKey, row.text, row.warning]), [
    ['5', 'DC 15，静态加值 2：骰出 13 及以上成功', false], ['6', 'DC 25，静态加值 2：任何骰点都失败', true]]);
  assert.equal(broken.samples[0].field, 'templateTool:5:advanced');
  // 修好资源断点后同版本重查：结构通过，但未覆盖项仍在、不宣称全路径
  rows[0].nodes[2].templateInfo.advancedConfigJson = JSON.stringify({ interactionRules: { costOptions: [{ hpCost: 1 }, { hpCost: 2 }] } });
  const fixed = target.checkReport({ messages: [], chapters: rows, version: 3 });
  assert.equal(fixed.structurePassed, true); assert.equal(fixed.fullPathGuarantee, false);
  assert.ok(fixed.uncovered.length >= 3); assert.match(fixed.summary, /不代表全路径可玩/);
});
