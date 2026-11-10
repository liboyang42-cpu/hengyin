// r3-pass:通行证购买页与到账动效的分步数据(纯函数,可单测)。
// 每一步只来自服务端事实:商品详情(/api/marketing/product/{id})与已付订单(statusLabel=PAID)。
// 没有的事实就没有那一步 —— 不编主题数、不编期限、不编人数,未知不写 0。
const datetime = require('../../utils/datetime.js');

const MAX_ROWS = 4;
const MAX_LINKS = 8;

function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
}

function str(v) {
  return v === null || v === undefined ? '' : String(v).trim();
}

const KIND_TEXT = {
  1: '单关票', 2: '城市包', 3: '时限通票', 4: '固定期年卡',
  5: '团体票', 6: '学校团体票', 7: '礼品通行证', 8: '加座'
};

/** 票面上那行字:优先城市,没有城市写类型;都没有就空着(不写「通票」之类的假名)。 */
function faceText(p) {
  if (!p) return '';
  return str(p.saleCity) || KIND_TEXT[Number(p.productKind)] || '';
}

/** 一位一格的数字(拨盘下面那排格子)。 */
function digits(text) {
  return String(text).split('').map((c, i) => ({ k: i, d: /\d/.test(c) ? Number(c) : null, c }));
}

/** 期限:相对天数写「N 天」;固定期写截止日期。没有可信值返回 null。 */
function validityDial(p) {
  if (!p) return null;
  const k = Number(p.validityKind);
  if ((k === 1 || k === 3) && isNum(p.validDays) && Number(p.validDays) > 0) {
    const n = Math.floor(Number(p.validDays));
    return {
      cells: digits(String(n)),
      unit: '天',
      line: (k === 3 ? '首次游玩起 ' : '购买后 ') + n + ' 天内有效',
      // 拨盘转的角度跟着天数走,一圈 = 365 天;至少转半圈,看得出是「拧开」
      angle: 180 + Math.round(Math.min(n, 365) / 365 * 540)
    };
  }
  if (k === 2) {
    const t = datetime.toTimestamp(p.fixedValidTo);
    if (!Number.isFinite(t)) return null;
    const day = datetime.formatDayDots(t); // YYYY.MM.DD
    if (!day) return null;
    return { cells: digits(day), unit: '', line: '有效至 ' + day, angle: 540 };
  }
  return null;
}

/**
 * 到账分步。order 必须是服务端回的已付订单(statusLabel === 'PAID')且是自己用(不是礼品):
 * 礼品在好友领取前买家也不能玩,不能说「已到账」。
 */
function unlockSteps(product, order) {
  if (!product || !product.productId) return [];
  if (!order || order.statusLabel !== 'PAID') return [];
  if (Number(order.fulfillmentKind) === 2) return [];
  const steps = [];
  const kind = KIND_TEXT[Number(product.productKind)] || '';
  steps.push({
    key: 'pass',
    eyebrow: '已到账',
    title: str(product.productName),
    line: [kind, str(product.saleCity)].filter(Boolean).join(' · ')
  });
  const items = (Array.isArray(product.items) ? product.items : [])
    .map((it) => str(it && it.topicTitle)).filter(Boolean);
  if (items.length) {
    steps.push({
      key: 'themes',
      eyebrow: '可玩主题',
      title: items.length + ' 个主题',
      count: items.length,
      rows: items.slice(0, MAX_ROWS).map((t, i) => ({ k: i, t }))
    });
  }
  const dial = validityDial(product);
  if (dial) {
    steps.push({ key: 'dial', eyebrow: '有效期', title: dial.line, dial });
  }
  const seats = isNum(product.seatCount) ? Math.floor(Number(product.seatCount)) : 0;
  if (seats > 1) {
    steps.push({
      key: 'seats',
      eyebrow: '同行',
      title: seats + ' 人可用',
      links: Array.from({ length: Math.min(seats, MAX_LINKS) }, (_, i) => ({ k: i }))
    });
  }
  return steps;
}

module.exports = { unlockSteps, validityDial, faceText, MAX_ROWS, MAX_LINKS };
