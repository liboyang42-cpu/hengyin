// P-29:一章的收尾文字要在下一章的章节卡之前读到。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { hasTrailingContent, holdForTrail } = require('../../pages/play/utils/chapter-trail.js');

const ch1 = { id: 11, blocks: [
  { type: 'text', content: '开场' },
  { type: 'node', nodeId: 1 },
  { type: 'node', nodeId: 2 },
  { type: 'text', content: '钟声停了。第一章到这里。' },
] };

test('最后一个节点块之后有能读的块才算有收尾', () => {
  assert.equal(hasTrailingContent(ch1.blocks), true);
  assert.equal(hasTrailingContent([{ type: 'text', content: 'a' }, { type: 'node', nodeId: 1 }]), false);
  assert.equal(hasTrailingContent([{ type: 'node', nodeId: 1 }, { type: 'mood', mood: 'NIGHT' }, { type: 'text', content: '  ' }]), false,
    '只有标记块或空文字不算');
  assert.equal(hasTrailingContent([{ type: 'node', nodeId: 1 }, { type: 'image', url: 'https://x/a.jpg' }]), true);
  assert.equal(hasTrailingContent([{ type: 'text', content: '没有节点' }]), false);
  assert.equal(hasTrailingContent(null), false);
});

test('本章各站都完成、收尾没读过才停在本章', () => {
  const done = [{ nodeId: 1, done: true }, { nodeId: 2, done: true }];
  assert.equal(holdForTrail(ch1, done, {}), true);
  assert.equal(holdForTrail(ch1, done, { 11: true }), false, '读过的不再停');
  assert.equal(holdForTrail(ch1, [{ nodeId: 1, done: true }, { nodeId: 2, done: false }], {}), false, '还有站没完成:不是换章');
  assert.equal(holdForTrail(Object.assign({}, ch1, { opening: true }), done, {}), false, '序章另有出发流程');
  assert.equal(holdForTrail(Object.assign({}, ch1, { ending: true }), done, {}), false);
  assert.equal(holdForTrail({ id: 12, blocks: [{ type: 'node', nodeId: 1 }] }, done, {}), false, '没有收尾就直接换章,行为不变');
});

test('换章那一步先停在本章,点「继续探索」才换到下一章', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.match(js, /if \(chapterChanged && !this\.data\.isPreview && chapterTrail\.holdForTrail\(this\.data\.chapter, nodes, this\._chapterTrailRead\)\) \{\n      this\._chapterTrailNext = activeChapter;\n      activeChapter = this\.data\.chapter;/);
  assert.match(js, /continueChapterStory\(\) \{\n    const opening = [^\n]+\n    \/\/ P-29[^\n]+\n    if \(this\._chapterTrailNext && !this\.data\.isPreview\) \{[\s\S]*?this\.setData\(\{ chapter: next \}, \(\) => this\.openChapterFull\(\)\);/);
});
