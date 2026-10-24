// 节点 NPC 半屏(CR-927 商家上传节点 NPC):给自己的点位配一个出场角色。
//
// 合同与门店形象(pages/merchant/decor/ai-npc)完全一致,不另造一套:
//   · 预设像素形象存编码(px1:*),没有上传、没有对象存储;
//   · 照片在端上裁成 1:1 再像素化后才上传 —— 原图不出手机;
//   · 文本(名字/台词)由服务端同步送检,非预设头像异步送检(违规回调自动下架);
//   · 数据落 npc_profile scope_type=4,一节点一条 —— 所以这里只有「保存」,没有新建/删除。
//
// 失败口径(任务):失败走 cy-result-sheet(fail,2s 自愈)回到表单、输入不丢;
// 只有「读取回填失败」这种表单本身没法用的场景才自愈后关掉半屏。
const app = getApp();
const voiceJournal = require('../../../../../utils/voice-enrollment-journal.js');
const modal = require('../../../../../utils/modal.js');
const pixelAvatar = require('../../../../../utils/pixel-avatar.js');
const pixelPortrait = require('../../../../../utils/pixel-portrait.js');
// 单文件限额与共享上传入口同一真源(生产 application.yml 10MB),页面不自定 20MB。
const { MAX_UPLOAD_FILE_SIZE } = require('../../../../../utils/transport/upload-client.js');

/** 预览/导出倍数与门店形象页一致:32 格 × 8 / 12,整数倍不糊。 */
const PREVIEW_SCALE = 8;
const EXPORT_SCALE = 12;
const RESULT_HOLD_MS = 2000;

Component({
  properties: {
    show: { type: Boolean, value: false },
    nodeId: { type: Number, value: 0, optionalTypes: [String] },
    nodeName: { type: String, value: '' },
  },
  data: {
    loading: false,
    form: { name: '', avatar: '', greeting: '' },
    presetIds: pixelAvatar.AVATAR_IDS,
    presetId: pixelAvatar.AVATAR_IDS[0],
    source: 'preset',           // preset=内置像素形象 / photo=商家上传的照片形象
    avatarPreview: '',
    processing: false,
    saving: false,
    voiceStatus: 0,
    voiceUnknown: false,
    voiceHistory: [],
    voiceHistoryResult: '',
    voiceStatusText: '',
    voiceFile: null,
    voiceFileText: '',
    voiceBusy: false,
    voicePlaying: false,
    voiceSample: '',
    result: { show: false, kind: 'success', title: '', why: '', duration: RESULT_HOLD_MS },
  },
  observers: {
    'show': function (show) {
      if (show) this._onOpen();
      else this._onHide();
    },
  },
  lifetimes: {
    detached() {
      this._destroyed = true;
      this._teardown();
    },
  },
  methods: {
    // ===== 数据 =====

    _currentForm() {
      const memberId = String(app.getUserID() || '');
      const nodeId = String(this.data.nodeId || '');
      const version = this._formVersion;
      return () => !this._destroyed && this._formVersion === version
        && String(this.data.nodeId || '') === nodeId && String(app.getUserID() || '') === memberId;
    },

    /** 打开即回填:没有配过就是空表单。读取失败 = 表单没法用,2s 后自动关半屏。 */
    _onOpen() {
      this._destroyed = false;
      this._formVersion = (this._formVersion || 0) + 1;
      // New form generation owns its own modal/query flags; keep all original journal records.
      this._voiceConfirming = false;
      this._voiceRiskModalOpen = false;
      this._voiceRiskModalOwner = null;
      const memberId = String(app.getUserID() || '');
      const current = this._currentForm();
      this._photoMember = this._voiceMember = memberId;
      this._closeFormAfterResult = false;
      this._voiceSubmitted = this._resultSaved = false;
      this.setData({
        loading: true,
        processing: false,
        saving: false,
        voiceBusy: false,
        form: { name: '', avatar: '', greeting: '' },
        source: 'preset',
        presetId: pixelAvatar.AVATAR_IDS[0],
        avatarPreview: pixelAvatar.stringifyCode(pixelAvatar.AVATAR_IDS[0]),
        voiceFile: null,
        voiceFileText: '',
        voiceStatus: 0,
        voiceUnknown: false, voiceHistory: [], voiceHistoryResult: '', voiceError: '',
        voiceStatusText: '未配置（可选）',
        voiceSample: '',
        voicePlaying: false,
        result: { show: false, kind: 'success', title: '', why: '', duration: RESULT_HOLD_MS },
      });
      this._portrait = null;
      this._photoPath = '';
      this._request('/api/merchant/chapter-node/npc/detail', { nodeId: this.data.nodeId })
        .then((res) => {
          if (!current()) return;
          const d = res.data || null;
          const avatar = String((d && d.avatar) || '');
          const preset = pixelAvatar.isPixelAvatar(avatar);
          this.setData({
            loading: false,
            form: { name: (d && d.name) || '', avatar, greeting: (d && d.greeting) || '' },
            source: avatar && !preset ? 'photo' : 'preset',
            presetId: preset ? pixelAvatar.parseCode(avatar) : pixelAvatar.AVATAR_IDS[0],
            avatarPreview: avatar || pixelAvatar.stringifyCode(pixelAvatar.AVATAR_IDS[0]),
            voiceStatus: Number(d && d.voiceStatus) || 0,
            voiceSample: (d && d.voiceSample) || '',
          }, () => this._renderVoiceStatus());
          this.refreshVoice();
        })
        .catch((e) => {
          if (!current()) return;
          this.setData({ loading: false });
          this._fail(e.message || '读取失败，请重试', true);
        });
    },

    _onHide() {
      this._formVersion = (this._formVersion || 0) + 1;
      // New form generation owns its own modal/query flags; keep all original journal records.
      this._voiceConfirming = false;
      this._voiceRiskModalOpen = false;
      this._voiceRiskModalOwner = null;
      this._teardown();
    },

    _teardown() {
      clearTimeout(this._voiceTimer);
      if (this._audio) this._audio.stop();
    },

    onInput(e) {
      const key = e.currentTarget.dataset.key;
      if (key !== 'name' && key !== 'greeting') return;
      this.setData({ ['form.' + key]: e.detail.value });
    },

    pickPreset(e) {
      if (this.data.saving || this.data.processing) return;
      const id = e.currentTarget.dataset.id;
      if (!id || (this.data.presetIds || []).indexOf(id) < 0) return;
      this._portrait = null;
      this._photoPath = '';
      this.setData({
        source: 'preset',
        presetId: id,
        avatarPreview: pixelAvatar.stringifyCode(id),
      });
    },

    // ===== 照片形象(与门店形象同一端上像素化链路)=====

    choosePhoto() {
      const current = this._currentForm();
      if (this.data.saving || this.data.processing) return;
      wx.chooseMedia({
        count: 1,
        mediaType: ['image'],
        sourceType: ['album'],
        sizeType: ['compressed'],
        success: (res) => {
          if (!current()) return;
          const file = res.tempFiles && res.tempFiles[0];
          if (!file || !file.tempFilePath) return;
          // 先让商家自己裁一刀:没有人脸检测时,人自己框比任何启发式都准。
          if (typeof wx.cropImage === 'function') {
            wx.cropImage({
              src: file.tempFilePath,
              cropScale: '1:1',
              success: (r) => { if (current()) this._processPhoto(r.tempFilePath || file.tempFilePath); },
              fail: (err) => {
                if (!current()) return;
                if (!/cancel/i.test(err.errMsg || '')) this._fail('裁剪失败，请重新选择照片');
              },
            });
          } else {
            this._processPhoto(file.tempFilePath);
          }
        },
      });
    },

    /** 画到离屏 canvas 再 getImageData —— 小程序拿不到图片原始像素,只能过一遍 canvas。 */
    _processPhoto(tempPath) {
      const memberId = String(app.getUserID() || '');
      const current = this._currentForm();
      this.setData({ processing: true });
      this._canvas('#nnf-work')
        .then((cv) => {
          if (!current()) return;
          const g = cv.getContext('2d');
          return new Promise((resolve, reject) => {
            const img = cv.createImage();
            img.onload = () => {
              if (!current()) { resolve(null); return; }
              const box = pixelPortrait.squareCrop(img.width, img.height);
              const SIDE = 32 * PREVIEW_SCALE;
              cv.width = SIDE;
              cv.height = SIDE;
              g.clearRect(0, 0, SIDE, SIDE);
              g.drawImage(img, box.x, box.y, box.size, box.size, 0, 0, SIDE, SIDE);
              resolve(pixelPortrait.buildPortrait(g.getImageData(0, 0, SIDE, SIDE).data, SIDE, SIDE, { grid: 32 }));
            };
            img.onerror = () => reject(Error('image load fail'));
            img.src = tempPath;
          });
        })
        .then((portrait) => {
          if (!current()) return;
          this._photoMember = memberId;
          this._portrait = portrait;
          return this._exportPortrait();
        })
        .then((path) => {
          if (!current()) return;
          this._photoPath = path;
          this.setData({ processing: false, source: 'photo', avatarPreview: path });
        })
        .catch(() => {
          if (!current()) return;
          this._portrait = null;
          this.setData({ processing: false });
          this._fail('照片处理失败，换一张清晰的正面照片试试');
        });
    },

    /** 导出成文件准备上传。倍数比预览大,marker/主页都够用。 */
    _exportPortrait() {
      const current = this._currentForm();
      return this._canvas('#nnf-work').then((cv) => {
        if (!current()) return;
        const side = 32 * EXPORT_SCALE;
        cv.width = side;
        cv.height = side;
        const g = cv.getContext('2d');
        g.fillStyle = '#EDE6D6'; /* ds-ok: 导出像素肖像画布底色,属于导出图像数据 */
        g.fillRect(0, 0, side, side);
        pixelPortrait.drawPortrait(g, this._portrait, 0, 0, EXPORT_SCALE);
        return new Promise((resolve, reject) => {
          wx.canvasToTempFilePath({
            canvas: cv,
            fileType: 'png',
            success: (r) => resolve(r.tempFilePath),
            fail: reject,
          });
        });
      });
    },

    /** 小程序里必须走 SelectorQuery 拿 canvas 节点;拿不到直接失败,不静默。 */
    _canvas(id) {
      return new Promise((resolve, reject) => {
        this.createSelectorQuery()
          .select(id)
          .fields({ node: true, size: true })
          .exec((r) => {
            const node = r && r[0] && r[0].node;
            node ? resolve(node) : reject(Error('canvas not found: ' + id));
          });
      });
    },

    _upload(filePath) {
      return new Promise((resolve, reject) => {
        app.getUploadClient().uploadAll([filePath], {
          bizType: 'image_1_1',
          mapResult: (d) => d.url,
          onDone: (r) => {
            if (r.ok && r.results && r.results[0]) resolve(r.results[0]);
            else reject(Error((r.failures && r.failures[0] && r.failures[0].msg) || '上传失败'));
          },
        });
      });
    },

    // ===== 保存 =====

    _resolveAvatar() {
      const memberId = String(app.getUserID() || '');
      const current = this._currentForm();
      if (this.data.source !== 'photo') {
        return Promise.resolve(pixelAvatar.stringifyCode(this.data.presetId));
      }
      if (this._photoMember !== memberId) return Promise.reject(Error('登录状态已变更，请重新选择照片'));
      if (this._photoPath) return this._upload(this._photoPath).then((url) => {
        if (!current()) return;
        // 上传成功先记住 URL:保存失败重试不重复上传。
        this._photoPath = '';
        this.setData({ avatarPreview: url, 'form.avatar': url });
        return url;
      });
      if (this.data.form.avatar) return Promise.resolve(this.data.form.avatar);
      return Promise.reject(Error('形象未就绪'));
    },

    save() {
      const current = this._currentForm();
      if (this.data.saving || this.data.processing) return;
      const name = (this.data.form.name || '').trim();
      if (!name) return this._fail('请填写角色名字');
      if (!this.data.form.avatar && !this._photoPath && this.data.source === 'photo') {
        return this._fail('请选择形象');
      }
      if (!this.data.avatarPreview) return this._fail('请选择形象');
      this.setData({ saving: true });
      this._resolveAvatar()
        .then((avatar) => {
          if (!current()) return;
          return this._request('/api/merchant/chapter-node/npc/save', {
            nodeId: this.data.nodeId,
            name,
            avatar,
            greeting: (this.data.form.greeting || '').trim(),
          });
        })
        .then((res) => {
          if (!current()) return;
          const d = res.data || {};
          this.setData({
            saving: false,
            form: { name: d.name || name, avatar: d.avatar || this.data.form.avatar, greeting: d.greeting || '' },
            source: pixelAvatar.isPixelAvatar(d.avatar) ? 'preset' : 'photo',
            avatarPreview: d.avatar || this.data.avatarPreview,
          });
          this._ok('已保存');
        })
        .catch((e) => {
          if (!current()) return;
          this.setData({ saving: false });
          this._fail(e.message || '保存失败，请重试');
        });
    },

    // ===== 声音(可选)=====

    chooseVoice() {
      if (this.data.voiceUnknown) return;
      const memberId = String(app.getUserID() || '');
      let context;
      try { context = this._voiceContext(); } catch (e) { this._fail(e.message); return; }
      const current = context.current;
      if (this.data.voiceBusy || this.data.voiceStatus === 1) return;
      wx.chooseMessageFile({
        count: 1,
        type: 'file',
        extension: ['mp3', 'm4a', 'wav'],
        success: (res) => {
          if (!current()) return;
          const file = res.tempFiles && res.tempFiles[0];
          if (!file) return;
          if (!/\.(mp3|m4a|wav)$/i.test(file.name)) {
            return this._fail('请选择 MP3、M4A 或 WAV 文件');
          }
          // 上限与共享上传入口同源(10MB);给可操作路径,不把超限留到断连后只报「失败」。
          if (!(file.size > 0 && file.size <= MAX_UPLOAD_FILE_SIZE)) {
            return this._fail('文件过大，请压缩或更换文件后上传（单个文件最大10MB）');
          }
          this.setData({ voiceBusy: true });
          const audio = this._audioContext();
          audio.stop();
          audio.src = file.path;
          let tries = 0;
          const read = () => {
            if (!current()) return;
            const duration = audio.duration;
            if (duration > 0) {
              this.setData({ voiceBusy: false });
              if (duration < 10 || duration > 300) {
                return this._fail('录音时长需要在 10 秒至 5 分钟之间');
              }
              this._voiceMember = memberId;
              this._voiceFileContext = context;
              this.setData({
                voiceFile: { path: file.path, name: file.name, size: file.size, duration: Math.round(duration) },
                voiceFileText: file.name + ' · ' + Math.round(duration) + '″ · ' + (file.size / 1048576).toFixed(1) + 'MB',
              });
            } else if (++tries < 40) this._durationTimer = setTimeout(read, 100);
            else {
              this.setData({ voiceBusy: false });
              this._fail('无法读取录音时长，请换一个文件');
            }
          };
          read();
        },
      });
    },

    _voiceContext() {
      const form = this._currentForm();
      return voiceJournal.context(app, wx, () => this.data.show && form());
    },
    _voiceObserve(context, requestId, value) {
      if (!context.current()) return false;
      if (value.voiceSelected !== false && /^media-asset:[1-9][0-9]*$/.test(this.data.voiceSample)
          && value.voiceSample !== this.data.voiceSample) throw Error('返回结果与这段录音不一致，请查询原请求');
      const observed = voiceJournal.observe(wx, context.member, 'node:' + this.data.nodeId, requestId, value);
      this.setData({ voiceHistory: voiceJournal.recover(wx, context.member, 'node:' + this.data.nodeId, value.voiceHistory).records });
      if (!observed.selected) return false;
    this._voiceDisplayContext = context;
      const uncertain = ['IN_FLIGHT', 'UNKNOWN'].includes(value.voiceIntentState);
      this.setData({ voiceStatus: Number(value.voiceStatus), voiceSample: value.voiceSample || this.data.voiceSample,
        voiceUnknown: uncertain, voiceFile: uncertain || value.voiceIntentState === 'NOT_SENT' ? this.data.voiceFile : null }, () => this._renderVoiceStatus());
      return true;
    },
    refreshVoice() {
      let context, pending;
      try { context = this._voiceContext(); pending = voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId); }
      catch (e) { this.setData({ voiceError: e.message }); return Promise.resolve(); }
      const version = this._voiceStatusVersion = (this._voiceStatusVersion || 0) + 1;
      const current = () => context.current() && this._voiceStatusVersion === version;
      clearTimeout(this._voiceTimer);
      if (!current()) return Promise.resolve();
      this.setData({ voiceHistory: pending.records });
      return this._request('/api/merchant/chapter-node/npc/voice/status', { nodeId: this.data.nodeId, voiceRequestId: pending.active })
        .then((res) => {
          if (!current()) return;
          const value = res.data || res;
          if (!value.voiceRequestId && !pending.active) {
            this._voiceDisplayContext = context;
          this.setData({ voiceStatus: Number(value.voiceStatus) || 0, voiceSample: value.voiceSample || '' });
          if (Number(value.voiceStatus) === 1) this._voiceTimer = setTimeout(() => { if (current()) this.refreshVoice(); }, 5000);
          return;
          }
          if (this._voiceObserve(context, pending.active, value) && value.voiceIntentState === 'PENDING')
            this._voiceTimer = setTimeout(() => { if (current()) this.refreshVoice(); }, 5000);
        }).catch(() => {
          if (!current()) return;
          // Query failure does not decide the original command; only this still-current pending view polls again.
          if (this.data.voiceStatus === 1)
            this._voiceTimer = setTimeout(() => { if (current()) this.refreshVoice(); }, 5000);
        });
    },
    readVoiceHistory(e) {
      let context;
      try { context = this._voiceContext(); } catch (error) { this.setData({ voiceError: error.message }); return; }
      const requestId = e.currentTarget.dataset.request;
      const pending = voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId);
      if (!pending.records.some(row => row.requestId === requestId)) return;
      this._request('/api/merchant/chapter-node/npc/voice/status', { nodeId: this.data.nodeId, voiceRequestId: requestId }).then(res => {
        if (!context.current()) return;
        const value = res.data || res;
        voiceJournal.observe(wx, context.member, 'node:' + this.data.nodeId, requestId, value);
        this.setData({ voiceHistory: voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId).records,
          voiceHistoryResult: '原请求状态：' + value.voiceIntentState });

      }).catch(error => { if (context.current()) this.setData({ voiceError: error.message }); });
    },
    confirmNewVoiceRequest() {
      if (this._voiceConfirming || this._voiceRiskModalOpen || this.data.voiceBusy || !this.data.voiceUnknown) return;
      let context, original;
      try { context = this._voiceContext(); original = voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId).active; }
      catch (e) { this.setData({ voiceError: e.message }); return; }
      this._voiceConfirming = true;
      // Prefer querying the original known voice id/result; status never creates a new enrollment.
      return this.refreshVoice().then(() => {
        if (!context.current() || voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId).active !== original) return;
        if (this.data.voiceUnknown) this._confirmNewVoiceAfterRead();
      }).finally(() => { if (context.current()) this._voiceConfirming = false; });
    },
    _confirmNewVoiceAfterRead() {
      if (this.data.voiceBusy || !this.data.voiceUnknown) return;
      let context, original;
      try {
        context = this._voiceContext();
        const pending = voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId);
        original = pending.records.find(row => row.requestId === pending.active);
        if (!original || !original.intentId) throw Error('请先查询原请求，保留未知记录');
        if (!/^media-asset:[1-9][0-9]*$/.test(this.data.voiceSample)) throw Error('请先查询原录音身份');
      } catch (e) { this.setData({ voiceError: e.message }); return; }
      const sample = this.data.voiceSample;
      const confirmation = {};
    this._voiceRiskModalOwner = confirmation;
    this._voiceRiskModalOpen = true;
      modal.show({ title: '另起独立声音请求？',
        content: '原结果仍可能完成。新建可能产生重复音色或占用额度，费用以实际模型为准；原未知记录会保留，系统不会重发原请求。', confirmText: '确认新建',
        success: result => {
          if (!context.current() || this._voiceRiskModalOwner !== confirmation) return;
          this._voiceRiskModalOwner = null;
          this._voiceRiskModalOpen = false;
          if (!result || !result.confirm) return;
          this.setData({ voiceBusy: true, voiceError: '' });
          app.requestSeparateConsent('voice', context.current).then(() => {
            if (!context.current()) return;
            const row = voiceJournal.begin(wx, context.member, 'node:' + this.data.nodeId, original.requestId, true);
            return this._sendVoiceIntent(context, row, sample);
          }).catch(e => { if (context.current()) this.setData({ voiceBusy: false, voiceError: e.message }); });
        }, complete: () => { if (context.current() && this._voiceRiskModalOwner === confirmation) {
          this._voiceRiskModalOwner = null; this._voiceRiskModalOpen = false;
        } } });
    },
    _sendVoiceIntent(context, row, sample) {
      if (!context.current()) return Promise.resolve();
      voiceJournal.dispatched(wx, context.member, 'node:' + this.data.nodeId, row);
      this._voiceStatusVersion = (this._voiceStatusVersion || 0) + 1;
      return this._request('/api/merchant/chapter-node/npc/voice/enroll', { nodeId: this.data.nodeId, voiceSample: sample,
        voiceRequestId: row.requestId, voicePreviousIntentId: row.previousIntentId,
        voiceDuplicateChargeConsent: row.previousIntentId !== null }).then(res => {
          if (!context.current()) return;
          if (!this._voiceObserve(context, row.requestId, res.data || res)) return;
          this.setData({ voiceBusy: false });
          return this.refreshVoice();
        }).catch(e => {
          if (context.current() && voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId).active === row.requestId) {
            this.setData({ voiceBusy: false, voiceUnknown: e.message !== '语音服务未开通', voiceError: e.message || '原请求结果未知，请查询状态' });
            if (e.message !== '语音服务未开通') this.refreshVoice();
          }
        });
    },
    uploadVoice() {
      if (this.data.voiceBusy || this.data.voiceStatus === 1 || this.data.voiceUnknown || !this.data.voiceFile) return;
      let context, pending;
      try { context = this._voiceContext(); pending = voiceJournal.load(wx, context.member, 'node:' + this.data.nodeId); }
      catch (e) { this.setData({ voiceError: e.message }); return; }
      if ((this._voiceFileContext && !this._voiceFileContext.current()) || this._voiceMember !== context.member) {
        this.setData({ voiceError: '登录状态已变更，请重新选择录音' }); return;
      }
      const file = this.data.voiceFile;
      if (this._audio) this._audio.stop(); this.setData({ voiceBusy: true, voiceError: '' });
      const upload = () => file.url && /^media-asset:[1-9][0-9]*$/.test(file.url) ? Promise.resolve(file.url)
        : new Promise((resolve, reject) => app.getUploadClient().uploadAll([file.path], {
          fileSizes: [file.size], mapResult: d => d.assetId ? 'media-asset:' + d.assetId : '',
          onDone: r => { if (r.ok && r.results[0]) resolve(r.results[0]); else {
            const failure = r.failures && r.failures[0];
            reject(Error(failure && failure.reason === 'oversize' ? failure.msg : '录音上传失败或缺少资产身份，文件已保留'));
          } } }));
      app.requestSeparateConsent('voice', context.current).then(() => context.current() ? upload() : null)
        .then(sample => {
          if (!context.current()) return;
          this.setData({ 'voiceFile.url': sample, voiceSample: sample });
          const row = voiceJournal.begin(wx, context.member, 'node:' + this.data.nodeId, pending.active, false);
          return this._sendVoiceIntent(context, row, sample);
        }).catch(e => { if (context.current()) this.setData({ voiceBusy: false, voiceError: e.message }); });
    },

    resetVoice() {
      let context;
      try { context = this._voiceContext(); } catch (e) { this._fail(e.message); return; }
      const current = context.current;
      if (this.data.voiceBusy || this.data.voiceUnknown) return;
      this._request('/api/merchant/chapter-node/npc/voice/reset', { nodeId: this.data.nodeId })
        .then(() => {
          if (!current()) return;
          this._voiceStatusVersion = (this._voiceStatusVersion || 0) + 1;
          clearTimeout(this._voiceTimer);
          this.setData({ voiceStatus: 0, voiceSample: '', voiceFile: null, voiceFileText: '', voicePlaying: false },
            () => this._renderVoiceStatus());
        })
        .catch((e) => { if (current()) this._fail(e.message || '清除失败，请重试'); });
    },

    _renderVoiceStatus() {
      const status = Number(this.data.voiceStatus) || 0;
      const text = this.data.voiceUnknown ? '声音结果未知，请查询原请求' : status === 1 ? '声音生成中…（稍后自动刷新）'
        : status === 2 ? '声音已就绪'
        : status === 3 ? '上次生成失败，可以重新生成' : '未配置（可选）';
      this.setData({ voiceStatusText: text });
    },

    _audioContext() {
      if (this._audio) return this._audio;
      const audio = wx.createInnerAudioContext();
      audio.obeyMuteSwitch = false;
      audio.onEnded(() => { if (!this._destroyed) this.setData({ voicePlaying: false }); });
      audio.onStop(() => { if (!this._destroyed) this.setData({ voicePlaying: false }); });
      audio.onError(() => { if (!this._destroyed) this.setData({ voicePlaying: false }); });
      this._audio = audio;
      return audio;
    },

    playVoice() {
      const original = this.data.voiceFile ? this._voiceFileContext : this._voiceDisplayContext;
      if (!original || !original.current()) { this._fail('登录或表单已变化，请重新读取声音'); return; }
      const audio = this._audioContext();
      if (this.data.voicePlaying) {
        audio.stop();
        return;
      }
      const src = (this.data.voiceFile && this.data.voiceFile.path) || this.data.voiceSample;
      if (!src) return;
      audio.src = src;
      audio.play();
      this.setData({ voicePlaying: true });
    },

    // ===== 弹层与结果 =====

    onSheetClose() {
      if (this.data.saving || this.data.processing) return;
      this.triggerEvent('close', { saved: false });
    },

    _ok(title) {
      this._closeFormAfterResult = true;
      this._resultSaved = true;
      this.setData({ result: { show: true, kind: 'success', title, why: '', duration: RESULT_HOLD_MS } });
    },

    /** @param closeForm 读取失败这类「表单本身没法用」的失败:自愈后连半屏一起收掉。 */
    _fail(why, closeForm) {
      this._closeFormAfterResult = !!closeForm;
      this._resultSaved = false;
      this.setData({ result: { show: true, kind: 'fail', title: '没有成功', why: why || '请稍后重试', duration: RESULT_HOLD_MS } });
    },

    onResultClose() {
      const closeForm = this._closeFormAfterResult;
      const saved = this._resultSaved;
      this._closeFormAfterResult = false;
      this._resultSaved = false;
      this.setData({ 'result.show': false });
      if (closeForm) this.triggerEvent('close', { saved: !!saved });
    },

    _request(url, data) {
      return new Promise((resolve, reject) => {
        app.sendRequest({
          url,
          method: 'POST',
          hideLoading: true,
          data: JSON.stringify(data || {}),
          header: { 'Content-Type': 'application/json' },
          success: (res) => {
            if (res && String(res.code) === '200') resolve(res);
            else reject(Error((res && res.msg) || '请求失败，请重试'));
          },
          successStatusAbnormal: (res) => reject(Error((res && res.msg) || '请求失败，请重试')),
          fail: () => reject(Error('网络异常，请稍后重试')),
        });
      });
    },
  },
});
