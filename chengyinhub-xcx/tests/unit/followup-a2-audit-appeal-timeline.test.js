'use strict';
// FOLLOWUP-A-2:「我的项目」里被驳回 / 下架的活动、主题、玩法 → 「审核记录」(GET /api/audit/timeline)
// 和「申诉」(POST /api/audit/appeal,#384)。申诉入口只在 timeline 回 canAppeal=true 时出现;失败如实 toast。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE = path.join(ROOT, 'subpackageA/pages/myproject/index.js');
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');

let h;
beforeEach(() => {
  h = { requests: [], toasts: [], modals: [] };
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: o => { h.requests.push(o); },
    getRequestErrorMessage: (r, fallback) => (r && r.msg) || fallback,
    getUserID: () => 9, getToken: () => '',
  });
  global.wx = {
    getStorageSync: () => undefined, setStorageSync() {},
    showToast: o => h.toasts.push(o && o.title), showLoading() {}, hideLoading() {},
    showModal: o => h.modals.push(o),
    navigateTo() {}, redirectTo() {}, getSystemInfoSync: () => ({ windowWidth: 375 }), nextTick: f => f(),
    setNavigationBarColor() {}, setNavigationBarTitle() {},
  };
  global.getCurrentPages = () => [];
  global.Page = cfg => { h.config = cfg; };
  for (const k of Object.keys(require.cache)) if (k.startsWith(ROOT + path.sep) && !k.includes('node_modules')) delete require.cache[k];
  require(PAGE);
  const page = Object.assign({}, h.config, { data: JSON.parse(JSON.stringify(h.config.data)) });
  page.setData = function (patch) {
    for (const key of Object.keys(patch)) {
      const parts = key.split('.');
      let target = this.data;
      while (parts.length > 1) { const p = parts.shift(); target[p] = Object.assign({}, target[p]); target = target[p]; }
      target[parts[0]] = patch[key];
    }
  };
  page.loadProjects = () => {}; page.loadTemplates = () => {};
  h.page = page;
});

const timeline = (over) => ({ code: 200, data: Object.assign({
  bizType: 'TOPIC', bizId: 31, appeal: null, canAppeal: true,
  rounds: [{ kind: 'REPORT', task: { id: 5 }, timeline: [
    { id: 2, action: 'REJECT', reasonText: '含违规引流信息', createTime: '2026-10-19 10:00:00' },
  ] }],
}, over) });

function longPress(item) {
  h.page.onCardLong({ currentTarget: { dataset: { item } } });
}

test('FOLLOWUP-A-2 未通过 / 已下架的主题、活动:长按面板出「审核记录」,读 /api/audit/timeline', () => {
  for (const [biz, state, bizType] of [['topic', 'rejected', 'TOPIC'], ['activity', 'offline', 'ACTIVITY']]) {
    h.requests.length = 0;
    longPress({ id: 31, bizType: biz, state, title: '老城夜巡', _canDelete: true, _canAudit: true });
    assert.equal(h.page.data.opsSheet.canAudit, true);
    h.page.onOpsPick({ currentTarget: { dataset: { k: 'audit' } } });
    assert.equal(h.page.data.opsSheet.show, false, '先收起长按面板');
    const req = h.requests[0];
    assert.equal(req.url, '/api/audit/timeline');
    assert.equal(req.method, 'GET');
    assert.deepEqual(req.data, { bizType, bizId: '31' });
    assert.equal(h.page.data.auditSheet.show, true);
  }
  const wxml = read('subpackageA/pages/myproject/index.wxml');
  assert.match(wxml, /wx:if="\{\{opsSheet\.canAudit\}\}" bindtap="onOpsPick" data-k="audit"/);
  assert.match(wxml, /<cy-sheet show="\{\{auditSheet\.show\}\}" title="审核记录"/);
  assert.match(wxml, /wx:if="\{\{auditSheet\.canAppeal\}\}" bindtap="appealAudit"/);
});

test('FOLLOWUP-A-2 审核记录展示每轮决定;canAppeal=true 才出「申诉」,提交 POST /api/audit/appeal JSON', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '老城夜巡');
  h.requests[0].success(timeline());
  const sheet = h.page.data.auditSheet;
  assert.equal(sheet.loading, false);
  assert.deepEqual(sheet.rows.map(r => r.label), ['举报复核 · 未通过 / 已下架']);
  assert.match(sheet.rows[0].sub, /含违规引流信息/);
  assert.equal(sheet.canAppeal, true);
  h.page.appealAudit();
  assert.equal(h.modals.length, 1);
  assert.equal(h.modals[0].editable, true);
  assert.equal(h.modals[0].validate(''), '请写清申诉理由');
  h.modals[0].success({ confirm: true, content: '  引流信息是活动官方公众号，非违规  ' });
  const appeal = h.requests[1];
  assert.equal(appeal.url, '/api/audit/appeal');
  assert.equal(appeal.method, 'POST');
  assert.match(JSON.stringify(appeal.header), /application\/json/);
  assert.deepEqual(JSON.parse(appeal.data), { bizType: 'TOPIC', bizId: 31, reason: '引流信息是活动官方公众号，非违规' });
  appeal.success({ code: 200, appealId: 3, state: 'SUBMITTED' });
  assert.deepEqual(h.toasts, ['申诉已提交']);
  const reload = h.requests[2];
  assert.equal(reload.url, '/api/audit/timeline', '提交后重读,申诉入口按新 canAppeal 收起');
  reload.success(timeline({ canAppeal: false, appeal: { id: 3, state: 'SUBMITTED' } }));
  assert.equal(h.page.data.auditSheet.canAppeal, false);
  assert.equal(h.page.data.auditSheet.appealText, '申诉已提交，等待平台处理');
});

test('FOLLOWUP-A-2 canAppeal=false(已申诉过 / 仍在审 / 已结)不出申诉,处理器也不发请求', () => {
  h.page.openAudit({ bizType: 'ACTIVITY', bizId: '8' }, '夜跑');
  h.requests[0].success(timeline({ canAppeal: false }));
  assert.equal(h.page.data.auditSheet.canAppeal, false);
  h.page.appealAudit();
  assert.equal(h.modals.length, 0);
  assert.equal(h.requests.length, 1);
});

test('FOLLOWUP-A-2 timeline 读失败 / 申诉被拒 / 网络失败都如实 toast', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '老城夜巡');
  h.requests[0].success({ code: 403, msg: '无权查看该内容的审核记录' });
  assert.equal(h.page.data.auditSheet.error, '无权查看该内容的审核记录');
  assert.equal(h.page.data.auditSheet.canAppeal, false);
  assert.deepEqual(h.toasts, ['无权查看该内容的审核记录']);

  h.toasts.length = 0;
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '老城夜巡');
  h.requests[1].success(timeline());
  h.page.appealAudit();
  h.modals[0].success({ confirm: true, content: '理由' });
  h.requests[2].success({ code: 409, msg: '该内容已申诉过' });
  assert.deepEqual(h.toasts, ['该内容已申诉过']);

  h.toasts.length = 0;
  h.page.appealAudit();
  h.modals[1].success({ confirm: true, content: '理由' });
  h.requests[3].fail({ errMsg: 'request:fail' });
  assert.deepEqual(h.toasts, ['网络异常，请重试']);
});

test('FOLLOWUP-A-2 玩法卡:个人审核未通过 → TEMPLATE;广场驳回 / 下架 → TEMPLATE_LIBRARY;正常玩法不出入口', () => {
  const at = require(path.join(ROOT, 'utils/audit-timeline.js'));
  assert.deepEqual(at.templateTarget({ id: 4, draftStatus: 1, status: 2 }), { bizType: 'TEMPLATE', bizId: '4' });
  assert.deepEqual(at.templateTarget({ id: 4, draftStatus: 1, status: 1, checkStatus: 1, marketTemplateId: 70, marketStatus: 2 }), { bizType: 'TEMPLATE_LIBRARY', bizId: '70' });
  assert.deepEqual(at.templateTarget({ id: 4, draftStatus: 1, status: 1, checkStatus: 1, marketTemplateId: 70, marketStatus: 1, marketPublishStatus: 0 }), { bizType: 'TEMPLATE_LIBRARY', bizId: '70' });
  assert.equal(at.templateTarget({ id: 4, draftStatus: 1, status: 1, checkStatus: 1, marketTemplateId: 70, marketStatus: 1, marketPublishStatus: 1 }), null);
  assert.equal(at.templateTarget({ id: 4, draftStatus: 0 }), null);
  assert.equal(at.projectTarget({ id: 1, bizType: 'topic', state: 'running' }), null);

  h.page.tplAudit({ currentTarget: { dataset: { biz: 'TEMPLATE_LIBRARY', bizid: '70', title: '铜牌年份' } } });
  assert.deepEqual(h.requests[0].data, { bizType: 'TEMPLATE_LIBRARY', bizId: '70' });
  const wxml = read('subpackageA/pages/myproject/index.wxml');
  assert.match(wxml, /wx:if="\{\{item\._audit\}\}" class="mp-op mp-op-toggle" catchtap="tplAudit"/);
});
