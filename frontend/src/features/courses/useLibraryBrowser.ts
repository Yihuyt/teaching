import { computed, ref, type Ref } from 'vue'

import { api, errorMessage } from '@/api/client'
import type { CourseProgrammingProblemView, CourseQuestionView, MaterialView } from '@/api/generated'
import { createLatestRequestGuard } from '@/shared/latestRequest'
import { buildLibraryRows, type LibraryRow } from '@/features/courses/library'
import { assertMaterialContract } from '@/features/courses/material'

export interface LibraryCrumb {
  id: number | null
  name: string
}

/**
 * 资料库浏览状态:目录导航 + 三类内容的拉取 + 本地关键字过滤。
 * 请求快照只含 courseId / parentId——关键字是本地过滤,输入时不应让在途请求失效。
 */
export function useLibraryBrowser(courseId: Ref<number>) {
  const crumbs = ref<LibraryCrumb[]>([{ id: null, name: '资料库' }])
  const keyword = ref('')
  const loading = ref(true)
  const error = ref('')
  const materials = ref<MaterialView[]>([])
  const questions = ref<CourseQuestionView[]>([])
  const problems = ref<CourseProgrammingProblemView[]>([])

  const parentId = computed(() => crumbs.value.at(-1)?.id ?? null)
  const atRoot = computed(() => parentId.value === null)
  const rows = computed<LibraryRow[]>(() =>
    buildLibraryRows(materials.value, questions.value, problems.value, atRoot.value, keyword.value),
  )

  const requests = createLatestRequestGuard(
    () => ({ courseId: courseId.value, parentId: parentId.value }),
    (left, right) => left.courseId === right.courseId && left.parentId === right.parentId,
  )

  /** 换课程 / 换目录才显示骨架屏;同一目录的重新加载保留现有表格 */
  let loadedKey: string | null = null

  async function load(): Promise<void> {
    const request = requests.begin()
    const { courseId: course, parentId: parent } = request.snapshot
    const key = `${course}:${parent ?? 'root'}`
    loading.value = loadedKey !== key
    error.value = ''
    try {
      const [materialsResponse, questionsResponse, problemsResponse] = await Promise.all([
        api.courseMaterialList(course, parent === null ? undefined : { parentId: parent }),
        parent === null ? api.courseQuestionList(course) : null,
        parent === null ? api.courseProgrammingProblemList(course) : null,
      ])
      if (!requests.isCurrent(request)) return
      assertMaterialContract(materialsResponse.data)
      materials.value = materialsResponse.data
      questions.value = questionsResponse ? questionsResponse.data : []
      problems.value = problemsResponse ? problemsResponse.data : []
      loadedKey = key
    } catch (cause: unknown) {
      if (!requests.isCurrent(request)) return
      error.value = errorMessage(cause)
    } finally {
      if (requests.isCurrent(request)) loading.value = false
    }
  }

  function enterFolder(material: MaterialView): void {
    crumbs.value = [...crumbs.value, { id: material.id, name: material.name }]
    keyword.value = ''
    void load()
  }

  function goToCrumb(index: number): void {
    crumbs.value = crumbs.value.slice(0, index + 1)
    keyword.value = ''
    void load()
  }

  function reset(): void {
    crumbs.value = [{ id: null, name: '资料库' }]
    keyword.value = ''
    void load()
  }

  return { crumbs, keyword, loading, error, rows, parentId, load, enterFolder, goToCrumb, reset }
}
