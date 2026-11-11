'use strict';
// P-41(f-pages 2026-11-11):官方模板种子规则「一步一行」数据迁移必须与生成脚本输出逐字一致,
// 且拆步规则就是小程序 utils/rule-steps.js 的 splitRuleSteps(单一真源,不另写一套拆法)。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const GEN = path.join(ROOT, 'docs/delivery/questo-ui-20261109/f-pages/tools/gen-rule-seed-split-migration.js');
const SQL = path.join(ROOT, 'chengyinhub-admin/sql/migration_20261111_rule_seed_split_steps.sql');

test('迁移文件 = 生成脚本输出(离线、只读种子原文)', () => {
  const generated = execFileSync(process.execPath, [GEN], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  assert.equal(fs.readFileSync(SQL, 'utf8'), generated);
});

test('只有数据 UPDATE,每条 WHERE 带种子原文(人工改过的不覆盖),无 DDL,已登记依赖', () => {
  const sql = fs.readFileSync(SQL, 'utf8');
  const updates = sql.split('\n').filter((l) => l.startsWith('UPDATE '));
  assert.equal(updates.length, 70, '35 条规则 × 两张表');
  updates.forEach((l) => {
    assert.match(l, /^UPDATE cms_(template_library|member_template) SET rule_instructions = '[^']*\\n[^']*' WHERE rule_instructions = '1\. /);
  });
  assert.doesNotMatch(sql, /\b(ALTER|CREATE|DROP|TRUNCATE|DELETE)\b/);
  assert.match(sql, /-- runner: assert=scalar-true/);
  const deps = JSON.parse(fs.readFileSync(path.join(ROOT, 'ci/migration-dependencies.json'), 'utf8')).dependencies;
  assert.deepEqual(deps['20261111_rule_seed_split_steps'], [
    'seed_template_library_12packs_20260820', 'seed_merchant_game_a8_20260823',
    'seed_merchant_game_v51_20260827', 'seed_home_solo_node_templates_20260826']);
});
