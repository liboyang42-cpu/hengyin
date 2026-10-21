// BL-76 商家核销记录(只读):POST /api/merchant/verification-records
// 后端直接返回 cms_registration_scan 行(CmsRegistrationScan),按 createTime 倒序、一次全量。
// 只用实体已有字段:id / registrationId / name / status(0 未验证 1 已验证) / validationMethod / createTime。
const STATUS_TABS = [
  { key: 'VERIFIED', label: '已核销' },
  { key: 'UNVERIFIED', label: '未核销' },
];

const METHOD_TEXT = { 1: '文字验证', 2: '图片验证', 3: '选项验证' };

function text(value) {
  return value == null ? '' : String(value).trim();
}

function isVerified(row) {
  return Number(row && row.status) === 1;
}

function shapeRecord(row) {
  if (!row || typeof row !== 'object') return null;
  const id = text(row.id);
  if (!id) return null;
  const createTime = text(row.createTime);
  const registrationId = Number(row.registrationId);
  const verified = isVerified(row);
  return {
    id,
    nameText: text(row.name) || '未命名据点',
    registrationText: Number.isSafeInteger(registrationId) && registrationId > 0 ? `报名 #${registrationId}` : '',
    methodText: METHOD_TEXT[Number(row.validationMethod)] || '',
    statusKey: verified ? 'VERIFIED' : 'UNVERIFIED',
    statusText: verified ? '已核销' : '未核销',
    statusTone: verified ? 'success' : 'neutral',
    dateKey: createTime.slice(0, 10),
    timeText: createTime.slice(11, 16),
  };
}

function shapeRecordList(data) {
  if (!Array.isArray(data)) return null;
  return data.map(shapeRecord).filter(Boolean);
}

function countByStatus(items) {
  const counts = { VERIFIED: 0, UNVERIFIED: 0 };
  (items || []).forEach((item) => { counts[item.statusKey] += 1; });
  return counts;
}

function groupRecords(items, statusKey) {
  const groups = [];
  const byKey = {};
  (items || []).forEach((item) => {
    if (item.statusKey !== statusKey) return;
    const key = item.dateKey || 'unknown';
    if (!byKey[key]) {
      byKey[key] = { key, label: item.dateKey || '时间未知', items: [] };
      groups.push(byKey[key]);
    }
    byKey[key].items.push(item);
  });
  return groups;
}

module.exports = {
  STATUS_TABS,
  shapeRecord,
  shapeRecordList,
  countByStatus,
  groupRecords,
};
