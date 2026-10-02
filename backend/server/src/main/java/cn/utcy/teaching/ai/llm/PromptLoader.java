package cn.utcy.teaching.ai.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 装载器。
 *
 * 各模块以 basePath 自持模板资源(如 "courseware/prompts"):
 * 模板目录:classpath:{basePath}/templates/{id}/{system.md,user.md}
 * 片段目录:classpath:{basePath}/snippets/{name}.md(片段模块本地,不跨模块共享)
 * 语法:{{snippet:name}} 内联片段;{{#if flag}}...{{/if}} 条件块(不嵌套);
 *       {{varName}} 变量插值(对象序列化为缩进 JSON,未提供的变量原样保留)。
 */
@Component
public class PromptLoader {

    private static final Pattern SNIPPET = Pattern.compile("\\{\\{snippet:(\\w[\\w-]*)}}");
    private static final Pattern CONDITIONAL = Pattern.compile("\\{\\{#if (\\w+)}}(.*?)\\{\\{/if}}", Pattern.DOTALL);
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private final ObjectMapper objectMapper;

    public PromptLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Prompt(String system, String user) {
    }

    public Prompt build(String basePath, String templateId, Map<String, Object> vars) {
        String systemRaw = readResource(basePath + "/templates/" + templateId + "/system.md").trim();
        String userRaw = readResource(basePath + "/templates/" + templateId + "/user.md").trim();
        return new Prompt(render(basePath, systemRaw, vars), render(basePath, userRaw, vars));
    }

    private String render(String basePath, String template, Map<String, Object> vars) {
        return interpolate(processConditionals(processSnippets(basePath, template), vars), vars);
    }

    private String processSnippets(String basePath, String template) {
        return replaceAll(SNIPPET, template, m -> loadSnippet(basePath, m.group(1)));
    }

    private String loadSnippet(String basePath, String name) {
        String path = basePath + "/snippets/" + name + ".md";
        if (PromptLoader.class.getClassLoader().getResource(path) == null) {
            // 缺片段一定是配置/笔误,立即抛错,绝不把 {{snippet:x}} 原文发给模型
            throw new IllegalStateException("Prompt snippet 不存在: " + path);
        }
        return readResource(path).trim();
    }

    private String processConditionals(String template, Map<String, Object> vars) {
        return replaceAll(CONDITIONAL, template,
                m -> truthy(vars.get(m.group(1))) ? m.group(2) : "");
    }

    private String interpolate(String template, Map<String, Object> vars) {
        return replaceAll(VARIABLE, template, m -> {
            String key = m.group(1);
            if (!vars.containsKey(key) || vars.get(key) == null) {
                return m.group();
            }
            return stringify(vars.get(key));
        });
    }

    /** 对齐 JS 真值语义:null/false/0/空串为假,其余为真 */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return true;
    }

    private String stringify(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Prompt 变量序列化失败: " + value.getClass().getName(), e);
        }
    }

    /** 替换值一律按字面量处理(quoteReplacement),避免片段/JSON 里的 $ 与 \ 被当作反向引用 */
    private static String replaceAll(Pattern pattern, String input, Function<Matcher, String> replacer) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String readResource(String path) {
        try (InputStream in = PromptLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Prompt 资源不存在: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt 资源读取失败: " + path, e);
        }
    }
}
