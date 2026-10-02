import {
  CourseProgrammingProblemViewDifficulty,
  MaterialViewKind,
  MaterialViewState,
  type CourseProgrammingProblemView,
  type CourseQuestionView,
  type MaterialView,
} from '@/api/generated'
import { buildLibraryRows, libraryRowKey, rowContent } from '@/features/courses/library'

function material(
  id: number,
  name: string,
  kind: MaterialViewKind,
  state: MaterialViewState = MaterialViewState.active,
): MaterialView {
  return {
    id,
    courseId: 6,
    parentId: null,
    name,
    kind,
    state,
    contentType: kind === MaterialViewKind.file ? 'application/pdf' : null,
    sizeBytes: kind === MaterialViewKind.file ? 10 : null,
    sha256: kind === MaterialViewKind.file ? 'a'.repeat(64) : null,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
  }
}

const question = {
  id: 3,
  courseId: 6,
  title: '单元测验',
  itemCount: 2,
  updatedAt: '2026-08-02T00:00:00Z',
} as CourseQuestionView
function problem(testcaseConfirmed: boolean): CourseProgrammingProblemView {
  return {
    id: 4,
    title: 'A + B',
    difficulty: CourseProgrammingProblemViewDifficulty.easy,
    testcaseConfirmed,
    updatedAt: '2026-08-03T00:00:00Z',
  }
}

const folder = material(1, '第一章', MaterialViewKind.folder)
const file = material(2, '讲义.pdf', MaterialViewKind.file)
const pending = material(5, '上传中.pdf', MaterialViewKind.file, MaterialViewState.pending_upload)

describe('buildLibraryRows', () => {
  it('文件夹在前,根目录再接试题与编程题', () => {
    const rows = buildLibraryRows([file, folder], [question], [problem(true)], true, '')
    expect(rows.map(libraryRowKey)).toEqual(['material-1', 'material-2', 'question-3', 'problem-4'])
  })

  it('非根目录只有文件 / 文件夹', () => {
    const rows = buildLibraryRows([file, folder], [question], [problem(true)], false, '')
    expect(rows.map(libraryRowKey)).toEqual(['material-1', 'material-2'])
  })

  it('关键字对三类内容同一规则,不区分大小写', () => {
    const rows = buildLibraryRows([file, folder], [question], [problem(true)], true, 'a + b')
    expect(rows.map(libraryRowKey)).toEqual(['problem-4'])
    expect(buildLibraryRows([file, folder], [question], [], true, '测验').map(libraryRowKey)).toEqual([
      'question-3',
    ])
  })
})

describe('rowContent', () => {
  it('文件夹、上传中的文件、未配置测试数据的编程题、还没有题目的试题不能加入课程内容', () => {
    expect(rowContent({ kind: 'folder', material: folder })).toBeNull()
    expect(rowContent({ kind: 'file', material: pending })).toBeNull()
    expect(rowContent({ kind: 'problem', problem: problem(false) })).toBeNull()
    expect(rowContent({ kind: 'question', question: { ...question, itemCount: 0 } })).toBeNull()
  })

  it('就绪内容映射为课程内容条目', () => {
    expect(rowContent({ kind: 'file', material: file })).toEqual({
      itemType: 'material',
      contentId: 2,
      title: '讲义.pdf',
    })
    expect(rowContent({ kind: 'question', question })).toEqual({
      itemType: 'question',
      contentId: 3,
      title: '单元测验',
    })
    expect(rowContent({ kind: 'problem', problem: problem(true) })).toEqual({
      itemType: 'programming_problem',
      contentId: 4,
      title: 'A + B',
    })
  })
})
