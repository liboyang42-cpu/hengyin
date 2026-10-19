function buildGroupCodeIssuePayload(activityId) {
  const id = Number(activityId);
  return Number.isSafeInteger(id) && id > 0 ? { activityId: id } : null;
}

function formatActivityStart(activity) {
  const dateSource = String(activity && activity.startDate || '').trim();
  const timeSource = String(activity && activity.startTime || '').trim();
  const dateMatch = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2}))?/.exec(dateSource);
  const timeMatch = /(\d{1,2}):(\d{2})/.exec(timeSource || dateSource);
  let dateText = '';
  let timeText = '';

  if (dateMatch) {
    const month = Number(dateMatch[2]);
    const day = Number(dateMatch[3]);
    if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
      dateText = month + '月' + day + '日';
    }
  }
  if (timeMatch) {
    const hour = Number(timeMatch[1]);
    const minute = Number(timeMatch[2]);
    if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
      timeText = String(hour).padStart(2, '0') + ':' + String(minute).padStart(2, '0');
    }
  }
  return dateText && timeText ? (dateText + ' ' + timeText) : '';
}

function listGroupCodeActivities(activities) {
  return (Array.isArray(activities) ? activities : []).map((activity) => {
    const payload = buildGroupCodeIssuePayload(activity && activity.id);
    if (!payload) return null;
    const name = activity.name || ('场次 ' + payload.activityId);
    const start = formatActivityStart(activity);
    return {
      id: payload.activityId,
      name: start ? (name + ' · ' + start) : name,
    };
  }).filter(Boolean);
}

// 领队端「码刷新倒计时」文案(X034):码 5 分钟有效、到点自动重出,
// 现场要让商家看到「这张码还能扫多久」,避免扫到刚过期的码。非正数/非法值不出文案。
function formatGroupCodeCountdown(seconds) {
  const total = Math.floor(Number(seconds));
  if (!Number.isFinite(total) || total <= 0) return '';
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  return minutes + ':' + String(rest).padStart(2, '0') + ' 后自动刷新';
}

module.exports = { buildGroupCodeIssuePayload, listGroupCodeActivities, formatGroupCodeCountdown };
