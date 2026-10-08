package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.model.AgentScopeModelFactory;
import com.gettyio.gim.agent.core.model.ModelProbe;
import com.gettyio.gim.agent.core.model.ModelSpec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.enums.ApiProtocol;
import com.gettyio.gim.agent.domain.enums.AuthMethod;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.security.SecretCipher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 模型提供商 / 模型配置的增删改查与默认模型设置。
 * <p>apiKey 落库前经 {@link SecretCipher} 加密；配置变更后清空 {@link AgentScopeModelFactory} 缓存。</p>
 */
public class ProviderAdminService {

    /** 模型可用性：与 models.status 的取值一致，由连接测试写回。 */
    public static final String MODEL_STATUS_VALID = "VALID";
    public static final String MODEL_STATUS_INVALID = "INVALID";
    /** 未测过：新增模型时的初始态。 */
    public static final String MODEL_STATUS_UNKNOWN = "UNKNOWN";

    private final GimDatabase db;
    private final SecretCipher cipher;
    private final AgentScopeModelFactory modelFactory;
    private final DefaultModelResolver resolver;
    private final ModelProbe modelProbe;

    public ProviderAdminService(GimDatabase db, SecretCipher cipher, AgentScopeModelFactory modelFactory,
                                DefaultModelResolver resolver, ModelProbe modelProbe) {
        this.db = db;
        this.cipher = cipher;
        this.modelFactory = modelFactory;
        this.resolver = resolver;
        this.modelProbe = modelProbe;
    }

    // ---- 提供商 ----

    public List<ModelProviderConfig> listProviders() {
        return db.modelProviderDao().findAll();
    }

    /**
     * 保存提供商。
     *
     * @param form          表单数据（编辑时其 apiKey 为库中现有密文）
     * @param newPlainKey   新输入的明文密钥；为空表示沿用现有密文
     */
    public void saveProvider(ModelProviderConfig form, String newPlainKey) {
        if (newPlainKey != null && !newPlainKey.isBlank()) {
            form.setApiKey(cipher.encrypt(newPlainKey.trim()));
        } else if (form.getApiKey() != null && !form.getApiKey().isBlank() && !cipher.canHandle(form.getApiKey())) {
            // 表单里带的是明文（新建时直接填的），加密之
            form.setApiKey(cipher.encrypt(form.getApiKey().trim()));
        }
        if (form.getEnabled() == null) {
            form.setEnabled(EnabledStatus.ENABLED);
        }
        boolean exists = db.modelProviderDao().findByCode(form.getProviderCode()).isPresent();
        if (exists) {
            db.modelProviderDao().update(form);
        } else {
            db.modelProviderDao().insert(form);
        }
        modelFactory.invalidate();
    }

    public void deleteProvider(String providerCode) {
        db.modelProviderDao().deleteByCode(providerCode);
        modelFactory.invalidate();
    }

    // ---- 模型 ----

    public List<ModelConfig> listModels() {
        return db.modelConfigDao().findAll();
    }

    /**
     * 是否至少配置并启用了 1 个模型：驱动聊天页「尚未配置模型」常驻提示的显隐。
     * <p>只认「启用」这一硬门槛——一个都没启用时无论怎样都发不出对话；一旦启用任意模型，
     * 工具条即可选中它（或在设置里设为默认），提示随即消失。</p>
     */
    public boolean hasEnabledModel() {
        for (ModelConfig m : db.modelConfigDao().findAll()) {
            if (EnabledStatus.isEnabled(m.getEnabled())) {
                return true;
            }
        }
        return false;
    }

    public List<ModelConfig> listModels(String providerCode) {
        return db.modelConfigDao().findEnabledByProvider(providerCode);
    }

    /**
     * 该模型能否发图片：仅当 {@code supports_vision} 明确测得为 0（不支持）时返回 {@code false}。
     * <p>未测（null）或模型不存在时一律放行——能力位只在多模态测试确认「不支持」后才作为硬拦拦依据，
     * 避免因未测而过紧拦拦正常发送。</p>
     */
    public boolean modelSupportsVision(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return true;
        }
        return db.modelConfigDao().findByCode(modelCode)
                .map(m -> !Integer.valueOf(0).equals(m.getSupportsVision()))
                .orElse(true);
    }

    public void saveModel(ModelConfig form) {
        if (form.getEnabled() == null) {
            form.setEnabled(EnabledStatus.ENABLED);
        }
        if (form.getPriority() == null) {
            form.setPriority(100);
        }
        boolean exists = db.modelConfigDao().findByCode(form.getModelCode()).isPresent();
        if (exists) {
            db.modelConfigDao().update(form);
        } else {
            db.modelConfigDao().insert(form);
        }
        modelFactory.invalidate();
    }

    public void deleteModel(String modelCode) {
        db.modelConfigDao().deleteByCode(modelCode);
        modelFactory.invalidate();
    }

    /**
     * 连接测试：用表单里正在编辑的模型名向所选提供商发一条最小对话。
     * <p>提供商必须已入库（BaseUrl 与密钥从它身上取），模型则不必——新增时正是想在落库前先测一次。
     * 模型已存在时顺手把状态与测试结果写回，让管理页看到的是真实探测结果而不是一律 UNKNOWN。
     * 本方法不抛异常：探测失败本身就是要回显的结果。</p>
     */
    public ModelProbe.Result testModel(String providerCode, String modelCode, String modelName) {
        ModelSpec spec;
        try {
            spec = resolver.specForModel(providerCode, modelCode, modelName);
        } catch (RuntimeException e) {
            // 提供商缺失、密钥解不开、协议字段脏数据等配置问题，归成一条失败结果而不是抛给 UI
            return new ModelProbe.Result(false, e.getMessage());
        }
        ModelProbe.Result result = modelProbe.probe(spec);
        if (modelCode != null && !modelCode.isBlank()) {
            db.modelConfigDao().findByCode(modelCode).ifPresent(saved -> {
                applyProbe(saved, result);
                db.modelConfigDao().update(saved);
            });
        }
        return result;
    }

    /**
     * 多模态测试：向正在编辑的模型发一条带图片的最小对话，判断它是否支持图片输入。
     * <p>与连通性测试 {@link #testModel} 不同，结果只落到 {@code supports_vision} 能力位，
     * 不碰模型的可用性 {@code status}——「不支持多模态」只是能力边界，不代表模型不可用。
     * 模型尚未入库（新增）时只返回结果，交由保存流程写入。本方法不抛异常。</p>
     */
    public ModelProbe.Result testModelVision(String providerCode, String modelCode, String modelName) {
        ModelSpec spec;
        try {
            spec = resolver.specForModel(providerCode, modelCode, modelName);
        } catch (RuntimeException e) {
            return new ModelProbe.Result(false, e.getMessage());
        }
        ModelProbe.Result result = modelProbe.probeVision(spec);
        if (modelCode != null && !modelCode.isBlank()) {
            db.modelConfigDao().findByCode(modelCode).ifPresent(saved -> {
                applyVision(saved, result);
                db.modelConfigDao().update(saved);
            });
        }
        return result;
    }

    /** 把探测结果落到模型实体上：状态 + 最近一次测试时间与详情（供保存时一并入库）。 */
    public static void applyProbe(ModelConfig form, ModelProbe.Result result) {
        form.setStatus(result.ok() ? MODEL_STATUS_VALID : MODEL_STATUS_INVALID);
        form.setLastTestAt(LocalDateTime.now());
        form.setLastTestResult(result.detail());
    }

    /** 把多模态探测结果落到能力位：支持=1、不支持=0（独立于 {@link #applyProbe}，不改写可用性状态）。 */
    public static void applyVision(ModelConfig form, ModelProbe.Result result) {
        form.setSupportsVision(result.ok() ? 1 : 0);
    }

    // ---- 默认模型 ----

    public void setDefault(String providerCode, String modelCode) {
        db.settingDao().upsert(DefaultModelResolver.KEY_DEFAULT_PROVIDER, providerCode, "默认模型提供商");
        db.settingDao().upsert(DefaultModelResolver.KEY_DEFAULT_MODEL, modelCode, "默认模型");
        modelFactory.invalidate();
    }

    public Optional<String> getDefaultProviderCode() {
        return db.settingDao().getValue(DefaultModelResolver.KEY_DEFAULT_PROVIDER);
    }

    public Optional<String> getDefaultModelCode() {
        return db.settingDao().getValue(DefaultModelResolver.KEY_DEFAULT_MODEL);
    }

    // ---- 本地模型一键接入 ----

    /** 本地服务导入结果：落到哪个提供商、新增/跳过各多少个模型。 */
    public record LocalImportResult(String providerCode, int imported, int skipped) {
    }

    /**
     * 把探测到的本地服务（Ollama / LM Studio）一键接入：提供商不存在则创建，模型逐个落库（已存在的跳过）。
     * <p>本地服务无需密钥：Ollama 走原生协议（密钥留空），LM Studio 走 OpenAI 兼容协议；
     * 两者对 apiKey 均不校验，这里统一不填。重复点击安全：幂等，只补缺失的模型。</p>
     *
     * @param kind       {@code OLLAMA} 或 {@code LM_STUDIO}（见 {@code LocalModelDetector.LocalService#kind}）
     * @param baseUrl    探测到的服务基础地址
     * @param modelNames 服务端模型名清单（Ollama 为 {@code name}，LM Studio 为 {@code id}）
     */
    public LocalImportResult importLocalModels(String kind, String baseUrl, List<String> modelNames) {
        boolean isOllama = "OLLAMA".equalsIgnoreCase(kind);
        String providerCode = isOllama ? "ollama" : "lmstudio";
        String providerName = isOllama ? "Ollama (本地)" : "LM Studio (本地)";
        ApiProtocol protocol = isOllama ? ApiProtocol.OLLAMA : ApiProtocol.OPENAI_COMPATIBLE;

        ModelProviderConfig provider = db.modelProviderDao().findByCode(providerCode)
                .orElseGet(() -> {
                    ModelProviderConfig p = new ModelProviderConfig();
                    p.setProviderCode(providerCode);
                    p.setProviderName(providerName);
                    p.setApiProtocol(protocol.name());
                    p.setAuthMethod(AuthMethod.BEARER_TOKEN.name());
                    p.setEnabled(EnabledStatus.ENABLED);
                    return p;
                });
        provider.setBaseUrl(baseUrl);
        // 保留既有密钥：编辑时表单密钥为空即沿用，这里直接传 null 即可
        saveProvider(provider, null);

        int imported = 0;
        int skipped = 0;
        for (String name : modelNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (db.modelConfigDao().findByCode(name).isPresent()) {
                skipped++;
                continue;
            }
            ModelConfig m = new ModelConfig();
            m.setProviderCode(providerCode);
            m.setModelCode(name);
            m.setModelName(name);
            m.setPriority(200 + imported);
            m.setEnabled(EnabledStatus.ENABLED);
            saveModel(m);
            imported++;
        }
        return new LocalImportResult(providerCode, imported, skipped);
    }
}
