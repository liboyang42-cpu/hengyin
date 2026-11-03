// 审阅层:每屏右栏的每一条改动 = 一项要业主看的东西。可以选「通过 / 要改 / 有疑问」,写批注,点「保存」才存(不自动保存)。
// 存在这个 artifact 的共享数据库 reviews/<屏id>~<序号>,Claude 那边直接读;没登录或数据库不可用时只能看,不能存。
(function () {
  var STATUS = [['ok', '通过'], ['change', '要改'], ['ask', '有疑问']];
  var docs = {};          // id -> data,来自数据库
  var drafts = {};        // id -> { status, note },只在本页内存,点保存才写库
  var db = null, me = '', ready = false, failed = false;
  var screens = [].slice.call(document.querySelectorAll('#vp > .scr'));
  var shR = document.getElementById('shR'), list = document.getElementById('shList');

  function itemsOf(screen) {
    var ol = screen.querySelector('.notes');
    return ol ? [].map.call(ol.children, function (li) { return { n: li.getAttribute('data-n'), html: li.innerHTML, text: li.textContent.slice(0, 120) }; }) : [];
  }
  function key(screen, n) { return screen.getAttribute('data-id') + '~' + n; }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function when(t) { if (!t) return ''; var d = new Date(t); return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); }

  // ---- 左栏:每屏「已看 / 共几项」 ----
  function badges() {
    [].forEach.call(list.querySelectorAll('.sh-i'), function (b) {
      var s = screens.filter(function (x) { return x.getAttribute('data-id') === b.getAttribute('data-to'); })[0];
      var items = s ? itemsOf(s) : [];
      var old = b.querySelector('.rv-badge'); if (old) old.remove();
      if (!items.length) return;
      var done = items.filter(function (it) { var d = docs[key(s, it.n)]; return d && d.status; }).length;
      var change = items.some(function (it) { var d = docs[key(s, it.n)]; return d && (d.status === 'change' || d.status === 'ask'); });
      var el = document.createElement('span');
      el.className = 'rv-badge' + (done === items.length ? ' is-done' : '') + (change ? ' is-flag' : '');
      el.textContent = done + '/' + items.length; b.appendChild(el);
    });
    var total = 0, seen = 0;
    screens.forEach(function (s) { itemsOf(s).forEach(function (it) { total++; var d = docs[key(s, it.n)]; if (d && d.status) seen++; }); });
    var sum = document.getElementById('rvSum');
    if (!sum) { sum = document.createElement('div'); sum.id = 'rvSum'; sum.className = 'rv-sum'; list.parentNode.insertBefore(sum, list); }
    sum.innerHTML = '<b>' + seen + '</b> / ' + total + ' 项已审' + (db ? '' : (failed || ready ? '<span class="rv-off">批注需要在 claude.ai 登录后才能保存</span>' : ''));
  }

  // ---- 顶栏:这一屏几处要看 ----
  function banner(screen) {
    var bar = document.querySelector('.sh-bar'), el = document.getElementById('rvBanner');
    if (!el) { el = document.createElement('span'); el.id = 'rvBanner'; el.className = 'rv-banner'; bar.insertBefore(el, bar.children[1]); }
    var items = itemsOf(screen);
    if (!items.length) { el.textContent = '这一屏是改前对照,不用审'; el.className = 'rv-banner is-quiet'; return; }
    var done = items.filter(function (it) { var d = docs[key(screen, it.n)]; return d && d.status; }).length;
    el.className = 'rv-banner' + (done === items.length ? ' is-done' : '');
    el.textContent = done === items.length ? '这一屏 ' + items.length + ' 处都审过了' : '这一屏 ' + items.length + ' 处要你看 · 已审 ' + done;
  }

  // ---- 右栏:每一条改动下面加审阅控件 ----
  function render(screen) {
    banner(screen);
    var items = itemsOf(screen);
    var lis = shR.querySelectorAll('ol > li');
    items.forEach(function (it, i) {
      var li = lis[i]; if (!li) return;
      var id = key(screen, it.n), saved = docs[id] || {}, draft = drafts[id] || { status: saved.status || '', note: saved.note || '' };
      var dirty = draft.status !== (saved.status || '') || draft.note !== (saved.note || '');
      var box = document.createElement('div'); box.className = 'rv-box' + (saved.status ? ' is-' + saved.status : '');
      box.setAttribute('data-id', id);
      box.innerHTML = '<div class="rv-st">' + STATUS.map(function (s) {
        return '<button type="button" class="rv-chip rv-chip--' + s[0] + (draft.status === s[0] ? ' on' : '') + '" data-st="' + s[0] + '">' + s[1] + '</button>';
      }).join('') + '</div>' +
        '<textarea class="rv-note" id="rv-' + id.replace(/[^a-z0-9]/gi, '_') + '" rows="2" placeholder="写批注(可选)">' + esc(draft.note) + '</textarea>' +
        '<div class="rv-foot"><span class="rv-meta">' + (dirty ? '还没保存' : (saved.updatedAt ? '已保存 · ' + when(saved.updatedAt) : '')) + '</span>' +
        '<button type="button" class="rv-save"' + (db && dirty ? '' : ' disabled') + '>保存</button></div>';
      li.querySelector('span').appendChild(box);
    });
  }
  function rerenderIfCurrent(force) {
    // 正在写批注时不重画右栏(别人保存或数据库推送时也一样),只更新左栏计数,免得吞掉正在打的字
    var a = document.activeElement;
    if (!force && a && a.classList && a.classList.contains('rv-note') && shR.contains(a)) { badges(); return; }
    var cur = window.PROTO && PROTO.cur(); if (cur) { shR.querySelectorAll('.rv-box').forEach(function (b) { b.remove(); }); render(cur); } badges(); }

  shR.addEventListener('click', function (e) {
    var box = e.target.closest('.rv-box'); if (!box) return;
    e.stopPropagation();
    var id = box.getAttribute('data-id'), saved = docs[id] || {};
    var d = drafts[id] || (drafts[id] = { status: saved.status || '', note: saved.note || '' });
    var chip = e.target.closest('.rv-chip');
    if (chip) { d.status = d.status === chip.getAttribute('data-st') ? '' : chip.getAttribute('data-st'); refreshBox(box, id); return; }
    if (e.target.closest('.rv-save')) save(box, id);
  });
  shR.addEventListener('input', function (e) {
    var box = e.target.closest('.rv-box'); if (!box || !e.target.classList.contains('rv-note')) return;
    var id = box.getAttribute('data-id'), saved = docs[id] || {};
    var d = drafts[id] || (drafts[id] = { status: saved.status || '', note: saved.note || '' });
    d.note = e.target.value; refreshFoot(box, id);
  });
  function refreshBox(box, id) {
    var d = drafts[id];
    box.querySelectorAll('.rv-chip').forEach(function (c) { c.classList.toggle('on', c.getAttribute('data-st') === d.status); });
    refreshFoot(box, id);
  }
  function refreshFoot(box, id) {
    var d = drafts[id], saved = docs[id] || {};
    var dirty = d.status !== (saved.status || '') || d.note !== (saved.note || '');
    box.querySelector('.rv-save').disabled = !db || !dirty;
    box.querySelector('.rv-meta').textContent = dirty ? '还没保存' : (saved.updatedAt ? '已保存 · ' + when(saved.updatedAt) : '');
  }
  function save(box, id) {
    var d = drafts[id]; if (!db || !d) return;
    var btn = box.querySelector('.rv-save'); btn.disabled = true; btn.textContent = '保存中…';
    var parts = id.split('~'), screen = screens.filter(function (s) { return s.getAttribute('data-id') === parts[0]; })[0];
    var it = itemsOf(screen).filter(function (x) { return x.n === parts[1]; })[0] || {};
    var data = { screen: parts[0], screenTitle: screen ? screen.getAttribute('data-title') : '', item: Number(parts[1]), itemText: it.text || '',
      status: d.status, note: d.note, updatedAt: Date.now(), by: me };
    db.collection('reviews').doc(id).set(data).then(function () {
      docs[id] = data; delete drafts[id]; btn.textContent = '保存';
      box.className = 'rv-box' + (data.status ? ' is-' + data.status : ''); refreshFoot(box, id); badges(); banner(PROTO.cur());
    }, function (err) {
      btn.textContent = '保存'; btn.disabled = false;
      box.querySelector('.rv-meta').textContent = err && err.code === 'permission_denied' ? '没有保存权限' : '没保存上,再点一次';
    });
  }

  // ---- 跟着换屏重画 ----
  var prev = window.ON_ENTER;
  window.ON_ENTER = function (id) { if (prev) prev(id); setTimeout(function () { rerenderIfCurrent(true); }, 0); };
  // 进来就把手机上的改动标注打开:标注就是「要你看的地方」
  var mk = document.getElementById('shMk'); if (mk && !mk.checked) { mk.checked = true; mk.onchange(); }
  rerenderIfCurrent();

  // ---- 数据库 ----
  var timer = setTimeout(function () { failed = true; badges(); }, 10000);
  if (window.claude && claude.use) {
    Promise.all([claude.use('db'), claude.use('user')]).then(function (r) {
      db = r[0]; var user = r[1];
      if (user) user.id().then(function (v) { me = v || ''; }, function () {});
      if (!db) { clearTimeout(timer); ready = true; rerenderIfCurrent(); return; }
      db.collection('reviews').onSnapshot(function (snap) {
        clearTimeout(timer); ready = true;
        docs = {}; snap.docs.forEach(function (doc) { docs[doc.id] = doc.data() || {}; });
        rerenderIfCurrent();
      }, function () { clearTimeout(timer); failed = true; db = null; rerenderIfCurrent(); });
    }, function () { clearTimeout(timer); failed = true; badges(); });
  } else { clearTimeout(timer); failed = true; badges(); }
})();
