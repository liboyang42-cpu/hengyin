// W7 SOC-15(2026-11-16):俱乐部邀请链接的纯逻辑,页面 pages/club/detail 只负责请求和渲染。
// 链接仍是俱乐部页 /pages/club/detail/index?id=<俱乐部>&invite=<邀请码>;邀请码 10 位,字母表与服务端一致。
// 邀请不改加入规则:接受邀请走原有 /api/club/join(公开团直接加入,审批团进待审),只记次数、带去目标频道。
// 接口:GET /api/club/invites/{code}(落地信息)、POST /api/club/invites/{code}/accept(接受)。

const CODE_PATTERN = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$/;

function parseInviteCode(options) {
  const raw = options && options.invite != null ? String(options.invite) : '';
  return CODE_PATTERN.test(raw) ? raw : '';
}

function landingUrl(code) {
  return '/api/club/invites/' + code;
}

function acceptUrl(code) {
  return '/api/club/invites/' + code + '/accept';
}

const STATE_TITLES = {
  EXPIRED: '邀请已失效',
  FULL: '邀请次数已用完',
  REVOKED: '邀请已撤销',
};

// 落地信息 → 页面横幅。有效时说明谁邀请、加入后去哪;失效时说明原因,并提示仍可按俱乐部规则申请。
function inviteBanner(landing) {
  if (!landing || typeof landing !== 'object') return null;
  const state = String(landing.state || '');
  const clubName = landing.club_name ? String(landing.club_name) : '';
  if (landing.already_member) return null;
  if (state === 'VALID') {
    const channel = landing.channel_name ? String(landing.channel_name) : '';
    return {
      valid: true,
      title: clubName ? '你被邀请加入「' + clubName + '」' : '你收到一个俱乐部邀请',
      sub: channel ? '加入后带你去 #' + channel : '',
      channelId: landing.channel_id ? String(landing.channel_id) : '',
    };
  }
  return {
    valid: false,
    title: STATE_TITLES[state] || '邀请已失效',
    sub: '你仍可按俱乐部的规则申请加入',
    channelId: '',
  };
}

// 接受成功后的去向:有目标频道且已加入 → 频道目录;待审 → 留在本页。
function afterAcceptUrl(clubId, result) {
  const state = result && result.state ? String(result.state) : '';
  const channelId = result && result.channel_id ? String(result.channel_id) : '';
  if (!channelId || !/^[1-9][0-9]{0,18}$/.test(String(clubId || ''))) return '';
  if (state !== 'joined' && state !== 'MEMBER') return '';
  return '/pages/club/channels/index?clubId=' + clubId;
}

module.exports = { parseInviteCode, landingUrl, acceptUrl, inviteBanner, afterAcceptUrl };
