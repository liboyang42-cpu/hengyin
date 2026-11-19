'use strict';
// 前端守卫的唯一判定(审核清单 §6.5-6:商家 27 页 / 俱乐部页无守卫,401/403 只靠后端 toast)。
// 纯函数:喂 access/me 的响应,回 allow / deny / unknown。unknown = 响应坏了,不拦(后端仍然拦),别把网络问题栽成没权限。
const { normalizeMerchantAccess } = require('./merchant-access-policy.js');

function okCode(res) { return !!res && (res.code === 200 || res.code === '200'); }

// 两个 access/me 在没有身份时走 RuoYi 的 error("请先登录") —— HTTP 200 + code 500,
// 不认它就会把「压根没登录」判成「响应坏了」而放行(实测 ApiMerchantOperatorController:44 / ApiClubRoleController:49)。
function noPrincipal(res) { return !!res && typeof res.msg === 'string' && res.msg.indexOf('请先登录') >= 0; }
const LOGIN_EXPIRED = '登录已过期，请重新进入';

/**
 * need = normalizeMerchantAccess 出来的布尔字段名(canReadFinance / canManageCoop …),空 = 只要是商家成员。
 * ⚠️ 铁律:**守卫绝不能比后端更严**。填 need 之前必须去 Controller 核实那一页真正调用的接口要哪个权限;
 * 拿不准就留空 —— 留空最多是「没帮上忙」,填错会把后端本会放行的岗位锁在页外(2026-09-06 复审实测 4 处)。
 */
// deny 附 member:true = 在营商家成员只是岗位缺这项权限(去处「返回」);其余一律 false(去处「申请入驻」)。
// expired:true = 会话没身份,守卫按登录门处理。都只影响呈现,state 判定不变。
function decideMerchantGate(res, need) {
  if (!okCode(res)) {
    if (noPrincipal(res)) return { state: 'deny', reason: LOGIN_EXPIRED, member: false, expired: true };
    const code = res && Number(res.code);
    return (code === 401 || code === 403) ? { state: 'deny', reason: '当前身份不能查看商家内容', member: false } : { state: 'unknown' };
  }
  const access = normalizeMerchantAccess(res.data);
  if (!access.active) return { state: 'deny', reason: '当前身份不能查看商家内容', member: false };
  if (need && access[need] !== true) return { state: 'deny', reason: '当前身份不能查看这项内容', member: true };
  return { state: 'allow', access };
}

/** need = 后端 ClubPermission 的字符串(club:finance:read …),clubId 给了就核 club 范围 */
function decideClubGate(res, clubId, need) {
  if (!okCode(res) || !res.data || typeof res.data !== 'object') {
    if (noPrincipal(res)) return { state: 'deny', reason: LOGIN_EXPIRED };
    const code = res && Number(res.code);
    return (code === 401 || code === 403) ? { state: 'deny', reason: '当前身份不能进入该俱乐部' } : { state: 'unknown' };
  }
  const access = res.data;
  if (typeof access.active !== 'boolean') return { state: 'unknown' };   // 没表态 = 坏响应
  if (access.active !== true) return { state: 'deny', reason: '当前身份不能进入该俱乐部' };
  const club = access.club && typeof access.club === 'object' ? access.club : null;
  if (clubId && (!club || String(club.id) !== String(clubId))) return { state: 'deny', reason: '当前身份不能进入这个俱乐部' };
  const permissions = Array.isArray(access.permissions) ? access.permissions : [];
  if (need && permissions.indexOf(need) < 0) return { state: 'deny', reason: '当前身份不能查看这项内容' };
  return { state: 'allow', access };
}

module.exports = { decideMerchantGate, decideClubGate };
