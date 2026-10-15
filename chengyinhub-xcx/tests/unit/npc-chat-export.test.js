'use strict';

// CL4 / N5:cy-npc-chat 「导出我的对话记录」。
// 读 GET /api/ai/npc/chat/data/export(NpcChatDataExportVO),只在内存里拼文本,用户自己选复制文本或完整 JSON;
// 不写任何本地存储;畸形回包落错误态,不伪装成「没有记录」。

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const exp = require('../../components/cy/npc-chat/chat-export.js');
const COMPONENT_PATH = require.resolve('../../components/cy/npc-chat/index.js');
const ROOT = path.resolve(__dirname, '../..');

let definition;
let activeApp;
global.getApp = () => activeApp;
(function load() {
  const prev = global.Component;
  global.Component = (options) => { definition = options; };
  delete require.cache[COMPONENT_PATH];
  require(COMPONENT_PATH);
  global.Component = prev;
}());

function createChat(app) {
  activeApp = app;
  const instance = {
    properties: { show: true, sessionId: 91, scope: 'roam' },
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(update) { Object.assign(this.data, update); },
    triggerEvent() {},
  };
  Object.assign(instance, definition.methods);
  return instance;
}

function sampleVo() {
  return {
    retentionDays: 90,
    retentionPolicy: '聊天原文最长保留 90 天,到期自动删除;你也可以在此随时主动删除全部记录,删除立即生效且不可恢复。',
    records: [
      { id: 1, userId: 5, profileId: 2, activityId: 3, userMsg: '下一站去哪?', npcReply: '往南走两百米。', safetyFlag: 0,
        model: 'm', latencyMs: 10, tokenIn: 1, tokenOut: 2, createTime: '2026-10-01T12:00:00.000+08:00' },
      { id: 2, userId: 5, profileId: 2, activityId: 3, userMsg: '', npcReply: null, createTime: null },
    ],
  };
}

test('格式化:可读文本含条数、保留说明与逐条对话;JSON 是回包原样', () => {
  const raw = sampleVo();
  const vo = exp.normalizeExport(raw);
  const text = exp.formatReadable(vo);
  assert.match(text, /共 2 条/);
  assert.match(text, /保留说明：聊天原文最长保留 90 天/);
  assert.match(text, /\[2026-10-01 12:00:00\]\n我：下一站去哪\?\nAI：往南走两百米。/);
  assert.match(text, /\[时间未记录\]\n我：（无内容）\nAI：（没有回复）/);
  assert.deepEqual(JSON.parse(exp.formatJson(raw)), raw, '完整 JSON 不增删字段');
});

test('格式化:畸形回包返回 null,不当成空记录', () => {
  assert.equal(exp.normalizeExport(null), null);
  assert.equal(exp.normalizeExport({ records: 'x' }), null);
  assert.equal(exp.normalizeExport({ records: [1] }), null);
  assert.equal(exp.normalizeExport([]), null);
  assert.deepEqual(exp.normalizeExport({ records: [] }).records, []);
});

test('预览最多 50 条,复制内容包含全部', () => {
  const records = Array.from({ length: 60 }, (_, i) => ({ id: i + 1, userMsg: 'q' + i, npcReply: 'a' + i }));
  const vo = exp.normalizeExport({ records });
  assert.equal(exp.previewRows(vo).length, exp.PREVIEW_LIMIT);
  assert.match(exp.formatReadable(vo), /共 60 条[\s\S]*q59/);
});

test('组件:导出 → 预览 → 复制文本/JSON,全程不写本地存储', () => {
  const requests = [];
  const storageWrites = [];
  const clipboard = [];
  global.wx = {
    setClipboardData: (o) => { clipboard.push(o.data); o.success && o.success(); },
    setStorageSync: () => storageWrites.push('sync'),
    setStorage: () => storageWrites.push('async'),
  };
  const chat = createChat({ sendRequest: (o) => { requests.push(o); return { abort() {} }; } });
  chat.onExportData();
  assert.equal(chat.data.exportState, 'loading');
  assert.equal(requests[0].url, '/api/ai/npc/chat/data/export');
  assert.equal(requests[0].method, 'GET');
  assert.equal(requests[0].autoErrorToast, false);
  chat.onExportData();
  assert.equal(requests.length, 1, '加载中不重复请求');
  const raw = sampleVo();
  requests[0].success({ code: 200, data: raw });
  assert.equal(chat.data.exportState, 'ready');
  assert.equal(chat.data.exportCount, 2);
  assert.equal(chat.data.exportPreview.length, 2);
  chat.onCopyExport({ currentTarget: { dataset: { format: 'text' } } });
  assert.match(clipboard[0], /城瘾 · 我的 AI 对话记录/);
  assert.match(chat.data.exportCopied, /已复制可读文本/);
  chat.onCopyExport({ currentTarget: { dataset: { format: 'json' } } });
  assert.deepEqual(JSON.parse(clipboard[1]), raw);
  assert.equal(storageWrites.length, 0, '导出不落本地存储');
  chat.onCloseExport();
  assert.equal(chat.data.exportState, '');
  assert.equal(chat._exportJson, '', '关闭面板即释放内存里的导出文本');
});

test('组件:空记录 / 业务失败 / 断网 / 畸形回包 各有诚实状态', () => {
  const requests = [];
  global.wx = { setClipboardData: () => {} };
  const chat = createChat({
    sendRequest: (o) => { requests.push(o); return { abort() {} }; },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  });
  chat.onExportData();
  requests[0].success({ code: 200, data: { retentionPolicy: 'p', records: [] } });
  assert.equal(chat.data.exportState, 'empty');
  chat.onExportData();
  requests[1].success({ code: 500, msg: '请先登录' });
  assert.equal(chat.data.exportState, 'error');
  assert.equal(chat.data.exportError, '请先登录');
  chat.onExportData();
  requests[2].fail({});
  assert.equal(chat.data.exportState, 'error');
  assert.match(chat.data.exportError, /网络没连上/);
  chat.onExportData();
  requests[3].success({ code: 200, data: { records: 'oops' } });
  assert.equal(chat.data.exportState, 'error', '畸形回包不能显示成「没有记录」');
});

test('组件:关闭面板或清除记录后,迟到的导出回包被丢弃', () => {
  const requests = [];
  global.wx = { setClipboardData: () => {} };
  const chat = createChat({ sendRequest: (o) => { requests.push(o); return { abort() {} }; } });
  chat.onExportData();
  chat.onCloseExport();
  requests[0].success({ code: 200, data: sampleVo() });
  assert.equal(chat.data.exportState, '');
  assert.equal(chat.data.exportPreview.length, 0);
});

test('模板:导出与清除并排;导出面板打开时隐藏输入区', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'components/cy/npc-chat/index.wxml'), 'utf8');
  assert.match(wxml, /bindtap="onExportData"[^>]*>导出我的对话记录</);
  assert.match(wxml, /bindtap="onAskClearData"[^>]*>清除我的对话记录</);
  assert.match(wxml, /wx:if="\{\{!exportState\}\}" class="npc-chat__composer"/);
  assert.match(wxml, /data-format="json" bindtap="onCopyExport"/);
});
