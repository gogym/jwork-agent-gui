package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.model.ModelSpec;
import com.gettyio.gim.agent.domain.GimDatabase;
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
        String providerCode = db.settingDao().getValue(KEY_DEFAULT_PROVIDER)
                .orElseThrow(() -> new IllegalStateException("尚未设置默认模型提供商，请在设置界面配置"));
        String modelCode = db.settingDao().getValue(KEY_DEFAULT_MODEL)
                .orElseThrow(() -> new IllegalStateException("尚未设置默认模型，请在设置界面配置"));

        ModelProviderConfig provider = db.modelProviderDao().findByCode(providerCode)
                .orElseThrow(() -> new IllegalStateException("默认提供商不存在: " + providerCode));
        ModelConfig model = db.modelConfigDao().findByCode(modelCode)
                .orElseThrow(() -> new IllegalStateException("默认模型不存在: " + modelCode));

        String apiKey = decryptIfNeeded(provider.getApiKey());
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

    private String decryptIfNeeded(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(stored);
        } catch (Exception e) {
            // 兼容历史明文
            return stored;
        }
    }
}
