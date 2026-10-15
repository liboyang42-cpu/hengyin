'use strict';

/**
 * 官方活动 V2 · 主办控制台的纯规则(无 wx / app 依赖,单测直接 require)。
 *
 * 真源:OfficialEventV2ServiceImpl(schedule / inviteOrganizer / transferOrganizer / pause / resume)。
 * 这里只把服务端的「何时可调用」前置成按钮可用性,**不替服务端做权限判断**:
 *   - 是否主办由 GET /api/official/v2/events/{id}/publish-check 是否 200 决定(canReadControlledEvent);
 *     协办方也能读,但五个命令都只认「本场有效主办」,协办点了会拿到服务端原话的拒绝。
 *   - 状态门槛与服务端一一对应,状态没读到(非整数)时全部置灰,不猜。
 * 五个端点都**没有** requestId / version 字段:重复提交靠服务端状态校验兜底
 *   (暂停已暂停 = 幂等成功;重复排期/转交/邀请会被状态或重复关系拒绝),所以结果未知时先回读、不盲目重发。
 */

// OfficialEvent.java 生命周期常量
const ST_DRAFT = 0;
const ST_SCHEDULED = 1;
const ST_SETTLING = 4;
const CONTROL_NORMAL = 'NORMAL';
const CONTROL_PAUSED = 'PAUSED';

// 服务端必填校验只判空串(StringUtils.isEmpty),前端多做一步 trim,避免提交纯空格。
// 长度是前端软上限:列都是 varchar(500),但审计 summary(同为 500)会在原因前拼「主办方从会员 X 转交给会员 Y：」
// 之类前缀,原因留 200 字才不会在审计写入时被截断/报错;暂停四项各自单独落列,留 300。
const LIMITS = { reason: 200, plan: 300 };

function isPositiveId(value) {
  const text = typeof value === 'number' ? String(value) : String(value == null ? '' : value).trim();
  // 只放行 JS 安全整数范围内的十进制正整数(≤15 位),以数字提交给 Java Long 不丢精度
  return /^[1-9]\d{0,14}$/.test(text);
}

function blank(value) {
  return typeof value !== 'string' || !value.trim();
}

/** 读活动状态:status 必须是整数,controlState 只认两个已知值,其余一律视为未知。 */
function normalizeEventState(raw) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const status = Number.isInteger(src.status) ? src.status : null;
  const controlState = src.controlState === CONTROL_NORMAL || src.controlState === CONTROL_PAUSED
    ? src.controlState : null;
  return {
    status,
    controlState,
    title: typeof src.title === 'string' && src.title.trim() ? src.title.trim() : '',
    pausedReason: typeof src.pausedReason === 'string' ? src.pausedReason.trim() : '',
    resumeNote: typeof src.resumeNote === 'string' ? src.resumeNote.trim() : '',
  };
}

/**
 * 每个命令在当前状态下能不能点,以及点不了的原因(给用户看的人话)。
 * 与服务端门槛对应:
 *   schedule  status == DRAFT                      (「仅草稿活动可以提交排期」)
 *   invite    status <  SETTLING                   (「当前活动状态不能调整主办方」)
 *   transfer  status <  SETTLING                   (「当前活动状态不能转交主办方」)
 *   pause     SCHEDULED <= status < SETTLING 且未暂停(「当前生命周期不能暂停」)
 *   resume    controlState == PAUSED               (「活动未暂停」)
 */
function actionAvailability(state) {
  const s = state || {};
  const known = Number.isInteger(s.status);
  const unknown = '活动状态没读到，刷新后再操作';
  const ended = '活动已进入结算或已结束，不能再调整';
  const out = {};
  out.schedule = !known ? { enabled: false, hint: unknown }
    : s.status === ST_DRAFT ? { enabled: true, hint: '' }
      : { enabled: false, hint: '活动已排期，只有草稿可以提交排期' };
  const organizerAdjust = !known ? { enabled: false, hint: unknown }
    : s.status < ST_SETTLING ? { enabled: true, hint: '' } : { enabled: false, hint: ended };
  out.invite = organizerAdjust;
  out.transfer = organizerAdjust;
  if (s.controlState === CONTROL_PAUSED) {
    out.pause = { enabled: false, hint: '活动已暂停' };
    out.resume = { enabled: true, hint: '' };
  } else if (s.controlState === CONTROL_NORMAL) {
    out.resume = { enabled: false, hint: '活动未暂停' };
    out.pause = !known ? { enabled: false, hint: unknown }
      : s.status < ST_SCHEDULED ? { enabled: false, hint: '草稿排期后才能暂停' }
        : s.status >= ST_SETTLING ? { enabled: false, hint: ended }
          : { enabled: true, hint: '' };
  } else {
    const hint = '控制状态待确认，已停用暂停/恢复';
    out.pause = { enabled: false, hint };
    out.resume = { enabled: false, hint };
  }
  return out;
}

/** 表单校验:与服务端必填一致,返回第一条人话错误;通过返回 ''。 */
function validateForm(action, form) {
  const f = form || {};
  if (action === 'invite') {
    if (!isPositiveId(f.memberId)) return '请填写被邀请官方会员的会员编号（正整数）';
    if (blank(f.reason)) return '邀请主办方必须填写授权说明';
    if (f.reason.trim().length > LIMITS.reason) return '授权说明最多 ' + LIMITS.reason + ' 字';
    return '';
  }
  if (action === 'transfer') {
    if (!isPositiveId(f.partyId)) return '请填写候任主办的关系编号（对方在「官方活动邀约」里可见）';
    if (blank(f.reason)) return '主办转交必须填写原因';
    if (f.reason.trim().length > LIMITS.reason) return '转交原因最多 ' + LIMITS.reason + ' 字';
    return '';
  }
  if (action === 'pause') {
    const keys = ['reason', 'impactScope', 'alternativePlan', 'notificationPlan'];
    if (keys.some((k) => blank(f[k]))) return '暂停必须填写原因、影响范围、替代方案和通知策略';
    if (keys.some((k) => f[k].trim().length > LIMITS.plan)) return '每项最多 ' + LIMITS.plan + ' 字';
    return '';
  }
  if (action === 'resume') {
    if (blank(f.reason)) return '恢复必须填写风险处理说明';
    if (f.reason.trim().length > LIMITS.reason) return '风险处理说明最多 ' + LIMITS.reason + ' 字';
    return '';
  }
  if (action === 'schedule') return '';
  return '未知操作';
}

/** 组请求体:字段名逐个对齐 OfficialEventV2*Request DTO;编号以数字提交(Jackson 反序列化成 Long)。 */
function buildPayload(action, form) {
  const f = form || {};
  if (action === 'invite') return { memberId: Number(String(f.memberId).trim()), reason: f.reason.trim() };
  if (action === 'transfer') return { partyId: Number(String(f.partyId).trim()), reason: f.reason.trim() };
  if (action === 'pause') {
    return {
      reason: f.reason.trim(),
      impactScope: f.impactScope.trim(),
      alternativePlan: f.alternativePlan.trim(),
      notificationPlan: f.notificationPlan.trim(),
    };
  }
  if (action === 'resume') return { reason: f.reason.trim() };
  return {};
}

/**
 * 成功后的回读核对。返回:
 *   'confirmed'   回读到的状态与命令预期一致
 *   'mismatch'    回读到了,但状态不符(提示刷新 / 联系平台,不宣称成功生效)
 *   'unverifiable' 本端没有可回读的字段(邀请 / 转交的主办关系列表未对小程序开放)
 */
function verifyReadBack(action, after, stillOrganizer) {
  const s = after || {};
  if (action === 'schedule') return Number.isInteger(s.status) && s.status >= ST_SCHEDULED ? 'confirmed' : 'mismatch';
  if (action === 'pause') return s.controlState === CONTROL_PAUSED ? 'confirmed' : 'mismatch';
  if (action === 'resume') return s.controlState === CONTROL_NORMAL ? 'confirmed' : 'mismatch';
  // 转交成功后原主办失去受控读取权 —— publish-check 被拒恰好是「已转出」的服务端证据。
  if (action === 'transfer') return stillOrganizer === false ? 'confirmed' : 'unverifiable';
  return 'unverifiable';
}

/** HTTP 层异常分类:5xx / 408 / 断网 = 结果未知;其余 4xx = 明确失败。 */
function isUnknownOutcome(httpStatus) {
  if (httpStatus == null) return true;
  const code = Number(httpStatus);
  return !Number.isFinite(code) || code >= 500 || code === 408;
}

module.exports = {
  ST_DRAFT,
  ST_SCHEDULED,
  ST_SETTLING,
  CONTROL_NORMAL,
  CONTROL_PAUSED,
  isPositiveId,
  normalizeEventState,
  actionAvailability,
  validateForm,
  buildPayload,
  verifyReadBack,
  isUnknownOutcome,
};
