const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const workspace = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/workspace.vue'),
  'utf8'
);

test('主题工作台在履约 Tab 展示带队场次和集合事实，不暴露到场名册', () => {
  assert.match(workspace, /销售、核销与带队场次/);
  assert.match(workspace, /clubFulfillment\(\)\s*\{\s*return this\.workspace\.clubFulfillment \|\| \{\}\s*\}/);
  assert.match(workspace, /clubFulfillment\.sessions \|\| \[\]/);
  assert.match(workspace, /row\.assemblyTime/);
  assert.match(workspace, /row\.assemblyLocation/);
  assert.match(workspace, /row\.leaderDisplayName/);
  assert.match(workspace, /row\.currentChapterId/);
  assert.match(workspace, /clubFulfillment\.attendanceCount/);
  assert.doesNotMatch(workspace, /row\.attendees|attendee\.memberDisplayName|attendee\.arrivedAt/);
  assert.match(workspace, /leadStatusLabel/);
  assert.match(workspace, /leadStatusTone/);
  assert.match(workspace, /状态待确认/);
  assert.doesNotMatch(workspace, /待激活/,
    '缺失的带队状态不得伪装为待激活');
});
