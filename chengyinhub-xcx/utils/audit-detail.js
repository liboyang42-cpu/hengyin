// BL-87 审核结果详情:GET /api/audit/{taskId}/detail(ApiAuditController,仅提交人本人可读)。
// 申请人视图字段以 AuditTaskServiceImpl.applicantTask / applicantTimeline 为准:
//   task: { id, bizType, bizId, submitterId, status(0待审/1通过/2未通过/3升级复核/4已撤回), submittedAt, version }
//   timeline: [{ id, taskId, action(SUBMIT/APPROVE/REJECT/REVERT), operatorId, reasonText, revertedRecordId, createTime }]
// 只展示后端给出的值,不补「已通过」之类的结论。
const STATUS_LABELS = { 0: '待审核', 1: '审核通过', 2: '审核未通过', 3: '升级复核中', 4: '已撤回' };
const ACTION_LABELS = { SUBMIT: '提交审核', APPROVE: '审核通过', REJECT: '审核未通过', REVERT: '撤销原决定' };

function positiveSafeId(value) {
  const out = Number(value);
  return Number.isSafeInteger(out) && out > 0 ? out : null;
}

function auditDetailUrl(taskId) {
  const id = positiveSafeId(taskId);
  return id ? '/api/audit/' + id + '/detail' : '';
}

function shapeAuditDetail(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const task = data.task && typeof data.task === 'object' ? data.task : null;
  if (!task) return null;
  const status = Number(task.status);
  const timeline = (Array.isArray(data.timeline) ? data.timeline : [])
    .filter(row => row && typeof row === 'object' && ACTION_LABELS[row.action])
    .map((row, index) => ({
      key: String(positiveSafeId(row.id) || 'row-' + index),
      label: ACTION_LABELS[row.action],
      reason: typeof row.reasonText === 'string' ? row.reasonText.trim() : '',
      time: typeof row.createTime === 'string' ? row.createTime : '',
    }));
  return {
    statusLabel: Object.prototype.hasOwnProperty.call(STATUS_LABELS, status) ? STATUS_LABELS[status] : '',
    timeline,
  };
}

// AuditTaskServiceImpl.notifyReviewResult 给被关组局的组局者/成员也发同一 taskId 的卡片(卡片标题如下),
// 他们是被举报方,detail 必然 403 ⇒ 不发请求(用户决定:不做被举报方视图)。
const NO_DETAIL_CARD_TITLES = ['你的组局已关闭', '你参与的组局已关闭'];

function shouldLoadAuditDetail(card) {
  const title = card && typeof card.title === 'string' ? card.title.trim() : '';
  return NO_DETAIL_CARD_TITLES.indexOf(title) < 0;
}

// 403(非提交人,如其他举报人/主题主办方)/404 重试也不会变 ⇒ 只回显文案,不给重试。
function isRetryableAuditError(res) {
  const code = Number(res && res.code);
  return code !== 403 && code !== 404;
}

module.exports = {
  auditDetailUrl, shapeAuditDetail, shouldLoadAuditDetail, isRetryableAuditError,
  STATUS_LABELS, ACTION_LABELS, NO_DETAIL_CARD_TITLES,
};
