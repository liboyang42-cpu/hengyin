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
module.exports = { positiveId, recordOf, originalUrl, buttonUrl };
