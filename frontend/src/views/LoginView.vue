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
  <main class="page">
    <section class="card">
      <h1>VideoNest</h1>
      <p>视频社区平台</p>

      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top">
            <el-form-item label="用户名">
              <el-input
                v-model="loginForm.username"
                placeholder="请输入用户名"
              />
            </el-form-item>

            <el-form-item label="密码">
              <el-input
                v-model="loginForm.password"
                type="password"
                show-password
                placeholder="请输入密码"
                @keyup.enter="handleLogin"
              />
            </el-form-item>

            <el-button
              type="primary"
              :loading="loading"
              class="button"
              @click="handleLogin"
            >
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form label-position="top">
            <el-form-item label="用户名">
              <el-input
                v-model="registerForm.username"
                placeholder="4-20 位：字母、数字或下划线"
              />
            </el-form-item>

            <el-form-item label="昵称">
              <el-input
                v-model="registerForm.nickname"
                placeholder="请输入昵称"
              />
            </el-form-item>

            <el-form-item label="密码">
              <el-input
                v-model="registerForm.password"
                type="password"
                show-password
                placeholder="6-32 位密码"
                @keyup.enter="handleRegister"
              />
            </el-form-item>

            <el-button
              type="primary"
              :loading="loading"
              class="button"
              @click="handleRegister"
            >
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </section>
  </main>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e0f2fe, #fce7f3);
}

.card {
  width: 380px;
  padding: 36px;
  background: #ffffff;
  border-radius: 16px;
  box-shadow: 0 12px 35px rgb(0 0 0 / 12%);
}

h1 {
  margin: 0;
  text-align: center;
  color: #1677ff;
}

p {
  margin: 10px 0 24px;
  text-align: center;
  color: #909399;
}

.button {
  width: 100%;
}
</style>