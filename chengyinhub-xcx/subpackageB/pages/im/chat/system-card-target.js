'use strict';

function positiveId(value) {
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  if (typeof value === 'string' && !/^[1-9]\d*$/.test(value)) return null;
  return Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Number(value) : null;
}
function recordOf(message) {
  if (!message || ![0, '0'].includes(message.senderId) || ![3, '3'].includes(message.msgType)) return null;
  const card = message.card;
  if (!card || card.cardType && card.cardType !== 'generic') return null;
  const result = card.result;
  return result && typeof result === 'object' && !Array.isArray(result) ? result : null;
}
function originalUrl(message) {
  const result = recordOf(message), id = result && positiveId(result.bizId);
  // These two AuditTask bizIds name exactly the existing detail page's id.
  // COMMUNITY_POST reads canonical /community/posts/{id}; TOPIC reads info-to-user.
  // SQUARE, comments, enforcement and project/sale review are different contracts.
  const paths = { COMMUNITY_POST: '/pages/square/detail/index', TOPIC: '/pages/topic/index/index' };
  if (!id || typeof result.bizType !== 'string' || !Object.prototype.hasOwnProperty.call(paths, result.bizType)) return '';
  const url = paths[result.bizType] + '?id=' + id;
  if (Object.prototype.hasOwnProperty.call(message.card, 'action') && message.card.action !== url) return '';
  return url;
}
function buttonUrl(message, index) {
  if (typeof index !== 'number' && typeof index !== 'string' || !/^(0|[1-9]\d*)$/.test(String(index))) return '';
  const url = originalUrl(message), buttons = message && message.card && message.card.buttons;
  const button = Array.isArray(buttons) && buttons[Number(index)];
  return url && button && button.action === url ? url : '';
}
// Legacy system cards may link to these current business pages; dataset URLs are never authority.
function genericActionInfo(message, index) {
  if (!message || ![0, '0'].includes(message.senderId) || ![3, '3'].includes(message.msgType)) return null;
  const card = message.card;
  if (!card || typeof card !== 'object' || Array.isArray(card) || card.cardType !== 'generic'
      || Object.prototype.hasOwnProperty.call(card, 'result')) return null;
  let action = card.action;
  if (index !== undefined) {
    if (typeof index !== 'number' && typeof index !== 'string' || !/^(0|[1-9]\d*)$/.test(String(index))) return null;
    const button = Array.isArray(card.buttons) && card.buttons[Number(index)];
    if (!button || typeof button !== 'object' || Array.isArray(button)) return null;
    action = button.action;
  }
  let url = '';
  if (typeof action === 'string') {
    const match = action.match(/^\/(pages\/activity\/detail\/index|pages\/topic\/index\/index|pages\/square\/detail\/index)\?id=([1-9]\d*)$/);
    if (match && positiveId(match[2])) url = action;
    else if (action === '/subpackageMember/coupon-wallet/index') url = action;
    else return null;
  } else if (action != null) return null;
  const bcId = positiveId(card.bcId);
  return url || index === undefined && bcId ? { url, bcId } : null;
}
module.exports = { positiveId, recordOf, originalUrl, buttonUrl, genericActionInfo };
