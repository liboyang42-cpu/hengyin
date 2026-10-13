const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = file => fs.readFileSync(path.join(__dirname, '../..', file), 'utf8');

test('F0064 切月刷新但不把主题目录当完整场次，无缓存不宣称空月', () => {
  const js = read('pages/club/detail/index.js');
  const handler = js.slice(js.indexOf('  onShiftEventMonth(e)'), js.indexOf('  refreshEventCalendar()'));
  assert.match(handler, /this.loadTopics\(\)/);
  const wxml = read('pages/club/detail/index.wxml');
  assert.match(wxml, /完整月份场次尚未确认/);
  assert.match(wxml, /最近读取：/);
  assert.doesNotMatch(wxml, /title="本月暂无活动"/);
});


test('F0064 快速切月的旧目录响应不得覆盖新读回，失败不展示旧日期', () => {
  const vm = require('node:vm');
  const js = read('pages/club/detail/index.js');
  const method = js.slice(js.indexOf('  loadTopics(cb)'), js.indexOf('  scopedManageTopics('));
  const requests = [];
  const page = vm.runInNewContext('({' + method + '})', {
    app: { sendRequest: request => requests.push(request) },
    isDemoClubId: () => false, jsonBody: JSON.stringify, jsonHeader: () => ({}),
    isSuccess: response => response.code === 200,
    splitTopics: list => ({ upcomingTopics: list, endedTopics: [] }),
    decorateTopicCard: item => item, dateOf: () => '', toTimestamp: () => 0,
  });
  page.data = { clubId: 7 };
  page.setData = patch => Object.assign(page.data, patch);
  page.scopedManageTopics = list => list;
  page.refreshEventCalendar = page.applySentInviteFilter = page.updateOwnerStage = () => {};
  page.loadTopics();
  page.loadTopics();
  requests[1].success({ code: 200, data: [{ id: 22 }] });
  requests[0].success({ code: 200, data: [{ id: 11 }] });
  assert.equal(page._topics[0].id, 22);
  requests[0].fail();
  assert.equal(page.data.topicState, 'ready');
  page.loadTopics();
  requests[2].fail();
  assert.equal(page.data.topicState, 'error');
  assert.equal(page._topics.length, 0);
});
