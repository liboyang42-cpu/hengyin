const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
const body = source.slice(source.indexOf('  startGame(nodeOverride) {'), source.indexOf('  async onAdvancedReady(e) {'));
const startGame = new Function('cyToast', 'return function ' + body.trim().replace(/,\s*$/, ''));

test('服务端不允许游玩时不打开任务表单，重新允许后可打开；试玩不受旧场次状态影响', () => {
  assert.match(source, /that\._playable = d\.playable !== false;/);
  for (const scenario of [
    { playable: false, preview: false, opens: false },
    { playable: true, preview: false, opens: true },
    { playable: false, preview: true, opens: true },
  ]) {
    let opened = false;
    let mutations = 0;
    const notices = [];
    const page = {
      _playable: scenario.playable,
      data: { sheet: { node: { nodeId: 222, hasAdvanced: true } }, isPreview: scenario.preview, timeBanner: '活动已结束' },
      stopAudio() {},
      setData(patch) { mutations += 1; if (patch.advancedPlay && patch.advancedPlay.show) opened = true; },
    };
    startGame((message) => notices.push(message)).call(page);
    assert.equal(opened, scenario.opens);
    assert.deepEqual(notices, scenario.opens ? [] : ['活动已结束']);
    if (!scenario.opens) {
      assert.equal(mutations, 0);
      startGame((message) => notices.push(message)).call(page, { nodeId: 223 });
      assert.equal(mutations, 0, '直接传节点也不能先打开任务半屏');
    }
  }
});
