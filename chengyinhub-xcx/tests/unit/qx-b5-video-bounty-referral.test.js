// qx-b5 §18 A/B 视频悬赏作者端 + §18 I 推广来源凭据。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const vb = require('../../subpackageA/utils/video-bounty-view.js');

const ROOT = path.join(__dirname, '..', '..');

test('§18 A 投稿行:通过后不出取消;未通过才可申诉;失败按码分别说', () => {
  const row = (s) => vb.submissionRow({ id: 1, platform: 'tiktok', sourceUrl: 'https://x', statusLabel: s });
  assert.equal(row('APPROVED').canCancel, false);
  assert.equal(row('APPROVED').canAppeal, false);
  assert.equal(row('REJECTED').canAppeal, true);
  assert.equal(row('UNDER_REVIEW').canCancel, true);
  assert.equal(row('WHAT').status, '状态待确认');
  assert.equal(vb.reasonText('VIDEO_ALREADY_SUBMITTED'), '这条视频已经投过稿');
  assert.equal(vb.reasonText('LINK_VIDEO_ID_MISSING'), '请填写视频页链接，不是主页链接');
  assert.equal(vb.reasonText(undefined, '服务端原话'), '服务端原话', '无码时原样显示后端提示');
});

test('§18 B 应计:恒写「已记录（未开放兑付）」;读取失败不显示 0 元', () => {
  const r = vb.accrualRow({ id: 9, basisViews: 1200, groupBasisViews: 3000, payableAmount: 2.4, currency: 'CNY', payoutState: 0 });
  assert.equal(r.state, '已记录（未开放兑付）');
  assert.equal(r.amount, '¥2.40');
  assert.equal(vb.accrualRow({ id: 9, payoutState: 1, payableAmount: 1 }).state, '已记录（未开放兑付）');
  assert.equal(vb.accrualRow({ id: 9, basisViews: null }).views, '—');
  assert.deepEqual(vb.listView(null, vb.accrualRow), { state: 'error', rows: [] });
  assert.equal(vb.listView([], vb.accrualRow).state, 'empty');
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageA/pages/video-bounty/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /可提现|待提现|已到账/);
});

test('§18 A 表单:只在运营下发的活动链接里出现;必填错误写在字段下方', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageA/pages/video-bounty/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{campaignKey && topicId\}\}"/);
  assert.match(wxml, /class="vb-field__err" wx:if="\{\{fieldError\.field === 'sourceUrl'\}\}"/);
  assert.deepEqual(vb.formError({ platform: 'tiktok', sourceUrl: ' ' }), { field: 'sourceUrl', text: '请填写视频链接' });
  assert.equal(vb.formError({ platform: 'youtube', sourceUrl: 'https://youtu.be/x' }), null);
});

function withStorage(fn) {
  const store = {};
  global.wx = {
    getStorageSync: (k) => store[k] || '',
    setStorageSync: (k, v) => { store[k] = v; },
    removeStorageSync: (k) => { delete store[k]; }
  };
  try { return fn(store); } finally { delete global.wx; }
}

test('§18 I 落地:按账号隔离存 ref;换号失效;分享 path 追加 ref', () => {
  const referral = require('../../utils/commerce/referral.js');
  withStorage((store) => {
    store.user_id = '9004';
    assert.equal(referral.capture({ ref: 'tok%2B1' }), true);
    assert.equal(referral.read().token, 'tok+1');
    store.user_id = '9009';
    assert.equal(referral.read(), null, '换号后不能挂到别人的单');
  });
  assert.equal(referral.withRef('/pages/activity/detail/index?id=9', 'a b'), '/pages/activity/detail/index?id=9&ref=a%20b');
});

test('§18 I claim:成功或任何 reasonCode 终态都清掉;网络失败只重试 1 次;分享凭据失败静默降级', async () => {
  const referral = require('../../utils/commerce/referral.js');
  await withStorage(async (store) => {
    store.user_id = '9004';
    referral.capture({ ref: 'tok' });
    let calls = 0;
    const app = { sendRequest: (o) => { calls++; o.fail(); } };
    referral.claimAfterOrder(app, 74);
    assert.equal(calls, 2);
    assert.ok(referral.read(), '网络失败不清,留给下一次进入原单时的人工路径');
    const app2 = { sendRequest: (o) => o.success({ code: 409, reasonCode: 'ATTRIBUTION_CONFLICT' }) };
    referral.claimAfterOrder(app2, 74);
    assert.equal(referral.read(), null);
    const app3 = { sendRequest: (o) => o.success({ code: 404, reasonCode: 'TARGET_NOT_FOUND' }) };
    const r = await referral.sharePathPromise(app3, 2, 9, '/p?id=9');
    assert.deepEqual(r, { path: '/p?id=9' });
  });
});

test('接线:活动/主题分享带 promise 换 ref;落地 capture;结算建单后旁路 claim', () => {
  const act = fs.readFileSync(path.join(ROOT, 'pages/activity/detail/index.js'), 'utf8');
  const topic = fs.readFileSync(path.join(ROOT, 'pages/topic/index/index.js'), 'utf8');
  const bm = fs.readFileSync(path.join(ROOT, 'pages/activity/baoming/baoming.js'), 'utf8');
  assert.match(act, /referral\.capture\(options\)/);
  assert.match(act, /referral\.sharePathPromise\(getApp\(\), 2,/);
  assert.match(topic, /referral\.sharePathPromise\(app, 1,/);
  assert.match(topic, /referral\.capture\(options\)/);
  assert.match(bm, /referral\.claimAfterOrder\(app, registrationId\)/);
});
