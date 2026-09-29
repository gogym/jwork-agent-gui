package com.gettyio.gim.agent.tools.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SKILL.md 清单（MVP SkillManifest 语义迁移）：解析 YAML front-matter 的
 * name / description / version 元数据；无 front-matter 时回退目录名/首个标题。
 */
public record SkillManifest(String name, String description, String version) {

    public static final String ENTRY_FILE = "SKILL.md";

    /** 解析 SKILL.md 头部 front-matter */
    public static SkillManifest parse(Path skillMd) {
        String content;
        try {
            content = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 SKILL.md 失败: " + skillMd, e);
        }
        Map<String, String> front = readFrontMatter(content);
        String parent = skillMd.getParent() == null ? "skill"
                : skillMd.getParent().getFileName().toString();
        String name = front.getOrDefault("name", parent);
        String description = front.get("description");
        if (description == null) {
            description = firstHeading(content);
        }
        String version = front.getOrDefault("version", "1.0.0");
        return new SkillManifest(name, description == null ? "" : description, version);
    }

    /** 提取 --- 包住的 front-matter 键值对（仅支持简单单行 key: value） */
    private static Map<String, String> readFrontMatter(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = content.split("\r?\n");
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return map;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().equals("---")) {
                break;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                map.put(key, unquote(value));
            }
        }
        return map;
    }

    private static String firstHeading(String content) {
        for (String line : content.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                return t.replaceFirst("^#+\\s*", "");
            }
        }
        return "";
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
