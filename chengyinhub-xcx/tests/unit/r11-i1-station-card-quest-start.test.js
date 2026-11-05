// 第十一轮 i1:通用局主题里,详情卡「开始任务」在本站任务还没开始时先发与背包同一条 QUEST_START,成功后再进玩法;
// 原来直接进玩法,服务端回「请先开始当前任务」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
const start = src.indexOf('  startGame(nodeOverride) {');
const body = src.slice(start, src.indexOf('\n  },\n', start));

test('详情卡开始任务先开始本站任务,再进玩法', () => {
  const questAt = body.indexOf("operation: 'QUEST_START'");
  assert.ok(questAt > 0, '用背包同一条命令开始任务');
  assert.ok(questAt < body.indexOf('advancedPlay: { show: true, node }'), '开始任务在进玩法之前');
  assert.match(body, /row\.kind === 'QUEST'[\s\S]*row\.operation === 'QUEST_START'/);
  assert.match(body, /this\._hasGenericProofFlow\(\)/, '只对通用局主题');
  assert.match(body, /themeCostChoice: \{ row: questStart, selected: -1 \}/, '要选费用组时走背包同一张选择单');
  assert.match(body, /ok === true && this\._questStartFor === String\(node\.nodeId\)\) this\.startGame\(node\)/, '开始成功才进玩法');
});
