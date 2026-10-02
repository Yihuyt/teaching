@org.springframework.modulith.ApplicationModule(
        displayName = "学情",
        allowedDependencies = {"shared", "course::application", "identity::application",
                "knowledgegraph::application", "knowledgegraph::vocabulary"}
)
package cn.utcy.teaching.analytics;
