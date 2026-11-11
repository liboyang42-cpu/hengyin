#!/usr/bin/env node
'use strict';
// P-41(f-pages 2026-11-11):官方模板种子的 rule_instructions 整份写成一行「1. … 2. … 3. …」,
// 编辑器按换行拆步骤时只拆出一步。本脚本离线读取种子迁移里的规则原文,用小程序同一份
// utils/rule-steps.js 的 splitRuleSteps 拆成一步一行,生成只改「仍是种子原文」行的 UPDATE。
// 只生成 SQL,不连库。用法:node gen-rule-seed-split-migration.js > ../../../../../chengyinhub-admin/sql/migration_20261111_rule_seed_split_steps.sql
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..', '..', '..', '..', '..');
const { splitRuleSteps } = require(path.join(ROOT, 'chengyinhub-xcx', 'utils', 'rule-steps.js'));

// 种子文件 → 写入的表(规则原文在哪张表就改哪张;商家套用产生的副本也在 cms_member_template)
const SEEDS = [
  ['migration_seed_template_library_12packs_20260820.sql', 'seed_library_12packs_20260820'],
  ['migration_seed_merchant_game_a8_20260823.sql', 'seed_merchant_game_a8_20260823'],
  ['migration_seed_merchant_game_v51_20260827.sql', 'seed_merchant_game_v51_20260827'],
  ['migration_seed_home_solo_node_templates_20260826.sql', 'seed_home_solo_node_templates_20260826'],
];
const INLINE = /^1\s*[.、．)）]\s*\S[\s\S]*[\s。；;，,：:！!？?]2\s*[.、．)）](?!\d)/;

function literals(sql) {
  // 提取 SQL 单引号字面量(支持 '' 与 \' 转义),返回解码后的文本
  const out = [];
  let i = 0;
  while (i < sql.length) {
    if (sql[i] !== "'") { i++; continue; }
    let j = i + 1, buf = '';
    while (j < sql.length) {
      const c = sql[j];
      if (c === '\\' && j + 1 < sql.length) { buf += sql[j + 1] === 'n' ? '\n' : sql[j + 1]; j += 2; continue; }
      if (c === "'" && sql[j + 1] === "'") { buf += "'"; j += 2; continue; }
      if (c === "'") break;
      buf += c; j++;
    }
    out.push(buf); i = j + 1;
  }
  return out;
}
const q = (s) => "'" + s.replace(/\\/g, '\\\\').replace(/'/g, "''").replace(/\n/g, '\\n') + "'";

const lines = [];
lines.push('-- P-41 官方模板种子:玩法规则一步一行(2026-11-11,f-pages)');
lines.push('-- 种子把整份规则写成一行「1. … 2. … 3. …」,编辑器按换行拆步骤只得一步、里面挤着四条。');
lines.push('-- 这里把仍是种子原文的 rule_instructions 改成一步一行(去掉步首序号,与小程序 utils/rule-steps.js 同一规则;');
lines.push('-- 「(运营提示:…)」随末步保留,展示层照旧不出)。只改 WHERE 带原文的行,人工改过的不覆盖;可重复执行。');
lines.push('-- 只有数据,无 DDL。生成脚本:docs/delivery/questo-ui-20261109/f-pages/tools/gen-rule-seed-split-migration.js');
lines.push('-- 执行方式:走 chengyin-db-migrate,只读预检后确认。');
lines.push('');
let total = 0;
const seen = new Set();
const checks = [];
SEEDS.forEach(([file, tag]) => {
  const sql = fs.readFileSync(path.join(ROOT, 'chengyinhub-admin', 'sql', file), 'utf8');
  const rules = literals(sql).filter((s) => INLINE.test(s) && !seen.has(s));
  lines.push('-- ' + file + '(' + rules.length + ' 条)');
  rules.forEach((old) => {
    seen.add(old);
    const next = splitRuleSteps(old).join('\n');
    if (next === old || next.indexOf('\n') < 0) return;
    total += 1;
    ['cms_template_library', 'cms_member_template'].forEach((table) => {
      lines.push('UPDATE ' + table + ' SET rule_instructions = ' + q(next) + ' WHERE rule_instructions = ' + q(old) + ';');
    });
    checks.push(q(old));
  });
  lines.push('');
});
lines.push('-- runner: assert=scalar-true');
lines.push('SELECT (SELECT COUNT(*) FROM cms_template_library WHERE rule_instructions IN (' + checks.join(',\n  ') + ')) = 0');
lines.push('   AND (SELECT COUNT(*) FROM cms_member_template WHERE rule_instructions IN (' + checks.join(',\n  ') + ')) = 0 AS rule_seed_split_done;');
process.stdout.write(lines.join('\n') + '\n');
process.stderr.write('rules rewritten: ' + total + '\n');
