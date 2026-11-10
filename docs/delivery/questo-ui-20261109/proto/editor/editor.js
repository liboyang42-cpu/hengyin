// 章节故事编辑器:顶部「添加」栏插入块;每个块长按(鼠标按住 350ms 也行)拖动到任意位置,
// 拖到一段正文中间会在句子之间拆段;邻居滑开让位;贴近上下边自动滚动;放下后可撤销。
// 全部用 pointer 事件,鼠标和手指同一套。
(function () {
  var esc = UI.esc, ic = D.ic;
  var LONG_MS = 350, TOL = 8, EDGE = 72, MAX_SPEED = 14;
  var TYPES = { p: '段落', img: '图片', audio: '音频', node: '玩法', branch: '分支任务', hr: '分割线' };
  var ADD = [['p', 'text', '段落'], ['img', 'image', '图片'], ['audio', 'audio', '音频'], ['node', 'game', '玩法'], ['branch', 'branch', '分支'], ['hr', 'hr', '分割线']];
  var chIdx = 0, selId = null, editId = null, undoStack = [], uid = 100, insertAfter = null;
  var scr, sv, list;

  function chapter() { return D.CHAPTERS[chIdx]; }
  function blocks() { return chapter().blocks; }
  function byId(id) { var b = blocks(); for (var i = 0; i < b.length; i++) if (b[i].id === id) return b[i]; return null; }
  function idx(id) { var b = blocks(); for (var i = 0; i < b.length; i++) if (b[i].id === id) return i; return -1; }
  function snap(label) { undoStack.push({ label: label, blocks: JSON.parse(JSON.stringify(blocks())) }); if (undoStack.length > 20) undoStack.shift(); }

  // ---------- 渲染 ----------
  function capStart() {
    var ch = chapter();
    return '<div class="ed-cap ed-cap--start" data-cap="start"><div class="ed-cap__cover" style="background-image:url(' + ch.cover + ')"><span class="ed-cap__tag">章节开始</span><span class="ed-cap__pv" data-act="edPreview" data-stage="chStart">' + ic('eye', 14, 2) + '预览弹窗</span></div>' +
      '<div class="ed-cap__body"><input class="ed-cap__name" value="' + esc(ch.name) + '" placeholder="章节名称" data-field="name"><textarea class="ed-cap__ta" rows="2" placeholder="开场白:玩家进入本章先看到的话" data-field="open">' + esc(ch.open) + '</textarea></div></div>';
  }
  function capEnd() {
    var ch = chapter();
    return '<div class="ed-cap ed-cap--end" data-cap="end"><div class="ed-cap__head"><span class="ed-cap__tag ed-cap__tag--end">章节结束</span><span class="ed-cap__pv" data-act="edPreview" data-stage="chEnd">' + ic('eye', 14, 2) + '预览弹窗</span></div>' +
      '<div class="ed-cap__body"><textarea class="ed-cap__ta" rows="2" placeholder="结语:本章完成时弹窗里的话" data-field="end">' + esc(ch.end) + '</textarea>' +
      '<div class="ed-cap__row"><span class="ed-cap__k">奖励</span><span class="ed-chip is-on">' + ic('medal', 13, 2) + esc(ch.reward || '线索') + '</span><span class="ed-chip ed-chip--add" data-toast="原型:选奖励">' + ic('plus', 12, 2.2) + '</span></div>' +
      '<div class="ed-cap__row"><span class="ed-cap__k">去向</span><span class="ed-chip is-on">下一章</span><span class="ed-chip" data-toast="原型:改为结束主题">结束主题</span></div></div></div>';
  }
  function blockHtml(b) {
    var sel = b.id === selId ? ' is-sel' : '', inner;
    if (b.type === 'p') inner = b.id === editId ? '<textarea class="blk__ta" data-ta="' + b.id + '" rows="1">' + esc(b.text) + '</textarea>' : '<div class="blk__text" data-text="' + b.id + '">' + (b.text ? esc(b.text) : '<span class="blk__ph">点一下写正文</span>') + '</div>';
    else if (b.type === 'img') { var n = b.layout || 1, cells = ''; for (var k = 0; k < n; k++) { var src = b.imgs[k]; cells += src ? '<div class="blk__cell" style="background-image:url(' + src + ')"></div>' : '<div class="blk__cell blk__cell--empty" data-act="edFillImg" data-id="' + b.id + '" data-k="' + k + '">' + ic('plus', 20, 2) + '<span>选图</span></div>'; }
      inner = '<div class="blk__grid blk__grid--' + n + '">' + cells + '</div><div class="blk__cap">' + esc(b.cap || '') + '</div>'; }
    else if (b.type === 'audio') inner = '<div class="blk__row"><span class="blk__tile blk__tile--audio">' + ic('play', 16, 2.2) + '</span><span class="blk__m"><b>' + esc(b.name) + '</b><i>音频 · ' + esc(b.dur) + ' · 读到这里才响</i></span></div>';
    else if (b.type === 'node') { var t = b.tpl ? D.tplOf(b.tpl) : null, g = t ? D.gameOf(t.game) : null;
      inner = '<div class="blk__row" data-act="edNode" data-id="' + b.id + '"><span class="blk__tile" style="--gc:' + (g ? g.color : '#999') + '">' + ic(g ? g.icon : 'game', 20, 1.9) + '</span><span class="blk__m"><b>' + esc(t ? t.title : '未选玩法模板') + '</b><i>' + (t ? esc(g.label + ' · ' + (t.by === '我' ? '我的模板' : '公共模板')) : '点开选择模板') + '</i></span><span class="blk__act">' + ic('right', 16, 2) + '</span></div>' +
        '<div class="blk__node"><span class="blk__nrow' + (b.place ? '' : ' is-warn') + '" data-act="edNode" data-id="' + b.id + '">' + ic('pin', 14, 2) + (b.place ? esc(b.place[0]) + ' <em>' + esc(b.place[1]) + '</em>' : '待选地点 · 一个玩法一个地点') + '</span>' + (b.photos && b.photos.length ? '<span class="blk__nrow">' + ic('image', 14, 2) + '照片 ' + b.photos.length + (t ? '/' + t.photos : '') + '</span>' : '') + '<span class="blk__nrow blk__nrow--pv" data-act="edPreview" data-stage="gStart">' + ic('eye', 14, 2) + '预览弹窗</span></div>'; }
    else if (b.type === 'branch') inner = '<div class="blk__brh">' + ic('branch', 15, 2) + '分支任务<span class="blk__act" data-act="edBranch" data-id="' + b.id + '">编辑</span><span class="blk__act" data-act="edPreview" data-stage="branch">预览</span></div><div class="blk__q">' + esc(b.q) + '</div>' +
      b.opts.map(function (o, i) { return '<div class="blk__opt"><span class="k">' + 'ABC'[i] + '</span><span class="t">' + esc(o.t) + '</span><span class="to">' + ic('right', 12, 2.2) + esc(o.to) + '</span></div>'; }).join('');
    else inner = '<div class="blk__hr"><i></i></div>';
    return '<div class="blk blk--' + b.type + sel + '" data-id="' + b.id + '"><div class="blk__body">' + inner + '</div><span class="blk__grip" aria-label="拖动">' + ic('grip', 16) + '</span>' + (sel ? '<span class="blk__x" data-act="edDelete" data-id="' + b.id + '">' + ic('close', 12, 2.4) + '</span>' : '') + '</div>';
  }
  function render(keepScroll) {
    if (!list) return;
    var top = sv ? sv.scrollTop : 0;
    list.innerHTML = capStart() + '<div class="ed-blocks" id="edBlocks">' + blocks().map(blockHtml).join('') + (blocks().length ? '' : '<div class="ed-empty">本章还没有内容。用上面的「添加」放进第一段。</div>') + '</div>' + capEnd();
    if (keepScroll !== false && sv) sv.scrollTop = top;
    var ta = list.querySelector('.blk__ta'); if (ta) { autoH(ta); ta.focus(); var n = ta.value.length; try { ta.setSelectionRange(n, n); } catch (e) {} }
    if (window.PROTO) requestAnimationFrame(PROTO.placeMarks);
  }
  function autoH(t) { t.style.height = 'auto'; t.style.height = Math.max(24, t.scrollHeight) + 'px'; }

  // ---------- 插入 / 删除 / 撤销 ----------
  function newBlock(type) {
    var id = 'n' + (uid++);
    if (type === 'p') return { id: id, type: 'p', text: '' };
    if (type === 'img') return { id: id, type: 'img', layout: 1, imgs: [], cap: '' };
    if (type === 'audio') return { id: id, type: 'audio', name: '新录音.m4a', dur: '0:18' };
    if (type === 'node') return { id: id, type: 'node', tpl: null, name: '', place: null, photos: [] };
    if (type === 'branch') return { id: id, type: 'branch', q: '接下来怎么走?', opts: [{ t: '选项 A', to: '继续本章' }, { t: '选项 B', to: '跳到第 2 章' }] };
    return { id: id, type: 'hr' };
  }
  function insertAt(block, at) {
    var b = blocks(); if (at == null || at < 0 || at > b.length) at = b.length;
    b.splice(at, 0, block);
    selId = block.id; editId = block.type === 'p' ? block.id : null;
    render();
    var el = list.querySelector('[data-id="' + block.id + '"]');
    if (el) { el.classList.add('is-new'); scrollToEl(el); setTimeout(function () { el.classList.remove('is-new'); }, 900); }
    undoBar('已添加' + TYPES[block.type]);
  }
  function insertIndex() {
    var b = blocks();
    if (selId && idx(selId) >= 0) return idx(selId) + 1;
    if (insertAfter && idx(insertAfter) >= 0) return idx(insertAfter) + 1;
    return b.length;
  }
  function scrollToEl(el) {
    var r = el.getBoundingClientRect(), s = sv.getBoundingClientRect(), k = scale();
    var topGap = 150 * k, botGap = 90 * k;
    if (r.top < s.top + topGap) sv.scrollTop -= (s.top + topGap - r.top) / k;
    else if (r.bottom > s.bottom - botGap) sv.scrollTop += (r.bottom - (s.bottom - botGap)) / k;
  }
  var undoT;
  function undoBar(label, canUndo) {
    var bar = scr.querySelector('.ed-undo');
    bar.innerHTML = '<span>' + esc(label) + '</span>' + (canUndo !== false && undoStack.length ? '<span class="ed-undo__b" data-act="edUndo">' + ic('undo', 14, 2.2) + '撤销</span>' : '');
    bar.classList.add('show'); clearTimeout(undoT); undoT = setTimeout(function () { bar.classList.remove('show'); }, 5000);
  }

  // ---------- 拖动 ----------
  var vp, phone;
  function scale() { return phone.getBoundingClientRect().width / 390; }
  function local(ev) { var r = vp.getBoundingClientRect(), k = scale(); return { x: (ev.clientX - r.left) / k, y: (ev.clientY - r.top) / k }; }
  function lrect(el) { var r = el.getBoundingClientRect(), b = vp.getBoundingClientRect(), k = scale(); return { left: (r.left - b.left) / k, top: (r.top - b.top) / k, width: r.width / k, height: r.height / k, bottom: (r.bottom - b.top) / k }; }

  var press = null, drag = null;
  function onDown(ev) {
    if (drag || (ev.pointerType === 'mouse' && ev.button !== 0)) return;
    var blk = ev.target.closest('.blk'); if (!blk || !list.contains(blk)) return;
    if (ev.target.closest('textarea,input,[data-act],[data-toast]')) return;
    var p = local(ev);
    press = { id: blk.getAttribute('data-id'), x: p.x, y: p.y, cx: ev.clientX, cy: ev.clientY, pid: ev.pointerId, el: blk, t: setTimeout(function () { startDrag(ev); }, ev.target.closest('.blk__grip') ? 120 : LONG_MS) };
    blk.classList.add('is-press');
  }
  function clearPress() { if (!press) return; clearTimeout(press.t); press.el.classList.remove('is-press'); press = null; }
  function startDrag(ev) {
    if (!press) return;
    var blk = press.el, r = lrect(blk), b = byId(press.id);
    if (editId) { editId = null; }
    drag = { id: press.id, block: b, H: r.height, W: r.width, left: r.left, grab: press.y - r.top, x: press.x, y: press.y, cx: press.cx, cy: press.cy, pid: press.pid, target: null, slot: null, speed: 0, raf: 0 };
    clearTimeout(press.t); press.el.classList.remove('is-press'); press = null;
    try { scr.setPointerCapture(drag.pid); } catch (e) {}
    // 幽灵:跟手的那一份
    var g = blk.cloneNode(true); g.classList.add('blk--ghost'); g.classList.remove('is-sel'); g.style.width = r.width + 'px'; g.style.left = r.left + 'px'; g.style.top = r.top + 'px';
    scr.appendChild(g); drag.ghost = g;
    requestAnimationFrame(function () { g.classList.add('is-lift'); });
    // 原位置先留一个同高的槽(不动画),原块隐藏
    blk.classList.add('is-src');
    var s = mkSlot(drag.H, true); blk.parentNode.insertBefore(s, blk.nextSibling); drag.slot = s;
    drag.target = { kind: 'gap', before: nextBlockId(blk), src: true };
    scr.classList.add('is-dragging'); scr.classList.add('has-dragged'); haptic(g, 'light');
    if (navigator.vibrate) { try { navigator.vibrate(10); } catch (e) {} }
  }
  function nextBlockId(el) { var n = el.nextElementSibling; while (n && (n.classList.contains('slot') || n.classList.contains('is-src'))) n = n.nextElementSibling; return n && n.classList.contains('blk') ? n.getAttribute('data-id') : null; }
  function mkSlot(h, open) { var s = document.createElement('div'); s.className = 'slot' + (open ? ' is-open no-anim' : ''); s.style.setProperty('--h', h + 'px'); s.innerHTML = '<i></i>'; return s; }
  function onMove(ev) {
    if (press) {
      var p0 = local(ev); if (Math.abs(p0.x - press.x) > TOL || Math.abs(p0.y - press.y) > TOL) clearPress();
      return;
    }
    if (!drag || ev.pointerId !== drag.pid) return;
    var p = local(ev); drag.x = p.x; drag.y = p.y; drag.cx = ev.clientX; drag.cy = ev.clientY;
    moveGhost(); retarget(); edgeScroll();
  }
  function moveGhost() { var y = Math.max(40, Math.min(844 - 40, drag.y - drag.grab)); drag.ghost.style.top = y + 'px'; }
  // 命中:指针在哪个块上,是段落中间还是块之间
  function retarget() {
    var els = [].slice.call(list.querySelectorAll('.blk:not(.is-src)')), y = drag.y, t = null;
    var slotR = drag.slot ? lrect(drag.slot) : null;
    if (slotR && y >= slotR.top && y <= slotR.bottom) return; // 停在槽上:不变
    for (var i = 0; i < els.length; i++) {
      var r = lrect(els[i]), id = els[i].getAttribute('data-id');
      if (y < r.top) { t = { kind: 'gap', before: id }; break; }
      if (y <= r.bottom) {
        var mid = r.top + r.height / 2, b = byId(id);
        if (b && b.type === 'p' && b.text && r.height > 44 && y > r.top + 12 && y < r.bottom - 12) {
          var at = splitAt(els[i], b);
          if (at > 0 && at < b.text.length) { t = { kind: 'split', id: id, at: at }; break; }
        }
        t = { kind: 'gap', before: y < mid ? id : nextBlockId(els[i]) }; break;
      }
    }
    if (!t) t = { kind: 'gap', before: null };
    if (same(t, drag.target)) return;
    applyTarget(t);
  }
  function same(a, b) { return a && b && a.kind === b.kind && a.before === b.before && a.id === b.id && a.at === b.at; }
  // 段落里离指针最近的句子边界(字符下标)
  function splitAt(el, b) {
    var text = b.text, bounds = [], re = /[。!?!?;;…]+|\n/g, m;
    while ((m = re.exec(text))) { var e = m.index + m[0].length; if (e > 0 && e < text.length) bounds.push(e); }
    if (!bounds.length) return -1;
    var off = caretOffset(el);
    if (off < 0) { var r = lrect(el); off = Math.round((drag.y - r.top) / r.height * text.length); }
    var best = bounds[0];
    bounds.forEach(function (x) { if (Math.abs(x - off) < Math.abs(best - off)) best = x; });
    return best;
  }
  function caretOffset(el) {
    var node, off;
    try {
      if (document.caretPositionFromPoint) { var cp = document.caretPositionFromPoint(drag.cx, drag.cy); if (!cp) return -1; node = cp.offsetNode; off = cp.offset; }
      else if (document.caretRangeFromPoint) { var cr = document.caretRangeFromPoint(drag.cx, drag.cy); if (!cr) return -1; node = cr.startContainer; off = cr.startOffset; }
      else return -1;
    } catch (e) { return -1; }
    if (!node || !el.contains(node)) return -1;
    if (node.nodeType !== 3) return -1;
    // 已被拆成两半显示时,下半段下标要加上上半段长度
    var half = node.parentNode.closest('.blk__text--b');
    var txt = el.querySelector('.blk__text--a');
    return off + (half && txt ? txt.textContent.length : 0);
  }
  function applyTarget(t) {
    var old = drag.slot, oldT = drag.target;
    // 还原上一次拆开的段落
    if (oldT && oldT.kind === 'split') { var pe = list.querySelector('[data-id="' + oldT.id + '"] .blk__body'); if (pe) { var pb = byId(oldT.id); pe.innerHTML = '<div class="blk__text" data-text="' + pb.id + '">' + esc(pb.text) + '</div>'; } }
    if (old) { old.classList.remove('no-anim'); old.classList.remove('is-open'); old.addEventListener('transitionend', function () { old.remove(); }); setTimeout(function () { if (old.parentNode) old.remove(); }, 260); }
    var s = mkSlot(drag.H, false);
    if (t.kind === 'split') {
      var pel = list.querySelector('[data-id="' + t.id + '"] .blk__body'), b = byId(t.id);
      pel.innerHTML = '<div class="blk__text blk__text--a" data-text="' + b.id + '">' + esc(b.text.slice(0, t.at)) + '</div><div class="blk__text blk__text--b" data-text="' + b.id + '">' + esc(b.text.slice(t.at)) + '</div>';
      pel.insertBefore(s, pel.querySelector('.blk__text--b'));
      s.classList.add('slot--in');
    } else {
      var host = list.querySelector('#edBlocks'), ref = t.before ? list.querySelector('[data-id="' + t.before + '"]') : null;
      host.insertBefore(s, ref);
    }
    void s.offsetHeight; s.classList.add('is-open');
    drag.slot = s; drag.target = t;
    haptic(drag.ghost, 'tick');
    if (navigator.vibrate) { try { navigator.vibrate(5); } catch (e) {} }
  }
  // 贴边自动滚动:越靠边越快
  function edgeScroll() {
    var r = lrect(sv), topZone = r.top + 132 + EDGE, botZone = r.bottom - 40 - EDGE, v = 0;
    if (drag.y < topZone) v = -Math.min(MAX_SPEED, Math.ceil((topZone - drag.y) / EDGE * MAX_SPEED));
    else if (drag.y > botZone) v = Math.min(MAX_SPEED, Math.ceil((drag.y - botZone) / EDGE * MAX_SPEED));
    drag.speed = v;
    if (v && !drag.raf) drag.raf = requestAnimationFrame(tick);
  }
  function tick() {
    if (!drag) return;
    drag.raf = 0;
    if (!drag.speed) return;
    var before = sv.scrollTop; sv.scrollTop = before + drag.speed;
    if (sv.scrollTop !== before) { retarget(); drag.raf = requestAnimationFrame(tick); scr.classList.add('is-autoscroll'); }
    else scr.classList.remove('is-autoscroll');
  }
  function onUp(ev) {
    if (press) { clearPress(); return; }
    if (!drag || ev.pointerId !== drag.pid) return;
    finish(ev.type !== 'pointercancel');
  }
  function finish(commit) {
    var d = drag; drag = null; scr.classList.remove('is-dragging'); scr.classList.remove('is-autoscroll');
    if (d.raf) cancelAnimationFrame(d.raf);
    try { scr.releasePointerCapture(d.pid); } catch (e) {}
    var t = d.target, slotR = d.slot ? lrect(d.slot) : null;
    var land = function () {
      if (commit && t && !t.src) {
        snap('move');
        var b = blocks(), from = idx(d.id), blk = b.splice(from, 1)[0];
        if (t.kind === 'split') {
          var pi = idx(t.id), p = b[pi], p2 = { id: 'n' + (uid++), type: 'p', text: p.text.slice(t.at).replace(/^\n/, '') };
          p.text = p.text.slice(0, t.at); b.splice(pi + 1, 0, blk, p2);
          selId = blk.id; render(); undoBar('已插到第 ' + (pi + 1) + ' 段中间,段落拆成两段');
        } else {
          var to = t.before ? idx(t.before) : b.length; b.splice(to, 0, blk);
          selId = blk.id; render(); undoBar('已移到第 ' + (to + 1) + ' 位');
        }
      } else render();
      if (d.ghost) d.ghost.remove();
    };
    if (slotR && d.ghost) {
      d.ghost.classList.remove('is-lift'); d.ghost.classList.add('is-land'); d.ghost.style.top = slotR.top + 'px'; d.ghost.style.left = (t.kind === 'split' ? slotR.left : d.left) + 'px'; d.ghost.style.width = slotR.width + 'px';
      haptic(d.ghost, 'medium'); if (navigator.vibrate) { try { navigator.vibrate(15); } catch (e) {} }
      setTimeout(land, 190);
    } else land();
  }
  // 震动的可视化:幽灵旁边闪一圈
  function haptic(el, kind) {
    if (!el) return;
    var h = document.createElement('span'); h.className = 'hapt hapt--' + kind; el.appendChild(h);
    requestAnimationFrame(function () { h.classList.add('go'); });
    setTimeout(function () { h.remove(); }, 420);
  }

  // ---------- 点击 / 编辑 ----------
  function onClick(ev) {
    if (drag) return;
    var tgt = ev.target;
    if (tgt.closest('[data-act],[data-toast],[data-sheet],[data-go]')) return;
    var blk = tgt.closest('.blk');
    if (!blk) { if (tgt.closest('.ed-cap')) return; if (selId || editId) { commitEdit(); selId = null; editId = null; render(); } return; }
    var id = blk.getAttribute('data-id'), b = byId(id);
    if (b.type === 'p') { if (editId !== id) { commitEdit(); editId = id; selId = id; render(); } return; }
    if (selId !== id) { commitEdit(); editId = null; selId = id; render(); }
  }
  function commitEdit() {
    if (!editId) return;
    var ta = list.querySelector('.blk__ta'), b = byId(editId);
    if (ta && b) { b.text = ta.value; if (!b.text.trim()) { blocks().splice(idx(b.id), 1); if (selId === b.id) selId = null; } }
    editId = null;
  }
  function onInput(ev) {
    var t = ev.target;
    if (t.classList.contains('blk__ta')) { autoH(t); return; }
    if (t.classList.contains('ed-cap__ta')) autoH(t);
    var f = t.getAttribute('data-field'); if (f) chapter()[f] = t.value;
  }
  function onKey(ev) {
    var t = ev.target;
    if (t.classList.contains('blk__ta') && ev.key === 'Enter' && !ev.shiftKey) {
      ev.preventDefault();
      var b = byId(editId), pos = t.selectionStart, rest = t.value.slice(pos);
      b.text = t.value.slice(0, pos);
      var nb = { id: 'n' + (uid++), type: 'p', text: rest };
      blocks().splice(idx(b.id) + 1, 0, nb); editId = nb.id; selId = nb.id; render();
    }
    if (ev.key === 'Escape' && drag) finish(false);
  }

  // ---------- 对外 ----------
  function mount() {
    scr = document.querySelector('.scr[data-id="ed-1"]'); if (!scr) return;
    sv = scr.querySelector('.ed-sv'); list = scr.querySelector('#edList'); vp = document.getElementById('vp'); phone = document.getElementById('phone');
    render(false);
    scr.addEventListener('pointerdown', onDown);
    scr.addEventListener('pointermove', onMove);
    scr.addEventListener('pointerup', onUp);
    scr.addEventListener('pointercancel', onUp);
    scr.addEventListener('click', onClick);
    scr.addEventListener('input', onInput);
    scr.addEventListener('keydown', onKey);
    scr.addEventListener('contextmenu', function (e) { if (e.target.closest('.blk')) e.preventDefault(); });
    vp.addEventListener('touchmove', function (e) { if (drag || press) e.preventDefault(); }, { passive: false });
    scr.addEventListener('focusout', function (e) { if (e.target.classList && e.target.classList.contains('blk__ta')) setTimeout(function () { if (!drag && editId && !list.contains(document.activeElement)) { commitEdit(); render(); } }, 120); });
  }
  window.ED = {
    chapter: chapter, blocks: blocks, mount: mount, render: render,
    setChapter: function (i) { chIdx = i; selId = null; editId = null; undoStack = []; render(false); },
    add: function (type) {
      commitEdit();
      if (type === 'node') { snap('add'); var nn = newBlock('node'); insertAt(nn, insertIndex()); if (window.NODE) NODE.open(nn.id, true); return; }
      if (type === 'img') { var at0 = insertIndex(); if (window.IMGL) IMGL.open(function (n) { var ib = newBlock('img'); ib.layout = n; snap('add'); insertAt(ib, at0); }); return; }
      if (type === 'branch') { snap('add'); var nb = newBlock('branch'); insertAt(nb, insertIndex()); if (window.BRANCH) BRANCH.open(nb.id); return; }
      snap('add'); insertAt(newBlock(type), insertIndex());
    },
    setTpl: function (id, key) { var b = byId(id); if (b) { snap('replace'); b.tpl = key; var t = D.tplOf(key); if (!b.name) b.name = t.title; render(); } },
    fillImg: function (id, k) { var b = byId(id); if (!b) return; b.imgs[k] = 'assets/covers/' + ['flavor-tour', 'heat-escape', 'wildlife-observation', 'treasure-hunter', 'life-backup', 'anomaly-coordinate'][(uid++) % 6] + '.jpg'; render(); },
    del: function (id) { var i = idx(id); if (i < 0) return; snap('delete'); var b = blocks().splice(i, 1)[0]; if (selId === id) selId = null; editId = null; render(); undoBar('已删除' + TYPES[b.type]); },
    undo: function () { var s = undoStack.pop(); if (!s) return; chapter().blocks = s.blocks; selId = null; editId = null; render(); undoBar('已撤销', false); },
    byId: byId, snap: snap, undoBar: undoBar,
    // 自检用:把一次拖动当成脚本跑(只给 puppeteer 用,不走 UI)
    _state: function () { return { drag: !!drag, press: !!press, blocks: blocks().map(function (b) { return b.type + ':' + (b.text ? b.text.slice(0, 6) : b.id); }) }; }
  };
  window.ACT = Object.assign(window.ACT || {}, {
    edAdd: function (el) { ED.add(el.getAttribute('data-type')); },
    edDelete: function (el) { ED.del(el.getAttribute('data-id')); },
    edUndo: function () { ED.undo(); },
    edPreview: function (el) { commitEdit(); PV.open(el.getAttribute('data-stage') || 'theme'); },
    edNode: function (el) { commitEdit(); NODE.open(el.getAttribute('data-id')); },
    edFillImg: function (el) { ED.fillImg(el.getAttribute('data-id'), +el.getAttribute('data-k')); },
    edBranch: function (el) { BRANCH.open(el.getAttribute('data-id')); },
    edDone: function () { commitEdit(); render(); PROTO.toast('已存本机 · 回到章节列表'); PROTO.show('ch-list', true); }
  });
  window.ED_ADD = ADD;
})();
