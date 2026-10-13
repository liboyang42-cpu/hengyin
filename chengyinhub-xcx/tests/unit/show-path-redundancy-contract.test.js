// 仅迁移 profile 死请求清理；首页角色复判保持最新主线，分类缓存通过运行测试覆盖。
'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

test('B1 「我的」整摞刷新不再包含零消费的推荐请求，死字段不得回来', () => {
  const js = read('subpackageMember/components/cy/profile/index.js');
  const wxml = read('subpackageMember/components/cy/profile/index.wxml');
  assert.doesNotMatch(js, /\/api\/recommendation\/list/,
    'aiTip 全仓零消费，这条请求每次 onShow 都要白跑一遍');
  assert.doesNotMatch(js, /loadRecommendations/, '推荐路线 loader 不得复活');
  assert.doesNotMatch(js, /\baiTip\b/, 'aiTip 字段（含 data 初值）不得复活');
  assert.doesNotMatch(js, /\btodayStats\b/, 'todayStats 零消费字段不得复活');
  assert.doesNotMatch(wxml, /aiTip|todayStats/, '两者在 WXML 里本来就没有消费点，别新挂上去');
});

test('B2 其余 11 条自我视角请求一条都不能少（只砍死请求，不砍刷新语义）', () => {
  const js = read('subpackageMember/components/cy/profile/index.js');
  const from = js.indexOf('refreshAll: function');
  const body = js.slice(from, js.indexOf('loadBanner: function', from));
  assert.ok(from > 0 && body.length > 0, '找不到 refreshAll 的请求分流块');
  [
    'this.loadUserData()', 'this.loadRoleInfo()', 'this.loadGrowthBundle()',
    'this.loadThemes()', 'this.loadMedals()', 'this.loadMerchantAccess()',
    'this.loadJoinData()', 'this.loadPointsStat()', 'this.loadOrderPreview()',
    'this.loadProjectPreview()', 'this.loadProjectPending()',
  ].forEach((call) => {
    assert.ok(body.includes(call), `refreshAll 少了 ${call} ⇒ tab 页回访会看不见站外写入的新数据`);
  });
});
