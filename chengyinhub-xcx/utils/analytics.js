const { isValidMobile } = require('./form-state.js');

const STORAGE_KEY = 'p3_analytics_queue_v1';
const MAX_QUEUE = 100;
const MAX_BATCH = 50;
// 自动幂等键的进程内序号：同一毫秒的不同事件不能因随机后缀碰撞被同键去重误吞。
let generatedKeySeq = 0;
const SENSITIVE_PROPERTY_KEY = /(phone|mobile|bank|card|identity|idcard|realname|name|email|address|location|latitude|longitude|openid|unionid|session|token|keyword|content|comment|message|reply|input|output|text|title|description)/i;
const EMAIL_VALUE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const LONG_DIGITS_VALUE = /^\d{15,19}$/;

const ALLOWED_EVENTS = {
  search_submit: true,
  content_view: true,
  content_like: true,
  content_favorite: true,
  content_share: true,
  signup_submit: true,
  payment_success: true,
  topic_publish_success: true,
  activity_publish_success: true,
  template_reuse: true,
  coupon_verify: true,
  withdrawal_submit: true,
  // M9 埋点与学习飞轮:自玩/游玩/掉落/点亮/轨迹/XP/转化 事件(彩蛋 2026-09-09 整条下线)
  solo_ticket_purchase: true,
  play_arrive_success: true,
  play_arrive_fallback: true,
  node_abandon: true,
  drop_shown: true,
  citymap_view: true,
  citymap_share: true,
  track_record_start: true,
  track_record_finish: true,
  track_to_topic: true,
  xp_alloc_done: true,
  become_leader_cta: true,
  // P0 自由定向推荐条:换一个 / 采纳(完成即推荐节点)
  play_rec_swap: true,
  play_rec_accept: true,
  // 完赛结算页附近路线卡：曝光 → 点击购票详情，两段组成一跳灰度漏斗。
  finish_route_recommendation_shown: true,
  finish_route_recommendation_click: true,
  // P2 漫游 roam Core Loop:开始 / 发现 POI / 结束结算
  roam_start: true,
  roam_poi_found: true,
  roam_finish: true,
  // 无形陪伴 V1：只记录表面是否真实展示/关闭，不记录话术、地点坐标或足迹内容。
  roam_route_story_shown: true,
  roam_poi_meaning_shown: true,
  roam_footprint_hint_shown: true,
  roam_footprint_hint_dismissed: true,
  // P0-1 首次发现商家二选一卡:领券/做任务点击(变现钩子转化)
  roam_discover_reward_click: true,
  roam_exploreday_reco_shown: true,
  roam_exploreday_reco_click: true,
  roam_share_channel: true,
  // U-171 分享回执(漫游 / 广场):只放渠道码、结果码与业务 id
  roam_share_complete: true,
  roam_share_fail: true,
  square_share_complete: true,
  square_share_fail: true,
  // P1-021 章节块流形状不对、故事流回落 description:只放章节 id 与原因码
  chapter_blocks_parse_failed: true,
  // 漫游 NPC Event 轨:profile 到货 / 气泡真实展示(properties 无原文无坐标)
  npc_profile_loaded: true,
  npc_bubble_impression: true,
  npc_chat_open: true,
  npc_chat_submit: true,
  npc_chat_complete: true,
  npc_chat_cancel: true
};

let sessionContext = {
  sessionId: '',
  source: 'wechat_miniprogram'
};
let sessionAccountId = '';
let accountGeneration = 0;

function nowSessionId() {
  return 's_' + Date.now() + '_' + Math.floor(Math.random() * 100000);
}

function getAppSafe() {
  try {
    return getApp();
  } catch (e) {
    return null;
  }
}

function currentAccountId() {
  const app = getAppSafe();
  if (!app || typeof app.getUserID !== 'function') return 'anonymous';
  const id = app.getUserID();
  return id === null || id === undefined || id === '' ? 'anonymous' : String(id);
}

function currentAuthorization() {
  const app = getAppSafe();
  try { return app && typeof app.getAuthorization === 'function' ? app.getAuthorization() : ''; }
  catch (e) { return null; }
}

function queueKey(accountId) {
  return STORAGE_KEY + ':' + (accountId || currentAccountId());
}

function discardLegacyQueue() {
  try { wx.removeStorageSync(STORAGE_KEY); } catch (e) {}
}

// 未落盘的变化仅保留在本进程，落盘成功以前不能投递或确认。
const pendingQueues = Object.create(null);
function copyQueue(queue) { return JSON.parse(JSON.stringify(queue)); }

function getQueue(accountId) {
  if (pendingQueues[accountId]) return copyQueue(pendingQueues[accountId]);
  try {
    discardLegacyQueue();
    const queue = wx.getStorageSync(queueKey(accountId));
    if (queue && !Array.isArray(queue)) return null;
    return copyQueue(queue || []);
  } catch (e) {
    return null; // 读取失败不等于空队列，不能覆盖原记录。
  }
}

function setQueue(queue, accountId) {
  try {
    wx.setStorageSync(queueKey(accountId), copyQueue(queue));
    delete pendingQueues[accountId];
    return true;
  } catch (e) {
    pendingQueues[accountId] = copyQueue(queue);
    return false;
  }
}

function ensureAccountContext() {
  const accountId = currentAccountId();
  if (sessionAccountId !== accountId) {
    accountGeneration++;
    sessionAccountId = accountId;
    sessionContext = { sessionId: nowSessionId(), source: 'wechat_miniprogram' };
  } else if (!sessionContext.sessionId) {
    sessionContext.sessionId = nowSessionId();
  }
  return accountId;
}

function currentPagePath() {
  try {
    const pages = getCurrentPages();
    const page = pages && pages.length ? pages[pages.length - 1] : null;
    return page && page.route ? '/' + page.route : '';
  } catch (e) {
    return '';
  }
}

function sanitizeProperties(properties) {
  const source = properties && typeof properties === 'object' && !Array.isArray(properties) ? properties : {};
  const sanitized = {};
  Object.keys(source).forEach(function (key) {
    const value = source[key];
    if (SENSITIVE_PROPERTY_KEY.test(key) || value === null || value === undefined) return;
    if (typeof value === 'number' && Number.isFinite(value)) {
      sanitized[key] = value;
      return;
    }
    if (typeof value === 'boolean') {
      sanitized[key] = value;
      return;
    }
    if (typeof value === 'string' && value.length <= 64
        && !isValidMobile(value) && !EMAIL_VALUE.test(value) && !LONG_DIGITS_VALUE.test(value)) {
      sanitized[key] = value;
    }
  });
  return sanitized;
}

function sanitizePropertiesJson(payload) {
  let properties = payload && payload.properties;
  if ((!properties || typeof properties !== 'object') && payload && payload.propertiesJson) {
    try {
      properties = JSON.parse(payload.propertiesJson);
    } catch (e) {
      properties = {};
    }
  }
  return JSON.stringify(sanitizeProperties(properties));
}

function normalizePayload(eventName, payload) {
  ensureAccountContext();
  const data = payload || {};
  return {
    eventName: eventName,
    // 身份归因由服务端认证用户处理，不把 openid 等稳定标识写入埋点队列。
    anonymousId: '',
    actorScope: 'member:' + sessionAccountId,
    sessionId: data.sessionId || sessionContext.sessionId,
    source: data.source || sessionContext.source,
    pagePath: data.pagePath || currentPagePath(),
    bizType: data.bizType || '',
    bizId: data.bizId || null,
    cityCode: data.cityCode || '',
    propertiesJson: sanitizePropertiesJson(data),
    idempotencyKey: data.idempotencyKey || data.eventKey
      || eventName + '_' + Date.now() + '_' + (++generatedKeySeq) + '_' + Math.floor(Math.random() * 10000),
    occurredAt: new Date().toISOString()
  };
}

function postBatch(events, onDone) {
  const app = getAppSafe();
  if (!app || !app.sendRequest || !app.globalData || !app.globalData.siteBaseUrl) {
    typeof onDone === 'function' && onDone(false);
    return;
  }
  // 走统一请求通道(Phase 1.3):认证由 request-client 自动注入,不再手拼 Authorization。
  // 埋点全程静默:silentError 关业务错误 toast、successStatusAbnormal 吞 HTTP 异常,均归一为投递失败。
  const batchSize = events.length;
  try {
    app.sendRequest({
      url: '/api/analytics/events/batch',
      method: 'POST',
      header: { 'content-type': 'application/json' },
      data: { events: events },
      silentError: true,
      success: function (body) {
        const accepted = body && body.data && body.data.accepted;
        typeof onDone === 'function' && onDone(!!(body && body.code === 200
          && Number.isInteger(accepted) && accepted === batchSize));
      },
      successStatusAbnormal: function () {
        typeof onDone === 'function' && onDone(false);
      },
      fail: function () {
        typeof onDone === 'function' && onDone(false);
      }
    });
  } catch (e) {
    typeof onDone === 'function' && onDone(false);
  }
}

function setSessionContext(ctx) {
  const accountId = currentAccountId();
  if (sessionAccountId !== accountId) {
    accountGeneration++;
    sessionAccountId = accountId;
    sessionContext = { sessionId: '', source: 'wechat_miniprogram' };
  }
  sessionContext = Object.assign({}, sessionContext, ctx || {});
  if (!sessionContext.sessionId) {
    sessionContext.sessionId = nowSessionId();
  }
}

// 投递锁:同一时刻只允许一个批次在途,避免并发 flush 重复投递同一队头(契约#35)。
let flushing = false;

function flush() {
  if (flushing) return;
  const accountId = ensureAccountContext();
  const queue = getQueue(accountId);
  if (!queue || !queue.length || accountId === 'anonymous') return;
  if (pendingQueues[accountId] && !setQueue(queue, accountId)) return;
  const generation = accountGeneration;
  const authorization = currentAuthorization(); // 仅内存快照，不落盘/埋点。
  const attempted = [];
  flushing = true;
  function accountChanged() {
    return currentAccountId() !== accountId || generation !== accountGeneration
      || currentAuthorization() !== authorization;
  }

  function drain() {
    if (accountChanged()) {
      flushing = false;
      // 旧代响应不写任一账号队列，新账号可自行排空原身份队列。
      flush();
      return;
    }
    const latest = getQueue(accountId);
    if (!latest) { flushing = false; return; }
    if (pendingQueues[accountId] && !setQueue(latest, accountId)) { flushing = false; return; }
    const batch = copyQueue(latest.filter(function (event) {
      return attempted.indexOf(JSON.stringify(event)) < 0;
    }).slice(0, MAX_BATCH));
    if (!batch.length) { flushing = false; return; }
    const snapshots = batch.map(function (event) { return JSON.stringify(event); });
    attempted.push.apply(attempted, snapshots);
    let settled = false;
    postBatch(batch, function (ok) {
      if (settled) return;
      settled = true;
      if (accountChanged()) {
        flushing = false;
        flush();
        return;
      }
      if (ok) {
        const current = getQueue(accountId);
        if (!current) { flushing = false; return; }
        const remaining = current.filter(function (event) {
          const index = snapshots.indexOf(JSON.stringify(event));
          if (index < 0) return true;
          snapshots.splice(index, 1);
          return false;
        });
        // 出队落盘失败保留原已确认事件，重试仍用原身份；不忙循环。
        if (!setQueue(remaining, accountId)) {
          pendingQueues[accountId] = current;
          flushing = false;
          return;
        }
      }
      // 本轮每个原对象至多尝试一次；单批失败不阻断后批，也不盲重发未知原批。
      drain();
    });
  }
  drain();
}

function track(eventName, payload) {
  if (!ALLOWED_EVENTS[eventName]) return false;
  const accountId = ensureAccountContext();
  const queue = getQueue(accountId);
  if (!queue || queue.length >= MAX_QUEUE) return false;
  const event = normalizePayload(eventName, payload);
  // 同键重试沿用原内容与时间；异内容不替换已经排队的请求。
  const duplicate = queue.find(function (item) {
    return item.eventName === event.eventName && item.idempotencyKey === event.idempotencyKey;
  });
  if (duplicate) { flush(); return false; }
  queue.push(event);
  const persisted = setQueue(queue, accountId);
  if (persisted) flush();
  return persisted;
}

module.exports = {
  track: track,
  flush: flush,
  setSessionContext: setSessionContext,
  sanitizeProperties: sanitizeProperties
};
