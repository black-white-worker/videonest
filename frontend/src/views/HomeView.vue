<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const user = computed(() => {
  const value = localStorage.getItem('userInfo')
  return value ? JSON.parse(value) : null
})

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  router.push('/login')
}
</script>

<template>
  <main class="page">
    <header>
      <h1>VideoNest</h1>

      <div v-if="user">
        <span>{{ user.nickname }}（{{ user.role }}）</span>
        <el-button type="danger" plain @click="logout">退出登录</el-button>
      </div>

      <el-button v-else type="primary" @click="router.push('/login')">
        登录 / 注册
      </el-button>
    </header>

    <section>
      <h2>视频首页</h2>
      <p>当前已完成用户注册、登录和 JWT Token 保存。</p>
    </section>
  </main>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f7f8fa;
}

header {
  height: 64px;
  padding: 0 8%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
}

header h1 {
  color: #1677ff;
}

header div {
  display: flex;
  align-items: center;
  gap: 16px;
}

section {
  max-width: 1000px;
  margin: 40px auto;
  padding: 30px;
  background: #fff;
  border-radius: 12px;
}
</style>