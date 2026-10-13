#!/usr/bin/env node
/*
 * 截图批次「出身」门禁：一批截图必须全部出自**同一个 commit**，且那个 commit 就是发版 HEAD。
 *
 *   node scripts/verify-shot-provenance.js <截图目录> --expect-head <sha>
 *   node scripts/verify-shot-provenance.js --selftest
 *
 * 为什么需要(2026-08-19 实证,不是假想):
 *   20260818 那批 234 张的四个来源分别是 47b9c7a90(183)/7e08cb706(118)/971db47fb(83)/
 *   072f74583(2),外加 11 张所在 manifest 连 head 都没有。后三个**都不在 master 上**
 *   (被 squash 合并,原 commit 不再是祖先),而当时 master 已是 261b92414 ——
 *   **没有一张代表生产**。据此做的 UI 复核,判据本身是假的:
 *   E05 拍到的多余「重试」和 A42/A37 的「关注 null」在 master 上早已修掉。
 *
 *   而 _shot_full_matrix.js 其实**记了** head(顶层),是 _merge_manifests.js 按 shot id
 *   合并时把它丢了 ⇒ 合并后的产物零溯源。verify-batch 原有的四条判据
 *   (md5 两两不同 / viewNodeCount 非零 / 路由回读 / 注入回读)一条都不看代码版本,
 *   所以这个洞可以一直存在而全绿。
 *
 * 判据(任一不满足即 exit 1):
 *   A. 每个贡献了「存活 shot」的 manifest 必须有顶层 head —— 缺 head 判红,不是跳过。
 *   B. 存活 shot 的 head 必须收敛到唯一一个。
 *   C. 该 head 必须等于 --expect-head。
 * 「存活」= 与 _merge_manifests.js 同一套 last-wins 语义(按 mtime 老的在前、后覆盖前),
 *   否则门禁看的集合和人看的产物不是同一批。
 */
const fs = require('fs');
const path = require('path');

function collect(dir) {
  const files = fs.readdirSync(dir)
    .filter((f) => /^manifest(?:\.[^.]+)?\.json$/.test(f) && f !== 'manifest.merged.json')
    .map((f) => ({ f, p: path.join(dir, f), t: fs.statSync(path.join(dir, f)).mtimeMs }))
    .sort((a, b) => a.t - b.t);
  // last-wins，与 _merge_manifests.js 对齐
  const byId = new Map();
  for (const { f, p } of files) {
    let d;
    try { d = JSON.parse(fs.readFileSync(p, 'utf8')); } catch { continue; }
    if (!d || typeof d !== 'object') continue;
    const head = typeof d.head === 'string' && d.head.trim() ? d.head.trim() : null;
    for (const s of (d.shots || [])) {
      if (s && s.id) byId.set(s.id, { head, manifest: f });
    }
  }
  return byId;
}

function judge(byId, expectHead) {
  const errs = [];
  const missing = [...byId.entries()].filter(([, v]) => !v.head);
  if (missing.length) {
    const mans = [...new Set(missing.map(([, v]) => v.manifest))];
    errs.push(`A. ${missing.length} 张截图所在的 manifest 没有顶层 head，无法追溯代码版本：${mans.join(', ')}`);
  }
  const heads = new Map();
  for (const [id, v] of byId) if (v.head) heads.set(v.head, (heads.get(v.head) || 0) + 1);
  if (heads.size > 1) {
    errs.push(`B. 这批截图跨 ${heads.size} 个 commit：`
      + [...heads.entries()].map(([h, n]) => `${h.slice(0, 9)}(${n}张)`).join(' / ')
      + ' —— 拼接批次不能当作对同一份代码的观测');
  }
  if (expectHead) {
    for (const h of heads.keys()) {
      if (h !== expectHead) {
        errs.push(`C. 截图出自 ${h.slice(0, 9)}，但发版 HEAD 是 ${expectHead.slice(0, 9)} —— 拍的不是要发的那份代码`);
      }
    }
  }
  return errs;
}

function selftest() {
  const cases = [
    ['全绿：单一 head 且等于期望', { a: { head: 'abc', manifest: 'm1' }, b: { head: 'abc', manifest: 'm1' } }, 'abc', 0],
    ['负控 A：有 shot 缺 head 必须红', { a: { head: 'abc', manifest: 'm1' }, b: { head: null, manifest: 'm2' } }, 'abc', 1],
    ['负控 B：跨两个 commit 必须红', { a: { head: 'abc', manifest: 'm1' }, b: { head: 'def', manifest: 'm2' } }, null, 1],
    ['负控 C：单一 head 但不是发版 HEAD 必须红', { a: { head: 'abc', manifest: 'm1' } }, 'zzz', 1],
    ['负控 C2：期望 HEAD 缺失时不得冒充通过', { a: { head: 'abc', manifest: 'm1' } }, null, 0],
  ];
  let bad = 0;
  for (const [name, obj, exp, wantErrs] of cases) {
    const got = judge(new Map(Object.entries(obj)), exp);
    const ok = wantErrs === 0 ? got.length === 0 : got.length >= 1;
    console.log(`${ok ? '✓' : '✗'} ${name}${ok ? '' : ` —— 实得 ${got.length} 条: ${got.join('|')}`}`);
    if (!ok) bad += 1;
  }
  if (bad) { console.error(`selftest 失败 ${bad} 例`); process.exit(1); }
  console.log('selftest 通过：3 条判据各有一个能真的判红的负控');
}

const argv = process.argv.slice(2);
if (argv.includes('--selftest')) { selftest(); process.exit(0); }
const dir = argv.find((a) => !a.startsWith('--'));
const ei = argv.indexOf('--expect-head');
const expectHead = ei >= 0 ? argv[ei + 1] : null;
if (!dir) { console.error('用法: verify-shot-provenance.js <截图目录> [--expect-head <sha>]'); process.exit(2); }
if (!expectHead) console.error('⚠️ 未传 --expect-head：只校验「同一个 commit」，不校验「就是发版那个 commit」');

const byId = collect(dir);
if (!byId.size) { console.error(`${dir} 里没有任何 manifest shot —— 判红，不当作通过`); process.exit(1); }
const errs = judge(byId, expectHead);
if (errs.length) { console.error(`截图出身门禁不通过(${byId.size} 张):`); errs.forEach((e) => console.error('  ✗ ' + e)); process.exit(1); }
console.log(`截图出身门禁通过：${byId.size} 张全部出自 ${[...new Set([...byId.values()].map((v) => v.head))][0].slice(0, 9)}`
  + (expectHead ? '，等于发版 HEAD' : ''));
