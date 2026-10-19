const { copy } = require('../../../utils/code-text.js')

Component({
  properties: {
    code: { type: String, value: '' },
    hint: { type: String, value: '二维码暂时不可用，可长按复制验证码' },
  },
  methods: {
    onCopy() { copy(this.data.code) },
  },
})
