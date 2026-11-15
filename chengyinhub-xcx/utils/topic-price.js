const { amount } = require('./money-format.js');

// minAmout 汇总场次票；自玩主题使用详情下发的 selfPlayPrice，缺价不能读成免费。
function topicPrice(item) {
  if (!item) return null;
  const raw = Number(item.selfPlay) === 1 ? item.selfPlayPrice
    : item.minAmout != null ? item.minAmout : item.minAmount != null ? item.minAmount : item.price;
  if (typeof raw !== 'number' && (typeof raw !== 'string' || raw.trim() === '')) return null;
  const value = amount(raw);
  return value != null && value >= 0 ? raw : null;
}

module.exports = { topicPrice };
