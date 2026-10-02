# 课件

标题:{{stageTitle}}

标题是教师建课件时填的,大纲的 title 以它为准,只在明显不贴切时才改写。

# 教学需求

{{requirement}}

{{#if materialText}}
# 参考材料(教师上传的文档,按顺序合并;每份以「## 来源文档 N」分节)

以下是本课的教材内容,**大纲必须以它为主要依据**:知识点覆盖、讲解顺序、术语与例子尽量取自教材;教学需求与教材冲突时以教学需求为准。
参考材料的语言**不改变**授课语言:按教学需求的语言讲解、翻译材料内容。

## 文档内容

{{materialText}}

## 可用图片

{{availableImages}}
{{/if}}

{{#if hasSourceImages}}
# 素材图片的使用

材料里抽出的图片列在「可用图片」中(带来源、尺寸与图注{{visionNote}})。
给**讲解页**挑选确实有助于讲解的图,写进该页的 `imageIds`;只能用列表里的 id,一张图只给一页,没有合适的图就省略该字段。
{{/if}}

{{#if sceneCount}}
# 页数要求(严格)

共 {{sceneCount}} 页(含封面),必须恰好等于此数,多一页少一页都会被打回。
{{/if}}

{{#if quizCount}}
# 测验页数量要求(严格)

恰好 {{quizCount}} 页 type=quiz,放在对应知识点讲解之后。
{{/if}}

{{#if interactiveCount}}
# 交互页数量要求(严格)

恰好 {{interactiveCount}} 页 type=interactive。
{{/if}}

请输出课件大纲 JSON。
