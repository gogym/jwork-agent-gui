package com.gettyio.gim.agent.domain.enums;

/**
 * API 认证方式（部分第三方厂商用 Bearer 头代替 x-api-key）
 */
public enum AuthMethod {

    /** x-api-key 请求头，Anthropic 原生方式 */
    X_API_KEY,

    /** Authorization: Bearer 请求头 */
    BEARER_TOKEN;

    public static AuthMethod fromCode(String code) {
        if (code == null || code.isBlank()) {
            return X_API_KEY;
        }
        for (AuthMethod method : values()) {
            if (method.name().equalsIgnoreCase(code.trim())) {
                return method;
            }
        }
        return X_API_KEY;
    }
}
