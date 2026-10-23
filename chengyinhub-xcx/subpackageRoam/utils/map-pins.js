'use strict';
/* v13 地图三类点位的样子(业主 2026-10-22「和 HTML 一模一样」):
 *   主题队伍 = 蓝色胶囊(最多三个头像 + 人数),上方蓝色「组队」小牌;
 *   地点玩法 = 圆形照片 + 白圈,上方白色斜贴小牌(地点类型 / 活动名);
 *   商家     = 圆角方形照片 + 橙框 + 右下礼物角标,上方橙色小牌(商家类型)。
 * 原生 map 的 marker 只能是图片,所以在离屏 canvas 上按原型尺寸画成一张图,anchor 落在点位本体中心。
 * 纯函数(画法 + 点位规格),便于单测;取图、存图由页面做。 */

const SCALE = 3;
const BOX = {
  spot: { w: 100, h: 86, cx: 50, cy: 58 },
  shop: { w: 100, h: 86, cx: 50, cy: 58 },
  team: { w: 96, h: 64, cx: 48, cy: 44 },
};
const COLOR = { team: '#0A84FF', shop: '#FF9F0A', ring: '#FFFFFF', tagText: '#111111', ph: '#333333' }; // ds-ok v13 点位色
const AV_BG = ['#FF6B6B', '#5AC8FA', '#FFD60A', '#34C759', '#BF5AF2']; // ds-ok 头像兜底底色
const TEAM_TAG = 4; // 与 map-team TEAM_MARKER_TAG 一致(队伍 marker id = teamId*10+4)

function text(v) {
  if (v == null) return '';
  const s = String(v).trim();
  return s === 'null' ? '' : s;
}
function firstTag(v) { return text(v).split(/[,，、;；|\s]+/).map((s) => s.trim()).filter(Boolean)[0] || ''; }
function short(s, n) { const t = text(s); return t.length > n ? t.slice(0, n) : t; }
function csv(v) { return text(v).split(',').map((s) => s.trim()).filter(Boolean); }

/* 每个点位要画成什么样:id 与页面现有 marker id 同一套(主题 *10+3 / 活动 *10+2 / 地标 *10+6 / 商家 *10+7 / 队伍 *10+4) */
function pinSpecs(input) {
  const o = input || {};
  const out = [];
  (o.items || []).forEach((it) => {
    if (!it || it.kind === 'hangout' || it.id == null) return;
    const topic = it.kind === 'topic';
    out.push({ id: Number(it.id) * 10 + (topic ? 3 : 2), kind: 'spot', img: text(it.picUrl),
      tag: topic ? (Number(it.productType) === 2 ? '自由探索' : '城市定向') : short(it.name, 6) });
  });
  (o.pois || []).forEach((p) => {
    if (!p || p.id == null) return;
    const shop = Number(p.type) === 2;
    out.push({ id: Number(p.id) * 10 + (shop ? 7 : 6), kind: shop ? 'shop' : 'spot', img: text(p.coverImg),
      tag: short(firstTag(p.tags) || (shop ? '商家' : '地点'), 6) });
  });
  (o.teams || []).forEach((t) => {
    if (!t || !t.teamId) return;
    const faces = csv(t.memberAvatarsJoined).slice(0, 3);
    if (!faces.length && text(t.leaderAvatar)) faces.push(text(t.leaderAvatar));
    const joined = Math.max(0, Number(t.joinedCount) || 0), cap = Math.max(0, Number(t.maxMembers) || 0);
    const letters = csv(t.memberNamesJoined).map((n) => n.slice(0, 1)).slice(0, 3);
    out.push({ id: Number(t.teamId) * 10 + (o.teamTag || TEAM_TAG), kind: 'team', faces, letters: letters.length ? letters : [text(t.leaderName).slice(0, 1) || '队'],
      count: cap ? joined + '/' + cap : String(joined), tag: '组队' });
  });
  return out;
}

function pinKey(spec) { return [spec.kind, spec.id, spec.img || '', (spec.faces || []).join('|'), spec.count || '', spec.tag || ''].join('#'); }

function roundRect(g, x, y, w, h, r) {
  g.beginPath();
  g.moveTo(x + r, y); g.lineTo(x + w - r, y); g.arcTo(x + w, y, x + w, y + r, r);
  g.lineTo(x + w, y + h - r); g.arcTo(x + w, y + h, x + w - r, y + h, r);
  g.lineTo(x + r, y + h); g.arcTo(x, y + h, x, y + h - r, r);
  g.lineTo(x, y + r); g.arcTo(x, y, x + r, y, r); g.closePath();
}

/* 照片铺满(aspectFill)画进当前裁剪区 */
function cover(g, img, x, y, w, h) {
  if (!img || !img.width) { g.fillStyle = COLOR.ph; g.fillRect(x, y, w, h); return; }
  const k = Math.max(w / img.width, h / img.height), iw = img.width * k, ih = img.height * k;
  g.drawImage(img, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih);
}

function shadow(g, on) {
  g.shadowColor = on ? 'rgba(0,0,0,0.55)' : 'transparent';
  g.shadowBlur = on ? 14 : 0; g.shadowOffsetY = on ? 6 : 0; g.shadowOffsetX = 0;
}

/* 上方小牌:圆角 8,12px 粗体,逆时针歪 9°,水平偏左(translateX(-60%)) */
function drawTag(g, label, cx, bottom, bg, fg, tilt) {
  if (!label) return;
  g.save();
  g.font = '800 12px sans-serif';
  const w = Math.ceil(g.measureText(label).width) + 16, h = 20;
  g.translate(cx, bottom - h / 2);
  if (tilt) g.rotate(-9 * Math.PI / 180);
  const x = -w * (tilt ? 0.6 : 0.5), y = -h / 2;
  shadow(g, true); g.shadowBlur = 8; g.shadowOffsetY = 3; g.shadowColor = 'rgba(0,0,0,0.45)';
  roundRect(g, x, y, w, h, 8); g.fillStyle = bg; g.fill();
  shadow(g, false);
  g.fillStyle = fg; g.textBaseline = 'middle'; g.textAlign = 'left';
  g.fillText(label, x + 8, 0.5);
  g.restore();
}

function drawGift(g, x, y) {
  g.save();
  g.beginPath(); g.arc(x, y, 11, 0, Math.PI * 2); g.fillStyle = COLOR.shop; g.fill();
  g.lineWidth = 2; g.strokeStyle = COLOR.ring; g.stroke();
  g.strokeStyle = '#000'; g.lineWidth = 1.6; // ds-ok 礼物图标黑线
  g.strokeRect(x - 5, y - 2, 10, 7); g.beginPath(); g.moveTo(x - 6, y - 2); g.lineTo(x + 6, y - 2); g.moveTo(x, y - 2); g.lineTo(x, y + 5); g.stroke();
  g.beginPath(); g.moveTo(x, y - 2); g.quadraticCurveTo(x - 4, y - 7, x - 3, y - 3); g.moveTo(x, y - 2); g.quadraticCurveTo(x + 4, y - 7, x + 3, y - 3); g.stroke();
  g.restore();
}

/* 画一枚点位。imgs:spot/shop 是一张照片;team 是头像数组(取不到的位置画首字) */
function drawPin(g, spec, imgs) {
  const b = BOX[spec.kind];
  g.save();
  g.clearRect(0, 0, b.w * SCALE, b.h * SCALE);
  g.scale(SCALE, SCALE);
  if (spec.kind === 'team') {
    const w = 78, h = 34, x = b.cx - w / 2, y = b.cy - h / 2;
    shadow(g, true); roundRect(g, x - 2.5, y - 2.5, w + 5, h + 5, 19.5); g.fillStyle = COLOR.ring; g.fill(); shadow(g, false);
    roundRect(g, x, y, w, h, 17); g.fillStyle = COLOR.team; g.fill();
    const n = Math.max(1, Math.min(3, (spec.faces || []).length || (spec.letters || []).length));
    for (let i = 0; i < n; i++) {
      const ax = x + 4 + 12 + i * 16, ay = b.cy;
      g.save(); g.beginPath(); g.arc(ax, ay, 12, 0, Math.PI * 2); g.fillStyle = COLOR.team; g.fill();
      g.beginPath(); g.arc(ax, ay, 10, 0, Math.PI * 2); g.clip();
      const im = imgs && imgs[i];
      if (im && im.width) cover(g, im, ax - 10, ay - 10, 20, 20);
      else {
        g.fillStyle = AV_BG[i % AV_BG.length]; g.fillRect(ax - 10, ay - 10, 20, 20);
        g.fillStyle = '#fff'; g.font = '700 11px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; // ds-ok
        g.fillText(((spec.letters || [])[i] || '队').slice(0, 1), ax, ay + 0.5);
      }
      g.restore();
    }
    g.fillStyle = '#fff'; g.font = '700 12px sans-serif'; g.textAlign = 'right'; g.textBaseline = 'middle'; // ds-ok
    g.fillText(spec.count || '', x + w - 8, b.cy + 0.5);
    drawTag(g, spec.tag, b.cx, y - 3, COLOR.team, '#fff', false); // ds-ok
  } else {
    const s = 48, x = b.cx - s / 2, y = b.cy - s / 2, shop = spec.kind === 'shop';
    shadow(g, true);
    if (shop) { roundRect(g, x - 3, y - 3, s + 6, s + 6, 16); g.fillStyle = COLOR.shop; g.fill(); }
    else { g.beginPath(); g.arc(b.cx, b.cy, s / 2 + 3, 0, Math.PI * 2); g.fillStyle = COLOR.ring; g.fill(); }
    shadow(g, false);
    g.save();
    if (shop) roundRect(g, x, y, s, s, 13); else { g.beginPath(); g.arc(b.cx, b.cy, s / 2, 0, Math.PI * 2); }
    g.clip(); cover(g, imgs && imgs[0], x, y, s, s); g.restore();
    if (shop) drawGift(g, x + s + 7 - 11, y + s + 7 - 11);
    drawTag(g, spec.tag, b.cx, y - 15 + 20, shop ? COLOR.shop : COLOR.ring, shop ? '#000' : COLOR.tagText, true); // ds-ok
  }
  g.restore();
}

/* 有图就换上点位图(大小按原型逻辑尺寸,anchor 落在本体中心),去掉旧名牌 */
function applyPins(markers, pins) {
  return (markers || []).map((m) => {
    const p = m && pins && pins[m.id];
    if (!p) return m;
    const b = BOX[p.kind];
    const out = Object.assign({}, m, { iconPath: p.icon, width: b.w, height: b.h, anchor: { x: b.cx / b.w, y: b.cy / b.h }, zIndex: p.kind === 'team' ? 12 : p.kind === 'shop' ? 8 : 6 });
    delete out.callout;
    return out;
  });
}

module.exports = { SCALE, BOX, pinSpecs, pinKey, drawPin, applyPins };
