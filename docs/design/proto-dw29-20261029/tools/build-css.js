// 把 master 的 WXSS 原样转成原型用的 CSS:rpx → px(390 宽,×0.52)、page → .cyp、:host → 组件包裹类,
// 页面样式按页根类加前缀(小程序里页面样式天然隔离,浏览器里不加前缀会互相打架)。
// 只读 master 源文件,不改任何小程序代码。用法:node docs/design/proto-dw29-20261029/tools/build-css.js
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '../../../../chengyinhub-xcx');
const OUT = path.resolve(__dirname, '../shared/cy.css');
const K = 390 / 750;

function read(rel) { return fs.readFileSync(path.join(ROOT, rel), 'utf8'); }

function units(css) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/@import[^;]*;/g, '')
    .replace(/(-?\d*\.?\d+)rpx/g, (m, n) => +(parseFloat(n) * K).toFixed(2) + 'px')
    .replace(/(\d*\.?\d+)vh/g, (m, n) => +(parseFloat(n) * 8.44).toFixed(2) + 'px')
    .replace(/(?:env|constant)\(\s*safe-area-inset-bottom\s*\)/g, '34px')
    .replace(/(?:env|constant)\(\s*safe-area-inset-top\s*\)/g, '47px')
    .replace(/(?:env|constant)\(\s*safe-area-inset-(?:left|right)\s*\)/g, '0px');
}

// 逐个顶层规则处理选择器;@media / @supports 递归,@keyframes / @font-face 原样
function mapRules(css, fn) {
  let out = '', i = 0;
  while (i < css.length) {
    const open = css.indexOf('{', i);
    if (open < 0) break;
    const sel = css.slice(i, open).trim();
    let depth = 1, j = open + 1;
    while (j < css.length && depth) { if (css[j] === '{') depth++; else if (css[j] === '}') depth--; j++; }
    const body = css.slice(open + 1, j - 1);
    if (/^@(media|supports)/.test(sel)) out += sel + '{' + mapRules(body, fn) + '}\n';
    else if (/^@/.test(sel)) out += sel + '{' + body + '}\n';
    else if (sel) out += fn(sel) + '{' + body + '}\n';
    i = j;
  }
  return out;
}

function scope(css, root) {
  return mapRules(units(css), (sel) => sel.split(',').map((s) => {
    s = s.trim();
    if (!s) return s;
    s = tag(s);
    if (/^(page|:host)\b/.test(s)) return s.replace(/^(page|:host)/, root);
    if (/^(from|to|\d+%)$/.test(s)) return s;
    return root + ' ' + s;
  }).join(', '));
}

// <image> 在 HTML 里会被解析成 <img>,选择器跟着换
const tag = (s) => s.replace(/(^|[\s,>+~(])image(?=[\s,.:>\[{]|$)/g, '$1img');

function global(css) {
  return mapRules(units(css), (sel) => sel.split(',').map((s) => tag(s.trim()).replace(/^page\b/, '.cyp')).join(', '));
}

const parts = [];
const add = (title, css) => parts.push('/* ===== ' + title + ' ===== */\n' + css);

['style/tokens.wxss', 'style/lib.wxss', 'style/common.wxss', 'style/dark-mode.wxss', 'style/components.wxss', 'app.wxss',
 'style/fade-in.wxss', 'style/merchant-light-scope.wxss', 'components/cy/icon/icons.wxss']
  .forEach((f) => add(f, global(read(f))));
add('components/cy/icon/index.wxss', global(read('components/cy/icon/index.wxss').replace(/:host\s*{[^}]*}/, '')));

const COMPONENTS = {
  btn: 'components/cy/btn/index.wxss', tabs: 'components/cy/tabs/index.wxss', empty: 'components/cy/empty/index.wxss',
  card: 'components/cy/card/index.wxss', avatar: 'components/cy/avatar/index.wxss', cell: 'components/cy/cell/index.wxss',
  sheet: 'components/cy/sheet/index.wxss', 'nav-bar': 'components/cy/nav-bar/index.wxss', 'page-title': 'components/cy/page-title/index.wxss',
  'post-card': 'components/cy/post-card/index.wxss', 'completion-share-card': 'components/cy/completion-share-card/index.wxss',
  'feed-play-card': 'components/cy/feed-play-card/index.wxss', 'footer-bar': 'components/cy/footer-bar/index.wxss',
  field: 'components/cy/field/index.wxss', 'consent-check': 'components/cy/consent-check/index.wxss',
  switch: 'components/cy/switch/index.wxss', tabbar: 'components/tabBar/index.wxss', 'post-compose': 'pages/square/components/cy/post-compose/index.wxss'
};
Object.keys(COMPONENTS).forEach((k) => add(COMPONENTS[k], scope(read(COMPONENTS[k]), '.c-' + k)));

const PAGES = {
  club: ['pages/club/detail/index.wxss'], talent: ['pages/talent/list/index.wxss'],
  profile: ['subpackageMember/components/cy/profile/index.wxss'], edit: ['pages/gerenziliao/gerenziliao.wxss'],
  tp: ['pages/templatedetail/topic-preview/index.wxss'], td: ['pages/templatedetail/templatedetail.wxss'],
  fabu: ['pages/publish/fabu/index.wxss'], bw: ['subpackageP3/pages/badge-wall/index/index.wxss'],
  al: ['subpackageP3/pages/stamp-album/index/index.wxss'], oc: ['subpackageP3/pages/object-cards/index/index.wxss'],
  cu: ['pages/merchant/customer/index.wxss'], crop: ['pages/crop/index.wxss']
};
Object.keys(PAGES).forEach((k) => PAGES[k].forEach((f) => add(f, scope(read(f), '.pg-' + k))));

fs.writeFileSync(OUT, '/* 自动生成 —— tools/build-css.js 从 master WXSS 转出,勿手改 */\n' + parts.join('\n'));
console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
