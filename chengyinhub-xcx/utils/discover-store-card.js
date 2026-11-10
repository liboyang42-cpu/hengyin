'use strict';
// r2-store 发现页通票入口卡(refs3/STRUCTURE.md「Explore feed card」):卡在帖文流第一条之后,不做整屏付费墙。
// 只读 /api/marketing/product/list 的在售商品;没有城市包/时限通票/年卡在售时整卡不出。
// 平台会员(自玩票)服务端还没有商品与权益,这里不出会员卡。
const PASS_KINDS = { 2: true, 3: true, 4: true }; // 城市包 / 时限通票 / 固定期年卡(单关、团体、礼品、加座不进入口卡)

function num(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v)) ? Number(v) : null;
}
function yuan(n) {
  const r = Math.round(n * 100) / 100;
  return '¥' + (Number.isInteger(r) ? String(r) : r.toFixed(2));
}

function storeCard(products) {
  if (!Array.isArray(products)) return null;
  const passes = products.filter((p) => p && p.productId && PASS_KINDS[Number(p.productKind)] && num(p.cashPrice) !== null
    && Array.isArray(p.items) && p.items.length);
  if (!passes.length) return null;
  passes.sort((a, b) => num(a.cashPrice) - num(b.cashPrice));
  const first = passes[0];
  const cities = passes.map((p) => p.saleCity).filter(Boolean);
  const oneCity = cities.length === passes.length && cities.every((c) => c === cities[0]);
  return {
    title: oneCity ? cities[0] + '通票' : '城市通票',
    meta: passes.length === 1 ? first.items.length + ' 个主题' : passes.length + ' 种通票',
    price: yuan(num(first.cashPrice)) + (passes.length > 1 ? ' 起' : ''),
    covers: first.items.map((it) => it && it.topicTitle).filter(Boolean).slice(0, 3)
  };
}

module.exports = { storeCard };
