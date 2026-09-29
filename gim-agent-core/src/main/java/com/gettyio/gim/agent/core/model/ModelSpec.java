package com.gettyio.gim.agent.core.model;

import com.gettyio.gim.agent.domain.enums.ApiProtocol;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 模型规格：从数据库 provider/model 配置 + 已解密 apiKey 归一化而来，
 * 作为 {@link AgentScopeModelFactory} 的输入。
 */
@Getter
@Accessors(fluent = true)
@ToString(exclude = "apiKey")
public class ModelSpec {

    private final String providerCode;
    private final String modelCode;
    private final ApiProtocol protocol;
    private final String modelName;
    private final String baseUrl;
    private final String apiKey;
    private final Double temperature;
    private final Integer maxTokens;
    private final boolean stream;

    private ModelSpec(Builder b) {
        this.providerCode = b.providerCode;
        this.modelCode = b.modelCode;
        this.protocol = b.protocol;
        this.modelName = b.modelName;
        this.baseUrl = b.baseUrl;
        this.apiKey = b.apiKey;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.stream = b.stream;
    }

    /** 缓存键 */
    public String cacheKey() {
        return providerCode + ":" + modelCode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String providerCode;
        private String modelCode;
        private ApiProtocol protocol = ApiProtocol.OPENAI_COMPATIBLE;
        private String modelName;
        private String baseUrl;
        private String apiKey;
        private Double temperature;
        private Integer maxTokens;
        private boolean stream = true;

        public Builder providerCode(String v) {
            this.providerCode = v;
            return this;
        }

        public Builder modelCode(String v) {
            this.modelCode = v;
            return this;
        }

        public Builder protocol(ApiProtocol v) {
            this.protocol = v;
            return this;
        }

        public Builder modelName(String v) {
            this.modelName = v;
            return this;
        }

        public Builder baseUrl(String v) {
            this.baseUrl = v;
            return this;
        }

        public Builder apiKey(String v) {
            this.apiKey = v;
            return this;
        }

        public Builder temperature(Double v) {
            this.temperature = v;
            return this;
        }

        public Builder maxTokens(Integer v) {
            this.maxTokens = v;
            return this;
        }

        public Builder stream(boolean v) {
            this.stream = v;
            return this;
        }

        public ModelSpec build() {
            if (protocol == null) {
                throw new IllegalArgumentException("protocol 不能为空");
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("modelName 不能为空");
            }
            return new ModelSpec(this);
        }
    }
}
