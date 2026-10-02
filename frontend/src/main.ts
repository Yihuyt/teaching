import { createPinia } from 'pinia'
import { createApp } from 'vue'

import 'element-plus/dist/index.css'
import 'katex/dist/katex.min.css'
import 'highlight.js/styles/github.css'
import '@/app/styles/index.css'

import App from '@/App.vue'
import { installSessionExpiryHandling } from '@/features/auth/sessionExpiry'
import { installElementPlus } from '@/app/plugins/elementPlus'
import router from '@/app/router'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
installElementPlus(app)
installSessionExpiryHandling(pinia, router)
app.mount('#app')
