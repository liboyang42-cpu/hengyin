const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_PATH = '../../pages/merchant/index/index.js';
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

let app;
let pageConfig;
let requests;
let openedScenes;
let closedScenes;

beforeEach(() => {
  requests = [];
  openedScenes = [];
  closedScenes = 0;
  pageConfig = null;
  app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getSession: () => ({ getRevision: () => 0 }),
    getUserID: () => 11,
    sendRequest: (request) => requests.push(request),
  };
  global.getApp = () => app;
  global.getCurrentPages = () => [];
  const persisted = {};
  global.wx = {
    getStorageSync: key => persisted[key],
    setStorageSync(key, value) { persisted[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete persisted[key]; },
    scanCode: ({ success }) => success({ result: JSON.stringify({ type: 'topic', code: 'TICKET-1' }) }),
    showLoading() {},
    hideLoading() {},
    showToast() {},
    navigateTo() {},
    reLaunch() {},
  };
  global.Page = (config) => { pageConfig = config; };
  delete require.cache[require.resolve(PAGE_PATH)];
  require(PAGE_PATH);
});

function makePage() {
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = (patch) => Object.assign(page.data, patch);
  page.openScene = (id) => openedScenes.push(id);
  page.closeScene = () => { closedScenes += 1; };
  return page;
}

test('扫码失败给出重试提示，取消扫码不提示也不提交核销', () => {
  const messages = [];
  wx.showToast = (options) => messages.push(options.title);
  const page = makePage();
  wx.scanCode = ({ fail }) => fail({ errMsg: 'scanCode:fail' });
  page.goScanQR();
  assert.deepEqual(messages, ['未识别到二维码，请对准后重试']);
  wx.scanCode = ({ fail }) => fail({ errMsg: 'scanCode:fail cancel' });
  page.goScanQR();
  assert.equal(messages.length, 1);
  assert.equal(requests.length, 0);
});

function openTwoStationChoice(page) {
  page.goScanQR();
  assert.equal(requests.length, 1);
  requests[0].success({
    code: 500,
    msg: '请选择要核销的据点',
    data: {
      needStationChoice: true,
      stations: [
        { registrationMerchantId: 701, name: '东门咖啡', validationMethod: 1 },
        { registrationMerchantId: 702, name: '西岸书店', validationMethod: 3 },
      ],
    },
  });
}

test('多据点回执只打开选择面板,不直接取第一站或显示核销成功', () => {
  const page = makePage();

  openTwoStationChoice(page);

  assert.equal(page.data.stationSheet.show, true);
  assert.equal(page.data.stationSheet.items.length, 2);
  assert.deepEqual(openedScenes, ['merchant-station-picker']);
  assert.equal(page.data.verificationResult.show, false,
    'needStationChoice 是未核销态，不能先显示成功结果');
  assert.equal(requests.length, 1,
    '商家尚未点选时不得自动调用选择端点或拿候选第一行核销');
});

test('取消据点选择不发第二次请求,code 仍保持未核销', () => {
  const page = makePage();
  openTwoStationChoice(page);

  page.onStationSheetClose();

  assert.equal(closedScenes, 1);
  assert.equal(requests.length, 1, '取消只关面板，不能调用核销端点');
  assert.equal(page.data.verificationResult.show, false,
    '取消不能把错误回执改写为已核销结果');
});

test('点选后只提交商家明确选择的 registrationMerchantId', () => {
  const page = makePage();
  openTwoStationChoice(page);

  page.onStationPick({ currentTarget: { dataset: { registrationMerchantId: 702 } } });

  assert.equal(requests.length, 2);
  assert.equal(requests[1].url, '/api/registration/scan_qr_code_station');
  assert.deepEqual(requests[1].data, { code: 'TICKET-1', registrationMerchantId: 702 });
  assert.equal(closedScenes, 1);
  requests[1].success({ code: 200, msg: '核销成功' });
});

test('商家工作台与个人页都按既有选章形态注册据点面板和选择端点', () => {
  const pageJs = read('pages/merchant/index/index.js');
  const pageWxml = read('pages/merchant/index/index.wxml');
  const profileJs = read('subpackageMember/components/cy/profile/index.js');
  const profileWxml = read('subpackageMember/components/cy/profile/index.wxml');
  const registry = read('utils/scene-registry.js');

  for (const js of [pageJs, profileJs]) {
    assert.match(js, /d\.needStationChoice/);
    assert.match(js, /openStationSheet/);
    assert.match(js, /scan_qr_code_station/);
    assert.match(js, /registrationMerchantId/);
  }
  assert.match(pageWxml, /sceneCurrent\.id === 'merchant-station-picker'/);
  assert.match(pageWxml, /wx:for="\{\{stationSheet\.items\}\}"/);
  assert.match(pageWxml, /bindtap="onStationPick"/);
  assert.match(profileWxml, /sceneCurrent\.id === 'member-station-picker'/);
  assert.match(profileWxml, /wx:for="\{\{stationSheet\.items\}\}"/);
  assert.match(profileWxml, /bindtap="onStationPick"/);
  assert.match(registry, /'merchant-station-picker':\s*\{[^\n]*variant: 'half'/);
  assert.match(registry, /'member-station-picker':\s*\{[^\n]*variant: 'half'/);
});

test('参与详情快捷扫码识别多据点未核销态,不会退化为普通成功提示', () => {
  const js = read('subpackageMember/components/scene-member-participation-detail/index.js');
  assert.match(js, /d\.needChapterChoice\s*\|\|\s*d\.needStationChoice/);
  assert.match(js, /多个章节/);
});

test('商家扫码历史章与待核销章并存必须点选，重试返回原回执', () => {
  const page=makePage(); page.goScanQR();
  requests[0].success({code:500,data:{needChapterChoice:true, chapters:[{id:31,name:'历史章',replayed:true,redemptionId:66},{id:32,name:'待核销章'}]}});
  assert.equal(requests.length,1);
  assert.equal(page.data.chapterSheet.items.length,2);
  assert.equal(page.data.verificationResult.show,false);
  page.onChapterPick({currentTarget:{dataset:{id:31}}});
  assert.deepEqual(requests[1].data,{code:'TICKET-1',chapterId:31});
  requests[1].success({code:200,data:{replayed:true,redemptionId:66,chapterId:31,completionEarnedXp:36}});
  assert.equal(page.data.verificationResult.state,'duplicate');
  assert.equal(page.data.verificationResult.message,'该章节已核销，请勿重复提供权益');
});

test('参与详情实际快捷扫码显示重复回执而非核销成功', () => {
  let definition; const toasts=[];
  global.Component = config => {definition=config;};
  wx.showToast=options=>toasts.push(options);
  const target=path.join(ROOT,'subpackageMember/components/scene-member-participation-detail/index.js');
  delete require.cache[require.resolve(target)];require(target);
  const component=Object.assign({},definition.methods);
  component.data=JSON.parse(JSON.stringify(definition.data));
  component.setData=patch=>Object.assign(component.data,patch);
  component._submitVerification=(key,title,request,callback)=>callback({code:200,data:{replayed:true,redemptionId:66,chapterId:31,completionEarnedXp:36}});
  component.goScanQR();
  assert.equal(toasts.at(-1).title,'已核销：该章节已核销，请勿重复提供权益');
  assert.equal(toasts.at(-1).icon,'none');
});
