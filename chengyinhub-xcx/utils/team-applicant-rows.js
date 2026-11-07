'use strict';
// 队伍申请人行:地图队伍半屏(subpackageRoam/nearby)与队伍详情页共用同一份形状(第十一轮 g2,j4 F4)。
const { toTimestamp } = require('./datetime.js');

/**
 * 申请失效倒计时,以服务端 applyExpireTime 为准(语义 = min(申请+24h, 场次开始),
 * 见 migration_20260915_play_team_public_apply.sql)。拿不到或已过期 → ''(由调用方兜底)。
 */
function expireLeft(v, now) {
  const at = toTimestamp(v);
  if (isNaN(at)) return '';
  const diff = at - (now == null ? Date.now() : now);
  if (diff <= 0) return '';
  const min = Math.floor(diff / 60000);
  if (min < 1) return '1 分钟';
  if (min < 60) return min + ' 分钟';
  return Math.floor(min / 60) + ' 小时';
}

function parseAt(v) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/.exec(String(v || ''));
  return m ? new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)).getTime() : null;
}

function ago(v, now) {
  const at = parseAt(v);
  if (at == null) return '';
  const min = Math.max(0, Math.floor(((now == null ? Date.now() : now) - at) / 60000));
  if (min < 1) return '刚刚申请';
  if (min < 60) return min + ' 分钟前申请';
  if (min < 1440) return Math.floor(min / 60) + ' 小时前申请';
  return Math.floor(min / 1440) + ' 天前申请';
}

/** P5 申请人行。服务端只回未过期待审,且申请时已验票。 */
function applicantRows(list, now) {
  return (Array.isArray(list) ? list : []).filter((a) => a && a.memberId).map((a) => {
    const left = expireLeft(a.applyExpireTime, now);
    // 申请留言(2026-09-17 拍板第19条):两端接通后队长审批时能看到申请人说的一句话。
    // 服务端已做 200 字上限与内容安全机审;这里只做展示形状(引号),空串/null 不渲染。
    const message = String(a.applyMessage == null ? '' : a.applyMessage).trim();
    return {
      memberId: a.memberId, name: a.memberName || '玩家', avatar: a.memberAvatar || '',
      sub: ['持本场票', ago(a.appliedAt, now), left ? left + '后失效' : ''].filter(Boolean).join(' · '),
      messageText: message ? '“' + message + '”' : '',
    };
  });
}

module.exports = { expireLeft, applicantRows };
