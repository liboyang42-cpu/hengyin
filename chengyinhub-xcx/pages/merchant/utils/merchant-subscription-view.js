// BL-108 商家已生效权益(只读):/api/merchant/subscription 返回 merchant_subscription 中
// is_active=1 且未到期的行(字段以 MerchantSubscription 实体为准)。后端不排除开始日期还没到的预购行,
// 所以这里再按 startDate 过滤掉「尚未生效」的行,保证面板只列已生效权益。只展示权益名与有效期,
// 不展示价格、不做购买/续费。
const pad = (n) => (n < 10 ? '0' + n : '' + n);

function formatDay(value) {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'string') {
    const m = value.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (m) return m[1] + '-' + m[2] + '-' + m[3];
  }
  const d = new Date(value);
  if (isNaN(d.getTime())) return '';
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

// 'YYYY-MM-DD' / 'YYYY-MM-DD HH:mm:ss' 按本地时间解析(不用 Date 解析带 '-' 的串,iOS 会得到 NaN);解析不了返回 NaN
function parseLocalTime(value) {
  if (value === null || value === undefined || value === '') return NaN;
  if (typeof value === 'number') return value;
  const m = String(value).match(/^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?/);
  if (!m) return NaN;
  return new Date(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0), +(m[6] || 0)).getTime();
}

/** tiers: 装修页已有的升级权益 [{key,name}],用来把 subscriptionType 翻成中文名。now 仅供单测注入。 */
function buildActiveSubscriptions(rows, tiers, now) {
  if (!Array.isArray(rows)) return [];
  const names = {};
  const at = typeof now === 'number' ? now : Date.now();
  (tiers || []).forEach((t) => { if (t && t.key) names[t.key] = t.name; });
  return rows.filter((r) => {
    if (!r || r.isActive === 0 || r.isActive === '0') return false;
    const start = parseLocalTime(r.startDate);
    return !(start > at); // 开始日期还没到 = 尚未生效,不列为已生效权益
  }).map((r, i) => {
    const end = formatDay(r.endDate);
    let usage = '';
    if (r.maxUsage !== null && r.maxUsage !== undefined && r.maxUsage !== '') {
      usage = '已用 ' + (Number(r.usedCount) || 0) + '/' + r.maxUsage + ' 次';
    }
    return {
      key: r.id != null ? String(r.id) : 'sub-' + i,
      name: names[r.subscriptionType] || '其他权益',
      period: r.endDate ? (end ? '有效期至 ' + end : '') : '长期有效',
      usage: usage
    };
  });
}

module.exports = { buildActiveSubscriptions, formatDay };
