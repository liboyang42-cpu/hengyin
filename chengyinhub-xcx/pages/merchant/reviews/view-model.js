const { decodeDisplayText } = require('../../../utils/display-text.js');

// Never stringify an unsafe Number: its original decimal digits are already lost.
function decimalId(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? String(value) : null;
  if (typeof value !== 'string' || !/^[1-9]\d{0,18}$/.test(value)) return null;
  return value.length < 19 || value <= '9223372036854775807' ? value : null;
}

// Compatibility export used by merchant-finance and registration eligibility.
// Review route/list/action IDs use decimalId instead.
function positiveId(value) {
  if (value === null || value === undefined || value === '') return null;
  const text = String(value).trim();
  if (!/^\d+$/.test(text)) return null;
  const id = Number(text);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function parseReviewRouteOptions(options) {
  const input = options && typeof options === 'object' ? options : {};
  return {
    mode: input.mode === 'manage' ? 'manage' : 'public',
    merchantRowId: decimalId(input.merchantRowId),
  };
}

function fallbackRoute(mode) {
  return mode === 'manage'
    ? { method: 'reLaunch', url: '/pages/merchant/index/index' }
    : { method: 'switchTab', url: '/pages/index/index' };
}

function classifyLoadFailure(mode, response) {
  const code = Number(response && response.code);
  return mode === 'manage' && (code === 401 || code === 403) ? 'no-permission' : 'error';
}

function createReviewRequestId(kind, now, random, sequence) {
  const prefix = String(kind || 'action').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 12) || 'action';
  const timePart = Math.max(0, Number(now) || 0).toString(36);
  const randomPart = Math.floor(Math.max(0, Number(random) || 0) * 0x1000000).toString(36);
  const sequencePart = Math.max(0, Number(sequence) || 0).toString(36);
  return `mr-${prefix}-${timePart}-${randomPart}-${sequencePart}`.slice(0, 64);
}

// W10a #20:管理态四个筛选 + 全部都由服务端做(/api/merchant/reviews/manage?filter=),
// 计数来自服务端 filterCounts(本店全量),不再只数已加载的那一页。
const MANAGE_REVIEW_FILTERS = ['all', 'pending', 'low', 'photo', 'platform'];

function normalizeManageFilter(value) {
  const filter = String(value || 'all');
  return MANAGE_REVIEW_FILTERS.indexOf(filter) >= 0 ? filter : 'all';
}

// 缺字段(老后端)⇒ null:页面不显示数字,绝不退回去数已加载页。字段在但不合法 ⇒ undefined:整页按数据不完整处理。
function shapeFilterCounts(raw) {
  if (!Object.prototype.hasOwnProperty.call(raw, 'filterCounts') || raw.filterCounts == null) return null;
  const source = raw.filterCounts;
  if (typeof source !== 'object' || Array.isArray(source)) return undefined;
  const counts = {};
  for (let index = 0; index < MANAGE_REVIEW_FILTERS.length; index += 1) {
    const key = MANAGE_REVIEW_FILTERS[index];
    const value = strictNonNegative(source[key]);
    if (value === null) return undefined;
    counts[key] = value;
  }
  return counts;
}

function shapePage(raw, expectedMode, expectedPageNum, expectedPageSize, expectedFilter) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const mode = raw.mode;
  if ((mode !== 'manage' && mode !== 'public') || mode !== expectedMode || !Array.isArray(raw.items)) return null;
  // 服务端回显的筛选必须就是这次请求的筛选:切得快时旧响应不能把别的筛选结果写进当前 tab。
  if (mode === 'manage' && expectedFilter !== undefined && raw.filter != null
      && raw.filter !== normalizeManageFilter(expectedFilter)) return null;
  const filterCounts = mode === 'manage' ? shapeFilterCounts(raw) : null;
  if (filterCounts === undefined) return null;
  const total = strictNonNegative(raw.total);
  const pageNum = strictPositiveInt(raw.pageNum);
  const pageSize = strictPositiveInt(raw.pageSize);
  if (total === null || pageNum === null || pageSize === null || typeof raw.hasMore !== 'boolean') return null;
  if (expectedPageNum !== undefined && pageNum !== expectedPageNum) return null;
  if (expectedPageSize !== undefined && pageSize !== expectedPageSize) return null;
  if (raw.items.length > pageSize) return null;
  const items = raw.items.map(shapeReview);
  if (items.some((item) => item === null)) return null;
  const loadedThrough = (pageNum - 1) * pageSize + items.length;
  if (loadedThrough > total || raw.hasMore !== (loadedThrough < total)) return null;
  const eligibility = shapeEligibility(raw.eligibility);
  const pendingReplyCount = optionalNonNegative(raw, 'pendingReplyCount', 999999);
  const monthNewCount = optionalNonNegative(raw, 'monthNewCount', 999999);
  const replyRatePct = optionalNonNegative(raw, 'replyRatePct', 100);
  if (pendingReplyCount === null || monthNewCount === null || replyRatePct === null) return null;
  return {
    mode,
    total,
    pageNum,
    pageSize,
    hasMore: raw.hasMore === true,
    averageRating: ratingText(raw.averageRating),
    ratingStars: ratingStars(raw.averageRating),
    pendingReplyCount,
    monthNewCount,
    replyRatePct,
    filterCounts,
    eligibility,
    items,
  };
}

function shapeReview(raw) {
  const id = strictPositiveId(raw && raw.id);
  const version = strictNonNegative(raw && raw.version);
  const rating = Number(raw && raw.rating);
  if (!id || version === null || !Number.isInteger(rating) || rating < 1 || rating > 5) return null;
  if (!Array.isArray(raw.imageUrls) || raw.imageUrls.length > 9
      || !raw.imageUrls.every((item) => typeof item === 'string' && item.length > 0)) return null;
  if (!['VISIBLE', 'PENDING_REVIEW', 'HIDDEN'].includes(raw.status)) return null;
  if (typeof raw.verifiedRedemption !== 'boolean'
      || typeof raw.canReply !== 'boolean' || typeof raw.canReport !== 'boolean') return null;
  const imageUrls = raw.imageUrls.slice();
  const status = raw.status;
  return {
    id,
    version,
    rating,
    stars: [1, 2, 3, 4, 5].map((value) => ({ value, filled: value <= rating })),
    // 评价与回复按纯文本展示(回复编辑框也从这里回填):服务端过滤会把 x<y 存成 x&lt;y,解码一次。
    content: decodeDisplayText(String(raw.content || '')),
    imageUrls,
    authorNickname: String(raw.authorNickname || '城瘾玩家'),
    authorAvatar: String(raw.authorAvatar || ''),
    verifiedRedemption: raw.verifiedRedemption === true,
    status,
    statusClass: status === 'VISIBLE' ? 'visible' : (status === 'PENDING_REVIEW' ? 'pending' : 'hidden'),
    statusText: status === 'VISIBLE' ? '公开展示中' : (status === 'PENDING_REVIEW' ? '待平台复核' : '平台已下架'),
    merchantReply: decodeDisplayText(String(raw.merchantReply || '')),
    repliedAtText: formatTime(raw.repliedAt),
    createTimeText: formatTime(raw.createTime),
    canReply: raw.canReply === true,
    // 缺字段按「不能改」降级:老后端不会返回它,宁可少一个按钮,不能给一个必然 409 的按钮。
    canEditReply: raw.canEditReply === true,
    canReport: raw.canReport === true,
    // 仅管理态由服务端给出:新评价待复核,或有举报仍在平台审核中。缺字段按 false。
    underPlatformReview: raw.underPlatformReview === true,
  };
}

function shapeEligibility(raw) {
  if (!raw || typeof raw !== 'object') {
    return { canCreate: false, reasonCode: 'UNAVAILABLE', registrationId: null };
  }
  return {
    canCreate: raw.canCreate === true && positiveId(raw.registrationId) !== null,
    reasonCode: String(raw.reasonCode || 'UNAVAILABLE'),
    registrationId: positiveId(raw.registrationId),
  };
}

function buildCreateDraft(data) {
  const rating = Number(data && data.rating);
  const content = String((data && data.content) || '').trim();
  const imageUrls = Array.isArray(data && data.images) ? data.images.slice(0, 9) : [];
  if (!Number.isInteger(rating) || rating < 1 || rating > 5) return { valid: false, error: '请选择 1 到 5 星评分' };
  if (content.length < 2 || content.length > 1000) return { valid: false, error: '评价正文需填写 2 到 1000 字' };
  if (imageUrls.length > 9) return { valid: false, error: '评价图片最多 9 张' };
  return { valid: true, payload: { rating, content, imageUrls } };
}

function fingerprint(value) {
  return JSON.stringify(value || {});
}

function formatTime(value) {
  if (!value) return '';
  return String(value).replace('T', ' ').replace(/\.\d{3}(Z|[+-]\d\d:\d\d)?$/, '').slice(0, 16);
}

function ratingText(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 1 && number <= 5 ? number.toFixed(1) : '0.0'; // 9-18 全局规则:数字没有就显示 0,不写「暂无」/「—」(CU-M-10)
}

function ratingStars(value) {
  const number = Number(value);
  const filledCount = Number.isFinite(number) && number >= 1 && number <= 5
    ? Math.round(number)
    : 0;
  return [1, 2, 3, 4, 5].map((star) => ({ value: star, filled: star <= filledCount }));
}

function strictPositiveId(value) {
  return decimalId(value);
}

function strictPositiveInt(value) {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : null;
}

function strictNonNegative(value) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null;
}

function optionalNonNegative(raw, key, max) {
  if (!Object.prototype.hasOwnProperty.call(raw, key) || raw[key] === null || raw[key] === undefined) return 0;
  const value = strictNonNegative(raw[key]);
  return value !== null && value <= max ? value : null;
}

module.exports = {
  MANAGE_REVIEW_FILTERS,
  buildCreateDraft,
  classifyLoadFailure,
  createReviewRequestId,
  fallbackRoute,
  fingerprint,
  normalizeManageFilter,
  parseReviewRouteOptions,
  positiveId,
  decimalId,
  shapePage,
};
