// P-09 / P-10:猜数字要显示题干,交过之后要有一行结果与作者揭示;交之前客户端拿不到真值。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');

function loadEstimate() {
  let def = null;
  const prev = global.Component, prevB = global.Behavior, prevWx = global.wx;
  global.Component = (d) => { def = d; };
  global.Behavior = (d) => d;
  if (!global.wx) global.wx = { getSystemInfoSync: () => ({}), getAppBaseInfo: () => ({}) };
  try {
    delete require.cache[require.resolve('../../pages/play/components/playkit-estimate/index.js')];
    require('../../pages/play/components/playkit-estimate/index.js');
  } finally { global.Component = prev; global.Behavior = prevB; global.wx = prevWx; }
  return def;
}

test('题干从服务端 question 透传成 title;交之前没有真值与揭示', () => {
  const kit = pickPlayKit({ sessionId: 's1', version: 1, playKit: { estimate: {
    question: '海关大楼钟面的直径大约有几米？', unit: '米', min: 1, max: 20, submitted: false, tier: '' } } }, new Date());
  assert.equal(kit.type, 'estimate');
  assert.equal(kit.title, '海关大楼钟面的直径大约有几米？');
  assert.equal(kit.submitted, false);
  assert.equal(kit.reveal, '');
  assert.equal(kit.answer, 0);
});

test('交过之后:估数、真值、揭示透传', () => {
  const kit = pickPlayKit({ sessionId: 's1', version: 2, playKit: { estimate: {
    question: 'Q', unit: '米', min: 1, max: 20, submitted: true, tier: 'MISS', guess: 6, answer: 5.4, reveal: '约 5.4 米。' } } }, new Date());
  assert.equal(kit.submitted, true);
  assert.equal(kit.tier, 'MISS');
  assert.equal(kit.guess, 6);
  assert.equal(kit.answer, 5.4);
  assert.equal(kit.reveal, '约 5.4 米。');
});

test('结果一行:中 / 差一点 / 差得远,带差多少', () => {
  const line = loadEstimate().methods._resultLine;
  assert.equal(line({ submitted: false, tier: '' }), null);
  assert.deepEqual(line({ submitted: true, tier: 'MISS', guess: 6, answer: 5.4, unit: '米', reveal: ' 约 5.4 米。 ' }),
    { head: '差得有点远', detail: '你猜 6 米 · 实际 5.4 米 · 差 0.6 米', reveal: '约 5.4 米。', miss: true });
  assert.equal(line({ submitted: true, tier: 'CLOSE', guess: 6, answer: 5.4, unit: '米' }).head, '差一点');
  const hit = line({ submitted: true, tier: 'HIT', guess: 5, answer: 5, unit: '米' });
  assert.equal(hit.head, '猜中了');
  assert.equal(hit.detail, '正好 5 米');
  assert.equal(hit.miss, false);
});

test('playkit 容器把结果字段接到猜数字组件', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit/index.wxml'), 'utf8');
  assert.match(wxml, /submitted="\{\{kit\.submitted\}\}" tier="\{\{kit\.tier\}\}" guess="\{\{kit\.guess\}\}" answer="\{\{kit\.answer\}\}" reveal="\{\{kit\.reveal\}\}"/);
  const own = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit-estimate/index.wxml'), 'utf8');
  assert.match(own, /\{\{result\.head\}\}/);
  assert.match(own, /\{\{title\}\}/);
});
