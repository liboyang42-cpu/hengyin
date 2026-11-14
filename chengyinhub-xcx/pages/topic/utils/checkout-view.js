// r2-store 主题结账(自玩通行证)。结构参照 refs3/STRUCTURE.md §1,数字只读服务端:
// - 价格:/api/registration/quote(ownerType=1)的 payAmount;报价没回来时只用主题详情的 selfPlayPrice,
//   不自算折扣。划线价只在服务端给了 memberDiscountYuan > 0 时出现(原价 = 应付 + 减免)。
// - 同行席位:自玩通行证建单 orderNum 恒为 1,服务端没有席位上限/加座单价 ⇒ 不出步进器。
// - 积分:自玩通行证建单 pointUsed 恒为 0,报价 pointsUsable 未给 ⇒ 不出抵扣开关。
// - 城市通票:只列 /api/marketing/product/list 里 items 覆盖本主题的在售商品,点进原通行证详情页购买。
// - 信任行:退款只读报价 refundableNow / refundPercentNow;席位数是建单事实(自玩恒 1 人)。
//   有效期写在单次购买卡上,沿用 topic-motion.passWindow(与建单同口径),信任行不重复。

const { chapterCountText } = require('../../../utils/chapter-count.js');

function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
}

function yuan(v) {
  if (!isNum(v)) return '';
  const n = Math.round(Number(v) * 100) / 100;
  return '¥' + (Number.isInteger(n) ? String(n) : n.toFixed(2));
}

function headPills(info) {
  const d = info || {};
  const out = [];
  const type = Number(d.productType) === 2 ? '自由探索' : (Number(d.productType) === 1 ? '城市定向' : '');
  if (type) out.push(type);
  const chapters = Array.isArray(d.chaptersList) ? d.chaptersList.filter(Boolean) : [];
  const total = Number(d.totalChapterCount) > 0 ? Number(d.totalChapterCount) : chapters.length;
  const count = chapterCountText(d);
  if (count && total > 1) out.push(count);
  const nodes = chapters.reduce((n, c) => n + (Array.isArray(c.nodes) ? c.nodes.length : 0), 0);
  if (nodes > 0) out.push(nodes + ' 站');
  return out;
}

/** 覆盖本主题的在售通票:只认服务端 items[].topicId,不按城市名猜。 */
function coveringPasses(products, topicId) {
  const id = String(topicId || '');
  if (!id || !Array.isArray(products)) return [];
  return products.filter((p) => p && p.productId && Array.isArray(p.items)
    && p.items.some((it) => it && String(it.topicId) === id)
    && Number(p.productKind) !== 8 && Number(p.productKind) !== 7)
    .map((p) => {
      const meta = [];
      if (p.saleCity) meta.push(p.saleCity);
      meta.push(p.items.length + ' 个主题');
      // 期限只写天数(固定期/首用起算的完整文案在通行证详情页,出自 market-view.validityText)
      if ((Number(p.validityKind) === 1 || Number(p.validityKind) === 3) && isNum(p.validDays)) meta.push(Number(p.validDays) + ' 天有效');
      const price = isNum(p.cashPrice) ? yuan(p.cashPrice) : (isNum(p.pointsPrice) ? Number(p.pointsPrice) + ' 积分' : '');
      return { id: String(p.productId), name: p.productName || '', meta: meta.join(' · '), price };
    })
    .filter((p) => p.name)
    .slice(0, 3);
}

/** 服务端拒绝原话里带字段名/编号(如 sellingStartedAt)时不给玩家看,只写「暂不可购买」。 */
function playerText(msg) {
  const t = String(msg || '').trim();
  return t && !/[A-Za-z0-9_]{3,}/.test(t) ? t : '暂不可购买';
}

function refundTrust(quote) {
  if (!quote) return null;
  if (quote.refundableNow === true && isNum(quote.refundPercentNow) && Number(quote.refundPercentNow) > 0) {
    return { key: 'refund', icon: 'finance', label: Number(quote.refundPercentNow) >= 100 ? '现在可全额退' : '现在可退 ' + Number(quote.refundPercentNow) + '%' };
  }
  if (quote.refundableNow === false) return { key: 'refund', icon: 'finance', label: '购买后不可退' };
  return null;
}

/**
 * @param {object} o { info, quote, quoteState: loading|ready|refused|failed, refusal, products, win }
 */
function checkoutView(o) {
  const opt = o || {};
  const info = opt.info || {};
  const quote = opt.quoteState === 'ready' ? (opt.quote || null) : null;
  const listed = isNum(info.selfPlayPrice) ? Number(info.selfPlayPrice) : null;
  const pay = quote && isNum(quote.payAmount) ? Number(quote.payAmount) : (opt.quoteState === 'refused' || opt.quoteState === 'failed' ? null : listed);
  // l-pass:通行证覆盖(服务端 platformPassCovered === true)时报价已是 ¥0,减免额 = 原价,不是俱乐部会员减免。
  const member = !!(quote && quote.platformPassCovered === true && pay !== null && !(pay > 0));
  const discount = !member && quote && isNum(quote.memberDiscountYuan) && Number(quote.memberDiscountYuan) > 0 ? Number(quote.memberDiscountYuan) : 0;
  const win = opt.win || {};

  const bill = [];
  if (pay !== null) {
    const listAmount = member && isNum(quote.memberDiscountYuan) ? Number(quote.memberDiscountYuan) : (discount ? pay + discount : pay);
    bill.push({ key: 'pass', k: '自玩门票 × 1', v: yuan(listAmount) });
    if (discount) bill.push({ key: 'club', k: '俱乐部会员', v: '-' + yuan(discount), minus: true });
    if (member) bill.push({ key: 'member', k: '通行证', v: '你已可直接游玩', minus: true });
  }

  const trust = [];
  const refund = refundTrust(quote);
  if (refund) trust.push(refund);
  if (pay !== null) trust.push({ key: 'seat', icon: 'walk', label: '1 人自玩' });

  const free = pay !== null && !(pay > 0);
  const refused = opt.quoteState === 'refused';
  // E-B9:报价请求失败不退回标价(会员减免/通行证覆盖时应付可能是 ¥0),支付置灰,改给「重新获取价格」。
  const failed = opt.quoteState === 'failed';
  return {
    pills: headPills(info),
    single: {
      title: '自玩门票',
      price: pay === null ? '—' : yuan(pay),
      strike: discount ? yuan(pay + discount) : '',
      line: win.untilText ? '可玩至 ' + win.untilText : ''
    },
    member,
    passes: member ? [] : coveringPasses(opt.products, info.id),
    bill,
    totalText: pay === null ? '—' : yuan(pay),
    trust,
    loading: opt.quoteState === 'loading',
    refused,
    failed,
    refusal: refused ? playerText(opt.refusal) : '',
    free,
    payLabel: refused ? '暂不可购买' : (failed ? '重新获取价格' : (free ? '免费领取' : (pay === null ? '支付' : '支付 ' + yuan(pay))))
  };
}

module.exports = { checkoutView, playerText, coveringPasses, headPills, refundTrust, yuan };
