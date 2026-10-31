'use strict';
// FOLLOWUP-A-1:主题 / 模板广场 / 个人玩法的举报入口(#383 的 /api/topic/report、/api/template/report、
// /api/template/personal-report)。流程与动态详情 reportSquare 同一套:确认 → 平台举报原因 → 原因弹层 → toast。
// 只对「公开可见、且不是本人的」内容出入口;失败如实 toast。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const REASONS = { code: 200, data: { reporting: { policyVersion: 'v1', reasons: [{ code: 'SPAM', label: '垃圾广告' }, { code: 'ILLEGAL', label: '违法违规' }] } } };

function harness(file) {
  const h = { requests: [], toasts: [], modals: [], me: '11' };
  let definition;
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getRequestErrorMessage: (r, fallback) => (r && r.msg) || fallback,
    getUserID: () => h.me, getUserRole: () => 'user', getUserType: () => 'user',
    sendRequest: o => { h.requests.push(o); return { abort() {} }; }, tips() {},
  });
  global.Page = c => { definition = c; };
  global.wx = new Proxy({
    showToast: o => h.toasts.push(o.title),
    showModal: o => h.modals.push(o),
    getStorageSync: () => '', getSystemInfoSync: () => ({}), getWindowInfo: () => ({ windowWidth: 390 }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    getMenuButtonBoundingClientRect: () => ({ top: 24, height: 32 }),
    nextTick: f => f(),
    createSelectorQuery: () => ({ in() { return this; }, select() { return this; }, selectAll() { return this; }, boundingClientRect() { return this; }, exec(done) { if (done) done([]); } }),
  }, { get: (t, k) => (k in t ? t[k] : () => {}) });
  global.getCurrentPages = () => [];
  for (const k of Object.keys(require.cache)) if (k.startsWith(ROOT + path.sep) && !k.includes('node_modules')) delete require.cache[k];
  require(path.join(ROOT, file));
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this); };
  h.page = page;
  h.last = () => h.requests[h.requests.length - 1];
  return h;
}

// 跑完整条举报:确认 → 拉原因 → 选第 2 条 → 提交
function runReport(h, start, reply) {
  start();
  assert.equal(h.modals.length, 1, '先过二次确认');
  assert.equal(h.modals[0].title, '举报内容');
  assert.equal(h.modals[0].content, '确认举报这条内容？举报后将提交平台审核。');
  h.modals[0].success({ confirm: true });
  const reasons = h.last();
  assert.equal(reasons.url, '/api/v1/community/capabilities', '和动态举报同一份平台举报原因');
  reasons.success(REASONS);
  assert.equal(h.page.data.reportReasonShow, true, '弹「选择举报原因」');
  assert.deepEqual(h.page.data.reportReasonItems, ['垃圾广告', '违法违规']);
  h.page.onReportReasonSelect({ detail: { index: 1 } });
  return new Promise(r => setImmediate(r)).then(() => {
    const submit = h.last();
    assert.notEqual(submit, reasons, '选完原因才提交');
    if (reply) reply(submit);
    return submit;
  });
}

// ---------------------------------------------------------------- 主题详情
function topicInfo(over) {
  return Object.assign({ id: 77, name: '老城夜巡', memberId: 22, isOwner: 0, status: 1, userStatus: 1, chaptersList: [] }, over);
}
function loadTopic(h, info) {
  h.page.data.id = 77;
  h.page.getData();
  const req = h.requests.find(r => r.url === '/api/topic/info-to-user');
  req.success({ code: 200, data: info });
  h.requests.length = 0;
}

test('FOLLOWUP-A-1 主题:别人公开的主题出举报入口,提交到 /api/topic/report 带 id + 所选原因', async () => {
  const h = harness('pages/topic/index/index.js');
  loadTopic(h, topicInfo());
  assert.equal(h.page._canReport, true);
  const submit = await runReport(h, () => h.page.reportTopic(), r => r.success({ code: 200, msg: '举报已提交，内容将进入审核' }));
  assert.equal(submit.url, '/api/topic/report');
  assert.equal(submit.method, 'POST');
  assert.deepEqual(submit.data, { id: 77, reason: '违法违规' });
  assert.deepEqual(h.toasts, ['举报已提交，内容将进入审核']);
});

test('FOLLOWUP-A-1 主题:本人的、未上架或审核未过的主题不出举报入口,处理器也不发请求', () => {
  for (const over of [{ isOwner: 1 }, { memberId: 11 }, { userStatus: 0 }, { status: 2 }, { status: 0 }]) {
    const h = harness('pages/topic/index/index.js');
    loadTopic(h, topicInfo(over));
    assert.equal(h.page._canReport, false, JSON.stringify(over));
    h.page.reportTopic();
    assert.equal(h.modals.length, 0, JSON.stringify(over));
    assert.equal(h.requests.length, 0);
  }
  const wxml = read('pages/topic/index/index.wxml');
  // 2026-10-29 走查 V-23 业主「举报也不展示」:详情正文不再放「举报这个主题」行(本页无自绘 ⋯ 菜单,处理器保留)。
  assert.doesNotMatch(wxml.replace(/<!--[\s\S]*?-->/g, ''), /举报这个主题|topic-report-entry/);
  const hero = wxml.slice(wxml.indexOf('<view class="cont topic-hero-meta">'), wxml.indexOf('<view class="topic-beta"'));
  assert.doesNotMatch(hero, /reportTopic/, '举报入口不能叠在头图上');
  assert.match(wxml, /<cy-option-sheet show="\{\{reportReasonShow\}\}" title="选择举报原因" items="\{\{reportReasonItems\}\}"/);
});

test('FOLLOWUP-A-1 主题:后端拒绝 / 网络失败都如实 toast', async () => {
  let h = harness('pages/topic/index/index.js');
  loadTopic(h, topicInfo());
  await runReport(h, () => h.page.reportTopic(), r => r.success({ code: 500, msg: '主题不存在' }));
  assert.deepEqual(h.toasts, ['主题不存在']);
  h = harness('pages/topic/index/index.js');
  loadTopic(h, topicInfo());
  await runReport(h, () => h.page.reportTopic(), r => r.fail({ errMsg: 'request:fail' }));
  assert.deepEqual(h.toasts, ['网络异常，请重试']);
});

// ---------------------------------------------------------------- 玩法详情(模板广场 / 个人玩法)

// 2026-10-28 P-42 业主(v8 review 页明确「删掉玩法详情页最底下的『举报这个玩法』」):
// 玩法详情页不再有举报入口,原因弹层与两个提交处理器一并撤掉;主题详情 / 动态详情的举报不受影响(上面几条)。
test('FOLLOWUP-A-1 玩法详情:P-42 后不再提供举报入口、原因弹层与提交处理器', () => {
  const wxml = read('pages/templatedetail/templatedetail.wxml').replace(/<!--[\s\S]*?-->/g, '');
  const js = read('pages/templatedetail/templatedetail.js');
  assert.doesNotMatch(wxml, /举报|reportReasonShow|xb-report/);
  assert.doesNotMatch(js, /reportTemplate|reportPersonalTemplate|reportKind|\/api\/template\/(?:personal-)?report/);
  assert.equal(JSON.parse(read('pages/templatedetail/templatedetail.json')).usingComponents['cy-option-sheet'], undefined);
});

test('FOLLOWUP-A-1 平台举报原因拉不到 → 如实 toast,不提交', () => {
  const h = harness('pages/topic/index/index.js');
  loadTopic(h, topicInfo());
  h.page.reportTopic();
  h.modals[0].success({ confirm: true });
  h.last().success({ code: 503, msg: 'down' });
  assert.deepEqual(h.toasts, ['举报原因暂不可用']);
  assert.equal(h.requests.length, 1);
});
