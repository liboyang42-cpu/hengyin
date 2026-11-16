const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { playerStoryLabel } = require('../../utils/story-player-label.js');
const root = path.resolve(__dirname, '../..');

test('only exact production metadata labels are hidden; names, time and prose remain', () => {
  for (const label of ['旁白', ' 旁白： ', '【旁白】', 'Narrator', 'NARRATION', '导演提示', '制作备注']) assert.equal(playerStoryLabel(label), '');
  for (const label of ['阿澜', '主持人', '导演', '镜头', '场记', '内容提示', '安全', '怎么玩', '三年后', '旁白这个词让她想起雨']) assert.equal(playerStoryLabel(label), label);
  for (const label of [null, undefined, {}, 12]) assert.equal(playerStoryLabel(label), '');
});

test('actual template Page preview preserves prose/media/time and never copies author-only fields', () => {
  let config;
  global.getApp = () => ({ globalData: {} });
  global.wx = { getStorageSync() {}, getSystemInfoSync: () => ({ windowWidth: 375 }) };
  global.Page = value => { config = value; };
  const file = path.join(root, 'pages/publish/temp/index.js');
  delete require.cache[require.resolve(file)]; require(file);
  const beats = [
    { tag: '旁白', text: '导演推开门，镜头里是一封信。', imgs: [], directorNotes: 'AUTHOR_ONLY' },
    { tag: '三年后', text: '她又读了一遍信。', imgs: ['https://media.example/story.jpg'] },
    { tag: '', text: '雨停了。', imgs: [], answer: 'SECRET_ANSWER' }
  ];
  const original = JSON.stringify(beats);
  const page = { ...config, data: { pvSteps: ['story'], storyBeats: beats }, setData(patch) { Object.assign(this.data, patch); }, syncNativeTheme() {} };
  page._applyPvStep(0);
  assert.deepEqual(page.data.pvBeats, [
    { id: 0, label: '', text: beats[0].text, imgs: [] },
    { id: 1, label: '三年后', text: beats[1].text, imgs: beats[1].imgs },
    { id: 2, label: '', text: beats[2].text, imgs: [] }
  ]);
  assert.equal(JSON.stringify(beats), original);
  assert.doesNotMatch(JSON.stringify(page.data.pvBeats), /AUTHOR_ONLY|SECRET_ANSWER|时刻/);
  const markup = fs.readFileSync(path.join(root, 'pages/publish/temp/index.wxml'), 'utf8');
  assert.match(markup, /class="cg-pv-beat-tag" wx:if="\{\{pb.label\}\}"/);
});
