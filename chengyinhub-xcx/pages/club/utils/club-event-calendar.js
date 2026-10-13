function parseMonthKey(monthKey) {
  const match = /^(\d{4})-(\d{2})$/.exec(String(monthKey || ''));
  if (!match) throw new Error('月份格式不合法');
  const year = Number(match[1]);
  const month = Number(match[2]);
  if (!Number.isInteger(year) || year < 1970 || year > 9999 || month < 1 || month > 12) {
    throw new Error('月份格式不合法');
  }
  return { year, month };
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

function dateText(date) {
  return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
}

function validEventDate(event) {
  const raw = event && (event.dateText || event.startDate);
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(raw || ''));
  if (!match) return '';
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(year, month - 1, day);
  if (parsed.getFullYear() !== year || parsed.getMonth() !== month - 1 || parsed.getDate() !== day) return '';
  return match[1] + '-' + match[2] + '-' + match[3];
}

function buildMonthCalendar(monthKey, topics) {
  const parsed = parseMonthKey(monthKey);
  const eventsByDate = Object.create(null);
  (Array.isArray(topics) ? topics : []).forEach(function (event) {
    const key = validEventDate(event);
    if (!key) return;
    if (!eventsByDate[key]) eventsByDate[key] = [];
    eventsByDate[key].push(event);
  });

  const first = new Date(parsed.year, parsed.month - 1, 1);
  const mondayOffset = (first.getDay() + 6) % 7;
  const start = new Date(parsed.year, parsed.month - 1, 1 - mondayOffset);
  const cells = [];
  for (let index = 0; index < 42; index += 1) {
    const current = new Date(start.getFullYear(), start.getMonth(), start.getDate() + index);
    const key = dateText(current);
    cells.push({
      key,
      dateText: key,
      day: current.getDate(),
      inMonth: current.getFullYear() === parsed.year && current.getMonth() === parsed.month - 1,
      events: eventsByDate[key] || [],
    });
  }
  return {
    monthKey: parsed.year + '-' + pad2(parsed.month),
    title: parsed.year + '年' + parsed.month + '月',
    cells,
  };
}

function shiftMonth(monthKey, delta) {
  const parsed = parseMonthKey(monthKey);
  const offset = Number(delta);
  if (!Number.isInteger(offset)) throw new Error('月份偏移不合法');
  const target = new Date(parsed.year, parsed.month - 1 + offset, 1);
  return target.getFullYear() + '-' + pad2(target.getMonth() + 1);
}

async function fetchMonthActivities(sendRequest, query, current) {
  parseMonthKey(query.month);
  const rows = [];
  let afterId = 0;
  let throughId;
  let visibilityScope;
  for (;;) {
    if (!current()) throw new Error('月份已切换');
    const data = { clubId: query.clubId, month: query.month };
    if (throughId !== undefined) { data.afterId = afterId; data.throughId = throughId; }
    const response = await new Promise((resolve, reject) => sendRequest({
      hideLoading: true, url: '/api/club/calendar', method: 'GET', data,
      success(response) { resolve(response); },
      fail(error) { reject(error); },
    }));
    if (!current()) throw new Error('月份已切换');
    const page = response && response.data;
    if (!response || Number(response.code) !== 200 || !page
        || page.clubId !== query.clubId || page.month !== query.month || page.timeZone !== 'Asia/Shanghai'
        || !['CLUB_MEMBER', 'ACTIVITY_OWNER', 'ANONYMOUS'].includes(page.visibilityScope)
        || (visibilityScope !== undefined && page.visibilityScope !== visibilityScope)
        || !Array.isArray(page.rows) || page.rows.length > 100 || typeof page.hasMore !== 'boolean'
        || !Number.isSafeInteger(page.throughId) || page.throughId < 0
        || !Number.isSafeInteger(page.nextAfterId) || page.nextAfterId < afterId
        || page.nextAfterId > page.throughId || typeof page.readAt !== 'string' || !page.readAt
        || (throughId !== undefined && page.throughId !== throughId)) {
      throw new Error('月份分页结果未确认');
    }
    let last = afterId;
    page.rows.forEach(event => {
      if (!event || !Number.isSafeInteger(event.id) || event.id <= last || event.id > page.throughId
          || validEventDate(event).slice(0, 7) !== query.month) throw new Error('场次分页或日期不一致');
      last = event.id;
    });
    if (page.nextAfterId !== last || (!page.hasMore && last !== page.throughId) || (page.hasMore && (page.rows.length !== 100 || last >= page.throughId))) {
      throw new Error('月份分页未完成');
    }
    rows.push(...page.rows);
    if (!page.hasMore) return { rows, readAt: page.readAt, visibilityScope: page.visibilityScope };
    afterId = last;
    throughId = page.throughId;
    visibilityScope = page.visibilityScope;
  }
}

module.exports = { buildMonthCalendar, shiftMonth, fetchMonthActivities };
