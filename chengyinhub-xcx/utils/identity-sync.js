'use strict';
// G-5(owner 2026-10-27 真机走查 m683/m687/m695):头像、名称只有一个来源,改完全局同步。
//
// 口径(单一真源):
//   · 商家视角:名称 = 商家名(mms_merchant.name),头像 = 商家 Logo,主页背景 = 店铺装修里的封面(coverImage);
//   · 玩家视角:名称 = 会员昵称,头像 = 会员头像。
//   两套不混用 —— 以前「我的」页在商家视角下仍显示会员昵称,首页显示商家名,同一家店两个名字。
//
// 写入方(品牌中心换 Logo/封面/店名、个人资料改头像/昵称)保存成功后调 publishIdentity:
//   ① 玩家头像/昵称写回会话快照(app.setAvatar / setNickname);
//   ② epoch +1,写进 app.globalData.identityEpoch,并同步通知已挂载的监听方;
//   ③ 没挂监听的页面(首页、我的、主页、店铺参谋、消息)在 onShow 比对 identityEpoch(),
//      变了就丢掉本页缓存的身份字段并重拉 —— 这一步就是「清缓存」。
// 只在内存里广播,不落库、不发请求;真值永远以接口回读为准,广播里的值只用来先把旧图换掉。

const FIELDS = {
  merchant: ['name', 'logo', 'coverImage'],
  member: ['nickname', 'avatar'],
};

let epoch = 0;
let latest = { merchant: null, member: null };
const listeners = [];

function safeApp() {
  try { return typeof getApp === 'function' ? getApp() : null; } catch (e) { return null; }
}

function pickFields(scope, patch) {
  const out = {};
  (FIELDS[scope] || []).forEach(function (key) {
    const value = patch && patch[key];
    if (typeof value === 'string' && value.trim()) out[key] = value.trim();
  });
  return out;
}

/**
 * 保存成功后广播一次身份变化。scope: 'merchant' | 'member'。
 * 只认白名单字段且必须是非空串:空串不广播,免得把别处正在显示的头像清成空圈。
 * 返回新的 epoch;没有可广播的字段时原样返回当前 epoch。
 */
function publishIdentity(scope, patch) {
  const kind = scope === 'merchant' ? 'merchant' : 'member';
  const clean = pickFields(kind, patch);
  if (!Object.keys(clean).length) return epoch;
  latest[kind] = Object.assign({}, latest[kind] || {}, clean);
  epoch += 1;
  const app = safeApp();
  if (app) {
    if (kind === 'member') {
      if (clean.avatar && typeof app.setAvatar === 'function') app.setAvatar(clean.avatar);
      if (clean.nickname && typeof app.setNickname === 'function') app.setNickname(clean.nickname);
    }
    if (app.globalData) app.globalData.identityEpoch = epoch;
  }
  const event = { epoch: epoch, scope: kind, patch: Object.assign({}, clean) };
  listeners.slice().forEach(function (fn) {
    try { fn(event); } catch (e) { /* 一个监听方出错不影响其他页面刷新 */ }
  });
  return epoch;
}

/** 订阅身份变化;返回取消订阅函数(组件 detached / 页面 onUnload 里调)。 */
function onIdentityChange(fn) {
  if (typeof fn !== 'function') return function () {};
  listeners.push(fn);
  return function off() {
    const at = listeners.indexOf(fn);
    if (at >= 0) listeners.splice(at, 1);
  };
}

function identityEpoch() { return epoch; }

/** 最近一次广播的字段(没有则 null)。只作回读到达前的过渡值。 */
function latestIdentity(scope) {
  const value = latest[scope === 'merchant' ? 'merchant' : 'member'];
  return value ? Object.assign({}, value) : null;
}

/**
 * 本人身份头:按视角取唯一来源。merchant = { name, logo },member = { nickname, avatar }。
 * 商家视角没 Logo 时头像为空串(由调用方落店名首字),不借会员头像;玩家没头像才用默认图。
 */
function resolveSelfIdentity(merchantView, merchant, member) {
  const m = merchant || {};
  const u = member || {};
  if (merchantView) {
    const name = String(m.name || '').trim();
    return { name: name || '商家', avatar: String(m.logo || '').trim(), initial: name ? name.charAt(0) : '店' };
  }
  return { name: String(u.nickname || '').trim() || '探索者', avatar: u.avatar || '/images/d_profile.png', initial: '' };
}

// V-15(2026-10-29 真机走查 v15):换完头像后资料页只剩一个黑圈。
// 后端回的头像是 OSS 签名 http:// 链接或相对路径,页面直接塞给 <image>,真机拒载。
// 显示前统一过 app.getImgUrl(http→https、相对路径补域名);
// 刚选好、还在本机的临时文件(wxfile:// / 开发者工具的 http://tmp/)原样显示,不能被补域名或改成 https。

const LOCAL_FILE = /^(wxfile|file):\/\//i;
const DEVTOOLS_TEMP = /^https?:\/\/(tmp|usr|store)\//i;

function isLocalFile(path) {
  const p = String(path || '');
  return LOCAL_FILE.test(p) || DEVTOOLS_TEMP.test(p);
}

/** 头像 → 可以直接给 <image src> 的地址;读不出来返回空串,由调用方落默认头像。 */
function avatarSrc(raw, getImgUrl) {
  const value = typeof raw === 'string' ? raw.trim() : '';
  if (!value) return '';
  if (isLocalFile(value) || typeof getImgUrl !== 'function') return value;
  return getImgUrl(value) || '';
}

function _resetForTest() {
  epoch = 0;
  latest = { merchant: null, member: null };
  listeners.length = 0;
}

module.exports = {
  publishIdentity: publishIdentity,
  onIdentityChange: onIdentityChange,
  identityEpoch: identityEpoch,
  latestIdentity: latestIdentity,
  resolveSelfIdentity: resolveSelfIdentity,
  isLocalFile: isLocalFile,
  avatarSrc: avatarSrc,
  _resetForTest: _resetForTest,
};
