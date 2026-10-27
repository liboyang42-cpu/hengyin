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
  h = { requests: [], toasts: [], modals: [], storage: new Map() };
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest: o => { h.requests.push(o); },
    chooseImage: (callback, count) => { h.upload = callback; h.uploadCount = count; },
    getRequestErrorMessage: (r, fallback) => (r && r.msg) || fallback,
    getUserID: () => 9, getToken: () => '',
  });
  global.wx = {
    getStorageSync: key => h.storage.get(key), setStorageSync: (key, value) => h.storage.set(key, JSON.parse(JSON.stringify(value))),
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
  assert.match(wxml, /wx:if="\{\{auditSheet\.canAppeal && !auditSheet\.pending\}\}" bindtap="appealAudit"/);
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
  const original = JSON.parse(appeal.data); assert.ok(original.requestId);
  assert.deepEqual(original, { bizType: 'TOPIC', bizId: '31', reason: '引流信息是活动官方公众号，非违规', attachments: [], requestId: original.requestId });
  appeal.success({ code: 200, appealId: 3, state: 'SUBMITTED' });
  assert.deepEqual(h.toasts, ['申诉已提交，正在核对结果']);
  const receipt = h.requests[2]; assert.equal(receipt.url, '/api/audit/appeal/receipt');
  receipt.success({ code: 200, data: { state: 'FOUND', intent: { ...original, version: 1, memberId: '9', evidence: null }, appealId: '3', taskId: '6', appealState: 'SUBMITTED' } });
  assert.equal(h.page.data.auditSheet.pending, false);
  const reload = h.requests[3];
  assert.equal(reload.url, '/api/audit/timeline', '提交后重读，申诉入口按新 canAppeal 收起');
  reload.success(timeline({ canAppeal: false, appeal: { id: 3, state: 'SUBMITTED' } }));
  assert.equal(h.page.data.auditSheet.canAppeal, false);
  assert.equal(h.page.data.auditSheet.appealText, '申诉已提交，等待平台处理');
});

test('审核记录展示后端原因标签，即使没有自由文本原因', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '老城夜巡');
  h.requests[0].success(timeline({ rounds: [{ kind: 'SUBMIT', timeline: [{
    id: 3, action: 'REJECT', reasonLabels: ['地点信息不完整', '含有站外引流'],
    reasonText: null, createTime: '2026-10-20 10:00:00'
  }] }] }));
  assert.equal(h.page.data.auditSheet.rows[0].sub, '地点信息不完整、含有站外引流 · 2026-10-20 10:00:00');
});

test('审核原因标签与补充说明同时保留，异常标签不展示为内部对象', () => {
  const at = require(path.join(ROOT, 'subpackageA/utils/audit-timeline.js'));
  const rows = at.timelineRows({ rounds: [{ timeline: [{ action: 'REJECT',
    reasonLabels: ['内容不完整', null, {}, 12, ''], reasonText: '请补集合地址', createTime: '2026-10-20'
  }, { action: 'REJECT', reasonLabels: '错误类型', reasonText: '旧记录说明' }] }] });
  assert.equal(rows[0].sub, '内容不完整 · 请补集合地址 · 2026-10-20');
  assert.equal(rows[1].sub, '旧记录说明');
});

test('申诉成立不承诺已经公开，撤回决定保留在审核记录中', () => {
  const at = require(path.join(ROOT, 'subpackageA/utils/audit-timeline.js'));
  assert.equal(at.appealText({ appeal: { state: 'ACCEPTED' } }), '申诉已成立，请查看最新审核状态');
  const rows = at.timelineRows({ rounds: [{ kind: 'APPEAL', timeline: [{
    action: 'CANCEL', reasonText: '修改内容后已撤回申诉', createTime: '2026-10-20'
  }] }] });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].label, '申诉复核 · 已撤回');
  assert.match(rows[0].sub, /修改内容后已撤回申诉/);
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
  assert.equal(h.modals.length, 1, '冲突不释放原输入，不重新发起申诉');
  assert.equal(h.requests[4].method, 'GET');
  h.requests[4].fail({ errMsg: 'request:fail' });
  assert.deepEqual(h.toasts, []);
  assert.match(h.page.data.auditSheet.appealError, /读取失败/);
  assert.equal(h.page.data.auditSheet.pending, true);
});

test('清理旧账号状态时收起审核面板，迟到的时间线与申诉回包不能恢复旧记录', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '老城夜巡');
  h.requests[0].success(timeline());
  h.page.appealAudit();
  h.modals[0].success({ confirm: true, content: '申诉理由' });
  const appeal = h.requests[1];
  h.page.loadAudit();
  const staleTimeline = h.requests[2];
  h.page.resetPrivateState(true);
  assert.equal(h.page.data.auditSheet.show, false);
  assert.deepEqual(h.page.data.auditSheet.rows, []);
  assert.equal(h.page.data.auditSheet.canAppeal, false);
  staleTimeline.success(timeline());
  staleTimeline.fail({ errMsg: 'request:fail' });
  appeal.success({ code: 200 });
  assert.equal(h.page.data.auditSheet.show, false);
  assert.deepEqual(h.page.data.auditSheet.rows, []);
  assert.equal(h.requests.length, 3, '旧申诉结果不能触发新账号的时间线读取');
  assert.deepEqual(h.toasts, []);
});

test('FOLLOWUP-A-2 玩法卡:个人审核未通过 → TEMPLATE;广场驳回 / 下架 → TEMPLATE_LIBRARY;正常玩法不出入口', () => {
  const at = require(path.join(ROOT, 'subpackageA/utils/audit-timeline.js'));
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

test('逐项事实保留大ID、历史记录且不依赖重新查询节点', () => {
  const at = require(path.join(ROOT, 'subpackageA/utils/audit-timeline.js'));
  const rows = at.timelineRows({ rounds: [{ kind: 'SUBMIT', timeline: [], problems: [
    { id: '9223372036854775806', itemType: 'NODE', itemId: '9223372036854775807', reasonText: '节点问题' }
  ] }] });
  assert.match(rows[0].label, /9223372036854775807/); assert.equal(rows[0].sub, '节点问题');
});

test('UNKNOWN重进保留说明和附件，负向读取不能解冻，切对象迟到回包无效', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '9223372036854775807' }, '大ID');
  h.requests[0].success(timeline());
  h.page.setData({ 'auditSheet.attachments': ['media-asset:12'] });
  h.page.appealAudit(); h.modals[0].success({ confirm: true, content: '附件申诉' });
  const original = JSON.parse(h.requests[1].data); assert.ok(original.requestId);
  assert.deepEqual(original, { bizType: 'TOPIC', bizId: '9223372036854775807', reason: '附件申诉', attachments: ['media-asset:12'], requestId: original.requestId });
  h.requests[1].fail();
  h.page.closeAuditSheet(); h.page.openAudit({ bizType: 'TOPIC', bizId: '9223372036854775807' }, '重进');
  assert.equal(h.page.data.auditSheet.pending, true); assert.equal(h.page.data.auditSheet.draftReason, '附件申诉');
  h.requests[2].success(timeline()); assert.equal(h.page.data.auditSheet.pending, true);
  h.page.openAudit({ bizType: 'TOPIC', bizId: '32' }, '另一个对象');
  h.requests[1].success({ code: 200 }); assert.equal(h.page.data.auditSheet.bizId, '32');
});

test('存储失败零申诉写入；400保留理由和附件可修正', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '主题'); h.requests[0].success(timeline());
  const save = wx.setStorageSync; wx.setStorageSync = () => { throw Error('full'); };
  h.page.appealAudit(); h.modals[0].success({ confirm: true, content: '理由' }); assert.equal(h.requests.length, 1);
  wx.setStorageSync = save;
  h.page.appealAudit(); h.modals[1].success({ confirm: true, content: '理由' });
  h.requests[1].success({ code: 400, msg: '附件待审核' });
  assert.equal(h.page.data.auditSheet.pending, false); assert.equal(h.page.data.auditSheet.draftReason, '理由');
});

test('附件上传成功后可移除并重进恢复；换对象后的迟到上传不回填', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '主题'); h.requests[0].success(timeline());
  h.page.addAuditAttachment(); assert.equal(h.uploadCount, 6);
  h.upload(['https://example.invalid/owned-a.png', 'https://example.invalid/owned-b.png']);
  assert.equal(h.page.data.auditSheet.attachments.length, 2);
  h.page.removeAuditAttachment({ currentTarget: { dataset: { index: 0 } } });
  h.page.closeAuditSheet(); h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '重进');
  assert.deepEqual(h.page.data.auditSheet.attachments, ['https://example.invalid/owned-b.png']);
  h.requests[1].success(timeline()); h.page.addAuditAttachment(); const late = h.upload;
  h.page.openAudit({ bizType: 'TOPIC', bizId: '32' }, '另一个'); late(['https://example.invalid/late.png']);
  assert.deepEqual(h.page.data.auditSheet.attachments, []);
});

test('原号回读查无或异参保持UNKNOWN，正向同意图回执才解冻', () => {
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '主题'); h.requests[0].success(timeline());
  h.page.appealAudit(); h.modals[0].success({ confirm: true, content: '原理由' });
  const command = JSON.parse(h.requests[1].data); h.requests[1].fail();
  h.page.closeAuditSheet(); h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '重进');
  h.page.recoverAuditAppeal(); const read = h.requests.at(-1); assert.equal(read.data.requestId, command.requestId);
  read.success({ code: 200, data: { state: 'NOT_FOUND' } }); assert.equal(h.page.data.auditSheet.pending, true);
  assert.equal(h.requests.filter(r => r.method === 'POST').length, 1);
  h.page.recoverAuditAppeal();
  const receipt = { state: 'FOUND', intent: { ...command, version: 1, evidence: null, memberId: '9', reason: '错理由' }, appealId: '9223372036854775807', taskId: '42', appealState: 'ACCEPTED' };
  h.requests.at(-1).success({ code: 200, data: receipt }); assert.equal(h.page.data.auditSheet.pending, true);
  receipt.intent.reason = command.reason; h.page.recoverAuditAppeal(); h.requests.at(-1).success({ code: 200, data: receipt });
  assert.equal(h.page.data.auditSheet.pending, false); assert.equal(h.page.data.auditSheet.confirmed, true);
  assert.match(h.page.data.auditSheet.appealError, /9223372036854775807/);
  assert.equal(h.requests.filter(r => r.method === 'POST').length, 1);
});

test('历史待确认草稿不生成请求号；迟到原号回读不能覆盖新对象', () => {
  h.storage.set('audit-appeal:9:TOPIC:31', { reason: '历史', pending: true, attachments: [] });
  h.page.openAudit({ bizType: 'TOPIC', bizId: '31' }, '主题'); h.page.recoverAuditAppeal();
  assert.equal(h.page.data.auditSheet.appealCommand, null); assert.match(h.page.data.auditSheet.appealError, /未绑定/);
  assert.equal(h.requests.filter(r => r.method === 'POST').length, 0);
  h.page.openAudit({ bizType: 'TOPIC', bizId: '32' }, '新对象'); h.requests.at(-1).success(timeline());
  h.page.appealAudit(); h.modals[0].success({ confirm: true, content: '新理由' });
  const command = JSON.parse(h.requests.at(-1).data); h.requests.at(-1).fail(); h.page.recoverAuditAppeal();
  const late = h.requests.at(-1);
  h.page.openAudit({ bizType: 'TOPIC', bizId: '33' }, '另一个对象');
  late.success({ code: 200, data: { state: 'FOUND', intent: { ...command, memberId: '9', version: 1, evidence: null }, appealId: '88', taskId: '89', appealState: 'ACCEPTED' } });
  assert.equal(h.page.data.auditSheet.bizId, '33'); assert.equal(h.page.data.auditSheet.confirmed, false);
});
