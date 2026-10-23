/* v13 路线小地图。points = 真实站点坐标(按顺序);tone = city | free。
   投影:经纬度按包围盒等比缩放进画布(留边);街道纹理是固定种子的示意线,不代表真实道路。 */
function rng(a) { return function () { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }

function project(points, w, h, pad) {
  const ok = (points || []).filter((p) => p && Number.isFinite(+p.lat) && Number.isFinite(+p.lng));
  if (!ok.length) return [];
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  const xy = ok.map((p) => { const x = +p.lng * Math.cos(+p.lat * Math.PI / 180), y = -(+p.lat); minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); return [x, y]; });
  const sw = Math.max(1e-9, maxX - minX), sh = Math.max(1e-9, maxY - minY);
  const k = Math.min((w - pad * 2) / sw, (h - pad * 2) / sh);
  const ox = (w - sw * k) / 2, oy = (h - sh * k) / 2;
  return xy.map(([x, y]) => [ox + (x - minX) * k, oy + (y - minY) * k]);
}

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    points: { type: Array, value: [] },
    tone: { type: String, value: 'city' },
    reducedMotion: { type: Boolean, value: false },
  },
  observers: { 'points, tone'() { this._draw(); } },
  lifetimes: { ready() { this._draw(); }, detached() { this._dead = true; } },
  methods: {
    onTap() { this.triggerEvent('open'); },
    _draw() {
      if (this._dead || typeof wx === 'undefined' || typeof wx.createSelectorQuery !== 'function') return;
      try {
        this.createSelectorQuery().select('#rscv').fields({ node: true, size: true }).exec((r) => {
          const n = r && r[0];
          if (!n || !n.node || this._dead) return;
          const cv = n.node, w = n.width, h = n.height, d = 2;
          cv.width = w * d; cv.height = h * d;
          const g = cv.getContext('2d');
          const pts = project(this.data.points, w, h, 26);
          const line = this.data.tone !== 'free';
          const col = line ? '#FF8A3D' : '#7CF0A0'; // ds-ok v13 路线色
          const R = rng(20261022);
          const roads = [];
          for (let i = 0; i < 9; i++) { const a = (R() - 0.5) * 0.9 + (i % 2 ? Math.PI / 2 : 0); const cx = R() * w, cy = R() * h; roads.push([cx - Math.cos(a) * w, cy - Math.sin(a) * w, cx + Math.cos(a) * w, cy + Math.sin(a) * w, i % 3 === 0 ? 7 : 3]); }
          const base = () => {
            g.setTransform(d, 0, 0, d, 0, 0);
            g.fillStyle = '#15171b'; g.fillRect(0, 0, w, h); // ds-ok v13 地图底
            roads.forEach((rd) => { g.strokeStyle = rd[4] > 5 ? '#262a31' : '#1f232a'; g.lineWidth = rd[4]; g.beginPath(); g.moveTo(rd[0], rd[1]); g.lineTo(rd[2], rd[3]); g.stroke(); }); // ds-ok 示意街道
            if (!line && pts.length) {
              const cx = pts.reduce((s, p) => s + p[0], 0) / pts.length, cy = pts.reduce((s, p) => s + p[1], 0) / pts.length;
              const rx = Math.max(60, ...pts.map((p) => Math.abs(p[0] - cx))) + 22, ry = Math.max(46, ...pts.map((p) => Math.abs(p[1] - cy))) + 22;
              g.setLineDash([6, 6]); g.strokeStyle = 'rgba(124,240,160,.5)'; g.lineWidth = 2; g.fillStyle = 'rgba(124,240,160,.06)'; // ds-ok
              g.beginPath(); g.ellipse(cx, cy, rx, ry, -0.3, 0, Math.PI * 2); g.fill(); g.stroke(); g.setLineDash([]);
            }
          };
          const seg = []; let L = 0;
          for (let i = 1; i < pts.length; i++) { const l = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); seg.push(l); L += l; }
          const frame = (e) => {
            base();
            if (pts.length > 1) {
              let left = L * e;
              g.setLineDash(line ? [] : [8, 7]); g.strokeStyle = col; g.lineWidth = 4; g.lineCap = 'round'; g.lineJoin = 'round';
              g.beginPath(); g.moveTo(pts[0][0], pts[0][1]);
              for (let i = 1; i < pts.length && left > 0; i++) { const f = Math.min(1, left / seg[i - 1]); g.lineTo(pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * f, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f); left -= seg[i - 1]; }
              g.stroke(); g.setLineDash([]);
            }
            pts.forEach((p, i) => {
              g.fillStyle = line ? col : '#fff'; g.beginPath(); g.arc(p[0], p[1], 10, 0, Math.PI * 2); g.fill(); // ds-ok
              if (line) { g.fillStyle = '#000'; g.font = '800 11px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(String(i + 1), p[0], p[1] + 0.5); } // ds-ok
              else { g.fillStyle = '#2f4a35'; g.beginPath(); g.arc(p[0], p[1], 6, 0, Math.PI * 2); g.fill(); } // ds-ok
            });
          };
          if (this.data.reducedMotion || typeof cv.requestAnimationFrame !== 'function') { frame(1); return; }
          const t0 = Date.now(), dur = 1300;
          const step = () => { if (this._dead) return; const k = Math.min(1, (Date.now() - t0) / dur); frame(1 - Math.pow(1 - k, 3)); if (k < 1) cv.requestAnimationFrame(step); };
          step();
        });
      } catch (e) { /* 画不出就留深色底 */ }
    },
  },
});
