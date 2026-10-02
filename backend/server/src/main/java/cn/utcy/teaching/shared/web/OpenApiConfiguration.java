package cn.utcy.teaching.shared.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.beans.Introspector;
import java.util.List;

@Configuration
class OpenApiConfiguration {

    @Bean
    OpenAPI teachingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("智能教学平台 API")
                .description("智能教学平台前后端之间唯一有效的 HTTP 契约")
                .version("5.0.0"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    OperationCustomizer stableOperationIds() {
        return (Operation operation, org.springframework.web.method.HandlerMethod handler) -> {
            String controllerName = handler.getBeanType().getSimpleName();
            String controllerStem = controllerName.endsWith("Controller")
                    ? controllerName.substring(0, controllerName.length() - "Controller".length())
                    : controllerName;
            String methodName = handler.getMethod().getName();
            operation.setOperationId(
                    Introspector.decapitalize(controllerStem)
                            + Character.toUpperCase(methodName.charAt(0))
                            + methodName.substring(1));
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer recordContracts(
            @Qualifier("requestMappingHandlerMapping")
            RequestMappingHandlerMapping handlerMapping) {
        return new RecordOpenApiContractCustomizer(() ->
                handlerMapping.getHandlerMethods().values().stream()
                        .filter(handler -> handler.getBeanType().getPackageName()
                                .startsWith("cn.utcy.teaching"))
                        .map(org.springframework.web.method.HandlerMethod::getMethod)
                        .distinct()
                        .toList());
    }
}
