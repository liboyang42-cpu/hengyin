// BL-108 商家已生效权益(只读):/api/merchant/subscription 返回 merchant_subscription 中
// is_active=1 且在有效期内的行(字段以 MerchantSubscription 实体为准)。只展示权益名与有效期,
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

/** tiers: 装修页已有的升级权益 [{key,name}],用来把 subscriptionType 翻成中文名。 */
function buildActiveSubscriptions(rows, tiers) {
  if (!Array.isArray(rows)) return [];
  const names = {};
  (tiers || []).forEach((t) => { if (t && t.key) names[t.key] = t.name; });
  return rows.filter((r) => r && r.isActive !== 0 && r.isActive !== '0').map((r, i) => {
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
