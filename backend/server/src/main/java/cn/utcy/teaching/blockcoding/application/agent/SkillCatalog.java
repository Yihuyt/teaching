package cn.utcy.teaching.blockcoding.application.agent;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置技能目录(刻意只内置、不开放用户上传):
 * classpath:{base}/{name}/SKILL.md,frontmatter 给出 name / title / description,正文是方法论。
 * 系统提示只列 name + description(路由说明);模型用 read_skill 读正文即激活。
 */
public class SkillCatalog {
    public record Skill(String name, String title, String description, String content) {
    }

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillCatalog(String base) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + base + "/*/SKILL.md");
            List<Skill> loaded = new ArrayList<>();
            for (Resource resource : resources) {
                loaded.add(parse(resource.getContentAsString(StandardCharsets.UTF_8), resource.getDescription()));
            }
            loaded.sort(Comparator.comparing(Skill::name));
            for (Skill skill : loaded) {
                if (skills.put(skill.name(), skill) != null) {
                    throw new IllegalStateException("技能名重复:" + skill.name());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("技能目录读取失败", exception);
        }
        if (skills.isEmpty()) {
            throw new IllegalStateException("技能目录为空:" + base + "/*/SKILL.md 缺失");
        }
    }

    static Skill parse(String raw, String where) {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        if (!text.startsWith("---")) {
            throw new IllegalStateException("技能文件缺少 frontmatter:" + where);
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalStateException("技能 frontmatter 未闭合:" + where);
        }
        String header = text.substring(3, end);
        String body = text.substring(end + 4).strip();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : header.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String value = line.substring(colon + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                fields.put(line.substring(0, colon).strip(), value);
            }
        }
        String name = fields.get("name");
        String description = fields.get("description");
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalStateException("技能 frontmatter 须有 name 与 description:" + where);
        }
        return new Skill(name, fields.getOrDefault("title", name), description, body);
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    public Skill find(String name) {
        return skills.get(name);
    }

    public String roster() {
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills.values()) {
            sb.append("- `").append(skill.name()).append("`(").append(skill.title()).append("):")
                    .append(skill.description()).append('\n');
        }
        return sb.toString().strip();
    }
}
