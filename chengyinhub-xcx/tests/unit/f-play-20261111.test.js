// f-play(2026-11-11):游戏能玩 —— 三条进入路径走查里修掉的缺陷,每条一组断言。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const shake = require('../../pages/play/utils/play-shake.js');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const STRONG = { x: 1.6, y: -1.1, z: .9 };

function fakeWx() {
  const listeners = new Set();
  const calls = { starts: 0, stops: 0 };
  return { listeners, calls,
    startAccelerometer() { calls.starts++; }, stopAccelerometer() { calls.stops++; },
    onAccelerometerChange(f) { listeners.add(f); }, offAccelerometerChange(f) { listeners.delete(f); },
    emit(r) { [...listeners].forEach((f) => f(r)); } };
}

function mountKit(kind, wx, props) {
  let config;
  const events = [];
  const file = path.join(ROOT, 'pages/play/components/playkit-' + kind + '/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component(value) { config = value; }, wx, Date, Math, setTimeout, clearTimeout,
    require(name) {
      if (name.endsWith('/play-shake.js')) return shake;
      if (name.endsWith('/reduced-motion.js')) return {};
      if (name.endsWith('/motion.js')) return { haptic() {} };
      if (name === './faces.js') return { faceMark() { return ''; }, faceCode() { return ''; }, toDataUri(x) { return x; } };
      if (name.endsWith('/d20-renderer.js')) return { createD20Renderer() { throw new Error('unexpected canvas'); } };
      throw new Error('unexpected dependency: ' + name);
    },
  }, { filename: file });
  const data = JSON.parse(JSON.stringify(config.data));
  for (const [k, v] of Object.entries(config.properties)) data[k] = v.value;
  Object.assign(data, props || {});
  const inst = { data, ...config.methods, setData(p) { Object.assign(this.data, p); }, triggerEvent(n) { events.push(n); } };
  config.lifetimes.attached.call(inst);
  const change = (patch) => {
    Object.assign(data, patch);
    for (const [names, fn] of Object.entries(config.observers)) {
      const keys = names.split(',').map((x) => x.trim());
      if (keys.some((k) => Object.hasOwn(patch, k))) fn.apply(inst, keys.map((k) => data[k]));
    }
  };
  return { events, change, detach() { config.lifetimes.detached.call(inst); } };
}

test('V3-04 共用传感器:第一个持有者开、最后一个放手才关;读数只给最上面那一个', () => {
  const wx = fakeWx();
  const got = [];
  const a = shake.holdAccelerometer(wx, () => got.push('a'));
  const b = shake.holdAccelerometer(wx, () => got.push('b'));
  assert.equal(wx.calls.starts, 1);
  assert.equal(wx.listeners.size, 1);
  wx.emit(STRONG);
  assert.deepEqual(got, ['b']);
  b(); b();
  assert.equal(wx.calls.stops, 0, '还有人持有时不关传感器');
  wx.emit(STRONG);
  assert.deepEqual(got, ['b', 'a']);
  const stale = [...wx.listeners][0];
  a();
  assert.equal(wx.calls.stops, 1);
  assert.equal(wx.listeners.size, 0);
  stale(STRONG);
  assert.deepEqual(got, ['b', 'a'], '放手后排队的旧回调不再送读数');
  assert.equal(shake.holdAccelerometer({}, () => {}), null, '没有传感器接口就不持有(按钮是退路)');
});

for (const kind of ['coinflip', 'diceroll']) {
  test('V3-04 ' + kind + ':流里一份 + 玩法开始弹窗选「全屏」一份,摇一下只动整屏那份;整屏收回后流里那份还能摇', () => {
    const wx = fakeWx();
    const inline = mountKit(kind, wx, { inline: true });
    inline.change({ show: true });
    const full = mountKit(kind, wx);
    full.change({ show: true });
    wx.emit(STRONG);
    const ev = kind === 'coinflip' ? 'flip' : 'roll';
    assert.deepEqual(full.events, [ev]);
    assert.deepEqual(inline.events, [], '盖在下面的那份不跟着抛 / 掷');
    full.change({ show: false });
    full.detach();
    assert.equal(wx.calls.stops, 0, '整屏关掉不能把流里那份的传感器一起关死');
    wx.emit(STRONG);
    assert.deepEqual(inline.events, [ev]);
    inline.detach();
    assert.equal(wx.calls.stops, 1);
    assert.equal(wx.listeners.size, 0);
  });
}

test('顶部缺图:主题开场标题卡后面压主题封面(原型 gp-start),封面链与本页其他底图同一口径', () => {
  const wxml = read('pages/play/index.wxml');
  const intro = wxml.slice(wxml.indexOf('<view class="st-intro '), wxml.indexOf('<view class="st-intro__go"'));
  assert.match(intro, /<view class="st-intro__bg" wx:if="\{\{topicCover \|\| chapter\.cover\}\}" aria-hidden="true"><image class="st-intro__im" src="\{\{topicCover \|\| chapter\.cover\}\}" mode="aspectFill" \/><\/view>/);
  assert.ok(intro.indexOf('st-intro__bg') < intro.indexOf('st-intro__body'), '封面在标题下面一层');
  const wxss = read('pages/play/index.wxss');
  const block = wxss.slice(wxss.indexOf('.st-intro__bg{'), wxss.indexOf('.st-intro__body{'));
  assert.match(block, /\.st-intro__im\{[^}]*opacity:\.55;[^}]*saturate\(\.5\)/);
  assert.match(block, /\.st-intro__body, \.st-intro__go\{ position:relative; \}/, '标题与按钮压在封面之上');
  assert.doesNotMatch(block, /@keyframes|animation:/);
});

test('地点卡距离跟着定位走:开着的卡每一跳重算,走到站上不再写出发时的距离;没变不 setData', () => {
  const pops = require('../../pages/play/utils/play-stage-pops.js');
  const api = pops.methods();
  let dist = '1.7km';
  const writes = [];
  const page = Object.assign({}, api, {
    data: { sheet: { show: true, node: Object.assign({ lat: 31.238, lng: 121.484 }, pops.sheetFacts({}, '1.7km')) } },
    _distText() { return dist; },
    setData(p) { writes.push(p); for (const [k, v] of Object.entries(p)) { const ks = k.split('.'); let o = this.data; ks.slice(0, -1).forEach((x) => { o = o[x]; }); o[ks[ks.length - 1]] = v; } },
  });
  assert.equal(page.data.sheet.node.walkText, '22');
  page._refreshSheetFacts();
  assert.equal(writes.length, 0, '距离没变不写');
  dist = '0m';
  page._refreshSheetFacts();
  assert.deepEqual(writes, [{ 'sheet.node.distText': '0m', 'sheet.node.walkText': '' }]);
  page.data.sheet.show = false; dist = '5m';
  page._refreshSheetFacts();
  assert.equal(writes.length, 1, '卡关着不写');
  const src = read('pages/play/index.js');
  assert.match(src, /if \(distText !== that\.data\.storyDistText\) that\.setData\(\{ storyDistText: distText \}\);\n\s+\}\n\s+if \(typeof that\._refreshSheetFacts === 'function'\) that\._refreshSheetFacts\(\);/, '挂在定位回调里');
});

test('V3-08 选章弹窗不在「没走完才显示」的块里:走完一局后「切换章节」能真的弹出来;城市定向多章也有入口', () => {
  const wxml = read('pages/play/index.wxml');
  const pick = wxml.indexOf('<view class="chpick" wx:if="{{chapterPick.show}}">');
  const gate = '<block wx:if="{{!screen && (!allDone || canContinueGames) && !emptyTip && !loading && !chapterFull}}">';
  const blockStart = wxml.indexOf(gate);
  assert.ok(pick > 0 && blockStart > 0 && pick < blockStart, '选章弹窗在 allDone 守卫块之前,不受它管');
  const before = wxml.slice(0, pick);
  assert.equal((before.match(/<block\b/g) || []).length, (before.match(/<\/block>/g) || []).length, '外面没有未闭合的 block 包着选章弹窗');
  assert.match(wxml, /<view class="drow" wx:if="\{\{mode==2 \|\| chapterCards\.length > 1\}\}" bindtap="openStackOrChaptersFromTools"/);
});

test('V3-09 选章每行带完成态:已完成写「已完成」,未解锁压暗,判据与 onPickChapter 同一个 chapterReadState', () => {
  const wxml = read('pages/play/index.wxml');
  assert.match(wxml, /chapterPick\.states\[index\] === 'locked' \? 'is-locked' : ''/);
  assert.match(wxml, /<text class="chpick__sub">\{\{chapterPick\.states\[index\] === 'completed' \? '已完成 · ' : ''\}\}/);
  assert.match(read('pages/play/index.wxss'), /\.chpick__row\.is-locked\{ opacity:\.4; \}/);
  assert.match(read('pages/play/index.js'), /'chapterPick\.states': this\._chapterPickStates\(\)/);
  const api = require('../../pages/play/utils/play-stage-pops.js').methods();
  const page = Object.assign({}, api, { _historyNodes: [], data: {
    chapterCards: [{ id: 1, blocks: [{ type: 'node', nodeId: '11' }] }, { id: 2, blocks: [{ type: 'node', nodeId: '22' }] }, { id: 3, locked: true }],
    nodes: [{ nodeId: 11, chapterId: 1, done: true }, { nodeId: 22, chapterId: 2, done: false }],
  } });
  assert.deepEqual(page._chapterPickStates(), ['completed', 'available', 'locked']);
});

test('V3-07 走完后完成页「继续」回到故事流只读回看(最后一章已完成的那章,停在末尾往上滑);不是走完的真实局照旧离开', () => {
  assert.match(read('pages/play/index.js'), /!this\.data\.isMock && !this\._reviewAfterFinish\(\)\) this\.goBackDetail\(\);/);
  const api = require('../../pages/play/utils/play-stage-pops.js').methods();
  const mk = (data) => {
    const picked = [];
    const page = Object.assign({}, api, { _historyNodes: [], data: Object.assign({
      allDone: true, isPreview: false, isMock: false, mode: 1, chapterFull: false, chapterParas: [],
      chapterCards: [{ id: 1, blocks: [{ type: 'node', nodeId: '11' }] }, { id: 2, blocks: [{ type: 'node', nodeId: '22' }] }],
      nodes: [{ nodeId: 11, chapterId: 1, done: true }, { nodeId: 22, chapterId: 2, done: true }],
    }, data), onPickChapter(e) { picked.push(e.currentTarget.dataset.idx); }, setData() {} });
    return { page, picked };
  };
  const ok = mk({});
  assert.equal(ok.page._reviewAfterFinish(), true);
  assert.deepEqual(ok.picked, [1], '最后一章已完成的那章');
  clearTimeout(ok.page._reviewEndT);
  for (const bad of [{ allDone: false }, { isPreview: true }, { isMock: true }, { mode: 2 },
    { nodes: [{ nodeId: 11, chapterId: 1, done: false }, { nodeId: 22, chapterId: 2, done: false }] }]) {
    const x = mk(bad);
    assert.equal(x.page._reviewAfterFinish(), false, JSON.stringify(bad));
    assert.deepEqual(x.picked, []);
  }
});

test('V3-05 进度游标看得见:用药丸自己的字色(--lk-bg)而不是与药丸同色系的强调色,随 doneCount/total 伸长', () => {
  const wxss = read('pages/play/play-stage.wxss');
  const pill = /\.theme-gpill \{[^}]*background: var\(--lk-ink, #FFFFFF\); color: var\(--lk-bg, #000000\)/;
  assert.match(wxss, pill);
  const bar = wxss.match(/\.theme-gpill__i \{([^}]*)\}/)[1];
  assert.match(bar, /background: var\(--lk-bg, #000000\)/, '游标与药丸底色对比 = 数字的对比');
  assert.doesNotMatch(bar, /--lk-a/);
  assert.match(bar, /height: 6rpx/);
  assert.match(read('pages/play/index.wxml'), /<view class="theme-gpill__i" style="transform:scaleX\(\{\{doneCount \/ total\}\}\)"><\/view>/);
});
