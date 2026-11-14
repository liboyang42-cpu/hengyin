#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const { discoverUnitTests } = require('./xcx-unit-manifest');
const root = path.resolve(__dirname, '..');
const mini = path.join(root, 'chengyinhub-xcx');
const modules = ['common', 'system', 'framework', 'quartz', 'generator', 'admin'].map(name => 'chengyinhub-' + name);
const moneyPath = file => /(?:registration|pay|settlement|refund|pass|funds|withdraw|balance|cash|wallet|invoice|price|pricing|checkout|order|fee|discount|ticket|coupon|perk|compensation|commission|revenue|transfer|subscription|renew|bill|deposit|receivable|payout)/i.test(file)
  // Shared activity/theme services can change money behavior without a money word in their filename.
  || /^(?:pom\.xml|ci\/test-impact\.js$|chengyinhub-(?:admin|system|common|framework)\/src\/main\/)/.test(file)
  || /\.sql$/i.test(file);

function dependencies(file, seen = new Set()) {
  if (seen.has(file)) return { files: seen, unknown: false };
  seen.add(file);
  if (!fs.existsSync(file) || fs.lstatSync(file).isSymbolicLink()) return { files: seen, unknown: true };
  if (!/\.[cm]?js$/.test(file)) return { files: seen, unknown: false };
  const source = fs.readFileSync(file, 'utf8');
  const literalRequires = [...source.matchAll(/\brequire\s*\(\s*['"]([^'"]+)['"]\s*\)/g)];
  let unknown = /\bimport\b|\brequire\.resolve\s*\(|\b(?:readFile|readdir|exists|stat|access|glob|exec|execFile|spawn)(?:Sync)?\s*\(/.test(source)
    || (source.match(/\brequire\s*\(/g) || []).length !== literalRequires.length
    || literalRequires.some(match => /^(?:node:)?(?:fs|child_process)$/.test(match[1]));
  if (unknown) return { files: seen, unknown };
  for (const match of literalRequires) {
    if (!match[1].startsWith('.')) continue;
    const base = path.resolve(path.dirname(file), match[1]);
    const resolved = [base, base + '.js', base + '.json', path.join(base, 'index.js')].find(p => fs.existsSync(p) && fs.statSync(p).isFile());
    if (!resolved) { unknown = true; continue; }
    if (dependencies(resolved, seen).unknown) return { files: seen, unknown: true };
  }
  return { files: seen, unknown };
}

function selectTests(changed, files = discoverUnitTests(mini)) {
  const global = changed.some(file => /^(?:ci\/|\.github\/|pom\.xml$|package|\.gitignore|chengyinhub-xcx\/(?:app\.|scripts\/|tests\/lint\/|package))/.test(file));
  if (global) return files;
  const absolute = new Set(changed.map(file => path.resolve(root, file)));
  return files.filter(test => {
    if (changed.includes('chengyinhub-xcx/' + test)) return true;
    const testName = path.basename(test, '.test.js');
    if (changed.some(file => path.basename(file).replace(/\.[^.]+$/, '') === testName)) return true;
    const graph = dependencies(path.join(mini, test));
    if (graph.unknown) return true;
    for (const file of graph.files) {
      if (absolute.has(file)) return true;
      const stem = file.replace(/\.[^.]+$/, '');
      if (changed.some(other => path.resolve(root, other).replace(/\.[^.]+$/, '') === stem)) return true;
    }
    return false;
  });
}

function plan(changed, event = 'pull_request') {
  const full = event !== 'pull_request';
  const files = discoverUnitTests(mini);
  const tests = full ? files : selectTests(changed, files);
  const backend = modules.filter(name => changed.some(file => file.startsWith(name + '/')));
  const money = changed.some(moneyPath);
  const backendFull = full || money || changed.some(file => /^(?:pom\.xml|ci\/backend|\.github\/workflows\/ci\.yml)/.test(file));
  return { full, money, changed, tests, totalTests: files.length, backendFull,
    backendModules: backendFull ? modules : backend,
    shards: full ? 20 : Math.max(1, Math.min(20, Math.ceil(tests.length / 30))) };
}

function changedFiles(event, payload) {
  if (event !== 'pull_request') return [];
  const base = payload.pull_request?.base?.sha;
  const head = payload.pull_request?.head?.sha;
  if (![base, head].every(sha => /^[a-f0-9]{40}$/.test(sha || ''))) throw new Error('missing exact PR source identity');
  return cp.execFileSync('git', ['diff', '--name-only', '-z', base + '...' + head], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
}

if (require.main === module) {
  const event = process.env.GITHUB_EVENT_NAME;
  const payload = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const changed = changedFiles(event, payload);
  if (process.argv[2] === 'funds') {
    const funds = event !== 'pull_request' || changed.some(moneyPath);
    if (!process.env.GITHUB_OUTPUT) throw new Error('missing funds routing output');
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `funds=${funds}\n`);
    console.log(`funds scope: ${funds}`);
  } else {
  const result = plan(changed, event);
  const output = process.argv[2] || path.join(root, '.ci-impact.json');
  fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
  if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT,
    ['full', 'money', 'backendFull'].map(key => `${key}=${result[key]}`).join('\n')
    + `\nbackend_modules=${result.backendModules.join(',')}\nmini_shards=${JSON.stringify(Array.from({ length: result.shards }, (_, i) => i + 1))}\n`);
  console.log(`impact: ${result.tests.length}/${result.totalTests} mini files; full=${result.full}; money=${result.money}`);
  }
}
module.exports = { dependencies, moneyPath, plan, selectTests };
