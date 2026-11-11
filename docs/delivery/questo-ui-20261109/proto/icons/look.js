// 外观系统:与代码里 pages/play/utils/theme-look.js 同一份目录(星尘 / 纸本 / 末世 / 夜城 × 主题配色 黑蓝红黄白)。
// 色值来自 pages/play/index.wxss 的 look-- / atmosphere-- 行(原型 v7 PAL),这里只是把它们挂到手机根节点的 --lk-* 变量上,
// 游戏内每一张弹窗、卡片、按钮都只读这些变量,所以切外观就整套跟着换。
(function () {
  var STYLES = {
    STARDUST: { label: '星尘', tx: 'iris', fd: '"Songti SC","STSong","Noto Serif SC",serif', fw: 600, ls: '.03em', fs: '"Xingkai SC","Kaiti SC","STKaiti",cursive', fm: '"Songti SC",serif', r: '18px', rb: '999px', cut: 'none', fx: 'gather', hint: '玻璃 · 光圈 · 手写' },
    PAPER:    { label: '纸本', tx: 'page', fd: '"Songti SC","STSong","Noto Serif SC",serif', fw: 900, ls: '.02em', fs: '"Kaiti SC","STKaiti",serif', fm: '"Courier New",Courier,monospace', r: '3px', rb: '3px', cut: 'none', fx: 'ink', hint: '纸纹 · 翻页 · 印章' },
    SURVIVAL: { label: '末世', tx: 'gust', fd: '-apple-system,"PingFang SC","Helvetica Neue",sans-serif', fw: 700, ls: '.12em', fs: '-apple-system,"PingFang SC",sans-serif', fm: 'Menlo,"SF Mono",monospace', r: '2px', rb: '2px', cut: 'polygon(0 3px,7px 0,calc(100% - 16px) 0,100% 6px,100% calc(100% - 9px),calc(100% - 6px) 100%,11px 100%,0 calc(100% - 4px))', fx: 'wind', hint: '锈蚀 · 风刮 · 时间段' },
    CYBER:    { label: '夜城', tx: 'surge', fd: '-apple-system,"PingFang SC","Helvetica Neue",sans-serif', fw: 800, ls: '.08em', fs: '-apple-system,"PingFang SC",sans-serif', fm: 'Menlo,"SF Mono",monospace', r: '0px', rb: '0px', cut: 'polygon(0 0,calc(100% - 12px) 0,100% 12px,100% 100%,12px 100%,0 calc(100% - 12px))', fx: 'decode', hint: '霓虹 · 电流 · 切角' },
  };
  var PAL = {
    STARDUST: { DEFAULT: ['#0A0A0A', '#F2F2F0', '#9AA0AA', '#E9E2D0', '#9AA0AA'], BLUE: ['#0E1A33', '#F5F8FF', '#A9BBE0', '#CFE0FF', '#A9BBE0'], RED: ['#2A0E12', '#FFF4F5', '#E0AAB1', '#FFB8AC', '#E0AAB1'], YELLOW: ['#2A2208', '#FFFBEF', '#DFCD9A', '#F2D58A', '#DFCD9A'], WHITE: ['#F5F6F8', '#111318', '#5B6270', '#111318', '#5B6270'] },
    PAPER:    { DEFAULT: ['#1D1B18', '#ECE6D8', '#9A927F', '#D0503C', '#9A927F'], BLUE: ['#1C2A44', '#EEF0EA', '#A2ADBF', '#E8C66A', '#A2ADBF'], RED: ['#3A1A17', '#F4E6DC', '#C9A596', '#E8C66A', '#C9A596'], YELLOW: ['#E6DCC4', '#2B241A', '#7D715C', '#A3281D', '#7D715C'], WHITE: ['#F4F1EA', '#1E1C19', '#7A756C', '#B0302A', '#7A756C'] },
    SURVIVAL: { DEFAULT: ['#0E1012', '#E4E7E9', '#8A9196', '#FF8A3D', '#8A9196'], BLUE: ['#0B1626', '#EAF3FB', '#8FA6BD', '#FFB35C', '#8FA6BD'], RED: ['#1C0A0A', '#F3E3E0', '#B08A86', '#7FE6C4', '#B08A86'], YELLOW: ['#2A1D0C', '#FFF2DC', '#CDB38A', '#8FD3FF', '#CDB38A'], WHITE: ['#E3E9EE', '#14202B', '#5D6D7B', '#D4561E', '#5D6D7B'] },
    CYBER:    { DEFAULT: ['#0B0B0F', '#ECEBE4', '#8A8C96', '#F3E600', '#24E0E8'], BLUE: ['#06101C', '#E2F1FF', '#7E93AC', '#2DE2E6', '#FF3B6B'], RED: ['#12060A', '#FFE8EA', '#A88088', '#FF2E4C', '#2DE2E6'], YELLOW: ['#100E04', '#FFF6CC', '#A99E6E', '#FCEE0A', '#FF2E4C'], WHITE: ['#E6E7EA', '#101216', '#5E636E', '#E0002B', '#008C99'] },
  };
  var PALS = [['DEFAULT', '黑'], ['BLUE', '蓝'], ['RED', '红'], ['YELLOW', '黄'], ['WHITE', '白']];
  var TIMES = { DAWN: 'linear-gradient(180deg,rgba(70,100,160,.35),rgba(240,180,140,.28))', NOON: 'linear-gradient(180deg,rgba(255,255,255,.10),rgba(255,250,225,.06))', DUSK: 'linear-gradient(180deg,rgba(60,40,110,.35),rgba(240,130,70,.42))', NIGHT: 'linear-gradient(180deg,rgba(0,4,16,.45),rgba(0,4,16,.2))' };
  var state = { style: 'STARDUST', pal: 'DEFAULT', time: 'DUSK' };
  try { var sv = JSON.parse(localStorage.getItem('cy-game-look') || 'null'); if (sv && STYLES[sv.style] && PAL[sv.style][sv.pal]) state = Object.assign(state, sv); } catch (e) {}
  // 截图用:?look=PAPER&pal=YELLOW
  var qs = /[?&]look=([A-Z]+)/.exec(location.search), qp = /[?&]pal=([A-Z]+)/.exec(location.search);
  if (qs && STYLES[qs[1]]) state.style = qs[1]; if (qp && PAL[state.style][qp[1]]) state.pal = qp[1];
  var reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

  function isLight() { var p = PAL[state.style][state.pal]; return parseInt(p[1].slice(1, 3), 16) < 120; }
  function apply() {
    var vp = document.getElementById('vp'); if (!vp) return;
    var s = STYLES[state.style], p = PAL[state.style][state.pal], st = vp.style;
    st.setProperty('--lk-bg', p[0]); st.setProperty('--lk-ink', p[1]); st.setProperty('--lk-mute', p[2]); st.setProperty('--lk-a', p[3]); st.setProperty('--lk-b', p[4]);
    st.setProperty('--lk-fd', s.fd); st.setProperty('--lk-fw', s.fw); st.setProperty('--lk-ls', s.ls); st.setProperty('--lk-fs', s.fs); st.setProperty('--lk-fm', s.fm);
    st.setProperty('--lk-r', s.r); st.setProperty('--lk-rb', s.rb); st.setProperty('--lk-cut', s.cut);
    st.setProperty('--lk-time', state.style === 'SURVIVAL' ? TIMES[state.time] : 'none');
    vp.setAttribute('data-look', state.style.toLowerCase()); vp.setAttribute('data-pal', state.pal.toLowerCase());
    vp.classList.toggle('lk-light', isLight());
    var phone = document.getElementById('phone'), cur = window.PROTO && PROTO.cur();
    if (phone && cur) phone.classList.toggle('lt', cur.classList.contains('gm') ? isLight() : /theme-merchant|theme-light|is-light/.test(cur.className));
    try { localStorage.setItem('cy-game-look', JSON.stringify(state)); } catch (e) {}
    [].forEach.call(document.querySelectorAll('#lkSeg [data-style]'), function (b) { b.classList.toggle('on', b.getAttribute('data-style') === state.style); });
    [].forEach.call(document.querySelectorAll('#lkPal [data-pal]'), function (b) { b.classList.toggle('on', b.getAttribute('data-pal') === state.pal); b.style.background = PAL[state.style][b.getAttribute('data-pal')][0]; b.style.borderColor = PAL[state.style][b.getAttribute('data-pal')][3]; });
    var tm = document.getElementById('lkTime'); if (tm) { tm.hidden = state.style !== 'SURVIVAL'; [].forEach.call(tm.children, function (b) { b.classList.toggle('on', b.getAttribute('data-time') === state.time); }); }
    var h = document.getElementById('lkHint'); if (h) h.textContent = s.label + ' · ' + PALS.filter(function (x) { return x[0] === state.pal; })[0][1] + ' · ' + s.hint;
    document.dispatchEvent(new CustomEvent('lookchange'));
    // 切换条高度变了,壳要重新把手机缩放进舞台
    requestAnimationFrame(function () { window.dispatchEvent(new Event('resize')); });
  }
  function mount() {
    var bar = document.querySelector('.sh-bar'); if (!bar || document.getElementById('lkSeg')) return;
    var box = document.createElement('div'); box.className = 'lk-bar';
    box.innerHTML = '<span class="lk-l">外观</span><span class="seg" id="lkSeg">' + Object.keys(STYLES).map(function (k) { return '<button data-style="' + k + '">' + STYLES[k].label + '</button>'; }).join('') + '</span>' +
      '<span class="lk-l">配色</span><span class="lk-pal" id="lkPal">' + PALS.map(function (p) { return '<button data-pal="' + p[0] + '" title="' + p[1] + '"></button>'; }).join('') + '</span>' +
      '<span class="seg" id="lkTime" hidden>' + [['DAWN', '清晨'], ['NOON', '正午'], ['DUSK', '日落'], ['NIGHT', '夜晚']].map(function (t) { return '<button data-time="' + t[0] + '">' + t[1] + '</button>'; }).join('') + '</span>' +
      '<span class="lk-hint" id="lkHint"></span>';
    bar.appendChild(box);
    box.addEventListener('click', function (e) {
      var b = e.target.closest('button'); if (!b) return;
      if (b.hasAttribute('data-style')) { var to = b.getAttribute('data-style'); if (to === state.style) return; tx(function () { state.style = to; apply(); }); return; }
      if (b.hasAttribute('data-pal')) { state.pal = b.getAttribute('data-pal'); apply(); return; }
      if (b.hasAttribute('data-time')) { state.time = b.getAttribute('data-time'); apply(); }
    });
    apply();
  }
  // 转场:盖上 → 换内容 → 揭开(与 components/look-transition 同一个三态模型):星尘光圈 / 纸本翻页 / 末世风刮 / 夜城电流
  function tx(swap, kind) {
    var vp = document.getElementById('vp'); if (!vp || reduce) { swap(); return; }
    var k = kind || STYLES[state.style].tx, el = document.createElement('div'); el.className = 'lt lt--' + k;
    el.innerHTML = k === 'iris' ? '<div class="lt__circle"></div>' : k === 'surge' ? '<div class="lt__panel"></div><div class="lt__bolt"></div>' : '<div class="lt__panel"></div>';
    vp.appendChild(el); el.getBoundingClientRect();
    requestAnimationFrame(function () { requestAnimationFrame(function () { el.classList.add('is-in'); }); });
    setTimeout(function () { swap(); el.classList.remove('is-in'); el.classList.add('is-out'); setTimeout(function () { el.remove(); }, 640); }, 520);
  }

  // ---- 故事文字动效(定稿原型 BRgHjPbXZfe9xhd6QBCwfP 的四种,跟随外观) ----
  var PUNCT = /^[,.!?;:，。！？；：、」』）)…—]$/, GLYPH = 'ｱｲｳｴｵｶｷｸｹｺ01#%&*<>/=+';
  function chars(text) {
    var a = Array.from(text).reduce(function (acc, ch) { if (acc.length && PUNCT.test(ch)) acc[acc.length - 1] += ch; else acc.push(ch); return acc; }, []), n = a.length;
    return a.map(function (ch, i) { var ang = Math.random() * 6.28, d = 24 + Math.random() * 40; return '<span class="c" style="--i:' + i + ';--n:' + n + ';--r:' + Math.random().toFixed(3) + ';--dx:' + (Math.cos(ang) * d).toFixed(1) + 'px;--dy:' + (Math.sin(ang) * d).toFixed(1) + 'px" data-ch="' + ch + '">' + ch + '</span>'; }).join('');
  }
  function fxLine(text, cls, role) { return '<p class="' + (cls || 'line') + ' fx" data-role="' + (role || 'body') + '" style="--n:' + Array.from(text).length + '" data-text="' + text.replace(/"/g, '&quot;') + '">' + chars(text) + '</p>'; }
  // 定稿 v3(BRgHjPbXZfe9xhd6QBCwfP)= theme-look.js 的 STYLE_TEXT:标题 / 选项各固定一种,正文按段在池子里轮换,相邻不重复
  var STYLE_TEXT = { STARDUST: { title: 'gather', opts: 'rise', pool: ['gather', 'rise', 'glow'] }, PAPER: { title: 'ink', opts: 'drop', pool: ['type', 'ink', 'drop'] }, SURVIVAL: { title: 'wind', opts: 'signal', pool: ['wind', 'signal', 'fog', 'drop'] }, CYBER: { title: 'neon', opts: 'scan', pool: ['decode', 'neon', 'scan', 'wipe'] } };
  var ALLFX = ['rise', 'type', 'ink', 'gather', 'glow', 'wipe', 'drop', 'wind', 'signal', 'fog', 'neon', 'scan', 'decode'];
  function playFx(root) {
    var st = STYLE_TEXT[state.style], last = null, seq = 0;
    var io = new IntersectionObserver(function (es) { es.forEach(function (en) { if (en.isIntersecting && !en.target.dataset.played) { en.target.dataset.played = '1'; start(en.target, en.target.dataset.fx); } }); }, { root: root, threshold: .35 });
    [].forEach.call(root.querySelectorAll('.fx'), function (n) {
      var role = n.getAttribute('data-role') || 'body', k;
      if (role === 'title') k = st.title; else if (role === 'opt') k = st.opts; else { k = st.pool[seq % st.pool.length]; if (k === last) k = st.pool[(seq + 1) % st.pool.length]; last = k; seq++; }
      n.className = n.className.replace(/\bfx-\w+/g, '').replace(/\bon\b|\bsettle\b|\bscanning\b/g, '') + ' fx-' + k; n.dataset.fx = k; delete n.dataset.played;
      [].forEach.call(n.querySelectorAll('.c.raw'), function (c) { c.classList.remove('raw'); c.textContent = c.dataset.ch; });
      io.observe(n);
    });
    function start(n, kind) {
      if (reduce) { n.classList.add('on'); return; }
      if (kind === 'decode') {
        var cs = [].slice.call(n.querySelectorAll('.c'));
        cs.forEach(function (c) { c.classList.add('raw'); c.textContent = GLYPH[(Math.random() * GLYPH.length) | 0]; });
        n.classList.add('on');
        cs.forEach(function (c, i) { var flips = 0, iv = setInterval(function () { if (!c.isConnected || ++flips > 3 + i * .5) { clearInterval(iv); c.textContent = c.dataset.ch; c.classList.remove('raw'); return; } c.textContent = GLYPH[(Math.random() * GLYPH.length) | 0]; }, 60); });
        return;
      }
      if (kind === 'scan') { n.classList.add('scanning'); requestAnimationFrame(function () { n.classList.add('on'); }); setTimeout(function () { n.classList.remove('scanning'); }, 1000); return; }
      requestAnimationFrame(function () { n.classList.add('on'); });
      if (kind === 'glow') setTimeout(function () { n.classList.add('settle'); }, 2000);
    }
  }
  // ---- 数字 ----
  function countUp(el, to, ms, fmt) {
    if (!el) return; var from = +(el.getAttribute('data-from') || 0), t0 = performance.now(), D = reduce ? 1 : (ms || 600);
    (function f(now) { var p = Math.min(1, (now - t0) / D), e = 1 - Math.pow(1 - p, 3), v = from + (to - from) * e; el.textContent = fmt ? fmt(v) : Math.round(v); if (p < 1 && el.isConnected) requestAnimationFrame(f); })(t0);
  }
  // 数字滚轮:每一位一列 0–9
  function roll(el, value) {
    if (!el) return; var s = String(value), cols = el.querySelectorAll('.dr__col');
    if (cols.length !== s.length) { el.innerHTML = s.split('').map(function () { return '<span class="dr__col"><span class="dr__strip">0<br>1<br>2<br>3<br>4<br>5<br>6<br>7<br>8<br>9</span></span>'; }).join(''); cols = el.querySelectorAll('.dr__col'); }
    [].forEach.call(cols, function (c, i) { var strip = c.firstChild; strip.style.transitionDelay = (s.length - 1 - i) * 30 + 'ms'; requestAnimationFrame(function () { strip.style.transform = 'translateY(' + (-s[i] * 10) + '%)'; }); });
  }

  // ---- 弹窗(居中)/ 关闭 ----
  function openPop(id) { var cur = PROTO.cur(), p = cur.querySelector('#' + id) || document.getElementById(id); if (!p) return; p.classList.add('open'); var k = p.getAttribute('data-enter'); if (k && G.on[k]) G.on[k](p); requestAnimationFrame(PROTO.placeMarks); }
  function closePop(el) { var p = el.closest('.gm-pop'); if (p) p.classList.remove('open'); requestAnimationFrame(PROTO.placeMarks); }

  var G = window.G = { STYLE_TEXT: STYLE_TEXT, STYLES: STYLES, PAL: PAL, PALS: PALS, state: state, apply: apply, tx: tx, fxLine: fxLine, chars: chars, playFx: playFx, countUp: countUp, roll: roll, openPop: openPop, closePop: closePop, reduce: reduce, on: {}, isLight: isLight };
  // 每屏进入钩子:G.on[屏id](section)
  var prev = window.ON_ENTER;
  window.ON_ENTER = function (id) { if (prev) prev(id); apply(); var s = PROTO.cur(); if (G.on[id]) setTimeout(function () { G.on[id](s); }, 30); };
  window.LOOK = { mount: mount, apply: apply, state: state };
})();
