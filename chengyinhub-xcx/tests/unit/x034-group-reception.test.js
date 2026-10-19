// X034 团核销码:领队端码刷新倒计时上屏 + 商家端「已接待团」回读与结算前撤销。
//
// 负控:
//  · group-code 页 startCountdown 不再 setData countdownText ⇒ 「出码后倒计时上屏」红;
//  · normalizeReception 不看 settled ⇒ 「已进入结算的记录不给撤销入口」红;
//  · 商家页不判 canVerify ⇒ 「无扫码权限不发列表请求」红。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const { formatGroupCodeCountdown } = require('../../utils/group-code-session.js');
const { normalizeReceptions, checkRevokeReason } = require('../../utils/group-reception.js');

function mountPage(rel, extraRequires) {
  const requests = [];
  const navigations = [];
  const intervals = [];
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    sendRequest(o) { requests.push(o); },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  };
  const file = path.resolve(ROOT, rel);
  let definition;
  const wx = {
    showToast() {}, navigateTo(o) { navigations.push(o.url); }, navigateBack() {},
    redirectTo(o) { navigations.push(o.url); }, switchTab(o) { navigations.push(o.url); },
    reLaunch(o) { navigations.push(o.url); },
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    getSystemInfoSync: () => ({}),
  };
  global.getApp = () => app;
  global.wx = wx;
  vm.runInNewContext(read(rel), {
    getApp: () => app,
    Page(p) { definition = p; },
    getCurrentPages: () => [{}, {}],
    setInterval(fn) { intervals.push(fn); return intervals.length; },
    clearInterval() {}, setTimeout(fn) { if (fn) fn(); return 1; }, clearTimeout() {},
    console, JSON, Math, Object, String, Number, Array, Date,
    require(id) {
      if (extraRequires && extraRequires[id]) return extraRequires[id];
      return require(path.resolve(path.dirname(file), id));
    },
    wx,
  }, { filename: rel });
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback.call(this); },
  });
  return { page, requests, intervals, navigations };
}

test('倒计时文案:m:ss 后自动刷新,非正数不出文案', () => {
  assert.equal(formatGroupCodeCountdown(300), '5:00 后自动刷新');
  assert.equal(formatGroupCodeCountdown(61), '1:01 后自动刷新');
  assert.equal(formatGroupCodeCountdown(9), '0:09 后自动刷新');
  assert.equal(formatGroupCodeCountdown(0), '');
  assert.equal(formatGroupCodeCountdown(-3), '');
  assert.equal(formatGroupCodeCountdown('abc'), '');
});

test('领队出码后倒计时上屏,每秒递减,到点重出码', () => {
  const { page, requests, intervals } = mountPage('pages/club/group-code/index.js');
  page.onLoad({ activityId: '30' });
  requests[0].success({ code: 200, data: { code: 'C1', qrcodeUrl: 'https://oss.test/qr.png', ttlMs: 3000 } });
  assert.equal(page.data.state, 'ready');
  assert.equal(page.data.countdownText, '0:03 后自动刷新');
  intervals[0]();
  assert.equal(page.data.countdownText, '0:02 后自动刷新');
  intervals[0]();
  intervals[0]();
  assert.equal(requests.length, 2, '倒计时归零应重新签发团码');
  assert.equal(page.data.countdownText, '', '重出码期间不显示旧倒计时');

  const wxml = read('pages/club/group-code/index.wxml');
  assert.match(wxml, /countdownText="\{\{state === 'ready' \? countdownText : ''\}\}"/);
  const sheet = read('pages/club/components/club-game-code-sheet/index.wxml');
  assert.match(sheet, /wx:if="\{\{state === 'ready' && countdownText\}\}"[^>]*class="cgcs__countdown"/);
});

test('接待记录归一:已进入结算的记录不给撤销入口,非法行丢弃,非数组判错误', () => {
  const items = normalizeReceptions([
    { id: 1, topicName: '城墙夜游', activityName: '周六场', activityStartText: '2026-10-10 19:00', leaderName: '阿青', verifyTimeText: '2026-10-10 19:30', settled: 0 },
    { id: 2, topicName: '', topicId: 9, settled: 1 },
    { id: 'x' },
    null,
  ]);
  assert.equal(items.length, 2);
  assert.equal(items[0].canRevoke, true);
  assert.equal(items[0].activityText, '周六场 · 2026-10-10 19:00');
  assert.equal(items[0].statusText, '结算前可撤销');
  assert.equal(items[1].canRevoke, false);
  assert.equal(items[1].topicName, '主题 #9');
  assert.equal(items[1].statusText, '已进入结算，不可撤销');
  assert.equal(normalizeReceptions({ rows: [] }), null);
});

test('撤销原因必填且不超过 200 字', () => {
  assert.equal(checkRevokeReason('  '), '请填写撤销原因');
  assert.equal(checkRevokeReason('误扫'), '');
  assert.match(checkRevokeReason('字'.repeat(201)), /200/);
});

function mountReception() {
  const modalCalls = [];
  const toasts = [];
  const mounted = mountPage('pages/merchant/group-reception/index.js', {
    '../../../utils/modal.js': { show(o) { modalCalls.push(o); } },
    '../../../utils/toast.js': (msg) => toasts.push(msg),
    '../../../utils/merchant-theme.js': { merchantPageShow() {}, merchantPageRestore() {} },
  });
  return Object.assign(mounted, { modalCalls, toasts });
}

const VERIFY_ACCESS = { active: true, roleCode: 'MERCHANT_CHECKIN', permissions: ['merchant:verify'], merchant: { id: 11, name: '店' } };

test('商家无扫码权限:不请求接待列表,落无权限态', () => {
  const { page, requests } = mountReception();
  page.onShow();
  assert.equal(requests[0].url, '/api/merchant/access/me');
  requests[0].success({ code: 200, data: { active: true, roleCode: 'MERCHANT_FINANCE', permissions: ['merchant:finance:read'], merchant: { id: 11, name: '店' } } });
  assert.equal(page.data.accessState, 'no-permission');
  assert.equal(requests.length, 1);
});

test('商家回读接待列表并在结算前撤销:带原因提交,成功后重新回读', () => {
  const { page, requests, modalCalls, toasts } = mountReception();
  page.onShow();
  requests[0].success({ code: 200, data: VERIFY_ACCESS });
  assert.equal(page.data.accessState, 'ready');
  assert.equal(requests[1].url, '/api/verify/groupcode/receptions');
  requests[1].success({ code: 200, data: [{ id: 5, topicName: 'A', settled: 0 }, { id: 6, topicName: 'B', settled: 1 }] });
  assert.equal(page.data.listState, 'ready');
  assert.equal(page.data.items.length, 2);

  page.onRevoke({ currentTarget: { dataset: { id: 6 } } });
  assert.equal(modalCalls.length, 0, '已进入结算的记录点了也不弹撤销');

  page.onRevoke({ currentTarget: { dataset: { id: 5 } } });
  assert.equal(modalCalls.length, 1);
  assert.equal(modalCalls[0].editable, true);
  assert.equal(modalCalls[0].validate('  '), '请填写撤销原因');
  modalCalls[0].success({ confirm: true, content: ' 扫错了团 ' });
  assert.equal(requests.length, 2, '填写原因后仍须确认撤销后果');
  assert.equal(modalCalls[1].dangerKey, 'merchant.group-reception.revoke');
  modalCalls[1].success({ confirm: true });
  const revoke = requests.find((r) => r.url === '/api/verify/groupcode/revoke');
  assert.deepEqual(JSON.parse(JSON.stringify(revoke.data)), { verifyId: 5, reason: '扫错了团' });
  revoke.success({ code: 200, msg: '已撤销该团的接待记录' });
  assert.equal(toasts[0], '已撤销该团的接待记录');
  assert.equal(requests[requests.length - 1].url, '/api/verify/groupcode/receptions', '撤销后重新回读');
});

test('撤销后果确认取消不提交，原接待列表保留', () => {
  const { page, requests, modalCalls } = mountReception();
  page.onShow();
  requests[0].success({ code: 200, data: VERIFY_ACCESS });
  requests[1].success({ code: 200, data: [{ id: 5, topicName: 'A', settled: 0 }] });
  page.onRevoke({ currentTarget: { dataset: { id: 5 } } });
  modalCalls[0].success({ confirm: true, content: '误扫' });
  const action = require('../../utils/danger-actions.js').DANGER_ACTIONS[modalCalls[1].dangerKey];
  assert.equal(action.irreversible, true);
  assert.ok(action.consequences.some((row) => row.text.includes('此操作不可撤销')));
  modalCalls[1].success({ confirm: false, cancel: true });
  assert.equal(requests.length, 2);
  assert.equal(page.data.items[0].id, 5);
  assert.equal(page.data.revokingId, 0);
});

test('服务端拒绝撤销(已进入结算)时以服务端文案为准并重新回读', () => {
  const { page, requests, toasts } = mountReception();
  page.onShow();
  requests[0].success({ code: 200, data: VERIFY_ACCESS });
  requests[1].success({ code: 200, data: [{ id: 5, topicName: 'A', settled: 0 }] });
  page.submitRevoke(5, '误扫');
  requests[2].success({ code: 500, msg: '该主题已进入结算，接待记录不能再撤销；如有错误请联系平台客服' });
  assert.match(toasts[0], /已进入结算/);
  assert.equal(requests[3].url, '/api/verify/groupcode/receptions');
});

test('商家工作台「更多」里有已接待团入口,与扫码同权限', () => {
  const wxml = read('pages/merchant/index/index.wxml');
  assert.match(wxml, /wx:if="\{\{merchantAccess\.canVerify\}\}"[^>]*data-action="group-reception"/);
  const js = read('pages/merchant/index/index.js');
  assert.match(js, /goGroupReception\(\) \{\s*if \(!this\.data\.merchantAccess\.canVerify\) return;\s*wx\.navigateTo\(\{ url: '\/pages\/merchant\/group-reception\/index' \}\);/);
  const appJson = JSON.parse(read('app.json'));
  const merchantPkg = (appJson.subPackages || appJson.subpackages).find((p) => p.root === 'pages/merchant');
  assert.ok(merchantPkg.pages.includes('group-reception/index'));
});
