'use strict';
// FE-STORY 6.1 / 7.3:已解锁章节的正文与碎片按「账号 + 场次」存本地(记录盖局号),断网时可读;
// 锁定章节没有正文可存;到访必须联网,离线时锁卡只提示「联网后才能解锁」,不排队、不补发。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const store = new Map();
global.wx = {
  getStorageSync: (k) => store.get(k),
  setStorageSync: (k, v) => { store.set(k, JSON.parse(JSON.stringify(v))); },
};
const cache = require('../../pages/play/utils/fe-story-cache.js');
const read = (rel) => fs.readFileSync(path.join(__dirname, '../..', rel), 'utf8');

const payload = {
  mode: 2, topicId: 9, topicName: '示例主题', topicCover: 'https://x/t.jpg', storyUnlocked: 1, storyTotal: 2,
  nodes: [
    { nodeId: 1, chapterId: 11, name: '示例站点', storyLocked: false, visited: true, fragmentText: '碎片一', description: '不缓存的节点正文' },
    { nodeId: 2, chapterId: 12, name: '另一处', storyLocked: true, visited: false, hookText: '一句钩子', fragmentText: null },
  ],
  chapters: [
    { chapterId: 11, name: '第一章', description: '已解锁的章节正文', blocks: [{ type: 'text', content: 'ABC' }] },
    { chapterId: 12, name: '第二章', chapterLocked: true, description: null, blocks: [] },
  ],
};
const scopeA = { memberId: 100, topicId: 9, routeSessionId: '77' };

test('只缓存已解锁章节正文与已到访碎片;锁定章节不存正文', () => {
  store.clear();
  assert.equal(cache.save(scopeA, payload), true);
  const v = cache.read(scopeA);
  assert.equal(v.runId, '77');
  assert.equal(v.chapters[0].description, '已解锁的章节正文');
  assert.deepEqual(v.chapters[0].blocks, [{ type: 'text', content: 'ABC' }]);
  assert.equal(v.chapters[1].chapterLocked, true);
  assert.equal('description' in v.chapters[1], false);
  assert.equal(v.nodes[0].fragmentText, '碎片一');
  assert.equal('fragmentText' in v.nodes[1], false);
  assert.equal('description' in v.nodes[0], false, '节点正文不进缓存');
  assert.equal(v.nodes[1].hookText, '一句钩子', '锁卡要显示的公开字段在');
});

test('按账号与场次隔离:别的账号、别的主题读不到;没有局号不存', () => {
  store.clear();
  cache.save(scopeA, payload);
  assert.equal(cache.read({ memberId: 101, topicId: 9 }), null, '换账号读不到');
  assert.equal(cache.read({ memberId: 100, topicId: 10 }), null, '换主题读不到');
  assert.ok(cache.read({ memberId: 100, topicId: 9 }), '断网时(局号未知)同账号同主题可读');
  assert.equal(cache.save({ memberId: 100, topicId: 9, routeSessionId: '' }, payload), false);
  assert.equal(cache.save({ topicId: 9, routeSessionId: '77' }, payload), false, '没有账号不存');
  cache.save({ memberId: 100, topicId: 9, routeSessionId: '78' }, Object.assign({}, payload, { storyUnlocked: 2 }));
  assert.equal(cache.read(scopeA).runId, '78', '新的一局整份覆盖');
});

test('城市定向与没有到访闸的回包不缓存', () => {
  store.clear();
  assert.equal(cache.save(scopeA, Object.assign({}, payload, { mode: 1 })), false);
  assert.equal(cache.save(scopeA, Object.assign({}, payload, { storyTotal: undefined })), false);
  assert.equal(cache.read(scopeA), null);
});

test('页面接线:成功加载后存,断网时读缓存;离线时锁卡不发到访', () => {
  const js = read('pages/play/index.js');
  assert.match(js, /if \(mode === 2\) that\._saveFeStoryCache\(d\);/);
  assert.match(js, /if \(res\.netFail && that\._applyFeOfflineCache\(\)\) return;/);
  assert.match(js, /if \(that\._applyFeOfflineCache\(\)\) return;\n\s+that\.setData\(\{ loading: false, emptyTip: '加载失败，请重试'/);
  const gate = read('pages/play/utils/fe-story-gate.js');
  assert.match(gate, /if \(this\.data\.storyOffline\) \{ cyToast\('联网后才能解锁'\); return; \}/);
  assert.match(read('pages/play/index.wxml'), /\{\{storyOffline \? '联网后才能解锁' : item\.visitBtn\}\}/);
});

test('离线读缓存后渲染为只读集章格', () => {
  store.clear();
  cache.save(scopeA, payload);
  const gate = require('../../pages/play/utils/fe-story-gate.js').methods();
  const page = Object.assign({}, gate, {
    data: { isPreview: false, isMock: false, chapter: {} }, rebuilt: 0,
    setData(p, cb) { Object.assign(this.data, p); if (cb) cb(); },
    _runSessionScope: () => ({ memberId: 100, topicId: 9, routeSessionId: '' }),
    normNode: (n) => Object.assign({ done: false }, n),
    buildChapterCards: (d) => d.chapters,
    rebuild() { this.rebuilt++; },
  });
  assert.equal(page._applyFeOfflineCache(), true);
  assert.equal(page.data.storyOffline, true);
  assert.equal(page.data.storyGate, true);
  assert.equal(page.data.nodes.length, 2);
  assert.equal(page.rebuilt, 1);
  const none = Object.assign({}, page, { _runSessionScope: () => ({ memberId: 555, topicId: 9 }) });
  assert.equal(none._applyFeOfflineCache(), false, '没有缓存就照常显示加载失败');
});
