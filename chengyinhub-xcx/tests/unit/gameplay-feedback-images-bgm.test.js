// 2026-10-22 玩法模板「结果反馈配图」与互动工具「背景音乐」的玩家端契约。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');

test('结算后的回执把配图(最多 3 张)交给结果位;未结算不露图', () => {
  const { outcomeReply } = require('../../pages/play/utils/r5-player-runtime.js');
  const settled = outcomeReply({ lastOutcome: { attemptId: 'a1', settled: true, status: 'SETTLED', outcome: 'PASS', reply: '答对了',
    images: ['https://cdn.example.com/1.jpg', '', 'https://cdn.example.com/2.jpg', 'https://cdn.example.com/3.jpg', 'https://cdn.example.com/4.jpg'] } });
  assert.equal(settled.reply, '答对了');
  assert.deepEqual(settled.images, ['https://cdn.example.com/1.jpg', 'https://cdn.example.com/2.jpg', 'https://cdn.example.com/3.jpg']);
  const pending = outcomeReply({ lastOutcome: { attemptId: 'a2', settled: false, status: 'PENDING', images: ['https://cdn.example.com/1.jpg'] } });
  assert.deepEqual(pending.images, []);
  const legacy = outcomeReply({ lastOutcome: { attemptId: 'a3', settled: true, status: 'SETTLED', outcome: 'FAIL', reply: '' } });
  assert.deepEqual(legacy.images, [], '旧回执没有 images 字段时是空列表,模板里 .length 不会报错');
});

test('结果位模板在结算后显示配图', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{r5OutcomeReply\.settled && r5OutcomeReply\.images\.length\}\}"/);
  assert.match(wxml, /wx:for="\{\{r5OutcomeReply\.images\}\}"/);
});

function loadAdvancedGame(audios) {
  const componentPath = require.resolve('../../pages/play/components/advanced-game/index.js');
  delete require.cache[componentPath];
  let definition;
  global.getApp = () => ({ sendRequest() {} });
  global.wx = {
    createInnerAudioContext: () => {
      const audio = { src: '', loop: false, plays: 0, stopped: false, destroyed: false,
        play() { this.plays += 1; }, pause() {}, stop() { this.stopped = true; }, destroy() { this.destroyed = true; },
        onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {} };
      audios.push(audio);
      return audio;
    },
    showToast() {},
  };
  global.Component = (value) => { definition = value; };
  require(componentPath);
  const instance = { data: { show: true, playingDrawId: '' }, setData(patch) { Object.assign(this.data, patch); } };
  Object.keys(definition.methods).forEach((name) => { instance[name] = definition.methods[name].bind(instance); });
  return { definition, instance };
}

test('背景音乐:开局按配置播放并循环;同一首不重复起;收起 / 切后台 / 销毁就停', () => {
  const audios = [];
  const { definition, instance } = loadAdvancedGame(audios);
  instance.syncBackgroundMusic({ audioUrl: 'https://cdn.example.com/tea.mp3', loop: true });
  assert.equal(audios.length, 1);
  assert.equal(audios[0].src, 'https://cdn.example.com/tea.mp3');
  assert.equal(audios[0].loop, true);
  assert.equal(audios[0].plays, 1);
  instance.syncBackgroundMusic({ audioUrl: 'https://cdn.example.com/tea.mp3', loop: true });
  assert.equal(audios.length, 1, '同一首不重新创建播放器');
  definition.pageLifetimes.hide.call(instance);
  assert.equal(audios[0].stopped && audios[0].destroyed, true, '切后台就停');
  instance.syncBackgroundMusic({ audioUrl: 'https://cdn.example.com/tea.mp3', loop: false });
  assert.equal(audios[1].loop, false, 'loop=false 只播一遍');
  definition.observers['show,node.nodeId'].call(instance, false, 1);
  assert.equal(audios[1].destroyed, true, '收起玩法就停');
});

test('背景音乐:没配或玩法没打开时不播', () => {
  const audios = [];
  const { instance } = loadAdvancedGame(audios);
  instance.syncBackgroundMusic(undefined);
  instance.syncBackgroundMusic({ title: '无音频' });
  instance.data.show = false;
  instance.syncBackgroundMusic({ audioUrl: 'https://cdn.example.com/tea.mp3' });
  assert.equal(audios.length, 0);
});
