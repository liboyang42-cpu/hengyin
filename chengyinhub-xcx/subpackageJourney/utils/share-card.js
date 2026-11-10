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

// r3-share(refs4/share-page-cards):与通用分享层同一版式 —— 整色卡,上半是路线插画 + 金色印章,
// 下沿一道弧,城瘾徽标压在弧上,眉题胶囊、两行大标题、一行成绩;底部字标。
// t: 0..1 动画进度(静态图传 1):印章 → 徽标 → 文字依次弹入 / 上浮。logo 为已加载的徽标图(可缺)。颜色是分享图自带的画面色,不随主题。
const CARD_BG = '#7A2619'; /* ds-ok 分享图画面色(与通用分享层主题卡第一色块同),导出图片不随主题 */

function rrect(ctx, x, y, w, h, r) {
  ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}

function clamp01(v) { return Math.max(0, Math.min(1, v)); }

// 两行折行,第二行放不下加省略号
function twoLines(ctx, text, maxW) {
  const rows = [''];
  let more = false;
  Array.from(text || '').some((ch) => {
    const i = rows.length - 1;
    if (rows[i] && ctx.measureText(rows[i] + ch).width > maxW) { if (i === 1) { more = true; return true; } rows.push(ch); } else rows[i] += ch;
    return false;
  });
  if (more) { let t = rows[1]; while (t.length > 1 && ctx.measureText(t + '…').width > maxW) t = t.slice(0, -1); rows[1] = t + '…'; }
  return rows.filter(Boolean);
}

function draw(ctx, w, h, card, t, logo) {
  const p = t == null ? 1 : clamp01(t);
  const ease = (x) => 1 - Math.pow(1 - clamp01(x), 3);
  const pop = (x) => { const k = clamp01(x); return k < 1 ? 1 - Math.pow(1 - k, 3) * Math.cos(k * 4) : 1; };
  const sealP = ease(p / 0.5); const badgeP = pop((p - 0.25) / 0.45); const textP = ease((p - 0.4) / 0.6);
  const cx = w / 2;
  const artH = h * 0.5;
  ctx.globalAlpha = 1;
  ctx.fillStyle = CARD_BG; ctx.fillRect(0, 0, w, h);
  // 上半:压暗一档 + 路线虚线 + 金色印章
  ctx.fillStyle = 'rgba(0,0,0,.22)'; ctx.fillRect(0, 0, w, artH); /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,.85)'; ctx.lineWidth = 3; ctx.lineCap = 'round'; ctx.setLineDash([1, 9]); /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.beginPath(); ctx.moveTo(w * 0.1, artH * 0.82); ctx.bezierCurveTo(w * 0.3, artH * 0.35, w * 0.45, artH * 0.9, w * 0.62, artH * 0.5); ctx.bezierCurveTo(w * 0.75, artH * 0.2, w * 0.85, artH * 0.35, w * 0.9, artH * 0.16); ctx.stroke();
  ctx.restore();
  ctx.fillStyle = '#FFFFFF'; ctx.beginPath(); ctx.arc(w * 0.1, artH * 0.82, 5, 0, Math.PI * 2); ctx.arc(w * 0.9, artH * 0.16, 5, 0, Math.PI * 2); ctx.fill(); /* ds-ok 分享图画面色,导出图片不随主题 */
  const sy = artH * 0.44; const R = Math.min(w, h) * 0.12 * (0.4 + 0.6 * sealP);
  ctx.globalAlpha = sealP;
  ctx.fillStyle = '#E9C47C'; ctx.beginPath(); ctx.arc(cx, sy, R, 0, Math.PI * 2); ctx.fill(); /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.strokeStyle = CARD_BG; ctx.lineWidth = R * 0.16; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  ctx.beginPath(); ctx.moveTo(cx - R * 0.4, sy); ctx.lineTo(cx - R * 0.08, sy + R * 0.32); ctx.lineTo(cx + R * 0.45, sy - R * 0.3); ctx.stroke();
  if (p < 1) { ctx.strokeStyle = 'rgba(233,196,124,' + (0.6 * (1 - p)).toFixed(2) + ')'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(cx, sy, R * (1 + p * 1.6), 0, Math.PI * 2); ctx.stroke(); } /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.globalAlpha = 1;
  // 弧:中间高、两边落到上半下沿(与通用分享层同一比例)
  const lift = artH * 0.129; const rx = w * 0.8; const ry = lift / 0.2194; const ey = artH - lift + ry;
  ctx.fillStyle = CARD_BG; ctx.beginPath(); ctx.ellipse(cx, ey, rx, ry, 0, 0, Math.PI * 2); ctx.fill(); ctx.fillRect(0, ey, w, h - ey);
  // 徽标压在弧顶
  const bs = w * 0.187 * (0.4 + 0.6 * badgeP); const by = artH - lift;
  ctx.globalAlpha = clamp01(badgeP * 1.4);
  ctx.save(); ctx.shadowColor = 'rgba(0,0,0,.35)'; ctx.shadowBlur = 12; ctx.shadowOffsetY = 5; /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.fillStyle = '#FFFFFF'; rrect(ctx, cx - bs / 2, by - bs / 2, bs, bs, bs * 0.27); ctx.fill(); ctx.restore(); /* ds-ok 分享图画面色,导出图片不随主题 */
  if (logo) ctx.drawImage(logo, cx - bs * 0.36, by - bs * 0.36, bs * 0.72, bs * 0.72);
  ctx.globalAlpha = 1;
  // 文字:眉题胶囊 → 两行标题 → 成绩
  const base = w * 0.187;
  const rise = (1 - textP) * 14;
  ctx.globalAlpha = textP;
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  let y = by + base / 2 + w * 0.06 + rise;
  if (card.kicker) {
    ctx.font = '600 ' + Math.round(w * 0.04) + 'px sans-serif';
    const cw = ctx.measureText(card.kicker).width + w * 0.07; const ch = w * 0.077;
    ctx.fillStyle = 'rgba(255,255,255,.16)'; rrect(ctx, cx - cw / 2, y, cw, ch, ch / 2); ctx.fill(); /* ds-ok 分享图画面色,导出图片不随主题 */
    ctx.fillStyle = 'rgba(255,255,255,.86)'; ctx.fillText(card.kicker, cx, y + ch / 2); /* ds-ok 分享图画面色,导出图片不随主题 */
    y += ch + w * 0.045;
  }
  const fs = Math.round(w * 0.085);
  ctx.fillStyle = '#FFFFFF'; ctx.font = '900 ' + fs + 'px sans-serif'; /* ds-ok 分享图画面色,导出图片不随主题 */
  twoLines(ctx, card.title, w * 0.84).forEach((ln) => { ctx.fillText(ln, cx, y + fs * 0.6); y += fs * 1.22; });
  if (card.meta) { ctx.fillStyle = 'rgba(255,255,255,.78)'; ctx.font = Math.round(w * 0.042) + 'px sans-serif'; ctx.fillText(card.meta, cx, y + w * 0.04); } /* ds-ok 分享图画面色,导出图片不随主题 */
  ctx.globalAlpha = 1;
  ctx.textBaseline = 'alphabetic';
  ctx.fillStyle = '#FFFFFF'; ctx.font = '800 ' + Math.round(w * 0.05) + 'px sans-serif'; ctx.fillText('城瘾', cx, h - w * 0.06); /* ds-ok 分享图画面色,导出图片不随主题 */
}

module.exports = { cardLines, draw, SIZES, LOGO: '/images/cy_logo_mark.png' };
