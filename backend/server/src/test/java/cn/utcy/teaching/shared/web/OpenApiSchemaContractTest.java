package cn.utcy.teaching.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSchemaContractTest {

    @Test
    void controllerRecordSchemasMatchRuntimeRequiredAndNullableContracts() {
        List<Method> methods = controllerMethods();
        OpenAPI openApi = createRuntimeModel(methods);

        new RecordOpenApiContractCustomizer(() -> methods).customise(openApi);
        JsonNode serialized = Json31.mapper().valueToTree(openApi);
        assertThat(serialized.findValues("nullable"))
                .as("OpenAPI 3.1 must not contain the legacy nullable keyword")
                .isEmpty();

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas =
                openApi.getComponents().getSchemas();
        Map<String, Class<?>> responseRecords = new LinkedHashMap<>();
        Map<String, Class<?>> requestRecords = new LinkedHashMap<>();
        for (Method method : methods) {
            collectRecords(method.getGenericReturnType(), responseRecords);
            Arrays.stream(method.getParameters())
                    .filter(parameter -> parameter.getAnnotation(RequestBody.class) != null)
                    .forEach(parameter ->
                            collectRecords(parameter.getParameterizedType(), requestRecords));
        }

        for (Map.Entry<String, Class<?>> entry : responseRecords.entrySet()) {
            if (entry.getValue() == PageResponse.class) {
                continue;
            }
            assertResponseRecord(schemas, entry.getKey(), entry.getValue());
        }
        schemas.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("PageResponse"))
                .forEach(entry -> assertAllPropertiesRequired(entry.getKey(), entry.getValue()));

        for (Map.Entry<String, Class<?>> entry : requestRecords.entrySet()) {
            assertRequestRecord(schemas, entry.getKey(), entry.getValue());
        }

        assertRequiredNonNullable(schemas, "ItemView", "answer");
        assertRequiredNonNullable(schemas, "CaseResultView", "detail");
        assertRequiredNullable(schemas, "ItemView", "options");
        assertRequiredNullable(schemas, "LearningItemView", "options");
        assertRequiredNullable(schemas, "ItemResultView", "answer");
        assertThat(schemas.get("LearningItemView").getProperties())
                .doesNotContainKeys("answer", "analysisMarkdown");
    }

    private OpenAPI createRuntimeModel(List<Method> methods) {
        Components components = new Components().schemas(new LinkedHashMap<>());
        OpenAPI openApi = new OpenAPI()
                .components(components)
                .paths(new Paths());
        int pathIndex = 0;
        for (Method method : methods) {
            io.swagger.v3.oas.models.media.Schema<?> responseSchema =
                    resolve(method.getGenericReturnType(), components);
            Arrays.stream(method.getParameters())
                    .filter(parameter -> parameter.getAnnotation(RequestBody.class) != null)
                    .forEach(parameter ->
                            resolve(parameter.getParameterizedType(), components));
            if (method.getReturnType() == void.class || responseSchema == null) {
                continue;
            }
            ApiResponse response = new ApiResponse()
                    .content(new Content().addMediaType(
                            "application/json",
                            new MediaType().schema(responseSchema)));
            openApi.getPaths().addPathItem(
                    "/contract/" + pathIndex++,
                    new PathItem().get(new io.swagger.v3.oas.models.Operation()
                            .responses(new ApiResponses().addApiResponse("200", response))));
        }
        return openApi;
    }

    private io.swagger.v3.oas.models.media.Schema<?> resolve(
            Type type,
            Components components
    ) {
        ResolvedSchema resolved = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        if (resolved.referencedSchemas != null) {
            components.getSchemas().putAll(resolved.referencedSchemas);
        }
        return resolved.schema;
    }

    private List<Method> controllerMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Method> methods = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("cn.utcy.teaching")) {
            try {
                Class<?> controller = Class.forName(candidate.getBeanClassName());
                Arrays.stream(controller.getDeclaredMethods())
                        .filter(method -> AnnotatedElementUtils.hasAnnotation(
                                method,
                                RequestMapping.class))
                        .forEach(methods::add);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException(
                        "无法加载控制器：" + candidate.getBeanClassName(),
                        exception);
            }
        }
        assertThat(methods).isNotEmpty();
        return methods;
    }

    private void collectRecords(Type type, Map<String, Class<?>> records) {
        if (type instanceof Class<?> rawType) {
            if (!rawType.isRecord()) {
                return;
            }
            Class<?> previous = records.putIfAbsent(rawType.getSimpleName(), rawType);
            assertThat(previous == null || previous == rawType)
                    .as("record schema name must be unique: %s", rawType.getSimpleName())
                    .isTrue();
            if (previous == rawType) {
                return;
            }
            for (RecordComponent component : rawType.getRecordComponents()) {
                collectRecords(component.getGenericType(), records);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            collectRecords(parameterizedType.getRawType(), records);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                collectRecords(argument, records);
            }
            return;
        }
        if (type instanceof GenericArrayType arrayType) {
            collectRecords(arrayType.getGenericComponentType(), records);
            return;
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type upperBound : wildcardType.getUpperBounds()) {
                collectRecords(upperBound, records);
            }
        }
    }

    private void assertResponseRecord(
            Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
            String schemaName,
            Class<?> recordType
    ) {
        io.swagger.v3.oas.models.media.Schema<?> schema = schemas.get(schemaName);
        assertThat(schema)
                .as("response schema %s", schemaName)
                .isNotNull();
        assertAllPropertiesRequired(schemaName, schema);
        for (RecordComponent component : recordType.getRecordComponents()) {
            io.swagger.v3.oas.models.media.Schema<?> property =
                    (io.swagger.v3.oas.models.media.Schema<?>)
                            schema.getProperties().get(component.getName());
            boolean nullable = nullableAnnotation(component);
            assertThat(isNullable(property))
                    .as("%s.%s nullable contract", schemaName, component.getName())
                    .isEqualTo(nullable);
            assertThat(property.getNullable())
                    .as("%s.%s must not use legacy nullable", schemaName, component.getName())
                    .isNull();
        }
    }

    private void assertRequestRecord(
            Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
            String schemaName,
            Class<?> recordType
    ) {
        io.swagger.v3.oas.models.media.Schema<?> schema = schemas.get(schemaName);
        assertThat(schema)
                .as("request schema %s", schemaName)
                .isNotNull();
        assertAllPropertiesRequired(schemaName, schema);
        for (RecordComponent component : recordType.getRecordComponents()) {
            io.swagger.v3.oas.models.media.Schema<?> property =
                    (io.swagger.v3.oas.models.media.Schema<?>)
                            schema.getProperties().get(component.getName());
            boolean nullable = allowsNull(component);
            assertThat(isNullable(property))
                    .as("%s.%s nullable contract", schemaName, component.getName())
                    .isEqualTo(nullable);
            assertThat(property.getNullable())
                    .as("%s.%s must not use legacy nullable", schemaName, component.getName())
                    .isNull();
        }
    }

    private void assertAllPropertiesRequired(
            String schemaName,
            io.swagger.v3.oas.models.media.Schema<?> schema
    ) {
        if (schema.getProperties() == null || schema.getProperties().isEmpty()) {
            return;
        }
        assertThat(new LinkedHashSet<>(schema.getRequired()))
                .as("%s required properties", schemaName)
                .containsExactlyInAnyOrderElementsOf(schema.getProperties().keySet());
    }

    private boolean allowsNull(RecordComponent component) {
        return !component.getType().isPrimitive()
                && !hasAnnotation(component, NotNull.class)
                && !hasAnnotation(component, NotBlank.class)
                && !hasAnnotation(component, NotEmpty.class);
    }

    private boolean nullableAnnotation(RecordComponent component) {
        Schema annotation = findAnnotation(component, Schema.class);
        return annotation != null && annotation.nullable();
    }

    private <A extends Annotation> boolean hasAnnotation(
            RecordComponent component,
            Class<A> annotationType
    ) {
        return findAnnotation(component, annotationType) != null;
    }

    private <A extends Annotation> A findAnnotation(
            RecordComponent component,
            Class<A> annotationType
    ) {
        for (AnnotatedElement element : List.of(component, component.getAccessor())) {
            A annotation = element.getAnnotation(annotationType);
            if (annotation != null) {
                return annotation;
            }
        }
        try {
            return component.getDeclaringRecord()
                    .getDeclaredField(component.getName())
                    .getAnnotation(annotationType);
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertRequiredNullable(
            Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
            String schemaName,
            String propertyName
    ) {
        io.swagger.v3.oas.models.media.Schema<?> schema = schemas.get(schemaName);
        assertThat(schema.getRequired()).contains(propertyName);
        assertThat(isNullable(
                (io.swagger.v3.oas.models.media.Schema<?>)
                        schema.getProperties().get(propertyName))).isTrue();
    }

    private void assertRequiredNonNullable(
            Map<String, io.swagger.v3.oas.models.media.Schema> schemas,
            String schemaName,
            String propertyName
    ) {
        io.swagger.v3.oas.models.media.Schema<?> schema = schemas.get(schemaName);
        assertThat(schema.getRequired()).contains(propertyName);
        assertThat(isNullable(
                (io.swagger.v3.oas.models.media.Schema<?>)
                        schema.getProperties().get(propertyName))).isFalse();
    }

    private boolean isNullable(
            io.swagger.v3.oas.models.media.Schema<?> schema
    ) {
        if (schema.getTypes() != null && schema.getTypes().contains("null")) {
            return true;
        }
        if ("null".equals(schema.getType())) {
            return true;
        }
        if (schema.getAnyOf() != null
                && schema.getAnyOf().stream().anyMatch(this::isNullable)) {
            return true;
        }
        if (schema.getOneOf() != null
                && schema.getOneOf().stream().anyMatch(this::isNullable)) {
            return true;
        }
        return false;
    }
}
