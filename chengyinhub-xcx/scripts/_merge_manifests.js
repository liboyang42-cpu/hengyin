/*
 * 把一轮验收里分批跑出来的多个 manifest 合并成一份：同一个 shot 取**最后一次**记录。
 *
 *   node scripts/_merge_manifests.js <目录>
 *
 * 为什么需要：DevTools 跑久了会挂（page destroyed / 截图能力失效），一轮 220 张
 * 实测要分 4-5 批才跑得完，每批都覆盖写同一个 manifest.json。不合并就只剩最后
 * 一批的几十条，前面跑成功的全看不见 —— 会误以为「大部分没拍」。
 *
 * 取最后一次而不是取最好一次：中途改过 fixture/selector 的 shot，早先那次的
 * 状态已经作废，取「最好」会把已经修掉的假绿留下来。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { SHOTS } = require('./shot-matrix');

const dir = process.argv[2] || path.join(process.env.HOME, 'Desktop', '城瘾全量截图_20260805');
const files = fs.readdirSync(dir)
  // 任意批次后缀（.partN / .nN / 随手起的名），不只 .partN
  .filter((f) => /^manifest(?:\.[^.]+)?\.json$/.test(f) && f !== 'manifest.merged.json')
  .map((f) => ({ f, p: path.join(dir, f), t: fs.statSync(path.join(dir, f)).mtimeMs }))
  .sort((a, b) => a.t - b.t); // 老的在前，后面的覆盖前面的

const byId = new Map();
const order = [];
for (const { f, p } of files) {
  const m = JSON.parse(fs.readFileSync(p, 'utf8'));
  for (const s of m.shots || []) {
    if (!byId.has(s.id)) order.push(s.id);
    byId.set(s.id, { ...s, _from: f });
  }
}
const shots = order.map((id) => byId.get(id));
const counts = shots.reduce((a, s) => { a[s.status] = (a[s.status] || 0) + 1; return a; }, {});
const out = {
  purpose: '2026-08-10 全量截图矩阵 —— 多批合并（同 shot 取最后一次）',
  mergedFrom: files.map((x) => x.f),
  counts,
  shots,
};
fs.writeFileSync(path.join(dir, 'manifest.merged.json'), JSON.stringify(out, null, 1));
console.log(`合并 ${files.length} 份 → ${shots.length} 张`, JSON.stringify(counts));

// 分批脚本按磁盘续跑；因此最终闸必须逐行复核状态和磁盘内容，否则某次红记录
// 会因 png 已存在而永久跳过。blocked / 行为断言不进截图分母，也不能拿预裁决
// 旧图制造假红。
const actionable = SHOTS.filter((s) => !s.blocked);
const dirs = { player: '玩家版', guest: '玩家版', club: '玩家版', merchant: '商家版' };
const md5of = (file) => crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');
const errors = [];
const hashIds = new Map();
for (const expected of actionable) {
  const row = byId.get(expected.id);
  if (!row) {
    errors.push(`${expected.id}: 缺记录`);
    continue;
  }
  if (row.status !== 'ok') errors.push(`${expected.id}: status=${row.status}`);
  for (const key of ['route', 'state', 'file', 'role']) {
    if (row[key] !== expected[key]) errors.push(`${expected.id}: ${key}=${JSON.stringify(row[key])}，期望 ${JSON.stringify(expected[key])}`);
  }
  const expectedPath = '/' + expected.route.split('?')[0].replace(/^\//, '');
  if (row.actualPath !== expectedPath) errors.push(`${expected.id}: actualPath=${JSON.stringify(row.actualPath)}，期望 ${expectedPath}`);
  if (!row.dom || row.dom.pass !== true) errors.push(`${expected.id}: DOM 状态闸未绿`);
  if (!Number.isFinite(row.viewNodeCount) || row.viewNodeCount <= 0) errors.push(`${expected.id}: viewNodeCount=${row.viewNodeCount}`);
  if (!/^[a-f0-9]{32}$/.test(String(row.md5 || ''))) errors.push(`${expected.id}: md5 缺失或格式错误`);
  if (row.injectUnstable && row.injectUnstable.length) errors.push(`${expected.id}: fixture 未稳定兑现 ${row.injectUnstable.join(',')}`);
  if (row.staleFrame) errors.push(`${expected.id}: staleFrame=${row.staleFrame}`);
  if (row.frame !== 'clean') errors.push(`${expected.id}: frame=${row.frame}`);
  if (Number.isFinite(expected.scrollTop) && (!row.scrollApplied || row.scrollApplied.moved !== true)) {
    errors.push(`${expected.id}: scrollTop=${expected.scrollTop} 未回读到位移`);
  }

  const image = path.join(dir, dirs[expected.role], expected.file);
  if (!fs.existsSync(image) || fs.statSync(image).size <= 0) {
    errors.push(`${expected.id}: 磁盘缺图 ${image}`);
  } else if (/^[a-f0-9]{32}$/.test(String(row.md5 || ''))) {
    const diskMd5 = md5of(image);
    if (diskMd5 !== row.md5) errors.push(`${expected.id}: manifest md5=${row.md5}，磁盘=${diskMd5}`);
  }
  if (row.md5) {
    if (!hashIds.has(row.md5)) hashIds.set(row.md5, []);
    hashIds.get(row.md5).push(expected.id);
  }
}
const duplicateGroups = [...hashIds.entries()].filter(([, ids]) => ids.length > 1);
for (const [hash, ids] of duplicateGroups) errors.push(`重复 md5 ${hash}: ${ids.join(' == ')}`);

for (const error of errors) console.log(`  ⚠️ ${error}`);
if (errors.length) {
  console.log(`actionable 最终闸失败 ${errors.length} 项；重复 ${duplicateGroups.length} 组`);
  process.exitCode = 1;
} else {
  console.log(`actionable ${actionable.length} 张状态/磁盘/md5 闸全绿；md5 全互异，重复 0 组`);
}
