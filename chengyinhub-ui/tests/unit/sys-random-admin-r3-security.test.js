const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const compiler = require('vue-template-compiler');

const uiRoot = path.resolve(__dirname, '../..');
const repoRoot = path.resolve(uiRoot, '..');

function read(rel) {
  return fs.readFileSync(path.join(repoRoot, rel), 'utf8');
}

function compileView(rel) {
  const sfc = compiler.parseComponent(read(rel));
  const out = compiler.compile(sfc.template.content);
  assert.deepEqual(out.errors, [], `${rel} template must compile cleanly`);
  return out.ast;
}

function walk(node, visit) {
  if (!node) return;
  visit(node);
  (node.children || []).forEach((c) => walk(c, visit));
  if (node.ifConditions) node.ifConditions.forEach((c) => c.block !== node && walk(c.block, visit));
}

test('HIGH-A1: drop-type selector compiles to three labelled options', () => {
  const ast = compileView('chengyinhub-ui/src/views/business/rewarddrop/index.vue');
  let select = null;
  walk(ast, (n) => {
    if (n.tag === 'el-select' && (n.attrsMap || {})['v-model'] === 'form.dropType') select = n;
  });
  assert.ok(select, 'form.dropType select exists');
  const options = select.children.filter((c) => c.tag === 'el-option');
  assert.equal(options.length, 3);
  const labels = options.map((o) => (o.attrsMap || {}).label);
  assert.deepEqual(labels, [
    '1 - XP倍率 ({"mult":2})',
    '2 - 徽章碎片 ({"badgeCode":"FRAGMENT"})',
    '3 - 商家彩蛋券 ({"couponTemplateId":1})',
  ]);
  assert.deepEqual(options.map((o) => o.attrsMap[':value']), ['1', '2', '3']);
});

function permsInView(rel) {
  return [...read(rel).matchAll(/v-hasPermi="\[([^\]]+)\]"/g)]
    .flatMap((m) => m[1].split(',').map((s) => s.trim().replace(/^'|'$/g, '')));
}

function permsInController(rel) {
  return [...read(rel).matchAll(/hasPermi\('([^']+)'\)/g)].map((m) => m[1]);
}

test('HIGH-A2: drop-rule and task-pool perms are dedicated and registered in sys_menu', () => {
  const sql = read('chengyinhub-admin/sql/migration_20261125_admin_r3_perms.sql');
  const registered = new Set([...sql.matchAll(/'(business:(?:rewarddrop|taskpool):[a-z]+)'/g)].map((m) => m[1]));
  for (const [prefix, view, ctrl] of [
    ['rewarddrop', 'chengyinhub-ui/src/views/business/rewarddrop/index.vue',
      'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/RewardDropAdminController.java'],
    ['taskpool', 'chengyinhub-ui/src/views/business/nodetaskpool/index.vue',
      'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/NodeTaskPoolController.java'],
  ]) {
    const used = [...permsInView(view), ...permsInController(ctrl)];
    assert.ok(used.length > 0);
    for (const perm of used) {
      // The merged drop page also reaches the roadmap rule editor (validate, enable/disable) under
      // business:rewardDrop:*; those are checked below against their own controller and registration.
      if (prefix === 'rewarddrop' && perm.startsWith('business:rewardDrop:')) continue;
      assert.ok(perm.startsWith(`business:${prefix}:`), `${perm} must use the dedicated ${prefix} prefix`);
      assert.ok(registered.has(perm), `${perm} must be inserted into sys_menu`);
    }
  }
  // Roadmap rule-editor perms used on the merged page: checked by RewardDropRuleAdminController and
  // registered case-exactly (sys_menu.perms is case-insensitive) by the perm-union migration.
  const union = read('chengyinhub-admin/sql/migration_20261126_reward_drop_perm_union.sql');
  const unionRegistered = new Set([...union.matchAll(/COLLATE utf8mb4_bin = '(business:(?:rewarddrop|rewardDrop):[a-z]+)'/g)].map((m) => m[1]));
  const editorPerms = new Set(permsInController('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/RewardDropRuleAdminController.java'));
  const pageView = 'chengyinhub-ui/src/views/business/rewarddrop/index.vue';
  for (const perm of [...permsInView(pageView), ...permsInController(
    'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/RewardDropAdminController.java'), ...editorPerms]) {
    assert.ok(unionRegistered.has(perm), `${perm} must be registered case-exactly by the perm-union migration`);
  }
  for (const perm of permsInView(pageView).filter((p) => p.startsWith('business:rewardDrop:'))) {
    assert.ok(editorPerms.has(perm), `${perm} must be a perm the rule-editor controller checks`);
  }
  // Every F row is additive and idempotent.
  const inserts = sql.split(/INSERT INTO/).slice(1);
  assert.ok(inserts.length >= 10);
  for (const stmt of inserts) assert.match(stmt, /NOT EXISTS/);
  assert.doesNotMatch(sql, /\b(DELETE|DROP|UPDATE|TRUNCATE)\b/i);

  const deps = JSON.parse(read('ci/migration-dependencies.json')).dependencies;
  assert.deepEqual(deps['20261125_admin_r3_perms'], ['20261123_sys_random']);
});

test('MED-A1: monetization review page surfaces request errors instead of swallowing them', () => {
  const view = read('chengyinhub-ui/src/views/business/externalgame/monetization.vue');
  assert.doesNotMatch(view, /\.catch\(\s*\(\s*\)\s*=>\s*\{\s*\}\s*\)/);
});
