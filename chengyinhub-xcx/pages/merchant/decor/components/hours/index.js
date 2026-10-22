const hours = require('../../../utils/merchant-business-hours');
const { HOURS, MINUTES, pad2 } = require('../../../../../utils/time-picker-options');
Component({
  properties: {
    value: { type: String, value: '' },
    saving: { type: Boolean, value: false },
    error: { type: String, value: '' },
  },
  data: {
    visible: false,
    draft: hours.parse(''),
    hours: HOURS.map(pad2),
    minutes: MINUTES.map(pad2),
    localError: '',
    timePart: '',
    hourIndex: 10,
    minuteIndex: 0,
  },
  methods: {
    open() {
      this.setData({ visible: true, timePart: '', draft: hours.parse(this.data.value), localError: '' });
      this.triggerEvent('visible', { visible: true });
    },
    close() {
      if (!this.data.visible) return;
      this.setData({ visible: false, timePart: '' });
      this.triggerEvent('visible', { visible: false });
    },
    toggleDay(e) {
      if (this.data.saving) return;
      const i = e.currentTarget.dataset.index;
      this.setData({ ['draft.days[' + i + '].on']: !this.data.draft.days[i].on, localError: '' });
    },
    openStart() {
      this.openTime('start');
    },
    openEnd() {
      this.openTime('end');
    },
    openTime(part) {
      if (this.data.saving) return;
      if (this.data.timePart === part) { this.setData({ timePart: '' }); return; }
      const values = this.data.draft[part].split(':');
      this.setData({ timePart: part, hourIndex: Number(values[0]), minuteIndex: Number(values[1]) });
    },
    pickHour(e) {
      if (this.data.saving || !this.data.timePart) return;
      const hour = Number(e.currentTarget.dataset.index);
      this.setData({
        hourIndex: hour,
        ['draft.' + this.data.timePart]: pad2(hour) + ':' + pad2(this.data.minuteIndex),
      });
    },
    pickMinute(e) {
      if (this.data.saving || !this.data.timePart) return;
      const minute = Number(e.currentTarget.dataset.index);
      this.setData({
        minuteIndex: minute,
        ['draft.' + this.data.timePart]: pad2(this.data.hourIndex) + ':' + pad2(minute),
        localError: '',
        timePart: '',
      });
    },
    save() {
      if (this.data.saving) return;
      const result = hours.serialize(this.data.draft);
      if (result.error) return this.setData({ localError: result.error });
      this.triggerEvent('save', { value: result.value });
    },
  },
});
