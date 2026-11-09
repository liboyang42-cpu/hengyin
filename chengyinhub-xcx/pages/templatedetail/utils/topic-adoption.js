// U02-01 / U02-02 / §23.3 / §27.1:主题模板「采用条件」与「沿用地点 / 换城市」采用。
// 只翻译服务端 /api/template/topic-template/info 的 contentDelivery 与 /use 回包,不补默认结论:
// 没有权威事实就写「待补充」或原因,不写 0、不写「已验证」、不写「两端可玩」。

const PENDING = '待补充';

const EVIDENCE_TEXT = { VERIFIED: '已实地验证', PILOTED: '已试点', ESTIMATED: '预估，未实地验证', NONE: '未实测' };

function list(v) { return Array.isArray(v) ? v.filter(x => x != null && String(x).trim()).map(String) : []; }

function revisionText(revision) {
  if (!revision || revision.contentRevisionId == null) return PENDING;
  return revision.revisionNo != null ? '第 ' + revision.revisionNo + ' 版' : '已发布版本';
}

function placeText(location) {
  if (!location || typeof location.physicalPlaceRequired !== 'boolean') return PENDING;
  if (!location.physicalPlaceRequired) return '不需要到现场';
  const n = Number(location.stationWithAddressCount);
  return Number.isSafeInteger(n) && n > 0 ? '需要到现场 · ' + n + ' 个地点' : '需要到现场';
}

function operatingText(delivery) {
  const visibility = delivery && delivery.operatingRequirementsVisibility;
  if (visibility === 'PARTNER_ONLY') return '仅对合作商家开放';
  if (visibility !== 'INCLUDED') return PENDING;
  const op = delivery.operatingRequirements || {};
  const parts = [];
  if (op.needsBooking === true) parts.push('需预约');
  if (Number.isFinite(op.staffMinutesMax) && op.staffMinutesMax > 0) parts.push('主持约 ' + op.staffMinutesMax + ' 分钟');
  if (Number.isFinite(op.maxConcurrent) && op.maxConcurrent > 0) parts.push('同时最多 ' + op.maxConcurrent + ' 组');
  list(op.materials).slice(0, 2).forEach(m => parts.push(m));
  list(op.venues).slice(0, 1).forEach(v => parts.push(v));
  return parts.length ? parts.join(' · ') : PENDING;
}

function evidenceText(evidence) {
  return evidence && EVIDENCE_TEXT[evidence.evidenceLevel] || PENDING;
}

// 本基线没有平台运行矩阵(platformExecution=UNKNOWN):不写「两端可玩」。
function platformText(delivery) {
  const support = delivery && delivery.runtimeSupport;
  return support && support.platformExecution && support.platformExecution !== 'UNKNOWN' ? String(support.platformExecution) : '待核实';
}

function licenseText(license) {
  if (!license || license.status !== 'AUTHORITATIVE') return '许可信息未提供';
  const who = license.licensedEntity || license.owner;
  const until = license.validTo ? '至 ' + String(license.validTo).slice(0, 10) : '';
  return [who || '已登记许可', until].filter(Boolean).join(' · ');
}

function localeText(delivery) {
  if (!delivery || !delivery.sourceLocale) return PENDING;
  return delivery.sourceLocale === 'zh-CN' ? '简体中文' : delivery.sourceLocale;
}

// 详情页「采用条件」行;contentDelivery 整段缺失时返回空数组(页面整块不画,不画一排「待补充」)。
function deliveryRows(delivery) {
  if (!delivery || typeof delivery !== 'object') return [];
  return [
    { key: 'place', label: '地点', value: placeText(delivery.locationRequirement) },
    { key: 'operating', label: '投入', value: operatingText(delivery) },
    { key: 'evidence', label: '验证', value: evidenceText(delivery.evidence) },
    { key: 'platform', label: '端侧', value: platformText(delivery) },
    { key: 'locale', label: '语言', value: localeText(delivery) },
    { key: 'license', label: '许可', value: licenseText(delivery.license) },
    { key: 'revision', label: '版本', value: revisionText(delivery.revision) }
  ].map(row => Object.assign(row, { pending: row.value === PENDING || row.value === '待核实' || row.value === '许可信息未提供' }));
}

// §27.1:只把当前发布修订的 id 原样带给 /use;没有修订就不传(旧来源 → 服务端回 null = 未锁定来源修订)。
function revisionIdOf(delivery) {
  const id = delivery && delivery.revision && delivery.revision.contentRevisionId;
  return id != null && /^[1-9]\d*$/.test(String(id)) ? String(id) : '';
}

const SOURCE_CITY_OF = delivery => {
  const c = delivery && delivery.locationRequirement && delivery.locationRequirement.cityCode;
  return c && /^\d{6}$/.test(String(c)) ? String(c) : '';
};

// /use 请求:沿用地点 = SAME_CITY;换城市 = NEW_CITY + targetCityCode。来源没有城市事实时不传意图(= 旧版采用)。
function usePayload(input) {
  const data = { id: String(input.id), scope: input.scope || '' };
  if (input.intent === 'NEW_CITY') {
    if (!/^\d{6}$/.test(String(input.targetCityCode || ''))) return { error: '请选择目标城市' };
    if (input.sourceCityCode && input.sourceCityCode === String(input.targetCityCode)) return { error: '目标城市与原城市相同，请选择沿用地点' };
    data.bindingIntent = 'NEW_CITY';
    data.targetCityCode = String(input.targetCityCode);
  } else if (input.intent === 'SAME_CITY' && input.sourceCityCode) {
    data.bindingIntent = 'SAME_CITY';
  }
  if (input.revisionId) data.sourceContentRevisionId = input.revisionId;
  return { data };
}

// /use 回包:对象 data.topicId(不是标量)。bindingIssues 只有 level=error 才算挡发布。
function useResult(res) {
  const data = res && String(res.code) === '200' && res.data && typeof res.data === 'object' ? res.data : null;
  const topicId = data && data.topicId != null && /^[1-9]\d*$/.test(String(data.topicId)) ? String(data.topicId) : '';
  if (!topicId) return { ok: false, message: res && res.msg || '' };
  const issues = Array.isArray(data.bindingIssues) ? data.bindingIssues : [];
  return {
    ok: true,
    topicId,
    blocking: issues.filter(i => i && i.level === 'error').length,
    pendingRebind: Array.isArray(data.pendingRebindSlotKeys) ? data.pendingRebindSlotKeys.length : 0,
    sourceContentRevisionId: data.sourceContentRevisionId == null ? null : String(data.sourceContentRevisionId)
  };
}

module.exports = { deliveryRows, revisionIdOf, sourceCityOf: SOURCE_CITY_OF, usePayload, useResult, PENDING };

// U02-05 / §27.5:玩家只读 GET /api/media/asset/attribution 的 sourceType/attribution/aiGenerated/sourcePendingConfirmation。
// 只显示署名、AI 标识、来源待确认;不显示许可备注、模型、时间或存储 key。读失败 / 未审核(422)不画。
function creditText(data) {
  if (!data || typeof data !== 'object') return '';
  const parts = [];
  if (data.attribution && String(data.attribution).trim()) parts.push(String(data.attribution).trim().slice(0, 40));
  if (data.aiGenerated === true) parts.push('AI 生成');
  if (data.sourcePendingConfirmation === true) parts.push('来源待确认');
  return parts.join(' · ');
}
module.exports.creditText = creditText;
