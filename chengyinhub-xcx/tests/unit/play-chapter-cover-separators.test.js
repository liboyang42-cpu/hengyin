const test = require('node:test');
const assert = require('node:assert/strict');

// Follow play-generic-flow.test.js: load the actual Page and call its consumers.
// No network, lifecycle hooks, or replacement cover/parser implementation.
let definition;
global.wx = { showToast() {}, vibrateShort() {}, nextTick(cb) { cb(); } };
global.getApp = () => ({ globalData: {} });
global.Page = page => { definition = page; };
require('../../pages/play/index.js');

const first = 'https://media.example.invalid/a.jpg?Signature=x%3D%2B%2F&Expires=1793000000';
const second = 'https://media.example.invalid/b.jpg?Signature=y%3D&Expires=1793000001';
const fallback = 'https://media.example.invalid/chapter.jpg';
const nodeCover = 'https://media.example.invalid/node.jpg';

const cases = [
  { name: '逗号', imgArr: `${first},${second}`, expected: first },
  { name: '分号', imgArr: `${first};${second}`, expected: first },
  { name: '混合且分号在前', imgArr: `${first};${second},tail.jpg`, expected: first },
  { name: '混合且逗号在前', imgArr: `${first},${second};tail.jpg`, expected: first },
  { name: '中间和尾部空项', imgArr: `${first};;,${second},;`, expected: first },
  // Preserve #610's first-slot semantics: it does not filter leading empty slots.
  { name: '首项为空不改选后图', imgArr: `;${second}`, expected: '', chapterExpected: nodeCover },
  { name: '仅分隔符', imgArr: ';,;', expected: '', chapterExpected: nodeCover },
  { name: '空数组字符串回退章节封面', imgArr: '', cover: fallback, expected: fallback },
  { name: '缺少数组回退章节封面', cover: fallback, expected: fallback },
  { name: '无章节图时仅主章节回退节点图', expected: '', chapterExpected: nodeCover },
  { name: '单张签名URL逐字保留', imgArr: first, expected: first }
];

for (const row of cases) {
  test(`buildChapterCards: ${row.name}`, () => {
    const page = Object.assign({}, definition);
    const chapters = [Object.assign({ chapterId: 11, name: '第一站' }, row),
      { chapterId: 12, name: '第二站', imgArr: second }];
    const cards = page.buildChapterCards({ chapters }, {});
    assert.equal(cards.length, 2);
    assert.equal(cards[0].cover, row.expected);
    assert.equal(cards[1].cover, second, '各章节独立取图');
  });

  test(`buildChapter: ${row.name}`, () => {
    const page = Object.assign({}, definition);
    const chapter = Object.assign({ chapterId: 11, name: '第一站' }, row);
    const result = page.buildChapter({ chapters: [chapter] }, [{ imgUrl: nodeCover }]);
    assert.equal(result.cover, row.chapterExpected === undefined ? row.expected : row.chapterExpected);
  });
}

test('无章节及节点图时主章节为空，卡片沿用主章节回退结果', () => {
  const page = Object.assign({}, definition);
  const chapter = page.buildChapter({}, []);
  assert.equal(chapter.cover, '');
  assert.equal(page.buildChapterCards({}, chapter)[0].cover, '');
  const withNode = page.buildChapter({}, [{}, { imgUrl: nodeCover }]);
  assert.equal(withNode.cover, nodeCover);
  assert.equal(page.buildChapterCards({}, withNode)[0].cover, nodeCover);
});

test('主章节切换到卡片读模型时保留本章节点封面，不污染选章卡片或其他章节', () => {
  const nodes = [{ chapterId: 11, imgUrl: `${nodeCover};${second}` },
    { chapterId: 12, imgUrl: second }];
  const page = { ...definition, data: { ...definition.data, nodes } };
  const chapter = page.buildChapter({ chapters: [{ chapterId: 11 }] }, nodes);
  const cards = page.buildChapterCards({ chapters: [{ chapterId: 11 }, { chapterId: 12 }, { chapterId: 13 }] }, chapter);
  page.data.chapterCards = cards;
  assert.equal(chapter.cover, nodeCover);
  assert.equal(page._chapterFromCard(cards[0]).cover, nodeCover);
  assert.equal(page._chapterFromCard(cards[1]).cover, second);
  assert.equal(page._chapterFromCard(cards[2]).cover, '');
  assert.deepEqual(cards.map(card => card.cover), ['', '', '']);
  cards[0].cover = first;
  assert.equal(page._chapterFromCard(cards[0]).cover, first, '作者章节图仍优先');
});

test('冻结历史没有原封面时不补当前节点图', () => {
  const page = { ...definition, data: { ...definition.data, nodes: [{ chapterId: 11, imgUrl: nodeCover }] } };
  const original = { id: '11', title: '旧章节', history: true, blocks: [], cover: '' };
  page._chapterReadCursor = { id: '11' };
  page._currentCompletedHistory = () => ({ chapters: [original] });
  assert.equal(page._chapterFromCard({ id: 11, title: '新章节', cover: first }).cover, '');
});
