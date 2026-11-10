'use strict';

/* r2-finish 结算分享卡:只画服务端事实(主题名、结果、节点、分数、名次);没有的行不画。
 * 帖文 4:5、故事 9:16。纯函数 cardLines 可单测;draw 在 2d canvas 上画一帧(t=0..1 时为动画帧,供视频录制)。 */

function cardLines(report, topicName) {
  const r = report || {};
  const lines = [];
  if (r.nodesText && r.nodesText !== '未知') lines.push('节点 ' + r.nodesText);
  if (r.scored) lines.push(r.scoreText + ' 分');
  if (r.rankSub) lines.push(r.rankText);
  return {
    kicker: r.win ? '已通关' : (r.result && r.result !== '未知' ? r.result : ''),
    title: String(topicName || '').slice(0, 24),
    meta: lines.join(' · ')
  };
}

const SIZES = { post: { w: 360, h: 450 }, story: { w: 270, h: 480 } };

function wrap(ctx, text, maxW) {
  const out = [];
  let line = '';
  Array.from(text || '').forEach((ch) => {
    if (ctx.measureText(line + ch).width > maxW && line) { out.push(line); line = ch; } else line += ch;
  });
  if (line) out.push(line);
  return out.slice(0, 3);
}

// t: 0..1 动画进度(静态图传 1)。颜色是分享图自带的画面色,不随主题。
function draw(ctx, w, h, card, t) {
  const p = t == null ? 1 : Math.max(0, Math.min(1, t));
  const ease = 1 - Math.pow(1 - p, 3);
  const g = ctx.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, '#1F1F21'); g.addColorStop(1, '#0A0A0B'); /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
  // 路线感的背景细线
  ctx.strokeStyle = 'rgba(233,196,124,.18)'; ctx.lineWidth = 2; /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.beginPath(); ctx.moveTo(w * 0.12, h * 0.62); ctx.bezierCurveTo(w * 0.35, h * 0.2, w * 0.6, h * 0.75, w * 0.9, h * 0.3); ctx.stroke();
  // 金色印章
  const cx = w / 2, cy = h * 0.3, R = Math.min(w, h) * 0.12 * (0.4 + 0.6 * ease);
  ctx.globalAlpha = ease;
  ctx.fillStyle = '#E9C47C'; ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.fill(); /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.strokeStyle = '#111111'; ctx.lineWidth = R * 0.16; ctx.lineCap = 'round'; /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.beginPath(); ctx.moveTo(cx - R * 0.4, cy); ctx.lineTo(cx - R * 0.08, cy + R * 0.32); ctx.lineTo(cx + R * 0.45, cy - R * 0.3); ctx.stroke();
  if (p < 1) { ctx.strokeStyle = 'rgba(233,196,124,' + (0.6 * (1 - p)).toFixed(2) + ')'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(cx, cy, R * (1 + p * 1.6), 0, Math.PI * 2); ctx.stroke(); } /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.globalAlpha = 1;
  // 文字
  const rise = (1 - ease) * 16;
  ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
  let y = h * 0.52 + rise;
  if (card.kicker) { ctx.fillStyle = '#E9C47C'; ctx.font = '600 14px sans-serif'; ctx.fillText(card.kicker, cx, y); y += 34; } /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.fillStyle = '#F5F5F2'; ctx.font = '700 24px sans-serif'; /* ds-ok 分享图画面色,导出图片不随主题 */
  wrap(ctx, card.title, w - 48).forEach((ln) => { ctx.fillText(ln, cx, y); y += 32; });
  if (card.meta) { ctx.fillStyle = 'rgba(245,245,242,.72)'; ctx.font = '14px sans-serif'; ctx.fillText(card.meta, cx, y + 6); } /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.fillStyle = 'rgba(245,245,242,.5)'; ctx.font = '600 13px sans-serif'; ctx.fillText('城瘾', cx, h - 24); /* ds-ok 分享图画面色,导出图片不随主题 */
}

module.exports = { cardLines, draw, SIZES };
