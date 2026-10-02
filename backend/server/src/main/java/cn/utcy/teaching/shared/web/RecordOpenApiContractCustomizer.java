package cn.utcy.teaching.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class RecordOpenApiContractCustomizer implements OpenApiCustomizer {

    private static final String COMPONENT_PREFIX = "#/components/schemas/";

    private final Supplier<? extends Collection<Method>> controllerMethods;

    RecordOpenApiContractCustomizer(
            Supplier<? extends Collection<Method>> controllerMethods
    ) {
        this.controllerMethods = controllerMethods;
    }

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            return;
        }
        applyRequestContracts(components.getSchemas());
        applyResponseRecordContracts(components.getSchemas());
        applyResponseContracts(openApi, components.getSchemas());
    }

    private void applyResponseRecordContracts(Map<String, Schema> schemas) {
        Map<String, Class<?>> responseRecords = new HashMap<>();
        for (Method method : controllerMethods.get()) {
            collectRecordTypes(method.getGenericReturnType(), responseRecords);
        }
        for (Map.Entry<String, Class<?>> entry : responseRecords.entrySet()) {
            Schema<?> schema = schemas.get(entry.getKey());
            if (schema == null) {
                if (entry.getValue() == PageResponse.class) {
                    continue;
                }
                throw new IllegalStateException(
                        "OpenAPI 缺少响应 record 的对象结构：" + entry.getValue().getName());
            }
            applyResponseRecord(schema, entry.getValue());
        }
    }

    private void applyResponseRecord(Schema<?> schema, Class<?> recordType) {
        if (schema.getProperties() == null) {
            throw new IllegalStateException(
                    "OpenAPI 响应 record 缺少对象字段：" + recordType.getName());
        }
        for (RecordComponent component : recordType.getRecordComponents()) {
            Schema<?> property = (Schema<?>) schema.getProperties().get(component.getName());
            if (property == null) {
                throw new IllegalStateException(
                        "OpenAPI 响应 record 缺少字段："
                                + recordType.getName() + "." + component.getName());
            }
            io.swagger.v3.oas.annotations.media.Schema annotation =
                    findAnnotation(component, io.swagger.v3.oas.annotations.media.Schema.class);
            applyOpenApi31Nullability(
                    property,
                    annotation != null && annotation.nullable());
        }
        requireEveryResponseProperty(schema);
    }

    private void applyRequestContracts(Map<String, Schema> schemas) {
        Map<String, Class<?>> requestRecords = new HashMap<>();
        for (Method method : controllerMethods.get()) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getAnnotation(RequestBody.class) != null) {
                    collectRecordTypes(parameter.getParameterizedType(), requestRecords);
                }
            }
        }

        for (Map.Entry<String, Class<?>> entry : requestRecords.entrySet()) {
            Schema<?> schema = schemas.get(entry.getKey());
            if (schema == null || schema.getProperties() == null) {
                throw new IllegalStateException(
                        "OpenAPI 缺少请求 record 的对象结构：" + entry.getValue().getName());
            }
            applyRequestRecord(schema, entry.getValue());
        }
    }

    private void collectRecordTypes(Type type, Map<String, Class<?>> records) {
        if (type instanceof Class<?> rawType) {
            if (!rawType.isRecord()) {
                return;
            }
            Class<?> previous = records.putIfAbsent(rawType.getSimpleName(), rawType);
            if (previous != null && previous != rawType) {
                throw new IllegalStateException(
                        "OpenAPI record 名称冲突：" + rawType.getSimpleName());
            }
            if (previous == rawType) {
                return;
            }
            for (RecordComponent component : rawType.getRecordComponents()) {
                collectRecordTypes(component.getGenericType(), records);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            collectRecordTypes(parameterizedType.getRawType(), records);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                collectRecordTypes(argument, records);
            }
            return;
        }
        if (type instanceof GenericArrayType arrayType) {
            collectRecordTypes(arrayType.getGenericComponentType(), records);
            return;
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type upperBound : wildcardType.getUpperBounds()) {
                collectRecordTypes(upperBound, records);
            }
        }
    }

    private void applyRequestRecord(Schema<?> schema, Class<?> recordType) {
        Set<String> required = new LinkedHashSet<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            Schema<?> property = (Schema<?>) schema.getProperties().get(component.getName());
            if (property == null) {
                throw new IllegalStateException(
                        "OpenAPI 请求 record 缺少字段："
                                + recordType.getName() + "." + component.getName());
            }
            required.add(component.getName());
            applyOpenApi31Nullability(property, allowsNull(component));
        }
        schema.setRequired(required.stream().sorted().toList());
    }

    private void applyOpenApi31Nullability(
            Schema<?> property,
            boolean allowsNull
    ) {
        property.setNullable(null);
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        }
        if (property.getType() != null) {
            types.add(property.getType());
        }
        types.remove("null");

        if (!types.isEmpty()) {
            if (allowsNull) {
                types.add("null");
            }
            property.setTypes(types);
            return;
        }
        if (!allowsNull) {
            return;
        }

        Schema<Object> nullSchema = new Schema<>();
        nullSchema.setTypes(Set.of("null"));
        if (property.get$ref() != null) {
            Schema<Object> referencedSchema = new Schema<>();
            referencedSchema.set$ref(property.get$ref());
            property.set$ref(null);
            property.setAnyOf(List.of(referencedSchema, nullSchema));
            return;
        }
        if (property.getAnyOf() != null && !property.getAnyOf().isEmpty()) {
            List<Schema> variants = new ArrayList<>(property.getAnyOf());
            variants.add(nullSchema);
            property.setAnyOf(variants);
            return;
        }
        if (property.getOneOf() != null && !property.getOneOf().isEmpty()) {
            List<Schema> variants = new ArrayList<>(property.getOneOf());
            variants.add(nullSchema);
            property.setOneOf(variants);
            return;
        }
        throw new IllegalStateException("OpenAPI 可空字段缺少基础 schema");
    }

    private boolean allowsNull(RecordComponent component) {
        if (component.getType().isPrimitive()) {
            return false;
        }
        if (hasAnnotation(component, NotNull.class)
                || hasAnnotation(component, NotBlank.class)
                || hasAnnotation(component, NotEmpty.class)) {
            return false;
        }
        io.swagger.v3.oas.annotations.media.Schema schema =
                findAnnotation(component, io.swagger.v3.oas.annotations.media.Schema.class);
        return schema == null || schema.nullable();
    }

    private <A extends java.lang.annotation.Annotation> boolean hasAnnotation(
            RecordComponent component,
            Class<A> annotationType
    ) {
        return findAnnotation(component, annotationType) != null;
    }

    private <A extends java.lang.annotation.Annotation> A findAnnotation(
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
            A annotation = component.getDeclaringRecord()
                    .getDeclaredField(component.getName())
                    .getAnnotation(annotationType);
            if (annotation != null) {
                return annotation;
            }
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException(
                    "无法读取 record 字段：" + component.getDeclaringRecord().getName(),
                    exception);
        }
        return null;
    }

    private void applyResponseContracts(OpenAPI openApi, Map<String, Schema> schemas) {
        if (openApi.getPaths() == null) {
            return;
        }
        Set<String> visitedComponents = new HashSet<>();
        openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation ->
                        visitResponses(operation, schemas, visitedComponents)));
    }

    private void visitResponses(
            Operation operation,
            Map<String, Schema> schemas,
            Set<String> visitedComponents
    ) {
        if (operation.getResponses() == null) {
            return;
        }
        for (ApiResponse response : operation.getResponses().values()) {
            visitContent(response.getContent(), schemas, visitedComponents);
        }
    }

    private void visitContent(
            Content content,
            Map<String, Schema> schemas,
            Set<String> visitedComponents
    ) {
        if (content == null) {
            return;
        }
        for (MediaType mediaType : content.values()) {
            visitResponseSchema(mediaType.getSchema(), schemas, visitedComponents);
        }
    }

    private void visitResponseSchema(
            Schema<?> schema,
            Map<String, Schema> schemas,
            Set<String> visitedComponents
    ) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null && schema.get$ref().startsWith(COMPONENT_PREFIX)) {
            String name = schema.get$ref().substring(COMPONENT_PREFIX.length());
            if (!visitedComponents.add(name)) {
                return;
            }
            Schema<?> component = schemas.get(name);
            if (component == null) {
                throw new IllegalStateException("OpenAPI 响应引用了不存在的 schema：" + name);
            }
            requireEveryResponseProperty(component);
            visitResponseSchema(component, schemas, visitedComponents);
            return;
        }

        requireEveryResponseProperty(schema);
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property ->
                    visitResponseSchema(
                            (Schema<?>) property,
                            schemas,
                            visitedComponents));
        }
        visitResponseSchema(schema.getItems(), schemas, visitedComponents);
        visitSchemaList(schema.getAllOf(), schemas, visitedComponents);
        visitSchemaList(schema.getOneOf(), schemas, visitedComponents);
        visitSchemaList(schema.getAnyOf(), schemas, visitedComponents);
        if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
            visitResponseSchema(additionalProperties, schemas, visitedComponents);
        }
    }

    private void requireEveryResponseProperty(Schema<?> schema) {
        if (schema.getProperties() == null || schema.getProperties().isEmpty()) {
            return;
        }
        List<String> required = new ArrayList<>(schema.getProperties().keySet());
        required.sort(Comparator.naturalOrder());
        schema.setRequired(required);
    }

    private void visitSchemaList(
            List<Schema> nestedSchemas,
            Map<String, Schema> schemas,
            Set<String> visitedComponents
    ) {
        if (nestedSchemas == null) {
            return;
        }
        nestedSchemas.forEach(schema ->
                visitResponseSchema(schema, schemas, visitedComponents));
    }
}
