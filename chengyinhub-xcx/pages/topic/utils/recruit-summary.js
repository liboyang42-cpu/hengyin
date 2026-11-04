/* j2 F-09:招商弹窗顶部三格与下面的列表同一个来源 —— 数字就是列表的行数。
   · 城市定向:站点 = 节点。已招 = 节点总数 - 「还没人接」行数;还没人接 = 那张列表的行数。
   · 自由探索(承接后才有节点):站点 = 章节行,按章节行的已承接 / 开放计。
   · 待处理申请 = 「收到的申请」行数 + 可处理的「章节承接申请」行数。
   · 「已确认」只列已确认(已中标 / 已通过)的商家;待审核的在「收到的申请」里,驳回的不列。 */

function list(value) {
  return Array.isArray(value) ? value : [];
}

function recruitSummary(input) {
  const src = input || {};
  const stations = list(src.stations);
  const recruit = src.recruit || {};
  const applies = list(src.applies && src.applies.rows);
  const pending = applies.length + list(src.chapterApps).filter((a) => a && a.actionable).length;
  const freeExplore = Number((src.topic || {}).productType) === 2;
  const nodeTotal = Number(recruit.nodeTotal) || 0;
  if ((!freeExplore && nodeTotal > 0) || !stations.length) {
    const open = list(recruit.openNodes).length;
    return { taken: Math.max(0, nodeTotal - open), total: nodeTotal, pending, open };
  }
  return {
    taken: stations.filter((r) => r && r.state === 'taken').length,
    total: stations.length,
    pending,
    open: stations.filter((r) => r && r.state === 'open').length,
  };
}

function confirmedMerchants(rows) {
  return list(rows).filter((m) => m && (Number(m.auditStatus) === 1 || Number(m.status) === 1));
}

module.exports = { recruitSummary, confirmedMerchants };
