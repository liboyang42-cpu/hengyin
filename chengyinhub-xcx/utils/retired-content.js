// 2026-10-23 用户确认移除的旧测试内容；只在新版展示层处理，保留服务端记录和模板。
const RETIRED_TOPICS = {
  1: '上海滨江 10KM 骑行',
  6: '成都宽窄巷子辣味食材寻味',
  7: '上海沉浸式戏剧',
  8: '西安大唐不夜城灯光秀',
  990052: '城市藏宝猎人',
  990053: '异常事务局：不存在的坐标',
  990054: '时间污染：被删除的今天',
  990055: '末日广播：最后一座中继站',
  990056: '野生邻居观察局',
  990062: '预制人生 · 第一站'
};

function isRetiredTopic(row) {
  return !!row && !!RETIRED_TOPICS[row.id] && row.name === RETIRED_TOPICS[row.id]
    && row.isTemplate !== 1 && row.isTemplate !== '1' && row.isTemplate !== true;
}

function filterRetiredContent(url, body) {
  const parts = String(url || '').split('?')[0].split('/');
  if (!body || String(body.code) !== '200') return body;
  if (parts.length !== 4 || parts[1] !== 'api') return body;
  const domain = parts[2], action = parts[3];
  if (domain === 'topic' && action === 'info-to-user' && isRetiredTopic(body.data)) {
    return { code: 404, msg: '该测试活动已下架', data: null };
  }
  const reads = { topic: ['list', 'like_list'], club: ['topics'], activity: ['list', 'like_list'] };
  if (!reads[domain] || reads[domain].indexOf(action) < 0) return body;
  const activity = domain === 'activity';
  const data = body.data;
  const rows = Array.isArray(data) ? data : (data && data.rows);
  if (!Array.isArray(rows)) return body;
  const visible = rows.filter(function (row) {
    return activity ? !row || !RETIRED_TOPICS[row.topicId] : !isRetiredTopic(row);
  });
  if (visible.length === rows.length) return body;
  // 分页时保留服务端游标总数，避免删掉当前页样本后提前终止后续真实内容的加载。
  const next = Array.isArray(data) ? visible : Object.assign({}, data, {
    rows: visible,
    total: Number(data.total) === rows.length ? visible.length : data.total
  });
  return Object.assign({}, body, { data: next });
}

module.exports = { isRetiredTopic, filterRetiredContent };
