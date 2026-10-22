// W10a #26:「自由探索期次」为空时的具体原因。
// 原因只来自 /api/club-compensation/editions/empty-reason 的 reasonCode;前端不从空数组猜。
// 拿不到原因(加载中 / 失败 / 未知码)时只说「还没有」,不说为什么。

const GENERIC = { title: '还没有可分享的自由探索期次', sub: '' };

const BY_REASON = {
  NOT_UNDERTAKEN: {
    title: '本俱乐部还没有承接自由探索期次',
    sub: '作为执行俱乐部承接合作、期次开售后，会出现在这里',
  },
  UNDERTAKEN_NOT_ON_SALE: {
    title: '已承接的期次还没有开售',
    sub: '开售并绑定本俱乐部后会出现在这里，可带票分享',
  },
  NO_PERMISSION: {
    title: '仅主理人或管理员可查看期次',
    sub: '期次与带票分享只对俱乐部负责人开放',
  },
  HAS_EDITIONS: {
    title: '期次列表已有更新',
    sub: '重新加载后可查看',
  },
};

function editionEmptyCopy(state, reasonCode) {
  if (state === 'ready' && Object.prototype.hasOwnProperty.call(BY_REASON, reasonCode)) {
    return { title: BY_REASON[reasonCode].title, sub: BY_REASON[reasonCode].sub, retry: reasonCode === 'HAS_EDITIONS' };
  }
  if (state === 'loading') return { title: GENERIC.title, sub: '正在确认原因…', retry: false };
  if (state === 'error') return { title: GENERIC.title, sub: '暂时无法确认原因', retry: true };
  return { title: GENERIC.title, sub: GENERIC.sub, retry: false };
}

function shapeEditionEmptyReason(raw, clubId) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  if (String(raw.clubId) !== String(clubId)) return null;
  return Object.prototype.hasOwnProperty.call(BY_REASON, raw.reasonCode) ? raw.reasonCode : null;
}

module.exports = { editionEmptyCopy, shapeEditionEmptyReason };
