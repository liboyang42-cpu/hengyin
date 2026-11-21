'use strict';
// x-life-games:偏好题汇总的展示数据(纯函数,可单测)。回答不够时只给总数,不给分布。
function view(data) {
  const d = data || {};
  const total = Number(d.total) || 0;
  const min = Number(d.minResponses) || 3;
  const enough = !!d.enoughResponses && Array.isArray(d.results);
  const rows = enough ? d.results.map((r) => ({
    code: String(r.resultCode || ''),
    title: String(r.title || r.resultCode || ''),
    responses: Number(r.responses) || 0,
    percent: Math.max(0, Math.min(100, Number(r.percent) || 0))
  })) : [];
  return { total, min, enough, rows };
}

module.exports = { view };
