// §18 A/B 视频悬赏作者端。审核只能运营做;应计恒「仅记录」,不写待提现/可提现/已到账。
// 投稿失败按 reasonCode 分别说,不合并成「提交失败」;前端不拼视频 id、不换算短链。
const datetime = require('../../utils/datetime.js');

const PLATFORMS = [
  { key: 'tiktok', label: 'TikTok' },
  { key: 'reels', label: 'Instagram Reels' },
  { key: 'youtube', label: 'YouTube' }
];
const STATUS_TEXT = {
  SUBMITTED: '已提交', UNDER_REVIEW: '审核中', APPROVED: '已通过', REJECTED: '未通过',
  REVOKED: '已撤销', APPEALING: '申诉中', CANCELLED: '已取消', UNKNOWN: '状态待确认'
};
const REASON_TEXT = {
  VIDEO_ALREADY_SUBMITTED: '这条视频已经投过稿',
  PLATFORM_MISMATCH: '链接与所选平台不一致',
  LINK_PLATFORM_UNSUPPORTED: '只支持 TikTok、Instagram Reels、YouTube',
  LINK_VIDEO_ID_MISSING: '请填写视频页链接，不是主页链接',
  LINK_SCHEME_NOT_ALLOWED: '只支持 https 公开链接',
  LINK_USERINFO_NOT_ALLOWED: '只支持 https 公开链接',
  TOPIC_NOT_FOUND: '主题不存在或未上架',
  CAMPAIGN_NOT_ACTIVE: '当前不接受投稿',
  CANCEL_NOT_ALLOWED: '当前状态不能取消'
};
function reasonText(code, msg) {
  return REASON_TEXT[code] || msg || '提交未完成，请稍后再试';
}
function day(v) {
  const t = datetime.toTimestamp(v);
  return Number.isFinite(t) ? datetime.formatDayDots(t) : '';
}

function submissionRow(s) {
  if (!s || s.id === undefined || s.id === null) return null;
  const label = STATUS_TEXT[s.statusLabel] ? s.statusLabel : 'UNKNOWN';
  const platform = (PLATFORMS.find((p) => p.key === s.platform) || {}).label || '';
  return {
    id: s.id,
    title: platform || '视频',
    url: s.sourceUrl || '',
    status: STATUS_TEXT[label],
    note: label === 'REJECTED' && s.reviewNote ? s.reviewNote : '',
    window: label === 'APPROVED' && day(s.windowEndsAt) ? ('统计至 ' + day(s.windowEndsAt)) : '',
    canAppeal: label === 'REJECTED',
    // 通过后只能运营撤销,作者不出取消
    canCancel: ['SUBMITTED', 'UNDER_REVIEW', 'REJECTED', 'APPEALING'].indexOf(label) >= 0
  };
}

function views(n) {
  return n === null || n === undefined || !Number.isFinite(Number(n)) ? '—' : String(Number(n));
}
function accrualRow(a) {
  if (!a || a.id === undefined || a.id === null) return null;
  const amount = a.payableAmount !== null && a.payableAmount !== undefined && Number.isFinite(Number(a.payableAmount))
    ? ((a.currency && a.currency !== 'CNY' ? a.currency + ' ' : '¥') + Number(a.payableAmount).toFixed(2)) : '¥0.00';
  return {
    key: String(a.id),
    views: views(a.basisViews),
    groupViews: views(a.groupBasisViews),
    amount,
    // payoutState 恒 0 = 仅记录;任何值都不写成可提现
    state: '已记录（未开放兑付）'
  };
}

/** 列表读回:null(失败)→ error,不显示 0 元;[] → empty */
function listView(data, rowFn) {
  if (!Array.isArray(data)) return { state: 'error', rows: [] };
  const rows = data.map(rowFn).filter(Boolean);
  return { state: rows.length ? 'ready' : 'empty', rows };
}

/** 提交前的本地必填检查(只查是否填写,链接合法性交给服务端按码判定) */
function formError(form) {
  if (!form || !form.platform) return { field: 'platform', text: '请选择平台' };
  if (!form.sourceUrl || !String(form.sourceUrl).trim()) return { field: 'sourceUrl', text: '请填写视频链接' };
  return null;
}

module.exports = { PLATFORMS, STATUS_TEXT, reasonText, submissionRow, accrualRow, listView, formError };
