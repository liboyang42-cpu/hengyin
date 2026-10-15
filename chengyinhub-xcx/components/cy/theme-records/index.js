Component({
  options: { addGlobalClass: true },
  properties: { title: String, historyOpen: Boolean, rows: Array, more: Boolean, busy: Boolean, error: String, readFailure: Boolean,
    cardSession: Number, card: Object, replayAllowed: Boolean, replayBusy: Boolean, replayUnconfirmed: Boolean },
  methods: {
    closeHistory() { this.triggerEvent('closehistory'); },
    moreHistory() { this.triggerEvent('morehistory'); },
    openCard(e) { this.triggerEvent('opencard', { id: e.currentTarget.dataset.id }); },
    closeCard() { this.triggerEvent('closecard'); },
    retryCard() { this.triggerEvent('retrycard'); },
    replay() { this.triggerEvent('replay'); },
    checkReplay() { this.triggerEvent('checkreplay'); },
  },
});
