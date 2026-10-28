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
  assert.deepEqual(legacy.images, [], '旧回执没有 images 字段时是空列表，模板里 .length 不会报错');
});

test('成功 / 失败反馈不进背包:背包只留结算状态,文字与配图上整屏判定', () => {
  const bag = fs.readFileSync(path.join(ROOT, 'pages/play/theme-backpack.wxml'), 'utf8');
  assert.doesNotMatch(bag, /r5OutcomeReply\.reply|r5OutcomeReply\.images/);
  assert.match(bag, /r5OutcomeReply\.statusText/);
  const page = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8');
  assert.match(page, /<cy-play-verdict show="\{\{r5Verdict\.show\}\}"[^>]*text="\{\{r5Verdict\.text\}\}" images="\{\{r5Verdict\.images\}\}"[^>]*bind:close="closeR5Verdict"/);
  const verdict = fs.readFileSync(path.join(ROOT, 'pages/play/components/play-verdict/index.wxml'), 'utf8');
  assert.match(verdict, /wx:for="\{\{images\}\}"/);
});

function runtimeHost() {
  const runtime = require('../../pages/play/utils/r5-player-runtime.js');
  const host = { data: JSON.parse(JSON.stringify(runtime.data)), setData(patch) {
    Object.keys(patch).forEach(key => { if (key.includes('.')) { const [a, b] = key.split('.'); this.data[a][b] = patch[key]; } else this.data[key] = patch[key]; });
  } };
  host.data.journeyCheck = {};
  Object.assign(host, runtime.methods());
  return host;
}
const receipt = (id, settled, outcome, reply, images) => ({ sessionId: 1, lastOutcome: { attemptId: id, settled, status: settled ? 'SETTLED' : 'PENDING', outcome, reply, images } });

test('玩家端:亲眼等到结算才弹判定屏;进页时已结过的、同一张回执、没配反馈的都不弹', () => {
  const host = runtimeHost();
  host._applyR5OutcomeView(receipt('a0', true, 'PASS', '旧的反馈', []));
  assert.equal(host.data.r5Verdict.show, false, '进页第一份视图只记下来');
  host._applyR5OutcomeView(receipt('a1', false, '', '', []));
  host._applyR5OutcomeView(receipt('a1', true, 'FAIL', '再找找门牌', ['https://cdn.example.com/1.jpg']));
  assert.deepEqual(host.data.r5Verdict, { show: true, kind: 'fail', text: '再找找门牌', images: ['https://cdn.example.com/1.jpg'] });
  host.setData({ 'r5Verdict.show': false });   // 页面上的 closeR5Verdict 就是这一句
  host._applyR5OutcomeView(receipt('a1', true, 'FAIL', '再找找门牌', ['https://cdn.example.com/1.jpg']));
  assert.equal(host.data.r5Verdict.show, false, '轮询回来同一张回执不重弹');
  host._applyR5OutcomeView(receipt('a2', true, 'PASS', '', []));
  assert.equal(host.data.r5Verdict.show, false, '没配反馈就不弹');
  host._applyR5OutcomeView(receipt('a3', true, 'PASS', '门开了', []));
  assert.equal(host.data.r5Verdict.kind, 'win');
});

test('编辑页试玩:按作者刚填的答案本地判,反馈取对应那一边', () => {
  const pv = require('../../pages/publish/temp/preview-verdict.js');
  const advanced = { qa: { mode: 'PICK', answerText: '钟楼、Bell Tower', options: [{ id: 'o1', correct: false }, { id: 'o2', correct: true }] },
    outcomeRules: { pass: { reply: '对了', images: ['https://cdn.example.com/p.jpg'] }, fail: { reply: '' } } };
  const qa = { type: 'qa' };
  assert.equal(pv.judge(advanced, qa, 'submit', { mode: 'pick', index: 1, optionId: 'o2' }), 'win');
  assert.equal(pv.judge(advanced, qa, 'submit', { mode: 'pick', index: 0, optionId: 'o1' }), 'fail');
  assert.equal(pv.judge(advanced, qa, 'submit', { mode: 'type', input: '  bell tower ' }), 'win', '与服务端同:去空格、不分大小写、顿号分隔');
  assert.equal(pv.judge(advanced, qa, 'submit', { mode: 'type', input: '鼓楼' }), 'fail');
  advanced.qa.multi = true; advanced.qa.options.push({ id: 'o3', correct: true });
  assert.equal(pv.judge(advanced, qa, 'submit', { mode: 'pick', optionIds: ['o3', 'o2'] }), 'win');
  assert.equal(pv.judge(advanced, qa, 'submit', { mode: 'pick', optionIds: ['o2'] }), 'fail');
  assert.equal(pv.judge(advanced, { type: 'estimate' }, 'answer', {}), '', '答案在玩法自己那儿的不硬判');
  assert.equal(pv.judge(advanced, { type: 'compass' }, 'verdict', { kind: 'fail' }), 'fail');
  assert.equal(pv.judge(advanced, { type: 'typein', passed: true }, 'submit', {}), 'win');
  assert.deepEqual(pv.feedback(advanced, 'win'), { text: '对了', images: ['https://cdn.example.com/p.jpg'] });
  assert.deepEqual(pv.feedback(advanced, 'fail'), { text: '', images: [] }, '无法打开那一边就只有两个字');
});

test('编辑页试玩:玩法那一屏不压翻页点 / 箭头 / 保存栏,判定屏可「继续」', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /cg-pv-float/);
  assert.match(wxml, /<cy-play-verdict wx:if="\{\{ pvStepKey === 'challenge' && pvKit \}\}"[^>]*closable="\{\{ true \}\}" bind:close="closePvVerdict"/);
  // 2026-10-27 P-40:业主删掉了预览底部保存 / 发布栏(只留左右滑动 + 返回),不再断言 cg-pv-submit 存在
  assert.doesNotMatch(wxml, /class="cg-pv-submit"/);
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.json'), 'utf8'));
  assert.equal(json.componentPlaceholder['cy-play-verdict'], 'view', '跨分包组件要占位');
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

test('试玩台里原来没法试玩的三屏(闭眼盲品 / 今日城市签 / D20 三档)现在都能试玩,并照样判定', () => {
  const pv = require('../../pages/publish/temp/preview-verdict.js');
  const preview = require('../../pages/publish/utils/publish/advanced-game-preview.js');
  ['blindTaste', 'dailySign', 'check'].forEach(key => assert.ok(preview.buildSampleKit(key), key + ' 要有试玩屏'));
  const taste = preview.buildSampleKit('blindTaste');
  assert.equal(taste.type, 'blindtaste'); assert.ok(taste.options.length >= 2);
  const advanced = { blindTaste: { answerKey: 'B' } };
  assert.equal(pv.judge(advanced, taste, 'answer', { key: 'B' }), 'win');
  assert.equal(pv.judge(advanced, taste, 'answer', { key: 'A' }), 'fail');
  const check = preview.buildSampleKit('check');
  assert.equal(check.type, 'journeycheck');
  assert.equal(pv.judge({}, check, 'settle', {}), '', '没掷没结算不判');
  assert.equal(pv.judge({}, Object.assign({}, check, { receipt: { settled: true, success: true } }), 'settle', {}), 'win');
  const { applyPreviewAction } = require('../../utils/game-preview-action.js');
  const sign = applyPreviewAction(preview.buildSampleKit('dailySign'), { action: 'accept', detail: { text: '来过' } }, 0);
  assert.equal(sign.kit.revealed && sign.kit.claimed, true); assert.equal(sign.kit.myText, '来过');
});

test('排序 / 连线 / 分类试玩也照作者填的答案本地判,整体全对才算过', () => {
  const pv = require('../../pages/publish/temp/preview-verdict.js');
  const adv = { sort: { answerOrder: ['a', 'b', 'c'] }, match: { pairs: [['l1', 'r1'], ['l2', 'r2']] }, classify: { answer: { i1: 'b1', i2: 'b2' } } };
  assert.equal(pv.judge(adv, { type: 'sort' }, 'submit', { order: ['a', 'b', 'c'] }), 'win');
  assert.equal(pv.judge(adv, { type: 'sort' }, 'submit', { order: ['b', 'a', 'c'] }), 'fail');
  assert.equal(pv.judge(adv, { type: 'match' }, 'submit', { pairs: [['l2', 'r2'], ['l1', 'r1']] }), 'win', '连线不看先后');
  assert.equal(pv.judge(adv, { type: 'match' }, 'submit', { pairs: [['l1', 'r2'], ['l2', 'r1']] }), 'fail');
  assert.equal(pv.judge(adv, { type: 'classify' }, 'submit', { placement: { i1: 'b1', i2: 'b2' } }), 'win');
  assert.equal(pv.judge(adv, { type: 'classify' }, 'submit', { placement: { i1: 'b2', i2: 'b2' } }), 'fail');
});

test('试玩左上角有一颗退出,全屏玩法自己那颗在试玩里藏掉', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxml'), 'utf8');
  assert.match(wxml, /class="cg-pv-exit" wx:if="\{\{ pvStepKey === 'challenge' && pvKit \}\}"[^>]*bindtap="closePreview"/);
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxss'), 'utf8');
  assert.match(wxss, /\.cg-preview--kit \{ --pk-back-display: none; \}/);
});
