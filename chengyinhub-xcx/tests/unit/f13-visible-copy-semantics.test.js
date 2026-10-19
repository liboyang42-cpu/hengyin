'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const journey = require('../../pages/publish/fabu/journey-check-settings.js');
const savedCheck = require('../../pages/publish/fabu/topic-saved-check.js');
const { buildThemeOverview } = require('../../pages/publish/utils/publish/topic-draft-preview.js');
const read = file => fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8');

test('F13 saved-check copy distinguishes online saves, dirty local drafts and publication approval', () => {
  const sheet = read('pages/publish/fabu/topic-detail-sheet.wxml');
  assert.match(sheet, /检查线上已保存内容/);
  assert.match(sheet, /仅查看线上已保存配置的试检查结果。不包含本地未保存修改，不是 AI 安全检查、开局预演或发布批准。正式发布仍会再次校验。/);
  assert.match(sheet, /本次检查未标明对应的保存版本；此结果不保证覆盖之后的保存，也不代表全部发布条件通过。/);
  assert.match(sheet, /本次试检查未报告问题/);
});

test('F13 default saved-check failures retain retry guidance and never become a successful check', () => {
  const cases = [
    [request => request.success({ code: 500 }), '线上已保存内容检查失败，请重试'],
    [request => request.fail({ errMsg: 'offline' }), '线上已保存内容检查未完成，请检查网络后重试'],
    [request => request.complete({ statusCode: 502 }), '线上已保存内容检查未完成，请重试'],
    [null, '线上已保存内容检查未打开，请重试'],
  ];
  for (const [finish, expected] of cases) {
    const calls = [];
    const app = { getUserID: () => 7, sendRequest(request) { if (!finish) throw new Error('offline'); calls.push(request); } };
    const page = Object.assign({ data: Object.assign(JSON.parse(JSON.stringify(savedCheck.data)), {
      editingTopicId: 31, editLoaded: true, editLoading: false, editLoadError: '', draftMemberId: 7,
      draftUuid: 'draft', baseRevision: 'revision', editScope: 'FULL', operationScope: '', topicDetailShow: true,
      formData: { name: 'Unsaved local edit' },
    }), setData(update) { Object.assign(this.data, update); } }, savedCheck.methods(app));
    page._savedCheckOnShow();
    const draft = JSON.stringify(page.data.formData);
    page.openSavedTopicCheck();
    if (finish) finish(calls[0]);
    assert.equal(page.data.savedTopicCheck.error, expected);
    assert.equal(page.data.savedTopicCheck.loaded, false);
    assert.equal(page.data.savedTopicCheck.busy, false);
    assert.equal(JSON.stringify(page.data.formData), draft);
    if (finish) {
      assert.equal(calls[0].method, 'GET');
      assert.deepEqual(calls[0].data, { topicId: '31', scope: '' });
    }
  }
});

test('F13 journey labels retain missing, null, explicit zero and disabled defaults without rewriting values', () => {
  const missing = journey.project('{}');
  assert.equal(missing.enabledOptions[missing.enabledIndex].label, '未配置（系统默认关闭）');
  assert.equal(journey.normalize('{}').checkEnabled, false);
  assert.equal(journey.apply('{}', missing), '{}');
  const raw = '{"schemaVersion":1,"checkEnabled":null,"modCap":null,"fumbleExtraDamage":0}';
  const draft = journey.project(raw);
  assert.equal(draft.enabledOptions[draft.enabledIndex].label, '保留已有 null（系统默认关闭）');
  assert.equal(draft.fields.find(row => row.path === 'modCap').presence, '已有 null，使用系统缺省');
  assert.equal(draft.fields.find(row => row.path === 'fumbleExtraDamage').value, '0');
  assert.equal(journey.normalize(raw).checkEnabled, false);
  assert.equal(journey.apply(raw, draft), raw);
  assert.match(read('pages/publish/fabu/runtime-editor.wxml'), /原有 null 在未编辑时保持原样/);
});

test('F13 rescue and runtime copy leaves authority with the system and no client-side grants', () => {
  const runtime = read('pages/publish/fabu/runtime-editor.wxml');
  const backpack = read('pages/play/theme-backpack.wxml');
  assert.match(runtime, /未配置，系统按 0 次处理/);
  assert.match(runtime, /留空移除这项配置，填写 0 明确表示不提供救援。只有系统认定进入可救援状态时才可用/);
  assert.match(backpack, /救援资格、可用次数和恢复结果按系统当前规则处理；不会重置背包或进度/);
  assert.match(runtime, /玩家提交的选项经系统与本局冻结的分支步骤、选项核对后/);
  assert.match(runtime, /系统取得可信站点完成确认记录后/);
  assert.match(runtime, /本页不发放、不请求领取，也不改变权限/);
  assert.match(runtime, /关闭检定不会跳过系统的配置合法性校验/);
  assert.match(runtime, /资格仍由系统校验/);
});

test('F13 weekly schedule remains read-only and fixed to Monday UTC / Beijing 08:00', () => {
  assert.match(read('pages/publish/fabu/runtime-editor.wxml'), /现有按周配置只读。系统按 UTC 周一切周，即北京时间周一 08:00，不随手机时区变化。下拉选择其他模式才会明确更改轮换方式。/);
});

test('F13 configuration preview stays local, unvalidated and separate from a player inventory', () => {
  assert.match(read('pages/play/theme-backpack.wxml'), /本页仅列出打开预览时的配置，未经系统规则校验。不是玩家背包，不会发放物品、扣减数值、触发广播或计算结局。完整故事与结局仍在原预览中。/);
  const result = buildThemeOverview({ themeRuntimeConfig: '{invalid' });
  assert.equal(result.kind, 'CONFIG');
  assert.equal(result.rows[0].detail, '请返回编辑器检查主题配置。故事预览仍可继续；这里没有运行系统校验。');
});
