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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 常见大模型参考单价目录：新增模型时按编码预填「输入 / 输出单价」，省去手工换算。
 * <p>数值单位与 models 表的 {@code cost_per_1k_*} 一致：人民币元 / 千 token。
 * 数据为 2026-10 各厂商价目（每百万 token 计）折算而来：人民币直除 1000，
 * 美元按参考汇率 {@link #USD_CNY} 折算后除 1000。部分模型存在峰谷价、
 * 缓存价、长上下文档位差异，这里统一取常规档（非峰值 / 基础档）价。</p>
 * <p>仅供预填参考：实际账单因缓存命中、峰谷时段、上下文长度而异，
 * 请保存前按自己的实际价目核对修改。</p>
 *
 * @author gogym
 */
public final class ModelPriceCatalog {

    /** 美元 → 人民币参考汇率（仅用于参考价折算，非实时汇率）。 */
    private static final double USD_CNY = 7.1;

    /** 编码（小写）→ [输入单价, 输出单价]，单位：元 / 千 token。 */
    private static final Map<String, double[]> PRICES = new LinkedHashMap<>();

    static {
        // ---- DeepSeek（2026-09/10 官方与阿里云百炼价目折算；旧编码 deepseek-chat 官方已下线，保留映射供遗留配置预填） ----
        PRICES.put("deepseek-chat", cnyPerM(1, 4));
        PRICES.put("deepseek-flash", cnyPerM(1, 4));
        PRICES.put("deepseek-v4-flash", cnyPerM(1, 2));
        PRICES.put("deepseek-v4-pro", cnyPerM(12, 24));

        // ---- 通义千问（阿里云百炼人民币价目） ----
        PRICES.put("qwen-turbo", cnyPerM(0.36, 0.72));
        PRICES.put("qwen-plus", cnyPerM(0.96, 2.4));
        PRICES.put("qwen-long", cnyPerM(0.5, 2));
        PRICES.put("qwen-max", cnyPerM(2.88, 11.52));
        PRICES.put("qwen3-max", cnyPerM(3, 12));
        PRICES.put("qwen3.8-flash", cnyPerM(0.96, 3.24));
        PRICES.put("qwen3.7-plus", cnyPerM(2.4, 9.6));
        PRICES.put("qwen3.6-plus", cnyPerM(2.4, 14.4));
        PRICES.put("qwen3.5-plus", cnyPerM(0.96, 5.76));
        PRICES.put("qwen3.5-flash", cnyPerM(0.24, 2.4));

        // ---- OpenAI（美元 / 百万 token，按参考汇率折算） ----
        PRICES.put("gpt-6-astra", usdPerM(10, 50));
        PRICES.put("gpt-6.1-sol", usdPerM(2, 10));
        PRICES.put("gpt-6-luna", usdPerM(0.10, 0.50));
        PRICES.put("gpt-5.6-sol", usdPerM(4, 20));
        PRICES.put("gpt-5.4-mini", usdPerM(0.25, 2));
        PRICES.put("gpt-5.4", usdPerM(2.50, 15));
        PRICES.put("gpt-5.1-chat", usdPerM(0.625, 5));
        PRICES.put("gpt-4o-mini", usdPerM(0.15, 0.60));
        PRICES.put("gpt-4o", usdPerM(2.50, 10));
        PRICES.put("gpt-4.1-mini", usdPerM(0.40, 1.60));
        PRICES.put("o4-mini", usdPerM(1.10, 4.40));

        // ---- Claude（美元 / 百万 token；Haiku 5.5 取 ≤100K 提示档基础价） ----
        PRICES.put("claude-fable-5", usdPerM(10, 50));
        PRICES.put("claude-opus-5-5", usdPerM(4, 20));
        PRICES.put("claude-opus-5", usdPerM(5, 25));
        PRICES.put("claude-sonnet-5-5", usdPerM(2, 10));
        PRICES.put("claude-sonnet-5", usdPerM(2, 10));
        PRICES.put("claude-sonnet-4-5", usdPerM(3, 15));
        PRICES.put("claude-haiku-5-5", usdPerM(0.10, 0.50));
        PRICES.put("claude-haiku-4-5", usdPerM(1, 5));
    }

    private ModelPriceCatalog() {
    }

    /**
     * 按模型编码查参考单价，支持带版本 / 日期后缀的编码（最长前缀匹配）。
     *
     * @param modelCode 模型编码（大小写不敏感）
     * @return {@code [输入单价, 输出单价]}（元 / 千 token）；无匹配返回 {@code null}
     */
    public static double[] lookup(String modelCode) {
        if (modelCode == null) {
            return null;
        }
        String code = modelCode.trim().toLowerCase(Locale.ROOT);
        if (code.isEmpty()) {
            return null;
        }
        double[] exact = PRICES.get(code);
        if (exact != null) {
            return exact.clone();
        }
        // 最长前缀优先：gpt-4o-mini-xxx 必须命中 mini 而不是 gpt-4o
        String best = null;
        for (String key : PRICES.keySet()) {
            if (code.startsWith(key) && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        return best == null ? null : PRICES.get(best).clone();
    }

    /** 目录内全部已知编码（测试与排查用）。 */
    static List<String> knownCodes() {
        return List.copyOf(PRICES.keySet());
    }

    /** 人民币（每百万 token）→ 目录值（元 / 千 token）。 */
    private static double[] cnyPerM(double inputPerMillion, double outputPerMillion) {
        return new double[]{perK(inputPerMillion), perK(outputPerMillion)};
    }

    /** 美元（每百万 token）→ 目录值（元 / 千 token，按参考汇率折算）。 */
    private static double[] usdPerM(double inputPerMillion, double outputPerMillion) {
        return new double[]{perK(inputPerMillion * USD_CNY), perK(outputPerMillion * USD_CNY)};
    }

    /** 每百万 token 单价 → 每千 token 单价，保留 7 位小数消除浮点误差。 */
    private static double perK(double pricePerMillion) {
        return BigDecimal.valueOf(pricePerMillion / 1000.0).setScale(7, RoundingMode.HALF_UP).doubleValue();
    }
}
