export type {
  Block,
  BlockType,
  LeafBlock,
  BlockBase,
  HeadingBlock,
  ParagraphBlock,
  BulletsBlock,
  BulletItem,
  FormulaBlock,
  CodeBlock,
  TableBlock,
  ChartBlock,
  ChartSeries,
  ChartType,
  EmphasisBlock,
  ImageBlock,
  CalloutBlock,
  CalloutVariant,
  ColumnsBlock,
  QuizChoiceBlock,
  QuizOption,
} from '@/features/courseware/dsl/blocks.ts'
export { BLOCK_TYPES, BLOCK_TYPE_LABELS } from '@/features/courseware/dsl/blocks.ts'

export type {
  Action,
  ActionType,
  ActionTarget,
  HighlightAction,
  RevealAction,
  PauseAction,
  SpeechSegment,
} from '@/features/courseware/dsl/actions.ts'

export type {
  Stage,
  Scene,
  SceneType,
  PresetName,
  ThemeName,
  InteractiveContent,
  VideoContent,
  BlockSize,
  PinFrame,
  BlockLayout,
} from '@/features/courseware/dsl/stage.ts'
export {
  isBlocklessScene,
  PRESET_NAMES,
  PRESET_LABELS,
  BLOCK_SIZES,
  BLOCK_SIZE_SCALES,
  BLOCK_SIZE_LABELS,
  MIN_PIN_WIDTH,
  MIN_PIN_HEIGHT,
  FREE_HEIGHT_TYPES,
} from '@/features/courseware/dsl/stage.ts'

export type { WidgetType, WidgetOutline } from '@/features/courseware/dsl/widgets.ts'
export { WIDGET_TYPES, WIDGET_TYPE_LABELS } from '@/features/courseware/dsl/widgets.ts'

export type { OutlineScene, OutlineSceneType, OutlineImage, OutlineIllustration, ImageAspectRatio } from '@/features/courseware/dsl/outline.ts'
export { IMAGE_ASPECT_RATIOS, IMAGE_ASPECT_RATIO_LABELS } from '@/features/courseware/dsl/outline.ts'
export { LIMITS } from '@/features/courseware/dsl/limits.ts'

export type {
  EditOp,
  EditSpeechSegment,
  UpdateStageMetaOp,
  AddSceneOp,
  AddInteractiveSceneOp,
  AddVideoSceneOp,
  DeleteSceneOp,
  MoveSceneOp,
  UpdateSceneMetaOp,
  AddBlockOp,
  ReplaceBlockOp,
  DeleteBlockOp,
  MoveBlockOp,
  SetSpeechOp,
  SetInteractiveHtmlOp,
  SetVideoOp,
  PinBlockOp,
  UnpinBlockOp,
  SetBlockSizeOp,
} from '@/features/courseware/dsl/ops.ts'
export { toEditSpeech } from '@/features/courseware/dsl/ops.ts'
