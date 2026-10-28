// 发布流程编辑页头契约。2026-10-10 主人裁定(取代 2026-09-24 CU-M-159 / CU-C-156 的右上 ✕):
// 所有编辑页/弹层页头统一「左 退出(文字) / 右 完成」,✕ 不能代替退出;退出保持原取消语义,完成保持保存语义。
// 证据:交付目录 shots/owner-feedback-sheet-header.png。
// —— 以下为被取代的旧规则原文,留作沿革 ——
// 用户规则:应用弹层的关闭 X 一律在右上角,各类型一致;「完成」是保存动作,继续在场。
// fabu 这一页原来有 5 处自绘头部把 ✕ 画在左上、完成占右上,和同一流程里
// 「选择分类」等用 cy-sheet 头部(右上 ✕)的弹层方向相反 —— 人找一个关闭要来回换手。
// 相册证据:CU-M-159 / CU-C-156 走查截图(主题详情弹层、新建节点弹层、编辑章节半屏、发布确认浮层)。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

const FABU_WXML = 'pages/publish/fabu/index.wxml';
const FABU_WXSS = 'pages/publish/fabu/index.wxss';
const TOPIC_WXML = 'pages/publish/fabu/topic-detail-sheet.wxml';

/** 按出现顺序取每个标记在源码里的下标,用于判定同一行头部里谁在左谁在右。 */
function positions(source, needle) {
  const out = [];
  for (let i = source.indexOf(needle); i !== -1; i = source.indexOf(needle, i + 1)) out.push(i);
  return out;
}

/** 每一处 node-sheet__top:退出(文字)必须排在该行最前(= 视觉最左),完成在它右边;不得是 ✕ 图标。 */
function assertExitIsLeftmost(sources) {
  const wxml = sources[FABU_WXML] === undefined ? read(FABU_WXML) : sources[FABU_WXML];
  const topic = sources[TOPIC_WXML] === undefined ? read(TOPIC_WXML) : sources[TOPIC_WXML];
  const tops = positions(wxml, 'class="node-sheet__top"').length + positions(topic, 'class="node-sheet__top"').length;
  assert.ok(tops >= 4, `node-sheet__top 头部至少应有 4 处，实测 ${tops}`);

  for (const [file, source] of [[FABU_WXML, wxml], [TOPIC_WXML, topic]]) {
    for (const start of positions(source, '<view class="node-sheet__top"')) {
      let depth = 0;
      let end = start;
      for (const tag of source.slice(start).matchAll(/<\/?view\b[^>]*>/g)) {
        depth += tag[0].startsWith('</') ? -1 : 1;
        if (depth === 0) { end = start + tag.index + tag[0].length; break; }
      }
      const header = source.slice(start, end);
      const done = positions(header, '<view class="node-sheet__done"');
      const close = positions(header, '<view class="node-sheet__close"');
      assert.equal(close.length, 1, `${file}: 每个头部应有一颗退出按钮`);
      for (const at of done) assert.ok(close[0] < at, `${file}:退出又排到完成右边了`);
      // 2026-10-28 原型 A(e-node / e-quest / e-route):节点编辑一族的半屏头部是「取消 / 标题 / 完成」,
      // 从半屏再开的一层是「‹ 上一层名」(返回箭头 + 上一层的名字)。✕ 仍然不能代替文字。
      const backLayer = /node-sheet__close route-mapping-sheet__back/.test(header);
      if (backLayer) {
        assert.match(header, /<cy-icon name="chevron-right"[^>]*\/><\/view>[^<]+<\/view>/, `${file}:下钻层左侧必须是返回箭头 + 上一层名字`);
        continue;
      }
      const exitEl = header.slice(close[0], header.indexOf('</view>', close[0]));
      assert.doesNotMatch(exitEl, /cy-icon/, `${file}:退出被换回 ✕ 图标了`);
      assert.match(exitEl, />(退出|返回|取消|\{\{structureEditor\.kind === 'quests' \? '取消' : '退出'\}\})$/, `${file}:左侧必须是文字「退出」(下钻层可为「返回」，节点编辑一族为「取消」)`);
    }
  }
}

test('fabu 自绘头部:左「退出」文字、右「完成」', () => {
  assertExitIsLeftmost({});
});

test('fabu 头部用 space-between:退出贴左、完成贴右', () => {
  const wxss = read(FABU_WXSS);
  const block = wxss.match(/\.node-sheet__top\s*\{([^}]*)\}/);
  assert.ok(block, '缺少 .node-sheet__top 规则');
  assert.match(block[1], /justify-content:\s*space-between/);
});

/** 标题区从 eyebrow 那颗起、到它自己的 </text> 为止(外层 </view> 会先吃掉 input)。 */
function titlebarOf(source) {
  const at = source.indexOf('class="node-sheet__titlebar"');
  assert.ok(at !== -1, '缺少 node-sheet__titlebar');
  const end = source.indexOf('</text>', at);
  assert.ok(end !== -1, '标题区没有收尾的 </text>');
  return source.slice(at, end);
}

function assertSingleTitle(source) {
  const titlebar = titlebarOf(source);
  assert.doesNotMatch(titlebar, /node-sheet__eyebrow/, '标题区又出现第二颗标题了');
  assert.match(titlebar, /node-sheet__name--static/);
}

function assertChapterHeader(source) {
  assert.doesNotMatch(source, /bindtap="cancelChapter"[^>]*>取消</, '左上的「取消」被加回来了');
  assert.match(source, /class="cs-close cs-exit"[^>]*bindtap="cancelChapter"[^>]*>退出<\/view>\s*<view class="tit">[^<]*<\/view>\s*<view class="node-sheet__done" bindtap="confrimChapter"[^>]*>完成<\/view>/, '章节设置必须是左退出、中标题、右完成(与其他编辑页同一颗黑色完成)');
  assert.doesNotMatch(source, /class="pc-back"/, '发布确认顶部又退回左上返回箭头');
  // 2026-10-22 业主裁决(L09):发布确认撤掉右上 ✕,退出走底部「返回修改」与遮罩
  assert.doesNotMatch(source, /class="pc-close"/, '发布确认又长回右上 ✕');
  assert.match(source, /class="pc-mask"[^>]*bindtap="closePublishCheck"/, '发布确认缺点遮罩返回修改');
  assert.match(source, /<cy-btn[^>]*bindtap="closePublishCheck"[^>]*>返回修改<\/cy-btn>/);
}

test('主题详情弹层只有一处标题:撤掉「编辑主题」小字,主题名承担标题', () => {
  assertSingleTitle(read(TOPIC_WXML));
});

test('编辑章节半屏:左退出 / 右完成;发布确认浮层仍是确认层(无 ✕,返回修改 + 遮罩)', () => {
  assertChapterHeader(read(FABU_WXML));
});

test('负控:任一 fabu 头部把退出挪到完成右边,契约必须判红', () => {
  const source = read(FABU_WXML);
  const mutated = source.replace(
    /(<view class="node-sheet__top"[^>]*>)(<view class="node-sheet__close"[^>]*>退出<\/view>)(\s*)(<view class="node-sheet__done"[^>]*>完成<\/view>)/,
    '$1$4$3$2',
  );
  assert.notEqual(mutated, source, '负控未命中 node-sheet__top 顺序');
  assert.throws(() => assertExitIsLeftmost({ [FABU_WXML]: mutated }), /退出又排到完成右边/);
});

test('负控:退出换回 ✕ 图标,契约必须判红', () => {
  const source = read(FABU_WXML);
  const mutated = source.replace(/(<view class="node-sheet__close"[^>]*>)退出(<\/view>)/, '$1<cy-icon name="close-sm" size="44" />$2');
  assert.notEqual(mutated, source, '负控未命中退出文字');
  assert.throws(() => assertExitIsLeftmost({ [FABU_WXML]: mutated }), /换回 ✕ 图标|必须是文字/);
});

test('负控:恢复「编辑主题」小字标题,契约必须判红', () => {
  const source = read(TOPIC_WXML);
  const mutated = source.replace(
    '<view class="node-sheet__titlebar">',
    '<view class="node-sheet__titlebar">\n      <text class="node-sheet__eyebrow">编辑主题</text>',
  );
  assert.notEqual(mutated, source, '负控未命中标题区');
  assert.throws(() => assertSingleTitle(mutated), /第二颗标题/);
});

test('负控:章节设置退回左上「取消」,契约必须判红', () => {
  const source = read(FABU_WXML);
  const mutated = source
    .replace(/\n\s*<view class="cs-close cs-exit"[\s\S]*?<\/view>/, '')
    .replace(/<view class="node-sheet__done" bindtap="confrimChapter"[^>]*>完成<\/view>/, '<cy-btn bindtap="cancelChapter">取消</cy-btn>');
  assert.notEqual(mutated, source, '负控未命中章节设置头部');
  assert.throws(() => assertChapterHeader(mutated), /「取消」被加回来|左退出/);
});
