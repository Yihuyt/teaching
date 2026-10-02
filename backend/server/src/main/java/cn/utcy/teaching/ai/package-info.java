/** 大模型接入:只依赖共享内核,不知道课程、用户之外的任何业务。对外按命名接口暴露:llm、agent、structured、document、media、application */
@org.springframework.modulith.ApplicationModule(displayName = "AI 模型接入", allowedDependencies = {"shared"})
package cn.utcy.teaching.ai;
