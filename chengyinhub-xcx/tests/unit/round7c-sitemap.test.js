'use strict';
// 第七轮 C 第 7 项:sitemap 只放公开内容页,其余(商家后台、俱乐部管理、提现、发票、订单、个人页)不收录。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const sitemap = require('../../sitemap.json');
const app = require('../../app.json');

const routes = new Set(app.pages.concat(...app.subPackages.map((pkg) => pkg.pages.map((page) => pkg.root + '/' + page))));

function assertParamsSchema(rules) {
  rules.forEach((rule) => {
    assert.ok(rule.params === undefined || Array.isArray(rule.params), `${rule.page}: params must be an array`);
  });
}

function decide(url) {
  const page = url.split('?')[0];
  for (const rule of sitemap.rules) {
    // 本文件只使用无参数规则,不模拟微信按参数名匹配的语义。
    assert.equal(rule.params, undefined);
    if (rule.page !== '*' && rule.page !== page) continue;
    return rule.action;
  }
  return 'allow';
}

test('官方 sitemap params 类型为数组,拒绝按参数值匹配的对象', () => {
  assert.doesNotThrow(() => assertParamsSchema([{ page: 'example', params: ['tab'] }]));
  assert.throws(() => assertParamsSchema([{ page: 'example', params: { tab: 'about' } }]), /params must be an array/);
  assertParamsSchema(sitemap.rules);
});

test('最后一条兜底 disallow *,每条 allow 都是已注册路由', () => {
  assert.deepEqual(sitemap.rules.at(-1), { action: 'disallow', page: '*' });
  sitemap.rules.filter((rule) => rule.page !== '*').forEach((rule) => assert.ok(routes.has(rule.page), rule.page));
});

test('公开内容页允许收录:主题、玩法、活动、俱乐部、官方路线和协议', () => {
  ['pages/index/index', 'pages/topic/index/index', 'pages/templatedetail/templatedetail', 'pages/activity/list/index',
    'pages/templatedetail/topic-detail/index', 'pages/templatedetail/topic-preview/index',
    'pages/activity/detail/index', 'pages/activity/official-detail/index', 'pages/club/detail/index',
    'subpackageRoam/official-routes/index', 'subpackageRoam/official-route/index', 'subpackageRoam/poi-detail/index',
    'pages/privacy/index', 'pages/agreement/index']
    .forEach((page) => assert.equal(decide(page), 'allow', page));
});

test('userinfo 任意参数均不收录,包括商家 about 和个人 mine', () => {
  assert.ok(!sitemap.rules.some((rule) => rule.action === 'allow' && rule.page === 'pages/userinfo/userinfo'));
  ['', '?userId=9', '?userId=9&tab=about', '?tab=mine', '?tab=anything&arbitrary=value']
    .forEach((query) => assert.equal(decide('pages/userinfo/userinfo' + query), 'disallow', query));
});

test('私有页不收录:所有商家后台、俱乐部管理、提现、发票、订单、个人中心', () => {
  const privatePages = [...routes].filter((page) => /^pages\/merchant\/(?!profile)|^pages\/club\/(?!detail)|^pages\/coop\/|tixian|withdraw|invoice|^subpackageMember\/|^subpackageA\/|^subpackageB\/|^pages\/publish\/|^pages\/shezhi\/|^pages\/member\//.test(page));
  assert.ok(privatePages.length > 60);
  privatePages.forEach((page) => assert.equal(decide(page), 'disallow', page));
});
