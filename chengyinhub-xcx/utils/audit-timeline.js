'use strict';
// FOLLOWUP-A-2:作者看自己被驳回 / 下架的活动、主题、玩法的「审核记录」,并在后端说可以时给「申诉」。
// GET /api/audit/timeline?bizType&bizId(仅作者本人)→ { rounds:[{kind, task, timeline:[record]}], appeal, canAppeal }
// POST /api/audit/appeal(JSON){ bizType, bizId, reason } —— 每条内容只能申诉一次,能不能申诉只认 canAppeal。

const KIND_TEXT = { SUBMIT: '内容审核', REPORT: '举报复核', APPEAL: '申诉复核' };
const ACTION_TEXT = { SUBMIT: '已提交审核', APPROVE: '审核通过', REJECT: '未通过 / 已下架', REVERT: '撤销了上一次决定' };
const APPEAL_TEXT = {
  SUBMITTED: '申诉已提交，等待平台处理', REVIEWING: '申诉处理中', ACCEPTED: '申诉成立，内容已恢复',
  REJECTED: '申诉未成立，维持原处理', WITHDRAWN: '申诉已撤回'
};
const MAX_APPEAL_REASON = 500;

// 项目卡(主题 / 活动):只在未通过或已下架时给审核记录。
function projectTarget(item) {
  if (!item || !item.id || (item.state !== 'rejected' && item.state !== 'offline')) return null;
  if (item.bizType === 'topic') return { bizType: 'TOPIC', bizId: String(item.id) };
  if (item.bizType === 'activity') return { bizType: 'ACTIVITY', bizId: String(item.id) };
  return null;
}

// 玩法卡:个人审核未通过 → 个人玩法(TEMPLATE);否则广场记录被驳回 / 下架 → 广场(TEMPLATE_LIBRARY)。
function templateTarget(t) {
  if (!t || !t.id) return null;
  const is = (value, expected) => value === expected || value === String(expected);
  if (is(t.draftStatus, 1) && (is(t.status, 2) || is(t.checkStatus, 2))) return { bizType: 'TEMPLATE', bizId: String(t.id) };
  const market = /^[1-9]\d*$/.test(String(t.marketTemplateId || ''));
  if (market && (is(t.marketStatus, 2) || (is(t.marketStatus, 1) && is(t.marketPublishStatus, 0)))) {
    return { bizType: 'TEMPLATE_LIBRARY', bizId: String(t.marketTemplateId) };
  }
  return null;
}

function timelineRows(data) {
  const rows = [];
  const rounds = data && Array.isArray(data.rounds) ? data.rounds : [];
  rounds.forEach(function (round, r) {
    const kind = KIND_TEXT[round && round.kind] || KIND_TEXT.SUBMIT;
    const records = round && Array.isArray(round.timeline) ? round.timeline : [];
    records.forEach(function (record, i) {
      if (!record || !ACTION_TEXT[record.action]) return;
      rows.push({
        key: r + '-' + (record.id != null ? record.id : i),
        label: kind + ' · ' + ACTION_TEXT[record.action],
        sub: [record.reasonText, record.createTime].filter(function (v) { return v != null && String(v).trim(); }).join(' · ')
      });
    });
  });
  return rows;
}

function appealText(data) {
  const appeal = data && data.appeal;
  if (!appeal || !appeal.state) return '';
  return APPEAL_TEXT[appeal.state] || '申诉状态待确认';
}

function validateReason(content) {
  const text = String(content || '').trim();
  if (!text) return '请写清申诉理由';
  if (text.length > MAX_APPEAL_REASON) return '申诉理由不能超过 500 字';
  return '';
}

module.exports = { projectTarget, templateTarget, timelineRows, appealText, validateReason };
