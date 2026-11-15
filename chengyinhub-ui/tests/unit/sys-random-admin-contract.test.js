const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
const repoRoot = path.resolve(uiRoot, '..');

function read(rel) {
  return fs.readFileSync(path.join(repoRoot, rel), 'utf8');
}

test('rewardDrop 与 taskPool API 及管理页面声明完整且权限一致', () => {
  const rewardApi = read('chengyinhub-ui/src/api/business/rewardDrop.js');
  const poolApi = read('chengyinhub-ui/src/api/business/taskPool.js');
  assert.match(rewardApi, /\/business\/reward-drop\/rules\/list/);
  assert.match(rewardApi, /\/business\/reward-drop\/base-prob/);
  assert.match(rewardApi, /\/business\/reward-drop\/preview/);
  assert.match(poolApi, /\/business\/node-task-pool\/list/);
  assert.match(poolApi, /\/business\/node-task-pool\/items/);

  const rewardView = read('chengyinhub-ui/src/views/business/rewarddrop/index.vue');
  const poolView = read('chengyinhub-ui/src/views/business/nodetaskpool/index.vue');
  assert.match(rewardView, /business:topicnode:edit/);
  assert.match(rewardView, /previewDropProbabilities/);
  assert.match(poolView, /@\/api\/business\/taskPool/);
  assert.match(poolView, /saveTaskPoolItem/);

  const sql = read('chengyinhub-admin/sql/migration_20261123_sys_random.sql');
  assert.match(sql, /'business\/rewarddrop\/index'/);
  assert.match(sql, /'business\/nodetaskpool\/index'/);
  assert.match(sql, /reward\.drop\.base\.prob\.bp/);
});
