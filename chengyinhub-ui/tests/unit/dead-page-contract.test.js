const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
const repositoryRoot = path.resolve(uiRoot, '..');

function exists(relativePath) {
  return fs.existsSync(path.join(repositoryRoot, relativePath));
}

function read(relativePath) {
  return fs.readFileSync(path.join(repositoryRoot, relativePath), 'utf8');
}

function javaSources(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) return javaSources(absolutePath);
    return entry.isFile() && entry.name.endsWith('.java') ? [absolutePath] : [];
  });
}

function sourceFiles(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(absolutePath);
    return entry.isFile() ? [absolutePath] : [];
  });
}

test('删除同时缺少路由、菜单权限、后台接口和外部引用的 node/item 生成器残页', () => {
  for (const resource of ['node', 'item']) {
    assert.equal(exists(`chengyinhub-ui/src/views/business/${resource}/index.vue`), false);
    assert.equal(exists(`chengyinhub-ui/src/api/business/${resource}.js`), false);
  }

  const router = read('chengyinhub-ui/src/router/index.js');
  assert.doesNotMatch(router, /views\/business\/(node|item)(?:\/index)?/);

  const uiSources = sourceFiles(path.join(repositoryRoot, 'chengyinhub-ui/src'))
    .map(filename => fs.readFileSync(filename, 'utf8'))
    .join('\n');
  assert.doesNotMatch(uiSources, /@\/api\/business\/(node|item)|business\/(node|item)\/index|["']\/business\/(node|item)(?:\/|["'])/);

  const backendSources = javaSources(path.join(repositoryRoot, 'chengyinhub-admin/src/main/java'))
    .map(filename => fs.readFileSync(filename, 'utf8'))
    .join('\n');
  assert.doesNotMatch(backendSources, /@RequestMapping\(\s*["']\/business\/(node|item)["']\s*\)/);

  const menuMigrations = fs.readdirSync(path.join(repositoryRoot, 'chengyinhub-admin/sql'))
    .filter(filename => filename.endsWith('.sql'))
    .map(filename => read(`chengyinhub-admin/sql/${filename}`))
    .join('\n');
  assert.doesNotMatch(menuMigrations, /business\/(node|item)\/index|business:(node|item):(list|query|add|edit|remove|export)/);
});

test('有业务存活证据的 couponhistory、betagame 与 world 页面不得误删', () => {
  for (const resource of ['couponhistory', 'betagame', 'world']) {
    assert.equal(exists(`chengyinhub-ui/src/views/business/${resource}/index.vue`), true);
  }

  assert.equal(exists('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/SmsCouponHistoryController.java'), true);
  assert.equal(exists('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsBetaGameController.java'), true);
  assert.equal(exists('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/IpWorldController.java'), true);

  const router = read('chengyinhub-ui/src/router/index.js');
  assert.match(router, /views\/business\/betagame\/edit/);

  const worldMigration = read('chengyinhub-admin/sql/migration_p3_phase_b_player_world_creator.sql');
  assert.match(worldMigration, /business\/world\/index/);
  assert.match(worldMigration, /business:world:list/);
});
