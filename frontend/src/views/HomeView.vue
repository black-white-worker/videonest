<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCategories, getHotVideos, getVideoList, type VideoCategory, type VideoListItem } from '../api/video'
import { getUnreadNotificationCount } from '../api/notification'
import SiteHeader from '../components/SiteHeader.vue'

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

interface WallpaperSlide {
  imageUrl: string
  eyebrow: string
  title: string
  description: string
}

const wallpaperSlides: WallpaperSlide[] = [
  {
    imageUrl: 'https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=2400&q=88',
    eyebrow: 'VIDEONEST · 灵感正在发生',
    title: '在光影之间，遇见新的故事',
    description: '每日轮换的网络风景壁纸，让内容发现从第一眼开始。'
  },
  {
    imageUrl: 'https://images.unsplash.com/photo-1470770841072-f978cf4d019e?auto=format&fit=crop&w=2400&q=88',
    eyebrow: 'DISCOVER · 去更远的地方',
    title: '世界很大，镜头替你先出发',
    description: '汇集创作者的新鲜表达，也收藏属于你的片刻共鸣。'
  },
  {
    imageUrl: 'https://images.unsplash.com/photo-1501785888041-af3ef285b470?auto=format&fit=crop&w=2400&q=88',
    eyebrow: 'TRENDING · 此刻全站热门',
    title: '跟上热度，也保留自己的方向',
    description: '热门内容持续更新，发现正在被大家讨论的作品。'
  }
]
const activeWallpaperIndex = ref(0)
let wallpaperTimer: number | undefined

const user = computed(() => {
  try {
    const value = localStorage.getItem('userInfo')
    return value ? JSON.parse(value) as { nickname: string; role: 'USER' | 'ADMIN' } : null
  } catch { return null }
})

const featuredVideo = computed(() => videos.value[0] || null)
const spotlightVideos = computed(() => videos.value.slice(1, 7))
const remainingVideos = computed(() => videos.value.slice(7))
const activeWallpaper = computed(() => wallpaperSlides[activeWallpaperIndex.value])
const userInitial = computed(() => user.value?.nickname?.slice(0, 1).toUpperCase() || '我')
const sectionTitle = computed(() => {
  if (keyword.value.trim()) return `“${keyword.value.trim()}”的搜索结果`
  if (isHotMode.value) return '全站热门'
  if (selectedCategoryId.value === undefined) return '为你推荐'
  return categories.value.find(item => item.id === selectedCategoryId.value)?.name || '分区视频'
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
function showWallpaper(index: number) { activeWallpaperIndex.value = (index + wallpaperSlides.length) % wallpaperSlides.length }
function nextWallpaper() { showWallpaper(activeWallpaperIndex.value + 1) }
function restartWallpaperTimer() {
  if (wallpaperTimer) window.clearInterval(wallpaperTimer)
  wallpaperTimer = window.setInterval(nextWallpaper, 8000)
}
function handleUserCommand(command: string) {
  if (command === 'logout') {
    logout()
    return
  }
  router.push(command)
}
async function loadUnreadNotificationCount() { if (!localStorage.getItem('token')) return; try { unreadNotificationCount.value = await getUnreadNotificationCount() } catch { unreadNotificationCount.value = 0 } }
onMounted(async () => {
  restartWallpaperTimer()
  await loadCategories()
  await loadVideos()
  await loadUnreadNotificationCount()
})
onBeforeUnmount(() => { if (wallpaperTimer) window.clearInterval(wallpaperTimer) })
</script>

<template>
  <main class="home-page">
    <SiteHeader overlay :elevated="false">
      <template #nav>
        <button class="site-nav-link" @click="selectCategory()">首页</button>
        <button class="site-nav-link" @click="selectHotVideos">热门</button>
        <button v-if="user" class="site-nav-link" @click="router.push('/profile')">动态</button>
      </template>

      <template #search>
        <el-input
          v-model="keyword"
          class="top-search"
          clearable
          placeholder="搜索你感兴趣的视频"
          @keyup.enter="searchVideos"
          @clear="searchVideos"
        >
          <template #append>
            <el-button aria-label="搜索" @click="searchVideos">搜索</el-button>
          </template>
        </el-input>
      </template>

      <template #actions>
        <template v-if="user">
          <el-badge
            :value="unreadNotificationCount"
            :hidden="unreadNotificationCount === 0"
            :max="99"
          >
            <button class="header-action" @click="router.push('/notifications')">
              <span class="header-action__icon">消息</span>
              <small>通知</small>
            </button>
          </el-badge>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <button class="user-trigger">
              <span class="user-avatar">{{ userInitial }}</span>
              <span class="user-name">{{ user.nickname }}</span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="/profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="/upload">创作中心</el-dropdown-item>
                <el-dropdown-item v-if="user.role === 'ADMIN'" command="/admin/review">
                  管理后台
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button class="upload-button" type="primary" @click="router.push('/upload')">
            + 投稿
          </el-button>
        </template>
        <el-button v-else type="primary" round @click="router.push('/login')">
          登录
        </el-button>
      </template>
    </SiteHeader>

    <section
      class="hero wallpaper-hero"
      :style="{ '--hero-cover': `url(${activeWallpaper.imageUrl})` }"
    >
      <div class="hero__veil" />
      <div class="container hero__content">
        <div class="hero__copy">
          <span class="hero__eyebrow">{{ activeWallpaper.eyebrow }}</span>
          <h1>{{ activeWallpaper.title }}</h1>
          <p>{{ activeWallpaper.description }}</p>
          <div class="hero__actions">
            <el-button class="hero-primary-button" size="large" @click="selectCategory()">
              浏览推荐
            </el-button>
            <el-button class="hero-secondary-button" size="large" @click="selectHotVideos">
              查看热门
            </el-button>
          </div>
          <div class="wallpaper-dots" aria-label="壁纸切换">
            <button
              v-for="(_, index) in wallpaperSlides"
              :key="index"
              :class="{ active: activeWallpaperIndex === index }"
              :aria-label="`切换到第 ${index + 1} 张壁纸`"
              @click="showWallpaper(index); restartWallpaperTimer()"
            />
          </div>
        </div>
        <div class="hero__side-note">
          <span>{{ isHotMode ? '热门模式' : '今日推荐' }}</span>
          <strong>{{ total }}</strong>
          <small>个正在被发现的视频</small>
        </div>
      </div>
    </section>

    <section class="channel-bar">
      <div class="container channel-bar__inner">
        <div class="channel-entry">
          <button
            class="channel-orb"
            :class="{ active: selectedCategoryId === undefined && !isHotMode && !keyword.trim() }"
            @click="selectCategory()"
          >
            <strong>首</strong>
            <span>推荐</span>
          </button>
          <button class="channel-orb hot" :class="{ active: isHotMode }" @click="selectHotVideos">
            <strong>热</strong>
            <span>热门</span>
          </button>
        </div>
        <el-skeleton :loading="categoryLoading" animated>
          <template #default>
            <div class="categories">
              <button
                v-for="category in categories"
                :key="category.id"
                class="category-button"
                :class="{ active: selectedCategoryId === category.id && !keyword.trim() }"
                @click="selectCategory(category.id)"
              >
                {{ category.name }}
              </button>
            </div>
          </template>
        </el-skeleton>
        <div v-if="user" class="channel-shortcuts">
          <button @click="router.push('/profile')">我的收藏</button>
          <button @click="router.push('/notifications')">消息中心</button>
        </div>
      </div>
    </section>

    <section class="container content">
      <div class="section-title">
        <div>
          <span class="section-kicker">{{ isHotMode ? 'TRENDING' : 'DISCOVER' }}</span>
          <h2>{{ sectionTitle }}</h2>
        </div>
        <span>共 {{ total }} 个视频</span>
      </div>

      <el-skeleton :loading="videoLoading" animated :count="8">
        <template #default>
          <div v-if="videos.length" class="rank-showcase">
            <article
              v-if="featuredVideo"
              class="rank-featured-card"
              tabindex="0"
              @click="goVideoDetail(featuredVideo.id)"
              @keyup.enter="goVideoDetail(featuredVideo.id)"
            >
              <div class="rank-featured-cover">
                <img :src="featuredVideo.coverUrl" :alt="featuredVideo.title" loading="eager">
                <div class="rank-featured-overlay">
                  <span class="rank-number">NO.1</span>
                  <div>
                    <h3>{{ featuredVideo.title }}</h3>
                    <p>{{ featuredVideo.description || '本期首位推荐作品，点击进入观看完整内容。' }}</p>
                    <div class="rank-featured-meta">
                      <span>▶ {{ formatNumber(featuredVideo.viewCount) }} 播放</span>
                      <span>♥ {{ formatNumber(featuredVideo.likeCount) }}</span>
                      <span>{{ formatDuration(featuredVideo.duration) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </article>

            <div class="rank-compact-grid">
              <article
                v-for="(video, index) in spotlightVideos"
                :key="video.id"
                class="video-card compact-video-card"
                tabindex="0"
                @click="goVideoDetail(video.id)"
                @keyup.enter="goVideoDetail(video.id)"
              >
                <div class="cover-box">
                  <img :src="video.coverUrl" :alt="video.title" class="cover" loading="lazy">
                  <span class="compact-rank">{{ String(index + 2).padStart(2, '0') }}</span>
                  <div class="cover-gradient" />
                  <div class="cover-meta">
                    <span>▶ {{ formatNumber(video.viewCount) }}</span>
                    <span>{{ formatDuration(video.duration) }}</span>
                  </div>
                </div>
                <div class="video-card__body">
                  <h3 :title="video.title">{{ video.title }}</h3>
                  <div class="author-line">
                    <span class="mini-avatar">{{ video.authorNickname.slice(0, 1) }}</span>
                    <div>
                      <strong>{{ video.authorNickname }}</strong>
                      <span>{{ video.categoryName }} · {{ formatDate(video.publishTime) }}</span>
                    </div>
                  </div>
                </div>
              </article>
            </div>
          </div>
          <div v-if="remainingVideos.length" class="rank-more-grid">
            <article
              v-for="(video, index) in remainingVideos"
              :key="video.id"
              class="video-card compact-video-card"
              tabindex="0"
              @click="goVideoDetail(video.id)"
              @keyup.enter="goVideoDetail(video.id)"
            >
              <div class="cover-box">
                <img :src="video.coverUrl" :alt="video.title" class="cover" loading="lazy">
                <span class="compact-rank">{{ String(index + 8).padStart(2, '0') }}</span>
                <div class="cover-gradient" />
                <div class="cover-meta">
                  <span>▶ {{ formatNumber(video.viewCount) }}</span>
                  <span>{{ formatDuration(video.duration) }}</span>
                </div>
              </div>
              <div class="video-card__body">
                <h3 :title="video.title">{{ video.title }}</h3>
                <div class="author-line">
                  <span class="mini-avatar">{{ video.authorNickname.slice(0, 1) }}</span>
                  <div>
                    <strong>{{ video.authorNickname }}</strong>
                    <span>{{ video.categoryName }} · {{ formatDate(video.publishTime) }}</span>
                  </div>
                </div>
              </div>
            </article>
          </div>
          <el-empty
            v-else
            :description="keyword.trim() ? '未找到匹配的视频' : '这个分区暂时还没有视频'"
          />
        </template>
      </el-skeleton>

      <div v-if="!isHotMode && total > pageSize" class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="handlePageChange"
        />
      </div>
    </section>
  </main>
</template>

<style scoped>
.home-page {
  min-height: 100vh;
  background: var(--vn-page);
  color: var(--vn-text);
}

.container {
  width: min(1360px, calc(100% - 64px));
  margin: 0 auto;
}

.top-search :deep(.el-input__wrapper) {
  border-radius: 9px 0 0 9px;
  background: #fff;
  box-shadow: 0 0 0 1px #94a3b8 inset !important;
}

.top-search :deep(.el-input-group__append) {
  border-radius: 0 9px 9px 0;
  background: #0284c7;
  box-shadow: 0 0 0 1px #0284c7 inset;
}

.top-search :deep(.el-input-group__append .el-button) {
  color: #fff;
  font-weight: 700;
}

.top-search :deep(.el-input__inner::placeholder) {
  color: #64748b;
}

.header-action,
.user-trigger {
  border: 0;
  background: transparent;
  cursor: pointer;
}

.header-action {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 52px;
  padding: 5px 8px;
  gap: 2px;
  border: 1px solid #cbd5e1;
  border-radius: 9px;
  background: #f8fafc;
  color: #334155;
}

.header-action__icon {
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
}

.header-action small {
  color: #475569;
  font-size: 12px;
  font-weight: 600;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 9px;
  border: 1px solid #cbd5e1;
  border-radius: 999px;
}

.user-trigger:hover {
  background: #e0f2fe;
  border-color: #7dd3fc;
}

.user-avatar,
.mini-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--vn-primary), #6ddcff);
  color: #fff;
  font-weight: 700;
}

.user-avatar {
  width: 34px;
  height: 34px;
}

.user-name {
  max-width: 90px;
  overflow: hidden;
  color: #0f172a;
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-button {
  min-width: 82px;
  border: 0;
  background: var(--vn-accent);
  box-shadow: 0 6px 16px rgb(251 114 153 / 22%);
}

.upload-button:hover {
  background: #ff8bad;
}

.hero {
  position: relative;
  height: clamp(270px, 31vw, 420px);
  overflow: hidden;
  background:
    radial-gradient(circle at 20% 20%, rgb(89 216 255 / 42%), transparent 38%),
    radial-gradient(circle at 78% 32%, rgb(251 114 153 / 32%), transparent 36%),
    linear-gradient(135deg, #263a6b, #172554 58%, #0f172a);
}

.hero.has-cover {
  background-image: var(--hero-cover);
  background-position: center 38%;
  background-size: cover;
}

.hero__veil {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgb(10 19 45 / 90%) 0%, rgb(10 19 45 / 65%) 48%, rgb(10 19 45 / 22%) 100%),
    linear-gradient(0deg, var(--vn-page) 0, transparent 22%);
}

.hero__content {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 30px;
  color: #fff;
}

.hero__copy {
  width: min(650px, 70%);
}

.hero__eyebrow,
.section-kicker {
  color: #76dfff;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 1.5px;
}

.hero h1 {
  display: -webkit-box;
  overflow: hidden;
  margin: 14px 0 12px;
  font-size: clamp(28px, 3.4vw, 50px);
  line-height: 1.18;
  letter-spacing: -1.5px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.hero p {
  margin: 0;
  color: rgb(255 255 255 / 78%);
  font-size: 15px;
}

.hero__actions {
  margin-top: 26px;
  display: flex;
  gap: 10px;
}

.hero__side-note {
  width: 170px;
  padding: 18px 20px;
  border: 1px solid rgb(255 255 255 / 18%);
  border-radius: 16px;
  background: rgb(255 255 255 / 10%);
  backdrop-filter: blur(16px);
}

.hero__side-note span,
.hero__side-note small {
  display: block;
  color: rgb(255 255 255 / 68%);
}

.hero__side-note strong {
  display: block;
  margin: 5px 0 2px;
  font-size: 36px;
}

.channel-bar {
  position: relative;
  z-index: 2;
  margin-top: -5px;
  border-bottom: 1px solid var(--vn-border-light);
  background: var(--vn-surface);
}

.channel-bar__inner {
  min-height: 92px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 28px;
}

.channel-entry {
  display: flex;
  gap: 14px;
}

.channel-orb {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  color: var(--vn-text-secondary);
  cursor: pointer;
}

.channel-orb strong {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #6ed08f;
  color: #fff;
}

.channel-orb.hot strong {
  background: var(--vn-accent);
}

.channel-orb.active span {
  color: var(--vn-primary);
  font-weight: 700;
}

.categories {
  display: grid;
  grid-template-columns: repeat(6, minmax(72px, 1fr));
  gap: 9px;
}

.category-button,
.channel-shortcuts button {
  height: 34px;
  border: 1px solid var(--vn-border-light);
  border-radius: 7px;
  background: #f6f7f8;
  color: var(--vn-text-secondary);
  cursor: pointer;
  transition: .2s;
}

.category-button:hover,
.category-button.active,
.channel-shortcuts button:hover {
  border-color: #bdeeff;
  background: var(--vn-primary-soft);
  color: var(--vn-primary-dark);
}

.channel-shortcuts {
  display: grid;
  grid-template-columns: repeat(2, auto);
  gap: 8px;
}

.channel-shortcuts button {
  padding: 0 12px;
}

.content {
  padding: 38px 0 64px;
}

.section-title {
  margin-bottom: 22px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.section-title h2 {
  margin: 4px 0 0;
  font-size: 25px;
  letter-spacing: -.5px;
}

.section-title > span {
  color: var(--vn-text-muted);
  font-size: 13px;
}

.video-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 30px 20px;
}

.video-card {
  min-width: 0;
  outline: none;
  cursor: pointer;
}

.cover-box {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  border-radius: 10px;
  background: #dce4f0;
}

.cover {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  transition: transform .35s ease;
}

.cover-gradient {
  position: absolute;
  inset: 44% 0 0;
  background: linear-gradient(transparent, rgb(0 0 0 / 70%));
}

.cover-meta {
  position: absolute;
  right: 9px;
  bottom: 7px;
  left: 9px;
  display: flex;
  justify-content: space-between;
  color: #fff;
  font-size: 12px;
  text-shadow: 0 1px 2px #000;
}

.video-card:hover .cover,
.video-card:focus .cover {
  transform: scale(1.045);
}

.video-card__body h3 {
  display: -webkit-box;
  overflow: hidden;
  min-height: 44px;
  margin: 10px 0 9px;
  color: var(--vn-text);
  font-size: 15px;
  font-weight: 600;
  line-height: 22px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  transition: color .2s;
}

.video-card:hover h3,
.video-card:focus h3 {
  color: var(--vn-primary-dark);
}

.author-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mini-avatar {
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  font-size: 12px;
}

.author-line div {
  min-width: 0;
}

.author-line strong,
.author-line span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.author-line strong {
  color: var(--vn-text-secondary);
  font-size: 12px;
  font-weight: 500;
}

.author-line div span {
  margin-top: 2px;
  color: var(--vn-text-muted);
  font-size: 11px;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 44px;
}

@media (max-width: 1100px) {
  .container {
    width: min(100% - 40px, 1360px);
  }

  .channel-bar__inner {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .channel-shortcuts {
    display: none;
  }

  .categories {
    grid-template-columns: repeat(4, minmax(72px, 1fr));
  }

  .video-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .container {
    width: min(100% - 28px, 1360px);
  }

  .header-action,
  .user-name {
    display: none;
  }

  .upload-button {
    min-width: auto;
    padding: 8px 12px;
  }

  .hero {
    height: 280px;
  }

  .hero__copy {
    width: 100%;
  }

  .hero__side-note {
    display: none;
  }

  .hero h1 {
    font-size: 28px;
  }

  .channel-bar__inner {
    min-height: 118px;
    grid-template-columns: 1fr;
    gap: 12px;
    padding: 14px 0;
  }

  .channel-entry {
    display: none;
  }

  .categories {
    display: flex;
    overflow-x: auto;
    gap: 8px;
    scrollbar-width: none;
  }

  .category-button {
    min-width: 78px;
    flex: 0 0 auto;
  }

  .content {
    padding-top: 28px;
  }

  .video-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 24px 12px;
  }

  .video-card__body h3 {
    font-size: 14px;
    line-height: 20px;
  }
}
</style>

<style scoped>
/* 首页采用独立网络壁纸横幅，视频封面只出现在内容榜单。 */
.wallpaper-hero {
  height: clamp(330px, 30vw, 440px);
  padding-top: 72px;
  background-color: #162133;
  background-image: var(--hero-cover);
  background-position: center;
  background-size: cover;
  transition: background-image .45s ease;
}

.wallpaper-hero .hero__veil {
  background:
    linear-gradient(90deg, rgb(6 13 28 / 82%) 0%, rgb(6 13 28 / 49%) 48%, rgb(6 13 28 / 18%) 100%),
    linear-gradient(0deg, rgb(6 13 28 / 48%), transparent 46%);
}

.wallpaper-hero .hero__content {
  align-items: center;
}

.wallpaper-hero .hero__copy {
  width: min(700px, 72%);
  padding-top: 12px;
  text-shadow: 0 2px 18px rgb(0 0 0 / 34%);
}

.wallpaper-hero .hero__eyebrow {
  color: #b9f1ff;
}

.wallpaper-hero h1 {
  max-width: 680px;
  margin-top: 10px;
  font-size: clamp(32px, 3.4vw, 52px);
}

.wallpaper-hero p {
  max-width: 600px;
  color: rgb(255 255 255 / 88%);
  font-size: 16px;
}

.hero-primary-button,
.hero-secondary-button {
  min-width: 112px;
  border: 0;
  font-weight: 800;
}

.hero-primary-button {
  background: #fff;
  color: #111827;
}

.hero-primary-button:hover {
  background: #eaf9ff;
  color: #075985;
}

.hero-secondary-button {
  border: 1px solid rgb(255 255 255 / 46%);
  background: rgb(15 23 42 / 50%);
  color: #fff;
  backdrop-filter: blur(10px);
}

.hero-secondary-button:hover {
  border-color: #fff;
  background: rgb(255 255 255 / 18%);
  color: #fff;
}

.wallpaper-dots {
  margin-top: 22px;
  display: flex;
  gap: 8px;
}

.wallpaper-dots button {
  width: 8px;
  height: 8px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 52%);
  cursor: pointer;
  transition: width .2s, background .2s;
}

.wallpaper-dots button.active {
  width: 28px;
  background: #fff;
}

.wallpaper-hero .hero__side-note {
  background: rgb(7 15 30 / 42%);
}

.rank-showcase {
  display: grid;
  grid-template-columns: minmax(430px, 1.35fr) minmax(0, 2fr);
  align-items: stretch;
  gap: 18px;
}

.rank-featured-card {
  min-width: 0;
  outline: none;
  cursor: pointer;
}

.rank-featured-cover {
  position: relative;
  height: 100%;
  min-height: 448px;
  overflow: hidden;
  border-radius: 13px;
  background: #dce4f0;
  box-shadow: 0 14px 34px rgb(15 23 42 / 13%);
}

.rank-featured-cover > img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  transition: transform .45s ease;
}

.rank-featured-card:hover img,
.rank-featured-card:focus img {
  transform: scale(1.035);
}

.rank-featured-overlay {
  position: absolute;
  inset: 0;
  padding: 24px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  background: linear-gradient(180deg, rgb(0 0 0 / 5%) 28%, rgb(4 9 20 / 88%) 100%);
  color: #fff;
}

.rank-number,
.compact-rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  font-weight: 900;
  letter-spacing: .4px;
}

.rank-number {
  align-self: flex-start;
  padding: 6px 10px;
  background: #fb7299;
  color: #111827;
  box-shadow: 0 6px 16px rgb(251 114 153 / 28%);
  font-size: 12px;
}

.rank-featured-overlay h3 {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: #fff;
  font-size: clamp(23px, 2.2vw, 33px);
  line-height: 1.3;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.rank-featured-overlay p {
  display: -webkit-box;
  max-width: 620px;
  overflow: hidden;
  margin: 9px 0 13px;
  color: rgb(255 255 255 / 76%);
  font-size: 13px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.rank-featured-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  color: rgb(255 255 255 / 86%);
  font-size: 12px;
}

.rank-compact-grid,
.rank-more-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px 16px;
}

.rank-more-grid {
  margin-top: 30px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.compact-video-card {
  min-width: 0;
}

.compact-video-card .cover-box {
  border-radius: 9px;
  box-shadow: 0 5px 16px rgb(15 23 42 / 7%);
}

.compact-rank {
  position: absolute;
  z-index: 2;
  top: 8px;
  left: 8px;
  min-width: 28px;
  height: 25px;
  padding: 0 6px;
  background: rgb(255 255 255 / 90%);
  color: #111827;
  font-size: 11px;
  backdrop-filter: blur(8px);
}

@media (max-width: 1180px) {
  .rank-showcase {
    grid-template-columns: minmax(360px, 1.1fr) minmax(0, 1.55fr);
  }

  .rank-compact-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .rank-featured-cover {
    min-height: 610px;
  }

  .rank-more-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 860px) {
  .wallpaper-hero {
    height: 360px;
  }

  .rank-showcase {
    grid-template-columns: 1fr;
  }

  .rank-featured-cover {
    min-height: 390px;
  }

  .rank-compact-grid,
  .rank-more-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 620px) {
  .wallpaper-hero {
    height: 350px;
    padding-top: 62px;
  }

  .wallpaper-hero .hero__copy {
    width: 100%;
  }

  .wallpaper-hero h1 {
    font-size: 29px;
  }

  .wallpaper-hero p {
    font-size: 13px;
  }

  .rank-featured-cover {
    min-height: 350px;
  }

  .rank-featured-overlay {
    padding: 18px;
  }

  .rank-compact-grid,
  .rank-more-grid {
    gap: 22px 10px;
  }

  .rank-more-grid {
    margin-top: 24px;
  }
}
</style>
