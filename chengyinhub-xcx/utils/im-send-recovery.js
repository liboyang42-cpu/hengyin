'use strict';
// 本地只保存回读身份，不保存聊天原文、图片或坐标；未知重进只查询原收据。
const PREFIX = 'im_send_receipts_v1:';
let generation = 0;
function key(owner, conversation) { return PREFIX + String(owner) + ':' + String(conversation); }
function read(owner, conversation) {
  const rows = wx.getStorageSync(key(owner, conversation));
  return Array.isArray(rows) ? rows.filter(r => r && typeof r.id === 'string').map(r => ({ id: r.id })) : [];
}
function begin(owner, conversation, id) {
  try {
    const rows = read(owner, conversation);
    if (!rows.some(r => r.id === id)) rows.push({ id });
    wx.setStorageSync(key(owner, conversation), rows);
    if (!read(owner, conversation).some(r => r.id === id)) return null;
    return { owner, conversation, id, generation };
  } catch (e) { return null; }
}
function current(receipt) {
  try { return !!receipt && receipt.generation === generation
    && read(receipt.owner, receipt.conversation).some(r => r.id === receipt.id); }
  catch (e) { return false; }
}
function complete(receipt) {
  if (!current(receipt)) return false;
  try {
    wx.setStorageSync(key(receipt.owner, receipt.conversation),
      read(receipt.owner, receipt.conversation).filter(r => r.id !== receipt.id));
    return !current(receipt);
  } catch (e) { return false; }
}
function pending(owner, conversation) {
  try { return read(owner, conversation).map(r => ({ owner, conversation, id: r.id, generation })); }
  catch (e) { return []; }
}
function abandon(owner, conversation) {
  generation += 1;
  try { wx.removeStorageSync(key(owner, conversation)); return true; } catch (e) { return false; }
}
function invalidate() { generation += 1; }
module.exports = { epoch: () => generation, begin, current, complete, pending, abandon, invalidate };
