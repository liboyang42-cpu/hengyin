/**
 * 俱乐部自建勋章(N2) · 纯函数,只消费 ApiClubController 既有合同,不另立字段。
 *
 *   POST /api/club/badge/list   body {id: clubId}          登录即可读;返回 GrowthBadge[]
 *        (selectByClub:id, badgeCode, badgeName, badgeDesc, iconUrl, unlockRule, status, sortNo, clubId, createTime)
 *   POST /api/club/badge/create body {clubId, badgeName, badgeDesc?, iconUrl?}
 *        仅主理人(club.member_id)且需「勋章设计」能力位;badgeCode 由服务端生成并回给 data.badgeCode
 *   POST /api/club/badge/grant  body {clubId, memberId, badgeCode}
 *        仅主理人;对方须是本团成员或主理人;重复颁发回 200 + msg「已颁发过」
 *
 * 字段上限取库表 growth_badge:badge_name varchar(120) NOT NULL、badge_desc varchar(255)、
 * icon_url varchar(255)。服务端只截断名称,描述/图标超长会落成库错误,所以客户端先挡。
 * 服务端没有「规则(unlockRule)」写入口,本页也不提供该输入。
 */

var NAME_MAX = 120;
var DESC_MAX = 255;
var ICON_MAX = 255;

function charLength(value) {
  return Array.from(String(value || '')).length;
}

function trimText(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function positiveId(value) {
  var parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

/** 列表行归一:缺 badgeCode 的行无法颁发,直接丢弃(不编造 code)。 */
function normalizeBadges(list) {
  if (!Array.isArray(list)) return [];
  var out = [];
  for (var i = 0; i < list.length; i++) {
    var item = list[i];
    if (!item || typeof item !== 'object') continue;
    var code = typeof item.badgeCode === 'string' ? item.badgeCode : '';
    if (!code) continue;
    out.push({
      id: item.id,
      badgeCode: code,
      badgeName: trimText(item.badgeName) || '未命名勋章',
      badgeDesc: trimText(item.badgeDesc),
      iconUrl: typeof item.iconUrl === 'string' ? item.iconUrl : '',
    });
  }
  return out;
}

/** 创建表单校验:返回 {ok, errors:{name,desc,icon}, payload}。 */
function validateCreateForm(form, clubId) {
  var name = trimText(form && form.name);
  var desc = trimText(form && form.desc);
  var icon = trimText(form && form.iconUrl);
  var errors = {};
  if (!name) errors.name = '请填写勋章名';
  else if (charLength(name) > NAME_MAX) errors.name = '勋章名最多 ' + NAME_MAX + ' 个字';
  if (charLength(desc) > DESC_MAX) errors.desc = '说明最多 ' + DESC_MAX + ' 个字';
  if (icon && icon.length > ICON_MAX) errors.icon = '图标地址过长，请重新上传';
  var id = positiveId(clubId);
  var ok = !!id && !errors.name && !errors.desc && !errors.icon;
  var payload = null;
  if (ok) {
    payload = { clubId: id, badgeName: name };
    if (desc) payload.badgeDesc = desc;
    if (icon) payload.iconUrl = icon;
  }
  return { ok: ok, errors: errors, payload: payload };
}

/** 成员名单归一(来自 /api/club/members):只留有 memberId 的在册成员。 */
function normalizeMembers(list) {
  if (!Array.isArray(list)) return [];
  var out = [];
  var seen = {};
  for (var i = 0; i < list.length; i++) {
    var m = list[i];
    if (!m || typeof m !== 'object') continue;
    var memberId = positiveId(m.memberId);
    if (!memberId || seen[memberId]) continue;
    seen[memberId] = true;
    out.push({
      memberId: memberId,
      nickname: trimText(m.nickname) || '成员' + memberId,
      avatar: typeof m.avatar === 'string' ? m.avatar : '',
      isOwner: m.isOwner === true,
    });
  }
  return out;
}

/** 颁发回执:服务端用 msg 区分「已颁发」与「已颁发过」(幂等),其余照原文。 */
function grantReceipt(res) {
  var msg = res && typeof res.msg === 'string' ? res.msg : '';
  if (msg === '已颁发过') return { repeated: true, text: '对方之前已经拿到这枚勋章，这次没有重复颁发。' };
  return { repeated: false, text: '这枚勋章已经记在对方名下。' };
}

module.exports = {
  NAME_MAX: NAME_MAX,
  DESC_MAX: DESC_MAX,
  ICON_MAX: ICON_MAX,
  charLength: charLength,
  positiveId: positiveId,
  normalizeBadges: normalizeBadges,
  validateCreateForm: validateCreateForm,
  normalizeMembers: normalizeMembers,
  grantReceipt: grantReceipt,
};
