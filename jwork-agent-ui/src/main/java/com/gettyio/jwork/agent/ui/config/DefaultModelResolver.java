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

package com.gettyio.jwork.agent.ui.config;

import com.gettyio.jwork.agent.core.model.ModelSpec;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.entity.AgentDefinition;
import com.gettyio.jwork.agent.domain.entity.ModelConfig;
import com.gettyio.jwork.agent.domain.entity.ModelProviderConfig;
import com.gettyio.jwork.agent.domain.enums.ApiProtocol;
import com.gettyio.jwork.agent.domain.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认模型解析：从 settings 表读取 default.provider/default.model，
 * 关联 provider+model 配置并解密 apiKey，产出 {@link ModelSpec}。
 *
 * @author gogym
 */
public class DefaultModelResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelResolver.class);

    public static final String KEY_DEFAULT_PROVIDER = "default.provider";
    public static final String KEY_DEFAULT_MODEL = "default.model";

    private final JworkDatabase db;
    private final SecretCipher cipher;

    public DefaultModelResolver(JworkDatabase db, SecretCipher cipher) {
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

        ModelConfig model = db.modelConfigDao().findByCode(mCode)
                .orElseThrow(() -> new IllegalStateException("模型不存在: " + mCode));
        return specForModel(pCode, mCode, model.getModelName());
    }

    /**
     * 按提供商编码 + 指定模型名拼规格：提供商必须已入库（BaseUrl 与密钥从它身上取），
     * 但模型名可以是表单里刚填还没保存的那个，以便新增时先测连通再落库。
     */
    public ModelSpec specForModel(String providerCode, String modelCode, String modelName) {
        ModelProviderConfig provider = db.modelProviderDao().findByCode(providerCode)
                .orElseThrow(() -> new IllegalStateException("模型提供商不存在: " + providerCode));
        return ModelSpec.builder()
                .providerCode(provider.getProviderCode())
                .modelCode(modelCode)
                .protocol(ApiProtocol.fromCode(provider.getApiProtocol()))
                .modelName(modelName)
                .baseUrl(provider.getBaseUrl())
                .apiKey(decryptApiKey(provider))
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
        return withGenParams(base, agent);
    }

    /**
     * 消息级模型覆盖：{@code providerCode} 与 {@code modelCode} 同时给出时，用它作为本条消息的
     * 定向模型（盖过智能体自带）；任一为空则完全回退 {@link #resolveFor(AgentDefinition)}。
     * <p>无论走哪条，生成参数（temperature/maxTokens）仍沿用智能体定义，为空则用模型默认。</p>
     */
    public ModelSpec resolveFor(AgentDefinition agent, String providerCode, String modelCode) {
        boolean override = providerCode != null && !providerCode.isBlank()
                && modelCode != null && !modelCode.isBlank();
        if (!override) {
            return resolveFor(agent);
        }
        return withGenParams(resolve(providerCode, modelCode), agent);
    }

    /**
     * 主模型故障时的备用模型：与主模型同提供商，按 {@code priority} 升序取下一个启用且非主模型的模型；
     * 生成参数沿用智能体定义与主模型一致。无候选或主模型自身解析失败时返回 {@code null}（不启用 failover）。
     * <p><b>消息级显式指定模型的轮次不提供备用</b>：用户在会话里选定了具体模型（对额度/资费/能力
     * 有明确偏好，对比链路的目标也是所选模型），失败时直接报错比静默换模型更符合预期。</p>
     */
    public ModelSpec resolveFallback(AgentDefinition agent, String providerCode, String modelCode) {
        // 消息级覆盖即「已指定」：与 resolveFor 同一谓词，不降级（失败经流错误原样显示给用户）
        if (providerCode != null && !providerCode.isBlank()
                && modelCode != null && !modelCode.isBlank()) {
            return null;
        }
        try {
            ModelSpec primary = resolveFor(agent, providerCode, modelCode);
            for (ModelConfig candidate : db.modelConfigDao().findEnabledByProvider(primary.providerCode())) {
                if (!candidate.getModelCode().equals(primary.modelCode())) {
                    return withGenParams(
                            specForModel(primary.providerCode(), candidate.getModelCode(), candidate.getModelName()),
                            agent);
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("备用模型解析失败，不启用 failover", e);
            return null;
        }
    }

    /** 用智能体定义的生成参数覆盖 base（仅当对应参数非空），否则原样返回。 */
    private ModelSpec withGenParams(ModelSpec base, AgentDefinition agent) {
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
