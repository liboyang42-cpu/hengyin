'use strict';
const intent = require('./publish/publish-intent.js');
const { exactId } = require('./user-character.js');
const scope = post => 'square:comment:' + post;
const validKey = key => typeof key === 'string' && key.trim().length >= 8 && key.trim().length <= 96;
const failure = () => ({ code: 507, msg: '评论提交记录无法保存，请稍后重试' });
function pending(wx, owner, post) {
  if (!exactId(owner) || !exactId(post)) return null;
  let row;
  try { row = wx.getStorageSync(intent.storageKey(scope(post), owner)); } catch (_) { throw failure(); }
  if (!row) return null;
  if (!['sending', 'unknown', 'rejected'].includes(row.status) || row.memberId !== String(owner) || row.postId !== String(post) || !validKey(row.key)
      || !row.wire || typeof row.wire.body !== 'string' || !row.wire.body.trim()
      || row.wire.clientRequestId !== row.key
      || row.wire.parentId != null && !exactId(row.wire.parentId)) {
    throw { code: 409, msg: '原评论记录不完整，请先核对提交结果' };
  }
  return row;
}
function save(wx, owner, post, row) {
  try {
    const key = intent.storageKey(scope(post), owner);
    wx.setStorageSync(key, row);
    if (JSON.stringify(wx.getStorageSync(key)) !== JSON.stringify(row)) throw failure();
  } catch (_) { throw failure(); }
}
function prepare(wx, owner, post, wire) {
  if (!exactId(owner) || !exactId(post)) throw { code: 409, msg: '评论身份或帖文无效，请刷新' };
  if (typeof wire.body !== 'string' || !wire.body.trim() || wire.body.length > 2000) throw { code: 400, msg: '评论正文应为1-2000字' };
  let row = pending(wx, owner, post);
  if (row && row.status !== 'rejected') {
    if (row.wire.body !== wire.body || String(row.wire.parentId || '') !== String(wire.parentId || '')) {
      throw { code: 409, msg: '上次评论尚未确认，请先按原内容重试', pendingComment: row.wire };
    }
    return { row, reused: true };
  }
  if (!validKey(wire.clientRequestId)) throw { code: 400, msg: '评论请求号无效' };
  row = { key: wire.clientRequestId, memberId: String(owner), postId: String(post), status: 'sending',
    wire: { ...wire } };
  save(wx, owner, post, row);
  return { row, reused: false };
}
function finish(wx, owner, post, record, code) {
  const current = pending(wx, owner, post);
  if (!current || current.key !== record.row.key) return;
  if (Number(code) === 200) {
    try {
      const key = intent.storageKey(scope(post), owner);
      wx.removeStorageSync(key);
      if (wx.getStorageSync(key)) throw failure();
    } catch (_) { throw failure(); }
  } else {
    const rejected = !record.reused && [400, 403, 404].includes(Number(code));
    save(wx, owner, post, { ...current, status: rejected ? 'rejected' : 'unknown' });
  }
}
module.exports = { pending, prepare, finish };
