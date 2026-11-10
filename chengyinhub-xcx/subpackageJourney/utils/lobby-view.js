'use strict';
// r2-store 开局大厅(refs3/STRUCTURE.md §2):在同行席位页上按 Questo 大厅结构重排。
// 只翻译 /api/play/player-journey/seats 的事实:
// - M = seatCount,N = 已被占用的席位(买家本人 + 已领取 / 已核销 / 已占用);读不到 seatCount 就不写 N/M。
// - 服务端不下发被邀请人的头像与昵称(recipientIdentityVisible=false),已领席位只画席位号圆,不编名字。
// - 空位只有在买家本人、非只读、服务端还有 freeSeats 时才能点开邀请。
// - 一人票(seatCount=1)不画同行卡;加座服务端没有从报名单发起的入口,不出「加席位」。
const OCCUPIED = { BUYER: true, VERIFIED: true, CLAIMED: true, RESERVED: true };

function isNum(v) {
  return v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
}

/**
 * @param {object} seats journey-view.seatsView 的结果
 * @param {object} me { avatar, initial } 买家本人(只有买家能读这个接口)
 */
function lobbyView(seats, raw, me) {
  if (!seats) return null;
  const total = raw && isNum(raw.seatCount) ? Number(raw.seatCount) : null;
  const self = me || {};
  const circles = (seats.seats || []).map(s => {
    const kind = s.state === 'BUYER' ? 'me'
      : OCCUPIED[s.state] ? 'taken'
        : s.state === 'INVITED' ? 'invited'
          : s.state === 'AVAILABLE' ? 'open' : 'off';
    return {
      key: s.key,
      no: s.no,
      kind,
      label: kind === 'me' ? '我' : s.label,
      avatar: kind === 'me' ? (self.avatar || '') : '',
      initial: kind === 'me' ? (self.initial || '我') : String(s.no),
      tappable: kind === 'open' && seats.canInvite
    };
  });
  const taken = circles.filter(c => c.kind === 'me' || c.kind === 'taken').length;
  const invited = circles.filter(c => c.kind === 'invited' || c.kind === 'taken')
    .map(c => (seats.seats || []).find(s => s.key === c.key))
    .filter(Boolean);
  return {
    showParty: total !== null && total > 1,
    countText: total !== null ? taken + '/' + total : '',
    circles,
    multi: total !== null && total > 1,
    canInvite: !!seats.canInvite,
    invited
  };
}

/** 去起点:带原活动/主题与报名单进游玩页,准入仍由游玩页向服务端核验。 */
function playUrl(seats) {
  if (!seats || !seats.topicId) return '';
  const reg = seats.registrationId ? '&registrationId=' + seats.registrationId : '';
  if (Number(seats.ownerType) === 2 && seats.ownerId) return '/pages/play/index?activityId=' + seats.ownerId + '&topicId=' + seats.topicId + reg;
  return '/pages/play/index?topicId=' + seats.topicId + reg + '&entry=ticket';
}

module.exports = { lobbyView, playUrl };
