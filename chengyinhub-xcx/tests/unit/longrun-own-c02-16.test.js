// Real Page methods, deferred request callbacks and local storage exercise lost-write responses.
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/fabu/index.js';
const { createPageBoundOperation } = require('../../utils/page-bound-operation.js');

let sent = [];
let imageCallback;
let imageOperations = [];
let storage = {};
let navigations = [];
let toasts = [];
let modals = [];
let memberId = 42;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  tips: (m) => { toasts.push(m); },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  chooseImage: callback => {
    const owner = {};
    const operation = createPageBoundOperation({
      owner, getPages: () => [owner], getUserID: () => memberId,
      setInterval: () => null, clearInterval() {},
    });
    // app.chooseImage 在成功/失败/取消后 finish；成功时先 finish 再给页面回调。
    imageCallback = result => { operation.finish(); callback(result); };
    imageOperations.push({ operation, callback: imageCallback });
    return operation;
  },
  getUserID: () => memberId,
  getUserInfo: () => null,
  getToken: () => '',
});
global.wx = {
  getStorageSync: (k) => storage[k],
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: (k) => { delete storage[k]; },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {}, hideLoading: () => {},
  showModal: (options) => { modals.push(options); if (options.success) options.success({ confirm: true }); },
  navigateTo: (options) => { navigations.push(options.url); },
  redirectTo: (options) => { navigations.push(options.url); },
  createMapContext: () => ({ getCenterLocation: () => {} }),
  pageScrollTo: () => {}, nextTick: (f) => f(),
};

let pageConfig = null;
global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  memberId = 42;
  imageOperations = [];
  imageCallback = undefined;
  sent = [];
  storage = {};
  navigations = [];
  toasts = [];
  modals = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.replace(/\[(\d+)\]/g, '.$1').split('.');
      let o = inst.data;
      for (let i = 0; i < parts.length - 1; i++) o = o[parts[i]];
      o[parts[parts.length - 1]] = patch[k];
    });
    if (cb) cb();
  };
  return inst;
}

test('story image callback cannot insert into another chapter and a current insertion still uses the real story command', () => {
  const page = makePage();
  page.updateAllStatistics = () => {};
  page.data.formData.chapters = [{ _localId:'A', nodes:[], blocks:[] }, { _localId:'B', nodes:[], blocks:[] }];
  page.data.storyEditor = { show:true, chapterIndex:0, insertMenuAt:0 };
  page.insertStoryImageAt({ currentTarget:{ dataset:{ index:0 } } });
  const oldUpload = imageCallback;
  page.data.storyEditor = { show:true, chapterIndex:1, insertMenuAt:0 };
  oldUpload(['A.jpg']);
  assert.equal(page.data.formData.chapters[1].blocks.length, 0);
  page.insertStoryImageAt({ currentTarget:{ dataset:{ index:0 } } });
  imageCallback(['B.jpg']);
  assert.equal(page.data.formData.chapters[1].blocks[0].url, 'B.jpg');
  assert.equal(page.data.formData.chapters[0].blocks.length, 0);
});

function storyImagePage() {
  const page = makePage();
  page.updateAllStatistics = () => {};
  page.data.formData.chapters = [{ _localId: 'A', nodes: [], blocks: [
    { key: 'before', type: 'text', content: '前' },
    { key: 'after', type: 'text', content: '后' },
  ] }];
  page.data.storyEditor = { show: true, chapterIndex: 0, insertMenuAt: 1 };
  return page;
}
const insertImageEvent = { currentTarget: { dataset: { index: 1 } } };

test('故事图片选择和上传在途时重复点击只启动一次 chooseImage', () => {
  const page = storyImagePage();
  page.insertStoryImageAt(insertImageEvent);
  page.insertStoryImageAt(insertImageEvent);
  assert.equal(imageOperations.length, 1);
  assert.equal(page._storyMediaUploadOperation, imageOperations[0].operation);
  assert.equal(page.data.formData.chapters[0].blocks.length, 2);
  imageOperations[0].callback(['A.jpg']);
  assert.equal(page.data.formData.chapters[0].blocks[1].url, 'A.jpg');
});

for (const result of ['cancel', 'upload-failed', 'aborted', 'success']) {
  test('故事图片 ' + result + ' 后可重新选择，新的在途操作仍防重', () => {
    const page = storyImagePage();
    page.insertStoryImageAt(insertImageEvent);
    const first = imageOperations[0];
    if (result === 'aborted') first.operation.abort();
    else if (result === 'success') first.callback(['first.jpg']);
    else first.operation.finish(); // 原生取消/上传失败不调用页面成功回调
    page.insertStoryImageAt(insertImageEvent);
    page.insertStoryImageAt(insertImageEvent);
    assert.equal(imageOperations.length, 2);
    assert.equal(page._storyMediaUploadOperation, imageOperations[1].operation);
    imageOperations[1].callback(['retry.jpg']);
    assert.equal(page.data.formData.chapters[0].blocks[1].url, 'retry.jpg');
    assert.equal(page.data.formData.chapters[0].blocks.filter(b => b.type === 'image').length,
      result === 'success' ? 2 : 1);
  });
}

for (const context of ['account', 'unloaded', 'editor', 'closed', 'chapter', 'before', 'after']) {
  test('故事图片上传在途变更 ' + context + ' 时旧结果仍被隔离，完成后可重试', () => {
    const page = storyImagePage();
    page.insertStoryImageAt(insertImageEvent);
    const first = imageOperations[0];
    if (context === 'account') memberId = 43;
    if (context === 'unloaded') page._unloaded = true;
    if (context === 'editor') page.data.storyEditor = { ...page.data.storyEditor };
    if (context === 'closed') page.data.storyEditor.show = false;
    if (context === 'chapter') page.data.formData.chapters[0]._localId = 'B';
    if (context === 'before') page.data.formData.chapters[0].blocks[0].key = 'new-before';
    if (context === 'after') page.data.formData.chapters[0].blocks[1].key = 'new-after';
    first.callback(['stale.jpg']);
    assert.equal(page.data.formData.chapters[0].blocks.filter(b => b.type === 'image').length, 0);
    page._unloaded = false;
    page.data.storyEditor.show = true;
    page.insertStoryImageAt(insertImageEvent);
    assert.equal(imageOperations.length, 2);
    imageOperations[1].callback(['current.jpg']);
    assert.equal(page.data.formData.chapters[0].blocks[1].url, 'current.jpg');
  });
}

test('故事图片没有目标章节时不创建选择操作', () => {
  const page = storyImagePage();
  page.data.formData.chapters = [];
  page.insertStoryImageAt(insertImageEvent);
  assert.equal(imageOperations.length, 0);
});

test('真实 app.chooseImage：取消、选图失败、空选择、上传失败和成功均结束 operation', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const vm = require('node:vm');
  const root = path.resolve(__dirname, '../..');
  let realApp, chooser, uploaded;
  const callbacks = [], owner = {};
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App(value) { realApp = value; },
    require(file) {
      if (file === './utils/loading.js') return { show() {}, hide() {} };
      if (file === './utils/toast.js') return () => {};
      return require(path.join(root, file));
    },
    wx: { chooseMedia(options) { chooser = options; } },
    console,
  }, { filename: 'app.js' });
  realApp.createPageBoundOperation = () => createPageBoundOperation({
    owner, getPages: () => [owner], getUserID: () => memberId,
    setInterval: () => null, clearInterval() {},
  });
  realApp.showModal = () => {};
  realApp.getUploadClient = () => ({
    uploadAll(files, options) { uploaded = options; return { abort() {} }; },
  });
  for (const outcome of ['cancel', 'picker-failed', 'empty', 'upload-failed', 'success']) {
    const operation = realApp.chooseImage(result => {
      assert.equal(operation.isFinished(), true, '真实 App 必须在页面成功回调前 finish');
      callbacks.push(result);
    }, 1, { skipCrop: true });
    assert.equal(operation.isFinished(), false);
    if (outcome === 'cancel') chooser.fail({ errMsg: 'chooseMedia:fail cancel' });
    else if (outcome === 'picker-failed') chooser.fail({ errMsg: 'chooseMedia:fail unavailable' });
    else if (outcome === 'empty') chooser.success({ tempFiles: [] });
    else {
      chooser.success({ tempFiles: [{ tempFilePath: '/fixture/image.png' }] });
      assert.equal(operation.isFinished(), false, '上传尚未完成时仍在途');
      uploaded.onDone(outcome === 'success'
        ? { ok: true, results: ['current.jpg'] }
        : { ok: false, failures: [{ msg: 'upload failed' }] });
    }
    assert.equal(operation.isFinished(), true, outcome);
    assert.equal(operation.isAborted(), false, outcome);
  }
  assert.deepEqual(JSON.parse(JSON.stringify(callbacks)), [['current.jpg']]);
});
