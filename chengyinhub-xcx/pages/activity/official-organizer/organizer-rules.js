'use strict';

/**
 * 官方活动 V2 · 主办控制台的纯规则(无 wx / app 依赖,单测直接 require)。
 *
 * 真源:OfficialEventV2ServiceImpl(schedule / pause / resume)。
 * 2026-10-15 用户决定(TASKS.md「用户决定」CL4):邀请候任主办 / 转交**暂不做**;
 *   「提交排期」对用户叫「发布活动」(后端语义:草稿通过发布检查后进入 SCHEDULED);
 *   暂停表单用「预设选项 + 补充文字」,四项后端字段仍为文本。
 * 这里只把服务端的「何时可调用」前置成按钮可用性,**不替服务端做权限判断**:
 *   - 是否主办由 GET /api/official/v2/events/{id}/publish-check 是否 200 决定(canReadControlledEvent);
 *     协办方也能读,但三个命令都只认「本场有效主办」,协办点了会拿到服务端原话的拒绝。
 *   - 状态门槛与服务端一一对应,状态没读到(非整数)时全部置灰,不猜。
 * 三个端点都**没有** requestId / version 字段:重复提交靠服务端状态校验兜底
 *   (暂停已暂停 = 幂等成功;重复发布会被「仅草稿」拒绝),所以结果未知时先回读、不盲目重发。
 */

// OfficialEvent.java 生命周期常量
const ST_DRAFT = 0;
const ST_SCHEDULED = 1;
const ST_SETTLING = 4;
const CONTROL_NORMAL = 'NORMAL';
const CONTROL_PAUSED = 'PAUSED';

// 服务端必填校验只判空串(StringUtils.isEmpty),前端多做一步 trim,避免提交纯空格。
// 长度是前端软上限:列与审计 summary 都是 varchar(500),合成后的单项留 300 字以内。
const LIMITS = { reason: 200, plan: 300 };

// 暂停四项的预设选项:纯前端文案,只是帮主办方快速写出可读说明,最终仍按文本提交;
// 选「其他」或不选预设时必须写补充文字。措辞可在本地验收时调整,不影响后端合同。
const PAUSE_FIELDS = ['reason', 'impactScope', 'alternativePlan', 'notificationPlan'];
const PAUSE_PRESETS = {
  reason: ['天气或安全原因', '场地或合作商家临时不可用', '活动内容或任务需要修正'],
  impactScope: ['全部已报名玩家', '部分节点或任务', '尚未报名的玩家'],
  alternativePlan: ['恢复后按原计划继续', '改期，时间另行通知', '暂无替代方案，恢复前不开放'],
  notificationPlan: ['站内通知全部已报名玩家', '主办方另行发布公告', '影响较小，暂不单独通知'],
};

function isPositiveId(value) {
  const text = typeof value === 'number' ? String(value) : String(value == null ? '' : value).trim();
  // 只放行 JS 安全整数范围内的十进制正整数(≤15 位)
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
 *   schedule(发布)status == DRAFT 且发布检查无阻塞  (「仅草稿活动可以提交排期」)
 *   pause   SCHEDULED <= status < SETTLING 且未暂停     (「当前生命周期不能暂停」)
 *   resume  controlState == PAUSED                     (「活动未暂停」)
 */
function actionAvailability(state) {
  const s = state || {};
  const known = Number.isInteger(s.status);
  const unknown = '活动状态没读到，刷新后再操作';
  const ended = '活动已进入结算或已结束，不能再调整';
  const out = {};
  out.schedule = !known ? { enabled: false, hint: unknown }
    : s.status === ST_DRAFT ? { enabled: true, hint: '' }
      : { enabled: false, hint: '活动已发布，只有草稿需要发布' };
  if (s.controlState === CONTROL_PAUSED) {
    out.pause = { enabled: false, hint: '活动已暂停' };
    out.resume = { enabled: true, hint: '' };
  } else if (s.controlState === CONTROL_NORMAL) {
    out.resume = { enabled: false, hint: '活动未暂停' };
    out.pause = !known ? { enabled: false, hint: unknown }
      : s.status < ST_SCHEDULED ? { enabled: false, hint: '活动发布后才能暂停' }
        : s.status >= ST_SETTLING ? { enabled: false, hint: ended }
          : { enabled: true, hint: '' };
  } else {
    const hint = '控制状态待确认，已停用暂停/恢复';
    out.pause = { enabled: false, hint };
    out.resume = { enabled: false, hint };
  }
  return out;
}

/** 暂停某一项的最终文本:预设 + 补充文字,两者都可单独成立;都空返回 ''。 */
function composePauseField(entry) {
  const e = entry || {};
  const preset = typeof e.preset === 'string' ? e.preset.trim() : '';
  const extra = typeof e.extra === 'string' ? e.extra.trim() : '';
  if (preset && extra) return preset + '：' + extra;
  return preset || extra;
}

/** 表单校验:与服务端必填一致,返回第一条人话错误;通过返回 ''。 */
function validateForm(action, form) {
  const f = form || {};
  if (action === 'pause') {
    const values = PAUSE_FIELDS.map((k) => composePauseField(f[k]));
    if (values.some((v) => !v)) return '暂停的原因、影响范围、替代方案和通知策略都要选一项或写几句';
    if (values.some((v) => v.length > LIMITS.plan)) return '每项最多 ' + LIMITS.plan + ' 字';
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

/** 组请求体:字段名逐个对齐 OfficialEventV2PauseRequest / OfficialEventV2PartyActionRequest。 */
function buildPayload(action, form) {
  const f = form || {};
  if (action === 'pause') {
    const out = {};
    PAUSE_FIELDS.forEach((k) => { out[k] = composePauseField(f[k]); });
    return out;
  }
  if (action === 'resume') return { reason: f.reason.trim() };
  return {};
}

/**
 * 成功后的回读核对:'confirmed' 回读状态与预期一致;'mismatch' 读到了但不符(提示刷新,不宣称生效)。
 */
function verifyReadBack(action, after) {
  const s = after || {};
  if (action === 'schedule') return Number.isInteger(s.status) && s.status >= ST_SCHEDULED ? 'confirmed' : 'mismatch';
  if (action === 'pause') return s.controlState === CONTROL_PAUSED ? 'confirmed' : 'mismatch';
  if (action === 'resume') return s.controlState === CONTROL_NORMAL ? 'confirmed' : 'mismatch';
  return 'mismatch';
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
  PAUSE_FIELDS,
  PAUSE_PRESETS,
  isPositiveId,
  normalizeEventState,
  actionAvailability,
  composePauseField,
  validateForm,
  buildPayload,
  verifyReadBack,
  isUnknownOutcome,
};
