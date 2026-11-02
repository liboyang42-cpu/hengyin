/* Boots the real pages/play/index in mock mode with a theme, and supplies what the backend/device would. */
(function () {
var MP = window.MPWEB;
var MEDIA = 'https://www.chengyinhub.com/profile/upload/demo/';
MP.mapRemote = function (u) { return u.indexOf(MEDIA) === 0 ? 'media/' + u.slice(MEDIA.length) : u; };

// ---- free-map stand-in: the real one wraps Tencent's native <map>, which a browser cannot host.
// Draws the same inputs (nodes / player / target / polyline) on a dark street canvas.
var FM = 'components/cy/free-map/index';
__T[FM + '.wxml'] = [{ g: 'canvas', i: 990001, p: { type: '2d', id: 'fm', class: 'fm' }, c: [] }];
__CSS[FM] = ':host{display:block;position:relative;overflow:hidden;background:#15171a}.fm{position:absolute;inset:0;width:100%;height:100%}';
MP.defs[FM] = { kind: 'component', def: {
  properties: { nodes: Array, player: Object, target: Object, center: Object, compact: Boolean, mapPolyline: Array, fit: Boolean, fitPadding: Array, scale: Number, interactive: Boolean, showControls: Boolean, showPhotoCallouts: Boolean, reducedMotion: Boolean, subkey: String, layerStyle: Number, accessibilityLabel: String, scene: String },
  observers: { 'nodes, player, target, mapPolyline': function () { this._draw && this._draw(); } },
  lifetimes: {
    ready: function () {
      var self = this;
      this.createSelectorQuery().select('#fm').fields({ node: true, size: true }).exec(function (r) {
        var it = r && r[0]; if (!it || !it.node) return;
        var cv = it.node, dpr = 2, W = it.width, H = it.height;
        cv.width = W * dpr; cv.height = H * dpr;
        var g = cv.getContext('2d');
        var seed = 7; function rnd() { seed = (seed * 16807) % 2147483647; return seed / 2147483647; }
        var streets = []; for (var i = 0; i < 16; i++) streets.push({ a: rnd() * Math.PI, o: rnd(), w: rnd() < 0.3 ? 2.4 : 1 });
        self._draw = function () {
          var d = self.data, pts = (d.nodes || []).filter(function (n) { return n.lat && n.lng; });
          var all = pts.slice(); if (d.player && d.player.lat) all.push(d.player);
          if (!all.length && d.center) all.push(d.center);
          var la = all.map(function (p) { return +p.lat; }), ln = all.map(function (p) { return +p.lng; });
          var cx = (Math.min.apply(0, ln) + Math.max.apply(0, ln)) / 2, cy = (Math.min.apply(0, la) + Math.max.apply(0, la)) / 2;
          var span = Math.max(Math.max.apply(0, ln) - Math.min.apply(0, ln), (Math.max.apply(0, la) - Math.min.apply(0, la)) * 1.15, 0.0018);
          var pad = d.compact ? 0.62 : 0.7; var k = Math.min(W, H) * pad / span;
          function P(p) { return [W / 2 + (p.lng - cx) * k, H / 2 - (p.lat - cy) * k * 1.15]; }
          g.setTransform(dpr, 0, 0, dpr, 0, 0);
          g.fillStyle = '#16181b'; g.fillRect(0, 0, W, H);
          streets.forEach(function (s) { g.save(); g.translate(W * s.o, H * (1 - s.o)); g.rotate(s.a); g.fillStyle = s.w > 2 ? '#2a2e34' : '#22252a'; g.fillRect(-W * 2, -s.w * 3, W * 4, s.w * 6); g.restore(); });
          g.fillStyle = '#1b2a22'; g.beginPath(); g.ellipse(W * 0.3, H * 0.35, W * 0.12, H * 0.08, 0.4, 0, 6.28); g.fill();
          var line = (d.mapPolyline && d.mapPolyline[0] && d.mapPolyline[0].points) || pts.map(function (p) { return { latitude: p.lat, longitude: p.lng }; });
          if (line.length > 1) { g.strokeStyle = 'rgba(90,144,214,.85)'; g.lineWidth = d.compact ? 2 : 4; g.setLineDash(d.compact ? [] : [8, 6]); g.beginPath(); line.forEach(function (q, i) { var xy = P({ lat: q.latitude, lng: q.longitude }); i ? g.lineTo(xy[0], xy[1]) : g.moveTo(xy[0], xy[1]); }); g.stroke(); g.setLineDash([]); }
          pts.forEach(function (p) {
            var xy = P(p), tgt = d.target && String(d.target.nodeId) === String(p.nodeId), done = p.state === 'done' || p.state === 'completed';
            var r = d.compact ? (tgt ? 6 : 4) : (tgt ? 15 : 12);
            g.fillStyle = done ? '#4b5563' : (tgt ? '#5A90D6' : '#e5e7eb'); g.beginPath(); g.arc(xy[0], xy[1], r, 0, 6.28); g.fill();
            if (!d.compact) { g.fillStyle = tgt ? '#fff' : '#111'; g.font = '700 12px -apple-system,sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(done ? '✓' : String(p.num || ''), xy[0], xy[1] + 0.5); }
          });
          if (d.player && d.player.lat) { var pp = P(d.player); g.fillStyle = 'rgba(74,222,128,.25)'; g.beginPath(); g.arc(pp[0], pp[1], d.compact ? 9 : 18, 0, 6.28); g.fill(); g.fillStyle = '#4ADE80'; g.beginPath(); g.arc(pp[0], pp[1], d.compact ? 3.5 : 6, 0, 6.28); g.fill(); g.strokeStyle = '#fff'; g.lineWidth = 2; g.stroke(); }
        };
        self._draw();
      });
    },
  },
  methods: {},
} };

// ---- device / backend stand-ins
var page = null, theme = null;
MP.theme = function () { return theme; };
MP.loc = null;
function nodeById(id) { return (theme.nodes || []).find(function (n) { return String(n.nodeId) === String(id); }); }
MP.scanTarget = function () {
  var d = page && page.data || {};
  var id = (d.game && d.screen === 'gamePlay' && d.game.nodeId) || (d.sheet && d.sheet.node && d.sheet.node.nodeId);
  var n = id != null ? nodeById(id) : null;
  return { code: (n && n.checkinCode) || ('CY-' + (id || 'NODE')), label: n ? n.name + ' · 门店码' : '门店码', photo: n ? MP.mapRemote(n.imgUrl) : '' };
};
MP.photoForShot = function () {
  var d = page && page.data || {}; var id = (d.game && d.game.nodeId) || (d.sheet && d.sheet.node && d.sheet.node.nodeId);
  var n = id != null ? nodeById(id) : null; return n ? n.imgUrl : MEDIA + 'ark-street.jpg';
};
// Location: the player "walks" to whichever station card is open.
var baseLocation = MP.location;
MP.location = function () {
  var d = page && page.data || {}; var n = d.sheet && d.sheet.node;
  if (n && n.lat) return { lat: n.lat + 0.00004, lng: n.lng - 0.00003 };
  var first = theme && theme.nodes.find(function (x) { return x.latitude; });
  return first ? { lat: first.latitude - 0.0004, lng: first.longitude - 0.0003 } : baseLocation();
};

MP.boot = function (kind, mount) {
  theme = window.CY_THEMES[kind];
  // What the backend receipt would carry on a successful node write (mock mode passes {}).
  var def = MP.defs['pages/play/index'] || (MP.require('pages/play/index.js'), MP.defs['pages/play/index']);
  if (!def.def.__wrapped) {
    var orig = def.def.onComplete;
    def.def.onComplete = function (nodeId, data) {
      if (data && Object.keys(data).length) return orig.call(this, nodeId, data);
      var nodes = this.data.nodes || [];
      var doneAfter = nodes.filter(function (n) { return n.done || String(n.nodeId) === String(nodeId); }).length;
      var n = nodes.find(function (x) { return String(x.nodeId) === String(nodeId); }) || {};
      var receipt = { nodeId: nodeId, firstTime: true, done: doneAfter, xp: n.points || 12, completedAt: new Date().toISOString(), completed: doneAfter >= nodes.length };
      if (n.medalName && doneAfter >= nodes.length) receipt.medalRank = 1;
      MP.emit && MP.emit('complete', { nodeId: nodeId, name: n.name, done: doneAfter, total: nodes.length });
      return orig.call(this, nodeId, receipt);
    };
    // 画面风格:?look=<json> 时把它挂到预览数据上,走玩家页真实的 _applyLook
    var origPrev = def.def.applyPreviewData;
    def.def.applyPreviewData = function (d) { if (MP.look) d = Object.assign({}, d, { look: MP.look }); return origPrev.call(this, d); };
    def.def.__wrapped = true;
  }
  try { var lq = new URLSearchParams(location.search).get('look'); MP.look = lq || null; } catch (e) { MP.look = null; }
  MP.resetServer && MP.resetServer();
  MP.watchKey = location.search.indexOf('watch=') >= 0 ? decodeURIComponent(location.search.split('watch=')[1]) : null;
  page = MP.openPage('pages/play/index', { mock: kind, topicId: String(theme.topicId), activityId: String(880000 + (kind === 'city' ? 69 : 201)) }, mount);
  MP.page = page;
  return page;
};
MP.shutdown = function () {
  if (page) MP.closePage(page);
  page = null;
  MP.audioEls.forEach(function (a) { try { a.pause(); } catch (e) {} });
  MP.audioEls.clear();
};
})();
