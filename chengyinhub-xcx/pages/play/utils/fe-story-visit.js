'use strict';
// 自由探索(mode 2)「只读已到访地点的章节」的端上投影与回包分类(纯函数,已单测)。
// 到访是否成立、章节正文给不给,全由服务端 /api/play/arrive · /api/play/checkin · /api/play/nodes 决定;
// 这里只把服务端字段翻成卡面文字,并把写请求的回包分成「成功 / 被拒 / 没发出去 / 结果未知」四类。
const { chinaParts } = require('../../../utils/datetime.js');
const { roundedMeters } = require('./arrival-radius.js');

const VISIT_CHECKS = ['GPS', 'SCAN', 'ONLINE'];

/** 服务端 visitCheck 归一:缺省按 GPS(默认到访方式) */
function normCheck(v) {
  const s = String(v || '').toUpperCase();
  return VISIT_CHECKS.indexOf(s) >= 0 ? s : 'GPS';
}

/** 锁卡主按钮 */
function visitButtonText(check) { return normCheck(check) === 'SCAN' ? '扫码解锁' : '我已到达'; }

/** 已解锁卡上的到访日期:「11月17日到访」;读不出时间就不写 */
function visitDateText(visitTime) {
  const p = visitTime ? chinaParts(visitTime) : null;
  return p ? p.month + '月' + p.day + '日到访' : '';
}

/** 已解锁卡的到访说明:队友到访解锁的写「队友已到访」(这时可能没有本人的到访时间),否则写日期 */
const TEAM_VISIT_TEXT = '队友已到访';
function visitedText(node) {
  if (!node) return '';
  return node.visitedByTeam === true ? TEAM_VISIT_TEXT : visitDateText(node.visitTime);
}

/** 票过期后的只读集章册:锁卡不再给到访按钮,只写这一句 */
const EXPIRED_TEXT = '已过期，不能再解锁';

/** 卡包进度:只写「已解锁 X / N 章」,不写百分比、不写还差几章 */
function progressText(unlocked, total) {
  return '已解锁 ' + (Number(unlocked) || 0) + ' / ' + (Number(total) || 0) + ' 章';
}

/** 碎片卡标题:N 是已到访数,与顺序无关 */
function fragmentTitle(visitedCount) { return '第 ' + Math.max(1, Number(visitedCount) || 0) + ' 个到访'; }

function isOk(r) { return !!r && (r.code === 200 || r.code === '200'); }

/** 写请求回包分类。unknown = 服务端可能已执行,只能回读 /api/play/nodes,绝不自动重发;
 *  notSent = 可证明没送达(intentNotExecuted),可以让玩家再点一次。 */
function outcome(r) {
  if (!r || r.obsolete) return 'obsolete';
  if (isOk(r)) return 'ok';
  if (r.intentNotExecuted) return 'notSent';
  if (r.netFail || r.httpFail || r.intentUnknown || Number(r.code) === 520) return 'unknown';
  return 'rejected';
}

/** 被拒原因。距离两处都读(data.distanceMeters / 顶层 distanceMeters),取整到 10 米 */
function rejection(r) {
  const code = String((r && r.errorCode) || '');
  const data = (r && r.data) || {};
  const raw = data.distanceMeters != null ? data.distanceMeters : (r ? r.distanceMeters : null);
  if (code === 'ARRIVE_TOO_FAR') {
    const meters = roundedMeters(raw);
    return { kind: 'tooFar', meters, title: meters ? '离这里还有约 ' + meters + ' 米' : '还没到这里', retry: '重新定位' };
  }
  if (code === 'ARRIVE_ACCURACY_LOW') return { kind: 'accuracy', title: '定位不准，再试一次', retry: '重新定位' };
  if (code === 'ARRIVE_LOCATION_MISSING') return { kind: 'missing', title: '没有取到定位', retry: '重新定位' };
  if (code === 'VISIT_SCAN_REQUIRED') return { kind: 'scan', title: '这里需要扫码', retry: '扫码解锁' };
  return { kind: 'other', title: (r && r.msg) || '暂时没有解锁', retry: '' };
}

/** 锁卡投影(地点格 / 大卡共用):只用服务端已下发的字段,锁定时不出章节正文 */
function tileView(node, distText) {
  const locked = node.storyLocked === true;
  return {
    storyLocked: locked,
    visitCheck: normCheck(node.visitCheck),
    visitBtn: visitButtonText(node.visitCheck),
    visitText: locked ? '' : visitedText(node),
    distText: locked ? (distText || '') : '',
    hookText: locked ? (node.hookText || '') : '',
  };
}

module.exports = { normCheck, visitButtonText, visitDateText, visitedText, TEAM_VISIT_TEXT, EXPIRED_TEXT, progressText, fragmentTitle, outcome, rejection, tileView };
