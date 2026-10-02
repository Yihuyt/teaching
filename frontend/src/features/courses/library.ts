import {
  MaterialViewKind,
  MaterialViewState,
  type CourseOutlineItemViewItemType,
  type CourseProgrammingProblemView,
  type CourseQuestionView,
  type MaterialView,
} from '@/api/generated'

export type LibraryRow =
  | { kind: 'folder'; material: MaterialView }
  | { kind: 'file'; material: MaterialView }
  | { kind: 'question'; question: CourseQuestionView }
  | { kind: 'problem'; problem: CourseProgrammingProblemView }

export interface LibraryContent {
  itemType: CourseOutlineItemViewItemType
  contentId: number
  title: string
}

export const libraryRowKindLabels: Readonly<Record<LibraryRow['kind'], string>> = {
  folder: '文件夹',
  file: '文件',
  question: '试题',
  problem: '编程题',
}

export function libraryRowKey(row: LibraryRow): string {
  switch (row.kind) {
    case 'folder':
    case 'file':
      return `material-${row.material.id}`
    case 'question':
      return `question-${row.question.id}`
    case 'problem':
      return `problem-${row.problem.id}`
  }
}

export function libraryRowTitle(row: LibraryRow): string {
  switch (row.kind) {
    case 'folder':
    case 'file':
      return row.material.name
    case 'question':
      return row.question.title
    case 'problem':
      return row.problem.title
  }
}

export function libraryRowUpdatedAt(row: LibraryRow): string {
  switch (row.kind) {
    case 'folder':
    case 'file':
      return row.material.updatedAt
    case 'question':
      return row.question.updatedAt
    case 'problem':
      return row.problem.updatedAt
  }
}

function matches(title: string, keyword: string): boolean {
  return keyword === '' || title.toLowerCase().includes(keyword.toLowerCase())
}

export function buildLibraryRows(
  materials: MaterialView[],
  questions: CourseQuestionView[],
  problems: CourseProgrammingProblemView[],
  atRoot: boolean,
  keyword: string,
): LibraryRow[] {
  const term = keyword.trim()
  const folders: LibraryRow[] = materials
    .filter((material) => material.kind === MaterialViewKind.folder)
    .map((material) => ({ kind: 'folder', material }))
  const files: LibraryRow[] = materials
    .filter((material) => material.kind === MaterialViewKind.file)
    .map((material) => ({ kind: 'file', material }))
  const rows: LibraryRow[] = [...folders, ...files]
  if (atRoot) {
    rows.push(...questions.map((question): LibraryRow => ({ kind: 'question', question })))
    rows.push(...problems.map((problem): LibraryRow => ({ kind: 'problem', problem })))
  }
  return rows.filter((row) => matches(libraryRowTitle(row), term))
}

/** 可加入课程内容的行 → 内容;文件夹、上传未完成的文件、没有题目的试题、未配置测试数据的编程题 → null(与后端就绪规则一致) */
export function rowContent(row: LibraryRow): LibraryContent | null {
  switch (row.kind) {
    case 'folder':
      return null
    case 'file':
      return row.material.state === MaterialViewState.active
        ? { itemType: 'material', contentId: row.material.id, title: row.material.name }
        : null
    case 'question':
      return row.question.itemCount > 0
        ? { itemType: 'question', contentId: row.question.id, title: row.question.title }
        : null
    case 'problem':
      return row.problem.testcaseConfirmed
        ? { itemType: 'programming_problem', contentId: row.problem.id, title: row.problem.title }
        : null
  }
}
