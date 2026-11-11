// 风格 A · 3D 玻璃(Three.js r128,cdnjs 同一文件本地副本 three.min.js):
// 一张覆盖整台手机的 WebGL 画布,按页面里每个 <span class="g3" data-icon="key"> 的位置用 scissor 分区渲染,
// 所以同一枚 3D 图标能同时出现在网格、弹窗、列表小尺寸里。材质 = 白色玻璃外壳(清漆 + 环境反射)+ 内芯(柠檬绿 / 深灰实体),
// 环境贴图是程序生成的「影棚」(三块柔光板 + 渐变天穹),不加载任何外部文件。
(function () {
  var T = window.THREE; if (!T) return;
  var LIME = 0xCDFF71, INK = 0x26272C, WHITE = 0xffffff;
  var renderer, envTex, views = {}, canvas, raf = 0, scenes = {}, cam, started = {}, lastSeen = 0;
  function ensureRenderer() {
    if (renderer) return renderer;
    var vp = document.getElementById('vp'); canvas = document.createElement('canvas'); canvas.className = 'g3cv'; vp.appendChild(canvas);
    renderer = new T.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true, premultipliedAlpha: true });
    renderer.setPixelRatio(2); renderer.setSize(390, 844, false); renderer.setClearColor(0x000000, 0);
    renderer.outputEncoding = T.sRGBEncoding; renderer.toneMapping = T.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.05;
    envTex = buildEnv(renderer);
    cam = new T.PerspectiveCamera(26, 1, .1, 50); cam.position.set(0, 0, 6.0); cam.lookAt(0, 0, 0);
    return renderer;
  }
  // 程序影棚:天穹渐变 + 三块柔光板(白 / 暖 / 柠檬绿),玻璃上的反光全来自这里
  function buildEnv(r) {
    var es = new T.Scene();
    var sky = new T.SphereGeometry(20, 32, 16), col = [], pos = sky.attributes.position;
    for (var i = 0; i < pos.count; i++) { var y = pos.getY(i) / 20, c = new T.Color().lerpColors(new T.Color(0x07080B), new T.Color(0xD8DEEA), Math.max(0, Math.min(1, y * .5 + .5))); col.push(c.r, c.g, c.b); }
    sky.setAttribute('color', new T.Float32BufferAttribute(col, 3));
    es.add(new T.Mesh(sky, new T.MeshBasicMaterial({ vertexColors: true, side: T.BackSide })));
    function box(c, w, h, p, l) { var m = new T.Mesh(new T.PlaneGeometry(w, h), new T.MeshBasicMaterial({ color: c, side: T.DoubleSide })); m.position.copy(p); m.lookAt(l || new T.Vector3()); es.add(m); }
    box(0xffffff, 10, 6, new T.Vector3(-6, 8, 6)); box(0xFFE2B8, 5, 9, new T.Vector3(9, 2, 3)); box(0xCDFF71, 7, 3, new T.Vector3(-4, -7, 4)); box(0xA8C8FF, 3, 3, new T.Vector3(2, 3, -9));
    var pm = new T.PMREMGenerator(r); var tex = pm.fromScene(es, .04).texture; pm.dispose(); return tex;
  }
  // 玻璃外壳:物理材质 + 注入一段菲涅尔 —— 正对的面几乎全透(看得见内芯),掠射的边缘变亮变实,这就是玻璃的厚边
  function shellMat(tint) {
    var m = new T.MeshPhysicalMaterial({ color: 0x000000, metalness: 0, roughness: .05, transparent: true, opacity: .08, clearcoat: 1, clearcoatRoughness: .04, envMap: envTex, envMapIntensity: 1.1, depthWrite: false, reflectivity: 1 }); m.userData.tint = new T.Color(tint || WHITE);
    m.onBeforeCompile = function (sh) { sh.fragmentShader = sh.fragmentShader.replace('#include <dithering_fragment>', '#include <dithering_fragment>\n float fr = pow(1.0 - saturate(dot(normalize(vNormal), normalize(vViewPosition))), 2.2); gl_FragColor.a = clamp(opacity + fr * 0.95, 0.0, 1.0); gl_FragColor.rgb += fr * uTint * 0.9;'); sh.fragmentShader = sh.fragmentShader.replace('#include <common>', '#include <common>\nuniform vec3 uTint;'); sh.uniforms.uTint = { value: m.userData.tint }; };
    return m;
  }
  function coreMat(c, rough) { return new T.MeshPhysicalMaterial({ color: c, emissive: c === LIME ? 0x3A5A00 : 0x000000, metalness: c === LIME ? 0 : .2, roughness: rough == null ? .3 : rough, clearcoat: .7, clearcoatRoughness: .2, envMap: envTex, envMapIntensity: 1.1 }); }
  // 玻璃件:外壳(透)+ 内芯(实,缩 .66);solid:true 则整件实体
  function piece(geo, o) {
    o = o || {}; var g = new T.Group();
    if (o.solid) { g.add(new T.Mesh(geo, coreMat(o.core || INK, o.rough))); }
    else {
      var core = new T.Mesh(geo, coreMat(o.core || LIME)); core.scale.setScalar(o.coreScale || .78); g.add(core);
      var back = new T.Mesh(geo, shellMat(o.tint)); back.material.side = T.BackSide; back.material.opacity = .06; g.add(back);
      var sh = new T.Mesh(geo, shellMat(o.tint)); g.add(sh);
    }
    if (o.p) g.position.set(o.p[0], o.p[1], o.p[2]); if (o.r) g.rotation.set(o.r[0], o.r[1], o.r[2]); if (o.s) g.scale.setScalar(o.s);
    return g;
  }
  function rrect(w, h, r) { var s = new T.Shape(); s.moveTo(-w / 2 + r, -h / 2); s.lineTo(w / 2 - r, -h / 2); s.quadraticCurveTo(w / 2, -h / 2, w / 2, -h / 2 + r); s.lineTo(w / 2, h / 2 - r); s.quadraticCurveTo(w / 2, h / 2, w / 2 - r, h / 2); s.lineTo(-w / 2 + r, h / 2); s.quadraticCurveTo(-w / 2, h / 2, -w / 2, h / 2 - r); s.lineTo(-w / 2, -h / 2 + r); s.quadraticCurveTo(-w / 2, -h / 2, -w / 2 + r, -h / 2); return s; }
  function slab(w, h, d, r) { var g = new T.ExtrudeGeometry(rrect(w, h, r), { depth: d, bevelEnabled: true, bevelThickness: .05, bevelSize: .05, bevelSegments: 4, curveSegments: 10 }); g.center(); return g; }
  function poly(pts, d) { var s = new T.Shape(); pts.forEach(function (p, i) { i ? s.lineTo(p[0], p[1]) : s.moveTo(p[0], p[1]); }); var g = new T.ExtrudeGeometry(s, { depth: d, bevelEnabled: true, bevelThickness: .03, bevelSize: .03, bevelSegments: 3 }); g.center(); return g; }
  function star(r, n, d) { var pts = []; for (var i = 0; i < n * 2; i++) { var a = -Math.PI / 2 + Math.PI * i / n, rr = i % 2 ? r * .45 : r; pts.push([Math.cos(a) * rr, Math.sin(a) * rr]); } return poly(pts, d); }
  var CYL = function (rt, rb, h, seg) { return new T.CylinderGeometry(rt, rb, h, seg || 48); };
  var BUILD = {
    clue: function (g) {
      var lens = new T.Group(); lens.add(piece(new T.TorusGeometry(.78, .17, 24, 64), { tint: 0xEAF2FF })); lens.add(piece(CYL(.64, .64, .06), { r: [Math.PI / 2, 0, 0], tint: 0xEAF2FF, coreScale: .98, core: 0x9FB6D6 }));
      lens.add(piece(new T.SphereGeometry(.17, 32, 24), { solid: true, core: LIME, p: [.18, -.14, .12] })); lens.position.set(-.35, .35, 0); g.add(lens);
      g.add(piece(CYL(.16, .16, 1.25), { solid: true, core: INK, p: [.62, -.62, 0], r: [0, 0, Math.PI / 4] }));
    },
    badge: function (g) {
      g.add(piece(CYL(.82, .82, .22), { r: [Math.PI / 2, 0, 0], p: [0, .3, 0], tint: 0xFFF2D0, coreScale: .88, core: 0xE8C877 }));
      g.add(piece(star(.42, 5, .12), { solid: true, core: LIME, p: [0, .3, .16] }));
      g.add(piece(new T.BoxGeometry(.34, 1.0, .08), { solid: true, core: INK, p: [-.26, -.75, -.04], r: [0, 0, .18] })); g.add(piece(new T.BoxGeometry(.34, 1.0, .08), { solid: true, core: INK, p: [.26, -.75, -.04], r: [0, 0, -.18] }));
    },
    key: function (g) {
      g.rotation.z = -Math.PI / 4;
      g.add(piece(new T.TorusGeometry(.52, .19, 24, 64), { p: [0, .78, 0], tint: 0xFFF6DC }));
      g.add(piece(CYL(.13, .11, 1.7), { solid: true, core: INK, p: [0, -.35, 0] }));
      g.add(piece(new T.BoxGeometry(.36, .16, .16), { solid: true, core: LIME, p: [.22, -.95, 0] })); g.add(piece(new T.BoxGeometry(.28, .16, .16), { solid: true, core: LIME, p: [.18, -.62, 0] }));
    },
    success: function (g) {
      g.add(piece(slab(1.7, 1.7, .22, .48), { tint: 0xF2FFD6, core: 0xB9EA60, coreScale: .8 }));
      g.add(piece(poly([[-.62, .02], [-.42, .24], [-.14, -.06], [.44, .56], [.64, .34], [-.14, -.5]], .14), { solid: true, core: LIME, p: [0, 0, .22] }));
    },
    fail: function (g) {
      g.add(piece(slab(1.7, 1.7, .22, .48), { tint: 0xFFE6E8, core: 0x4A4C55, coreScale: .8 }));
      g.add(piece(new T.BoxGeometry(1.0, .22, .14), { solid: true, core: INK, p: [0, 0, .22], r: [0, 0, Math.PI / 4] })); g.add(piece(new T.BoxGeometry(1.0, .22, .14), { solid: true, core: INK, p: [0, 0, .22], r: [0, 0, -Math.PI / 4] }));
    },
    wait: function (g) {
      g.add(piece(CYL(.72, .72, .1), { solid: true, core: INK, p: [0, .86, 0] })); g.add(piece(CYL(.72, .72, .1), { solid: true, core: INK, p: [0, -.86, 0] }));
      g.add(piece(new T.ConeGeometry(.6, .8, 40), { p: [0, .42, 0], r: [Math.PI, 0, 0], tint: 0xFFF8E6, core: 0xFFE9B0, coreScale: .4 })); g.add(piece(new T.ConeGeometry(.6, .8, 40), { p: [0, -.42, 0], tint: 0xFFF8E6, core: LIME, coreScale: .62 }));
      g.add(piece(CYL(.03, .03, .5), { solid: true, core: LIME, p: [0, -.25, 0] }));
    },
    team: function (g) {
      [[-.62, -.05, -.35, .78], [.62, -.05, -.35, .78], [0, .1, .3, 1]].forEach(function (p) {
        var m = new T.Group(); m.add(piece(new T.SphereGeometry(.34, 40, 28), { p: [0, .58, 0], tint: 0xEEF4FF })); m.add(piece(CYL(.42, .5, .7, 40), { p: [0, -.1, 0], tint: 0xEEF4FF, core: p[3] === 1 ? LIME : 0x8EA2C8, coreScale: .7 }));
        m.position.set(p[0], p[1], p[2]); m.scale.setScalar(p[3]); g.add(m);
      });
    },
    pass: function (g) {
      g.rotation.z = -.12;
      g.add(piece(slab(1.3, 1.9, .12, .18), { tint: 0xE6FBFF, core: 0xF4F4F0, coreScale: .9 }));
      g.add(piece(new T.BoxGeometry(1.3, .4, .14), { solid: true, core: LIME, p: [0, .6, .02] }));
      g.add(piece(new T.TorusGeometry(.14, .05, 12, 32), { solid: true, core: INK, p: [0, 1.0, .02] }));
      g.add(piece(new T.BoxGeometry(.7, .08, .06), { solid: true, core: INK, p: [-.15, 0, .1] })); g.add(piece(new T.BoxGeometry(.45, .08, .06), { solid: true, core: INK, p: [-.27, -.25, .1] }));
    },
    points: function (g) {
      g.rotation.x = .35;
      g.add(piece(CYL(.8, .8, .2), { p: [-.1, -.5, 0], tint: 0xFFF4D6, core: 0xE8C877, coreScale: .9 })); g.add(piece(CYL(.8, .8, .2), { p: [.08, -.18, 0], tint: 0xFFF4D6, core: 0xE8C877, coreScale: .9 }));
      g.add(piece(CYL(.8, .8, .2), { p: [-.02, .16, 0], tint: 0xF6FFE0, core: LIME, coreScale: .9 })); g.add(piece(star(.3, 5, .06), { solid: true, core: INK, p: [-.02, .3, 0], r: [-Math.PI / 2, 0, 0] }));
    },
    seal: function (g) {
      g.add(piece(CYL(.22, .26, .8), { solid: true, core: INK, p: [0, .72, 0] }));
      g.add(piece(slab(1.5, 1.0, .3, .16), { p: [0, .12, 0], r: [Math.PI / 2, 0, 0], tint: 0xF4F4F4, core: 0xB7B9C2, coreScale: .86 }));
      g.add(piece(slab(1.1, .8, .06, .14), { solid: true, core: LIME, p: [0, -.55, 0], r: [Math.PI / 2, 0, 0] }));
    },
  };
  function scene(name) {
    if (scenes[name]) return scenes[name];
    var s = new T.Scene(), g = new T.Group(); BUILD[name](g); s.add(g); s.userData.obj = g;
    s.add(new T.AmbientLight(0xffffff, .25));
    var k = new T.DirectionalLight(0xffffff, 1.4); k.position.set(-3, 5, 6); s.add(k);
    var rim = new T.DirectionalLight(0xCFE0FF, 1.2); rim.position.set(4, 2, -5); s.add(rim);
    var fill = new T.DirectionalLight(0xCDFF71, .35); fill.position.set(-4, -5, 3); s.add(fill);
    return (scenes[name] = s);
  }
  var reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  function pose(obj, t, born, o) {
    var age = (t - born) / 1000, inA = reduce ? 1 : Math.min(1, age / .7);
    var sc = inA >= 1 ? 1 : Math.sin(inA * Math.PI / 2) * (1 + .12 * Math.sin(inA * Math.PI)); // 进场:弹出带一点过冲
    obj.scale.setScalar(sc); obj.rotation.y = reduce ? -.35 : (o && o.spin === false ? -.35 : Math.sin(t / 1000 * .55) * .55); obj.rotation.x = reduce ? .12 : .12 + Math.sin(t / 1000 * .7) * .08;
    obj.position.y = reduce ? 0 : Math.sin(t / 1000 * 1.1 + (o && o.ph || 0)) * .05;
  }
  function frame(t) {
    raf = requestAnimationFrame(frame);
    var els = document.querySelectorAll('.scr.on .g3'); if (!els.length) { if (renderer && lastSeen) { renderer.setScissorTest(false); renderer.clear(); lastSeen = 0; } return; }
    ensureRenderer(); lastSeen = t;
    var vr = document.getElementById('vp').getBoundingClientRect(), openPop = document.querySelector('.scr.on .gm-pop.open');
    renderer.setScissorTest(false); renderer.clear(); renderer.setScissorTest(true);
    for (var i = 0; i < els.length; i++) {
      var el = els[i], r = el.getBoundingClientRect(); if (!r.width || r.bottom < vr.top || r.top > vr.bottom) continue;
      var pp = el.closest('.gm-pop'); if (pp && !pp.classList.contains('open')) continue;
      if (!pp && openPop) continue; // 弹窗打开时,弹窗后面的 3D 图标不画(画布在最上层,否则会盖住遮罩)
      var name = el.getAttribute('data-icon'), s = scene(name); if (!el.dataset.born) el.dataset.born = t;
      pose(s.userData.obj, t, +el.dataset.born, { ph: i });
      var x = r.left - vr.left, y = r.top - vr.top, w = r.width, h = r.height, ox = 0;
      // 同一个场景在不同格子里会共用姿态;列表小图取同一帧,这是刻意的(像一套同一时刻的实物)
      renderer.setViewport(x, 844 - y - h, w, h); renderer.setScissor(x, 844 - y - h, w, h); cam.aspect = w / h; cam.updateProjectionMatrix();
      renderer.render(s, cam);
    }
  }
  function start() { if (!raf) raf = requestAnimationFrame(frame); }
  // 预渲染序列帧(小程序落地方式之一):同一场景绕 Y 轴一圈 n 帧拼成一条 WebP 雪碧图
  function sprite(name, n, px) {
    ensureRenderer(); n = n || 24; px = px || 112;
    var off = document.createElement('canvas'), r2 = new T.WebGLRenderer({ canvas: off, antialias: true, alpha: true }); r2.setPixelRatio(1); r2.setSize(px, px, false); r2.setClearColor(0, 0); r2.outputEncoding = T.sRGBEncoding; r2.toneMapping = T.ACESFilmicToneMapping; r2.toneMappingExposure = 1.05;
    var s = scene(name), obj = s.userData.obj, strip = document.createElement('canvas'); strip.width = n * px; strip.height = px; var g = strip.getContext('2d'), c2 = new T.PerspectiveCamera(26, 1, .1, 50); c2.position.set(0, 0, 6.0); c2.lookAt(0, 0, 0);
    var keep = { s: obj.scale.x, y: obj.rotation.y, x: obj.rotation.x, py: obj.position.y };
    for (var i = 0; i < n; i++) { obj.scale.setScalar(1); obj.rotation.x = .12; obj.position.y = 0; obj.rotation.y = Math.sin(i / n * Math.PI * 2) * .55; r2.render(s, c2); g.drawImage(off, i * px, 0); }
    obj.scale.setScalar(keep.s); obj.rotation.y = keep.y; obj.rotation.x = keep.x; obj.position.y = keep.py; r2.dispose();
    var url = strip.toDataURL('image/webp', .82); return { url: url, bytes: Math.round((url.length - 23) * 3 / 4), n: n, px: px };
  }
  window.G3 = { start: start, sprite: sprite, names: Object.keys(BUILD), ensure: ensureRenderer, env: function () { ensureRenderer(); return envTex; }, T: T, piece: piece, slab: slab, rrect: rrect, poly: poly };
  start();
})();
