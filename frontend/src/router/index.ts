import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import LoginView from '../views/LoginView.vue'
import VideoDetailView from '../views/VideoDetailView.vue'
import UploadVideoView from '../views/UploadVideoView.vue'
import AdminReviewView from '../views/AdminReviewView.vue'
import ProfileView from '../views/ProfileView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: HomeView
    },
    {
      path: '/login',
      component: LoginView
    },
    {
      path: '/video/:id',
      component: VideoDetailView
    },
    {
      path: '/upload',
      component: UploadVideoView
    },
    {
      path: '/admin/review',
      component: AdminReviewView
    },
    {
      path: '/profile',
      component: ProfileView
    }
  ]
})

export default router