// 主题工作台底部「进度」(走查 v2 R04/R16,业主 2026-10-30 定稿原型 p7)。
// 只用工作台已经拿到的数:主题 lifecycle、站点招满数、待确认数、合作俱乐部数、已售份数。
// 每一步给 state(done/run/todo)、当前步的蓝字状态、子项(还差什么 + 去处理入口);
// 入口全部复用工作台现有派发(onQuickAction 的 club/merchant/customer/scan、goAddStations、goFinance),不新开页面。
//
// lifecycle:0 草稿 / 7 立项待审 / 1 招募中 / 2 待定价 / 3 售票中 / 4 进行中 / 5 结算中 / 6 已关闭
// 顺序按业主 10-30:先发布、先招商(此时可以不配票务),合作方就位后再配票务、开售。

const STAGE_BY_LIFECYCLE = { 0: 1, 7: 1, 1: 2, 2: 3, 3: 4, 4: 5, 5: 6, 6: 7 };

function count(n) {
  const v = Number(n);
  return Number.isFinite(v) && v > 0 ? v : 0;
}

function buildHostSteps(input) {
  const src = input || {};
  const lifecycle = Number(src.lifecycle);
  const stage = Object.prototype.hasOwnProperty.call(STAGE_BY_LIFECYCLE, lifecycle) ? STAGE_BY_LIFECYCLE[lifecycle] : 1;
  const nodeTotal = count(src.nodeTotal);
  const nodeFilled = count(src.nodeFilled);
  const pending = count(src.pendingCount);
  const clubs = count(src.clubCount);
  const paid = count(src.paidCount);
  const freeExplore = Number(src.productType) === 2;
  const unit = freeExplore ? '章' : '站';

  const steps = [
    { key: 'config', label: '配置主题' },
    { key: 'publish', label: '发布', status: lifecycle === 7 ? '审核中' : '待发布',
      subs: [{ text: lifecycle === 7 ? '平台审核通过后开始招商' : '发布后先开始招商,票务可以之后再配', act: 'goAddStations', actText: '去编辑' }] },
    { key: 'recruit', label: '招商', status: '待邀约',
      subs: [
        { text: '俱乐部 ' + clubs + ' 家', ok: clubs > 0, act: 'onQuickAction', actKey: 'club', actText: clubs ? '看俱乐部' : '去邀约' },
        { text: '商家 ' + nodeFilled + '/' + nodeTotal + ' ' + unit + (pending ? ' · ' + pending + ' 个申请待处理' : ''), ok: nodeTotal > 0 && nodeFilled >= nodeTotal && !pending,
          act: 'onQuickAction', actKey: 'merchant', actText: pending ? '去处理' : '去招商' },
      ] },
    { key: 'ticket', label: '配置票务', status: '待配置',
      subs: [{ text: '票种和票价在主题配置里设,配好后提交开售', act: 'goAddStations', actText: '去配置' }] },
    { key: 'sale', label: '售票', status: '售票中',
      subs: [{ text: '已售 ' + paid + ' 份', ok: paid > 0, act: 'onQuickAction', actKey: 'customer', actText: '看客户' }] },
    { key: 'verify', label: '核销接待', status: '待核销',
      subs: [{ text: '玩家到店出示入场码,扫码核销', act: 'onQuickAction', actKey: 'scan', actText: '去扫码' }] },
    { key: 'settle', label: '结算', status: '结算中', lock: '每月 5 日',
      subs: [{ text: '每月 5 日结算上月已核销部分', act: 'goFinance', actText: '分润明细' }] },
  ];

  return steps.map(function (step, i) {
    const state = i < stage ? 'done' : (i === stage ? 'run' : 'todo');
    return {
      key: step.key,
      label: step.label,
      state: state,
      status: state === 'run' ? step.status : '',
      lock: state === 'todo' && step.lock ? step.lock : '',
      subs: state === 'run' ? (step.subs || []) : [],
    };
  });
}

module.exports = { buildHostSteps };
