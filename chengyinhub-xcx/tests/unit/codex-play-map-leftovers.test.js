const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const view = require('../../pages/play/utils/playkit-view.js');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
test('F17:文字问答只显示终局服务端揭示,失败终局仍保留答案与作者反馈', () => {
  const base = { sessionId: 1, status: 'RUNNING', playKit: { qa: { mode: 'TYPE', answerText: '钟楼' } } };
  assert.equal(view.pickPlayKit(base).answerText, '');
  base.playKit.qa.finished = true; base.status = 'FAILED'; base.canRetry = true;
  base.outcomeFeedback = { reply: '再看一眼', images: ['reply.png'] };
  const kit = view.pickPlayKit(base);
  assert.equal(kit.answerText, '钟楼'); assert.equal(kit.terminalDetail, '钟楼');
  assert.equal(kit.terminalFeedback, '再看一眼'); assert.deepEqual(kit.terminalImages, ['reply.png']);
});
test('N7:可重试的失败只显示终局重试屏,不先叠一次判定屏', () => {
  const m = require('../../pages/play/utils/r5-player-runtime.js').methods();
  const page = { data: { themeBag: {} }, _r5PlainSeen: { '1': '' }, setData() { assert.fail('不应再弹一层'); } };
  m._applyR5PlainOutcome.call(page, { sessionId: 1, status: 'FAILED', canRetry: true, config: { outcomeRulesVersion: 1 }, outcomeFeedback: { outcome: 'FAIL', reply: '再看一眼' } });
});
test('地图大字覆盖卡片和原生贴纸;放行前不引导去下一站;完成按钮进入内容流', () => {
  assert.match(read('pages/roam/index.wxss'), /pstat__v\{ font-size:calc\(62rpx \* var\(--cy-type-scale, 1\)\)/);
  assert.match(read('components/cy/free-map/index.wxml'), /font-size:\{\{stickerFontSize \|\| 11\}\}px/);
  assert.match(read('pages/play/index.wxml'), /nextNode && !\(chapterGate.show && chapterGate.held\)/);
  assert.match(read('pages/play/index.wxss'), /\.finsheet \.finsheet__cta\{ position:relative/);
});
