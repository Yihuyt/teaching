@org.springframework.modulith.ApplicationModule(
        displayName = "试题",
        allowedDependencies = {"shared", "course::application", "identity::application", "knowledgebase::application",
                "retrieval::application", "resource::application", "ai::agent", "ai::document", "ai::llm", "ai::structured"}
)
package cn.utcy.teaching.question;
