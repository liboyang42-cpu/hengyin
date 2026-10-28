'use strict';

const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const MOTION_COMPONENTS = ['btn', 'search', 'empty', 'upload', 'modal', 'offline-banner', 'date-sheet', 'node', 'slide-confirm', 'progress-status', 'sheet', 'dropdown', 'loading-mask', 'qr-voucher', 'result-sheet', 'starfield', 'skeleton', 'celebrate', 'spin-card', 'field', 'tabs'];

// 基础组件契约；只证明源码语义，真机焦点、朗读与命中区域仍需集成验收。
function scanSharedA11y(read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')) {
  const violations = [];
  const check = (component, extension, pattern, message) => {
    const key = `components/cy/${component}/index.${extension}`;
    const source = read(key).replace(/<!--[\s\S]*?-->|\/\*[\s\S]*?\*\//g, '');
    if (!pattern.test(source)) violations.push({ code: 'A11Y', key, message });
  };
  for (const tag of ['input', 'textarea']) {
    check('field', 'wxml', new RegExp(`<${tag}\\b[^>]*aria-label="[^"\\n]*label[^"\\n]*errorText`), `${tag} 缺少字段名/错误语义`);
  }
  check('field', 'wxml', /class="fd__select"[^>]*aria-role="button"[^>]*aria-label="[^"\n]*displayValue[^>]*aria-disabled=/, '选择字段必须读出名称、值和禁用态');
  check('field', 'wxml', /class="fd__help"[^>]*aria-live="polite"/, '字段错误必须动态宣布');
  check('search', 'wxml', /<input\b[^>]*aria-label="[^"\n]*accessibilityLabel[^"\n]*placeholder/, '搜索输入必须有可读名称');
  check('search', 'wxml', /class="search__clear"[^>]*aria-role="button"[^>]*aria-label="清除搜索内容"/, '清除搜索必须有操作语义');
  check('btn', 'wxml', /aria-busy="{{loading}}"/, '提交按钮必须读出忙碌状态');
  check('btn', 'wxml', /<text\b[^>]*wx:if="{{loading}}"[^>]*aria-live="polite"[^>]*>[^<]*loadingLabel/, '提交中必须保留可见文本替代');
  check('cell', 'wxml', /aria-label="[^"\n]*title[^"\n]*value[^"\n]*description/, '单元格不能只读标题而丢掉当前值和说明');
  const tabs = read('components/cy/tabs/index.wxml').match(/<view\b[^>]*bindtap="onTab"[^>]*>/g) || [];
  if (tabs.length !== 5 || tabs.some((tag) => !/aria-role="tab"/.test(tag) || !/aria-selected="{{item.key === active}}"/.test(tag) || !/aria-label="{{item.label}}"/.test(tag))) {
    violations.push({ code: 'A11Y', key: 'components/cy/tabs/index.wxml', message: '所有 tab 分支必须有名称与选中状态' });
  }
  const tablists = read('components/cy/tabs/index.wxml').match(/aria-role="tablist"/g) || [];
  if (tablists.length !== 5) violations.push({ code: 'A11Y', key: 'components/cy/tabs/index.wxml', message: '所有 tab 容器必须有 tablist 角色' });
  for (const component of ['modal', 'sheet']) {
    check(component, 'wxml', /<view\b[^>]*wx:if="\{\{_render\}\}"[^>]*aria-hidden="\{\{!show \|\| _closing\}\}"/, '关闭中的叠层不能被朗读');
  }
  for (const component of ['search', 'upload', 'modal']) {
    check(component, 'wxml', /aria-busy="\{\{(?:loading|item.uploading)\}\}"/, '加载中必须提供忙碌状态');
    check(component, 'wxml', /<text\b[^>]*>(?:搜索中|上传中|处理中)<\/text>/, '加载中必须保留可见文字替代');
    check(component, 'wxml', /aria-role="status"[^>]*aria-live="polite"/, '加载状态必须动态宣布');
  }
  const fieldCss = read('components/cy/field/index.wxss').replace(/\/\*[\s\S]*?\*\//g, '');
  const placeholderRules = [...fieldCss.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .filter(rule => rule[1].split(',').some(selector => selector.trim() === '.fd__ph'));
  if (placeholderRules.length !== 1 || !/color:\s*var\(--cy-color-text-placeholder\)/.test(placeholderRules[0][2])) {
    violations.push({ code: 'A11Y', key: 'components/cy/field/index.wxss', message: '普通占位色不能被禁用色污染' });
  }
  check('field', 'wxss', /\.is-disabled \.fd__ph\s*\{\s*color:\s*var\(--cy-color-text-disabled\);\s*\}/, '禁用字段只改变自己的颜色，不可机械混入尺寸规则');
  check('btn', 'wxss', /\.btn\s*\{[^}]*min-width:\s*88rpx;[^}]*min-height:\s*88rpx;[^}]*height:\s*auto;/, '按钮盒内触达至少 88rpx 且能随大字增高');
  check('btn', 'wxss', /\.btn\s*\{[^}]*flex-wrap:\s*wrap;[^}]*white-space:\s*normal;/, '按钮必须允许长文案换行');
  for (const component of ['field', 'search']) {
    const input = component === 'field' ? 'fd__input' : 'search__input';
    check(component, 'wxss', new RegExp(`\\.${input}\\s*\\{[^}]*min-height:\\s*88rpx;[^}]*height:\\s*calc\\(var\\(--cy-type-body\\)`), '输入框高度必须跟随现有字阶');
    const clear = component === 'field' ? 'fd__clear' : 'search__clear';
    check(component, 'wxss', new RegExp(`\\.${clear}\\s*\\{[^}]*width:\\s*88rpx;[^}]*height:\\s*88rpx;`), '清除按钮必须具有完整 88rpx 热区');
  }
  check('cell', 'wxss', /\.cell__value\s*\{[^}]*white-space:\s*normal;[^}]*word-break:\s*break-word;/, '单元格当前值不能被省略截断');
  for (const component of ['btn', 'search', 'empty', 'upload']) {
    check(component, 'js', /behaviors:\s*\[reducedMotionBehavior\]/, '必须复用已有减少动效偏好');
    check(component, 'wxml', /reducedMotion \? '[^']*--reduced-motion'/, '必须消费减少动效状态');
    check(component, 'wxss', /--reduced-motion[^{}]+\{\s*animation:\s*none;/, '减少动效必须停止转圈');
  }
  for (const component of MOTION_COMPONENTS) {
    check(component, 'wxss', /@import ["']\.\.\/\.\.\/\.\.\/style\/reduced-motion\.wxss["'];/, '隔离组件必须导入系统静态替代');
  }
  const motion = read('style/reduced-motion.wxss').replace(/\/\*[\s\S]*?\*\//g, '');
  const staticRule = motion.match(/([^{}]+)\{\s*animation:\s*none !important;\s*transition:\s*none !important;/);
  const staticSelectors = new Set(staticRule ? staticRule[1].trim().split(',').map((selector) => selector.trim().replace(/\s+/g, ' ')) : []);
  if (!staticSelectors.size || [...staticSelectors].some((selector) => !selector.startsWith('.') || /(?:^|[\s>+~])(?:[#\[]|[a-zA-Z])/.test(selector))) {
    violations.push({ code: 'A11Y', key: 'style/reduced-motion.wxss', message: '组件静态样式只允许明确 class 选择器' });
  }
  for (const component of MOTION_COMPONENTS) {
    const key = `components/cy/${component}/index.wxss`;
    const css = read(key).replace(/\/\*[\s\S]*?\*\//g, '');
    for (const rule of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const moves = [...rule[2].matchAll(/\b(?:animation|transition)(?:-[\w-]+)?\s*:\s*([^;]+)/g)]
        .some((declaration) => !/^none\b/.test(declaration[1].trim()));
      if (!moves) continue;
      for (const selector of rule[1].trim().split(',')) {
        const normalized = selector.trim().replace(/\s+/g, ' ');
        if (!staticSelectors.has(normalized)) violations.push({ code: 'A11Y', key, message: `系统静态替代未覆盖 ${normalized}` });
      }
    }
  }
  if (!/@media \(prefers-reduced-motion: reduce\)/.test(motion)
      || !/animation:\s*none !important/.test(motion)
      || !/transition:\s*none !important/.test(motion)
      || !/\.cy-celebrate[^{}]+\{\s*display:\s*none !important/.test(motion)
      || !/\.sk-bar\s*\{\s*background:\s*var\(--cy-color-skeleton-base\)/.test(motion)) {
    violations.push({ code: 'A11Y', key: 'style/reduced-motion.wxss', message: '系统减少动效必须静态呈现并隐藏装饰彩片' });
  }
  return violations;
}

module.exports = { scanSharedA11y };
