// 主题勋章 · 第二版(插画式)。V-23b 业主否了第一版「同一个珐琅底板 + 换徽记」,这一版每枚是一张小插画:
//   外形   ← 这个主题故事里的核心物件(船舵、蒸笼、怀表、装饰艺术盾、水滴、六边坐标牌),不是按类型套底板
//   画面   ← 封面场景拆三层:远景(天色 / 时间)· 中景(城市地标 / 场地)· 前景主物件(勋章名说的那样东西)
//   破框   ← 每枚有一样东西伸出边框(热气、表冠与碎片、舵柄、光芒、水珠、错位碎片),轮廓一眼能分开
//   金属   ← 主题风格:怀旧人文 = 古铜 · 科幻异常 = 镀铬 · 生存逃脱 = 黑镍 · 美食 = 竹黄 · 其余 = 金
//   色板   ← 封面取色(同第一版 hue),每枚 5 档
//   背面   ← 拉丝金属 + 刻字(主题名、获得日期、编号),3D 查看时翻过来看
//   未点亮 ← 整枚灰阶压暗,轮廓不变
// 每枚是 SVG(240 × 240 视框),可以直接导出 @3x PNG 给小程序;画面由设计按上面的规则逐个主题画,
// 规则只定结构,不靠程序随机拼。
(function () {
  var uid = 0;
  var METAL = {
    gold: ['#FFF4C9', '#E9BC52', '#9C6A1B', '#F7D77F', '#7A4E10'],
    bronze: ['#F6D5B3', '#C4834E', '#73401F', '#DDA474', '#5A2E16'],
    chrome: ['#FFFFFF', '#CDD5DE', '#7A8692', '#E9EEF3', '#56606B'],
    black: ['#A7AEB8', '#555B64', '#1C1F24', '#737A84', '#0E1013'],
    bamboo: ['#F6E2B0', '#CFA25A', '#8C6328', '#E9CB86', '#6E4B1C']
  };
  var METAL_ZH = { gold: '金', bronze: '古铜', chrome: '镀铬', black: '黑镍', bamboo: '竹黄' };
  function grad(id, f, rev) {
    var s = METAL[f], o = [0, .3, .55, .78, 1];
    var c = rev ? 'x1="210" y1="230" x2="30" y2="20"' : 'x1="30" y1="20" x2="210" y2="230"';
    return '<linearGradient id="' + id + '" gradientUnits="userSpaceOnUse" ' + c + '>' + s.map(function (x, i) { return '<stop offset="' + o[i] + '" stop-color="' + x + '"/>'; }).join('') + '</linearGradient>';
  }
  function lg(id, stops, x1, y1, x2, y2) {
    return '<linearGradient id="' + id + '" x1="' + (x1 || 0) + '" y1="' + (y1 || 0) + '" x2="' + (x2 === undefined ? 0 : x2) + '" y2="' + (y2 === undefined ? 1 : y2) + '">' +
      stops.map(function (s) { return '<stop offset="' + s[0] + '" stop-color="' + s[1] + '"' + (s[2] !== undefined ? ' stop-opacity="' + s[2] + '"' : '') + '/>'; }).join('') + '</linearGradient>';
  }
  function rg(id, stops, cx, cy, r) {
    return '<radialGradient id="' + id + '" cx="' + (cx || .5) + '" cy="' + (cy || .5) + '" r="' + (r || .5) + '">' +
      stops.map(function (s) { return '<stop offset="' + s[0] + '" stop-color="' + s[1] + '"' + (s[2] !== undefined ? ' stop-opacity="' + s[2] + '"' : '') + '/>'; }).join('') + '</radialGradient>';
  }
  function circ(cx, cy, r) { return 'M' + cx + ' ' + (cy - r) + 'a' + r + ' ' + r + ' 0 1 1 0 ' + 2 * r + 'a' + r + ' ' + r + ' 0 1 1 0 ' + -2 * r + 'z'; }
  function poly(cx, cy, r, n, rot) {
    var p = []; for (var i = 0; i < n; i++) { var a = (rot + i * 360 / n) * Math.PI / 180; p.push((cx + Math.cos(a) * r).toFixed(1) + ' ' + (cy + Math.sin(a) * r).toFixed(1)); }
    return 'M' + p.join('L') + 'Z';
  }
  function sc(c, s) { return 'translate(' + c[0] + ' ' + c[1] + ') scale(' + s + ') translate(' + -c[0] + ' ' + -c[1] + ')'; }
  function rot(a, x, y, inner) { return '<g transform="rotate(' + a + ' ' + x + ' ' + y + ')">' + inner + '</g>'; }

  // ---------- 六枚 ----------
  var DEFS = {
    // 海上方舟 · 方舟见证人:船舵外形,黄昏海面上的方舟
    't-ark': {
      finish: 'bronze', c: [120, 120], shape: circ(120, 120, 84), field: .82,
      shapeZh: '船舵(八根舵柄伸出边框)', layers: '黄昏天色 + 落日 · 海面与倒影 · 张帆的方舟、近处浪头', breakout: '舵柄',
      behind: function (id) {
        var h = '';
        for (var k = 0; k < 8; k++) h += rot(k * 45 + 22.5, 120, 120, '<rect x="114" y="14" width="12" height="40" rx="6" fill="url(#' + id + 'A)"/><circle cx="120" cy="16" r="9.5" fill="url(#' + id + 'A)"/><circle cx="120" cy="16" r="9.5" fill="none" stroke="rgba(0,0,0,.35)" stroke-width="1"/>');
        return h;
      },
      art: function (id) {
        return '<defs>' + lg(id + 'sky', [[0, '#13203F'], [.42, '#3B3F6E'], [.66, '#C9706A'], [.8, '#F2A65E']]) + lg(id + 'sea', [[0, '#24507F'], [1, '#08182F']]) +
          lg(id + 'hull', [[0, '#8A4728'], [1, '#3A1A0E']]) + lg(id + 'sail', [[0, '#FFF6E2'], [1, '#D6BD90']], 0, 0, 1, 1) + rg(id + 'sun', [[0, '#FFE7B8'], [.45, '#FFC27A', .55], [1, '#FFC27A', 0]]) + '</defs>' +
          '<rect x="0" y="0" width="240" height="240" fill="url(#' + id + 'sky)"/>' +
          [[78, 70], [96, 62], [150, 66], [168, 80], [132, 58], [62, 92]].map(function (s) { return '<circle cx="' + s[0] + '" cy="' + s[1] + '" r="1" fill="#fff" opacity=".85"/>'; }).join('') +
          '<circle cx="150" cy="146" r="40" fill="url(#' + id + 'sun)"/><circle cx="150" cy="146" r="15" fill="#FFE1A8"/>' +
          '<rect x="0" y="146" width="240" height="94" fill="url(#' + id + 'sea)"/>' +
          '<g stroke="#FFD9A0" stroke-linecap="round" opacity=".7"><line x1="140" y1="152" x2="160" y2="152" stroke-width="2"/><line x1="144" y1="158" x2="157" y2="158" stroke-width="1.6"/><line x1="146" y1="164" x2="154" y2="164" stroke-width="1.3"/></g>' +
          '<path d="M70 150 Q 92 146 112 150" stroke="#7FA8D6" stroke-width="1.2" fill="none" opacity=".6"/>' +
          '<path d="M64 150 L158 150 L148 168 Q110 175 76 168 Z" fill="url(#' + id + 'hull)"/><path d="M68 156 L154 156" stroke="#E3B272" stroke-width="1.6"/>' +
          '<rect x="90" y="138" width="34" height="12" rx="2" fill="#9A5A34"/><circle cx="98" cy="144" r="2" fill="#FFD9A0"/><circle cx="107" cy="144" r="2" fill="#FFD9A0"/><circle cx="116" cy="144" r="2" fill="#FFD9A0"/>' +
          '<rect x="105" y="74" width="3.4" height="66" fill="#3E2214"/><rect x="129" y="94" width="2.6" height="46" fill="#3E2214"/>' +
          '<path d="M108.5 80 C 138 90 140 122 108.5 132 Z" fill="url(#' + id + 'sail)"/><path d="M108.5 80 C 124 92 126 118 108.5 132" stroke="#C6A877" stroke-width="1" fill="none"/>' +
          '<path d="M105 86 C 82 98 80 122 105 130 Z" fill="#EAD8B3"/><path d="M131.5 98 C 150 106 150 126 131.5 132 Z" fill="url(#' + id + 'sail)"/>' +
          '<path d="M107 74 L107 64 L124 69 Z" fill="#D8433A"/>' +
          '<path d="M0 176 Q 30 162 60 176 T 120 176 T 180 176 T 240 176 V240 H0Z" fill="#163763"/><path d="M0 176 Q 30 162 60 176 T 120 176 T 180 176 T 240 176" stroke="#A8D2F5" stroke-width="2" fill="none" opacity=".75"/>' +
          '<path d="M0 194 Q 26 184 52 194 T 104 194 T 156 194 T 208 194 T 260 194 V240 H0Z" fill="#0B2245"/><path d="M0 194 Q 26 184 52 194 T 104 194 T 156 194 T 208 194 T 260 194" stroke="#7FB4E8" stroke-width="1.6" fill="none" opacity=".6"/>' +
          '<path d="M164 112 l4 3 l4 -3 M176 104 l3 2 l3 -2" stroke="#2A1F2E" stroke-width="1.4" fill="none" stroke-linecap="round"/>';
      }
    },
    // 风味巡游 · 老饕:蒸笼外形,三只小笼 + 红漆筷,热气冒出边框
    't-flavor': {
      finish: 'bamboo', c: [120, 126], shape: circ(120, 126, 86), field: .8,
      shapeZh: '竹蒸笼(笼圈有编纹)', layers: '笼布 · 醋碟与姜丝 · 三只小笼包、红漆筷、风味印', breakout: '热气、筷子',
      rim: function () {
        var h = '<g stroke="rgba(90,55,20,.38)" stroke-width="1.3">';
        for (var i = 0; i < 64; i++) { var a = i * Math.PI * 2 / 64; h += '<line x1="' + (120 + Math.cos(a) * 71).toFixed(1) + '" y1="' + (126 + Math.sin(a) * 71).toFixed(1) + '" x2="' + (120 + Math.cos(a) * 83).toFixed(1) + '" y2="' + (126 + Math.sin(a) * 83).toFixed(1) + '"/>'; }
        return h + '</g>';
      },
      art: function (id) {
        function bao(x, y, s) {
          var p = '';
          for (var k = -3; k <= 3; k++) p += '<path d="M' + x + ' ' + (y - 17 * s) + ' Q ' + (x + k * 6 * s) + ' ' + (y - 10 * s) + ' ' + (x + k * 8.5 * s) + ' ' + (y + 4 * s) + '" stroke="#D2B482" stroke-width="1.3" fill="none"/>';
          return '<ellipse cx="' + x + '" cy="' + (y + 13 * s) + '" rx="' + 24 * s + '" ry="' + 6 * s + '" fill="#9C7A44" opacity=".35"/>' +
            '<path d="M' + (x - 23 * s) + ' ' + (y + 9 * s) + ' C ' + (x - 26 * s) + ' ' + (y - 8 * s) + ' ' + (x - 10 * s) + ' ' + (y - 19 * s) + ' ' + x + ' ' + (y - 19 * s) + ' C ' + (x + 10 * s) + ' ' + (y - 19 * s) + ' ' + (x + 26 * s) + ' ' + (y - 8 * s) + ' ' + (x + 23 * s) + ' ' + (y + 9 * s) + ' Q ' + x + ' ' + (y + 17 * s) + ' ' + (x - 23 * s) + ' ' + (y + 9 * s) + 'Z" fill="url(#' + id + 'bao)"/>' +
            p + '<circle cx="' + x + '" cy="' + (y - 17 * s) + '" r="' + 3.2 * s + '" fill="#F3E3C2" stroke="#D2B482" stroke-width="1"/>' +
            '<ellipse cx="' + (x - 9 * s) + '" cy="' + (y - 6 * s) + '" rx="' + 5 * s + '" ry="' + 3 * s + '" fill="#fff" opacity=".7"/>';
        }
        return '<defs>' + rg(id + 'cloth', [[0, '#FBF2DC'], [.75, '#EBD3A2'], [1, '#C9A464']]) + rg(id + 'bao', [[0, '#FFFFFF'], [.7, '#F6EBD5'], [1, '#DCC398']], .4, .35, .7) + rg(id + 'vin', [[0, '#A8652C'], [1, '#4E2810']]) + '</defs>' +
          '<rect x="0" y="0" width="240" height="240" fill="url(#' + id + 'cloth)"/>' +
          '<g stroke="#D9BF8A" stroke-width="1" opacity=".55">' + [70, 82, 94, 106, 118, 130, 142, 154, 166, 178].map(function (y) { return '<line x1="30" y1="' + y + '" x2="210" y2="' + y + '"/>'; }).join('') + '</g>' +
          '<ellipse cx="80" cy="150" rx="17" ry="12" fill="#F5F0E6" stroke="#CDB98F"/><ellipse cx="80" cy="150" rx="12" ry="8" fill="url(#' + id + 'vin)"/>' +
          '<path d="M74 148 l9 -3 M76 152 l8 -1 M78 146 l6 4" stroke="#F2C94C" stroke-width="1.4" stroke-linecap="round"/>' +
          bao(98, 110, 1) + bao(146, 112, 1) + bao(124, 152, 1.08) +
          rot(-8, 158, 176, '<rect x="150" y="168" width="17" height="17" rx="2" fill="#C8313A"/><text x="158.5" y="181" font-size="12" font-weight="700" fill="#FFF3E0" text-anchor="middle" font-family="serif">味</text>');
      },
      front: function (id) {
        var steam = [[98, 86], [124, 92], [148, 88]].map(function (p, i) {
          var x = p[0], y = p[1];
          var d = 'M' + x + ' ' + y + ' C ' + (x - 9) + ' ' + (y - 16) + ' ' + (x + 9) + ' ' + (y - 28) + ' ' + x + ' ' + (y - 44) + ' C ' + (x - 7) + ' ' + (y - 56) + ' ' + (x + 6) + ' ' + (y - 66) + ' ' + (x + (i - 1) * 4) + ' ' + (y - 80);
          return '<path d="' + d + '" stroke="#FFFFFF" stroke-width="7" fill="none" stroke-linecap="round" opacity=".28" filter="url(#' + id + 'blur)"/><path d="' + d + '" stroke="#FFFFFF" stroke-width="2.6" fill="none" stroke-linecap="round" opacity=".85"/>';
        }).join('');
        var sticks = '<defs>' + lg(id + 'lac', [[0, '#E0454C'], [1, '#7A141D']], 0, 0, 1, 0) + '</defs>' +
          rot(36, 186, 96, '<rect x="182" y="34" width="6" height="118" rx="3" fill="url(#' + id + 'lac)"/><rect x="182" y="34" width="6" height="18" rx="3" fill="#E8C062"/><rect x="193" y="40" width="5.4" height="112" rx="2.7" fill="url(#' + id + 'lac)"/><rect x="193" y="40" width="5.4" height="18" rx="2.7" fill="#E8C062"/>');
        return '<defs><filter id="' + id + 'blur" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="3"/></filter></defs>' + steam + sticks;
      }
    },
    // 异常事务局 · 坐标校准员:六边坐标牌,边框错位(青 / 品红色差),夜城网格上一枚出错的定位钉
    't-anomaly': {
      finish: 'chrome', c: [120, 120], shape: poly(120, 120, 98, 6, -90), field: .8,
      shapeZh: '六边坐标牌(边框色差错位)', layers: '夜空 · 透视网格与楼群 · 撕裂的定位钉、坐标', breakout: '错位碎片',
      behind: function () {
        return '<path d="' + poly(120, 120, 98, 6, -90) + '" fill="#22D3EE" opacity=".9" transform="translate(-5 -1)"/><path d="' + poly(120, 120, 98, 6, -90) + '" fill="#F43F9E" opacity=".9" transform="translate(5 2)"/>';
      },
      art: function (id) {
        var grid = '<g stroke="#3EE6FF" stroke-width="1" opacity=".4">';
        for (var x = -120; x <= 360; x += 26) grid += '<line x1="120" y1="112" x2="' + x + '" y2="240"/>';
        [124, 134, 148, 168, 196, 232].forEach(function (y) { grid += '<line x1="0" y1="' + y + '" x2="240" y2="' + y + '"/>'; });
        grid += '</g>';
        var city = [[30, 74, 20], [52, 88, 16], [70, 62, 14], [86, 80, 18], [150, 70, 16], [168, 56, 20], [190, 84, 18], [206, 70, 16]].map(function (b) {
          var w = '';
          for (var yy = b[1] + 6; yy < 108; yy += 8) for (var xx = b[0] + 3; xx < b[0] + b[2] - 3; xx += 6) if ((xx * 7 + yy * 3) % 5 < 2) w += '<rect x="' + xx + '" y="' + yy + '" width="2.4" height="3" fill="' + ((xx + yy) % 3 ? '#6EE7FF' : '#FFD166') + '" opacity=".75"/>';
          return '<rect x="' + b[0] + '" y="' + b[1] + '" width="' + b[2] + '" height="' + (112 - b[1]) + '" fill="#18275C"/>' + w;
        }).join('');
        var pin = 'M120 152 C 108 134 95 122 95 104 A25 25 0 1 1 145 104 C 145 122 132 134 120 152Z';
        return '<defs>' + lg(id + 'sky', [[0, '#070D24'], [1, '#1D2F6B']]) + lg(id + 'pin', [[0, '#FF7A66'], [1, '#C21A4A']], 0, 0, 1, 1) +
          '<pattern id="' + id + 'scan" width="4" height="3" patternUnits="userSpaceOnUse"><rect width="4" height="1" fill="#000" opacity=".22"/></pattern>' +
          '<clipPath id="' + id + 'band"><rect x="0" y="100" width="240" height="9"/></clipPath><clipPath id="' + id + 'band2"><rect x="0" y="124" width="240" height="5"/></clipPath></defs>' +
          '<rect width="240" height="240" fill="url(#' + id + 'sky)"/>' +
          '<circle cx="74" cy="46" r="10" fill="#C9E9FF" opacity=".85"/><circle cx="79" cy="43" r="9" fill="#0B1230"/>' +
          city + '<rect x="0" y="112" width="240" height="128" fill="#0C1640"/>' + grid +
          '<ellipse cx="120" cy="154" rx="22" ry="5" fill="#3EE6FF" opacity=".45"/>' +
          '<path d="' + pin + '" fill="#22D3EE" opacity=".85" transform="translate(-6 0)"/><path d="' + pin + '" fill="#F43F9E" opacity=".85" transform="translate(6 0)"/>' +
          '<path d="' + pin + '" fill="url(#' + id + 'pin)"/><circle cx="120" cy="104" r="9.5" fill="#0B1230"/>' +
          '<path d="M104 96 A18 18 0 0 1 116 84" stroke="#fff" stroke-width="3" fill="none" stroke-linecap="round" opacity=".5"/>' +
          '<g clip-path="url(#' + id + 'band)" transform="translate(12 0)"><path d="' + pin + '" fill="url(#' + id + 'pin)"/><circle cx="120" cy="104" r="9.5" fill="#0B1230"/></g>' +
          '<g clip-path="url(#' + id + 'band2)" transform="translate(-9 0)"><path d="' + pin + '" fill="#22D3EE"/></g>' +
          '<text x="120" y="182" font-size="9" fill="#7DF3FF" text-anchor="middle" font-family="ui-monospace,Menlo,monospace" letter-spacing=".5" opacity=".9">31°14′N 121°29′E</text>' +
          '<rect width="240" height="240" fill="url(#' + id + 'scan)"/>';
      },
      front: function (id) {
        return '<path d="M196 50 L214 62 L206 74 L190 64 Z" fill="url(#' + id + 'A)" stroke="#22D3EE" stroke-width="1.2"/><path d="M212 82 L224 90 L218 98 L208 92 Z" fill="url(#' + id + 'A)" stroke="#F43F9E" stroke-width="1.2"/>' +
          '<rect x="24" y="168" width="14" height="4" fill="#22D3EE"/><rect x="30" y="176" width="9" height="3" fill="#F43F9E"/>';
      }
    },
    // 时间污染 · 找回今天:怀表外形,表盘缺了一块,缺口里是被删掉的那个早晨
    't-time': {
      finish: 'gold', c: [120, 132], shape: circ(120, 132, 84), field: .82,
      shapeZh: '怀表(表环 + 表冠)', layers: '表盘刻度与罗马数字 · 缺口里的日出 · 蓝钢指针、飞出的表盘碎片', breakout: '表冠、表环、碎片',
      behind: function (id) {
        return '<circle cx="120" cy="30" r="13" fill="none" stroke="url(#' + id + 'A)" stroke-width="6"/>' +
          '<rect x="108" y="38" width="24" height="14" rx="4" fill="url(#' + id + 'A)"/>' +
          '<g stroke="rgba(0,0,0,.3)" stroke-width="1">' + [112, 116, 120, 124, 128].map(function (x) { return '<line x1="' + x + '" y1="40" x2="' + x + '" y2="50"/>'; }).join('') + '</g>' +
          '<rect x="114" y="50" width="12" height="6" fill="url(#' + id + 'B)"/>';
      },
      art: function (id) {
        var t = '';
        for (var i = 0; i < 60; i++) { var a = i * 6, big = i % 5 === 0; t += rot(a, 120, 132, '<line x1="120" y1="' + (132 - 66) + '" x2="120" y2="' + (132 - (big ? 58 : 62)) + '" stroke="#3B2F25" stroke-width="' + (big ? 2.2 : .9) + '"/>'); }
        var num = [['XII', 120, 87], ['III', 168, 136], ['VI', 120, 184], ['IX', 72, 136]].map(function (n) { return '<text x="' + n[1] + '" y="' + n[2] + '" font-size="12" font-family="Georgia,serif" font-weight="700" fill="#2B2118" text-anchor="middle">' + n[0] + '</text>'; }).join('');
        var hole = 'M150 106 L176 116 L186 148 L164 160 L148 140 L156 126 Z';
        return '<defs>' + rg(id + 'dial', [[0, '#FFFCF3'], [.8, '#F1E6CB'], [1, '#DCCBA3']]) + lg(id + 'dawn', [[0, '#5B4BC4'], [.45, '#FF6F91'], [1, '#FFC46B']]) + '</defs>' +
          '<rect width="240" height="240" fill="url(#' + id + 'dial)"/>' + t + num +
          '<circle cx="120" cy="158" r="12" fill="none" stroke="#3B2F25" stroke-width=".9"/><line x1="120" y1="158" x2="120" y2="149" stroke="#3B2F25" stroke-width="1.2"/>' +
          '<path d="' + hole + '" fill="url(#' + id + 'dawn)"/><circle cx="170" cy="150" r="7" fill="#FFE9A8"/><path d="M152 150 Q 166 144 184 150" stroke="#3A2F6E" stroke-width="2" fill="none" opacity=".5"/>' +
          '<path d="' + hole + '" fill="none" stroke="#6B4F2A" stroke-width="1.6" stroke-linejoin="round"/>' +
          '<path d="M150 106 L138 96 L132 84 M186 148 L196 160 M148 140 L136 146 L128 158" stroke="#6B4F2A" stroke-width="1" fill="none"/>' +
          '<path d="M120 132 L102 108 L100 104 L106 106 Z" fill="#1E3A8A"/><path d="M118.6 132 L119.2 76 L120 72 L120.8 76 L121.4 132 Z" fill="#1E3A8A"/>' +
          '<line x1="120" y1="132" x2="96" y2="160" stroke="#B5361E" stroke-width="1.2"/><circle cx="120" cy="132" r="5" fill="url(#' + id + 'A)"/>';
      },
      front: function (id) {
        return '<defs>' + lg(id + 'dawn2', [[0, '#FF6F91'], [1, '#FFC46B']]) + '</defs>' +
          '<path d="M200 104 L216 98 L212 116 Z" fill="#F4E9CF" stroke="#6B4F2A" stroke-width="1.2" transform="rotate(14 208 106)"/>' +
          '<path d="M210 130 L224 128 L220 140 L208 140 Z" fill="url(#' + id + 'dawn2)" stroke="#6B4F2A" stroke-width="1.2"/>' +
          '<path d="M202 156 L212 160 L204 168 Z" fill="#F4E9CF" stroke="#6B4F2A" stroke-width="1.1"/>';
      }
    },
    // 上海像一部旧电影 · 散场之后:装饰艺术盾形,外滩剪影 + 放映光束 + 胶片
    't-movie': {
      finish: 'bronze', c: [120, 132], field: .8,
      shape: 'M120 224 C 88 208 52 182 52 132 L 52 82 L 72 82 L 72 64 L 92 64 L 92 48 L 120 34 L 148 48 L 148 64 L 168 64 L 168 82 L 188 82 L 188 132 C 188 182 152 208 120 224 Z',
      shapeZh: '装饰艺术盾(外滩大楼的阶梯顶)', layers: '黄昏棕调天空与月牙 · 外滩海关钟楼、和平饭店尖顶 · 放映光束与胶片', breakout: '阶梯顶后的放射光芒',
      behind: function (id) {
        var h = '';
        for (var i = 0; i < 9; i++) { var a = -160 + i * 17.5; h += rot(a + 90, 120, 56, '<path d="M117 56 L120 -6 L123 56 Z" fill="url(#' + id + 'A)" opacity="' + (i % 2 ? .75 : 1) + '"/>'); }
        return h;
      },
      art: function (id) {
        var holes = '';
        for (var x = -10; x < 260; x += 13) holes += '<rect x="' + x + '" y="182" width="7" height="5" rx="1.5" fill="#E9C99A"/><rect x="' + x + '" y="203" width="7" height="5" rx="1.5" fill="#E9C99A"/>';
        var frames = '';
        for (var f = 0; f < 5; f++) frames += '<rect x="' + (f * 52 - 4) + '" y="189" width="44" height="12" fill="#6E4A2A" opacity=".8"/>';
        return '<defs>' + lg(id + 'sky', [[0, '#24180F'], [.55, '#7E4F2C'], [.85, '#DDA265']]) + lg(id + 'beam', [[0, '#FFF4DC', .75], [1, '#FFF4DC', 0]], 0, 1, 1, 0) + '</defs>' +
          '<rect width="240" height="240" fill="url(#' + id + 'sky)"/>' +
          '<path d="M84 72 a12 12 0 1 0 12 16 a9 9 0 1 1 -12 -16z" fill="#F4DFB8"/>' +
          [[150, 74], [166, 92], [136, 66], [104, 100]].map(function (s) { return '<circle cx="' + s[0] + '" cy="' + s[1] + '" r="1" fill="#FFE9C2"/>'; }).join('') +
          '<path d="M46 196 L196 96 L214 128 Z" fill="url(#' + id + 'beam)"/>' +
          '<g fill="#22160F"><rect x="56" y="142" width="22" height="40"/><path d="M80 120 L94 96 L108 120 Z"/><rect x="80" y="120" width="28" height="62"/><rect x="110" y="132" width="16" height="50"/>' +
          '<rect x="128" y="108" width="16" height="74"/><rect x="131" y="96" width="10" height="14"/><path d="M131 96 L136 86 L141 96 Z"/><rect x="146" y="136" width="20" height="46"/><rect x="168" y="126" width="18" height="56"/></g>' +
          '<circle cx="136" cy="116" r="4.2" fill="#E9B571"/><g fill="#E9B571" opacity=".85">' + [[84, 130], [92, 130], [100, 130], [84, 142], [100, 142], [150, 146], [158, 156], [172, 136], [176, 150], [60, 152], [68, 164]].map(function (w) { return '<rect x="' + w[0] + '" y="' + w[1] + '" width="3" height="4"/>'; }).join('') + '</g>' +
          rot(-5, 120, 196, '<rect x="-20" y="178" width="280" height="34" fill="#140D09"/>' + frames + holes) +
          [[160, 112], [176, 104], [150, 124], [186, 118]].map(function (s) { return '<circle cx="' + s[0] + '" cy="' + s[1] + '" r=".9" fill="#fff" opacity=".8"/>'; }).join('');
      }
    },
    // 高温末日逃生 · 降温者:水滴外形,上半是烤化的城市,下半是升起的凉水和一块冰
    't-heat': {
      finish: 'black', c: [120, 146], field: .8,
      shape: 'M120 16 C 142 58 198 98 198 150 A 78 78 0 0 1 42 150 C 42 98 98 58 120 16 Z',
      shapeZh: '水滴', layers: '烈日与热浪 · 融化滴落的城市天际线 · 冷水、气泡与一块冰', breakout: '溅出的水珠',
      art: function (id) {
        return '<defs>' + lg(id + 'sky', [[0, '#FFE08A'], [.5, '#FF8A3D'], [1, '#E2403A']]) + lg(id + 'water', [[0, '#5FE0F5'], [1, '#0A5F96']]) + rg(id + 'sun', [[0, '#FFFDE8'], [.4, '#FFF1A6'], [1, '#FFD45E', 0]]) + '</defs>' +
          '<rect width="240" height="240" fill="url(#' + id + 'sky)"/>' +
          '<circle cx="146" cy="92" r="36" fill="url(#' + id + 'sun)"/><circle cx="146" cy="92" r="15" fill="#FFF7CF"/>' +
          '<g stroke="#FFE7A6" stroke-width="2" fill="none" opacity=".7" stroke-linecap="round"><path d="M64 98 q8 -6 16 0 t16 0 t16 0"/><path d="M70 112 q8 -6 16 0 t16 0"/><path d="M154 122 q8 -6 16 0 t16 0"/></g>' +
          '<path d="M40 150 L40 126 L56 126 L56 114 L72 114 L72 132 L84 132 L84 104 L100 104 L100 128 L112 128 L112 118 L130 118 L130 134 L142 134 L142 110 L158 110 L158 128 L172 128 L172 120 L190 120 L190 150 Z" fill="#7A1F2B"/>' +
          '<g fill="#7A1F2B"><path d="M60 126 q2 8 0 12 q-2 -4 0 -12z"/><path d="M92 104 q3 12 0 18 q-3 -6 0 -18z"/><path d="M150 110 q2.6 10 0 15 q-2.6 -5 0 -15z"/><path d="M180 120 q2 7 0 10 q-2 -3 0 -10z"/></g>' +
          '<rect x="0" y="148" width="240" height="92" fill="url(#' + id + 'water)"/>' +
          '<path d="M0 150 Q 20 144 40 150 T 80 150 T 120 150 T 160 150 T 200 150 T 240 150" stroke="#C9F6FF" stroke-width="2" fill="none"/>' +
          '<g transform="translate(100 140) rotate(-10)"><path d="M0 8 L22 0 L44 8 L22 16 Z" fill="#F2FDFF"/><path d="M0 8 L22 16 L22 42 L0 34 Z" fill="#A8EAF7"/><path d="M22 16 L44 8 L44 34 L22 42 Z" fill="#6FCDE6"/>' +
          '<path d="M5 12 L5 26 M26 22 L38 18" stroke="#fff" stroke-width="2" stroke-linecap="round" opacity=".85"/></g>' +
          '<rect x="0" y="156" width="240" height="84" fill="#127FB5" opacity=".35"/>' +
          [[86, 196, 4], [96, 210, 2.6], [150, 188, 3.4], [160, 204, 2.2], [132, 216, 3]].map(function (b) { return '<circle cx="' + b[0] + '" cy="' + b[1] + '" r="' + b[2] + '" fill="none" stroke="#E8FCFF" stroke-width="1.2" opacity=".8"/>'; }).join('');
      },
      front: function () {
        function drop(x, y, s) { return '<path d="M' + x + ' ' + (y - 9 * s) + ' C ' + (x + 3 * s) + ' ' + (y - 3 * s) + ' ' + (x + 6 * s) + ' ' + y + ' ' + (x + 6 * s) + ' ' + (y + 3 * s) + ' A ' + 6 * s + ' ' + 6 * s + ' 0 0 1 ' + (x - 6 * s) + ' ' + (y + 3 * s) + ' C ' + (x - 6 * s) + ' ' + y + ' ' + (x - 3 * s) + ' ' + (y - 3 * s) + ' ' + x + ' ' + (y - 9 * s) + 'Z" fill="#7FE6F8" stroke="#0A5F96" stroke-width="1"/><circle cx="' + (x - 2 * s) + '" cy="' + (y + 2 * s) + '" r="' + 1.6 * s + '" fill="#fff"/>'; }
        return drop(204, 112, 1.2) + drop(218, 136, .8) + drop(30, 128, .9);
      }
    }
  };
  var ORDER = ['t-ark', 't-flavor', 't-anomaly', 't-time', 't-movie', 't-heat'];
  var META = {
    't-ark': { name: '海上方舟', medal: '方舟见证人', style: '怀旧人文' },
    't-flavor': { name: '风味巡游', medal: '老饕', style: '美食' },
    't-anomaly': { name: '异常事务局：不存在的坐标', medal: '坐标校准员', style: '科幻异常' },
    't-time': { name: '时间污染：被删除的今天', medal: '找回今天', style: '其余' },
    't-movie': { name: '上海像一部旧电影', medal: '散场之后', style: '怀旧人文' },
    't-heat': { name: '高温末日逃生', medal: '降温者', style: '生存逃脱' }
  };

  function svg(key, o) {
    o = o || {};
    var d = DEFS[key]; if (!d) return '';
    var id = 'm' + (++uid) + '_';
    var c = d.c, fc = sc(c, d.field);
    var body =
      '<defs>' + grad(id + 'A', d.finish) + grad(id + 'B', d.finish, 1) +
      '<clipPath id="' + id + 'clip"><path d="' + d.shape + '" transform="' + fc + '"/></clipPath>' +
      '<filter id="' + id + 'sh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="6" stdDeviation="6" flood-color="#000" flood-opacity=".45"/></filter>' +
      lg(id + 'gloss', [[0, '#fff', .42], [.5, '#fff', .06], [1, '#fff', 0]], 0, 0, .7, .9) + '</defs>' +
      '<g filter="url(#' + id + 'sh)">' + (d.behind ? d.behind(id) : '') +
      '<path d="' + d.shape + '" fill="url(#' + id + 'A)"/>' +
      '<path d="' + d.shape + '" transform="' + sc(c, .95) + '" fill="url(#' + id + 'B)"/>' +
      '<path d="' + d.shape + '" transform="' + sc(c, d.field + .03) + '" fill="#000" opacity=".4"/></g>' +
      (d.rim ? d.rim(id) : '') +
      '<g clip-path="url(#' + id + 'clip)">' + d.art(id) + '<path d="' + d.shape + '" transform="' + fc + '" fill="url(#' + id + 'gloss)"/></g>' +
      '<path d="' + d.shape + '" transform="' + fc + '" fill="none" stroke="rgba(0,0,0,.45)" stroke-width="1.4"/>' +
      '<path d="' + d.shape + '" fill="none" stroke="rgba(255,255,255,.55)" stroke-width=".9"/>' +
      (d.front ? d.front(id) : '');
    return '<svg class="m2' + (o.locked ? ' is-locked' : '') + (o.cls ? ' ' + o.cls : '') + '" viewBox="0 0 240 240" aria-label="' + META[key].medal + '">' + body + '</svg>';
  }

  // 背面:同外形,拉丝金属,刻主题名 / 日期 / 编号
  function back(key, o) {
    o = o || {};
    var d = DEFS[key], m = META[key]; if (!d) return '';
    var id = 'b' + (++uid) + '_', c = d.c;
    var name = m.name.split('：')[0];
    return '<svg class="m2" viewBox="0 0 240 240"><defs>' + grad(id + 'A', d.finish) + grad(id + 'B', d.finish, 1) +
      '<pattern id="' + id + 'br" width="240" height="2" patternUnits="userSpaceOnUse"><rect width="240" height="1" fill="#fff" opacity=".07"/></pattern>' +
      '<filter id="' + id + 'sh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="6" stdDeviation="6" flood-color="#000" flood-opacity=".45"/></filter></defs>' +
      '<g filter="url(#' + id + 'sh)" transform="translate(240 0) scale(-1 1)">' + (d.behind ? d.behind(id).replace(/#22D3EE|#F43F9E/g, 'transparent') : '') +
      '<path d="' + d.shape + '" fill="url(#' + id + 'A)"/><path d="' + d.shape + '" transform="' + sc(c, .9) + '" fill="url(#' + id + 'B)"/>' +
      '<path d="' + d.shape + '" transform="' + sc(c, .9) + '" fill="url(#' + id + 'br)"/><path d="' + d.shape + '" transform="' + sc(c, .9) + '" fill="none" stroke="rgba(0,0,0,.35)" stroke-width="1.2"/></g>' +
      '<g text-anchor="middle" font-family="-apple-system,PingFang SC,sans-serif">' +
      '<text x="120" y="' + (c[1] - 8) + '" font-size="17" font-weight="700" fill="rgba(0,0,0,.5)">' + name + '</text><text x="120" y="' + (c[1] - 7) + '" font-size="17" font-weight="700" fill="rgba(255,255,255,.28)" dy="1">' + name + '</text>' +
      '<text x="120" y="' + (c[1] + 16) + '" font-size="11" fill="rgba(0,0,0,.45)" letter-spacing="1">' + (o.date || '2026.10.26') + '</text>' +
      '<text x="120" y="' + (c[1] + 34) + '" font-size="10" fill="rgba(0,0,0,.4)" letter-spacing="2" font-family="ui-monospace,Menlo,monospace">No.0128</text></g></svg>';
  }

  window.MEDALS2 = { ORDER: ORDER, DEFS: DEFS, META: META, METAL_ZH: METAL_ZH, svg: svg, back: back };
})();
