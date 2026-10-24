'use strict';
// C:U-027:活动生命周期语义状态的前端唯一真源。取值由后端
// com.chengyinhub.business.util.ActivityLifecycle 算出(camelCase),列表与详情读同一个值。
// ⚠️ 与 utils/order-status.js 的 ORDER_STATE_KEY(snake_case)是两套不同的词表:
// 那边是「玩家订单」的读模型(含退款/待支付等),这边是「活动档期/审核」状态。
// 两套各自自洽,不要混用;tests/unit/state-key-vocabulary-contract.test.js 钉住两边。
const ACTIVITY_STATE = Object.freeze({
  CANCELLED: 'cancelled',
  DRAFT: 'draft',
  PENDING: 'pending',
  REJECTED: 'rejected',
  OFFLINE: 'offline',
  NOT_STARTED: 'notStarted',
  RUNNING: 'running',
  COMPLETED: 'completed',
});

module.exports = { ACTIVITY_STATE };
