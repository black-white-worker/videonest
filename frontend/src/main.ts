import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { logger } from './utils/logger'
import { bootstrapSession } from './utils/auth'

const app = createApp(App)

app.config.errorHandler = (error, _instance, info) => {
  logger.error('vue.unhandled.error', {
    info,
    message: error instanceof Error ? error.message : String(error)
  })
}

window.addEventListener('unhandledrejection', (event) => {
  logger.error('browser.unhandled.rejection', {
    message: event.reason instanceof Error ? event.reason.message : String(event.reason)
  })
})

// 先尝试用 refresh Cookie 换回内存 token，再挂载应用：
// 否则页面刷新后首次进入受保护路由会被守卫误判为未登录。
bootstrapSession().finally(() => {
  app.use(router).use(ElementPlus).mount('#app')
})
