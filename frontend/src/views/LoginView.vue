<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '../api/auth'

const router = useRouter()

const activeTab = ref('login')
const loading = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  password: '',
  nickname: ''
})

async function handleLogin() {
  if (!loginForm.username || !loginForm.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }

  try {
    loading.value = true

    const user = await login(loginForm)

    localStorage.setItem('token', user.token)
    localStorage.setItem('userInfo', JSON.stringify(user))

    ElMessage.success(`欢迎回来，${user.nickname}`)
    await router.push('/')
  } catch (error) {
    const message = error instanceof Error ? error.message : '登录失败'
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  if (
    !registerForm.username ||
    !registerForm.password ||
    !registerForm.nickname
  ) {
    ElMessage.warning('请填写完整注册信息')
    return
  }

  try {
    loading.value = true

    await register(registerForm)

    ElMessage.success('注册成功，请登录')

    loginForm.username = registerForm.username
    loginForm.password = registerForm.password
    activeTab.value = 'login'
  } catch (error) {
    const message = error instanceof Error ? error.message : '注册失败'
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <button class="back-home" @click="router.push('/')">
      <span>←</span>
      返回主站
    </button>

    <section class="brand-panel">
      <div class="brand-logo">
        <span>▶</span>
        VideoNest
      </div>
      <div class="brand-copy">
        <span class="eyebrow">CREATE · SHARE · CONNECT</span>
        <h1>在这里，发现每一种热爱</h1>
        <p>收藏喜欢的内容，关注有趣的创作者，也让你的作品被更多人看见。</p>
      </div>
      <div class="brand-features">
        <div><strong>高清</strong><span>多清晰度播放</span></div>
        <div><strong>互动</strong><span>评论与创作者交流</span></div>
        <div><strong>创作</strong><span>一站式投稿中心</span></div>
      </div>
      <div class="decor decor-one" />
      <div class="decor decor-two" />
    </section>

    <section class="auth-panel">
      <div class="auth-card">
        <div class="mobile-brand">
          <span>▶</span>
          VideoNest
        </div>
        <div class="auth-heading">
          <h2>{{ activeTab === 'login' ? '欢迎回来' : '加入 VideoNest' }}</h2>
          <p>{{ activeTab === 'login' ? '登录后继续探索感兴趣的内容' : '创建账号，开启你的视频旅程' }}</p>
        </div>

        <el-tabs v-model="activeTab" stretch class="auth-tabs">
          <el-tab-pane label="登录" name="login">
            <el-form label-position="top" @submit.prevent="handleLogin">
              <el-form-item label="用户名">
                <el-input
                  v-model="loginForm.username"
                  size="large"
                  autocomplete="username"
                  placeholder="请输入用户名"
                />
              </el-form-item>

              <el-form-item label="密码">
                <el-input
                  v-model="loginForm.password"
                  size="large"
                  type="password"
                  autocomplete="current-password"
                  show-password
                  placeholder="请输入密码"
                  @keyup.enter="handleLogin"
                />
              </el-form-item>

              <el-button
                native-type="submit"
                type="primary"
                size="large"
                :loading="loading"
                class="button"
              >
                登录
              </el-button>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="注册" name="register">
            <el-form label-position="top" @submit.prevent="handleRegister">
              <el-form-item label="用户名">
                <el-input
                  v-model="registerForm.username"
                  size="large"
                  autocomplete="username"
                  placeholder="4-20 位：字母、数字或下划线"
                />
              </el-form-item>

              <el-form-item label="昵称">
                <el-input
                  v-model="registerForm.nickname"
                  size="large"
                  autocomplete="nickname"
                  placeholder="请输入昵称"
                />
              </el-form-item>

              <el-form-item label="密码">
                <el-input
                  v-model="registerForm.password"
                  size="large"
                  type="password"
                  autocomplete="new-password"
                  show-password
                  placeholder="6-32 位密码"
                  @keyup.enter="handleRegister"
                />
              </el-form-item>

              <el-button
                native-type="submit"
                type="primary"
                size="large"
                :loading="loading"
                class="button"
              >
                注册
              </el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>

        <p class="agreement">继续即表示你同意遵守社区规范，共同维护友善的交流环境。</p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(420px, 1.08fr) minmax(420px, .92fr);
  overflow: hidden;
  background: #fff;
}

.back-home {
  position: absolute;
  z-index: 3;
  top: 24px;
  right: 28px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 12px;
  border: 0;
  border-radius: 9px;
  background: #f1f2f3;
  color: var(--vn-text-secondary);
  cursor: pointer;
}

.brand-panel {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  padding: clamp(42px, 6vw, 84px);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  background:
    radial-gradient(circle at 18% 12%, rgb(118 223 255 / 55%), transparent 30%),
    radial-gradient(circle at 78% 82%, rgb(251 114 153 / 42%), transparent 32%),
    linear-gradient(145deg, #112853, #183b74 52%, #172554);
  color: #fff;
}

.brand-logo,
.mobile-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 22px;
  font-weight: 800;
}

.brand-logo > span,
.mobile-brand > span {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  background: linear-gradient(135deg, var(--vn-primary), #5ed8ff);
  font-size: 13px;
  box-shadow: 0 8px 22px rgb(0 174 236 / 28%);
}

.brand-copy {
  position: relative;
  z-index: 1;
  max-width: 590px;
}

.eyebrow {
  color: #76dfff;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 2px;
}

.brand-copy h1 {
  margin: 18px 0 20px;
  font-size: clamp(40px, 5vw, 72px);
  line-height: 1.12;
  letter-spacing: -2.5px;
}

.brand-copy p {
  max-width: 510px;
  margin: 0;
  color: rgb(255 255 255 / 72%);
  font-size: 17px;
  line-height: 1.9;
}

.brand-features {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.brand-features div {
  padding: 16px;
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 14px;
  background: rgb(255 255 255 / 8%);
  backdrop-filter: blur(14px);
}

.brand-features strong,
.brand-features span {
  display: block;
}

.brand-features strong {
  margin-bottom: 4px;
}

.brand-features span {
  color: rgb(255 255 255 / 62%);
  font-size: 12px;
}

.decor {
  position: absolute;
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 50%;
}

.decor-one {
  width: 280px;
  height: 280px;
  top: -100px;
  right: -60px;
}

.decor-two {
  width: 430px;
  height: 430px;
  right: -160px;
  bottom: -190px;
}

.auth-panel {
  min-height: 100vh;
  padding: 80px 32px 40px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.auth-card {
  width: min(430px, 100%);
}

.mobile-brand {
  display: none;
  margin-bottom: 28px;
  color: var(--vn-text);
}

.auth-heading {
  margin-bottom: 24px;
}

.auth-heading h2 {
  margin: 0 0 8px;
  font-size: 30px;
  letter-spacing: -.8px;
}

.auth-heading p,
.agreement {
  margin: 0;
  color: var(--vn-text-muted);
}

.auth-tabs :deep(.el-tabs__header) {
  margin-bottom: 28px;
}

.auth-tabs :deep(.el-form-item) {
  margin-bottom: 20px;
}

.auth-tabs :deep(.el-input__wrapper) {
  min-height: 46px;
}

.button {
  width: 100%;
  margin-top: 6px;
  border: 0;
  border-radius: 9px;
}

.agreement {
  margin-top: 22px;
  text-align: center;
  font-size: 12px;
  line-height: 1.7;
}

@media (max-width: 900px) {
  .auth-page {
    grid-template-columns: 1fr;
    background:
      radial-gradient(circle at 10% 8%, rgb(118 223 255 / 20%), transparent 35%),
      var(--vn-page);
  }

  .brand-panel {
    display: none;
  }

  .auth-panel {
    min-height: 100vh;
    padding: 72px 24px 36px;
  }

  .auth-card {
    padding: 30px;
    border: 1px solid var(--vn-border-light);
    border-radius: 18px;
    background: #fff;
    box-shadow: var(--vn-shadow);
  }

  .mobile-brand {
    display: flex;
  }
}

@media (max-width: 520px) {
  .back-home {
    top: 14px;
    right: 14px;
  }

  .auth-panel {
    padding: 62px 14px 20px;
    align-items: flex-start;
  }

  .auth-card {
    padding: 24px 20px;
  }

  .auth-heading h2 {
    font-size: 25px;
  }
}
</style>
