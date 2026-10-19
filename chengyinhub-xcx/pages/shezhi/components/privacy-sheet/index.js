// cy-privacy-sheet · 隐私与定位设置的半屏弹窗(从设置页发起,不再整页跳走)
//
// ⚠️ 只承接「设置」这一路:说明 + 官方指引入口 + 撤回漫游定位同意。
//    微信隐私授权待决策那一路(原生 <button open-type="agreePrivacyAuthorization">)
//    仍留在 pages/privacy 路由页 —— wx.onNeedPrivacyAuthorization 回调靠 navigateTo
//    压那一页,而且那个原生按钮不能收编成普通 view,搬进弹窗会让授权彻底失效。
//    故 pages/privacy 路由页保留不删。
const toast = require('../../../../utils/toast.js');
const bgTracker = require('../../../../utils/location/bg-tracker.js');
const roamConsent = require('../../../../utils/location/roam-consent.js');
const app = getApp();
<<<<<<< public
const presencePrivacy = require('../../../../utils/roam-presence-privacy.js');
const bgTracker = require('../../../../utils/location/bg-tracker.js');
=======
const {
  MASK_ZONE_MAX,
  MASK_RADIUS_DEFAULT,
  readSharePrivacy,
  writeSharePrivacy,
} = require('../../../../utils/roam-route-privacy.js');
const locationManager = require('../../../../utils/location/location-manager.js');
const cyModal = require('../../../../utils/modal.js');
>>>>>>> private-feature/w2-r

Component({
  options: { multipleSlots: true },
  properties: {
    show: { type: Boolean, value: false },
    theme: { type: String, value: 'player' },
  },
  data: {
    submitting: false,
    maskZones: [],
    maskZoneMax: MASK_ZONE_MAX,
    maskRadius: MASK_RADIUS_DEFAULT,
    saveError: '',
  },
  lifetimes: {
    attached() { this._loadSharePrivacy(); },
  },
  observers: {
    // 每次重新打开都从存储读一次:设置面板不是唯一写入方(分享页也读同一份),
    // 缓存住 attached 那一次的值会在第二次打开时显示旧状态。
    show(next) { if (next) this._loadSharePrivacy(); },
  },
  methods: {
    _loadSharePrivacy() {
      const privacy = readSharePrivacy(wx);
      this.setData({
        maskZones: privacy.maskZones,
        saveError: '',
      });
    },

    /* 落盘失败必须说出来。静默吞掉 = 用户以为关掉了裁剪/调好了比例,
       下次分享还是按旧值走 —— 位置隐私上这种假保证比没有开关更坏。 */
    _persist(patch) {
      const result = writeSharePrivacy(patch, wx);
      this.setData({
        maskZones: result.maskZones,
        saveError: result.ok ? '' : '设置没能保存，请重试；本次分享仍按上一次的设置执行。',
      });
      return result.ok;
    },

    /**
     * N015 敏感地点:用户在地图上**手动选点**(不读当前位置),选中才落盘;
     * 取消选点 = 什么都不改。区域坐标只存本机,上传快照只带「遮了几处」。
     * 已发布的链接/已导出的图不会因为这里的修改而改变 —— 下次分享才按新设置去敏。
     */
    addMaskZone() {
      if ((this.data.maskZones || []).length >= MASK_ZONE_MAX) {
        toast('最多设置 ' + MASK_ZONE_MAX + ' 处敏感地点');
        return;
      }
      cyModal.show({
        title: '添加敏感地点',
        content: '在地图上选一个地点（如家、公司）。分享足迹时，该地点 ' + MASK_RADIUS_DEFAULT + ' 米内的轨迹点和打卡地点会被去掉。地点只保存在本机，不会上传。',
        confirmText: '去选点',
        cancelText: '取消',
        success: (res) => {
          if (!res || !res.confirm) return;
          locationManager.pickLocation({
            onPick: (poi) => {
              const zones = (this.data.maskZones || []).slice();
              zones.push({
                id: 'z' + Date.now().toString(36),
                name: poi.name || '敏感地点',
                lat: poi.latitude,
                lng: poi.longitude,
                r: MASK_RADIUS_DEFAULT,
              });
              if (this._persist({ maskZones: zones })) toast('已添加敏感地点');
            },
          });
        },
      });
    },

    removeMaskZone(e) {
      const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id;
      if (!id) return;
      const zones = (this.data.maskZones || []).filter((zone) => zone.id !== id);
      if (this._persist({ maskZones: zones })) toast('已移除，之后的分享不再遮挡该地点');
    },

    /* ⚠️ 只发事件,**不**自己 setData({show:false}):show 是父级传进来的属性,组件自己改一次,
       父级那份状态并没有跟着变;等父级下次想再打开时 setData(true) 与它自己记着的 true 无差异,
       不会重新下发,弹窗就再也打不开了。开关的所有权留在父级(cy-sheet 本身也是这么做的)。 */
    onClose() {
      // 提交中不让关:撤回请求在途时关掉弹窗,用户看不到成功/失败结果
      if (this.data.submitting) return;
      this.triggerEvent('close');
    },

    openOwnMedia() {
      if (this.data.submitting) return;
      wx.navigateTo({ url: '/pages/privacy/index', success: () => this.triggerEvent('close'),
        fail: () => toast('媒体管理暂时打不开，请重试') });
    },

    openPrivacyContract() {
      wx.openPrivacyContract({
        fail() {
          toast('隐私保护指引暂不可用');
        },
      });
    },

    disablePresenceSharing() {
      if (this.data.submitting) return;
      this.setData({ submitting: true });
      presencePrivacy.setSharing(app, null, false)
        .then(() => toast('已关闭附近的人位置分享'))
        .catch(error => toast(error.message))
        .finally(() => this.setData({ submitting: false }));
    },

    withdrawRoamLocationConsent() {
      if (this.data.submitting) return;
      this.setData({ submitting: true });
<<<<<<< public
      app.recordConsent({
        docType: 'privacy_policy',
        scene: 'roam_location',
        eventType: 'REVOKE',
      }).then(() => {
        bgTracker.revoke('roam');
        toast('已撤回漫游定位同意');
=======
      // F0385:与 pages/privacy 共用同一撤回编排——先本机停所有持有者+落撤回标记,再留痕。
      roamConsent.revoke({
        tracker: bgTracker,
        stopLocation: (options) => wx.stopLocationUpdate(options),
        recordConsent: (payload) => app.recordConsent(payload),
      }).then((result) => {
        const stopOk = !result || !result.stop || result.stop.ok;
        toast(stopOk ? '已撤回漫游定位同意' : '已撤回同意，但定位停止失败，请重试');
>>>>>>> private-feature/w2-r
        this.setData({ submitting: false });
      }).catch(() => {
        toast('已停定位，留痕失败请重试');
        this.setData({ submitting: false });
      });
    },
  },
});
