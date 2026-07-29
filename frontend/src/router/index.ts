import { createRouter, createWebHistory } from 'vue-router'

const HomeView = () => import('../views/HomeView.vue')
const LoginView = () => import('../views/LoginView.vue')
const VideoDetailView = () => import('../views/VideoDetailView.vue')
const UploadVideoView = () => import('../views/UploadVideoView.vue')
const AdminReviewView = () => import('../views/AdminReviewView.vue')
const ProfileView = () => import('../views/ProfileView.vue')
const AdminCommentView = () => import('../views/AdminCommentView.vue')
const NotificationView = () => import('../views/NotificationView.vue')
const AdminRecycleBinView = () => import('../views/AdminRecycleBinView.vue')
const AdminDeadLetterView = () => import('../views/AdminDeadLetterView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: HomeView,
      meta: { title: '首页' }
    },
    {
      path: '/login',
      component: LoginView,
      meta: { title: '登录与注册' }
    },
    {
      path: '/video/:id',
      component: VideoDetailView,
      meta: { title: '视频播放' }
    },
    {
      path: '/upload',
      component: UploadVideoView,
      meta: { title: '发布视频' }
    },
    {
      path: '/admin/review',
      component: AdminReviewView,
      meta: { title: '投稿审核' }
    },
    {
      path: '/profile',
      component: ProfileView,
      meta: { title: '个人中心' }
    },
    {
      path: '/admin/comments',
      component: AdminCommentView,
      meta: { title: '评论管理' }
    },
    {
      path: '/notifications',
      component: NotificationView,
      meta: { title: '消息中心' }
    },
    {
      path: '/admin/recycle-bin',
      component: AdminRecycleBinView,
      meta: { title: '视频回收站' }
    },
    {
      path: '/admin/dead-letters',
      component: AdminDeadLetterView,
      meta: { title: '死信处理' }
    }
  ]
})

router.afterEach(to => {
  document.title = `${String(to.meta.title || '视频社区')} - VideoNest`
})

export default router
