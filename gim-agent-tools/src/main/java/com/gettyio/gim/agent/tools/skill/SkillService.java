package com.gettyio.gim.agent.tools.skill;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.SkillPackage;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能安装服务（MVP SkillInstallHelper/AiSkillHubService 语义迁移）：
 * <ul>
 *   <li>技能落盘目录 {@code ~/.jwork/workspace/skills/&lt;code&gt;/SKILL.md}，
 *       直接作为 AgentScope FileSystemSkillRepository 的扫描根；</li>
 *   <li>禁用 = 目录整体移入 {@code skills-disabled/}（从仓库扫描中隔离），元数据保留在 skills 表；</li>
 *   <li>内置示例技能在启动时从 classpath 资源播种（幂等，不覆盖用户修改）。</li>
 * </ul>
 */
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** 随应用分发的内置示例技能（classpath: /skills/<code>/SKILL.md） */
    private static final List<String> BUILTIN_SKILLS = List.of("translate", "weekly-report", "office-documents", "software-automation");

    private final GimPaths paths;
    private final GimDatabase db;

    public SkillService(GimPaths paths, GimDatabase db) {
        this.paths = paths;
        this.db = db;
    }

    public Path skillsRoot() {
        return paths.skillsDir();
    }

    private Path disabledRoot() {
        return paths.workspaceDir().resolve("skills-disabled");
    }

    public List<SkillPackage> list() {
        return db.skillDao().findAll();
    }

    /**
     * 从外部目录安装技能：源目录必须包含 SKILL.md。
     *
     * @return 落库后的技能包记录
     */
    public SkillPackage install(Path srcDir) {
        Path srcEntry = srcDir.resolve(SkillManifest.ENTRY_FILE);
        if (!Files.isRegularFile(srcEntry)) {
            throw new IllegalArgumentException("安装目录必须包含 " + SkillManifest.ENTRY_FILE + ": " + srcDir);
        }
        Path srcRoot = srcDir.toAbsolutePath().normalize();
        if (srcRoot.startsWith(skillsRoot())) {
            throw new IllegalArgumentException("不能从技能目录自身安装");
        }
        String code = sanitizeCode(srcRoot.getFileName().toString());
        SkillManifest manifest = SkillManifest.parse(srcEntry);

        Path target = skillsRoot().resolve(code);
        try {
            deleteRecursively(target);
            deleteRecursively(disabledRoot().resolve(code));
            Files.createDirectories(target.getParent());
            copyTree(srcRoot, target);
        } catch (IOException e) {
            throw new UncheckedIOException("安装技能失败", e);
        }
        return upsert(code, manifest, "INSTALLED", EnabledStatus.ENABLED);
    }

    /**
     * 从单个文件导入技能：目录委托 {@link #install(Path)}；{@code .md}（单个 SKILL.md）按名建目录；
     * {@code .zip}（校验含 SKILL.md、拒绝 {@code ../} 路径穿越）解压落 skillsRoot 并同步 skills 表。
     */
    public SkillPackage importFromFile(Path file) {
        if (Files.isDirectory(file)) {
            return install(file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            return importFromZip(file);
        }
        if (name.endsWith(".md")) {
            return importFromMarkdown(file);
        }
        throw new IllegalArgumentException("仅支持 .md 或 .zip 文件: " + file.getFileName());
    }

    /** 单个 SKILL.md：以其文件名（SKILL.md 则取父目录名）建目录，复制为目录内 SKILL.md。 */
    private SkillPackage importFromMarkdown(Path md) {
        SkillManifest manifest = SkillManifest.parse(md);
        String base = stripExtension(md.getFileName().toString());
        if (base.equalsIgnoreCase("SKILL")) {
            Path parent = md.getParent();
            base = (parent != null && parent.getFileName() != null) ? parent.getFileName().toString() : "skill";
        }
        String code = sanitizeCode(base);
        Path target = skillsRoot().resolve(code);
        try {
            deleteRecursively(target);
            deleteRecursively(disabledRoot().resolve(code));
            Files.createDirectories(target);
            Files.copy(md, target.resolve(SkillManifest.ENTRY_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("导入技能失败", e);
        }
        return upsert(code, manifest, "INSTALLED", EnabledStatus.ENABLED);
    }

    /**
     * zip 导入：先解到 skillsRoot 下的临时目录，逐条校验条目 normalize 后仍位于临时目录内（拒绝
     * {@code ../} 与绝对路径穿越），定位最浅层 SKILL.md 作为内容根，整体搬入 skillsRoot/&lt;code&gt;。
     */
    private SkillPackage importFromZip(Path zip) {
        String code = sanitizeCode(stripExtension(zip.getFileName().toString()));
        Path target = skillsRoot().resolve(code);
        Path tmp = null;
        try {
            Files.createDirectories(skillsRoot());
            tmp = Files.createTempDirectory(skillsRoot(), ".import-");
            Path tmpRoot = tmp.toRealPath();
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    Path resolved = tmpRoot.resolve(entry.getName()).normalize();
                    if (!resolved.startsWith(tmpRoot)) {
                        throw new IllegalArgumentException("压缩包包含非法路径条目: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(resolved);
                    } else {
                        Files.createDirectories(resolved.getParent());
                        Files.copy(zin, resolved, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zin.closeEntry();
                }
            }
            Path entryFile = findEntryFile(tmpRoot);
            if (entryFile == null) {
                throw new IllegalArgumentException("压缩包内未找到 " + SkillManifest.ENTRY_FILE);
            }
            deleteRecursively(target);
            deleteRecursively(disabledRoot().resolve(code));
            Files.createDirectories(target.getParent());
            copyTree(entryFile.getParent(), target);
            SkillManifest manifest = SkillManifest.parse(target.resolve(SkillManifest.ENTRY_FILE));
            return upsert(code, manifest, "INSTALLED", EnabledStatus.ENABLED);
        } catch (IOException e) {
            throw new UncheckedIOException("导入技能失败", e);
        } finally {
            if (tmp != null) {
                try {
                    deleteRecursively(tmp);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响导入结果
                }
            }
        }
    }

    /** 在解压树中查找层级最浅的 SKILL.md（兼容 zip 顶层或单一子目录包裹）。 */
    private static Path findEntryFile(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(SkillManifest.ENTRY_FILE))
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 启动播种内置示例技能：目录已存在（含禁用态）则跳过，不覆盖用户修改 */
    public void seedBuiltinSkills() {
        for (String code : BUILTIN_SKILLS) {
            Path target = skillsRoot().resolve(code);
            if (Files.exists(target) || Files.exists(disabledRoot().resolve(code))) {
                continue;
            }
            // 必须是正斜杠且以 / 开头的绝对资源路径；用 Path.of 在 Windows 上会生成 \skills\... 前缀，
            // getResourceAsStream 会把它当包内相对路径解析，导致播种在 Windows 上静默失败
            String resource = "/skills/" + code + "/" + SkillManifest.ENTRY_FILE;
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) {
                    log.warn("内置技能资源缺失: {}", resource);
                    continue;
                }
                Files.createDirectories(target);
                Files.copy(in, target.resolve(SkillManifest.ENTRY_FILE), StandardCopyOption.REPLACE_EXISTING);
                SkillManifest manifest = SkillManifest.parse(target.resolve(SkillManifest.ENTRY_FILE));
                upsert(code, manifest, "BUILTIN", EnabledStatus.ENABLED);
                log.info("已播种内置技能: {}", code);
            } catch (IOException e) {
                log.error("播种内置技能失败: {}", code, e);
            }
        }
    }

    /** 启用/禁用：在 skills 与 skills-disabled 目录间整体移动技能目录 */
    public void setEnabled(String code, boolean enabled) {
        Path from = enabled ? disabledRoot().resolve(code) : skillsRoot().resolve(code);
        Path to = enabled ? skillsRoot().resolve(code) : disabledRoot().resolve(code);
        if (!Files.isDirectory(from)) {
            throw new IllegalStateException("技能目录不存在，无法切换: " + code);
        }
        try {
            Files.createDirectories(to.getParent());
            deleteRecursively(to);
            Files.move(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException("切换技能状态失败", e);
        }
        db.skillDao().findByCode(code).ifPresent(s -> {
            s.setEnabled(enabled ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);
            db.skillDao().update(s);
        });
    }

    /** 删除技能：文件与元数据一并清除 */
    public void delete(String code) {
        try {
            deleteRecursively(skillsRoot().resolve(code));
            deleteRecursively(disabledRoot().resolve(code));
        } catch (IOException e) {
            throw new UncheckedIOException("删除技能文件失败", e);
        }
        db.skillDao().deleteByCode(code);
    }

    /** 读取 SKILL.md 正文（启用/禁用目录先后查找），不存在返回空串 */
    public String readContent(String code) {
        Path md = skillsRoot().resolve(code).resolve(SkillManifest.ENTRY_FILE);
        if (!Files.isRegularFile(md)) {
            md = disabledRoot().resolve(code).resolve(SkillManifest.ENTRY_FILE);
        }
        try {
            return Files.isRegularFile(md) ? Files.readString(md, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private SkillPackage upsert(String code, SkillManifest manifest, String source, int enabled) {
        SkillPackage s = db.skillDao().findByCode(code).orElseGet(SkillPackage::new);
        s.setSkillCode(code);
        s.setSkillName(manifest.name());
        s.setDescription(manifest.description());
        s.setVersion(manifest.version());
        s.setEntryPath("skills/" + code + "/" + SkillManifest.ENTRY_FILE);
        s.setSource(source);
        s.setEnabled(enabled);
        if (s.getId() == null) {
            db.skillDao().insert(s);
        } else {
            db.skillDao().update(s);
        }
        return s;
    }

    private static String sanitizeCode(String raw) {
        String code = raw.replaceAll("[^A-Za-z0-9_-]", "-");
        if (code.isBlank()) {
            throw new IllegalArgumentException("非法技能编码: " + raw);
        }
        return code;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
