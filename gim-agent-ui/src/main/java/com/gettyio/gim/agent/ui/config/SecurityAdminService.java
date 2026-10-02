package com.gettyio.gim.agent.ui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.tools.guard.DangerousCommandPolicy;
import com.gettyio.gim.agent.tools.guard.DangerousRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 安全策略管理：设置「安全」页的后端。聚合四组真实能力，全部落 {@code settings} 键值表，不新增表。
 * <ul>
 *   <li><b>审批档位默认</b>：{@code ask|auto|readonly}，聊天页启动时读取作为初始档。</li>
 *   <li><b>危险命令黑名单</b>：{@link DangerousRule} 列表，首次读时以 {@link DangerousCommandPolicy#DEFAULT_RULES} 播种，
 *       支持逐条启停、增删与「重置」；运行时策略经 Supplier 读本服务，改动即时生效。</li>
 *   <li><b>Shell 程序白名单</b>：用户扩展程序名（基础集留在代码常量），并入 {@code ExecutableAllowlistPolicy}。</li>
 *   <li><b>文件授权目录</b>：用户新增目录，并入 {@code PathWhitelistPolicy}。</li>
 * </ul>
 * <p>另保留「总是允许」授权规则的列出/撤销（HITL 写入 {@code tool_permission_rules}）。</p>
 */
public class SecurityAdminService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_APPROVAL_DEFAULT = "security.approval.default";
    private static final String KEY_DANGEROUS_RULES = "security.dangerous.rules";
    private static final String KEY_SHELL_ALLOWLIST = "security.shell.allowlist";
    private static final String KEY_PATH_ROOTS = "security.path.roots";

    /** 审批档位取值（与聊天页 ApprovalOption 对应）。 */
    public static final String APPROVAL_ASK = "ask";
    public static final String APPROVAL_AUTO = "auto";
    public static final String APPROVAL_READONLY = "readonly";

    private final GimDatabase db;

    public SecurityAdminService(GimDatabase db) {
        this.db = db;
    }

    // ===================== 审批档位默认 =====================

    /** 审批档位默认值；未设置时回退「询问」。 */
    public String getApprovalDefault() {
        return db.settingDao().getValue(KEY_APPROVAL_DEFAULT)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .orElse(APPROVAL_ASK);
    }

    public void setApprovalDefault(String mode) {
        String value = mode == null || mode.isBlank() ? APPROVAL_ASK : mode.trim();
        db.settingDao().upsert(KEY_APPROVAL_DEFAULT, value, "工具调用审批档位默认值");
    }

    // ===================== 危险命令黑名单 =====================

    /** 列出危险命令规则；键缺失时以内置默认播种并持久化。 */
    public List<DangerousRule> listDangerousRules() {
        Optional<String> raw = db.settingDao().getValue(KEY_DANGEROUS_RULES);
        if (raw.isEmpty()) {
            List<DangerousRule> seed = new ArrayList<>(DangerousCommandPolicy.DEFAULT_RULES);
            saveRules(seed);
            return seed;
        }
        return readRules(raw.get());
    }

    /** 逐条启停：仅切换 enabled，其余字段不变。 */
    public void setDangerousRuleEnabled(String id, boolean enabled) {
        mutateRules(rules -> rules.replaceAll(r -> r.id().equals(id) ? r.withEnabled(enabled) : r));
    }

    /** 新增自定义规则（默认高严重度、归入「自定义」分组、立即启用）。 */
    public void addDangerousRule(String pattern, String description) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        List<DangerousRule> rules = listDangerousRules();
        rules.add(new DangerousRule(
                "custom_" + UUID.randomUUID().toString().substring(0, 8),
                pattern.trim(),
                description == null ? "" : description.trim(),
                DangerousRule.SEVERITY_HIGH,
                "自定义",
                true));
        saveRules(rules);
    }

    public void deleteDangerousRule(String id) {
        mutateRules(rules -> rules.removeIf(r -> r.id().equals(id)));
    }

    /** 重置为内置默认（丢弃用户改动）。 */
    public void resetDangerousRules() {
        saveRules(new ArrayList<>(DangerousCommandPolicy.DEFAULT_RULES));
    }

    private void mutateRules(java.util.function.Consumer<List<DangerousRule>> mutator) {
        List<DangerousRule> rules = listDangerousRules();
        mutator.accept(rules);
        saveRules(rules);
    }

    private void saveRules(List<DangerousRule> rules) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (DangerousRule r : rules) {
            ObjectNode o = arr.addObject();
            o.put("id", r.id());
            o.put("pattern", r.pattern());
            o.put("description", r.description());
            o.put("severity", r.severity());
            o.put("category", r.category());
            o.put("enabled", r.enabled());
        }
        db.settingDao().upsert(KEY_DANGEROUS_RULES, arr.toString(), "危险命令黑名单规则");
    }

    private List<DangerousRule> readRules(String json) {
        List<DangerousRule> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node.isArray()) {
                for (JsonNode n : node) {
                    out.add(new DangerousRule(
                            n.path("id").asText(""),
                            n.path("pattern").asText(""),
                            n.path("description").asText(""),
                            n.path("severity").asText(DangerousRule.SEVERITY_HIGH),
                            n.path("category").asText(""),
                            n.path("enabled").asBoolean(true)));
                }
            }
        } catch (Exception e) {
            // 脏数据按空处理：宁可暂时少拦几条，也不让解析异常冒泡打断安全链
            return new ArrayList<>();
        }
        return out;
    }

    // ===================== Shell 程序白名单（用户扩展） =====================

    public List<String> listShellAllowlist() {
        return CapabilityCodec.readCodes(db.settingDao().getValue(KEY_SHELL_ALLOWLIST).orElse(""));
    }

    public void addShellProgram(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        List<String> list = new ArrayList<>(listShellAllowlist());
        String norm = name.trim().toLowerCase();
        if (!list.contains(norm)) {
            list.add(norm);
            saveShell(list);
        }
    }

    public void removeShellProgram(String name) {
        List<String> list = new ArrayList<>(listShellAllowlist());
        if (list.removeIf(s -> s.equalsIgnoreCase(name == null ? "" : name.trim()))) {
            saveShell(list);
        }
    }

    private void saveShell(List<String> list) {
        db.settingDao().upsert(KEY_SHELL_ALLOWLIST, CapabilityCodec.writeCodes(list), "Shell 可执行程序白名单（用户扩展）");
    }

    // ===================== 文件授权目录 =====================

    public List<String> listPathRoots() {
        return CapabilityCodec.readCodes(db.settingDao().getValue(KEY_PATH_ROOTS).orElse(""));
    }

    public void addPathRoot(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        List<String> list = new ArrayList<>(listPathRoots());
        String norm = path.trim();
        if (!list.contains(norm)) {
            list.add(norm);
            savePaths(list);
        }
    }

    public void removePathRoot(String path) {
        List<String> list = new ArrayList<>(listPathRoots());
        if (list.removeIf(s -> s.equals(path == null ? "" : path.trim()))) {
            savePaths(list);
        }
    }

    private void savePaths(List<String> list) {
        db.settingDao().upsert(KEY_PATH_ROOTS, CapabilityCodec.writeCodes(list), "文件写入授权目录（用户新增）");
    }

    // ===================== 「总是允许」授权规则 =====================

    public List<ToolPermissionRule> list() {
        return db.permissionRuleDao().findAll();
    }

    public void delete(Long id) {
        if (id != null) {
            db.permissionRuleDao().delete(id);
        }
    }

    public void clear() {
        for (ToolPermissionRule r : db.permissionRuleDao().findAll()) {
            db.permissionRuleDao().delete(r.getId());
        }
    }
}
