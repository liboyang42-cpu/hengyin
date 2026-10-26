'use strict';

// CL4 / N3:官方活动 V2 主办控制台。
// 断言的是「服务端说了算」这条主线:publish-check 不放行就不出任何控件;命令先确认再提交;
// 成功只由同请求号持久回执确认，业务状态另行刷新;断网/5xx 落「结果未确认」而不是假成功或假失败。

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const { platform, receipt, tick } = require('./helpers/team-event-platform');
const ROOT = path.resolve(__dirname, '../..');
const rules = require('../../pages/activity/official-organizer/organizer-rules.js');
const { getDangerAction } = require('../../utils/danger-actions.js');
const { attachDangerConfirm } = require('./helpers/danger-confirm-stub');

function read(rel) { return fs.readFileSync(path.join(ROOT, rel), 'utf8'); }

function installWx(extra) {
  const calls = { navigateTo: [], toasts: [] };
  const base = { ...platform(),
    stopPullDownRefresh: () => {},
    navigateTo: (o) => calls.navigateTo.push(o.url),
    navigateBack: () => {},
    redirectTo: () => {},
    showToast: (o) => calls.toasts.push(o.title),
  };
  global.wx = new Proxy(Object.assign(base, extra || {}), {
    get(target, key) { return key in target ? target[key] : () => {}; },
  });
  global.getCurrentPages = () => [];
  return calls;
}

function loadPage(rel, app) {
  let definition;
  global.getApp = () => app;
  global.Page = (config) => { definition = config; };
  const abs = path.join(ROOT, rel);
  delete require.cache[require.resolve(abs)];
  require(abs);
  return definition;
}

function setPath(target, key, value) {
  const parts = key.split('.');
  let node = target;
  for (let i = 0; i < parts.length - 1; i++) {
    node[parts[i]] = Object.assign({}, node[parts[i]]);
    node = node[parts[i]];
  }
  node[parts[parts.length - 1]] = value;
}

function makePage(definition) {
  const page = Object.assign({}, definition);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = (patch) => {
    // 先拷贝顶层对象,与真实 setData 一样不共享旧引用
    Object.keys(patch).forEach((key) => setPath(page.data, key, patch[key]));
  };
  return page;
}

function makeApp() {
  const requests = [];
  return {
    requests,
    getUserID: () => '71', getAuthorization: () => 'token-a',
    sendRequest: (req) => { requests.push(req); return { abort() {} }; },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  };
}

function ok(data) { return { code: 200, data }; }

// 把一次完整读取走完:publish-check → 详情(或回落到我发布的)
function finishLoad(app, { check, detail, mine }) {
  const take = (suffix) => {
    const idx = app.requests.findIndex((r) => !r._done && r.url.endsWith(suffix));
    assert.ok(idx >= 0, '应发出请求 ' + suffix);
    app.requests[idx]._done = true;
    return app.requests[idx];
  };
  take('/publish-check').success(check);
  if (check && check.code === 200) {
    const d = take('/api/official/events/7');
    if (detail === 'fail') d.fail({});
    else d.success(detail);
    if (mine !== undefined) take('/api/official/my-published').success(mine);
  }
}

function openPage(app, query) {
  const calls = installWx();
  const definition = loadPage('pages/activity/official-organizer/index.js', app);
  const page = makePage(definition);
  page.onLoad(query || { id: '7' });
  return { page, calls };
}

function wireConfirms(page) {
  const stubs = {};
  for (const action of ['schedule', 'pause', 'resume']) {
    const name = 'onConfirm' + action[0].toUpperCase() + action.slice(1);
    stubs[action] = attachDangerConfirm(page, { id: '#dc-' + action, confirmHandler: name });
  }
  page.selectComponent = (sel) => {
    const action = sel.replace('#dc-', '');
    return stubs[action] || null;
  };
  return stubs;
}

const LIVE = { id: 7, title: '外滩夜行', status: 3, contractVersion: 2, controlState: 'NORMAL' };
const PASS = ok({ blockers: [], risks: [] });

test('规则:状态门槛与服务端一一对应,状态未知时全部置灰', () => {
  const draft = rules.actionAvailability({ status: 0, controlState: 'NORMAL' });
  assert.equal(draft.schedule.enabled, true);
  assert.equal(draft.pause.enabled, false, '草稿不能暂停(服务端 status<SCHEDULED 拒绝)');
  assert.deepEqual(Object.keys(draft).sort(), ['pause', 'resume', 'schedule'], '用户决定:邀请/转交暂不做');
  const live = rules.actionAvailability({ status: 3, controlState: 'NORMAL' });
  assert.equal(live.schedule.enabled, false);
  assert.equal(live.pause.enabled, true);
  assert.equal(live.resume.enabled, false);
  const paused = rules.actionAvailability({ status: 3, controlState: 'PAUSED' });
  assert.equal(paused.resume.enabled, true);
  assert.equal(paused.pause.enabled, false);
  const settling = rules.actionAvailability({ status: 4, controlState: 'NORMAL' });
  assert.equal(settling.pause.enabled, false);
  const unknown = rules.actionAvailability(rules.normalizeEventState({ status: '3', controlState: 'WEIRD' }));
  for (const key of Object.keys(unknown)) assert.equal(unknown[key].enabled, false, key + ' 状态未知必须置灰');
});

test('规则:暂停四项=预设+补充文字合成文本;恢复必填说明', () => {
  const pause = {
    reason: { preset: '天气或安全原因', extra: '暴雨预警' },
    impactScope: { preset: '全部已报名玩家', extra: '' },
    alternativePlan: { preset: '', extra: '改到下周六' },
    notificationPlan: { preset: '站内通知全部已报名玩家', extra: '' },
  };
  assert.equal(rules.validateForm('pause', pause), '');
  assert.deepEqual(rules.buildPayload('pause', pause), {
    reason: '天气或安全原因：暴雨预警',
    impactScope: '全部已报名玩家',
    alternativePlan: '改到下周六',
    notificationPlan: '站内通知全部已报名玩家',
  });
  const missing = Object.assign({}, pause, { alternativePlan: { preset: '', extra: '   ' } });
  assert.match(rules.validateForm('pause', missing), /都要选一项或写几句/);
  for (const key of rules.PAUSE_FIELDS) assert.ok(rules.PAUSE_PRESETS[key].length >= 2, key + ' 需要预设选项');
  assert.match(rules.validateForm('resume', { reason: '' }), /风险处理说明/);
  assert.deepEqual(rules.buildPayload('resume', { reason: ' 已处理 ' }), { reason: '已处理' });
  assert.equal(rules.isPositiveId('1234567890123456'), false, '超过 JS 安全整数的编号不能以数字提交');
});

test('publish-check 被拒:落无权态,不读活动、不渲染控件', () => {
  const app = makeApp();
  const { page } = openPage(app);
  app.requests[0].success({ code: 500, msg: '无权查看该活动发布检查' });
  assert.equal(page.data.phase, 'denied');
  assert.equal(page.data.deniedReason, '无权查看该活动发布检查');
  assert.equal(app.requests.length, 1, '无权时不应继续读取活动');
  const wxml = read('pages/activity/official-organizer/index.wxml');
  assert.match(wxml, /<scroll-view wx:else/, '控件全部在 phase=ready 的 wx:else 分支里');
});

test('非法 id 落缺参态,不发请求', () => {
  const app = makeApp();
  const { page } = openPage(app, { id: 'abc' });
  assert.equal(page.data.phase, 'missing');
  assert.equal(app.requests.length, 0);
});

test('草稿在公开详情不可见时,回落到「我发布的」同一条服务端记录', () => {
  const app = makeApp();
  const { page } = openPage(app);
  finishLoad(app, {
    check: ok({ blockers: ['缺少任务'], risks: [] }),
    detail: { code: 500, msg: '活动当前不可见' },
    mine: ok({ events: [{ id: 7, title: '草稿活动', status: 0, contractVersion: 2, controlState: 'NORMAL' }], broadcasts: [] }),
  });
  assert.equal(page.data.phase, 'ready');
  assert.equal(page.data.view.title, '草稿活动');
  assert.equal(page.data.avail.schedule.enabled, true);
  assert.equal(page.data.check.pass, false);
  assert.deepEqual(page.data.check.blockers, ['缺少任务']);
});

test('排期:发布检查有阻塞项时不弹确认、不发请求', () => {
  const app = makeApp();
  const { page } = openPage(app);
  const dc = wireConfirms(page);
  finishLoad(app, {
    check: ok({ blockers: ['缺少任务'] }),
    detail: { code: 500 },
    mine: ok({ events: [{ id: 7, status: 0, contractVersion: 2, controlState: 'NORMAL' }], broadcasts: [] }),
  });
  const before = app.requests.length;
  page.onAction({ currentTarget: { dataset: { action: 'schedule' } } });
  assert.equal(dc.schedule.opens.length, 0);
  assert.equal(app.requests.length, before);
});

test('暂停:先确认再提交；成功必须有原号APPLIED，随后独立刷新状态', async () => {
  const app = makeApp(); const { page } = openPage(app); const dc = wireConfirms(page);
  finishLoad(app, { check: PASS, detail: ok(LIVE) });
  page.onAction({ currentTarget: { dataset: { action: 'pause' } } });
  assert.equal(dc.pause.opens.length, 0); assert.equal(page.data.formError.action, 'pause');
  for (const key of rules.PAUSE_FIELDS) page.onPickPreset({ currentTarget: { dataset: { key, value: rules.PAUSE_PRESETS[key][0] } } });
  page.onFieldInput({ currentTarget: { dataset: { form: 'pause', key: 'reason' } }, detail: { value: '暴雨预警' } });
  const before = app.requests.length;
  page.onAction({ currentTarget: { dataset: { action: 'pause' } } });
  assert.equal(dc.pause.opens.length, 1); assert.equal(app.requests.length, before);
  dc.pause.confirm(); await tick(); const req = app.requests.at(-1);
  assert.equal(req.url, '/api/official/command'); assert.equal(req.method, 'POST');
  const body = JSON.parse(req.data);
  assert.equal(body.targetId, '7'); assert.equal(body.action, 'pause'); assert.match(body.requestId, /^[a-f0-9]{32}$/);
  assert.equal(body.reason, rules.PAUSE_PRESETS.reason[0] + '：暴雨预警');
  for (const field of ['impactScope', 'alternativePlan', 'notificationPlan']) assert.equal(body[field], rules.PAUSE_PRESETS[field][0]);
  req.success(receipt(req, 'APPLIED'));
  assert.equal(page.data.commandRecovery.state, 'APPLIED'); assert.equal(page.data.result.tone, 'success');
  finishLoad(app, { check: PASS, detail: ok({ ...LIVE, controlState: 'PAUSED' }) });
  assert.equal(page.data.view.paused, true);
  assert.equal(page.data.forms.pause.reason.extra, body.reason, '原输入在确认回执前保持可见');
  page.acknowledgeCommand(); assert.equal(page.data.commandRecovery.state, '');
});

test('普通状态相符也不能确认丢失的原请求；UNKNOWN保留冻结输入', async () => {
  const app = makeApp(); const { page } = openPage(app); const dc = wireConfirms(page);
  finishLoad(app, { check: PASS, detail: ok({ ...LIVE, controlState: 'PAUSED' }) });
  page.onFieldInput({ currentTarget: { dataset: { form: 'resume', key: 'reason' } }, detail: { value: '已处理' } });
  page.onAction({ currentTarget: { dataset: { action: 'resume' } } }); dc.resume.confirm(); await tick();
  const req = app.requests.at(-1); req.fail({});
  page.load(); finishLoad(app, { check: PASS, detail: ok({ ...LIVE, controlState: 'NORMAL' }) });
  assert.equal(page.data.commandRecovery.state, 'UNKNOWN'); assert.equal(page.data.result, null);
  page.queryCommand(); const query = app.requests.at(-1); assert.ok(query.url.endsWith('/command/query'));
  query.success(receipt(query, 'UNKNOWN')); assert.equal(page.data.commandRecovery.state, 'UNKNOWN');
  page.onFieldInput({ currentTarget: { dataset: { form: 'resume', key: 'reason' } }, detail: { value: 'new' } });
  assert.equal(page.data.forms.resume.reason, '已处理');
  page.retryCommand(); assert.equal(app.requests.at(-1).data, req.data);
});

test('持久REJECTED回执不假成功；保留原输入直到确认结果', async () => {
  const app = makeApp(); const { page } = openPage(app); const dc = wireConfirms(page);
  finishLoad(app, { check: PASS, detail: ok({ ...LIVE, controlState: 'PAUSED' }) });
  page.onFieldInput({ currentTarget: { dataset: { form: 'resume', key: 'reason' } }, detail: { value: '已处理' } });
  page.onAction({ currentTarget: { dataset: { action: 'resume' } } }); dc.resume.confirm(); await tick();
  const req = app.requests.at(-1); assert.equal(JSON.parse(req.data).reason, '已处理');
  req.success(receipt(req, 'REJECTED'));
  assert.equal(page.data.result.tone, 'error'); assert.equal(page.data.commandRecovery.state, 'REJECTED');
  assert.equal(page.data.forms.resume.reason, '已处理');
  const before = app.requests.length; page.retryCommand(); assert.equal(app.requests.length, before);
});

test('断网/5xx不换号；终止入口使用原输入，CANCELLED才释放', async () => {
  for (const failure of ['network', 'http']) {
    const app = makeApp(); const { page } = openPage(app); const dc = wireConfirms(page);
    finishLoad(app, { check: PASS, detail: ok({ ...LIVE, status: 0 }) });
    page.onAction({ currentTarget: { dataset: { action: 'schedule' } } }); dc.schedule.confirm(); await tick();
    const req = app.requests.at(-1); assert.equal(JSON.parse(req.data).action, 'schedule');
    if (failure === 'http') req.successStatusAbnormal({}, 502); else req.fail({});
    assert.equal(page.data.commandRecovery.state, 'UNKNOWN'); assert.equal(page.data.result, null);
    page.cancelCommand(); const cancel = app.requests.at(-1);
    assert.ok(cancel.url.endsWith('/command/cancel')); assert.equal(cancel.data, req.data);
    cancel.success(receipt(cancel, 'CANCELLED')); assert.equal(page.data.commandRecovery.state, 'CANCELLED');
    assert.notEqual(page.data.result.tone, 'success');
    page.acknowledgeCommand(); assert.equal(page.data.commandRecovery.state, '');
  }
});

test('旧HTTP分类器保留兼容；新命令只认持久回执', () => {
  assert.equal(rules.isUnknownOutcome(403), false);
  assert.equal(rules.isUnknownOutcome(500), true);
  assert.equal(rules.isUnknownOutcome(408), true);
  assert.equal(rules.isUnknownOutcome(undefined), true);
});

test('危险确认文案已登记;不可逆的写明不可撤销', () => {
  assert.equal(getDangerAction('official.organizer.invite', {}), null, '邀请/转交暂不做，确认文案不应残留');
  assert.equal(getDangerAction('official.organizer.transfer', {}), null);
  assert.equal(getDangerAction('official.organizer.schedule', { name: 'x' }).confirmText, '发布活动');
  for (const action of ['schedule', 'pause', 'resume']) {
    const a = getDangerAction('official.organizer.' + action, { name: 'x' });
    assert.ok(a, action + ' 未登记');
    assert.ok(a.consequences.length >= 2);
    if (a.irreversible) assert.ok(a.consequences.some((c) => c.text.includes('此操作不可撤销')), action);
  }
});

test('官方活动详情:只有 publish-check 放行才出现主办入口', () => {
  installWx();
  const app = makeApp();
  const definition = loadPage('pages/activity/official-detail/index.js', app);
  const page = makePage(definition);
  page.data.id = 7;
  page._fetchSeq = 1;
  page._probeOrganizer({ _isV2: true });
  const probe = app.requests[0];
  assert.equal(probe.url, '/api/official/v2/events/7/publish-check');
  assert.equal(probe.autoErrorToast, false, '普通玩家被拒不应弹错误');
  probe.success({ code: 500, msg: '无权' });
  assert.equal(page.data.organizerEntry, false);
  page._probeOrganizer({ _isV2: true });
  app.requests[1].success(ok({ blockers: [] }));
  assert.equal(page.data.organizerEntry, true);
  page._probeOrganizer({ _isV2: false });
  assert.equal(page.data.organizerEntry, false, 'V1 活动没有主办控制台');
  assert.equal(app.requests.length, 2, 'V1 不发探针');
  const wxml = read('pages/activity/official-detail/index.wxml');
  assert.match(wxml, /wx:if="\{\{organizerEntry\}\}"/);
});

test('我发布的:V2 活动挂控制台入口;页面不再有邀请/转交', () => {
  const mine = read('pages/activity/official-mine/index.wxml');
  assert.match(mine, /wx:if="\{\{item\._isV2\}\}"[^>]*catchtap="openOrganizer"/);
  assert.doesNotMatch(read('pages/activity/official-organizer/index.js'), /organizer-invites|organizer-transfer/);
  assert.doesNotMatch(read('pages/activity/official-organizer/index.wxml'), /转交|候任|邀请/);
  assert.match(read('pages/activity/official-organizer/index.wxml'), /发布活动[\s\S]*通过发布检查后对报名可见/);
  const appJson = JSON.parse(read('app.json'));
  const activity = appJson.subPackages.find((p) => p.root === 'pages/activity');
  assert.ok(activity.pages.includes('official-organizer/index'));
});

test('规则:composePauseField 拼接与空值;verifyReadBack 不一致必须判 mismatch', () => {
  assert.equal(rules.composePauseField({ preset: '天气或安全原因', extra: '暴雨' }), '天气或安全原因：暴雨');
  assert.equal(rules.composePauseField({ preset: ' 预设 ', extra: '  ' }), '预设');
  assert.equal(rules.composePauseField({ preset: '', extra: ' 只有补充 ' }), '只有补充');
  assert.equal(rules.composePauseField({}), '');
  assert.equal(rules.composePauseField(null), '');
  assert.equal(rules.composePauseField({ preset: 3, extra: null }), '', '非字符串当空');
  assert.equal(rules.verifyReadBack('schedule', { status: 1 }), 'confirmed');
  assert.equal(rules.verifyReadBack('schedule', { status: 0 }), 'mismatch');
  assert.equal(rules.verifyReadBack('schedule', { status: '1' }), 'mismatch', '状态非整数不算确认');
  assert.equal(rules.verifyReadBack('pause', { controlState: 'NORMAL' }), 'mismatch');
  assert.equal(rules.verifyReadBack('resume', { controlState: 'PAUSED' }), 'mismatch');
  assert.equal(rules.verifyReadBack('resume', { controlState: 'NORMAL' }), 'confirmed');
  assert.equal(rules.verifyReadBack('pause', null), 'mismatch');
  assert.equal(rules.verifyReadBack('unknown-action', { status: 3 }), 'mismatch');
});
