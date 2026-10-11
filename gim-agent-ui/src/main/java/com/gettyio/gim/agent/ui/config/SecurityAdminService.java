package com.gettyio.gim.agent.ui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.security.SecretCipher;
import com.gettyio.gim.agent.tools.guard.DangerousCommandPolicy;
import com.gettyio.gim.agent.tools.guard.DangerousRule;
import com.gettyio.gim.agent.tools.sandbox.SandboxExecutor;

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
    private static final String KEY_WEB_PROVIDER = "web.search.provider";
    private static final String KEY_WEB_APIKEY = "web.search.apiKey";
    private static final String KEY_KB_BASEURL = "knowledge.embedding.baseUrl";
    private static final String KEY_KB_MODEL = "knowledge.embedding.model";
    private static final String KEY_KB_APIKEY = "knowledge.embedding.apiKey";
    private static final String KEY_IMG_BASEURL = "image.generation.baseUrl";
    private static final String KEY_IMG_MODEL = "image.generation.model";
    private static final String KEY_IMG_APIKEY = "image.generation.apiKey";
    private static final String KEY_SANDBOX_DOCKER = "agent.sandbox.autoApproveDocker";
    private static final String KEY_SANDBOX_IMAGE = "agent.sandbox.image";

    /** 联网搜索提供方默认值（当前仅内置 Tavily）。 */
    public static final String WEB_PROVIDER_TAVILY = "tavily";

    /** Docker 沙箱镜像默认值：与执行器内置默认保持单一来源。 */
    public static final String SANDBOX_IMAGE_DEFAULT = SandboxExecutor.DEFAULT_IMAGE;

    /** 审批档位取值（与聊天页 ApprovalOption 对应）。 */
    public static final String APPROVAL_ASK = "ask";
    public static final String APPROVAL_AUTO = "auto";
    public static final String APPROVAL_READONLY = "readonly";

    private final GimDatabase db;
    private final SecretCipher cipher;

    public SecurityAdminService(GimDatabase db, SecretCipher cipher) {
        this.db = db;
        this.cipher = cipher;
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

    // ===================== 联网搜索（提供方 + API Key） =====================

    public void setWebSearchProvider(String provider) {
        String value = provider == null || provider.isBlank() ? WEB_PROVIDER_TAVILY : provider.trim().toLowerCase();
        db.settingDao().upsert(KEY_WEB_PROVIDER, value, "联网搜索提供方");
    }

    /** 读取明文 API Key（密文存储，取出即解密）；未配置或解密失败返回空串。供工具运行时实时取用。 */
    public String getWebSearchApiKeyPlain() {
        String stored = db.settingDao().getValue(KEY_WEB_APIKEY).orElse("").trim();
        if (stored.isEmpty()) {
            return "";
        }
        try {
            return cipher.canHandle(stored) ? cipher.decrypt(stored) : stored;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 保存 API Key（加密入库）；空白即清除已存 Key。 */
    public void setWebSearchApiKey(String plain) {
        if (plain == null || plain.isBlank()) {
            db.settingDao().upsert(KEY_WEB_APIKEY, "", "联网搜索 API Key（密文）");
            return;
        }
        db.settingDao().upsert(KEY_WEB_APIKEY, cipher.encrypt(plain.trim()), "联网搜索 API Key（密文）");
    }

    // ===================== 向量知识库 embedding 提供方 =====================

    /** embedding 服务协议根（OpenAI 兼容 /embeddings；未配置返回空串）。 */
    public String getKnowledgeEmbeddingBaseUrl() {
        return db.settingDao().getValue(KEY_KB_BASEURL).orElse("").trim();
    }

    public void setKnowledgeEmbeddingBaseUrl(String url) {
        db.settingDao().upsert(KEY_KB_BASEURL, url == null ? "" : url.trim(), "向量知识库 embedding 服务地址");
    }

    /** embedding 模型编码（如 text-embedding-v4 / text-embedding-3-small）。 */
    public String getKnowledgeEmbeddingModel() {
        return db.settingDao().getValue(KEY_KB_MODEL).orElse("").trim();
    }

    public void setKnowledgeEmbeddingModel(String model) {
        db.settingDao().upsert(KEY_KB_MODEL, model == null ? "" : model.trim(), "向量知识库 embedding 模型");
    }

    /** 读取明文 embedding API Key（密文存储，取出即解密）；未配置或解密失败返回空串。 */
    public String getKnowledgeEmbeddingKeyPlain() {
        String stored = db.settingDao().getValue(KEY_KB_APIKEY).orElse("").trim();
        if (stored.isEmpty()) {
            return "";
        }
        try {
            return cipher.canHandle(stored) ? cipher.decrypt(stored) : stored;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 保存 embedding API Key（加密入库）；空白即清除已存 Key。 */
    public void setKnowledgeEmbeddingKey(String plain) {
        if (plain == null || plain.isBlank()) {
            db.settingDao().upsert(KEY_KB_APIKEY, "", "向量知识库 embedding API Key（密文）");
            return;
        }
        db.settingDao().upsert(KEY_KB_APIKEY, cipher.encrypt(plain.trim()), "向量知识库 embedding API Key（密文）");
    }

    // ===================== 图像生成（OpenAI 兼容 images/generations） =====================

    /** 图像生成服务协议根（未配置返回空串）。 */
    public String getImageBaseUrl() {
        return db.settingDao().getValue(KEY_IMG_BASEURL).orElse("").trim();
    }

    public void setImageBaseUrl(String url) {
        db.settingDao().upsert(KEY_IMG_BASEURL, url == null ? "" : url.trim(), "图像生成服务地址");
    }

    /** 图像模型编码（如 dall-e-3 / cogview-3）。 */
    public String getImageModel() {
        return db.settingDao().getValue(KEY_IMG_MODEL).orElse("").trim();
    }

    public void setImageModel(String model) {
        db.settingDao().upsert(KEY_IMG_MODEL, model == null ? "" : model.trim(), "图像生成模型");
    }

    /** 读取明文图像 API Key（密文存储，取出即解密）；未配置或解密失败返回空串。 */
    public String getImageApiKeyPlain() {
        String stored = db.settingDao().getValue(KEY_IMG_APIKEY).orElse("").trim();
        if (stored.isEmpty()) {
            return "";
        }
        try {
            return cipher.canHandle(stored) ? cipher.decrypt(stored) : stored;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 保存图像 API Key（加密入库）；空白即清除已存 Key（无鉴权的本地服务可留空）。 */
    public void setImageApiKey(String plain) {
        if (plain == null || plain.isBlank()) {
            db.settingDao().upsert(KEY_IMG_APIKEY, "", "图像生成 API Key（密文）");
            return;
        }
        db.settingDao().upsert(KEY_IMG_APIKEY, cipher.encrypt(plain.trim()), "图像生成 API Key（密文）");
    }

    /** 是否已配置可用的图像生成服务：地址与模型齐备即视为可用（Key 可空，兼容本地免鉴权服务）。 */
    public boolean isImageConfigured() {
        return !getImageBaseUrl().isEmpty() && !getImageModel().isEmpty();
    }

    // ===================== Docker 沙箱（自动通过档隔离执行） =====================

    /** 自动通过档是否启用 Docker 沙箱隔离（缺省关闭；关闭时命令按既有本地方式执行）。 */
    public boolean isSandboxDockerEnabled() {
        return db.settingDao().getValue(KEY_SANDBOX_DOCKER)
                .map(v -> "1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    public void setSandboxDockerEnabled(boolean enabled) {
        db.settingDao().upsert(KEY_SANDBOX_DOCKER, enabled ? "1" : "0",
                "自动通过档启用 Docker 沙箱隔离");
    }

    /** 沙箱镜像（如 python:3.12-slim）；未设置或空白回退 {@link #SANDBOX_IMAGE_DEFAULT}。 */
    public String getSandboxImage() {
        return db.settingDao().getValue(KEY_SANDBOX_IMAGE)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .orElse(SANDBOX_IMAGE_DEFAULT);
    }

    /** 保存沙箱镜像；空白即恢复默认（存空串，读取侧统一回退）。 */
    public void setSandboxImage(String image) {
        db.settingDao().upsert(KEY_SANDBOX_IMAGE, image == null ? "" : image.trim(),
                "Docker 沙箱镜像");
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
