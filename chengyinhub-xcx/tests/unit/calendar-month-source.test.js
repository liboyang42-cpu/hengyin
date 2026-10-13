const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const { fetchMonthActivities, buildMonthCalendar } = require('../../pages/club/utils/club-event-calendar');
const event = id => ({ id, startDate: '2028-02-29 23:59:59', name: '实际场次' });
const page = (rows, more = false, through = rows.length ? rows.at(-1).id : 0) => ({ code: 200, data: {
  clubId: 7, month: '2028-02', timeZone: 'Asia/Shanghai', rows, hasMore: more,
  visibilityScope: 'CLUB_MEMBER',
  throughId: through, nextAfterId: rows.length ? rows.at(-1).id : 0, readAt: '2028-02-01 08:00:00',
} });
const query = { clubId: 7, month: '2028-02' };

test('complete month reads beyond 100 rows with original upper bound and real leap-day dates', async () => {
  const calls = [];
  const result = await fetchMonthActivities(req => {
    calls.push(req.data);
    req.success(calls.length === 1 ? page(Array.from({ length: 100 }, (_, i) => event(i + 1)), true, 102)
      : page([event(101), event(102)]));
  }, query, () => true);
  assert.equal(result.rows.length, 102);
  assert.deepEqual(calls[1], { ...query, afterId: 100, throughId: 102 });
  assert.equal(buildMonthCalendar(query.month, result.rows).cells.find(c => c.dateText === '2028-02-29').events.length, 102);
});

test('empty month is confirmed; transport, business and malformed responses remain failures', async () => {
  assert.equal((await fetchMonthActivities(req => req.success(page([])), query, () => true)).rows.length, 0);
  await assert.rejects(fetchMonthActivities(req => req.fail(new Error('offline')), query, () => true));
  await assert.rejects(fetchMonthActivities(req => req.success({ code: 500, data: [] }), query, () => true));
  await assert.rejects(fetchMonthActivities(req => req.success(page([], false, 2)), query, () => true));
});

test('nonmember empty remains explicitly creator-only and is never a complete club empty month', async () => {
  const response = page([]);
  response.data.visibilityScope = 'ACTIVITY_OWNER';
  const result = await fetchMonthActivities(req => req.success(response), query, () => true);
  assert.equal(result.visibilityScope, 'ACTIVITY_OWNER');
  const view = fs.readFileSync(require.resolve('../../pages/club/detail/index.wxml'), 'utf8');
  assert.match(view, /eventCalendarScope !== 'CLUB_MEMBER'/);
  assert.match(view, /空结果不代表俱乐部本月没有场次/);
});

test('duplicate/overlap rows, wrong month, viewer club and shifted cursor are rejected', async () => {
  for (const response of [page([event(1), event(1)]), page([{ ...event(1), startDate: '2028-03-01 00:00:00' }]),
    { ...page([]), data: { ...page([]).data, clubId: 8 } },
    { ...page([event(1)]), data: { ...page([event(1)]).data, nextAfterId: '1' } }]) {
    await assert.rejects(fetchMonthActivities(req => req.success(response), query, () => true));
  }
  let calls = 0;
  await assert.rejects(fetchMonthActivities(req => {
    calls++;
    req.success(calls === 1 ? page(Array.from({ length: 100 }, (_, i) => event(i + 1)), true, 102)
      : page([event(100), event(102)]));
  }, query, () => true));
  calls = 0;
  await assert.rejects(fetchMonthActivities(req => {
    calls++;
    const response = calls === 1 ? page(Array.from({ length: 100 }, (_, i) => event(i + 1)), true, 102)
      : page([event(101), event(102)]);
    if (calls > 1) response.data.visibilityScope = 'ACTIVITY_OWNER';
    req.success(response);
  }, query, () => true));
});

test('second page failure never returns a partial month and stale response never requests next page', async () => {
  let calls = 0;
  await assert.rejects(fetchMonthActivities(req => {
    calls++;
    if (calls === 1) req.success(page(Array.from({ length: 100 }, (_, i) => event(i + 1)), true, 102));
    else req.fail(new Error('offline'));
  }, query, () => true));
  let current = true;
  let request;
  const result = fetchMonthActivities(req => { request = req; }, query, () => current);
  current = false;
  request.success(page([event(1)]));
  await assert.rejects(result, /月份已切换/);
});

test('page isolates fast month changes, identity changes and errors from topic list state', async () => {
  const source = fs.readFileSync(require.resolve('../../pages/club/detail/index.js'), 'utf8');
  const methods = source.slice(source.indexOf('  async loadEventCalendar()'), source.indexOf('  loadMembers()'));
  let viewer = 1;
  const requests = [];
  const context = { buildMonthCalendar, fetchMonthActivities, isDemoClubId: () => false,
    app: { getUserID: () => viewer, sendRequest: req => requests.push(req) } };
  const subject = vm.runInNewContext('({' + methods + '})', context);
  subject.data = { clubId: 7, eventCalendarMonth: '2028-02', topicState: 'ready' };
  subject.setData = patch => Object.assign(subject.data, patch);
  const old = subject.loadEventCalendar();
  const latest = subject.loadEventCalendar();
  requests[1].success(page([event(2)]));
  await latest;
  requests[0].success(page([event(1)]));
  await old;
  assert.equal(subject.data.eventCalendarCells.find(c => c.dateText === '2028-02-29').events[0].id, 2);
  const differentViewer = subject.loadEventCalendar();
  viewer = 2;
  requests[2].success(page([event(3)]));
  await differentViewer;
  assert.equal(subject.data.eventCalendarHasEvents, false);
  const failure = subject.loadEventCalendar();
  requests[3].fail(new Error('offline'));
  await failure;
  assert.equal(subject.data.eventCalendarState, 'error');
  assert.equal(subject.data.topicState, 'ready');
});
