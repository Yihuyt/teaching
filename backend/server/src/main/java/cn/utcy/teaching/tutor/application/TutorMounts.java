package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.retrieval.application.RetrievalTools;

import java.util.List;

/**
 * 一次对话的助手配置:名称、教师补充要求、模型配置与轮次上限,以及助手挂载中**索引就绪**的知识库
 * (未就绪的挂载不进工具,也不预检索;就绪状态在助手视图里单独展示)。
 */
public record TutorMounts(String assistantName, String instructions, ModelConfig model, int maxRounds,
                          List<KnowledgeBaseRef> knowledgeBases) {

    public boolean isEmpty() {
        return knowledgeBases.isEmpty();
    }

    public RetrievalTools.Mounts retrieval() {
        return new RetrievalTools.Mounts(knowledgeBases);
    }
}
