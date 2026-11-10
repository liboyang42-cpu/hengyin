// qx-b4 §26.1:合作局共享帮助 VIEW/USE/SKIP 与 QUEST_RESOLVE 的 answerRevision。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const c = require('../../pages/play/utils/coop-help.js');

const cooperation = { version: 1, gameSessionId: 50, authorityEpoch: 1, questions: [
  { runtimeInstance: 'inst-old', questKey: 'q1', nodeId: 11, completedAt: 100 },
  { runtimeInstance: 'inst-new', questKey: 'q1', nodeId: 11, answerRevision: 12, answerReceiptId: 3, confirmationStatus: 'WAITING_CONFIRMATION' },
  { runtimeInstance: 'inst-2', questKey: 'q2', nodeId: 12 }
] };

test('取本题当前实例;QUEST_RESOLVE 只在有答对版本时追加 answerRevision + runtimeInstance', () => {
  assert.equal(c.question(cooperation, 'q1').runtimeInstance, 'inst-new');
  assert.deepEqual(c.resolveFields(cooperation, 'q1'), { runtimeInstance: 'inst-new', answerRevision: 12 });
  assert.deepEqual(c.resolveFields(cooperation, 'q2'), {});
  assert.deepEqual(c.resolveFields(undefined, 'q1'), {});
});

test('请求体:身份全部来自服务端投影;VIEW 不带 requestId,USE 带 level', () => {
  assert.deepEqual(c.helpBody({ activityId: '70', cooperation, questKey: 'q2', operation: 'VIEW' }),
    { activityId: 70, gameSessionId: 50, nodeId: 12, runtimeInstance: 'inst-2', authorityEpoch: 1, capabilities: 'game.teamCooperation@1', operation: 'VIEW' });
  const use = c.helpBody({ activityId: 70, cooperation, questKey: 'q2', operation: 'USE', level: 2, requestId: 'coop-a' });
  assert.equal(use.level, 2); assert.equal(use.requestId, 'coop-a');
  assert.equal(c.helpBody({ activityId: 70, cooperation, questKey: 'nope', operation: 'VIEW' }), null);
  assert.equal(c.canHelp(cooperation, 'q2'), true);
  assert.equal(c.canHelp({ ...cooperation, gameSessionId: null }, 'q2'), false);
});

test('层级三态 + 重看;位置类不出现;跳过单独一行且服务端不可用时只给原因', () => {
  const { rows, skip } = c.levelRows([
    { level: 1, type: 'TEXT', usable: true, unlocked: false, free: true, costPoints: 5 },
    { level: 2, type: 'IMAGE', usable: true, unlocked: false, free: false, costPoints: 5 },
    { level: 3, type: 'ANSWER', usable: false, unlocked: false, free: false, costPoints: 9, reason: 'INSUFFICIENT_POINTS' },
    { level: 4, type: 'LETTER', usable: true, unlocked: true, free: false, costPoints: 5 },
    { level: 5, type: 'LOCATION', usable: true },
    { level: 6, type: 'SKIP', usable: false, reason: 'REAL_OPERATION_REQUIRED' }
  ]);
  assert.deepEqual(rows.map(r => [r.state, r.actionText]), [['free', '免费'], ['cost', '消耗 5 积分'], ['off', '积分不足'], ['unlocked', '重看']]);
  assert.deepEqual(skip, { level: 6, usable: false, reason: '需要现场完成，不能跳过' });
  assert.equal(c.freeText(2), '本局剩余免费 2 次');
  assert.equal(c.freeText(null), '');
});

test('回包:网络失败是未知,服务端拒绝保留原文', () => {
  assert.equal(c.outcome({ netFail: true }).state, 'UNKNOWN');
  assert.deepEqual(c.outcome({ code: 409, msg: '客户端尚未声明团队合作能力', reasonCode: 'GAME_CLIENT_CAPABILITY_REQUIRED' }),
    { state: 'REJECTED', message: '客户端尚未声明团队合作能力', reasonCode: 'GAME_CLIENT_CAPABILITY_REQUIRED' });
  assert.equal(c.outcome({ code: 200, data: { levels: [] } }).state, 'OK');
});

test('面板:未知重试沿用原 requestId;跳过二次确认文案;不画共享计时/全队分数', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/play/utils/coop-help-methods.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  const bag = fs.readFileSync(path.join(__dirname, '../../pages/play/utils/theme-backpack.js'), 'utf8');
  assert.match(js, /sheet\.unknown\.operation === 'USE' && sheet\.unknown\.level === level \? sheet\.unknown\.requestId/);
  assert.match(wxml, /本题记 0 分并标辅助完成/);
  assert.doesNotMatch(wxml.slice(wxml.indexOf('§26.1')), /elapsedMillis|puzzleScore|全队计时|全队分数/);
  assert.equal((bag.match(/coop\.resolveFields\(view\.cooperation, input\.contentId\)/g) || []).length, 2, 'preview 与 command 都带');
});
