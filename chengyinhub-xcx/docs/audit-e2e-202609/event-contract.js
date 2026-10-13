#!/usr/bin/env node
/*
 * 审查探针（只读）：组件 triggerEvent ↔ 宿主 bind: 契约对账。
 * 上轮三条接缝根因里「事件契约」没有 lint，本脚本是补的那把尺子的重建件。
 *
 * 输出一类：
 *   UNBOUND  组件会抛某事件，但所有用到它的宿主都没 bind:
 * 用法：node docs/audit-e2e-202609/event-contract.js
 * 注意：刻意不用 Node 全局（process 等），免得把 lint:scope 自己搞红。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const SKIP_DIRS = new Set([
  'node_modules', 'docs', 'tests', 'scripts', 'artifacts', 'ci', 'miniapp',
  'images', 'audio', 'i18n', 'coverage', 'miniprogram_npm', '.git',
]);

function walk(target, out = []) {
  if (!fs.existsSync(target)) return out;
  if (!fs.statSync(target).isDirectory()) { out.push(target); return out; }
  for (const name of fs.readdirSync(target)) {
    const p = path.join(target, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name.startsWith('.') || SKIP_DIRS.has(name)) continue;
      walk(p, out);
    } else out.push(p);
  }
  return out;
}

const all = [];
walk(ROOT, all);
if (!all.length) throw new Error('扫描结果为空 — 检查 ROOT/SKIP_DIRS 是否指错目录');

const wxmls = all.filter((p) => p.endsWith('.wxml'));
const jsAll = all.filter((p) => p.endsWith('.js'));

function read(p) { try { return fs.readFileSync(p, 'utf8'); } catch (_) { return ''; } }
// 注释内容清空但保留换行，保证行号与磁盘一致
function blank(src) { return src.replace(/[^\n]/g, ''); }
function stripComments(src) {
  return src
    .replace(/<!--[\s\S]*?-->/g, blank)
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/^\s*\/\/.*$/gm, blank);
}

// usingComponents：tag -> 绝对（相对 ROOT）目录/文件基名
function usingComponents(wxmlPath) {
  const jsonPath = wxmlPath.replace(/\.wxml$/, '.json');
  const map = {};
  let data = {};
  try { data = JSON.parse(read(jsonPath) || '{}'); } catch (_) { data = {}; }
  const uc = Object.assign({}, data.usingComponents || {});
  // 注意：componentPlaceholder 的值是「降级用的内置标签名」（如 "view"），不是组件路径，不能并进来
  for (const [tag, p] of Object.entries(uc)) {
    let abs;
    if (p.startsWith('/')) abs = path.join(ROOT, p);
    else if (p.startsWith('.')) abs = path.resolve(path.dirname(wxmlPath), p);
    else abs = path.resolve(ROOT, p); // 自定义基础库/构建别名，解析不出→当外部组件忽略
    if (!fs.existsSync(abs) && !fs.existsSync(abs + '.js')) continue; // 解析不到真实文件就不进账，避免退化成整个父目录
    map[tag] = abs;
  }
  return map;
}

// 组件目录里真正会抛的事件
const firedCache = new Map();
function firedEvents(componentBase) {
  if (firedCache.has(componentBase)) return firedCache.get(componentBase);
  const set = new Map(); // event -> [file:line]
  let dir = componentBase;
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) dir = path.dirname(componentBase);
  const files = jsAll.filter((p) => p.startsWith(dir + path.sep));
  for (const f of files) {
    const src = stripComments(read(f));
    const re = /triggerEvent\s*\(\s*['"]([A-Za-z0-9_-]+)['"]/g;
    let m;
    while ((m = re.exec(src))) {
      const line = src.slice(0, m.index).split('\n').length;
      if (!set.has(m[1])) set.set(m[1], []);
      set.get(m[1]).push(`${path.relative(ROOT, f)}:${line}`);
    }
  }
  firedCache.set(componentBase, set);
  return set;
}

// 每个组件被哪些宿主使用、宿主绑了哪些事件
const usedBy = new Map(); // componentBase -> { hosts: Map<hostRel, Set<event>>, used: n }
function noteUse(componentBase, hostRel, boundEvents) {
  if (!usedBy.has(componentBase)) usedBy.set(componentBase, { hosts: new Map(), count: 0 });
  const rec = usedBy.get(componentBase);
  rec.count += 1;
  if (!rec.hosts.has(hostRel)) rec.hosts.set(hostRel, new Set());
  for (const e of boundEvents) rec.hosts.get(hostRel).add(e);
}

const TAG_RE = /<([a-zA-Z][\w-]*)/g;
/* ⚠️ 冒号是可选的：WXML 里 `bind:tap` 与 `bindtap` 完全等价（catch 同理，还有 capture- 前缀）。
   只认带冒号的写法会把 `catchtap="goMemberProfile"` 判成「没接」，凭空造出一批假阳性。 */
const ATTR_RE = /(?:capture-)?(?:bind|catch)\s*:?\s*([A-Za-z0-9_-]+)\s*=/g;

/* 从标签名后面截取属性串。不能非贪婪撞到第一个 '>'：
   `wx:if="{{count > 0}}"` 里的 '>' 在 {{}} 内，撞上去会把后面的 bind: 整段截丢。 */
function attrsOf(src, from) {
  let depth = 0;
  for (let i = from; i < src.length; i++) {
    const c = src[i];
    if (c === '{') depth++;
    else if (c === '}') depth--;
    else if (c === '>' && depth <= 0) return src.slice(from, i);
  }
  return src.slice(from);
}

let dynamicTags = 0;
for (const w of wxmls) {
  const src = stripComments(read(w));
  if (!src) continue;
  const uc = usingComponents(w);
  const hostRel = path.relative(ROOT, w);
  if (/<component\s+is\s*=/.test(src)) dynamicTags += 1;
  let m;
  TAG_RE.lastIndex = 0;
  while ((m = TAG_RE.exec(src))) {
    const tag = m[1];
    if (!(tag in uc)) continue;
    const attrs = attrsOf(src, m.index + tag.length + 1);
    const bound = new Set();
    let a;
    ATTR_RE.lastIndex = 0;
    while ((a = ATTR_RE.exec(attrs))) bound.add(a[1]);
    noteUse(uc[tag], hostRel, bound);
  }
}

const findings = [];
for (const [componentBase, rec] of usedBy) {
  const fired = firedEvents(componentBase);
  if (!fired.size) continue;
  const boundAnywhere = new Set();
  for (const set of rec.hosts.values()) for (const e of set) boundAnywhere.add(e);
  const relComp = path.relative(ROOT, componentBase);
  for (const [evt, sites] of fired) {
    if (boundAnywhere.has(evt)) continue;
    findings.push({
      component: relComp,
      event: evt,
      firedAt: sites,
      hosts: [...rec.hosts.keys()].sort(),
      hostCount: rec.hosts.size,
    });
  }
}

findings.sort((a, b) => a.component.localeCompare(b.component) || a.event.localeCompare(b.event));

function label(comp) { return path.basename(comp) === 'index' ? path.basename(path.dirname(comp)) : path.basename(comp); }

console.log(`扫描 wxml ${wxmls.length} / js ${jsAll.length}；<component is> 动态引用文件 ${dynamicTags} 个（未展开）`);
console.log(`组件抛了但没有任何宿主 bind: 的事件：${findings.length} 条\n`);
for (const f of findings) {
  console.log(`[UNBOUND] <${label(f.component)}> @ ${f.component}`);
  console.log(`   event : ${f.event}`);
  console.log(`   fired : ${f.firedAt.join(', ')}`);
  console.log(`   hosts : ${f.hostCount} 处 → ${f.hosts.join(' | ')}`);
  console.log('');
}
