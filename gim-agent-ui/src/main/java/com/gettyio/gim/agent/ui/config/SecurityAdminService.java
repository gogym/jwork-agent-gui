/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gettyio.gim.agent.ui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gettyio.gim.agent.core.capability.CapabilityCodec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ToolPermissionRule;
import com.gettyio.gim.agent.domain.security.SecretStore;
import com.gettyio.gim.agent.tools.guard.DangerousCommandPolicy;
import com.gettyio.gim.agent.tools.guard.DangerousRule;
import com.gettyio.gim.agent.tools.sandbox.SandboxExecutor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 *
 * @author gogym
 */
public class SecurityAdminService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_APPROVAL_DEFAULT = "security.approval.default";
    private static final String KEY_DANGEROUS_RULES = "security.dangerous.rules";
    private static final String KEY_SHELL_ALLOWLIST = "security.shell.allowlist";
    private static final String KEY_PATH_ROOTS = "security.path.roots";
    private static final String KEY_WEB_PROVIDER = "web.search.provider";
    /** API Key 按提供方独立存储（前缀 + 提供方编码）：Tavily 与博查的 Key 互不串用。 */
    private static final String KEY_WEB_APIKEY_PREFIX = "web.search.apiKey.";
    private static final String KEY_WEB_BASEURL = "web.search.baseUrl";
    /** 是否允许工具访问内网/回环地址（默认关闭，见 {@code AddressPolicy}）。 */
    private static final String KEY_URL_ALLOW_PRIVATE = "web.fetch.allowPrivateAddresses";
    private static final String KEY_KB_BASEURL = "knowledge.embedding.baseUrl";
    private static final String KEY_KB_MODEL = "knowledge.embedding.model";
    private static final String KEY_KB_APIKEY = "knowledge.embedding.apiKey";
    private static final String KEY_IMG_BASEURL = "image.generation.baseUrl";
    private static final String KEY_IMG_MODEL = "image.generation.model";
    private static final String KEY_IMG_APIKEY = "image.generation.apiKey";
    private static final String KEY_SANDBOX_DOCKER = "agent.sandbox.autoApproveDocker";
    private static final String KEY_SANDBOX_IMAGE = "agent.sandbox.image";

    /** settings.remark 文案：密钥项一律标注密文存储，改动需保持既有值不变以免已入库行被重写。 */
    private static final String REMARK_WEB_APIKEY = "联网搜索 API Key（密文）";
    private static final String REMARK_KB_APIKEY = "向量知识库 embedding API Key（密文）";
    private static final String REMARK_IMG_APIKEY = "图像生成 API Key（密文）";

    /** 联网搜索提供方：Tavily——AI 原生搜索（默认）。 */
    public static final String WEB_PROVIDER_TAVILY = "tavily";
    /** 联网搜索提供方：博查——国内合规搜索（数据不出境）。 */
    public static final String WEB_PROVIDER_BOCHA = "bocha";
    /** 联网搜索提供方：SearXNG——自建元搜索实例（免 Key，需实例地址）。 */
    public static final String WEB_PROVIDER_SEARXNG = "searxng";

    /** Docker 沙箱镜像默认值：与执行器内置默认保持单一来源。 */
    public static final String SANDBOX_IMAGE_DEFAULT = SandboxExecutor.DEFAULT_IMAGE;

    /** 审批档位取值（与聊天页 ApprovalOption 对应）。 */
    public static final String APPROVAL_ASK = "ask";
    public static final String APPROVAL_AUTO = "auto";
    public static final String APPROVAL_READONLY = "readonly";

    private final GimDatabase db;
    private final SecretStore secrets;

    public SecurityAdminService(GimDatabase db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
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

    /**
     * 列出危险命令规则；键缺失时以内置默认播种并持久化。
     *
     * <p>版本升级新增的内置规则会就地补齐（用户的启停与自定义规则原样保留）：黑名单少一条是安全
     * 缺口，不该等用户去设置页点「重置」才发现。因此<b>不想再看到某条内置规则，请停用而非删除</b>
     * ——删除会在下次读取时被补回，停用则永久生效。</p>
     */
    public List<DangerousRule> listDangerousRules() {
        Optional<String> raw = db.settingDao().getValue(KEY_DANGEROUS_RULES);
        if (raw.isEmpty()) {
            List<DangerousRule> seed = new ArrayList<>(DangerousCommandPolicy.DEFAULT_RULES);
            saveRules(seed);
            return seed;
        }
        List<DangerousRule> stored = readRules(raw.get());
        List<DangerousRule> merged = backfillMissingBuiltins(stored);
        if (merged.size() != stored.size()) {
            saveRules(merged);
        }
        return merged;
    }

    /** 追加 stored 里没有的内置规则（按 id 比对）；无缺口时返回同一实例，避免无谓落库。 */
    private static List<DangerousRule> backfillMissingBuiltins(List<DangerousRule> stored) {
        Set<String> present = new LinkedHashSet<>();
        for (DangerousRule r : stored) {
            present.add(r.id());
        }
        List<DangerousRule> missing = new ArrayList<>();
        for (DangerousRule builtin : DangerousCommandPolicy.DEFAULT_RULES) {
            if (!present.contains(builtin.id())) {
                missing.add(builtin);
            }
        }
        if (missing.isEmpty()) {
            return stored;
        }
        List<DangerousRule> out = new ArrayList<>(stored);
        out.addAll(missing);
        return out;
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

    /** 追加一个 shell 白名单程序（小写归一、去重）；空白名忽略。 */
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

    /** 移除一个 shell 白名单程序（忽略大小写匹配）；无变化不落库。 */
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

    /** 追加一个文件授权目录（去首尾空白、去重）；空白路径忽略。 */
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

    /** 移除一个文件授权目录（精确匹配）；无变化不落库。 */
    public void removePathRoot(String path) {
        List<String> list = new ArrayList<>(listPathRoots());
        if (list.removeIf(s -> s.equals(path == null ? "" : path.trim()))) {
            savePaths(list);
        }
    }

    private void savePaths(List<String> list) {
        db.settingDao().upsert(KEY_PATH_ROOTS, CapabilityCodec.writeCodes(list), "文件写入授权目录（用户新增）");
    }

    // ===================== 密钥项读写（统一口径） =====================

    /**
     * 读明文：空白即未配置；密文按前缀路由解密，解不开返回空串并由 {@link SecretStore} 记 ERROR。
     * <p>不存在「把密文原样当明文返回」的分支——那串东西下一步就是 Authorization 头。</p>
     */
    private String openSecret(String storageKey, String what) {
        return secrets.open(db.settingDao().getValue(storageKey).orElse(""), what);
    }

    /** 写密文：空白表示不修改（不落库），返回是否真的写入。 */
    private boolean saveSecret(String storageKey, String plain, String remark) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        db.settingDao().upsert(storageKey, secrets.seal(plain), remark);
        return true;
    }

    /** 显式清除（用户点「清除」才走这里）。 */
    private void clearSecret(String storageKey, String remark) {
        db.settingDao().upsert(storageKey, "", remark);
    }

    private SecretStore.State stateOfSecret(String storageKey) {
        return secrets.state(db.settingDao().getValue(storageKey).orElse(""));
    }

    // ===================== 联网搜索（提供方 + API Key + 实例地址） =====================

    /** 当前提供方编码；未设置或空白回退 Tavily。 */
    public String getWebSearchProvider() {
        return db.settingDao().getValue(KEY_WEB_PROVIDER)
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(v -> !v.isEmpty())
                .orElse(WEB_PROVIDER_TAVILY);
    }

    public void setWebSearchProvider(String provider) {
        String value = provider == null || provider.isBlank() ? WEB_PROVIDER_TAVILY : provider.trim().toLowerCase();
        db.settingDao().upsert(KEY_WEB_PROVIDER, value, "联网搜索提供方");
    }

    /** 自定义实例地址（当前仅 SearXNG 使用；未配置返回空串）。 */
    public String getWebSearchBaseUrl() {
        return db.settingDao().getValue(KEY_WEB_BASEURL).orElse("").trim();
    }

    public void setWebSearchBaseUrl(String url) {
        db.settingDao().upsert(KEY_WEB_BASEURL, url == null ? "" : url.trim(), "联网搜索自定义实例地址（SearXNG）");
    }

    /**
     * 是否允许工具访问内网/回环地址（缺省关闭）。
     * <p>开关只管<b>模型写出来的</b>地址（{@code web_fetch} 与图像结果下载）；用户自己在设置页填的
     * 服务地址不受影响，所以自建 SearXNG、局域网向量库都不必为了能用而放开这一条。</p>
     */
    public boolean isAllowPrivateAddresses() {
        return db.settingDao().getValue(KEY_URL_ALLOW_PRIVATE)
                .map(v -> "1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    public void setAllowPrivateAddresses(boolean enabled) {
        db.settingDao().upsert(KEY_URL_ALLOW_PRIVATE, enabled ? "1" : "0",
                "允许联网工具访问内网/回环地址");
    }

    /** 读取明文 API Key：按当前提供方独立存储；未配置或本机解不开返回空串（解不开已记 ERROR）。供工具运行时实时取用。 */
    public String getWebSearchApiKeyPlain() {
        return openSecret(webApiKeyKey(), "联网搜索 API Key");
    }

    /**
     * 保存 API Key（加密入库），写入当前提供方对应的键。
     * <p>空白表示「不修改」：不落库。界面上一次瞬时解密失败后失焦，不该顺手清空用户配好的 Key；
     * 真要撤销请调 {@link #clearWebSearchApiKey()}。</p>
     *
     * @return 是否发生了写入
     */
    public boolean setWebSearchApiKey(String plain) {
        return saveSecret(webApiKeyKey(), plain, REMARK_WEB_APIKEY);
    }

    /** 显式撤销当前提供方已存的 API Key。 */
    public void clearWebSearchApiKey() {
        clearSecret(webApiKeyKey(), REMARK_WEB_APIKEY);
    }

    /** 当前提供方已存 Key 的状态：界面据此区分「未配置」「已配置」「已配置但本机解不开」。 */
    public SecretStore.State getWebSearchApiKeyState() {
        return stateOfSecret(webApiKeyKey());
    }

    /** 当前提供方对应的 API Key 存储键。 */
    private String webApiKeyKey() {
        return KEY_WEB_APIKEY_PREFIX + getWebSearchProvider();
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

    /** 读取明文 embedding API Key；未配置或本机解不开返回空串。 */
    public String getKnowledgeEmbeddingKeyPlain() {
        return openSecret(KEY_KB_APIKEY, "向量知识库 embedding API Key");
    }

    /** 保存 embedding API Key（加密入库）；空白表示不修改，返回是否写入。 */
    public boolean setKnowledgeEmbeddingKey(String plain) {
        return saveSecret(KEY_KB_APIKEY, plain, REMARK_KB_APIKEY);
    }

    /** 显式撤销 embedding API Key。 */
    public void clearKnowledgeEmbeddingKey() {
        clearSecret(KEY_KB_APIKEY, REMARK_KB_APIKEY);
    }

    /** embedding API Key 的存储状态。 */
    public SecretStore.State getKnowledgeEmbeddingKeyState() {
        return stateOfSecret(KEY_KB_APIKEY);
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

    /** 读取明文图像 API Key；未配置或本机解不开返回空串。 */
    public String getImageApiKeyPlain() {
        return openSecret(KEY_IMG_APIKEY, "图像生成 API Key");
    }

    /** 保存图像 API Key（加密入库）；空白表示不修改，返回是否写入。 */
    public boolean setImageApiKey(String plain) {
        return saveSecret(KEY_IMG_APIKEY, plain, REMARK_IMG_APIKEY);
    }

    /** 显式撤销图像 API Key（无鉴权的本地服务用这一条，而不是把输入框留空）。 */
    public void clearImageApiKey() {
        clearSecret(KEY_IMG_APIKEY, REMARK_IMG_APIKEY);
    }

    /** 图像 API Key 的存储状态。 */
    public SecretStore.State getImageApiKeyState() {
        return stateOfSecret(KEY_IMG_APIKEY);
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

    /** 删除单条「总是允许」规则（id 为空忽略）。 */
    public void delete(Long id) {
        if (id != null) {
            db.permissionRuleDao().delete(id);
        }
    }

    /** 清空全部「总是允许」规则。 */
    public void clear() {
        for (ToolPermissionRule r : db.permissionRuleDao().findAll()) {
            db.permissionRuleDao().delete(r.getId());
        }
    }
}
