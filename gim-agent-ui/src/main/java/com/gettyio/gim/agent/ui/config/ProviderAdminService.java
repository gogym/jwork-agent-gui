package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.core.model.AgentScopeModelFactory;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.ModelConfig;
import com.gettyio.gim.agent.domain.entity.ModelProviderConfig;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.security.SecretCipher;

import java.util.List;
import java.util.Optional;

/**
 * 模型提供商 / 模型配置的增删改查与默认模型设置。
 * <p>apiKey 落库前经 {@link SecretCipher} 加密；配置变更后清空 {@link AgentScopeModelFactory} 缓存。</p>
 */
public class ProviderAdminService {

    private final GimDatabase db;
    private final SecretCipher cipher;
    private final AgentScopeModelFactory modelFactory;

    public ProviderAdminService(GimDatabase db, SecretCipher cipher, AgentScopeModelFactory modelFactory) {
        this.db = db;
        this.cipher = cipher;
        this.modelFactory = modelFactory;
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

    public List<ModelConfig> listModels(String providerCode) {
        return db.modelConfigDao().findEnabledByProvider(providerCode);
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
}
