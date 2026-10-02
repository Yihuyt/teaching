import type { CourseOutlineItemView } from '@/api/generated'

export type ResultsTarget = Pick<CourseOutlineItemView, 'itemType' | 'contentId' | 'title'>
