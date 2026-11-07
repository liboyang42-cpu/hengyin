// P-29:一章的收尾文字要在下一章的章节卡之前读到。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { hasTrailingContent, trailState } = require('../../pages/play/utils/chapter-trail.js');

const full = { id: 44, blocks: [
  { type: 'text', content: '开场' },
  { type: 'node', nodeId: 75 },
  { type: 'text', content: '钟楼里传出一声闷响。第一章的门在身后合上了。' },
] };
// 最后一站完成前拉到的同一章:服务端只下发到第一个未完成节点,收尾不在里面
const truncated = { id: 44, blocks: full.blocks.slice(0, 2) };
const done = [{ nodeId: 75, done: true }, { nodeId: 76, done: false }];

test('最后一个节点块之后有能读的块才算有收尾', () => {
  assert.equal(hasTrailingContent(full.blocks), true);
  assert.equal(hasTrailingContent(truncated.blocks), false);
  assert.equal(hasTrailingContent([{ type: 'node', nodeId: 1 }, { type: 'mood', mood: 'NIGHT' }, { type: 'text', content: '  ' }]), false,
    '只有标记块或空文字不算');
  assert.equal(hasTrailingContent([{ type: 'node', nodeId: 1 }, { type: 'image', url: 'https://x/a.jpg' }]), true);
  assert.equal(hasTrailingContent([{ type: 'text', content: '没有节点' }]), false);
  assert.equal(hasTrailingContent(null), false);
});

test('换章:收尾到手就停下读;块流是完成前拉的就先等;读过或没有收尾就直接换', () => {
  // rig 实测的顺序:作答回执先触发一次重算(章节卡还是完成前拉的,只有 2 块),随后重拉节点带来 3 块
  assert.equal(trailState(truncated, done, {}, []), 'unknown', '收尾还没到手:不能当成「没有收尾」直接换章');
  assert.equal(trailState(full, done, {}, ['75']), 'hold');
  assert.equal(trailState(full, done, { 44: true }, ['75']), 'none', '读过的不再停');
  assert.equal(trailState({ id: 45, blocks: [{ type: 'node', nodeId: 75 }] }, done, {}, ['75']), 'none', '真没有收尾:直接换章,行为不变');
  assert.equal(trailState(full, [{ nodeId: 75, done: false }], {}, []), 'none', '这一章还没走完:不是换章');
  assert.equal(trailState(Object.assign({}, full, { opening: true }), done, {}, ['75']), 'none', '序章另有出发流程');
  assert.equal(trailState(null, done, {}, ['75']), 'none');
});

test('换章那一步停在上一次所在的章(重拉会把 data.chapter 重置成第一章,不能拿它判),点「继续探索」才换', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.match(js, /const trailPrevId = this\._lastActiveChapterId;/);
  assert.match(js, /trailHold = chapterTrail\.trailState\(prevCard, nodes, trailRead, this\._trailDoneAtLoad\);\n      if \(trailHold !== 'none'\) \{ this\._chapterTrailNext = activeChapter; activeChapter = this\._chapterFromCard\(prevCard\); \}/);
  assert.match(js, /if \(trailCards !== this\._trailCardsRef\) \{\n      this\._trailCardsRef = trailCards;\n      this\._trailDoneAtLoad = /);
  assert.match(js, /continueChapterStory\(\) \{\n    if \(this\._chapterReadCursor\) \{ this\.closeChapterFull\(\); return Promise\.resolve\(\); \}\n    const opening = [^\n]+\n    \/\/ P-29[^\n]+\n    if \(this\._chapterTrailNext && !this\.data\.isPreview\) \{[\s\S]*?this\.setData\(\{ chapter: next \}, \(\) => this\.openChapterFull\(\)\);/);
  assert.match(js, /storyResume\.patch\(this\._runSessionScope\(\), \{ trails: /);
});
