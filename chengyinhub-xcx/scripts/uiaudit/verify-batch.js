#!/usr/bin/env node
'use strict';

/**
 * 一批截图拍完之后的**批次判决**:这批图到底可不可信。
 *
 * 三条判据,每条都对应一次真事故:
 *  1) **md5 两两互异** —— mp.screenshot 会返回**陈旧帧**。2026-07-31 实测:4 个数值已证不同的态
 *     拍出来 md5 全同。它不是"少了一张",是**反向制造假 bug**(照着那批图推断出的"手记黑屏"
 *     病因根本不成立)。⇒ 出现重复 md5,**整批作废重拍**,不是删掉重复那张。
 *  2) **viewNodeCount 非 0** —— 为 0 说明拍到的是编译错误图。并行截图那次 9 张全是同一张编译
 *     错误图,而截图脚本自己一声不吭。
 *  3) **矩阵里该有的都在盘上** —— 少一张就是漏拍;漏拍在"全绿"里是看不见的。
 *  4) **★两张图肉眼是不是同一屏(2026-08-19 新增)** —— 判据①只认字节相同,而截图里有
 *     **每分钟都在变的状态栏时钟**、每次重绘都不完全一致的**原生地图瓦片**。实测
 *     B12-play-classic 与 B17-play-flight-cta:同一路由、fixture 没兑现 ⇒ 拍的是同一屏,
 *     4.4% 的像素因为地图重绘而不同、md5 当然不同 ⇒ **判据①判绿**。
 *     A32 与 A34 更直白:除了状态栏那 92 个时钟像素以外逐字节相同,md5 仍不同。
 *     ⇒ 加一条**感知判据**:裁掉状态栏后降采样成 16×32 色块签名,mean/max 差都低于阈值
 *     即判「同一屏」。阈值实测有两个数量级的余量(同屏 mean ≤0.08 / 异屏 mean ≥4.5)。
 *
 * ⚠️ 本脚本**不启动 DevTools、不拍图**,只读产物目录 + manifest 判决。
 *
 * 用法:node scripts/uiaudit/verify-batch.js <截图目录>
 *       node scripts/uiaudit/verify-batch.js --selftest
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { readPng } = require('../lib/png.js');

function md5(file) {
  return crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');
}

// 状态栏(含每分钟变的时钟)在 624x1352 的截图里占 y<60;实测 A32/A34 的时钟像素落在 y 31..46。
// 裁掉它再比,才不会因为「隔了一分钟拍的」把同一屏算成两屏。
const STATUS_BAR_PX = 60;
// ⚠️ 网格分辨率是被实测逼上去的,别再往下调:
//   16×32 时 D02(待处理)/D03(已结束)被判成同一屏 —— 两张只差一张卡里的订单号与状态文字
//   (3011 个像素、占 0.36%),粗网格把它平均没了。**误判「不同的两态是同一屏」比不判还糟**:
//   它会逼人去重拍一对本来就正确的图。64×128 下这对的 max 差是 40.4,与真同屏拉开。
const SIG_GX = 64;
const SIG_GY = 128;
// 阈值按两类实测数据取(网格 64×128,均已裁掉状态栏):
//   同一屏:A32/A34(只差时钟) 0/0 · B01/B03 0.021/2.5 · B01/B04 0.022/2.6 · B12/B17 0.187/14.3
//           —— B12/B17 是最坏情况:同一屏,但底下的原生地图每次重绘不完全一致(4.4% 像素、均值 19)
//   不同屏:D02/D03 0.100/40.4(只差一张卡的文字) · D02/D04 5.5/248 · A10/A12 12.3/236.8
// ⇒ mean 在这两类上不可分(0.187 > 0.100),真正分得开的是 max。取 20:高于地图噪声的 14.3,
//   低于最小的一处真差异 40.4。
const SIG_MEAN_MAX = 0.5;
const SIG_MAX_MAX = 20;

/** 裁掉状态栏后降采样成 16×32 色块均值签名。图解不开(不是 PNG/异常格式)则返回 null,由调用方降级。 */
function contentSignature(file) {
  let img;
  try { img = readPng(file); } catch (e) { return null; }
  const y0 = img.height > STATUS_BAR_PX * 2 ? STATUS_BAR_PX : 0;
  const h = img.height - y0;
  if (h <= 0 || img.width <= 0) return null;
  const sum = new Float64Array(SIG_GX * SIG_GY * 3);
  const cnt = new Float64Array(SIG_GX * SIG_GY);
  for (let y = y0; y < img.height; y += 1) {
    const gy = Math.min(SIG_GY - 1, Math.floor(((y - y0) * SIG_GY) / h));
    for (let x = 0; x < img.width; x += 1) {
      const gx = Math.min(SIG_GX - 1, Math.floor((x * SIG_GX) / img.width));
      const i = gy * SIG_GX + gx;
      const p = img.px(x, y);
      sum[i * 3] += p[0]; sum[i * 3 + 1] += p[1]; sum[i * 3 + 2] += p[2];
      cnt[i] += 1;
    }
  }
  const sig = new Float64Array(SIG_GX * SIG_GY * 3);
  for (let i = 0; i < SIG_GX * SIG_GY; i += 1) {
    const c = cnt[i] || 1;
    sig[i * 3] = sum[i * 3] / c; sig[i * 3 + 1] = sum[i * 3 + 1] / c; sig[i * 3 + 2] = sum[i * 3 + 2] / c;
  }
  return sig;
}

function signatureDistance(a, b) {
  let total = 0;
  let max = 0;
  for (let i = 0; i < a.length; i += 1) {
    const d = Math.abs(a[i] - b[i]);
    total += d;
    if (d > max) max = d;
  }
  return { mean: total / a.length, max };
}

function sameScreen(a, b) {
  const d = signatureDistance(a, b);
  return d.mean < SIG_MEAN_MAX && d.max < SIG_MAX_MAX;
}

/** 按「肉眼同一屏」分组。O(n^2) 但 n 只有几百,别为它引入索引结构。 */
function groupBySignature(entries) {
  const groups = [];
  entries.forEach((e) => {
    const hit = groups.find((g) => sameScreen(g.sig, e.sig));
    if (hit) hit.files.push(e.file);
    else groups.push({ sig: e.sig, files: [e.file] });
  });
  return groups.filter((g) => g.files.length > 1).map((g) => ({ files: g.files }));
}

function collectPngs(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  fs.readdirSync(dir, { withFileTypes: true }).forEach((e) => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...collectPngs(p));
    else if (e.isFile() && e.name.toLowerCase().endsWith('.png')) out.push(p);
  });
  return out;
}

/** 从批次目录里读所有 manifest*.json,合出 file -> viewNodeCount。 */
function readNodeCounts(dir) {
  const counts = new Map();
  if (!fs.existsSync(dir)) return counts;
  fs.readdirSync(dir).filter((f) => /^manifest.*\.json$/.test(f)).forEach((f) => {
    let json;
    try { json = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')); } catch (e) { return; }
    const shots = json.shots || (json.result && json.result.shots) || [];
    (Array.isArray(shots) ? shots : []).forEach((s) => {
      if (s && s.file) counts.set(s.file, s.viewNodeCount);
    });
  });
  return counts;
}

function verify(dir, expectedFiles) {
  const pngs = collectPngs(dir);
  const byMd5 = new Map();
  pngs.forEach((p) => {
    const h = md5(p);
    if (!byMd5.has(h)) byMd5.set(h, []);
    byMd5.get(h).push(path.basename(p));
  });
  const duplicateGroups = [...byMd5.entries()].filter(([, files]) => files.length > 1)
    .map(([h, files]) => ({ md5: h, files }));

  // 判据④:md5 不同、但肉眼是同一屏(时钟/地图瓦片这类噪声撑开了字节差)。
  const exactDup = new Set(duplicateGroups.flatMap((g) => g.files));
  const sigEntries = [];
  const undecodable = [];
  pngs.forEach((p) => {
    const file = path.basename(p);
    const sig = contentSignature(p);
    if (!sig) { undecodable.push(file); return; }
    sigEntries.push({ file, sig });
  });
  const nearDuplicateGroups = groupBySignature(sigEntries)
    // 已经被判据①点名的那几张不重复报;只报「md5 骗过了①」的那些组。
    .map((g) => ({ files: g.files.filter((f) => !exactDup.has(f)) }))
    .filter((g) => g.files.length > 1);

  const counts = readNodeCounts(dir);
  const zeroNode = pngs.map((p) => path.basename(p))
    .filter((f) => counts.has(f) && !(Number(counts.get(f)) > 0));
  const noCount = pngs.map((p) => path.basename(p)).filter((f) => !counts.has(f));

  const onDisk = new Set(pngs.map((p) => path.basename(p)));
  const missing = (expectedFiles || []).filter((f) => !onDisk.has(f));

  const verdict = duplicateGroups.length === 0 && nearDuplicateGroups.length === 0
    && zeroNode.length === 0 && missing.length === 0
    ? 'OK' : 'VOID';
  return { dir, shot: pngs.length, duplicateGroups, nearDuplicateGroups, undecodable, zeroNode, noCount, missing, verdict };
}

function expectedFromMatrix() {
  try {
    const { SHOTS } = require('../shot-matrix.js');
    return SHOTS.filter((s) => !s.blocked).map((s) => s.file);
  } catch (e) { return []; }
}

// ---------- 自证 ----------
function runSelfTest() {
  const { writePng } = require('../lib/png.js');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'uiaudit-verify-'));
  // 判据④要按像素解图 ⇒ 自证样本必须是**真 PNG**,不能再拿 'AAA' 当图。
  // 高 80 > STATUS_BAR_PX*2,裁剪逻辑才真的走到;时钟画在 y<60 的状态栏带里。
  // ⚠️ 尺寸必须用真实视口 624×1352:判据是「按块求均值」,块的大小直接决定它对噪声的容忍度。
  //    拿一张 64×256 的小图做负控,每块只盖 1.5 个像素、均值根本没发生,噪声用例会假红 ——
  //    那样的自证测的是另一个判据,不是线上跑的这个。
  const W = 624; const H = 1352;
  const png = (name, bodyRgb, clockRgb) => writePng(path.join(tmp, name), W, H,
    (x, y) => (y < 46 && x < 90 ? clockRgb : bodyRgb));   // 时钟按实测位置:y 31..46、左上角
  // 同一屏 + 原生地图重绘噪声:必须仍判同屏。噪声形状照实测的 B12/B17 造 ——
  //   **稀疏 + 低频**:约 1/31 的 25×25 色块整体偏移 ±14,其余逐字节相同。
  //   (逐像素高频抖动不是地图的样子 —— 块内一平均就抵消,那种负控恒绿=白写;
  //    而每块都偏移是「换了配色」,本来就该判两屏。)实测本样本 mean 0.44 / max 14,
  //   贴着真实的 B12/B17(0.187 / 14.3):谁把 max 阈值收到 14 以下,这条立刻红。
  const clamp = (v) => Math.max(0, Math.min(255, v));
  const tileNoise = (x, y, k) => {
    const px = Math.floor(x / 25); const py = Math.floor(y / 25);
    if ((px * 7 + py * 13) % 31 !== 0) return 0;
    return ((px * 3 + py * 5 + k) % 2 ? 1 : -1) * 14;
  };
  const pngNoisy = (name, bodyRgb) => writePng(path.join(tmp, name), W, H,
    (x, y) => (y < 46 && x < 90 ? [255, 255, 255]
      : [clamp(bodyRgb[0] + tileNoise(x, y, 0)), clamp(bodyRgb[1] + tileNoise(x, y, 1)), clamp(bodyRgb[2] + tileNoise(x, y, 2))]));
  // 不同屏,但差异只有一小块高对比文字(D02/D03 的形状):必须判成两屏。
  //   30×20 的区域里按 2/5 画横笔画 = 240 个像素(占 0.03%),比真实的 D02/D03
  //   (3011 像素、0.36%)还小一个数量级 —— 故意从严。实测 64×128 下 max=50;
  //   退回 16×32 粗网格只剩 18.8(<20 ⇒ 误判成同一屏),这条就是防降分辨率的看门狗。
  const pngWithGlyph = (name, bodyRgb) => writePng(path.join(tmp, name), W, H,
    (x, y) => (y < 46 && x < 90 ? [255, 255, 255]
      : (y >= 600 && y < 620 && x >= 200 && x < 230 && (y % 5) < 2 ? [245, 245, 245] : bodyRgb)));
  const w = (name, bytes) => fs.writeFileSync(path.join(tmp, name), Buffer.from(bytes));

  // 绿:三张内容互不相同、manifest 里 viewNodeCount 都 > 0、期望文件都在
  const BODY_A = [120, 120, 120];
  png('a.png', BODY_A, [255, 255, 255]);
  png('b.png', [60, 60, 60], [255, 255, 255]);
  png('c.png', [230, 230, 230], [255, 255, 255]);
  const restoreB = () => png('b.png', [60, 60, 60], [255, 255, 255]);
  fs.writeFileSync(path.join(tmp, 'manifest.json'), JSON.stringify({
    shots: [{ file: 'a.png', viewNodeCount: 10 }, { file: 'b.png', viewNodeCount: 11 }, { file: 'c.png', viewNodeCount: 12 }],
  }));
  const green = verify(tmp, ['a.png', 'b.png', 'c.png']);

  // 红①陈旧帧:b 拍成了和 a 一样的内容(逐字节相同)
  fs.copyFileSync(path.join(tmp, 'a.png'), path.join(tmp, 'b.png'));
  const dup = verify(tmp, ['a.png', 'b.png', 'c.png']);
  restoreB();

  // ★红④「时钟骗过 md5」:b 的正文与 a 完全一样,只有状态栏(时钟)不同。
  //   md5 一定不同 ⇒ 判据①看不见;判据④必须点名。这条就是本判据存在的理由。
  png('b.png', BODY_A, [0, 0, 0]);
  const nearDup = verify(tmp, ['a.png', 'b.png', 'c.png']);
  const nearDupMissedByMd5 = md5(path.join(tmp, 'a.png')) !== md5(path.join(tmp, 'b.png'));
  restoreB();

  // ★绿④负控的另一半:正文真的不同(哪怕只差一档灰)就不许判成同一屏,
  //   否则这条判据会把「相邻状态」全部误杀,比不判还糟。
  png('b.png', [BODY_A[0] + 24, BODY_A[1], BODY_A[2]], [255, 255, 255]);
  const nearOk = verify(tmp, ['a.png', 'b.png', 'c.png']);
  restoreB();

  // ★红④之二:同一屏 + 全屏低幅零均值噪声(原生地图每次重绘不完全一致)仍必须判同屏 —— B12/B17 的形状。
  pngNoisy('b.png', BODY_A);
  const noisyDup = verify(tmp, ['a.png', 'b.png', 'c.png']);
  restoreB();

  // ★绿④之二:只差一小块高对比文字就必须判成两屏 —— D02/D03 的形状,
  //   16×32 的粗网格会把它误判成同一屏,这条用例就是那次误判的看门狗。
  pngWithGlyph('b.png', BODY_A);
  const glyphOk = verify(tmp, ['a.png', 'b.png', 'c.png']);
  restoreB();

  // 红②编译错误图:c 的 viewNodeCount 是 0
  fs.writeFileSync(path.join(tmp, 'manifest.json'), JSON.stringify({
    shots: [{ file: 'a.png', viewNodeCount: 10 }, { file: 'b.png', viewNodeCount: 11 }, { file: 'c.png', viewNodeCount: 0 }],
  }));
  const zero = verify(tmp, ['a.png', 'b.png', 'c.png']);
  fs.writeFileSync(path.join(tmp, 'manifest.json'), JSON.stringify({
    shots: [{ file: 'a.png', viewNodeCount: 10 }, { file: 'b.png', viewNodeCount: 11 }, { file: 'c.png', viewNodeCount: 12 }],
  }));

  // 红③漏拍:期望里多一张盘上没有的
  const miss = verify(tmp, ['a.png', 'b.png', 'c.png', 'd.png']);

  fs.rmSync(tmp, { recursive: true, force: true });

  const checks = {
    '绿:互异 + 有节点数 + 不缺图 ⇒ OK': green.verdict === 'OK',
    '★红①陈旧帧:重复 md5 ⇒ 整批 VOID 并点名':
      dup.verdict === 'VOID' && dup.duplicateGroups.length === 1 && dup.duplicateGroups[0].files.length === 2,
    '★红②编译错误图:viewNodeCount=0 ⇒ VOID 并点名':
      zero.verdict === 'VOID' && zero.zeroNode.length === 1 && zero.zeroNode[0] === 'c.png',
    '★红③漏拍:矩阵里有盘上没有 ⇒ VOID 并点名':
      miss.verdict === 'VOID' && miss.missing.length === 1 && miss.missing[0] === 'd.png',
    '三条判据互相独立(绿态下三个明细都为空)':
      green.duplicateGroups.length === 0 && green.zeroNode.length === 0 && green.missing.length === 0,
    '★红④时钟骗过 md5:字节不同但同一屏 ⇒ VOID 并点名':
      nearDupMissedByMd5 && nearDup.verdict === 'VOID'
      && nearDup.duplicateGroups.length === 0
      && nearDup.nearDuplicateGroups.length === 1
      && nearDup.nearDuplicateGroups[0].files.slice().sort().join(',') === 'a.png,b.png',
    '★绿④正文真不同(Δ24/255)不许判成同一屏':
      nearOk.verdict === 'OK' && nearOk.nearDuplicateGroups.length === 0,
    '★红④同一屏 + 地图那种全屏低幅噪声 ⇒ 仍判同一屏':
      noisyDup.verdict === 'VOID' && noisyDup.nearDuplicateGroups.length === 1
      && noisyDup.nearDuplicateGroups[0].files.slice().sort().join(',') === 'a.png,b.png',
    '★绿④只差一小块高对比文字(D02/D03 形状)⇒ 必须判成两屏':
      glyphOk.verdict === 'OK' && glyphOk.nearDuplicateGroups.length === 0,
    '判据④绿态下无解不开的图': green.undecodable.length === 0,
  };
  const failed = Object.keys(checks).filter((k) => !checks[k]);
  if (failed.length) {
    console.error('verify-batch 自证失败 ->', failed.join(' / '));
    process.exit(1);
  }
  console.log('verify-batch 自证通过(' + Object.keys(checks).length + ' 项,四条判据各有红用例)');
}

if (require.main === module) {
  if (process.argv.includes('--selftest')) { runSelfTest(); process.exit(0); }
  const dir = process.argv[2];
  if (!dir) { console.error('用法: node scripts/uiaudit/verify-batch.js <截图目录>'); process.exit(2); }
  const res = verify(dir, expectedFromMatrix());
  console.log(`目录 ${res.dir}:盘上 ${res.shot} 张`);
  if (res.noCount.length) console.log(`  ${res.noCount.length} 张在 manifest 里查不到 viewNodeCount(判不了,不静默:${res.noCount.slice(0, 5).join(', ')}${res.noCount.length > 5 ? ' 等' : ''})`);
  if (res.duplicateGroups.length) {
    console.error(`  ★ ${res.duplicateGroups.length} 组重复 md5 —— 陈旧帧,**整批作废重拍**:`);
    res.duplicateGroups.forEach((g) => console.error(`    ${g.md5}  ${g.files.join(' == ')}`));
  }
  if (res.nearDuplicateGroups.length) {
    console.error(`  ★ ${res.nearDuplicateGroups.length} 组「md5 不同但肉眼同一屏」—— 状态没换/陈旧帧被时钟或地图噪声掩盖:`);
    res.nearDuplicateGroups.forEach((g) => console.error(`    ${g.files.join(' ≈ ')}`));
  }
  if (res.undecodable.length) console.log(`  ${res.undecodable.length} 张解不开、判据④跳过(不静默):${res.undecodable.slice(0, 5).join(', ')}`);
  if (res.zeroNode.length) console.error(`  ★ ${res.zeroNode.length} 张 viewNodeCount=0 —— 编译错误图:${res.zeroNode.join(', ')}`);
  if (res.missing.length) console.error(`  ★ 漏拍 ${res.missing.length} 张:${res.missing.slice(0, 10).join(', ')}${res.missing.length > 10 ? ' 等' : ''}`);
  console.log('批次判决:' + res.verdict);
  process.exit(res.verdict === 'OK' ? 0 : 1);
}

module.exports = { verify, collectPngs, readNodeCounts, expectedFromMatrix, contentSignature, sameScreen };
