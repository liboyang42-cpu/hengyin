// 发起邀约入口只由当前账号的发布者事实打开，角色或已有参与关系不能代替归属。
function readPublisherAccess(app, context, callback) {
  const memberId = String(app.getUserID() || '');
  const current = () => memberId && String(app.getUserID() || '') === memberId;
  const finish = allowed => { if (current()) callback(!!allowed); };
  if (!memberId) { callback(false); return; }
  const scope = context.operationScope || '';
  if (context.topicId) {
    app.sendRequest({
      url: '/api/topic/invite-chapters', method: 'POST', hideLoading: true, autoErrorToast: false,
      data: { id: context.topicId, scope },
      success: res => finish(res && res.code == 200 && res.data && Array.isArray(res.data.chapters) && res.data.chapters.some(chapter => chapter && chapter.id)),
      fail: () => finish(false),
    });
  } else {
    app.sendRequest({
      url: '/api/topic/list', method: 'POST', hideLoading: true, autoErrorToast: false,
      data: { is_my: 1, scope, pageNum: 1, pageSize: 1 },
      success: res => finish(res && res.code == 200 && res.data && Array.isArray(res.data.rows) && res.data.rows.some(topic => topic && topic.id)),
      fail: () => finish(false),
    });
  }
}
module.exports = { readPublisherAccess };
