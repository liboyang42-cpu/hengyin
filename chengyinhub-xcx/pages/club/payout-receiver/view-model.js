// 俱乐部主个人收款:把 /api/club/personal-receiver/status 的服务端事实转成页面状态。
// 只认服务端给的 receiverState / paused / payoutEnabled / canUnbind,不在端上推断资金结果。
const PAUSE_TEXT = {
  PERSONAL_BINDING_REQUIRED: '绑定尚未确认',
};

// 后端以编码返回的业务失败,统一转成人话;未知的一律给兜底,不把编码露给用户。
const ERROR_TEXT = {
  PERSONAL_RECEIVER_IDENTITY_MISMATCH: '当前微信与账号不一致，无法绑定',
  PERSONAL_RECEIVER_IDENTITY_IMMUTABLE: '已绑定其他微信，请先解除绑定',
  PERSONAL_RECEIVER_PROVIDER_NOT_CONFIRMED: '微信暂未确认，请稍后重试',
  PERSONAL_RECEIVER_APPID_MISMATCH: '收款暂未开放',
  CLUB_OWNER_REQUIRED: '仅俱乐部主理人可设置收款',
  CLUB_REQUIRED: '缺少俱乐部信息',
  WECHAT_FRESH_CODE_EXCHANGE_FAILED: '微信登录失败，请重试',
  WECHAT_MINI_IDENTITY_REQUIRED: '请先完成微信登录',
  MEMBER_UNAVAILABLE: '账号暂不可用',
};

function errorText(msg, fallback) {
  if (typeof msg === 'string' && ERROR_TEXT[msg]) return ERROR_TEXT[msg];
  // 业务文案(中文)原样透出,编码类(全大写下划线)不透出
  if (typeof msg === 'string' && msg && !/^[A-Z0-9_]+$/.test(msg)) return msg;
  return fallback;
}

function shapeStatus(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data) || data.available !== true) return null;
  const state = data.receiverState;
  if (state !== 'UNBOUND' && state !== 'CONSENTED' && state !== 'VERIFIED') return null;
  const payoutEnabled = data.payoutEnabled === true;
  const paused = data.paused === true;
  const bound = state !== 'UNBOUND';
  const pageState = !bound ? 'unbound' : (paused ? 'paused' : 'bound');
  return {
    pageState,
    stateText: pageState === 'unbound' ? '未绑定' : (pageState === 'paused' ? '已暂停' : '已绑定'),
    stateTone: pageState === 'bound' ? 'success' : (pageState === 'paused' ? 'warning' : 'neutral'),
    payoutText: payoutEnabled ? '' : '平台开通后开始',
    pauseText: paused ? (PAUSE_TEXT[data.pauseReason] || '绑定尚未确认') : '',
    canUnbind: bound && data.canUnbind === true,
  };
}

module.exports = { shapeStatus, errorText, PAUSE_TEXT, ERROR_TEXT };
