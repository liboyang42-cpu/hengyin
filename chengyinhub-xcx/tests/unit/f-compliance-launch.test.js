'use strict';
// f-compliance(2026-11-11):AIGC 标识与备案号、AI 对话记录设置入口、游玩页紧急求助、附近队伍举报。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const json = (p) => JSON.parse(read(p));

test('aigc-label 判据与后端 AigcLabel 一致;备案号只取 features 字符串', () => {
  const label = require('../../utils/aigc-label.js');
  assert.equal(label.isAiGenerated('ai_simple'), true);
  assert.equal(label.isAiGenerated(' AI_SIMPLE '), true);
  for (const v of ['simple', 'pro', '', null, undefined, 1]) assert.equal(label.isAiGenerated(v), false);
  assert.equal(label.filingNo({ globalData: { features: { aigcFilingNo: ' 网信算备110000000000000000001号 ' } } }), '网信算备110000000000000000001号');
  assert.equal(label.filingNo({ globalData: { features: { aigcFilingNo: '' } } }), '');
  assert.equal(label.filingNo({ globalData: { features: {} } }), '');
  assert.equal(label.filingNo({ globalData: { features: { aigcFilingNo: true } } }), '');
  assert.equal(label.filingNo(null), '');
});

test('AI 生成标识:剧情页、游玩开场、快速配置草稿都按服务端事实显示', () => {
  assert.match(read('pages/club/topic-story/index.js'), /aiGenerated: aigcLabel\.isAiGenerated\(res\.data\.publishMode\)/);
  assert.match(read('pages/club/topic-story/index.wxml'), /wx:if="\{\{aiGenerated && loaded && !loadError\}\}"[^>]*><cy-tag[^>]*>AI 生成<\/cy-tag>/);
  assert.equal(json('pages/club/topic-story/index.json').usingComponents['cy-tag'], '/components/cy/tag/index');
  assert.match(read('pages/play/index.js'), /topicAiGenerated: d\.topicAiGenerated === true/);
  assert.match(read('pages/play/index.wxml'), /\{\{topicAiGenerated \? ' · AI 生成' : ''\}\}/);
  const simple = read('pages/publish/simple/index.wxml');
  assert.match(simple, /AI 生成 · \{\{aiPlanNodes\.length\}\} 站/);
  assert.match(simple, /<view wx:if="\{\{aigcFilingNo\}\}" class="simple-ai__filing">\{\{aigcFilingNo\}\}<\/view>/);
});

test('备案号:关于页与 AI 生成入口为空不渲染', () => {
  assert.match(read('pages/shezhi/about/index.wxml'), /<view wx:if="\{\{aigcFilingNo\}\}" class="about-app__version about-app__filing">\{\{aigcFilingNo\}\}<\/view>/);
  assert.match(read('pages/shezhi/about/index.js'), /aigcFilingNo: aigcLabel\.filingNo\(app\)/);
  assert.match(read('pages/publish/simple/index.js'), /aigcLabel\.filingNo\(app\)/);
});

test('设置页「AI 对话记录」:分包组件 + componentPlaceholder,复用 export / delete 接口', () => {
  const wxml = read('pages/shezhi/shezhi.wxml');
  assert.match(wxml, /id="settings-ai-chat-records"[^>]*title="AI 对话记录" bind:tap="openAiChatRecords"/);
  assert.match(wxml, /<cy-ai-chat-records show="\{\{showAiChatRecords\}\}" bind:close="closeAiChatRecords" \/>/);
  const page = json('pages/shezhi/shezhi.json');
  assert.equal(page.usingComponents['cy-ai-chat-records'], '/subpackageMember/components/cy/ai-chat-records/index');
  assert.equal(page.componentPlaceholder['cy-ai-chat-records'], 'view');
});

function loadRecordsComponent(requests, store) {
  let def;
  const dc = { opened: [], busy: 0, doneText: '', failedText: '',
    open(key) { this.opened.push(key); }, busyOn() { this.busy++; }, done(t) { this.doneText = t; }, failed(t) { this.failedText = t; } };
  const clipboard = [];
  const toasts = [];
  const app = { getUserID: () => '42', getRequestErrorMessage: (res, fb) => (res && res.msg) || fb,
    sendRequest: (opts) => requests.push(opts) };
  global.getApp = () => app;
  global.wx = { setClipboardData: (o) => clipboard.push(o.data), showToast: (o) => toasts.push(o.title),
    getStorageSync: (k) => store[k], setStorageSync: (k, v) => { store[k] = v; }, removeStorageSync: (k) => { delete store[k]; } };
  global.getCurrentPages = () => [];
  global.Component = (d) => { def = d; };
  const file = path.join(root, 'subpackageMember/components/cy/ai-chat-records/index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  const events = [];
  const inst = Object.assign({ data: def.data, properties: {}, selectComponent: () => dc,
    triggerEvent: (name) => events.push(name), setData() {} }, def.methods);
  return { inst, dc, clipboard, toasts, events };
}

test('AI 对话记录:导出复制文本;清除走危险确认 + 原删除接口(只带 requestId,身份取登录态)', () => {
  const requests = []; const store = {};
  const h = loadRecordsComponent(requests, store);
  h.inst.onSelect({ detail: { index: 0 } });
  assert.deepEqual(h.events, ['close']);
  assert.equal(requests[0].url, '/api/ai/npc/chat/data/export');
  assert.equal(requests[0].method, 'GET');
  requests[0].success({ code: 200, data: { records: [{ userMsg: '你好', npcReply: '你好呀', createTime: '2026-11-11 10:00:00' }], retentionPolicy: '90 天' } });
  assert.equal(h.clipboard.length, 1);
  assert.match(h.clipboard[0], /你好呀/);

  h.inst.onSelect({ detail: { index: 1 } });
  assert.deepEqual(h.dc.opened, ['ai.npc.chatData.delete']);
  assert.equal(requests.length, 1, '确认前不发删除');
  h.inst.onConfirmClear();
  const del = requests[1];
  assert.equal(del.url, '/api/ai/npc/chat/data/delete');
  assert.equal(del.method, 'POST');
  assert.deepEqual(Object.keys(del.data), ['requestId']);
  del.success({ code: 200, data: { deleted: 3 } });
  assert.equal(h.dc.doneText, '已清除 3 条对话');
});

test('AI 对话记录:空记录不复制,失败给原因', () => {
  const requests = [];
  const h = loadRecordsComponent(requests, {});
  h.inst.exportRecords();
  requests[0].success({ code: 200, data: { records: [] } });
  assert.equal(h.clipboard.length, 0);
  h.inst.exportRecords();
  requests[1].success({ code: 500, msg: '服务繁忙' });
  assert.equal(h.clipboard.length, 0);
});

test('紧急求助:菜单行 → 动作菜单;只拨 110/120;位置复制为文字', () => {
  const sos = require('../../pages/play/utils/play-sos.js');
  assert.deepEqual(sos.SOS_ITEMS, ['拨打 110', '拨打 120', '复制我的位置']);
  const calls = []; const copied = []; const toasts = []; let located = null;
  const wxApi = { makePhoneCall: (o) => calls.push(o.phoneNumber), setClipboardData: (o) => copied.push(o.data),
    getLocation: (o) => { located = o; } };
  const page = Object.assign({ data: { sessionToolsOpen: true }, toggled: 0,
    toggleSessionTools() { this.toggled++; this.data.sessionToolsOpen = false; },
    setData(p) { Object.assign(this.data, p); } }, sos.methods(wxApi, (t) => toasts.push(t)));
  page.openSos();
  assert.equal(page.toggled, 1);
  assert.equal(page.data.sosOpen, true);
  page.onSosSelect({ detail: { index: 0 } });
  page.onSosSelect({ detail: { index: 1 } });
  page.sosCall('10086');
  assert.deepEqual(calls, ['110', '120']);
  assert.equal(page.data.sosOpen, false);
  page._loc = { latitude: 31.2304, longitude: 121.4737 };
  page.onSosSelect({ detail: { index: 2 } });
  assert.match(copied[0], /纬度 31\.2304，经度 121\.4737/);
  assert.match(copied[0], /position=121\.4737,31\.2304/);
  page._loc = null;
  page.sosShareLocation();
  assert.equal(located.type, 'gcj02');
  located.fail();
  assert.deepEqual(toasts, ['没有取到位置']);
  assert.equal(sos.locationText({ latitude: 0, longitude: 0 }), '');
  const wxml = read('pages/play/index.wxml');
  assert.match(wxml, /bindtap="openSos"[^>]*aria-label="紧急求助"/);
  assert.match(wxml, /<cy-option-sheet wx:if="\{\{sosOpen\}\}"[^>]*items="\{\{sosItems\}\}" immediate bind:select="onSosSelect"/);
  assert.doesNotMatch(read('pages/play/utils/play-sos.js'), /showActionSheet\(/);
});

test('附近队伍卡:接上既有举报 sheet(分包异步化),队长卡不出举报', () => {
  const wxml = read('subpackageRoam/nearby/index.wxml');
  assert.match(wxml, /class="mt-report" bindtap="openTeamReport"[^>]*>举报队伍</);
  assert.match(wxml, /<team-report-sheet show="\{\{reportShow\}\}" team-id="\{\{reportTeamId\}\}" bind:close="closeTeamReport" \/>/);
  const page = json('subpackageRoam/nearby/index.json');
  assert.equal(page.usingComponents['team-report-sheet'], '/subpackageMember/components/cy/team-report-sheet/index');
  assert.equal(page.componentPlaceholder['team-report-sheet'], 'view');
  const src = read('subpackageRoam/nearby/index.js');
  const start = src.indexOf('openTeamReport() {');
  const body = src.slice(start, src.indexOf('closeTeamReport()', start));
  let set = null;
  const fn = new Function('return {' + body + '}')().openTeamReport;
  fn.call({ data: { current: { teamId: 9, viewerStatus: 'NONE' } }, setData: (p) => { set = p; } });
  assert.deepEqual(set, { reportTeamId: '9', reportShow: true });
  set = null;
  fn.call({ data: { current: { teamId: 9, viewerStatus: 'LEADER' } }, setData: (p) => { set = p; } });
  assert.equal(set, null);
});
