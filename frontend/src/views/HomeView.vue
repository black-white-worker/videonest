<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCategories, getHotVideos, getVideoList, type VideoCategory, type VideoListItem } from '../api/video'
import { getUnreadNotificationCount } from '../api/notification'

const router = useRouter()
const categories = ref<VideoCategory[]>([])
const videos = ref<VideoListItem[]>([])
const selectedCategoryId = ref<number | undefined>()
const keyword = ref('')
const isHotMode = ref(false)
const currentPage = ref(1)
const pageSize = ref(12)
const total = ref(0)
const categoryLoading = ref(false)
const videoLoading = ref(false)
const unreadNotificationCount = ref(0)

const user = computed(() => {
  try {
    const value = localStorage.getItem('userInfo')
    return value ? JSON.parse(value) as { nickname: string; role: 'USER' | 'ADMIN' } : null
  } catch { return null }
})

async function loadCategories() {
  try { categoryLoading.value = true; categories.value = await getCategories() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '获取视频分区失败') }
  finally { categoryLoading.value = false }
}

async function loadVideos() {
  try {
    videoLoading.value = true
    const result = await getVideoList({ categoryId: selectedCategoryId.value, keyword: keyword.value.trim() || undefined, page: currentPage.value, size: pageSize.value })
    videos.value = result.records
    total.value = result.total
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '获取视频列表失败') }
  finally { videoLoading.value = false }
}

async function loadHotVideos() {
  try { videoLoading.value = true; videos.value = await getHotVideos(pageSize.value); total.value = videos.value.length }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '获取热门视频失败') }
  finally { videoLoading.value = false }
}

function selectCategory(categoryId?: number) {
  isHotMode.value = false; keyword.value = ''; selectedCategoryId.value = categoryId; currentPage.value = 1; loadVideos()
}
function selectHotVideos() {
  isHotMode.value = true; keyword.value = ''; selectedCategoryId.value = undefined; currentPage.value = 1; loadHotVideos()
}
function searchVideos() {
  isHotMode.value = false; selectedCategoryId.value = undefined; currentPage.value = 1; loadVideos()
}
function handlePageChange(page: number) { currentPage.value = page; loadVideos(); window.scrollTo({ top: 0, behavior: 'smooth' }) }
function formatDuration(seconds: number) { return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` }
function formatNumber(value: number) { return value >= 10000 ? `${(value / 10000).toFixed(1)}万` : String(value) }
function formatDate(value: string) { return value ? new Date(value).toLocaleDateString('zh-CN') : '' }
function logout() { localStorage.removeItem('token'); localStorage.removeItem('userInfo'); ElMessage.success('已退出登录'); router.push('/login') }
function goVideoDetail(videoId: number) { router.push(`/video/${videoId}`) }
async function loadUnreadNotificationCount() { if (!localStorage.getItem('token')) return; try { unreadNotificationCount.value = await getUnreadNotificationCount() } catch { unreadNotificationCount.value = 0 } }
onMounted(async () => { await loadCategories(); await loadVideos(); await loadUnreadNotificationCount() })
</script>

<template>
  <main class="home-page">
    <header class="header"><div class="header-content">
      <div class="logo" @click="selectCategory()"><span class="logo-icon">▶</span><span>VideoNest</span></div>
      <div class="search-box"><el-input v-model="keyword" clearable placeholder="搜索视频标题" @keyup.enter="searchVideos" @clear="searchVideos"><template #append><el-button @click="searchVideos">搜索</el-button></template></el-input></div>
      <div v-if="user" class="user-area">
        <el-badge :value="unreadNotificationCount" :hidden="unreadNotificationCount === 0" :max="99"><el-button size="small" @click="router.push('/notifications')">通知</el-button></el-badge>
        <span class="nickname">{{ user.nickname }} <small>{{ user.role === 'ADMIN' ? '管理员' : '用户' }}</small></span>
        <el-button type="primary" plain size="small" @click="router.push('/profile')">个人主页</el-button>
        <el-button type="primary" plain size="small" @click="router.push('/upload')">投稿</el-button>
        <el-button v-if="user.role === 'ADMIN'" type="warning" plain size="small" @click="router.push('/admin/review')">审核投稿</el-button>
        <el-button type="danger" plain size="small" @click="logout">退出登录</el-button>
      </div>
      <el-button v-else type="primary" @click="router.push('/login')">登录 / 注册</el-button>
    </div></header>

    <section class="category-section"><div class="container"><el-skeleton :loading="categoryLoading" animated><template #default><div class="categories">
      <button class="category-button" :class="{ active: selectedCategoryId === undefined && !isHotMode && !keyword.trim() }" @click="selectCategory()">全部</button>
      <button v-for="category in categories" :key="category.id" class="category-button" :class="{ active: selectedCategoryId === category.id && !keyword.trim() }" @click="selectCategory(category.id)">{{ category.name }}</button>
      <button class="category-button" :class="{ active: isHotMode }" @click="selectHotVideos">热门</button>
    </div></template></el-skeleton></div></section>

    <section class="container content">
      <div class="section-title"><h2>{{ keyword.trim() ? `搜索结果：${keyword.trim()}` : (isHotMode ? '热门视频' : (selectedCategoryId === undefined ? '推荐视频' : '分区视频')) }}</h2><span>共 {{ total }} 个视频</span></div>
      <el-skeleton :loading="videoLoading" animated :count="8"><template #default>
        <div v-if="videos.length" class="video-grid"><article v-for="video in videos" :key="video.id" class="video-card" @click="goVideoDetail(video.id)">
          <div class="cover-box"><img :src="video.coverUrl" :alt="video.title" class="cover"><span class="duration">{{ formatDuration(video.duration) }}</span></div>
          <h3 :title="video.title">{{ video.title }}</h3><div class="video-meta"><span>{{ video.authorNickname }}</span><span>{{ formatNumber(video.viewCount) }} 播放</span></div><div class="video-meta secondary"><span>{{ video.categoryName }}</span><span>{{ formatDate(video.publishTime) }}</span></div>
        </article></div>
        <el-empty v-else :description="keyword.trim() ? '未找到匹配的视频' : '这个分区暂时还没有视频'" />
      </template></el-skeleton>
      <div v-if="!isHotMode && total > pageSize" class="pagination"><el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="total" layout="prev, pager, next" background @current-change="handlePageChange" /></div>
    </section>
  </main>
</template>

<style scoped>
.home-page{min-height:100vh;background:#f6f7f8;color:#18191c}.header{height:64px;background:#fff;border-bottom:1px solid #e7e7e7;position:sticky;top:0;z-index:10}.header-content,.container{width:min(1200px,calc(100% - 48px));margin:0 auto}.header-content{height:100%;display:flex;justify-content:space-between;align-items:center;gap:20px}.logo{display:flex;align-items:center;gap:8px;color:#1677ff;font-size:22px;font-weight:700;cursor:pointer}.logo-icon{width:28px;height:28px;display:inline-flex;justify-content:center;align-items:center;border-radius:50%;background:#1677ff;color:#fff;font-size:14px}.search-box{width:min(360px,30vw)}.user-area{display:flex;align-items:center;gap:12px;white-space:nowrap}.nickname{font-size:14px}.nickname small{margin-left:5px;color:#909399}.category-section{background:#fff;border-bottom:1px solid #ededed}.categories{min-height:62px;display:flex;align-items:center;gap:12px;overflow-x:auto}.category-button{flex-shrink:0;padding:7px 16px;border:0;border-radius:8px;background:#f1f2f3;color:#61666d;cursor:pointer}.category-button:hover{color:#1677ff}.category-button.active{background:#e8f3ff;color:#1677ff;font-weight:600}.content{padding:28px 0 48px}.section-title{margin-bottom:18px;display:flex;align-items:baseline;justify-content:space-between}.section-title h2{margin:0;font-size:22px}.section-title span{color:#9499a0;font-size:14px}.video-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:24px 16px}.video-card{cursor:pointer;min-width:0}.cover-box{position:relative;overflow:hidden;aspect-ratio:16/9;border-radius:8px;background:#e5e7eb}.cover{width:100%;height:100%;display:block;object-fit:cover;transition:transform .25s}.video-card:hover .cover{transform:scale(1.05)}.duration{position:absolute;right:7px;bottom:7px;padding:2px 5px;border-radius:4px;background:rgb(0 0 0 / 65%);color:#fff;font-size:12px}.video-card h3{overflow:hidden;margin:10px 0 8px;font-size:15px;line-height:22px;font-weight:500;white-space:nowrap;text-overflow:ellipsis}.video-card:hover h3{color:#1677ff}.video-meta{display:flex;justify-content:space-between;gap:8px;color:#61666d;font-size:13px;overflow:hidden}.video-meta span{overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.secondary{margin-top:5px;color:#9499a0}.pagination{display:flex;justify-content:center;margin-top:36px}@media (max-width:960px){.video-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.user-area{gap:8px}}@media (max-width:700px){.header-content,.container{width:min(100% - 28px,1200px)}.search-box{width:160px}.user-area .nickname,.user-area .el-button:not(:first-child){display:none}.video-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:18px 12px}}
</style>
