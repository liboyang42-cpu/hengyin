'use strict';
// 第七轮 C 第 7 项:sitemap 只放公开内容页,其余(商家后台、俱乐部管理、提现、发票、订单、个人页)不收录。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const sitemap = require('../../sitemap.json');
const app = require('../../app.json');

const routes = new Set(app.pages.concat(...app.subPackages.map((pkg) => pkg.pages.map((page) => pkg.root + '/' + page))));

function decide(page, params) {
  for (const rule of sitemap.rules) {
    if (rule.page !== '*' && rule.page !== page) continue;
    if (rule.params && !Object.keys(rule.params).every((key) => params && String(params[key]) === String(rule.params[key]))) continue;
    return rule.action;
  }
  return 'allow';
}

test('最后一条兜底 disallow *,每条 allow 都是已注册路由', () => {
  assert.deepEqual(sitemap.rules.at(-1), { action: 'disallow', page: '*' });
  sitemap.rules.filter((rule) => rule.page !== '*').forEach((rule) => assert.ok(routes.has(rule.page), rule.page));
});

test('公开内容页允许收录:主题、玩法、活动、俱乐部、商家主页、官方路线', () => {
  ['pages/index/index', 'pages/topic/index/index', 'pages/templatedetail/templatedetail', 'pages/activity/list/index',
    'pages/activity/detail/index', 'pages/activity/official-detail/index', 'pages/club/detail/index',
    'subpackageRoam/official-routes/index', 'subpackageRoam/official-route/index', 'subpackageRoam/poi-detail/index']
    .forEach((page) => assert.equal(decide(page, {}), 'allow', page));
  assert.equal(decide('pages/userinfo/userinfo', { userId: '9', tab: 'about' }), 'allow', '商家主页 canonical');
  assert.equal(decide('pages/userinfo/userinfo', { userId: '9' }), 'disallow', '普通个人主页不收录');
});

test('私有页不收录:所有商家后台、俱乐部管理、提现、发票、订单、个人中心', () => {
  const privatePages = [...routes].filter((page) => /^pages\/merchant\/(?!profile)|^pages\/club\/(?!detail)|^pages\/coop\/|tixian|withdraw|invoice|^subpackageMember\/|^subpackageA\/|^subpackageB\/|^pages\/publish\/|^pages\/shezhi\/|^pages\/member\//.test(page));
  assert.ok(privatePages.length > 60);
  privatePages.forEach((page) => assert.equal(decide(page, {}), 'disallow', page));
});
