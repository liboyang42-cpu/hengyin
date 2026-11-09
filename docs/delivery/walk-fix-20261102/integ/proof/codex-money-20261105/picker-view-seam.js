// Browser-only stand-in for WeChat's native picker-view. Choices come from rendered rows.
// shortcut: clicks replace wheel gestures here; verify native gestures in WeChat before launch.
(() => {
  const style = document.createElement('style');
  style.textContent = 'wx-picker-view{display:flex!important;height:180px!important;overflow:hidden!important}wx-picker-view-column{display:block;flex:1;min-width:0;height:180px;overflow-y:auto}wx-picker-view-column>.picker-item{cursor:pointer;min-height:42px;line-height:42px!important}wx-picker-view-column>.rig-selected{background:#ddd;border-radius:8px}';
  function install(root) {
    if (!root.__moneyPickerStyle) { root.__moneyPickerStyle = true; (root === document ? document.head : root).appendChild(style.cloneNode(true)); }
    for (const picker of root.querySelectorAll('wx-picker-view')) {
      const cols = [...picker.children].filter(e => e.__tag === 'picker-view-column');
      const value = picker.__vp?.value || [];
      cols.forEach((col, ci) => [...col.children].forEach((item, i) => {
        item.classList.toggle('rig-selected', i === Number(value[ci] || 0));
        if (item.__moneyPickerClick) return;
        item.__moneyPickerClick = true;
        item.addEventListener('click', e => {
          e.stopPropagation();
          const next = [...(picker.__vp?.value || cols.map(() => 0))];
          next[ci] = i;
          window.MPWEB.fire(picker, 'change', { value: next });
          queueMicrotask(() => install(root));
        });
      }));
    }
    for (const e of root.querySelectorAll('*')) if (e.shadowRoot) install(e.shadowRoot);
  }
  window.__moneyInstallPicker = () => install(document);
  install(document);
  window.__moneyPickerObserver = new MutationObserver(() => install(document));
  window.__moneyPickerObserver.observe(document.body, { childList: true, subtree: true });
})();
