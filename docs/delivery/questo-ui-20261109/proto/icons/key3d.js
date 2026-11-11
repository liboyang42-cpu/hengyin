// 通行证 · 钥匙开场(逐镜对照 xref/satya2.mp4 0–9.3s,时间轴见下方 TL):
// 0.0 黑场 + 一粒四芒星闪  → 0.5 金色碎片从四面八方翻滚飞入  → 1.1 钥匙的四个部件(章头 / 吊环 / 杆 / 齿)也在碎片里飞  → 2.4 合体成一把金钥匙
// → 2.6 碎片穿过钥匙消散,一枚发光小方块掉到右下角  → 3.0 钥匙身后一团金尘炸开,底部暖橙渐变升起  → 3.4 眉题 / 标题 / 一行正文 / 分页点 / 白色药丸依次浮现
// → 4.3 右上角「跳过」,五枚发光小方块开始绕钥匙公转  → 6.5–9.3 钥匙绕竖轴整转一圈,侧立时只剩一道轮廓光  → 之后缓慢摆动待机。
// 几何全部程序生成:章头是带钥匙孔的圆角方章,正面一圈「城瘾 · 通行证」环形字(CanvasTexture)。无外部模型、无贴图文件。
(function () {
  var T = window.THREE; if (!T) return;
  var GOLD = 0xD9B36A, S = null, reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  var TL = { shards: 500, pieces: 1100, assembled: 2400, dust: 3000, grad: 3000, eb: 3400, h: 3550, p: 3750, dots: 3950, btn: 4050, skip: 4300, orbit: 4200, turn0: 6500, turn1: 9300 };
  function ease(x) { return x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; }
  function outBack(x) { var c = 1.3; return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); }
  function rnd() { var u = Math.random() * 6.283, v = Math.acos(2 * Math.random() - 1); return new T.Vector3(Math.sin(v) * Math.cos(u), Math.sin(v) * Math.sin(u), Math.cos(v)); }
  function clamp01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
  function warmEnv(r) {
    var es = new T.Scene(); es.add(new T.Mesh(new T.SphereGeometry(20, 24, 12), new T.MeshBasicMaterial({ color: 0x14100C, side: T.BackSide })));
    function box(c, w, h, p) { var m = new T.Mesh(new T.PlaneGeometry(w, h), new T.MeshBasicMaterial({ color: c, side: T.DoubleSide })); m.position.copy(p); m.lookAt(new T.Vector3()); es.add(m); }
    box(0xFFF1DC, 9, 5, new T.Vector3(-6, 7, 5)); box(0xFF9A4A, 6, 4, new T.Vector3(6, -6, 4)); box(0xFFE7C2, 3, 8, new T.Vector3(8, 3, -2)); box(0x6B7A99, 2, 2, new T.Vector3(-3, 4, -9));
    var pm = new T.PMREMGenerator(r), tex = pm.fromScene(es, .03).texture; pm.dispose(); return tex;
  }
  function ringTex() {
    var c = document.createElement('canvas'); c.width = c.height = 512; var g = c.getContext('2d'); g.translate(256, 256);
    g.strokeStyle = 'rgba(60,36,10,.55)'; g.lineWidth = 6; g.beginPath(); g.arc(0, 0, 214, 0, 6.283); g.stroke(); g.lineWidth = 3; g.beginPath(); g.arc(0, 0, 150, 0, 6.283); g.stroke();
    var txt = '城瘾 · 通行证 · CHENGYIN PASS · 城瘾 · 通行证 · CHENGYIN PASS · ', n = txt.length; g.fillStyle = 'rgba(52,30,8,.9)'; g.font = '700 30px -apple-system,"PingFang SC","Helvetica Neue",sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
    for (var i = 0; i < n; i++) { var a = i / n * 6.283 - Math.PI / 2; g.save(); g.rotate(a); g.translate(0, -182); g.fillText(txt[i], 0, 0); g.restore(); }
    g.fillStyle = 'rgba(52,30,8,.75)'; for (var k = 0; k < 24; k++) { var a2 = k / 24 * 6.283; g.beginPath(); g.arc(Math.cos(a2) * 232, Math.sin(a2) * 232, 3, 0, 6.283); g.fill(); }
    var t = new T.CanvasTexture(c); t.anisotropy = 4; return t;
  }
  function spot(color, inner) { var c = document.createElement('canvas'); c.width = c.height = 128; var g = c.getContext('2d'), gr = g.createRadialGradient(64, 64, 0, 64, 64, 64); gr.addColorStop(0, inner || 'rgba(255,255,255,1)'); gr.addColorStop(.25, color); gr.addColorStop(1, 'rgba(255,200,120,0)'); g.fillStyle = gr; g.fillRect(0, 0, 128, 128); return new T.CanvasTexture(c); }
  function starTex() { var c = document.createElement('canvas'); c.width = c.height = 256; var g = c.getContext('2d'); g.translate(128, 128); g.fillStyle = '#fff'; g.beginPath(); for (var i = 0; i < 8; i++) { var a = i * Math.PI / 4, r = i % 2 ? 10 : (i % 4 === 0 ? 120 : 70); g.lineTo(Math.cos(a) * r, Math.sin(a) * r); } g.closePath(); g.fill(); var gr = g.createRadialGradient(0, 0, 0, 0, 0, 40); gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(1, 'rgba(255,255,255,0)'); g.fillStyle = gr; g.fillRect(-128, -128, 256, 256); return new T.CanvasTexture(c); }
  function rr(w, h, r) { var s = new T.Shape(); s.moveTo(-w / 2 + r, -h / 2); s.lineTo(w / 2 - r, -h / 2); s.quadraticCurveTo(w / 2, -h / 2, w / 2, -h / 2 + r); s.lineTo(w / 2, h / 2 - r); s.quadraticCurveTo(w / 2, h / 2, w / 2 - r, h / 2); s.lineTo(-w / 2 + r, h / 2); s.quadraticCurveTo(-w / 2, h / 2, -w / 2, h / 2 - r); s.lineTo(-w / 2, -h / 2 + r); s.quadraticCurveTo(-w / 2, -h / 2, -w / 2 + r, -h / 2); return s; }

  function build(sec) {
    var cv = sec.querySelector('#kyCv'), r = new T.WebGLRenderer({ canvas: cv, antialias: true, alpha: true }); r.setPixelRatio(2); r.setSize(390, 844, false); r.setClearColor(0, 0);
    r.outputEncoding = T.sRGBEncoding; r.toneMapping = T.ACESFilmicToneMapping; r.toneMappingExposure = 1.1;
    var env = warmEnv(r), sc = new T.Scene(), cam = new T.PerspectiveCamera(32, 390 / 844, .1, 100); cam.position.set(0, 0, 14); cam.lookAt(0, 0, 0);
    var gold = new T.MeshStandardMaterial({ color: GOLD, metalness: 1, roughness: .3, envMap: env, envMapIntensity: 1.15 });
    var goldDark = gold.clone(); goldDark.color.setHex(0xA87F3A); goldDark.roughness = .45;
    var KC = new T.Vector3(0, 1.5, 0); // 钥匙中心
    // ---- 钥匙:四个部件各自飞入 ----
    var key = new T.Group(); key.position.copy(KC); sc.add(key);
    var headShape = rr(1.0, 1.0, .3), hole = new T.Path(); hole.absarc(0, .12, .1, 0, 6.283, true); headShape.holes.push(hole);
    var slot = new T.Path(); slot.moveTo(-.055, .06); slot.lineTo(.055, .06); slot.lineTo(.085, -.24); slot.lineTo(-.085, -.24); slot.closePath(); headShape.holes.push(slot);
    var headGeo = new T.ExtrudeGeometry(headShape, { depth: .16, bevelEnabled: true, bevelThickness: .04, bevelSize: .04, bevelSegments: 5, curveSegments: 14 }); headGeo.center();
    var head = new T.Group(); head.add(new T.Mesh(headGeo, gold));
    var ring = new T.MeshBasicMaterial({ map: ringTex(), transparent: true, depthWrite: false }); var rp = new T.Mesh(new T.PlaneGeometry(1.0, 1.0), ring); rp.position.z = .125; head.add(rp); var rp2 = rp.clone(); rp2.rotation.y = Math.PI; rp2.position.z = -.125; head.add(rp2);
    var loop = new T.Group(); loop.add(new T.Mesh(new T.TorusGeometry(.12, .035, 16, 40), gold)); loop.userData.rest = new T.Vector3(0, .64, 0);
    var shaft = new T.Group(); var sm = new T.Mesh(new T.CylinderGeometry(.075, .06, 1.3, 32), gold); shaft.add(sm); var collar = new T.Mesh(new T.CylinderGeometry(.11, .11, .08, 32), goldDark); collar.position.y = .6; shaft.add(collar); shaft.userData.rest = new T.Vector3(0, -1.15, 0);
    var bits = new T.Group(); var b1 = new T.Mesh(new T.BoxGeometry(.3, .16, .1), gold); b1.position.set(.18, -.2, 0); bits.add(b1); var b2 = new T.Mesh(new T.BoxGeometry(.22, .13, .1), gold); b2.position.set(.14, .08, 0); bits.add(b2); bits.userData.rest = new T.Vector3(0, -1.5, 0);
    head.userData.rest = new T.Vector3(0, 0, 0);
    var pieces = [[head, [-2.6, 2.6, -1.8], [1.2, 2.2, .5], 0], [loop, [1.8, 3.2, 1.0], [2.5, .5, 1], 120], [shaft, [3.2, -2.6, -1.2], [.4, 1.2, 2.6], 60], [bits, [-2.8, -2.2, 1.6], [2.2, .3, 1.4], 200]];
    pieces.forEach(function (p) { p[0].userData.from = new T.Vector3().fromArray(p[1]); p[0].userData.fromR = new T.Euler().fromArray(p[2]); p[0].userData.dly = p[3]; key.add(p[0]); });
    // ---- 碎片 + 发光方块 ----
    var shards = [], shardMat = new T.MeshStandardMaterial({ color: GOLD, metalness: 1, roughness: .35, envMap: env, envMapIntensity: 1.2, transparent: true });
    for (var i = 0; i < 42; i++) {
      var w = .06 + Math.random() * .3, h = .03 + Math.random() * .1, pts = [[-w / 2, -h / 2], [w / 2 * (0.6 + Math.random() * .4), -h / 2], [w / 2, h / 2 * (0.5 + Math.random() * .5)], [-w / 2 * (0.5 + Math.random() * .5), h / 2]], s = new T.Shape(); pts.forEach(function (p, k) { k ? s.lineTo(p[0], p[1]) : s.moveTo(p[0], p[1]); });
      var g = new T.ExtrudeGeometry(s, { depth: .015 + Math.random() * .03, bevelEnabled: false }), m = new T.Mesh(g, shardMat.clone());
      var dir = rnd(); m.userData = { from: dir.clone().multiplyScalar(4.2 + Math.random() * 2.2).add(KC), to: rnd().multiplyScalar(.2 + Math.random() * .7).add(KC), rv: new T.Vector3(Math.random() * 6 - 3, Math.random() * 6 - 3, Math.random() * 6 - 3), dly: Math.random() * 900, dur: 1300 + Math.random() * 700 };
      m.rotation.set(Math.random() * 6, Math.random() * 6, Math.random() * 6); sc.add(m); shards.push(m);
    }
    var cubes = [], glowTex = spot('rgba(255,214,150,.55)'), cubeMat = new T.MeshStandardMaterial({ color: 0xFFF6E6, emissive: 0xFFE2B4, emissiveIntensity: 1.25, roughness: .35, metalness: 0, envMap: env });
    var holeTex = (function () { var c = document.createElement('canvas'); c.width = c.height = 64; var g = c.getContext('2d'); g.fillStyle = '#4a2c0c'; g.beginPath(); g.arc(32, 26, 7, 0, 6.283); g.fill(); g.beginPath(); g.moveTo(27, 30); g.lineTo(37, 30); g.lineTo(40, 46); g.lineTo(24, 46); g.fill(); return new T.CanvasTexture(c); })();
    for (var j = 0; j < 5; j++) {
      var cg = new T.ExtrudeGeometry(rr(.17, .17, .05), { depth: .11, bevelEnabled: true, bevelThickness: .02, bevelSize: .02, bevelSegments: 3 }); cg.center();
      var cm = new T.Group(); cm.add(new T.Mesh(cg, cubeMat)); var hp = new T.Mesh(new T.PlaneGeometry(.14, .14), new T.MeshBasicMaterial({ map: holeTex, transparent: true })); hp.position.z = .072; cm.add(hp);
      var sp = new T.Sprite(new T.SpriteMaterial({ map: glowTex, transparent: true, depthWrite: false, blending: T.AdditiveBlending, opacity: .6 })); sp.scale.setScalar(.5); cm.add(sp);
      var d0 = rnd(); cm.userData = { from: d0.multiplyScalar(4.5 + Math.random() * 2).add(KC), to: new T.Vector3((Math.random() - .5) * 1.6, (Math.random() - .5) * 1.6, .3).add(KC), rv: new T.Vector3(Math.random() * 4 - 2, Math.random() * 4 - 2, Math.random() * 4 - 2), dly: 200 + Math.random() * 700, dur: 1500 + Math.random() * 500, R: [.95, 1.25, 1.1, 1.4, 1.05][j], sp: [.55, -.42, .48, -.36, .6][j], tilt: [.5, -.6, .2, .9, -.3][j], ph: j * 1.26, drop: j === 4 };
      sc.add(cm); cubes.push(cm);
    }
    // ---- 星闪 / 金尘 ----
    var star = new T.Sprite(new T.SpriteMaterial({ map: starTex(), transparent: true, depthWrite: false, blending: T.AdditiveBlending })); star.position.set(-1.3, 3.4, 0); star.scale.setScalar(0); sc.add(star);
    var N = 240, dpos = new Float32Array(N * 3), dvel = [], dg = new T.BufferGeometry(); dg.setAttribute('position', new T.BufferAttribute(dpos, 3));
    for (var q = 0; q < N; q++) { var v = rnd(); v.multiplyScalar(.6 + Math.random() * 2.2); v.z -= .4; dvel.push(v); }
    var dust = new T.Points(dg, new T.PointsMaterial({ map: spot('rgba(255,190,110,.9)'), color: 0xFFD9A0, size: .09, transparent: true, depthWrite: false, blending: T.AdditiveBlending, opacity: 0, sizeAttenuation: true })); sc.add(dust);
    // ---- 灯 ----
    sc.add(new T.AmbientLight(0xFFE8D0, .18));
    var keyL = new T.DirectionalLight(0xFFE6C4, 1.5); keyL.position.set(-3, 5, 6); sc.add(keyL);
    var fill = new T.PointLight(0xFF7A2E, 0, 12); fill.position.set(0, -3.5, 2.5); sc.add(fill);
    var rim = new T.DirectionalLight(0xFFF4E4, .5); rim.position.set(1.5, 2, -6); sc.add(rim);
    var rim2 = new T.DirectionalLight(0xFFE9CF, 0); rim2.position.set(-3, 1, -6); sc.add(rim2);
    return { r: r, sc: sc, cam: cam, key: key, pieces: pieces, shards: shards, cubes: cubes, star: star, dust: dust, dpos: dpos, dvel: dvel, fill: fill, rim: rim, rim2: rim2, KC: KC, sec: sec, cv: cv };
  }
  function tick(S, t) {
    var key = S.key;
    // 星闪
    var sa = clamp01(t / 350), sb = clamp01((t - 350) / 450); S.star.scale.setScalar(sa * 1.3 * (1 - sb * .85)); S.star.material.opacity = sa * (1 - sb); S.star.material.rotation = t * .0006;
    // 部件飞入
    S.pieces.forEach(function (p) {
      var o = p[0], u = o.userData, k = clamp01((t - TL.pieces - u.dly) / (TL.assembled - TL.pieces - u.dly)), e = outBack(k);
      o.visible = t > TL.pieces - 200 + u.dly;
      o.position.lerpVectors(u.from, u.rest, e); o.rotation.set(u.fromR.x * (1 - e), u.fromR.y * (1 - e), u.fromR.z * (1 - e));
    });
    // 碎片
    S.shards.forEach(function (m) {
      var u = m.userData, k = clamp01((t - TL.shards - u.dly) / u.dur), e = ease(k); m.visible = t > TL.shards + u.dly && k < 1;
      m.position.lerpVectors(u.from, u.to, e); m.rotation.x += u.rv.x * .016; m.rotation.y += u.rv.y * .016; m.rotation.z += u.rv.z * .016;
      m.material.opacity = k < .75 ? 1 : 1 - (k - .75) / .25;
    });
    // 方块:先飞入,再公转
    S.cubes.forEach(function (c) {
      var u = c.userData, k = clamp01((t - TL.shards - u.dly) / u.dur), e = ease(k); c.visible = t > TL.shards + u.dly;
      if (t < TL.orbit) { c.position.lerpVectors(u.from, u.to, e); if (u.drop && t > 2500) { var kd = clamp01((t - 2500) / 900); c.position.set(u.to.x + kd * 1.6, u.to.y - kd * kd * 4.2, u.to.z); } }
      else { var a = u.ph + (t - TL.orbit) / 1000 * u.sp, blend = clamp01((t - TL.orbit) / 1400); var ox = Math.cos(a) * u.R, oz = Math.sin(a) * u.R * .55, oy = Math.sin(a + u.tilt) * u.R * .45; var tgt = new T.Vector3(S.KC.x + ox, S.KC.y + oy, S.KC.z + oz); var base = u.drop ? new T.Vector3(u.to.x + 1.6, u.to.y - 4.2, u.to.z) : u.to; c.position.lerpVectors(base, tgt, ease(blend)); }
      c.rotation.x += u.rv.x * .01; c.rotation.y += u.rv.y * .01;
    });
    // 金尘
    var dk = (t - TL.dust) / 1500; if (dk > 0 && dk < 1) { for (var i = 0; i < S.dvel.length; i++) { var v = S.dvel[i], e2 = 1 - Math.pow(1 - dk, 2.2); S.dpos[i * 3] = S.KC.x + v.x * e2; S.dpos[i * 3 + 1] = S.KC.y + .2 + v.y * e2 - dk * dk * 1.2; S.dpos[i * 3 + 2] = S.KC.z + v.z * e2; } S.dust.geometry.attributes.position.needsUpdate = true; S.dust.material.opacity = Math.sin(dk * Math.PI) * .9; } else S.dust.material.opacity = 0;
    // 暖光从下面升起(跟 DOM 渐变同步)
    S.fill.intensity = 1.4 * ease(clamp01((t - TL.grad) / 900));
    // 整转 + 轮廓光 + 待机
    var tk = clamp01((t - TL.turn0) / (TL.turn1 - TL.turn0)), ry = ease(tk) * Math.PI * 2, edge = Math.pow(Math.sin(ry), 2);
    S.rim.intensity = .5 + 3.6 * edge; S.rim2.intensity = 2.6 * edge; S.sec.classList.toggle('ky--edge', edge > .6);
    var idle = t > TL.assembled + 300 && (tk <= 0 || tk >= 1) ? 1 : 0;
    key.rotation.y = ry + idle * Math.sin(t / 1000 * .6) * .16; key.rotation.x = idle * Math.sin(t / 1000 * .45) * .05; key.position.y = S.KC.y + idle * Math.sin(t / 1000 * 1.2) * .035;
    // DOM 层
    var cl = S.sec.classList; cl.toggle('ky-g', t >= TL.grad); cl.toggle('ky-t1', t >= TL.eb); cl.toggle('ky-t2', t >= TL.h); cl.toggle('ky-t3', t >= TL.p); cl.toggle('ky-t4', t >= TL.dots); cl.toggle('ky-t5', t >= TL.btn); cl.toggle('ky-t6', t >= TL.skip);
    S.r.render(S.sc, S.cam);
  }
  var cur = null;
  function start(sec) {
    stop(); cur = { S: build(sec), t0: performance.now(), off: 0, raf: 0, frozen: false };
    if (reduce) { seek(4600); cur.frozen = true; return; }
    (function f(now) { if (!cur || !sec.isConnected || !sec.classList.contains('on')) { stop(); return; } if (!cur.frozen) tick(cur.S, now - cur.t0 + cur.off); cur.raf = requestAnimationFrame(f); })(performance.now());
  }
  function seek(ms) { if (!cur) return; cur.off = ms; cur.t0 = performance.now(); tick(cur.S, ms); }
  function stop() { if (!cur) return; cancelAnimationFrame(cur.raf); try { cur.S.r.dispose(); } catch (e) {} cur = null; }
  window.KEY = { start: start, seek: seek, stop: stop, TL: TL, time: function () { return cur ? performance.now() - cur.t0 + cur.off : 0; } };
})();
