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
    // calendarKey 给 wx:key 用:同一主题同一天可以有多场,不能拿 id 当键。
    eventsByDate[key].push(Object.assign({}, event, {
      calendarKey: event.calendarKey || ('topic-' + String(event.id) + '-' + eventsByDate[key].length),
    }));
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

const SESSION_STATE_SUFFIX = { UNPUBLISHED: '（待审核）', CANCELLED: '（已取消）' };

function strictPositiveId(value) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null;
}

/**
 * W10a #19:把 /api/club/calendar/sessions 的响应收成月历事件。
 * 响应必须回显本次请求的 clubId 与 month,否则视为过期/错位响应返回 null;
 * 任一场次字段不合法也整体返回 null —— 月历宁可退回「仅已加载主题」,也不画半张排期。
 */
function shapeCalendarSessions(raw, clubId, monthKey) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  if (String(raw.clubId) !== String(clubId) || raw.month !== monthKey) return null;
  if (!Array.isArray(raw.sessions) || typeof raw.truncated !== 'boolean') return null;
  const prefix = monthKey + '-';
  const events = [];
  for (let index = 0; index < raw.sessions.length; index += 1) {
    const item = raw.sessions[index];
    const activityId = strictPositiveId(item && item.activityId);
    const date = item && typeof item.date === 'string' ? item.date : '';
    if (!activityId || date.indexOf(prefix) !== 0 || !validEventDate({ dateText: date })) return null;
    const state = item.state === 'UNPUBLISHED' || item.state === 'CANCELLED' ? item.state : 'PUBLISHED';
    const topicId = strictPositiveId(item.topicId);
    events.push({
      // id 进 goTopic:场次挂在主题下,点进的是主题详情;没有主题的场次不可点。
      id: topicId,
      calendarKey: 'session-' + activityId,
      activityId,
      name: String(item.name || '场次') + (SESSION_STATE_SUFFIX[state] || ''),
      dateText: date,
      state,
      updatedAt: typeof item.updatedAt === 'string' ? item.updatedAt : '',
    });
  }
  return {
    monthKey,
    events,
    truncated: raw.truncated,
    updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : '',
  };
}

const LOADED_TOPICS_ONLY_NOTICE = '月历仅展示已加载主题';

/** 月历顶部说明。只有拿到本月真实场次才说「按场次」;其余一律声明只是已加载主题,不暗示完整排期。 */
function calendarNotice(state, sessions) {
  if (state === 'ready' && sessions) {
    if (sessions.truncated) return '本月场次较多，仅显示前 ' + sessions.events.length + ' 场';
    const updated = String(sessions.updatedAt || '').slice(5, 16);
    return updated ? '按本月场次显示 · 更新于 ' + updated : '按本月场次显示';
  }
  if (state === 'loading') return LOADED_TOPICS_ONLY_NOTICE + '，场次加载中…';
  if (state === 'error') return LOADED_TOPICS_ONLY_NOTICE + '，场次加载失败';
  return LOADED_TOPICS_ONLY_NOTICE;
}

module.exports = {
  LOADED_TOPICS_ONLY_NOTICE,
  buildMonthCalendar,
  calendarNotice,
  shapeCalendarSessions,
  shiftMonth,
};
