// 风格 B · v2 精致插画。根基仍是业主空态插图(白底形 + 粗黑描边 4.5 + 圆角端点),在此之上按「参考」屏里的几套做法补齐:
//  1) 共用五色小调色盘,每枚只取 2–3 色(柠檬绿 / 天蓝 / 珊瑚 / 金 / 紫,各配一档深色做同色系阴影);
//  2) 体积:下半同色系深一档 + 左上一道白高光 + 脚下软影;
//  3) 动作:主动作 + 次级动作(绶带跟着摆、纸条跟着探头、硬币落下后顶上那枚翻面),闪光只在「完成」的那一下出现。
// 全部原创 SVG,动画只用 transform / opacity / dash(icons.css .ib--*),减少动态效果时只留终态。
(function () {
  var K = '#121213', W = '#FFFFFF';
  var C = { lime: '#CDFF71', limeD: '#9ED53A', sky: '#8EC5FF', skyD: '#3F86E8', coral: '#FF8E6B', coralD: '#E25A3A', gold: '#FFC94F', goldD: '#E39A17', vio: '#B9A3FF', vioD: '#7C62E6', paper: '#F6F3EC', paperD: '#D9D3C4', grey: '#C9CBD2', greyD: '#9A9DA8' };
  var ST = 'stroke="' + K + '" stroke-width="4.5" stroke-linejoin="round" stroke-linecap="round"';
  function F(c) { return 'fill="' + c + '" ' + ST; }
  var LN = 'fill="none" ' + ST, HI = 'fill="none" stroke="' + W + '" stroke-width="3.5" stroke-linecap="round"', HI2 = 'fill="none" stroke="rgba(255,255,255,.7)" stroke-width="3" stroke-linecap="round"';
  function star4(cx, cy, r, fill, cls) { return '<path class="sp ' + (cls || '') + '" d="M' + cx + ' ' + (cy - r) + 'Q' + (cx + r * .16) + ' ' + (cy - r * .16) + ' ' + (cx + r) + ' ' + cy + 'Q' + (cx + r * .16) + ' ' + (cy + r * .16) + ' ' + cx + ' ' + (cy + r) + 'Q' + (cx - r * .16) + ' ' + (cy + r * .16) + ' ' + (cx - r) + ' ' + cy + 'Q' + (cx - r * .16) + ' ' + (cy - r * .16) + ' ' + cx + ' ' + (cy - r) + 'Z" fill="' + (fill || C.lime) + '" stroke="' + K + '" stroke-width="3" stroke-linejoin="round" style="transform-origin:' + cx + 'px ' + cy + 'px"/>'; }
  function ground(w, cx) { return '<ellipse class="gd" cx="' + (cx || 60) + '" cy="107" rx="' + (w || 30) + '" ry="5" fill="' + K + '" opacity=".10"/>'; }
  function scallop(cx, cy, r, n, amp) { var d = ''; for (var i = 0; i <= n * 2; i++) { var a = Math.PI * 2 * i / (n * 2) - Math.PI / 2, rr = r + (i % 2 ? amp : -amp * .2); d += (i ? 'L' : 'M') + (cx + Math.cos(a) * rr).toFixed(1) + ' ' + (cy + Math.sin(a) * rr).toFixed(1); } return d + 'Z'; }
  function star5(cx, cy, r, fill, sw) { var d = ''; for (var i = 0; i < 10; i++) { var a = -Math.PI / 2 + Math.PI * i / 5, rr = i % 2 ? r * .46 : r; d += (i ? 'L' : 'M') + (cx + Math.cos(a) * rr).toFixed(1) + ' ' + (cy + Math.sin(a) * rr).toFixed(1); } return '<path d="' + d + 'Z" fill="' + (fill || K) + '" stroke="' + K + '" stroke-width="' + (sw == null ? 3 : sw) + '" stroke-linejoin="round"/>'; }
  function dot(cx, cy, r, c, cls) { return '<circle class="' + (cls || 'cf') + '" cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="' + c + '" stroke="' + K + '" stroke-width="2.5" style="transform-origin:' + cx + 'px ' + cy + 'px"/>'; }
  var D = {
    // 线索:天蓝镜片 + 金色镜圈 + 一张探出头的小纸条(珊瑚封条)。主动作:放大镜左右找;次级:纸条探头、镜面反光扫过;找到时才闪一下。
    clue: ground(28) +
      '<g class="note"><path d="M40 74l26-6 6 26-26 6z" ' + F(C.paper) + '/><path d="M46 80l14-3M48 88l14-3" ' + LN + ' stroke-width="3"/><path d="M58 68l10-2 2 8" fill="' + C.coral + '" stroke="' + K + '" stroke-width="3.5" stroke-linejoin="round"/></g>' +
      '<g class="mg"><path d="M74 74 102 102" stroke="' + K + '" stroke-width="15" stroke-linecap="round"/><path d="M78 78l20 20" stroke="' + C.goldD + '" stroke-width="7" stroke-linecap="round"/><path d="M80 80l5 5" ' + HI2 + '/>' +
      '<circle cx="50" cy="50" r="31" ' + F(C.gold) + '/><path d="M50 19a31 31 0 0 1 0 62z" fill="' + C.goldD + '" opacity=".85"/><circle cx="50" cy="50" r="31" ' + LN + '/>' +
      '<circle cx="50" cy="50" r="22" fill="' + C.sky + '"/><path d="M50 28a22 22 0 0 1 0 44z" fill="' + C.skyD + '" opacity=".55"/><circle cx="50" cy="50" r="22" ' + LN + ' stroke-width="3.5"/>' +
      '<path class="glint" d="M36 46c2-7 7-12 14-14" ' + HI + '/><path class="glint2" d="M60 36l2-2" ' + HI + '/></g>' + star4(96, 30, 7, C.lime, 'found') + star4(22, 26, 5, W, 'found f2'),
    // 勋章:金章(深金下半)+ 白圈 + 黑星,珊瑚绶带(深珊瑚折痕),柠檬绿小星只在落定那一下爆开。主动作:落下压扁;次级:绶带慢半拍摆。
    badge: ground(26) +
      '<g class="rib"><path d="M46 64 34 108l14-7 6 10 6-46z" ' + F(C.coral) + '/><path d="M46 64l-4 16 12 2 2-16z" fill="' + C.coralD + '" opacity=".7"/><path d="M74 64l12 44-14-7-6 10-6-46z" ' + F(C.coral) + '/><path d="M74 64l4 16-12 2-2-16z" fill="' + C.coralD + '" opacity=".7"/></g>' +
      '<g class="md"><path d="' + scallop(60, 50, 29, 12, 3.5) + '" ' + F(C.gold) + '/><path d="M60 21a29 29 0 0 1 0 58z" fill="' + C.goldD + '" opacity=".8"/><path d="' + scallop(60, 50, 29, 12, 3.5) + '" ' + LN + '/>' +
      '<circle cx="60" cy="50" r="19" ' + F(W) + ' stroke-width="3.5"/><circle cx="60" cy="50" r="19" fill="' + C.paperD + '" opacity=".5" clip-path="inset(0 0 0 50%)"/><path d="M46 46c2-6 7-10 13-11" ' + HI + '/>' + star5(60, 50, 11, K) + '</g>' +
      '<g class="burst">' + star4(98, 26, 7, C.lime) + star4(20, 40, 5, C.lime) + dot(102, 56, 3, C.sky) + dot(24, 70, 3, C.coral) + '</g>',
    // 钥匙:金色柄 + 紫色宝石 + 深金齿。主动作:像在锁里转四分之一圈再弹回;次级:宝石随转动闪;转到底那一刻才有一粒绿光。
    key: ground(30) +
      '<g class="kb"><path d="M56 56 98 98" stroke="' + K + '" stroke-width="16" stroke-linecap="round"/><path d="M56 56 98 98" stroke="' + C.gold + '" stroke-width="8" stroke-linecap="round"/><path d="M58 52l-3 3" ' + HI2 + '/>' +
      '<path d="M84 72l12-12M94 82l12-12" stroke="' + K + '" stroke-width="12" stroke-linecap="round"/><path d="M84 72l12-12M94 82l12-12" stroke="' + C.goldD + '" stroke-width="5" stroke-linecap="round"/>' +
      '<circle cx="40" cy="40" r="24" ' + F(C.gold) + '/><path d="M40 16a24 24 0 0 1 0 48z" fill="' + C.goldD + '" opacity=".85"/><circle cx="40" cy="40" r="24" ' + LN + '/>' +
      '<g class="gem"><path d="M40 26l12 12-12 16-12-16z" ' + F(C.vio) + '/><path d="M40 26l12 12-12 16z" fill="' + C.vioD + '" opacity=".7"/><path d="M40 26l12 12-12 16-12-16z" ' + LN + ' stroke-width="3.5"/><path d="M34 36l5-5" ' + HI + '/></g><path d="M24 32c2-5 6-9 11-10" ' + HI + '/></g>' + star4(100, 28, 7, C.lime, 'click') + star4(22, 84, 5, W, 'click f2'),
    // 成功:柠檬绿方砖 + 白钩黑边,三色纸屑(珊瑚 / 天蓝 / 金)只在钩画完时弹出来再落下。
    success: ground(30) +
      '<g class="tile"><rect x="26" y="24" width="68" height="68" rx="20" ' + F(C.lime) + '/><path d="M60 24h14c11 0 20 9 20 20v28c0 11-9 20-20 20H60z" fill="' + C.limeD + '" opacity=".75"/><rect x="26" y="24" width="68" height="68" rx="20" ' + LN + '/><path d="M34 40c1-5 5-9 10-10" ' + HI + '/>' +
      '<path class="ck" d="M42 60l12 12 24-26" fill="none" stroke="' + K + '" stroke-width="15" stroke-linecap="round" stroke-linejoin="round"/><path class="ck" d="M42 60l12 12 24-26" fill="none" stroke="' + W + '" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/></g>' +
      '<g class="conf"><rect class="cf" x="98" y="22" width="9" height="9" rx="2" fill="' + C.coral + '" stroke="' + K + '" stroke-width="2.5" style="transform-origin:102px 26px"/>' + dot(20, 32, 4, C.sky) + dot(104, 64, 3.5, C.gold) + '<rect class="cf" x="14" y="68" width="8" height="8" rx="2" fill="' + C.vio + '" stroke="' + K + '" stroke-width="2.5" style="transform-origin:18px 72px"/>' + star4(96, 92, 6, W) + '</g>',
    // 失败:纸色方砖裂成两半(深纸色断面),珊瑚叉;小灰云飘来,只掉一滴。主动作:落地抖一下再裂开;次级:云慢慢飘到位。
    fail: ground(30) +
      '<g class="tile"><g class="hl"><path d="M30 44c0-11 9-20 20-20h26l-8 36 6 32H50c-11 0-20-9-20-20z" ' + F(C.paper) + '/><path d="M44 42c1-5 5-9 10-10" ' + HI + '/></g>' +
      '<g class="hr"><path d="M76 24c10 0 18 9 18 20v28c0 11-9 20-20 20h-6l-6-32z" ' + F(C.paper) + '/><path d="M70 92l4 0c11 0 20-9 20-20V44c0-11-9-20-20-20" fill="' + C.paperD + '" opacity=".8"/><path d="M76 24c10 0 18 9 18 20v28c0 11-9 20-20 20h-6l-6-32z" ' + LN + '/></g>' +
      '<path class="xx" d="M46 46l26 26M72 46L46 72" fill="none" stroke="' + K + '" stroke-width="13" stroke-linecap="round"/><path class="xx" d="M46 46l26 26M72 46L46 72" fill="none" stroke="' + C.coral + '" stroke-width="5" stroke-linecap="round"/></g>' +
      '<g class="cloud"><path d="M84 30c-6 0-9-4-9-8 0-5 4-8 9-7 2-6 9-8 14-5 5 2 6 7 5 12 3 1 5 4 4 8H84z" ' + F(C.grey) + ' stroke-width="3.5"/><path d="M92 20c3-2 6-2 8 0" ' + HI2 + '/><path class="drop" d="M96 36c-3 4-3 7 0 7s3-3 0-7z" fill="' + C.sky + '" stroke="' + K + '" stroke-width="2.5"/></g>',
    // 调查中:黑框沙漏 + 天蓝玻璃 + 金沙。主动作:金沙一粒粒往下落、底堆慢慢涨;翻转时整只框带一点过冲;次级:玻璃上的高光跟着晃。
    wait: ground(26) +
      '<g class="hg"><rect x="34" y="18" width="52" height="10" rx="5" ' + F(K) + '/><rect x="34" y="92" width="52" height="10" rx="5" ' + F(K) + '/>' +
      '<path d="M42 28h36c0 16-8 24-14 30 6 6 14 14 14 34H42c0-20 8-28 14-34-6-6-14-14-14-30z" ' + F(C.sky) + '/><path d="M60 28h18c0 16-8 24-14 30 6 6 14 14 14 34H60z" fill="' + C.skyD + '" opacity=".4"/><path d="M42 28h36c0 16-8 24-14 30 6 6 14 14 14 34H42c0-20 8-28 14-34-6-6-14-14-14-30z" ' + LN + '/>' +
      '<path class="sand-t" d="M48 38h24c-2 9-7 14-12 19-5-5-10-10-12-19z" fill="' + C.gold + '"/><path class="sand-b" d="M46 92h28c0-10-6-16-14-20-8 4-14 10-14 20z" fill="' + C.gold + '"/><path d="M50 92h20c0-6-4-10-10-13-6 3-10 7-10 13z" fill="' + C.goldD + '" opacity=".6"/>' +
      '<circle class="grain" cx="60" cy="64" r="1.8" fill="' + C.goldD + '"/><circle class="grain g2" cx="60" cy="72" r="1.8" fill="' + C.goldD + '"/><circle class="grain gr3" cx="60" cy="80" r="1.8" fill="' + C.goldD + '"/><path class="glint" d="M47 36c1 6 4 10 7 14" ' + HI + '/></g>' + star4(100, 40, 6, W) + star4(20, 62, 5, C.lime),
    // 队伍:三个人三件不同颜色的衣服(天蓝 / 珊瑚 / 柠檬绿),纸色脸。主动作:两侧往中间靠;次级:中间那个弹一下,头顶冒一颗小心形。
    team: ground(34) +
      '<g class="p p1"><path d="M14 98c0-14 10-24 24-24s24 10 24 24z" ' + F(C.sky) + '/><path d="M38 74c14 0 24 10 24 24H38z" fill="' + C.skyD + '" opacity=".45"/><circle cx="38" cy="54" r="13" ' + F(C.paper) + '/><path d="M28 50c2-4 5-6 9-7" ' + HI + '/></g>' +
      '<g class="p p3"><path d="M58 98c0-14 10-24 24-24s24 10 24 24z" ' + F(C.coral) + '/><path d="M82 74c14 0 24 10 24 24H82z" fill="' + C.coralD + '" opacity=".45"/><circle cx="82" cy="54" r="13" ' + F(C.paper) + '/><path d="M72 50c2-4 5-6 9-7" ' + HI + '/></g>' +
      '<g class="p p2"><path d="M30 100c0-17 13-30 30-30s30 13 30 30z" ' + F(C.lime) + '/><path d="M60 70c17 0 30 13 30 30H60z" fill="' + C.limeD + '" opacity=".6"/><path d="M30 100c0-17 13-30 30-30s30 13 30 30z" ' + LN + '/>' +
      '<circle cx="60" cy="46" r="17" ' + F(C.paper) + '/><path d="M60 29a17 17 0 0 1 0 34z" fill="' + C.paperD + '" opacity=".7"/><circle cx="60" cy="46" r="17" ' + LN + '/><path d="M50 40c1-4 4-7 8-8" ' + HI + '/><circle cx="55" cy="46" r="2" fill="' + K + '"/><circle cx="65" cy="46" r="2" fill="' + K + '"/><path d="M55 52c3 2 7 2 10 0" ' + LN + ' stroke-width="3"/></g>' +
      '<path class="heart" d="M60 22c-3-5-10-4-10 2 0 5 10 10 10 10s10-5 10-10c0-6-7-7-10-2z" ' + F(C.coral) + ' stroke-width="3" style="transform-origin:60px 28px"/>',
    // 通行证:纸色卡票 + 紫色票头 + 金星 + 黑挂绳。主动作:整张挂在绳上来回荡;次级:挂绳跟着晃;票头上柠檬绿「有效」小点轻呼吸。
    pass: ground(30) +
      '<g class="card"><path d="M30 34c0-5 4-9 9-9h42c5 0 9 4 9 9v18a6 6 0 0 0 0 12v30c0 5-4 9-9 9H39c-5 0-9-4-9-9V64a6 6 0 0 0 0-12z" ' + F(C.paper) + '/><path d="M60 25h21c5 0 9 4 9 9v18a6 6 0 0 0 0 12v30c0 5-4 9-9 9H60z" fill="' + C.paperD + '" opacity=".55"/>' +
      '<path d="M30 34c0-5 4-9 9-9h42c5 0 9 4 9 9v11H30z" fill="' + C.vio + '"/><path d="M60 25h21c5 0 9 4 9 9v11H60z" fill="' + C.vioD + '" opacity=".6"/><path d="M30 34c0-5 4-9 9-9h42c5 0 9 4 9 9v18a6 6 0 0 0 0 12v30c0 5-4 9-9 9H39c-5 0-9-4-9-9V64a6 6 0 0 0 0-12z" ' + LN + '/><path d="M30 45h60" ' + LN + ' stroke-width="3.5"/>' +
      '<path d="M38 58h30M38 68h20" stroke="' + K + '" stroke-width="4" stroke-linecap="round"/><path d="M42 86h6M52 86h3M59 86h8M71 86h3M78 86h4" stroke="' + K + '" stroke-width="4" stroke-linecap="round"/>' + star5(78, 66, 8, C.gold) + dot(80, 35, 4, C.lime, 'valid') +
      '<rect x="52" y="12" width="16" height="10" rx="5" ' + F(K) + ' stroke-width="3"/><path d="M34 30c1-2 3-3 5-3" ' + HI + '/></g><path class="lan" d="M60 12V2" ' + LN + ' stroke-width="3.5"/>' + star4(102, 50, 6, W),
    // 积分:三枚金币,中间一枚珊瑚色「赏」币,顶上柠檬绿「+」只在叠完那一下出现。主动作:三枚依次落下压实;次级:顶上那枚落稳后翻一面。
    points: ground(34) +
      '<g class="c c1"><path d="M26 86c0-7 15-12 34-12s34 5 34 12v8c0 7-15 12-34 12S26 101 26 94z" ' + F(C.gold) + '/><path d="M60 74c19 0 34 5 34 12v8c0 7-15 12-34 12z" fill="' + C.goldD + '" opacity=".55"/><ellipse cx="60" cy="86" rx="34" ry="12" ' + F(C.gold) + '/></g>' +
      '<g class="c c2"><path d="M26 70c0-7 15-12 34-12s34 5 34 12v8c0 7-15 12-34 12S26 85 26 78z" ' + F(C.coral) + '/><path d="M60 58c19 0 34 5 34 12v8c0 7-15 12-34 12z" fill="' + C.coralD + '" opacity=".5"/><ellipse cx="60" cy="70" rx="34" ry="12" ' + F(C.coral) + '/></g>' +
      '<g class="c c3"><path d="M26 52c0-7 15-12 34-12s34 5 34 12v8c0 7-15 12-34 12S26 67 26 60z" ' + F(C.gold) + '/><path d="M60 40c19 0 34 5 34 12v8c0 7-15 12-34 12z" fill="' + C.goldD + '" opacity=".55"/><ellipse cx="60" cy="52" rx="34" ry="12" ' + F(C.gold) + '/><ellipse cx="60" cy="52" rx="22" ry="7" ' + LN + ' stroke-width="3"/>' + star5(60, 52, 6, K, 2.5) + '<path d="M34 48c3-3 8-5 14-6" ' + HI + '/></g>' +
      '<g class="plus"><path d="M100 22v16M92 30h16" stroke="' + K + '" stroke-width="9" stroke-linecap="round"/><path d="M100 22v16M92 30h16" stroke="' + C.lime + '" stroke-width="4" stroke-linecap="round"/></g>' + star4(18, 36, 5, W),
    // 章节印:紫色柄 + 纸色木座 + 珊瑚印记(白星)。主动作:举起、落下、压实再抬起;次级:印记在压下那一刻才「啪」地显出来,边上溅两点墨。
    seal: ground(32) +
      '<g class="imp"><rect x="30" y="80" width="60" height="22" rx="6" ' + F(C.coral) + '/><path d="M60 80h24c3 0 6 3 6 6v10c0 3-3 6-6 6H60z" fill="' + C.coralD + '" opacity=".5"/><rect x="30" y="80" width="60" height="22" rx="6" ' + LN + '/>' + star5(60, 91, 7, W, 2.5) + dot(98, 78, 2.5, C.coralD, 'ink') + dot(22, 84, 2, C.coralD, 'ink i2') + '</g>' +
      '<g class="stp"><rect x="48" y="14" width="24" height="34" rx="10" ' + F(C.vio) + '/><path d="M60 14h2c6 0 10 4 10 10v24H60z" fill="' + C.vioD + '" opacity=".7"/><rect x="48" y="14" width="24" height="34" rx="10" ' + LN + '/><path d="M53 24c0-3 2-5 5-5" ' + HI + '/>' +
      '<path d="M36 50h48c6 0 10 4 10 10v10H26V60c0-6 4-10 10-10z" ' + F(C.paper) + '/><path d="M60 50h24c6 0 10 4 10 10v10H60z" fill="' + C.paperD + '" opacity=".8"/><path d="M36 50h48c6 0 10 4 10 10v10H26V60c0-6 4-10 10-10z" ' + LN + '/>' + star5(60, 60, 5, C.coral, 2.5) + '</g>' + star4(102, 30, 6, C.lime, 'stamped'),
  };
  var NAMES = { clue: '线索', badge: '勋章', key: '钥匙', success: '成功', fail: '失败', wait: '调查中', team: '队伍', pass: '通行证', points: '积分', seal: '章节印' };
  var LIST = ['clue', 'badge', 'key', 'success', 'fail', 'wait', 'team', 'pass', 'points', 'seal'];
  function img(name, o) {
    o = o || {}; var sz = o.size || 64;
    return '<span class="ib ib--' + name + (o.cls ? ' ' + o.cls : '') + '" style="width:' + sz + 'px;height:' + sz + 'px;--ic-d:' + (o.d || 0) + 'ms"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '><svg viewBox="0 0 120 120">' + D[name] + '</svg></span>';
  }
  window.IB = { img: img, names: NAMES, list: LIST, src: D, palette: C };
})();
