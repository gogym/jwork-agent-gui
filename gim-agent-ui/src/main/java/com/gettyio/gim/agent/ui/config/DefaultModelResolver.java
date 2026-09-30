package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.model.ModelSpec;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.AgentDefinition;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.enums.ApiProtocol;
import com.gettyio.gim.agent.domain.security.SecretCipher;

/**
 * 默认模型解析：从 settings 表读取 default.provider/default.model，
 * 关联 provider+model 配置并解密 apiKey，产出 {@link ModelSpec}。
 */
public class DefaultModelResolver {

    public static final String KEY_DEFAULT_PROVIDER = "default.provider";
    public static final String KEY_DEFAULT_MODEL = "default.model";

    private final GimDatabase db;
    private final SecretCipher cipher;

    public DefaultModelResolver(GimDatabase db, SecretCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    public ModelSpec resolve() {
        return resolve(null, null);
    }

    /**
     * 按指定提供商/模型编码解析；任一为空则回退 settings 中的默认配置。
     */
    public ModelSpec resolve(String providerCode, String modelCode) {
        String pCode = (providerCode == null || providerCode.isBlank())
                ? db.settingDao().getValue(KEY_DEFAULT_PROVIDER)
                        .orElseThrow(() -> new IllegalStateException("尚未设置默认模型提供商，请在设置界面配置"))
                : providerCode;
        String mCode = (modelCode == null || modelCode.isBlank())
                ? db.settingDao().getValue(KEY_DEFAULT_MODEL)
                        .orElseThrow(() -> new IllegalStateException("尚未设置默认模型，请在设置界面配置"))
                : modelCode;

        ModelProviderConfig provider = db.modelProviderDao().findByCode(pCode)
                .orElseThrow(() -> new IllegalStateException("模型提供商不存在: " + pCode));
        ModelConfig model = db.modelConfigDao().findByCode(mCode)
                .orElseThrow(() -> new IllegalStateException("模型不存在: " + mCode));

        String apiKey = decryptApiKey(provider);
        return ModelSpec.builder()
                .providerCode(provider.getProviderCode())
                .modelCode(model.getModelCode())
                .protocol(ApiProtocol.fromCode(provider.getApiProtocol()))
                .modelName(model.getModelName())
                .baseUrl(provider.getBaseUrl())
                .apiKey(apiKey)
                .stream(true)
                .build();
    }

    /**
     * 按智能体定义解析模型：providerCode 与 modelCode 同时给出才作定向覆盖（只给其一
     * 视为配置不完整，回退应用默认以免跳提供商错配）；定义内的
     * temperature/maxTokens 覆盖生成参数，为空则沿用模型默认。
     */
    public ModelSpec resolveFor(AgentDefinition agent) {
        boolean pinned = agent != null
                && agent.getProviderCode() != null && !agent.getProviderCode().isBlank()
                && agent.getModelCode() != null && !agent.getModelCode().isBlank();
        ModelSpec base = pinned ? resolve(agent.getProviderCode(), agent.getModelCode()) : resolve();
        if (agent == null || (agent.getTemperature() == null && agent.getMaxTokens() == null)) {
            return base;
        }
        return ModelSpec.builder()
                .providerCode(base.providerCode())
                .modelCode(base.modelCode())
                .protocol(base.protocol())
                .modelName(base.modelName())
                .baseUrl(base.baseUrl())
                .apiKey(base.apiKey())
                .temperature(agent.getTemperature() != null ? agent.getTemperature() : base.temperature())
                .maxTokens(agent.getMaxTokens() != null ? agent.getMaxTokens() : base.maxTokens())
                .stream(base.stream())
                .build();
    }

    /**
     * 解密 API Key：库里的密钥一律是密文，解密失败（密钥轮换、换机器、脏数据）就明确报错。
     * <p>不把原值当成密钥凑合用：那会把密文发给提供商，用户只看到一个摸不着头脑的 401。</p>
     */
    private String decryptApiKey(ModelProviderConfig provider) {
        String stored = provider.getApiKey();
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(stored);
        } catch (Exception e) {
            throw new IllegalStateException("模型提供商 " + provider.getProviderCode()
                    + " 的 API Key 无法解密，请在设置界面重新录入", e);
        }
    }
}
