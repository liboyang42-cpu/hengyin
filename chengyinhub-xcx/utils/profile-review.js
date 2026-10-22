/**
 * 资料/形象审核回执文案(W10b #25)· 只把后端回执翻成一句话,不自己推断状态。
 *
 * 合同来源:
 *   GET /api/user/profile/review → data: { available, currentVersion, avatar: { versionNo, state, live } | null }
 *   GET /api/merchant/npc/review、POST /api/merchant/npc/save 的 receipt
 *        → { hasProfile, review, reviewReason, playerVisible, avatarReview }
 *   state / review / avatarReview ∈ PENDING | APPROVED | REJECTED | NOT_SUBMITTED | NOT_REQUIRED
 *
 * 规则:NOT_SUBMITTED(没有送检记录)不是「通过」,也不说「审核中」——什么都不说;
 * 回执缺失或读失败同样返回空串,页面不显示任何审核结论。
 */

function memberAvatarText(receipt) {
  if (!receipt || receipt.available !== true || !receipt.avatar) return '';
  var avatar = receipt.avatar;
  var live = avatar.live === true;
  if (avatar.state === 'PENDING') return live ? '头像已更新，图片安全复核中；未通过会被撤下' : '';
  if (avatar.state === 'APPROVED') return live ? '头像已通过图片安全复核' : '';
  if (avatar.state === 'REJECTED') {
    return live ? '头像未通过图片安全复核，正在撤下，请换一张' : '上次提交的头像未通过图片安全复核，已撤下，请换一张';
  }
  return '';
}

/** 门店 AI 形象:人设审核 + 玩家此刻能否看到 + 形象图复核,分开说,互不推断。 */
function merchantNpcText(receipt) {
  if (!receipt || receipt.hasProfile !== true) return '';
  var parts = [];
  if (receipt.review === 'PENDING') parts.push('审核通过前玩家看不到这个形象');
  else if (receipt.review === 'REJECTED') parts.push('未通过审核，玩家看不到这个形象');
  else if (receipt.review === 'APPROVED') parts.push(receipt.playerVisible === true ? '玩家现在能看到这个形象' : '形象已过审，但当前未启用');
  if (receipt.avatarReview === 'PENDING') parts.push('形象图片安全复核中');
  else if (receipt.avatarReview === 'REJECTED') parts.push('形象图片未通过安全复核，请换一张');
  else if (receipt.avatarReview === 'APPROVED') parts.push('形象图片已通过安全复核');
  return parts.join('；');
}

module.exports = { memberAvatarText: memberAvatarText, merchantNpcText: merchantNpcText };
