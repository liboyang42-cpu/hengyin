// 第十一轮 i1:九宫格是主题级玩法(格子与奖励在主题设置里配,服务端 PlayBingoServiceImpl 判),节点编辑器造不出它。
// 玩法单子里保留入口只为告诉作者去哪配:必须是灰的、点不动,不能选出一个没有段的配置。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');

test('节点玩法单子里的九宫格是灰的,说明去主题里配', () => {
  const bingo = catalog.BASE_GAMES.find(item => item.key === 'bingo');
  assert.ok(bingo);
  assert.equal(bingo.disabled, true);
  assert.equal(bingo.reason, '在主题里配置');
  assert.equal(bingo.gameKey, undefined, '没有节点段可写');
  const editor = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/r5-editor.js'), 'utf8');
  assert.match(editor, /pickR5Game\(e\) \{[^\n]*if \(!base \|\| base\.disabled \|\| base\.reason\) return;/, '灰的点不动');
});

test('主题设置里能配九宫格(编辑与校验都在主题发布页)', () => {
  const fabu = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');
  assert.match(fabu, /advancedGameConfig\.validateTopicBingo\(/);
  assert.match(fabu, /bingoRows: advancedGameConfig\.BINGO_S_ORDER/);
});
