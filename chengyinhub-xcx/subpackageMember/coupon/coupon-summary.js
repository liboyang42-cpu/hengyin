// BL-77 商家券领取/核销统计(只读):POST /api/merchant/coupon-summary
// 返回 { couponCount(在投放), couponTotal(未删除券数), received(累计领取), verified(累计核销) }。
// couponTotal 不是正整数 = 没有券 ⇒ 不展示;字段缺失/非法 ⇒ 不展示,不补 0。
function count(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 ? n : null;
}

function shapeCouponSummary(res) {
  if (!res || !(res.code === 200 || res.code === '200')) return null;
  const data = res.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const total = count(data.couponTotal);
  const received = count(data.received);
  const verified = count(data.verified);
  if (!total || received === null || verified === null) return null;
  return [
    { label: '累计领取', value: String(received) },
    { label: '累计核销', value: String(verified) },
  ];
}

module.exports = { shapeCouponSummary };
