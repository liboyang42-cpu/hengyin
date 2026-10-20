/** The page renders the existing option sheet; transport retains the server reason codes. */
function finish(page, index, error) {
  const pending = page._reportReasonSelection;
  if (!pending) return;
  page._reportReasonSelection = null;
  try { page.setData({ reportReasonShow: false, reportReasonItems: [] }); }
  catch (_) { error = { code: 500, msg: '举报原因选择失败，请重试' }; }
  if (error) pending.reject(error);
  else pending.resolve(index);
}
function open(page, items, task) {
  if (page._reportReasonSelection) return Promise.reject({ code: 409, msg: '请选择当前举报原因' });
  if (page._unloaded || task.aborted) return Promise.reject({ code: 499, msg: '已取消举报' });
  return new Promise((resolve, reject) => {
    page._reportReasonSelection = { resolve, reject, count: items.length };
    const pending = page._reportReasonSelection;
    task.cancel = () => { if (page._reportReasonSelection === pending) cancel(page); };
    try { page.setData({ reportReasonShow: true, reportReasonItems: items }); }
    catch (_) { finish(page, null, { code: 500, msg: '举报原因选择失败，请重试' }); }
  });
}
function select(page, event) {
  const pending = page._reportReasonSelection;
  if (!pending) return;
  const index = event && event.detail && event.detail.index;
  if (!Number.isInteger(index) || index < 0 || index >= pending.count) {
    finish(page, null, { code: 500, msg: '举报原因选择无效，请重试' });
    return;
  }
  finish(page, index);
}
function cancel(page) { finish(page, null, { code: 499, msg: '已取消举报' }); }
module.exports = { open, select, cancel };
