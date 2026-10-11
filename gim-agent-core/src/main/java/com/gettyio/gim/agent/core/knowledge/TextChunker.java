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

package com.gettyio.gim.agent.core.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块器：把长文本切成带重叠的固定尺寸块，供向量化索引。
 * <p>按段落优先切分，段落超限时再按句子/硬边界回退；块间保留 overlap 字符，
 * 保证跨块的句子在相邻块中仍可被独立命中。尺寸按字符估算 token（CJK 约 1 字 1 token、
 * 拉丁约 4 字符 1 token），默认 500 token 上限对中英混排文档都接近可用窗口。</p>
 *
 * @author gogym
 */
public final class TextChunker {

    /** 单块目标上限（字符）。 */
    private static final int MAX_CHARS = 1200;
    /** 相邻块重叠（字符）。 */
    private static final int OVERLAP_CHARS = 150;

    private TextChunker() {
    }

    /** 以默认参数分块（500 token 档、150 字符重叠）。 */
    public static List<String> chunk(String text) {
        return chunk(text, MAX_CHARS, OVERLAP_CHARS);
    }

    /**
     * 分块：段落优先 → 句子回退 → 硬截断；空文本返回空列表。
     *
     * @param maxChars 单块最大字符数（含重叠）
     * @param overlapChars 相邻块重叠字符数（须小于 maxChars 的一半）
     */
    public static List<String> chunk(String text, int maxChars, int overlapChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        int overlap = Math.min(overlapChars, maxChars / 2);
        StringBuilder current = new StringBuilder();
        for (String para : text.strip().split("\\n{2,}")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            // 当前块装得下整段就直接并入；否则封块另起（段内超限再单独切）
            if (current.length() > 0 && current.length() + p.length() + 2 > maxChars) {
                out.add(current.toString().strip());
                current = carryOver(current, overlap);
                if (current.length() > 0) {
                    current.append('\n');
                }
            }
            while (p.length() > maxChars - current.length() - 2) {
                int room = Math.max(maxChars - current.length() - 2, maxChars / 2);
                out.add((current + "\n" + p.substring(0, room)).strip());
                p = p.substring(Math.max(room - overlap, 0)).strip();
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }
        if (current.length() > 0) {
            out.add(current.toString().strip());
        }
        return out;
    }

    /** 封块时保留尾部 overlap 字符作为下一块开头（换行处优先断开）。 */
    private static StringBuilder carryOver(StringBuilder current, int overlap) {
        if (overlap <= 0 || current.length() <= overlap) {
            return new StringBuilder();
        }
        String tail = current.substring(current.length() - overlap);
        int brk = tail.indexOf('\n');
        return new StringBuilder(brk >= 0 ? tail.substring(brk + 1) : tail);
    }
}
