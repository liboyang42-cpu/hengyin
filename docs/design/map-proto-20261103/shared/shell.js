// 原型外壳:左栏屏目录、中间 390 宽手机(微信状态栏 + 胶囊)、右栏改动说明。
// 屏 = <section class="scr" data-id data-title data-group data-src>;改动 = 元素上 data-mk="n",说明写在屏内 <ol class="notes"><li data-n="n">。
// 交互:data-go="屏id" 跳转 · data-back 返回 · data-sheet="sheetId"(+data-detent=peek|half|full) 开 iOS sheet · data-close 关
//       data-toast="文字" · data-tab="组:键" 切同屏分段(配 data-pane="组:键") · data-act="函数名" 调 window.ACT[函数名](el, ev)
(function () {
  var pageTitle = document.title;
  var screens = [].slice.call(document.querySelectorAll('#screens > .scr'));
  var shell = document.createElement('div');
  shell.className = 'shx';
  shell.innerHTML =
    '<nav class="sh-l"><h1>' + pageTitle + '</h1><a class="home" href="index.html">← 原型目录</a><div id="shList"></div></nav>' +
    '<main class="sh-c"><div class="sh-bar"><span class="ttl" id="shTtl"></span>' +
    '<label class="tg"><input type="checkbox" id="shMk"><i></i>显示改动</label>' +
    '<span class="seg" id="shTheme"><button data-t="">外壳跟随系统</button><button data-t="light">浅</button><button data-t="dark">深</button></span></div>' +
    '<div class="sh-stage" id="shStage"><div class="sh-fit" id="shFit"><div class="phone" id="phone"><div class="viewport" id="vp">' +
    '<div class="island"></div><div class="sbar"><span>9:41</span><span class="ic">' +
    '<svg width="18" height="12" viewBox="0 0 18 12" fill="currentColor"><rect x="0" y="8" width="3" height="4" rx="1"/><rect x="5" y="5" width="3" height="7" rx="1"/><rect x="10" y="2.5" width="3" height="9.5" rx="1"/><rect x="15" y="0" width="3" height="12" rx="1"/></svg>' +
    '<svg width="16" height="12" viewBox="0 0 16 12" fill="currentColor"><path d="M8 2.4c2.3 0 4.4.9 6 2.4l1.2-1.3A10.3 10.3 0 0 0 8 .6 10.3 10.3 0 0 0 .8 3.5L2 4.8a8.6 8.6 0 0 1 6-2.4Zm0 3.5c1.4 0 2.6.5 3.6 1.4l1.2-1.3A7 7 0 0 0 8 4.1 7 7 0 0 0 3.2 6l1.2 1.3c1-.9 2.2-1.4 3.6-1.4Zm0 3.4c.6 0 1.1.2 1.5.6L8 11.6 6.5 9.9c.4-.4.9-.6 1.5-.6Z"/></svg>' +
    '<svg width="27" height="13" viewBox="0 0 27 13" fill="none"><rect x=".5" y=".5" width="23" height="12" rx="3.5" stroke="currentColor" opacity=".4"/><rect x="2" y="2" width="20" height="9" rx="2" fill="currentColor"/><path d="M25 4.5v4c.8-.3 1.3-1.1 1.3-2s-.5-1.7-1.3-2Z" fill="currentColor" opacity=".5"/></svg>' +
    '</span></div><div class="cap"><span class="d"><i></i><i></i><i></i></span><span class="sep"></span><span class="o"><b></b></span></div>' +
    '<div class="homebar"></div><div class="wx-toast" id="toast"></div><div id="mkLayer"></div></div></div></div></div></main>' +
    '<aside class="sh-r" id="shR"></aside>';
  document.body.appendChild(shell);
  var vp = document.getElementById('vp'), phone = document.getElementById('phone');
  screens.forEach(function (s) { vp.insertBefore(s, vp.firstChild); });
  document.getElementById('screens').remove();

  // 左栏
  var list = document.getElementById('shList'), lastG = null;
  screens.forEach(function (s) {
    var g = s.getAttribute('data-group') || '';
    if (g !== lastG) { var h = document.createElement('div'); h.className = 'sh-g'; h.textContent = g; list.appendChild(h); lastG = g; }
    var b = document.createElement('button'); b.className = 'sh-i'; b.textContent = s.getAttribute('data-title') || s.id;
    b.setAttribute('data-to', s.getAttribute('data-id')); list.appendChild(b);
    b.onclick = function () { stack = []; show(s.getAttribute('data-id')); };
  });

  var cur = null, stack = [];
  function byId(id) { return screens.filter(function (s) { return s.getAttribute('data-id') === id; })[0]; }
  function isLight(s) { return /theme-merchant|theme-light|theme-topic-editor|is-light/.test(s.className); }
  function show(id, push) {
    var s = byId(id) || screens[0];
    if (cur && push) stack.push(cur.getAttribute('data-id'));
    screens.forEach(function (x) { x.classList.toggle('on', x === s); });
    vp.querySelectorAll('.ios-sheet.open').forEach(function (x) { x.classList.remove('open'); });
    cur = s;
    phone.classList.toggle('lt', isLight(s));
    document.getElementById('shTtl').textContent = s.getAttribute('data-title') || '';
    [].forEach.call(list.querySelectorAll('.sh-i'), function (b) { b.classList.toggle('on', b.getAttribute('data-to') === s.getAttribute('data-id')); });
    if (history.replaceState) history.replaceState(null, '', location.pathname + location.search + '#' + s.getAttribute('data-id'));
    renderNotes();
    var o = s.getAttribute('data-open');
    if (o) openSheet(o);
    if (window.ON_ENTER) window.ON_ENTER(s.getAttribute('data-id'));
    requestAnimationFrame(placeMarks);
  }
  function renderNotes() {
    var r = document.getElementById('shR'), s = cur;
    var notes = s.querySelector('.notes');
    var html = '<h2>' + (s.getAttribute('data-title') || '') + '</h2>';
    if (s.getAttribute('data-src')) html += '<p class="src">复刻自 master:' + s.getAttribute('data-src') + '</p>';
    html += '<h3>本屏改动</h3>';
    html += notes && notes.children.length ? '<ol>' + [].map.call(notes.children, function (li) {
      return '<li data-n="' + li.getAttribute('data-n') + '"><b class="n">' + li.getAttribute('data-n') + '</b><span>' + li.innerHTML + '</span></li>';
    }).join('') + '</ol>' : '<p class="none">无改动(与 master 相同,作对照)</p>';
    r.innerHTML = html;
    [].forEach.call(r.querySelectorAll('li'), function (li) {
      li.onmouseenter = function () { hl(li.getAttribute('data-n'), true); };
      li.onmouseleave = function () { hl(li.getAttribute('data-n'), false); };
    });
  }
  function hl(n, on) {
    [].forEach.call(cur.querySelectorAll('[data-mk="' + n + '"]'), function (el) { el.classList.toggle('mk-hl', on); });
    [].forEach.call(document.querySelectorAll('#shR li'), function (li) { li.classList.toggle('hl', on && li.getAttribute('data-n') === n); });
  }
  // 标注圆点:贴在被标元素左上角;元素不可见(display:none / 滚出视口)不画
  function placeMarks() {
    var layer = document.getElementById('mkLayer'); layer.innerHTML = '';
    if (!cur) return;
    var scale = phone.getBoundingClientRect().width / 390, base = vp.getBoundingClientRect();
    var seen = {};
    var open = [].slice.call(vp.querySelectorAll('.ios-sheet.open'));
    var scopes = open.length ? open : [cur];
    scopes.forEach(function (root) {
      [].forEach.call(root.querySelectorAll('[data-mk]'), function (el) {
        var r = el.getBoundingClientRect();
        if (!r.width && !r.height) return;
        var x = (r.left - base.left) / scale, y = (r.top - base.top) / scale;
        if (y < -4 || y > 844 || x > 390) return;
        var n = el.getAttribute('data-mk'), key = n + '@' + Math.round(x / 30) + ',' + Math.round(y / 30);
        if (seen[key]) return; seen[key] = 1;
        var d = document.createElement('div'); d.className = 'mk-dot'; d.textContent = n;
        d.style.left = Math.max(9, Math.min(381, x)) + 'px'; d.style.top = Math.max(9, y) + 'px';
        d.onmouseenter = function () { hl(n, true); }; d.onmouseleave = function () { hl(n, false); };
        layer.appendChild(d);
      });
    });
  }
  window.PROTO = { show: show, placeMarks: placeMarks, toast: toast, openSheet: openSheet, closeSheets: closeSheets, cur: function () { return cur; } };

  function toast(t) { var el = document.getElementById('toast'); el.textContent = t; el.classList.add('show'); clearTimeout(toast._t); toast._t = setTimeout(function () { el.classList.remove('show'); }, 1600); }
  function openSheet(id, detent) {
    var sh = cur.querySelector('#' + id) || vp.querySelector('#' + id); if (!sh) return;
    if (detent) sh.setAttribute('data-detent', detent);
    sh.classList.add('open'); requestAnimationFrame(placeMarks); setTimeout(placeMarks, 320);
  }
  function closeSheets(el) {
    var sh = el && el.closest ? el.closest('.ios-sheet') : null;
    (sh ? [sh] : [].slice.call(vp.querySelectorAll('.ios-sheet.open'))).forEach(function (x) { x.classList.remove('open'); });
    requestAnimationFrame(placeMarks);
  }

  vp.addEventListener('click', function (ev) {
    var t = ev.target.closest('[data-go],[data-back],[data-sheet],[data-close],[data-toast],[data-tab],[data-act],.ios-sheet .mask');
    if (!t || !vp.contains(t)) return;
    if (t.classList.contains('mask')) { closeSheets(t); return; }
    if (t.hasAttribute('data-act') && window.ACT && ACT[t.getAttribute('data-act')]) { ACT[t.getAttribute('data-act')](t, ev); requestAnimationFrame(placeMarks); return; }
    if (t.hasAttribute('data-tab')) {
      var v = t.getAttribute('data-tab'), grp = v.split(':')[0];
      [].forEach.call(cur.querySelectorAll('[data-tab^="' + grp + ':"]'), function (x) { x.classList.toggle(x.getAttribute('data-on-class') || 'is-on', x.getAttribute('data-tab') === v); });
      [].forEach.call(cur.querySelectorAll('[data-pane^="' + grp + ':"]'), function (x) { x.hidden = x.getAttribute('data-pane') !== v; });
      requestAnimationFrame(placeMarks); return;
    }
    if (t.hasAttribute('data-close')) { closeSheets(t); return; }
    if (t.hasAttribute('data-sheet')) { openSheet(t.getAttribute('data-sheet'), t.getAttribute('data-detent')); return; }
    if (t.hasAttribute('data-back')) { closeSheets(); if (stack.length) show(stack.pop()); else toast('返回上一页'); return; }
    if (t.hasAttribute('data-go')) { closeSheets(); show(t.getAttribute('data-go'), true); return; }
    if (t.hasAttribute('data-toast')) toast(t.getAttribute('data-toast'));
  });
  // sheet 抓手:点一下在 half / full 之间切
  vp.addEventListener('click', function (ev) {
    var g = ev.target.closest('.ios-sheet .grab'); if (!g) return;
    var sh = g.closest('.ios-sheet'), d = sh.getAttribute('data-detent');
    sh.setAttribute('data-detent', d === 'full' ? 'half' : (d === 'half' ? 'full' : 'half'));
    setTimeout(placeMarks, 320);
  });
  vp.addEventListener('scroll', function () { requestAnimationFrame(placeMarks); }, true);

  // 「显示改动」
  var mk = document.getElementById('shMk');
  mk.checked = /[?&]mk=1/.test(location.search) || localGet('dw29-mk') === '1';
  document.body.classList.toggle('mk', mk.checked);
  mk.onchange = function () { document.body.classList.toggle('mk', mk.checked); localSet('dw29-mk', mk.checked ? '1' : '0'); placeMarks(); };
  // 外壳主题
  var seg = document.getElementById('shTheme');
  function setTheme(t) { if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme');
    [].forEach.call(seg.children, function (b) { b.classList.toggle('on', b.getAttribute('data-t') === (t || '')); }); localSet('dw29-theme', t || ''); }
  [].forEach.call(seg.children, function (b) { b.onclick = function () { setTheme(b.getAttribute('data-t')); }; });
  setTheme(localGet('dw29-theme') || '');
  function localGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function localSet(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }

  // 手机缩放到舞台
  function fit() {
    var st = document.getElementById('shStage'), f = document.getElementById('shFit');
    var s = Math.min(1, (st.clientHeight - 40) / 868, (st.clientWidth - 40) / 414);
    f.style.transform = 'translate(-50%,-50%) scale(' + s + ')';
    placeMarks();
  }
  window.addEventListener('resize', fit);
  fit();
  if (window.ACT && ACT.init) ACT.init();
  show((location.hash || '').slice(1) || screens[0].getAttribute('data-id'));
  window.addEventListener('load', function () { placeMarks(); });
})();
