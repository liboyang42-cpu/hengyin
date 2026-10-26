const test = require('node:test');
const assert = require('node:assert/strict');
const { project } = require('../../pages/play/utils/arrival-guard-view.js');
test('guard reason and precise log ID survive without inventing an appeal action', () => {
  const r = project({ errorCode: 'ARRIVE_SPEED', guardLogId: '9007199254740993', appealable: true, msg: '请核对位置' }, '12');
  assert.equal(r.guardLogId, '9007199254740993'); assert.equal(r.reason, 'ARRIVE_SPEED');
  assert.match(r.next, /未提交申诉/); assert.equal(r.endpoint, undefined);
});
test('missing accuracy is not cheating; unsafe IDs and transport failures cannot create appeal references', () => {
  assert.match(project({ errorCode: 'ARRIVE_ACCURACY_MISSING', appealable: false }, '12').next, /不是作弊/);
  assert.equal(project({ errorCode: 'ARRIVE_SPEED', guardLogId: 9007199254740992 }, '12').guardLogId, '');
  assert.equal(project({ errorCode: 'ARRIVE_SPEED', httpFail: true }, '12'), null);
});

test('original run and node reference stay precise; a different node rejection is not displayed', () => {
  const response = { errorCode: 'ARRIVE_SPEED', nodeId: '12', routeSessionId: '9007199254740995',
    guardLogId: '9007199254740993', runtimeInstance: 'original', authorityEpoch: 2,
    appealable: true, appealBizType: 'play_arrive_guard' };
  const projected = project(response, '12');
  assert.equal(projected.routeSessionId, response.routeSessionId);
  assert.equal(projected.runtimeInstance, 'original'); assert.equal(projected.authorityEpoch, 2);
  assert.equal(projected.appealBizType, 'play_arrive_guard'); assert.equal(projected.appealable, true);
  assert.equal(projected.endpoint, undefined); assert.equal(project(response, '13'), null);
});
