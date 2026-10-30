// 主题工作台「分润明细」弹窗的口径(走查 v2 R09,业主 2026-10-30 定稿原型 p7)。
// 只用两份已有数据:/api/coop/finance 里本主题那一行(到手、已售、平台、商家合计、结算)
// 与 /api/topic/pricing/preview 的阵容条款(平台费率 + 每个合作方的分成方式 / 比例 / 每人固定费)。
// 比例条只画「按票款分成」的部分;按核销固定费、引流档不进比例条,写在下面一行,不编百分比。

const COLORS = { host: '#A5A6E8', merchant: '#F2C46B', club: '#7FD1A6', platform: '#5A5A60' };

function num(v) {
  const n = Number(v);
  return v == null || v === '' || !Number.isFinite(n) ? null : n;
}

function money(v) {
  const n = num(v);
  return n == null || n < 0 ? '—' : '¥' + n.toFixed(2);
}

function pct(n) {
  return Math.round(n * 10) / 10 + '%';
}

// 业主 2026-10-30:档位卡上面只写商家 / 俱乐部名字,下面只写那个数
function tierText(row) {
  const mode = Number(row && row.shareMode);
  if (mode === 1 && num(row.shareRate) != null) return pct(num(row.shareRate));
  if (mode === 2 && num(row.fixedFee) != null) return '¥' + num(row.fixedFee).toFixed(2) + '/人';
  return '引流';
}

// lineup:[{toType, toId, shareMode, shareRate(百分数 0–100), fixedFee, name}]
// platformRate:后端 PricingResult.platformRate 是小数(0.10 = 10%,见 PricingServiceImpl.compute 的 1−rate−share)
function buildSplit(lineup, platformRate) {
  const rows = Array.isArray(lineup) ? lineup : [];
  const raw = num(platformRate);
  if (raw == null || raw < 0) return null;
  const platform = raw <= 1 ? raw * 100 : raw;
  let merchantRate = 0;
  let clubRate = 0;
  const fixed = [];
  rows.forEach(function (r) {
    const mode = Number(r && r.shareMode);
    const rate = num(r && r.shareRate);
    if (mode === 1 && rate != null) {
      if (r.toType === 'club') clubRate += rate; else merchantRate += rate;
    } else if (mode === 2 && num(r.fixedFee) != null) {
      fixed.push((r.name || (r.toType === 'club' ? '俱乐部' : '商家')) + ' 每人 ¥' + num(r.fixedFee).toFixed(2));
    }
  });
  const hostRate = Math.max(0, 100 - platform - merchantRate - clubRate);
  const parts = [
    { key: 'host', label: '主办', rate: hostRate },
    { key: 'merchant', label: '承接商家', rate: merchantRate },
    { key: 'club', label: '带团俱乐部', rate: clubRate },
    { key: 'platform', label: '平台', rate: platform },
  ].filter(function (p) { return p.rate > 0; }).map(function (p) {
    return Object.assign(p, { color: COLORS[p.key], rateText: pct(p.rate), flex: Math.max(1, Math.round(p.rate)) });
  });
  return {
    parts,
    fixedText: fixed.length ? '另按核销:' + fixed.join(' · ') : '',
    tiers: rows.map(function (r) { return { key: (r.toType || '') + ':' + (r.toId || ''), toType: r.toType, toId: r.toId, name: r.name || '', tier: tierText(r) }; }),
  };
}

function buildPartyRows(financeRow, split) {
  const t = financeRow || {};
  const rateOf = function (key) {
    const p = split && split.parts.find(function (x) { return x.key === key; });
    return p ? p.rateText + ' · ' : '';
  };
  return [
    { key: 'host', label: '主办(我)', sub: rateOf('host') + '按核销结算', amount: money(t.myIncome), color: COLORS.host },
    { key: 'merchant', label: '承接商家', sub: rateOf('merchant') + (num(t.merchantPaid) != null ? '已打款 ' + money(t.merchantPaid) : '按核销结算'), amount: money(t.merchantTotal), color: COLORS.merchant },
    { key: 'platform', label: '平台', sub: rateOf('platform') + '服务费', amount: money(t.platformAmount), color: COLORS.platform },
  ];
}

module.exports = { buildSplit, buildPartyRows, money, tierText };
