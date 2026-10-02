import { config } from '@vue/test-utils'

Object.defineProperty(window, 'scrollTo', {
  configurable: true,
  value: () => undefined,
})

config.global.stubs = {
  transition: false,
  'el-icon': true,
}
