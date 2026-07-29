import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { logger } from './utils/logger'

const app = createApp(App)

app.config.errorHandler = (error, _instance, info) => {
  logger.error('vue.unhandled.error', {
    info,
    message: error instanceof Error ? error.message : String(error)
  })
}

window.addEventListener('unhandledrejection', event => {
  logger.error('browser.unhandled.rejection', {
    message:
      event.reason instanceof Error
        ? event.reason.message
        : String(event.reason)
  })
})

app.use(router).use(ElementPlus).mount('#app')
