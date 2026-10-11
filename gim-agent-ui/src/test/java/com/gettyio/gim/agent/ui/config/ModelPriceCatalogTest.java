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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelPriceCatalog} 单元测试：精确匹配、最长前缀、大小写与未知编码。
 *
 * @author gogym
 */
class ModelPriceCatalogTest {

    @Test
    void exactMatchReturnsConfiguredPrice() {
        double[] qwenPlus = ModelPriceCatalog.lookup("qwen-plus");
        assertNotNull(qwenPlus);
        assertEquals(0.00096, qwenPlus[0], 1e-9);
        assertEquals(0.0024, qwenPlus[1], 1e-9);
    }

    @Test
    void exactMatchBeatsShorterPrefix() {
        double[] gpt4o = ModelPriceCatalog.lookup("gpt-4o");
        double[] mini = ModelPriceCatalog.lookup("gpt-4o-mini");
        assertNotNull(gpt4o);
        assertNotNull(mini);
        assertEquals(0.01775, gpt4o[0], 1e-9);
        assertNotEquals(gpt4o[0], mini[0]);
    }

    @Test
    void longestPrefixWinsForVersionedCode() {
        double[] mini = ModelPriceCatalog.lookup("gpt-4o-mini");
        double[] versioned = ModelPriceCatalog.lookup("gpt-4o-mini-2024-07-18");
        assertNotNull(versioned);
        assertEquals(mini[0], versioned[0], 1e-9);
        assertEquals(mini[1], versioned[1], 1e-9);
    }

    @Test
    void prefixMatchesDateSuffixedCode() {
        double[] dated = ModelPriceCatalog.lookup("qwen-max-2025-01-25");
        assertNotNull(dated);
        assertEquals(0.00288, dated[0], 1e-9);
    }

    @Test
    void lookupIsCaseAndWhitespaceInsensitive() {
        double[] upper = ModelPriceCatalog.lookup(" QWEN-PLUS ");
        assertNotNull(upper);
        assertEquals(0.00096, upper[0], 1e-9);
    }

    @Test
    void unknownOrBlankCodeReturnsNull() {
        assertNull(ModelPriceCatalog.lookup("unknown-model-xyz"));
        assertNull(ModelPriceCatalog.lookup(""));
        assertNull(ModelPriceCatalog.lookup("   "));
        assertNull(ModelPriceCatalog.lookup(null));
    }

    @Test
    void allCatalogEntriesAreFiniteAndNonNegative() {
        assertFalse(ModelPriceCatalog.knownCodes().isEmpty());
        for (String code : ModelPriceCatalog.knownCodes()) {
            double[] price = ModelPriceCatalog.lookup(code);
            assertNotNull(price, code);
            assertTrue(Double.isFinite(price[0]) && price[0] >= 0, code);
            assertTrue(Double.isFinite(price[1]) && price[1] >= 0, code);
        }
    }

    @Test
    void lookupReturnsDefensiveCopy() {
        double[] first = ModelPriceCatalog.lookup("qwen-plus");
        assertNotNull(first);
        first[0] = 999;
        assertEquals(0.00096, ModelPriceCatalog.lookup("qwen-plus")[0], 1e-9);
    }
}
