// §26.1 完整合作玩法(端侧):共享帮助 VIEW / USE / SKIP 与答对待确认的 QUEST_RESOLVE 增量字段。
// 只消费服务端 cooperation 投影与 POST /api/play/help/shared 回包:
//  - 免费 N 整局同队共池,按钮三态:免费 / 消耗 N 积分 / 不可用(带原因);已解锁层「重看」不扣
//  - 跳过只在服务端给出可用 SKIP 层时出现,确认文案「本题记 0 分并标辅助完成」,不放成功动画
//  - 未知结果只按原 requestId 重查;同号不能换动作
//  - 本轮不画「全队共用计时 / 共享跳过额度 / 全队一个分数」(lane 定:No shared timer, shared skip quota or single team score)

const CAPABILITY = 'game.teamCooperation@1';
const TYPE_TEXT = { TEXT: '文字提示', IMAGE: '图片提示', LETTER: '揭示字母', DIGIT: '揭示数字', ANSWER: '查看答案' };
const REASON_TEXT = {
  REAL_OPERATION_REQUIRED: '需要现场完成，不能跳过',
  HELP_TYPE_UNSUPPORTED: '暂不支持这种提示',
  INSUFFICIENT_POINTS: '积分不足'
};

function int(v) { return Number.isSafeInteger(v) ? v : null; }

// 本题当前合作实例:同 questKey 下未完成、未取消的那一个(没有就取最后一个)
function question(cooperation, questKey) {
  const rows = cooperation && Array.isArray(cooperation.questions) ? cooperation.questions : [];
  const mine = rows.filter(q => q && q.questKey === questKey && q.runtimeInstance);
  return mine.find(q => !q.completedAt && !q.cancelledAt) || mine[mine.length - 1] || null;
}

// QUEST_RESOLVE(sharedPreview 与 command)只在有真实答对版本时追加 answerRevision + runtimeInstance
function resolveFields(cooperation, questKey) {
  const q = question(cooperation, questKey);
  if (!q || int(q.answerRevision) == null) return {};
  return { runtimeInstance: String(q.runtimeInstance), answerRevision: q.answerRevision };
}

function canHelp(cooperation, questKey) {
  const q = question(cooperation, questKey);
  return !!(q && int(q.nodeId) && int(cooperation.gameSessionId) && int(cooperation.authorityEpoch) != null && !q.completedAt && !q.cancelledAt);
}

function helpBody(input) {
  const { activityId, cooperation, questKey, operation, level, requestId } = input;
  const q = question(cooperation, questKey);
  if (!q) return null;
  const body = {
    activityId: Number(activityId), gameSessionId: cooperation.gameSessionId, nodeId: q.nodeId,
    runtimeInstance: String(q.runtimeInstance), authorityEpoch: cooperation.authorityEpoch,
    capabilities: CAPABILITY, operation
  };
  if (operation !== 'VIEW') body.requestId = requestId;
  if (operation === 'USE') body.level = level;
  return body;
}

// 帮助层:三态按钮;SKIP 单独成一行(只在服务端标可用时出现)
function levelRows(levels) {
  const rows = [];
  let skip = null;
  (Array.isArray(levels) ? levels : []).forEach(l => {
    if (!l || int(l.level) == null) return;
    if (l.type === 'SKIP') { skip = { level: l.level, usable: l.usable === true, reason: REASON_TEXT[l.reason] || (l.usable ? '' : '暂不可跳过') }; return; }
    if (l.type === 'LOCATION') return; // §十二:位置类提示不出现
    let state, actionText;
    if (l.unlocked) { state = 'unlocked'; actionText = '重看'; }
    else if (!l.usable) { state = 'off'; actionText = REASON_TEXT[l.reason] || '不可用'; }
    else if (l.free || !(l.costPoints > 0)) { state = 'free'; actionText = '免费'; }
    else { state = 'cost'; actionText = '消耗 ' + l.costPoints + ' 积分'; }
    rows.push({ level: l.level, title: TYPE_TEXT[l.type] || '提示', state, actionText });
  });
  return { rows, skip };
}

function freeText(remaining) {
  return int(remaining) != null ? '本局剩余免费 ' + remaining + ' 次' : '';
}

// 回包分类:成功 / 服务端明确拒绝(原文) / 未知(只能同号重查)
function outcome(res) {
  if (res && String(res.code) === '200' && res.data && typeof res.data === 'object') return { state: 'OK', data: res.data };
  if (!res || res.netFail || res.httpFail || res.statusCode >= 500 && !res.msg) return { state: 'UNKNOWN' };
  return { state: 'REJECTED', message: res.msg || '操作失败', reasonCode: res.reasonCode || '' };
}

function newRequestId() {
  return 'coop-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

module.exports = { CAPABILITY, question, resolveFields, canHelp, helpBody, levelRows, freeText, outcome, newRequestId };
