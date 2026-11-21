// 2026-11-21 主人走查「游戏要顺、主题一致」:编辑页试玩(先看再选)与共享入场动效的回归钉。
// 每条对应 lead 在 DevTools 里实测到的一处坏:没有试玩、空白屏、英文示例、店家请客口吻、首屏要等 3 秒。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');
const preview = require('../../pages/publish/utils/publish/advanced-game-preview.js');
const config = require('../../pages/publish/utils/publish/advanced-game-config.js');

/** 与 pages/publish/temp/index.js previewGameFromSheet 同一条链:范例配置 → kit,建不出来再要范例屏 */
function sheetPreview(key) {
  const draft = catalog.applyToConfig(config.defaultConfig(), key, { demo: true });
  return draft && (preview.buildPreviewKit(draft, { title: '', limitSeconds: 0 }) || preview.buildSampleKit(key));
}

test('选择玩法里每一个玩法都能试玩(含原来没有试玩的 10 个)', () => {
  const keys = catalog.ALL.map(item => item.key);
  for (const key of ['photoCheck', 'qaShot', 'objectCard', 'compass', 'steps', 'blindTaste', 'album', 'bingo', 'classify', 'stopwatch']) {
    assert.ok(keys.includes(key), key);
  }
  for (const key of keys) assert.ok(sheetPreview(key), key + ' 没有试玩');
});

test('「预览」不因玩法在当前主题不可选就消失;没有 pickKey 的(九宫格)按标签 key 试玩', () => {
  const wxml = read('pages/publish/temp/index.wxml');
  const pill = wxml.match(/<view class="cg-r5-pill"[^>]*bindtap="previewGameFromSheet"[^>]*>/);
  assert.ok(pill, '选择玩法底栏要有预览');
  assert.doesNotMatch(pill[0], /sheetPickItem\.reason/, '不可选的原因只拦「完成」,不拦预览');
  assert.match(pill[0], /data-key="\{\{ sheetPickItem\.pickKey \|\| sheetPickItem\.key \}\}"/);
});

test('相册 / 找东西 / 拍照审核 / 拍物成卡的范例屏有真内容,不是空框', () => {
  const album = sheetPreview('album');
  assert.equal(album.type, 'album');
  assert.ok(album.images.length >= 2);
  const hidden = sheetPreview('hidden');
  assert.match(hidden.imageUrl, /^\/pages\/publish\/images\/sample-window\.svg$/);
  assert.ok(fs.existsSync(path.join(ROOT, hidden.imageUrl)), '范例图要真的在包里');
  assert.deepEqual(hidden.targets.map(t => t.label), ['钥匙', '茶杯', '黑猫']);
  assert.equal(hidden.targets.some(t => 'x' in t || 'y' in t), false, '坐标仍不进 kit');
  const shot = sheetPreview('photoCheck');
  assert.equal(shot.lastUrl, hidden.imageUrl, '拍照审核演「已经拍了一张」的模拟输入');
  const own = preview.buildPreviewKit(catalog.applyToConfig(config.defaultConfig(), 'photoCheck'));
  assert.equal(own.lastUrl, '', '作者自己的配置不演范例照片');
  const card = sheetPreview('objectCard');
  assert.ok(card.title && card.cardTitle);
});

test('拍照 / 传感器玩法的试玩挂「试玩 · 不计成绩」小标,别的玩法不挂', () => {
  for (const key of ['photoCheck', 'qaShot', 'compass', 'steps', 'stopwatch', 'shout', 'quiet', 'shake', 'react']) {
    assert.equal(preview.isSimulatedKit(sheetPreview(key)), true, key);
  }
  for (const key of ['qaPick', 'branch', 'countdown', 'random', 'match', 'bingo']) {
    assert.equal(preview.isSimulatedKit(sheetPreview(key)), false, key);
  }
  const wxml = read('pages/publish/temp/index.wxml');
  assert.match(wxml, /class="cg-pv-sim" wx:if="\{\{ pvSimulated \}\}"[^>]*>试玩 · 不计成绩</);
  const js = read('pages/publish/temp/index.js');
  assert.equal((js.match(/pvSimulated: advancedGamePreview\.isSimulatedKit\(pvKit\)/g) || []).length, 2);
});

test('逐字猜词范例是中文,抛硬币范例不写成店家请客', () => {
  const hangman = sheetPreview('hangman');
  assert.ok(hangman.slots.length >= 2);
  for (const slot of hangman.slots.filter(s => s.ch)) assert.match(slot.ch, /\p{Script=Han}/u);
  assert.doesNotMatch(read('pages/publish/utils/publish/node-game-catalog.js'), /ENDLESS|这杯店家请/);
  const coin = sheetPreview('coin');
  assert.doesNotMatch(coin.headsAction + coin.tailsAction, /店家|请客|请下一杯/);
  assert.doesNotMatch(read('pages/publish/temp/index.wxml'), /这杯店家请/);
});

test('入场动效不再错峰也不再起步延时:统一 200ms token,首帧即内容', () => {
  const css = read('pages/playkit/style/play-surface.wxss');
  const rule = css.match(/\.pe\.pe \{[\s\S]*?\n\}/)[0];
  assert.doesNotMatch(rule, /--pe-d|--pe-i|--pe-unfold|--pe-dur/, '各屏零件上的延时 / 错峰 / 长时长不再生效');
  assert.match(rule, /var\(--cy-motion-fade-swap, 200ms\)/);
  assert.doesNotMatch(css.replace(/\/\*[\s\S]*?\*\//g, ''), /--pe-dur:/, '效果行不再各自拉长时长');
  assert.match(css, /\.pk-in \{\s*animation: pk-in var\(--cy-motion-fade-swap, 200ms\)/);
  const tokens = read('style/tokens.wxss');
  assert.match(tokens, /--cy-motion-fade-swap: 200ms;/);
});

test('标准主按钮 .g-btn 走 cy 主按钮 token,字色不读内嵌时可能透明的 --bg', () => {
  const css = read('pages/playkit/style/play-surface.wxss');
  const rule = css.match(/\.g-btn \{[\s\S]*?\n\}/)[0];
  assert.match(rule, /background: var\(--lk-a, var\(--cy-comp-btn-primary-bg, #FFF\)\);/);
  assert.match(rule, /color: var\(--lk-bg, var\(--cy-comp-btn-primary-fg, #111114\)\);/);
  assert.doesNotMatch(rule.replace(/\/\*[\s\S]*?\*\//g, ''), /var\(--bg\b|--pk-embed-bg/);
});

test('选项问答首帧就有题和选项:不再有 1 秒的 intro 遮罩和选项延时', () => {
  const wxml = read('pages/playkit/components/playkit-qa/index.wxml');
  const js = read('pages/playkit/components/playkit-qa/index.js');
  const wxss = read('pages/playkit/components/playkit-qa/index.wxss');
  assert.doesNotMatch(wxml, /qa--intro|qa__cta--hold|--pe-d:/);
  assert.doesNotMatch(js, /INTRO_MS|_introTimer|intro:/);
  assert.doesNotMatch(wxss, /qa--intro|qa__cta--hold/);
});
