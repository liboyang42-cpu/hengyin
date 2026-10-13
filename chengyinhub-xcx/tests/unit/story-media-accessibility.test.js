const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const story = require('../../pages/publish/utils/publish/pro-editor-story.js');

for (const type of ['image', 'audio']) {
  test(type + ': 文字替代经编辑、提交、重开保留，且不污染摘要', () => {
    const source = { chapter: { blocks: [{ key: 'm', type, url: 'https://cdn.example/media' }], nodes: [] } };
    const next = story.applyStoryCommand(source, { type: 'setMediaAltText', blockKey: 'm', altText: '街角有一个路牌' }).draft;
    assert.equal(source.chapter.blocks[0].altText, undefined);
    const payload = story.toPayloadChapter(next.chapter);
    assert.equal(payload.blocks[0].altText, '街角有一个路牌');
    assert.equal(story.materializeChapter(payload, () => 'key').blocks[0].altText, '街角有一个路牌');
    assert.equal(payload.description || '', '');
    assert.throws(() => story.applyStoryCommand(source, { type: 'setMediaAltText', blockKey: 'm', altText: '字'.repeat(2001) }));
  });
}
test('旧媒体无文字替代仍兼容；失效引用不能修改另一个块', () => {
  const chapter = { blocks: [{ key: 'm', type: 'audio', url: 'https://cdn.example/a.mp3' }], nodes: [] };
  assert.equal(story.toPayloadChapter(chapter).blocks[0].altText, undefined);
  assert.throws(() => story.applyStoryCommand({ chapter }, { type: 'setMediaAltText', blockKey: 'missing', altText: '文字' }));
});
test('故事段保留失败文字与重试，播放状态有加载/暂停/失败与后台恢复说明', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.wxml'), 'utf8');
  assert.match(wxml, /binderror="onStoryImageError"/);
  assert.match(wxml, /bind:action="retryStoryImage"/);
  assert.match(wxml, /item.altText/);
  assert.match(wxml, /audioLoading/);
  assert.match(wxml, /播放失败，点此重播/);
  assert.match(wxml, /重新进入从本段开头播放/);
});
