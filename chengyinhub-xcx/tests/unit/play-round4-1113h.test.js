'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..', '..');
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8');
const pops = require('../../pages/play/utils/play-stage-pops.js').methods();

function page(data, pick) {
  const left = [];
  const p = Object.assign({}, pops, {
    _historyNodes: [], data: Object.assign({ allDone: true, isPreview: false, isMock: false, mode: 1 }, data),
    onPickChapter: pick, goBackDetail() { left.push(1); }, setData(d) { Object.assign(this.data, d); },
  });
  return { p, left };
}
const text = (t) => ({ type: 'text', content: t });
const node = (id) => ({ type: 'node', nodeId: String(id) });

test('1 线性两章:回看打不开(选章被拦)按走完离开,不停在空故事页', () => {
  const x = page({
    chapterCards: [{ id: 1, blocks: [text('一'), node(11)] }, { id: 2, blocks: [text('二'), node(22)] }],
    nodes: [{ nodeId: 11, chapterId: 1, done: true }, { nodeId: 22, chapterId: 2, done: true }],
  }, () => false);
  assert.equal(x.p._reviewAfterFinish(), false);
  assert.equal(x.p._reviewPending, false);
});

test('1 线性两章:回看打开后读不到内容,走 _afterFinishLeave 离开;打开成功后不再误离开', () => {
  const x = page({ chapterFull: true }, () => true);
  x.p._reviewPending = true;
  x.p._afterFinishLeave();
  assert.deepEqual(x.left, [1]);
  assert.equal(x.p.data.chapterFull, false);
  x.p._afterFinishLeave();
  assert.deepEqual(x.left, [1], '只离开一次');
});

test('1 三章+结局的分支路线:停在最后一个有正文的已完成章,选章成功才算回看', () => {
  const picked = [];
  const x = page({
    chapterCards: [{ id: 1, blocks: [text('一'), node(11)] }, { id: 2, blocks: [text('二'), node(22)] },
      { id: 3, blocks: [text('三'), node(33)] }, { id: 4, ending: true, blocks: [] }],
    nodes: [11, 22, 33].map((id, i) => ({ nodeId: id, chapterId: i + 1, done: true })),
  }, (e) => { picked.push(e.currentTarget.dataset.idx); });
  assert.equal(x.p._reviewAfterFinish(), true);
  assert.deepEqual(picked, [2]);
  clearTimeout(x.p._reviewEndT);
  assert.equal(x.p._reviewPending, true);
});

test('1 源码:只读回看没有可读内容时不开空页,离开交给 _afterFinishLeave', () => {
  const src = read('pages/play/index.js');
  assert.match(src, /readOnly && !wasOpen && !items\.some\(\(item\) => item\.kind !== 'cover' && item\.kind !== 'divider'\)/);
  assert.match(src, /this\._chapterReadCursor = null;\n\s+if \(typeof this\._afterFinishLeave === 'function'\) this\._afterFinishLeave\(\);/);
});

const { tidyText, keepMeasure } = require('../../pages/play/utils/keep-numerals.js');
const introLine = (() => {
  const src = read('pages/play/index.js');
  return new Function(src.match(/function introLine\(desc\) \{[\s\S]*?\n\}/)[0] + '; return introLine;')();
})();

test('5 中文标点后的空格去掉,英文句号后保留', () => {
  assert.equal(tidyText('第二页。 这一页').replace(/⁠/g, ''), '第二页。这一页');
  assert.equal(tidyText('好！  再来，  一次').replace(/⁠/g, ''), '好！再来，一次');
  assert.equal(tidyText('Page 2. Next'), 'Page 2. Next');
});

test('4 章节门说明:被截断的一律补省略号,与长短无关', () => {
  assert.equal(introLine('一句完整的话。'), '一句完整的话。');
  assert.equal(introLine('四段长镜头\n第二行还有更多'), '四段长镜头…');
  assert.equal(introLine('四段长镜头'), '四段长镜头');
  const long = '一二三四五六七八九十'.repeat(5);
  assert.equal(introLine(long), long.slice(0, 40) + '…');
  assert.equal(introLine('四段长镜头，后面是同一行里的下半句'), '四段长镜头，后面是同一行里的下半句', '一行内没超长、没有后续段落,不加省略号');
});

test('3 开局太远:数字单位不断行;定位已知且超 300 米时在出任何成功判定前就拦下并给重试', () => {
  const out = keepMeasure('请到起点 300 米内再开始').replace(/⁠/g, '');
  assert.equal(out, '请到起点 300米内再开始');
  assert.ok(/3⁠0⁠0⁠米/.test(keepMeasure('300 米')));
  const src = read('pages/play/index.js');
  assert.match(src, /_startRefusal\(message, retry\)/);
  assert.match(src, /confirmText: '重新定位'/);
  assert.match(src, /const tooFar = this\._startTooFar\(node\);\n\s+if \(tooFar\) \{ this\._startRefusal\(tooFar, \(\) => this\.onArriveTap\(\)\); return; \}/);
  assert.match(src, /const tooFar = this\._startTooFar\(node\);\n\s+if \(tooFar\) \{ this\._startRefusal\(tooFar, \(\) => this\.startStoryGame\(e\)\); return; \}/);
  assert.match(src, /r\.errorCode === 'ARRIVE_TOO_FAR' \|\| r\.reason === 'ARRIVE_TOO_FAR'\) that\._startRefusal/);
  const api = { _startLocationNeeded: () => true, haversine: (a, b, c, d) => Math.hypot(a - c, b - d) * 111000 };
  const tooFar = src.match(/  _startTooFar\(node\) \{[\s\S]*?\n  \},/)[0].replace('  _startTooFar(node) {', 'function f(node) {').replace(/\n  \},$/, '\n}');
  const f = new Function('ARRIVAL_START_RADIUS_METERS', 'ARRIVAL_START_RADIUS_TEXT', tooFar + '; return f;')(300, '300 米');
  const ctx = (data, loc) => Object.assign({ data, _loc: loc }, api);
  const node = { lat: 31, lng: 121 };
  assert.match(f.call(ctx({ mode: 1 }, { latitude: 31.01, longitude: 121 }), node), /离起点太远/);
  assert.equal(f.call(ctx({ mode: 1 }, { latitude: 31.001, longitude: 121 }), node), '');
  assert.equal(f.call(ctx({ mode: 1 }, null), node), '', '没有定位不凭空拦');
});
