// 风格 B · 精致插画:沿用业主空态插图的语言(白底形 + 粗黑描边 + 柠檬绿 #CDFF71),
// 比 v3 多三层:1) 物件下半有一层暖灰体积阴影 #E4E2DB;2) 一道白色高光;3) 地面软影。
// 一律原创 SVG,动画只用 transform / opacity(icons.css .ib--*),减少动态效果时只留终态。
(function () {
  var K = '#121213', L = '#CDFF71', W = '#FFFFFF', S = '#E4E2DB';
  var ST = 'stroke="' + K + '" stroke-width="4.5" stroke-linejoin="round" stroke-linecap="round"';
  var FW = 'fill="' + W + '" ' + ST, FL = 'fill="' + L + '" ' + ST, FK = 'fill="' + K + '" ' + ST, LN = 'fill="none" ' + ST;
  var HI = 'fill="none" stroke="' + W + '" stroke-width="3.5" stroke-linecap="round"';
  function star4(cx, cy, r, fill, sw) { return '<path class="sp" d="M' + cx + ' ' + (cy - r) + 'Q' + (cx + r * .16) + ' ' + (cy - r * .16) + ' ' + (cx + r) + ' ' + cy + 'Q' + (cx + r * .16) + ' ' + (cy + r * .16) + ' ' + cx + ' ' + (cy + r) + 'Q' + (cx - r * .16) + ' ' + (cy + r * .16) + ' ' + (cx - r) + ' ' + cy + 'Q' + (cx - r * .16) + ' ' + (cy - r * .16) + ' ' + cx + ' ' + (cy - r) + 'Z" fill="' + (fill || L) + '" stroke="' + K + '" stroke-width="' + (sw == null ? 3.5 : sw) + '" stroke-linejoin="round"/>'; }
  function ground(w) { return '<ellipse class="gd" cx="60" cy="106" rx="' + (w || 30) + '" ry="5" fill="' + K + '" opacity=".10"/>'; }
  function scallop(cx, cy, r, n, amp) { var d = ''; for (var i = 0; i <= n * 2; i++) { var a = Math.PI * 2 * i / (n * 2) - Math.PI / 2, rr = r + (i % 2 ? amp : -amp * .2); d += (i ? 'L' : 'M') + (cx + Math.cos(a) * rr).toFixed(1) + ' ' + (cy + Math.sin(a) * rr).toFixed(1); } return d + 'Z'; }
  function star5(cx, cy, r, fill) { var d = ''; for (var i = 0; i < 10; i++) { var a = -Math.PI / 2 + Math.PI * i / 5, rr = i % 2 ? r * .45 : r; d += (i ? 'L' : 'M') + (cx + Math.cos(a) * rr).toFixed(1) + ' ' + (cy + Math.sin(a) * rr).toFixed(1); } return '<path d="' + d + 'Z" fill="' + (fill || K) + '" stroke="' + K + '" stroke-width="3" stroke-linejoin="round"/>'; }
  var D = {
    clue: ground(28) +
      '<g class="mg"><path d="M74 74 102 102" stroke="' + K + '" stroke-width="14" stroke-linecap="round"/><path d="M80 80 96 96" ' + HI + ' stroke-width="2.5"/>' +
      '<circle cx="50" cy="50" r="30" ' + FL + '/><circle cx="50" cy="50" r="22" fill="' + W + '"/><path d="M50 28a22 22 0 0 1 0 44 22 22 0 0 0 0-44z" fill="' + S + '" opacity=".9"/><path d="M50 28a22 22 0 0 1 22 22" fill="none" stroke="' + K + '" stroke-width="0"/>' +
      '<circle cx="50" cy="50" r="22" ' + LN + ' stroke-width="3.5"/><path d="M36 44c2-6 7-10 13-11" ' + HI + '/>' +
      '<circle cx="44" cy="54" r="2.4" fill="' + K + '"/><circle cx="51" cy="50" r="2.4" fill="' + K + '"/><circle class="pip" cx="58" cy="56" r="6" ' + FL + ' stroke-width="3.5"/></g>' + star4(22, 24, 8) + star4(92, 36, 5, W, 3),
    badge: ground(26) +
      '<g class="rib"><path d="M46 62 34 106l14-7 6 10 6-46z" ' + FL + '/><path d="M40 86l10-3" ' + LN + '/><path d="M74 62l12 44-14-7-6 10-6-46z" ' + FL + '/><path d="M80 86l-10-3" ' + LN + '/></g>' +
      '<g class="md"><path d="' + scallop(60, 50, 28, 12, 3.5) + '" ' + FW + '/><path d="M60 22a28 28 0 0 1 0 56z" fill="' + S + '" opacity=".8"/><path d="' + scallop(60, 50, 28, 12, 3.5) + '" ' + LN + '/>' +
      '<circle cx="60" cy="50" r="19" ' + FL + ' stroke-width="3.5"/><path d="M48 44c2-5 6-8 11-9" ' + HI + '/>' + star5(60, 50, 10) + '</g>' + star4(96, 30, 6, W, 3),
    key: ground(30) +
      '<g class="kb"><path d="M56 56 98 98" stroke="' + K + '" stroke-width="16" stroke-linecap="round"/><path d="M56 56 98 98" stroke="' + W + '" stroke-width="8" stroke-linecap="round"/><path d="M60 50l-4 4" ' + HI + '/>' +
      '<path d="M84 72l12-12M94 82l12-12" stroke="' + K + '" stroke-width="12" stroke-linecap="round"/><path d="M84 72l12-12M94 82l12-12" stroke="' + W + '" stroke-width="4.5" stroke-linecap="round"/>' +
      '<circle cx="40" cy="40" r="24" ' + FL + '/><path d="M40 16a24 24 0 0 1 0 48z" fill="#B9EA60" opacity=".9"/><circle cx="40" cy="40" r="24" ' + LN + '/><circle cx="40" cy="40" r="11" ' + FW + '/><circle cx="40" cy="40" r="4" fill="' + K + '"/><path d="M26 32c2-5 6-9 11-10" ' + HI + '/></g>' + star4(90, 26, 7) + star4(24, 84, 5, W, 3),
    success: ground(30) +
      '<g class="tile"><rect x="26" y="24" width="68" height="68" rx="20" ' + FL + '/><path d="M60 24h14c11 0 20 9 20 20v28c0 11-9 20-20 20H60z" fill="#B9EA60" opacity=".85"/><rect x="26" y="24" width="68" height="68" rx="20" ' + LN + '/><path d="M34 40c1-5 5-9 10-10" ' + HI + '/>' +
      '<path class="ck" d="M42 60l12 12 24-26" fill="none" stroke="' + K + '" stroke-width="15" stroke-linecap="round" stroke-linejoin="round"/><path class="ck" d="M42 60l12 12 24-26" fill="none" stroke="' + W + '" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/></g>' +
      '<g class="burst">' + star4(100, 24, 8, W, 3.5) + star4(18, 70, 6, W, 3) + '<circle cx="104" cy="60" r="3" fill="' + K + '"/><circle cx="24" cy="30" r="3" fill="' + K + '"/></g>',
    fail: ground(30) +
      '<g class="tile"><path d="M30 44c0-11 9-20 20-20h26l-8 36 6 32H50c-11 0-20-9-20-20z" ' + FW + '/><path class="half" d="M76 24c9 0 18 9 18 20v28c0 11-9 20-20 20h-6l-6-32z" ' + FW + '/><path d="M70 92l4 0c11 0 20-9 20-20V44c0-11-9-20-20-20" fill="' + S + '" opacity=".8"/><path class="half" d="M76 24c9 0 18 9 18 20v28c0 11-9 20-20 20h-6l-6-32z" ' + LN + '/><path d="M38 40c1-5 5-9 10-10" ' + HI + '/>' +
      '<path class="xx" d="M46 46l26 26M72 46L46 72" fill="none" stroke="' + K + '" stroke-width="13" stroke-linecap="round"/><path class="xx" d="M46 46l26 26M72 46L46 72" fill="none" stroke="' + W + '" stroke-width="4.5" stroke-linecap="round"/></g>' +
      '<g class="puff"><path d="M96 98c-6 0-9-4-9-8 0-5 4-8 9-7 2-6 9-8 14-5 5 2 6 7 5 12 3 1 5 4 4 8H96z" ' + FW + ' stroke-width="3.5"/></g>' + star4(22, 30, 5, S, 3),
    wait: ground(26) +
      '<g class="hg"><rect x="34" y="18" width="52" height="10" rx="5" ' + FK + '/><rect x="34" y="92" width="52" height="10" rx="5" ' + FK + '/>' +
      '<path d="M42 28h36c0 16-8 24-14 30 6 6 14 14 14 34H42c0-20 8-28 14-34-6-6-14-14-14-30z" ' + FW + '/><path d="M60 28h18c0 16-8 24-14 30 6 6 14 14 14 34H60z" fill="' + S + '" opacity=".75"/><path d="M42 28h36c0 16-8 24-14 30 6 6 14 14 14 34H42c0-20 8-28 14-34-6-6-14-14-14-30z" ' + LN + '/>' +
      '<path class="sand-t" d="M50 40h20c-2 8-6 12-10 16-4-4-8-8-10-16z" fill="' + L + '"/><path class="sand-b" d="M46 92h28c0-10-6-16-14-20-8 4-14 10-14 20z" fill="' + L + '"/><circle class="grain" cx="60" cy="66" r="1.8" fill="' + K + '"/><circle class="grain g2" cx="60" cy="74" r="1.8" fill="' + K + '"/><path d="M47 36c1 6 4 10 7 14" ' + HI + '/></g>' + star4(100, 40, 6, W, 3) + star4(20, 62, 5, L, 3),
    team: ground(34) +
      '<g class="p p1"><path d="M14 98c0-14 10-24 24-24s24 10 24 24z" ' + FW + '/><circle cx="38" cy="54" r="13" ' + FW + '/></g>' +
      '<g class="p p3"><path d="M58 98c0-14 10-24 24-24s24 10 24 24z" ' + FW + '/><circle cx="82" cy="54" r="13" ' + FW + '/></g>' +
      '<g class="p p2"><path d="M30 100c0-17 13-30 30-30s30 13 30 30z" ' + FW + '/><path d="M60 70c17 0 30 13 30 30H60z" fill="' + S + '" opacity=".8"/><path d="M30 100c0-17 13-30 30-30s30 13 30 30z" ' + LN + '/><path d="M44 80c6-4 12-6 16-6s10 2 16 6l-4 10c-4-2-8-3-12-3s-8 1-12 3z" ' + FL + ' stroke-width="3.5"/>' +
      '<circle cx="60" cy="46" r="17" ' + FW + '/><path d="M60 29a17 17 0 0 1 0 34z" fill="' + S + '" opacity=".8"/><circle cx="60" cy="46" r="17" ' + LN + '/><path d="M50 40c1-4 4-7 8-8" ' + HI + '/><circle cx="55" cy="46" r="2" fill="' + K + '"/><circle cx="65" cy="46" r="2" fill="' + K + '"/><path d="M55 52c3 2 7 2 10 0" ' + LN + ' stroke-width="3"/></g>' + star4(100, 34, 6) + star4(18, 30, 5, W, 3),
    pass: ground(30) +
      '<g class="card"><path d="M30 34c0-5 4-9 9-9h42c5 0 9 4 9 9v18a6 6 0 0 0 0 12v30c0 5-4 9-9 9H39c-5 0-9-4-9-9V64a6 6 0 0 0 0-12z" ' + FW + '/><path d="M60 25h21c5 0 9 4 9 9v18a6 6 0 0 0 0 12v30c0 5-4 9-9 9H60z" fill="' + S + '" opacity=".7"/>' +
      '<path d="M30 34c0-5 4-9 9-9h42c5 0 9 4 9 9v9H30z" fill="' + L + '"/><path d="M30 34c0-5 4-9 9-9h42c5 0 9 4 9 9v18a6 6 0 0 0 0 12v30c0 5-4 9-9 9H39c-5 0-9-4-9-9V64a6 6 0 0 0 0-12z" ' + LN + '/><path d="M30 43h60" ' + LN + ' stroke-width="3.5"/>' +
      '<path d="M38 58h30M38 68h20" stroke="' + K + '" stroke-width="4" stroke-linecap="round"/><path d="M42 86h6M52 86h3M59 86h8M71 86h3M78 86h4" stroke="' + K + '" stroke-width="4" stroke-linecap="round"/>' + star5(78, 66, 7, L) +
      '<rect x="52" y="12" width="16" height="10" rx="5" ' + FK + ' stroke-width="3"/><path d="M60 12V4" ' + LN + ' stroke-width="3.5"/><path d="M34 30c1-2 3-3 5-3" ' + HI + '/></g>' + star4(100, 46, 7) + star4(22, 70, 5, W, 3),
    points: ground(34) +
      '<g class="c c1"><path d="M26 86c0-7 15-12 34-12s34 5 34 12v8c0 7-15 12-34 12S26 101 26 94z" ' + FW + '/><ellipse cx="60" cy="86" rx="34" ry="12" ' + FW + '/></g>' +
      '<g class="c c2"><path d="M26 70c0-7 15-12 34-12s34 5 34 12v8c0 7-15 12-34 12S26 85 26 78z" ' + FW + '/><path d="M60 58c19 0 34 5 34 12v8c0 7-15 12-34 12z" fill="' + S + '" opacity=".7"/><ellipse cx="60" cy="70" rx="34" ry="12" ' + FW + '/></g>' +
      '<g class="c c3"><path d="M26 52c0-7 15-12 34-12s34 5 34 12v8c0 7-15 12-34 12S26 67 26 60z" ' + FL + '/><path d="M60 40c19 0 34 5 34 12v8c0 7-15 12-34 12z" fill="#B9EA60" opacity=".85"/><ellipse cx="60" cy="52" rx="34" ry="12" ' + FL + '/><ellipse cx="60" cy="52" rx="22" ry="7" ' + LN + ' stroke-width="3"/>' + star5(60, 52, 6) + '<path d="M34 48c3-3 8-5 14-6" ' + HI + '/></g>' + star4(102, 30, 7, W, 3.5) + star4(18, 36, 5, L, 3),
    seal: ground(32) +
      '<g class="imp"><rect x="30" y="80" width="60" height="22" rx="6" ' + FL + '/><path d="M40 91h40" ' + LN + ' stroke-dasharray="3 5" stroke-width="3.5"/></g>' +
      '<g class="stp"><rect x="48" y="14" width="24" height="34" rx="10" ' + FW + '/><path d="M60 14h2c6 0 10 4 10 10v24H60z" fill="' + S + '" opacity=".8"/><rect x="48" y="14" width="24" height="34" rx="10" ' + LN + '/><path d="M53 24c0-3 2-5 5-5" ' + HI + '/>' +
      '<path d="M36 50h48c6 0 10 4 10 10v10H26V60c0-6 4-10 10-10z" ' + FW + '/><path d="M60 50h24c6 0 10 4 10 10v10H60z" fill="' + S + '" opacity=".8"/><path d="M36 50h48c6 0 10 4 10 10v10H26V60c0-6 4-10 10-10z" ' + LN + '/>' + star5(60, 60, 5, L) + '</g>' + star4(100, 28, 7) + star4(20, 54, 5, W, 3),
  };
  var NAMES = { clue: '线索', badge: '勋章', key: '钥匙', success: '成功', fail: '失败', wait: '调查中', team: '队伍', pass: '通行证', points: '积分', seal: '章节印' };
  var LIST = ['clue', 'badge', 'key', 'success', 'fail', 'wait', 'team', 'pass', 'points', 'seal'];
  function img(name, o) {
    o = o || {}; var sz = o.size || 64;
    return '<span class="ib ib--' + name + (o.cls ? ' ' + o.cls : '') + '" style="width:' + sz + 'px;height:' + sz + 'px;--ic-d:' + (o.d || 0) + 'ms"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '><svg viewBox="0 0 120 120">' + D[name] + '</svg></span>';
  }
  window.IB = { img: img, names: NAMES, list: LIST, src: D };
})();
