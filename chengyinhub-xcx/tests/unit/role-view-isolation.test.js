// G-4 / P-46 / P-54(owner 2026-10-27 真机走查 p89、p95):玩家视角不请求商家工作台接口,
// 也不展示「商家身份无效或权限不足」等商家报错。闸在请求层(request-client + role-view-filter),
// 不靠页面逐个打补丁。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequestClient } = require('../../utils/transport/request-client.js');
const filter = require('../../utils/transport/role-view-filter.js');

const ROOT = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function makeClient(playerView) {
  const calls = [];
  const toasts = [];
  const client = createRequestClient({
    wxRequest: (opts) => { calls.push(opts); },
    getBaseUrl: () => 'https://base',
    getAuthorization: () => 'TOK',
    getUserID: () => '7',
    showToast: (m) => { toasts.push(m); },
    getErrorMessage: (res, fb) => (res && res.msg) || fb,
    shouldAutoToast: (param, body) => !(param && param.silentError) && !!(body && body.code && body.code != 200),
    isPlayerView: () => playerView,
  });
  return { client, calls, toasts };
}

const DENIAL = { code: 403, msg: '商家身份无效或权限不足' };

test('filter:只认商家工作台接口与商家身份 403', () => {
  assert.equal(filter.isMerchantOnlyUrl('/api/merchant/predict/inbox'), true);
  assert.equal(filter.isMerchantOnlyUrl('/api/merchant/relation-home'), true);
  assert.equal(filter.isMerchantOnlyUrl('/api/coop/mybiz'), true);
  // 玩家也要用的共用接口不拦
  ['/api/coop/list', '/api/merchant/access/me', '/api/merchant/crm/marketing-consents',
    '/api/merchant/public-home', '/api/merchant/list', '/api/coop/finance'].forEach((url) => {
    assert.equal(filter.isMerchantOnlyUrl(url), false, url);
  });
  assert.equal(filter.isMerchantDenial(DENIAL, 200), true);
  assert.equal(filter.isMerchantDenial({ code: 500, msg: '商家身份无效或权限不足' }, 403), true);
  assert.equal(filter.isMerchantDenial({ code: 403, msg: '商家身份状态不可用，请刷新后重试' }, 200), true);
  // 非 403、或非商家身份文案不算
  assert.equal(filter.isMerchantDenial({ code: 500, msg: '商家身份无效或权限不足' }, 200), false);
  assert.equal(filter.isMerchantDenial({ code: 403, msg: '无权操作' }, 200), false);
  assert.equal(filter.isMerchantDenial({ code: 409, msg: '您已注册为商户,一个账户不能同时是商户与俱乐部主理人' }, 200), false);
});

test('玩家视角:商家工作台接口不发请求,异步按 403 静默失败', async () => {
  const { client, calls, toasts } = makeClient(true);
  const seen = [];
  client.send({ url: '/api/merchant/predict/inbox', method: 'POST',
    fail: (body, status) => seen.push(['fail', body, status]),
    complete: (body) => seen.push(['complete', body]) });
  assert.equal(seen.length, 0, '必须异步回调,不能在 send 里同步回');
  await Promise.resolve();
  assert.equal(calls.length, 0);
  assert.equal(toasts.length, 0);
  assert.deepEqual(seen.map((s) => s[0]), ['fail', 'complete']);
  assert.equal(seen[0][2], 403);
  assert.equal(filter.isSuppressed(seen[0][1]), true);
  assert.equal(seen[0][1].msg, '');
});

test('玩家视角:共用接口照发;回了商家身份 403 不 toast、文案清空并打标记', () => {
  const { client, calls, toasts } = makeClient(true);
  let got = null;
  client.send({ url: '/api/coop/list', method: 'POST', success: (body) => { got = body; } });
  assert.equal(calls.length, 1);
  calls[0].success({ statusCode: 200, data: Object.assign({}, DENIAL) });
  assert.equal(toasts.length, 0);
  assert.equal(got.msg, '');
  assert.equal(filter.isSuppressed(got), true);

  // HTTP 403 也一样,不 toast
  client.send({ url: '/api/merchant/access/me', method: 'POST' });
  calls[1].success({ statusCode: 403, data: Object.assign({}, DENIAL) });
  assert.equal(toasts.length, 0);
});

test('商家视角:不拦接口,商家报错照常 toast(商家要知道自己为什么被拒)', () => {
  const { client, calls, toasts } = makeClient(false);
  client.send({ url: '/api/merchant/predict/inbox', method: 'POST' });
  assert.equal(calls.length, 1);
  let got = null;
  client.send({ url: '/api/coop/list', method: 'POST', success: (body) => { got = body; } });
  calls[1].success({ statusCode: 200, data: Object.assign({}, DENIAL) });
  assert.deepEqual(toasts, ['商家身份无效或权限不足']);
  assert.equal(filter.isSuppressed(got), false);
});

test('玩家视角:非商家身份的业务报错不受影响', () => {
  const { client, calls, toasts } = makeClient(true);
  client.send({ url: '/api/club/apply', method: 'POST' });
  calls[0].success({ statusCode: 200, data: { code: 500, msg: '名额已满' } });
  assert.deepEqual(toasts, ['名额已满']);
});

test('生产装配:app.js 把视角交给 roleGuard.isMerchantView,roleGuard 走 identity-policy', () => {
  const app = read('app.js');
  assert.match(app, /isPlayerView:\s*function\s*\(\)\s*\{\s*return !roleGuard\.isMerchantView\(\);\s*\}/);
  const guard = read('utils/roleGuard.js');
  assert.match(guard, /function isMerchantView\(\)[\s\S]*policy\.isMerchantView\(/);
  assert.match(guard, /isMerchantView: isMerchantView/);
});

test('消息页:玩家视角不查商家入口;合作邀请的商家报错按「没有邀请」处理,不出横幅', () => {
  const js = read('subpackageB/pages/im/list/index.js');
  assert.match(js, /roleViewFilter\.isSuppressed\(res\)/);
  assert.match(js, /if \(!this\.data\.isMerchantView\)[^\n]*canSettlePredict: false/);
});
