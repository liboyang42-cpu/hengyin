const { toTimestamp } = require('../../../../utils/datetime.js');

function freeExplorationValidityScheduleIssues(ticket) {
  if (Number(ticket && ticket.mode) !== 2) return [];
  const start = String(ticket.startTime || '').trim();
  const end = String(ticket.endTime || '').trim();
  const startTs = toTimestamp(start);
  // 旧草稿的纯日期结束按当天末尾上送，与 _doSubmit 的归一化保持一致。
  const endTs = toTimestamp(/^\d{4}[-/]\d{1,2}[-/]\d{1,2}$/.test(end) ? end + ' 23:59:59' : end);
  const issues = [];
  if (start && !Number.isFinite(startTs)) {
    issues.push({ field: 'startTime', message: '票单开始日期无效' });
  }
  if (end && !Number.isFinite(endTs)) {
    issues.push({ field: 'endTime', message: '票单结束日期无效' });
  }
  if (Number.isFinite(startTs) && Number.isFinite(endTs) && endTs <= startTs) {
    issues.push({ field: 'endTime', message: '票单结束时间必须晚于开始时间' });
  }
  return issues;
}

function freeExplorationSaleScheduleIssues(ticket) {
  if (Number(ticket && ticket.mode) !== 2) return [];
  const start = String(ticket.saleStartTime || '').trim();
  const end = String(ticket.saleEndTime || '').trim();
  const issues = [];
  const startTs = toTimestamp(start);
  const endTs = toTimestamp(end);
  if (start && !Number.isFinite(startTs)) {
    issues.push({ field: 'saleStartTime', message: '售票开始日期无效' });
  }
  if (end && !Number.isFinite(endTs)) {
    issues.push({ field: 'saleEndTime', message: '售票结束日期无效' });
  }
  if (Number.isFinite(startTs) && Number.isFinite(endTs) && endTs < startTs) {
    issues.push({ field: 'saleEndTime', message: '售票结束日期不能早于开始日期' });
  }
  return issues;
}

function cityOrientationScheduleIssues(ticket) {
  if (Number(ticket && ticket.mode) !== 1) return [];

  const startTime = String((ticket && ticket.startTime) || '').trim();
  const endTime = String((ticket && ticket.endTime) || '').trim();
  const issues = [];

  if (!startTime) issues.push({ field: 'startTime', message: '请选择集合开始时间' });
  if (!endTime) issues.push({ field: 'endTime', message: '请选择集合结束时间' });
  if (startTime && endTime && endTime <= startTime) {
    issues.push({ field: 'endTime', message: '集合结束时间必须晚于开始时间' });
  }
  return issues;
}

module.exports = { cityOrientationScheduleIssues, freeExplorationSaleScheduleIssues, freeExplorationValidityScheduleIssues };
