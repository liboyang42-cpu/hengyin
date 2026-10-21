// cy-privacy-gate · 微信隐私授权闸的【真弹窗】实现。
//
// 它取代的旧形态是「伪弹窗」:app.js 用 wx.navigateTo 把 /pages/privacy/index 压进页面栈,
// 只是画得像弹窗。伪弹窗的六个后果里,第 5 条在这里是真 bug —— 页面上「待决策不给关闭」
// 的强制闸挡不住系统返回(左滑/物理键),系统退页时没有任何 resolve 被调用,
// 那个 Promise 永不 settle,后续需要隐私授权的接口全部静默挂起。
// 页内弹窗没有「被系统退掉」这个出口,所以从结构上就没有这个洞。
//
// ⚠️ 同意按钮必须是原生 <button open-type="agreePrivacyAuthorization">(微信强制,不能换成 cy-btn)。
// 它在自定义组件里是可用的 —— 本仓 cy-cell / scene-share-invite / 订单详情都在组件内用 open-type。
const toast = require('../../../utils/toast.js');
const app = getApp();

Component({
  properties: {
    // 宿主页把自己的显隐位传进来;组件自己不改它(所有权留在宿主,同 cy-privacy-sheet 的做法)
    show: { type: Boolean, value: false },
  },
  data: {
    submitting: false,
    // 进入弹窗不再单独勾选服务协议:点「同意并继续」即同意一句话里的两份文件。
    showServiceSheet: false,
  },
  methods: {
    // toggleService 已撤(勾选框随弹窗简化移除)。
    openService() { this.setData({ showServiceSheet: true }); },
    closeService() { this.setData({ showServiceSheet: false }); },
    openPrivacyContract() {
      wx.openPrivacyContract({
        fail() { toast('隐私保护指引暂不可用'); },
      });
    },

    agreePrivacy(event) {
      if (this.data.submitting) return;
      this.setData({ submitting: true });
      app.agreeUsageConsent(event, true)
        .then(() => {
          this.setData({ submitting: false });
          this.triggerEvent('settled', { event: 'agree' });
        })
        .catch(() => {
          this.setData({ submitting: false });
          // F-L5C-10:微信授权已结算、本机已记下明确同意时,留痕失败只是「待补传」(登录成功后自动补),
          // 不把用户卡在不可关闭的弹窗里;本机选择也没留下(如条款已升版)才停在弹窗重试。
          if (app.getUsageConsent && app.getUsageConsent().granted()) {
            toast('已同意，联网后会自动补记同意记录');
            this.triggerEvent('settled', { event: 'agree', recordPending: true });
            return;
          }
          toast('同意记录失败，请检查网络后重试');
        });
    },

    disagreePrivacy() {
      if (this.data.submitting) return;
      app.declineUsageConsent();
      app.resolvePrivacyAuthorization({ event: 'disagree' });
      this.triggerEvent('settled', { event: 'disagree' });
    },
  },

  lifetimes: {
    // 兜底:弹窗随宿主页一起被销毁(用户切走/页面被回收)时,等待中的授权必须结算,
    // 否则调用方仍会挂住 —— 这是伪弹窗那个 bug 的同源风险,在这里也堵上。
    detached() {
      if (app.hasPendingPrivacyAuthorization && app.hasPendingPrivacyAuthorization()) {
        app.declineUsageConsent();
        app.resolvePrivacyAuthorization({ event: 'disagree', reason: 'gate_detached' });
      }
    },
  },
});
