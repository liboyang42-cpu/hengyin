// 公共拼屏助手(H):与 proto-game 同一套,只保留本原型用到的部分。
(function () {
  var esc = UI.esc;
  var SVG = {
    back: '<svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg>',
    x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    chev: '<svg viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>',
    repeat: '<svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 0 1 14-5l2 2M20 12a8 8 0 0 1-14 5l-2-2M18 4v4h-4M6 20v-4h4"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.6" fill="currentColor"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><circle cx="19" cy="12" r="1.6" fill="currentColor"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
  };
  var SCREENS = [];
  function add(id, title, group, cls, html, src, notes) { SCREENS.push([id, title, group, cls, html, src || '', notes || '']); }
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function top(title, o) {
    o = o || {};
    return '<div class="gm-top' + (o.cls ? ' ' + o.cls : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '>' + (o.back === false ? '' : '<span class="bk" ' + (o.backGo ? 'data-go="' + o.backGo + '"' : 'data-back') + '>' + SVG.back + '</span>') +
      (title ? '<span class="tt">' + esc(title) + '</span>' : '') + (o.right ? '<span class="ra" ' + (o.rightAttr || '') + '>' + o.right + '</span>' : '') + '</div>';
  }
  function gb(label, o) {
    o = o || {};
    var a = ' class="gb gb--' + (o.kind || 'primary') + (o.cls ? ' ' + o.cls : '') + '"';
    if (o.go) a += ' data-go="' + o.go + '"'; if (o.back) a += ' data-back';
    if (o.pop) a += ' data-act="pop" data-id="' + o.pop + '"'; if (o.close) a += ' data-act="closePop"';
    if (o.act) a += ' data-act="' + o.act + '"'; if (o.sheet) a += ' data-sheet="' + o.sheet + '"' + (o.detent ? ' data-detent="' + o.detent + '"' : '');
    if (o.toast) a += ' data-toast="' + esc(o.toast) + '"'; if (o.mk) a += ' data-mk="' + o.mk + '"'; if (o.id) a += ' id="' + o.id + '"'; if (o.data) a += ' ' + o.data;
    return '<div' + a + '>' + label + '</div>';
  }
  function foot(inner, o) { o = o || {}; return '<div class="gm-foot' + (o.row ? ' gm-foot--row' : '') + (o.cls ? ' ' + o.cls : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '>' + inner + '</div>'; }
  function pop(id, o) {
    o = o || {};
    return '<div class="gm-pop' + (o.cls ? ' ' + o.cls : '') + '" id="' + id + '"' + (o.enter ? ' data-enter="' + o.enter + '"' : '') + '><div class="mask"' + (o.close === false ? '' : ' data-act="closePop"') + '></div><div class="card' + (o.center ? ' card--center' : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '>' +
      (o.title || o.close !== false ? '<div class="card__h"><span class="card__t">' + (o.title || '') + '</span>' + (o.close === false ? '' : '<span class="x" data-act="closePop">' + SVG.x + '</span>') + '</div>' : '') +
      '<div class="card__b">' + (o.body || '') + '</div>' + (o.foot ? '<div class="card__f">' + o.foot + '</div>' : '') + '</div></div>';
  }
  function seg(items, act, mk) { return '<div class="gseg2"' + (mk ? ' data-mk="' + mk + '"' : '') + '>' + items.map(function (it, i) { return '<span class="it' + (i === 0 ? ' on' : '') + '" data-act="' + act + '" data-v="' + it[0] + '">' + it[1] + '</span>'; }).join('') + '</div>'; }
  function flush() {
    document.getElementById('screens').insertAdjacentHTML('beforeend', SCREENS.map(function (s) {
      return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
    }).join(''));
    SCREENS.length = 0;
  }
  window.H = { SVG: SVG, add: add, notes: notes, top: top, gb: gb, foot: foot, pop: pop, seg: seg, flush: flush, esc: esc };
  window.ACT = Object.assign(window.ACT || {}, {
    pop: function (el) { G.openPop(el.getAttribute('data-id')); },
    closePop: function (el) { G.closePop(el); },
    go: function (el) { PROTO.show(el.getAttribute('data-to'), true); },
    txgo: function (el) { var to = el.getAttribute('data-to'); G.tx(function () { PROTO.show(to, true); }); },
    segOn: function (el) { var box = el.closest('.gseg2'); [].forEach.call(box.children, function (c) { c.classList.toggle('on', c === el); }); },
    init: function () { LOOK.mount(); },
  });
})();
