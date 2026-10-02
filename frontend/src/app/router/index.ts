import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { AccountViewRole } from '@/api/generated'
import { usePlatformStore } from '@/stores/platform'
import { useSessionStore } from '@/stores/session'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/features/auth/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/service-unavailable',
    name: 'service-unavailable',
    component: () => import('@/app/views/ServiceUnavailableView.vue'),
    meta: { public: true, title: '服务不可用' },
  },
  {
    path: '/',
    component: () => import('@/app/layouts/PortalLayout.vue'),
    children: [
      { path: '', redirect: '/home' },
      {
        path: 'home',
        name: 'home',
        component: () => import('@/features/portal/views/HomeView.vue'),
        meta: { title: '首页' },
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/features/courses/views/CourseListView.vue'),
        meta: { title: '课程' },
      },
      {
        path: 'courses/:id',
        name: 'course-detail',
        component: () => import('@/features/courses/views/CourseDetailView.vue'),
        meta: { title: '课程详情' },
      },
      {
        path: 'courses/:courseId/knowledge-graphs/:id',
        name: 'course-knowledge-graph',
        component: () => import('@/features/knowledgegraph/views/CourseKnowledgeGraphView.vue'),
        meta: { title: '课程知识图谱' },
      },
      {
        path: 'courses/:courseId/questions/:questionId',
        name: 'course-question',
        component: () => import('@/features/courses/views/CourseQuestionView.vue'),
        meta: { title: '试题' },
      },
      {
        path: 'courses/:courseId/problems/:problemId',
        name: 'course-problem',
        component: () => import('@/features/programming/views/ProblemDetailView.vue'),
        meta: { title: '编程题' },
      },
      {
        path: 'courses/:courseId/problems/:problemId/submissions/:submissionId',
        name: 'course-submission',
        component: () => import('@/features/programming/views/SubmissionDetailView.vue'),
        meta: { title: '提交详情' },
      },
      {
        path: 'account',
        name: 'account',
        component: () => import('@/features/account/views/AccountView.vue'),
        meta: { title: '账户设置' },
      },
      {
        path: 'account/password',
        name: 'account-password',
        component: () => import('@/features/account/views/AccountView.vue'),
        meta: { title: '修改密码' },
      },
    ],
  },
  {
    path: '/focus',
    component: () => import('@/app/layouts/FocusLayout.vue'),
    children: [
      {
        path: 'courses/:courseId/blockcoding/:projectId',
        name: 'blockcoding-studio',
        component: () => import('@/features/blockcoding/views/BlockCodingStudioView.vue'),
        meta: { title: '积木创作台' },
      },
      {
        path: 'courses/:courseId/coursewares/:coursewareId/play',
        name: 'courseware-play',
        component: () => import('@/features/courseware/views/CoursewarePlayView.vue'),
        meta: { title: '课件学习' },
      },
      {
        path: 'admin/courses/:courseId/blockcoding/:projectId',
        name: 'admin-blockcoding-studio',
        component: () => import('@/features/blockcoding/views/BlockCodingStudioView.vue'),
        meta: {
          title: '积木创作台',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
      {
        path: 'admin/courses/:courseId/coursewares/:coursewareId/edit',
        name: 'admin-courseware-edit',
        component: () => import('@/features/courseware/views/AdminCoursewareWorkbenchView.vue'),
        meta: {
          title: '课件工作台',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
      {
        path: 'admin/courses/:courseId/questions/new',
        name: 'admin-question-create',
        component: () => import('@/features/courses/views/AdminQuestionEditorView.vue'),
        meta: {
          title: '创建试题',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
      {
        path: 'admin/courses/:courseId/questions/:questionId/edit',
        name: 'admin-question-edit',
        component: () => import('@/features/courses/views/AdminQuestionEditorView.vue'),
        meta: {
          title: '编辑试题',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
      {
        path: 'admin/courses/:courseId/programming-problems/new',
        name: 'admin-programming-problem-create',
        component: () => import('@/features/programming/views/AdminProgrammingProblemEditorView.vue'),
        meta: {
          title: '创建编程题',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
      {
        path: 'admin/courses/:courseId/programming-problems/:problemId/edit',
        name: 'admin-programming-problem-edit',
        component: () => import('@/features/programming/views/AdminProgrammingProblemEditorView.vue'),
        meta: {
          title: '编辑编程题',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
      {
        path: 'admin/courses/:courseId/knowledge-graphs/:graphId/edit',
        name: 'admin-knowledge-graph-editor',
        component: () => import('@/features/knowledgegraph/views/AdminKnowledgeGraphEditorView.vue'),
        meta: {
          title: '编辑图谱',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
      {
        path: 'admin/courses/:courseId/coursewares/:coursewareId/preview',
        name: 'admin-courseware-preview',
        component: () => import('@/features/courseware/views/AdminCoursewarePreviewView.vue'),
        meta: {
          title: '课件预览',
          roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher],
        },
      },
    ],
  },
  {
    path: '/admin',
    component: () => import('@/app/layouts/AdminLayout.vue'),
    meta: { roles: [AccountViewRole.root, AccountViewRole.admin, AccountViewRole.teacher] },
    children: [
      { path: '', redirect: '/admin/courses' },
      {
        path: 'accounts',
        name: 'admin-accounts',
        component: () => import('@/features/admin/views/AdminAccountsView.vue'),
        meta: { title: '账户管理', roles: [AccountViewRole.root, AccountViewRole.admin] },
      },
      {
        path: 'courses',
        name: 'admin-courses',
        component: () => import('@/features/courses/views/AdminCoursesView.vue'),
        meta: { title: '课程管理' },
      },
      {
        path: 'courses/:courseId',
        name: 'admin-course-detail',
        component: () => import('@/features/courses/views/AdminCourseDetailView.vue'),
        meta: { title: '课程管理详情' },
      },
      {
        path: 'announcements',
        name: 'admin-announcements',
        component: () => import('@/features/admin/views/AdminAnnouncementsView.vue'),
        meta: { title: '公告管理', roles: [AccountViewRole.root, AccountViewRole.admin] },
      },
      {
        path: 'platform',
        name: 'admin-platform',
        component: () => import('@/features/admin/views/AdminPlatformView.vue'),
        meta: { title: '设置', roles: [AccountViewRole.root] },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/app/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (to.name === 'service-unavailable') {
    return true
  }

  const platform = usePlatformStore()
  const session = useSessionStore()

  try {
    await platform.initialize()
  } catch {
    return { name: 'service-unavailable' }
  }

  if (to.name === 'login') {
    try {
      await session.initialize()
    } catch {
      return { name: 'service-unavailable' }
    }
    if (session.authenticated) return { name: 'home' }
    return true
  }

  if (to.meta.public) {
    return true
  }

  try {
    await session.initialize()
  } catch {
    return { name: 'service-unavailable' }
  }

  if (!session.authenticated) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    }
  }

  if (session.account?.mustResetPassword && to.name !== 'account-password') {
    return { name: 'account-password' }
  }

  const roles = to.meta.roles
  if (roles && session.account && !roles.includes(session.account.role)) {
    return { name: 'home' }
  }

  return true
})

export default router
