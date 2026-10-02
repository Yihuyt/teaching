## 本题 template
- question_id: {{questionId}}
- topic: {{topic}}
- question_type: {{typeValue}}
- difficulty: {{difficulty}}

## 探索轨迹(阶段 1 完整思考 + 工具调用历史,工具结果已概括)
{{explorationTrace}}

## 完整规划(本轮所有题目)
{{planSummary}}

## 本轮已生成题目(**不要**重复)
{{previousQuestions}}

{{#if existingTitles}}
## 题库中已有的试题标题(不得重复考查)
{{existingTitles}}
{{/if}}

## 参考素材(仅仿写模式使用——本题应**仿写 / 改编**该参考题的风格和难度,**不要**另起炉灶)
{{referenceBlock}}

开始为 {{questionId}} 出题。
