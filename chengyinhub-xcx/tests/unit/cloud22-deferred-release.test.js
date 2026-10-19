// User-requested release exclusion; enable features only after explicit scope review.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const manifest = require('../../../docs/delivery/w2/cloud22-deferred/manifest.json');
test('暂缓22项不得进入原W2候选的页面、客户端、后端或迁移', () => {
  assert.equal(manifest.status, 'DEFERRED_DO_NOT_RELEASE');
  assert.equal(manifest.features.length, 22);
  assert.ok(manifest.excludedSourceFiles.length > 100);
  for (const { path: file } of manifest.excludedSourceFiles) {
    assert.equal(fs.existsSync(path.join(root, file)), false, `暂缓源码被重新带入: ${file}`);
  }
  const app = require('../../app.json');
  const routes = app.pages.concat(app.subPackages.flatMap(pkg => pkg.pages.map(page => pkg.root + '/' + page)));
  assert.equal(routes.length, 132);
  assert.ok(routes.every(route => !route.startsWith('subpackageTools/') && route !== 'pages/expense-split/index'));
  const profile = fs.readFileSync(path.join(root, 'chengyinhub-xcx/subpackageMember/components/cy/profile/index.wxml'), 'utf8');
  assert.doesNotMatch(profile, /goExplorationTools|goExpenseSplit|goPairQuiz|goRelayInvite/);
});
