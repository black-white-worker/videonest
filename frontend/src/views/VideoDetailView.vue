<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getVideoDetail, type VideoDetail } from '../api/video'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const video = ref<VideoDetail | null>(null)

function formatDuration(seconds: number) {
  const minute = Math.floor(seconds / 60)
  const second = seconds % 60

  return `${String(minute).padStart(2, '0')}:${String(second).padStart(2, '0')}`
}

function formatNumber(value: number) {
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }

  return value.toString()
}

function formatDate(value: string) {
  if (!value) {
    return ''
  }

  return new Date(value).toLocaleString('zh-CN')
}

async function loadVideoDetail() {
  const id = Number(route.params.id)

  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.error('视频 ID 不合法')
    router.push('/')
    return
  }

  try {
    loading.value = true
    video.value = await getVideoDetail(id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '获取视频详情失败'
    ElMessage.error(message)
    router.push('/')
  } finally {
    loading.value = false
  }
}

function goHome() {
  router.push('/')
}

onMounted(() => {
  loadVideoDetail()
})
</script>

<template>
  <main class="detail-page">
    <header class="header">
      <div class="header-content">
        <div class="logo" @click="goHome">
          <span class="logo-icon">▶</span>
          <span>VideoNest</span>
        </div>

        <el-button @click="goHome">返回首页</el-button>
      </div>
    </header>

    <section class="container">
      <el-skeleton :loading="loading" animated :rows="12">
        <template #default>
          <template v-if="video">
            <div class="video-player-box">
              <video
                :poster="video.coverUrl"
                :src="video.videoUrl"
                controls
                class="video-player"
              >
                当前浏览器不支持视频播放。
              </video>
            </div>

            <section class="video-info">
              <div class="category">
                {{ video.categoryName }}
              </div>

              <h1>{{ video.title }}</h1>

              <div class="statistics">
                <span>{{ formatNumber(video.viewCount) }} 播放</span>
                <span>{{ formatNumber(video.likeCount) }} 点赞</span>
                <span>{{ formatNumber(video.favoriteCount) }} 收藏</span>
                <span>{{ formatDate(video.publishTime) }}</span>
              </div>

              <div class="author">
                <div class="avatar">
                  {{ video.authorNickname.slice(0, 1) }}
                </div>

                <div>
                  <strong>{{ video.authorNickname }}</strong>
                  <p>@{{ video.authorUsername }}</p>
                </div>

                <el-button plain type="primary">关注</el-button>
              </div>

              <el-divider />

              <div class="description">
                <h3>视频简介</h3>
                <p>{{ video.description || '该视频暂未填写简介。' }}</p>
              </div>
            </section>
          </template>
        </template>
      </el-skeleton>
    </section>
  </main>
</template>

<style scoped>
.detail-page {
  min-height: 100vh;
  background: #f6f7f8;
  color: #18191c;
}

.header {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid #e7e7e7;
}

.header-content,
.container {
  width: min(1200px, calc(100% - 48px));
  margin: 0 auto;
}

.header-content {
  height: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1677ff;
  font-size: 22px;
  font-weight: 700;
  cursor: pointer;
}

.logo-icon {
  width: 28px;
  height: 28px;
  display: inline-flex;
  justify-content: center;
  align-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 14px;
}

.container {
  padding-top: 30px;
  padding-bottom: 50px;
}

.video-player-box {
  overflow: hidden;
  border-radius: 12px;
  background: #000;
  box-shadow: 0 8px 26px rgb(0 0 0 / 14%);
}

.video-player {
  width: 100%;
  max-height: 680px;
  display: block;
  background: #000;
}

.video-info {
  margin-top: 24px;
  padding: 26px 30px;
  border-radius: 12px;
  background: #fff;
}

.category {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 5px;
  background: #e8f3ff;
  color: #1677ff;
  font-size: 13px;
}

h1 {
  margin: 14px 0 10px;
  font-size: 26px;
}

.statistics {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  color: #9499a0;
  font-size: 14px;
}

.author {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 24px;
}

.avatar {
  width: 44px;
  height: 44px;
  display: flex;
  justify-content: center;
  align-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 19px;
}

.author strong {
  display: block;
}

.author p {
  margin: 4px 0 0;
  color: #9499a0;
  font-size: 13px;
}

.author .el-button {
  margin-left: auto;
}

.description h3 {
  margin: 0 0 10px;
  font-size: 17px;
}

.description p {
  margin: 0;
  color: #61666d;
  line-height: 1.8;
  white-space: pre-wrap;
}

@media (max-width: 700px) {
  .header-content,
  .container {
    width: min(100% - 28px, 1200px);
  }

  .video-info {
    padding: 20px;
  }

  h1 {
    font-size: 21px;
  }
}
</style>