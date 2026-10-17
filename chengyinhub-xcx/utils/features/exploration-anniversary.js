// Inject app.sendRequest.bind(app) and getIdentity() => memberId + login-session epoch.
// Read-only private memories; no persistence, automatic message, reward or AI request.
function createExplorationAnniversaryClient(options) {
  if (!options || typeof options.sendRequest !== 'function' || typeof options.getIdentity !== 'function') throw new Error('需要请求通道和登录会话标识');
  let bound = String(options.getIdentity() || ''); let epoch = 0; let generation = 0; let disposed = false; let state = null;
  function sync() {
    if (disposed) throw new Error('回忆客户端已销毁');
    const owner = String(options.getIdentity() || '');
    if (owner !== bound) { epoch++; generation++; state = null; bound = owner; }
    if (!owner) throw new Error('请先登录'); return owner;
  }
  function active(owner, token, read) {
    if (disposed || owner !== String(options.getIdentity() || '') || token !== epoch || read !== generation) throw new Error('回忆读取上下文已变化');
  }
  function validDay(month, day) {
    if (!Number.isInteger(month) || !Number.isInteger(day) || month < 1 || month > 12 || day < 1) return false;
    return day <= new Date(Date.UTC(2000, month, 0)).getUTCDate(); // Leap day allowed for explicit preview.
  }
  function id(value) { return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && (value.length < 19 || value <= '9223372036854775807'); }
  function greater(a, b) { return a.length !== b.length ? a.length > b.length : a > b; }
  function stamp(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$/.test(value)) return null;
    const parsed = new Date(value.replace(' ', 'T') + 'Z');
    return !isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 23).replace('T', ' ') === value ? parsed : null;
  }
  function projection(value, input) {
    if (!value || value.timeZone !== 'Asia/Shanghai' || value.leapDayRule !== 'EXACT_MONTH_DAY_ONLY'
      || value.coverage !== 'RECORDED_OWN_COMPLETED_SESSIONS_WITH_CURRENT_VISIBLE_ROUTE' || value.metadataMode !== 'CURRENT_ROUTE_METADATA'
      || !validDay(value.month, value.day) || !Number.isInteger(value.referenceYear) || value.referenceYear < 2000 || value.referenceYear > 9999
      || !Number.isSafeInteger(value.asOfMs) || value.asOfMs < 0 || !Array.isArray(value.items) || value.items.length > input.limit
      || typeof value.hasMore !== 'boolean' || (value.hasMore ? typeof value.nextCursor !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(value.nextCursor) || value.items.length !== input.limit : value.nextCursor !== null)) throw new Error('回忆响应未确认');
    const today = new Date(value.asOfMs + 8 * 3600000);
    if (today.getUTCFullYear() !== value.referenceYear || (input.month != null ? value.month !== input.month || value.day !== input.day
      : value.month !== today.getUTCMonth() + 1 || value.day !== today.getUTCDate())
      || (input.referenceYear != null && value.referenceYear !== input.referenceYear)
      || (input.cursor != null && value.hasMore && value.nextCursor === input.cursor)) throw new Error('回忆日期或分页上下文不匹配');
    const seen = new Set(); let previous = input.boundary || null;
    const items = value.items.map(item => {
      const completed = item && stamp(item.completedAt);
      if (!item || !id(item.id) || !id(item.topicId) || seen.has(item.id) || (item.title !== null && typeof item.title !== 'string')
        || !completed || !Number.isInteger(item.year) || item.year !== completed.getUTCFullYear() || item.year >= value.referenceYear
        || completed.getUTCMonth() + 1 !== value.month || completed.getUTCDate() !== value.day
        || !Number.isInteger(item.yearsAgo) || item.yearsAgo !== value.referenceYear - item.year
        || (previous && (previous.completedAt < item.completedAt || (previous.completedAt === item.completedAt && !greater(previous.id, item.id))))) throw new Error('回忆记录不合法');
      seen.add(item.id); previous = item;
      return { id: item.id, topicId: item.topicId, title: item.title, completedAt: item.completedAt, year: item.year, yearsAgo: item.yearsAgo };
    });
    return { month: value.month, day: value.day, referenceYear: value.referenceYear, timeZone: value.timeZone, asOfMs: value.asOfMs,
      coverage: value.coverage, metadataMode: value.metadataMode, leapDayRule: value.leapDayRule, items, hasMore: value.hasMore, nextCursor: value.nextCursor };
  }
  function fetch(input) {
    let owner;
    try { owner = sync(); } catch (error) { return Promise.reject(error); }
    const token = epoch; const read = ++generation; state = null;
    const data = { limit: input.limit };
    if (input.month != null) { data.month = input.month; data.day = input.day; }
    if (input.cursor != null) data.cursor = input.cursor;
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => finish(new Error('回忆请求超时，请重新加载')), options.timeoutMs || 15000);
      function finish(error, response) {
        if (settled) return; settled = true; clearTimeout(timer);
        try { active(owner, token, read); } catch (changed) { return reject(changed); }
        if (error) return reject(error);
        if (!response || response.code !== 200 || !response.data) return reject(new Error('回忆读取未确认'));
        resolve(response.data);
      }
      try { options.sendRequest({ url: '/api/exploration-anniversary', method: 'GET', data, hideLoading: true, autoErrorToast: false,
        success: response => finish(null, response), fail: () => finish(new Error('回忆读取失败')) }); }
      catch (error) { finish(error); }
    }).then(value => {
      active(owner, token, read); const clean = projection(value, input); state = { data: clean, limit: input.limit };
      return JSON.parse(JSON.stringify(clean));
    });
  }
  return {
    load(month, day, limit) {
      try { sync(); } catch (error) { return Promise.reject(error); }
      // A new selector is a new intent even when its local date/limit validation fails.
      generation++; state = null;
      if ((month != null || day != null) && !validDay(month, day)) return Promise.reject(new Error('请提供有效的月份和日期'));
      const size = limit == null ? 20 : limit;
      if (!Number.isInteger(size) || size < 1 || size > 50) return Promise.reject(new Error('分页数量须为1至50'));
      return fetch({ month, day, limit: size });
    },
    next() {
      try { sync(); } catch (error) { return Promise.reject(error); }
      if (!state || !state.data.hasMore) return Promise.reject(new Error('没有下一页，请重新加载'));
      const current = state;
      return fetch({ month: current.data.month, day: current.data.day, referenceYear: current.data.referenceYear, cursor: current.data.nextCursor,
        boundary: current.data.items[current.data.items.length - 1], limit: current.limit });
    },
    snapshot() { sync(); return state ? JSON.parse(JSON.stringify(state.data)) : null; },
    dispose() { disposed = true; epoch++; generation++; state = null; }
  };
}
module.exports = { createExplorationAnniversaryClient };
