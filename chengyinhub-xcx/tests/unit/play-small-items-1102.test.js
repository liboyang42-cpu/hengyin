// 2026-11-02 走查小项:P-24 拍照上传失败写在屏上;P-25 提示计数与代价;P-26 定位在开始时问、同一局不重问;P-28 城市定向菜单不说「商家」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { puzzleHintLabel } = require('../../pages/play/utils/puzzle-hint-label.js');
const read = (p) => fs.readFileSync(path.join(__dirname, '../../', p), 'utf8');

test('P-25:看过第一条后计数前进,并在点之前写出代价', () => {
  assert.equal(puzzleHintLabel(0, 2), '1/2 · −30 分');
  assert.equal(puzzleHintLabel(1, 2), '2/2 · −30 分');
  assert.equal(puzzleHintLabel(2, 2), '2/2');   // 已看完:不再有代价(按钮本身也会收起)
  const js = read('pages/play/index.js');
  assert.match(js, /hintLabel: puzzleHintLabel\(hints\.length, hintCount\),/);
  assert.doesNotMatch(js, /hintLabel: hints\.length \+ '\/' \+ hintCount,/);
});

test('P-24:普通拍照审核上传失败,屏上写「上传失败，请重拍」,再拍时清掉', () => {
  let def = null;
  const prevC = global.Component, prevB = global.Behavior;
  global.Component = (d) => { def = d; }; global.Behavior = (d) => d;
  try {
    delete require.cache[require.resolve('../../pages/play/components/playkit-photocheck/index.js')];
    require('../../pages/play/components/playkit-photocheck/index.js');
  } finally { global.Component = prevC; global.Behavior = prevB; }
  const ctx = { data: { uploadFail: '', busy: true }, setData(p) { Object.assign(this.data, p); } };
  def.observers.photoFailSeq.call(ctx, 1);
  assert.equal(ctx.data.uploadFail, '上传失败，请重拍');
  assert.equal(ctx.data.busy, false);
  ctx.data.uploadFail = '';
  def.observers.photoFailSeq.call(ctx, 1);   // 同一个序号不重复报
  assert.equal(ctx.data.uploadFail, '');
  const wxml = read('pages/play/components/playkit-photocheck/index.wxml');
  assert.match(wxml, /wx:if="\{\{uploadFail && !verdict\}\}"/);
  assert.match(read('pages/play/components/playkit/index.wxml'), /photo-fail-seq="\{\{kit\.photoFailSeq\}\}"\n  bind:shoot="onShoot" bind:close="onClose" \/>/);
});

test('P-26:开始游戏时问定位;同一局同意过不再重问', () => {
  const js = read('pages/play/index.js');
  assert.match(js, /if \(located\) this\.requestPlayLocation\(\);/);
  assert.match(js, /if \(storyResume\.read\(scope\)\.locationConsent\) \{ locate\(\); return; \}/);
  assert.match(js, /storyResume\.patch\(scope, \{ locationConsent: true \}\);/);
});

test('P-28:城市定向菜单的扫码到达不再写「商家」', () => {
  const wxml = read('pages/play/index.wxml');
  assert.match(wxml, /<text class="dt">扫码到达<\/text><text class="ds">到站后扫现场的码<\/text>/);
  assert.doesNotMatch(wxml, /到店后扫商家的码/);
});
