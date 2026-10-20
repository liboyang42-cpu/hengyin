// BL-100 商家触达任务记录(只读)。
// GET /api/merchant/crm/campaigns          → MerchantCrmCampaignVO[](最近 20 条,recipients 为空)
// GET /api/merchant/crm/campaigns/{id}     → MerchantCrmCampaignVO(含逐人结果 recipients;不含联系方式,客户名为昵称原文)
// 状态取自 MerchantCrmCampaign / MerchantCrmCampaignRecipient 常量;未知状态如实写「其他状态」。
const CAMPAIGN_STATUS = {
  READY: { text: '待投递', tone: 'neutral' },
  PROCESSING: { text: '投递中', tone: 'neutral' },
  SUCCESS: { text: '已完成', tone: 'success' },
  PARTIAL_FAILED: { text: '部分未送达', tone: 'warning' },
  NO_ELIGIBLE: { text: '无可触达客户', tone: 'neutral' },
};

const RECIPIENT_STATUS = {
  PENDING: { text: '待投递', tone: 'neutral' },
  DELIVERED: { text: '已送达', tone: 'success' },
  SKIPPED_NO_CONSENT: { text: '未同意接收', tone: 'neutral' },
  SKIPPED_FREQUENCY: { text: '今日已触达，跳过', tone: 'neutral' },
  RETRYABLE_BUSY: { text: '暂未送达', tone: 'warning' },
  FAILED: { text: '投递失败', tone: 'warning' },
};

const CHANNEL_TEXT = { IN_APP: '站内消息', COUPON: '优惠券' };

function text(value) {
  return value == null ? '' : String(value).trim();
}

function count(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

function pad(n) {
  return n < 10 ? `0${n}` : String(n);
}

// 后端是 java.util.Date,无 @JsonFormat:可能是毫秒数,也可能是 ISO/「yyyy-MM-dd HH:mm:ss」字符串。
function formatTime(value) {
  if (value == null || value === '') return '';
  let date = null;
  if (typeof value === 'number') date = new Date(value);
  else {
    const raw = text(value);
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(raw)) return raw.slice(0, 16);
    date = new Date(raw);
  }
  if (!date || Number.isNaN(date.getTime())) return '';
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function statusOf(map, raw) {
  const key = text(raw).toUpperCase();
  return map[key] || { text: '其他状态', tone: 'neutral' };
}

function shapeCampaign(row) {
  if (!row || typeof row !== 'object') return null;
  const id = Number(row.id);
  if (!Number.isSafeInteger(id) || id <= 0) return null;
  const status = statusOf(CAMPAIGN_STATUS, row.status);
  const recipientCount = count(row.recipientCount);
  const deliveredCount = count(row.deliveredCount);
  return {
    id,
    titleText: text(row.title) || '未命名触达',
    channelText: CHANNEL_TEXT[text(row.channel).toUpperCase()] || '',
    statusText: status.text,
    statusTone: status.tone,
    timeText: formatTime(row.createTime),
    recipientCount,
    deliveredCount,
    noConsentCount: count(row.noConsentCount),
    frequencySkippedCount: count(row.frequencySkippedCount),
    failedCount: count(row.failedCount),
    retryableCount: count(row.retryableCount),
    summaryText: `送达 ${deliveredCount || 0} / ${recipientCount || 0} 人`,
  };
}

function shapeCampaignList(data) {
  if (!Array.isArray(data)) return null;
  return data.map(shapeCampaign).filter(Boolean);
}

function shapeRecipient(row, index) {
  if (!row || typeof row !== 'object') return null;
  const status = statusOf(RECIPIENT_STATUS, row.status);
  const id = Number(row.recipientId);
  return {
    key: Number.isSafeInteger(id) && id > 0 ? `r${id}` : `i${index}`,
    nameText: text(row.customerName) || '客户',
    statusText: status.text,
    statusTone: status.tone,
    reasonText: status.tone === 'warning' ? text(row.failureMessage) : '',
    timeText: formatTime(row.deliveredAt),
  };
}

function shapeCampaignDetail(data) {
  const campaign = shapeCampaign(data);
  if (!campaign) return null;
  const rows = Array.isArray(data.recipients) ? data.recipients : [];
  campaign.recipients = rows.map(shapeRecipient).filter(Boolean);
  return campaign;
}

module.exports = {
  CAMPAIGN_STATUS,
  RECIPIENT_STATUS,
  formatTime,
  shapeCampaign,
  shapeCampaignList,
  shapeCampaignDetail,
};
