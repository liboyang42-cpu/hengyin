/* 动效 v2 时刻 6:点主题卡时量出封面矩形,随 eventChannel 交给详情页;详情页头图从这个矩形长出来。
   量不到/没有封面就退回普通 navigateTo(无任何动效)。 */
function go(page, e, url, done) {
  const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
  const send = (rect) => wx.navigateTo({ url, complete: done, success: (res) => { if (rect && res && res.eventChannel) res.eventChannel.emit('heroRect', Object.assign(rect, { cover: ds.cover || '' })); } });
  if (!ds.id || !ds.cover) { send(null); return; }
  try { page.createSelectorQuery().select('#tc-' + ds.id).boundingClientRect((r) => send(r && r.width ? { left: r.left, top: r.top, width: r.width, height: r.height } : null)).exec(); } catch (err) { send(null); }
}
module.exports = { go };
