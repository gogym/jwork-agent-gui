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

package com.gettyio.jwork.agent.tools.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 prompt 从正文里挑相关段落：抓取回来的长页面，模型真正要的往往只是其中几句。
 *
 * <p>做法刻意保持朴素可预测：把 prompt 切成词（拉丁词按整词，中文按整串 + 二字切片，因为中文没有
 * 空格），逐段计分（命中次数封顶 3 次、长词权重更高），取分数最高的若干段填满预算，再按原文顺序
 * 输出——顺序乱了模型会读错因果。没命中就回空选择，由调用方降级为「正文开头 + 说明」，
 * 绝不硬凑一段不相干的内容冒充答案。</p>
 *
 * @author gogym
 */
public final class PassageSelector {

    /** 选段结果：{@code text} 为空表示没有任何段落命中。 */
    public record Selection(String text, int matched, int total) {

        private static final Selection NONE = new Selection("", 0, 0);
    }

    /** 参与计分的词上限：再多也只是噪声。 */
    private static final int MAX_TERMS = 12;

    /** 最低命中分：低于此视为不相干。 */
    private static final int MIN_SCORE = 4;

    /** 单个词最多计几次命中，防止长段落靠刷屏取胜。 */
    private static final int MAX_HITS_PER_TERM = 3;

    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9][A-Za-z0-9._+-]*");

    /** 问句里的填充词：留着会把每个段落都算命中。 */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "was", "with", "that", "this", "from", "have",
            "has", "how", "what", "when", "where", "which", "who", "why", "does", "did",
            "you", "your", "about", "into", "than", "then", "them", "they", "their",
            "please", "find", "show", "tell", "give", "summary", "summarize");

    private PassageSelector() {
    }

    /**
     * 选出与 prompt 最相关的段落。
     *
     * @param text     已按块分行的正文
     * @param prompt   用户/模型想要的信息描述
     * @param maxChars 输出预算（字符）
     * @return 命中段落（保持原文顺序）；一个都没命中时返回 {@link Selection#NONE}
     */
    public static Selection select(String text, String prompt, int maxChars) {
        List<String> terms = terms(prompt);
        if (terms.isEmpty() || text == null || text.isBlank()) {
            return Selection.NONE;
        }
        String[] lines = text.split("\n");
        List<Scored> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int score = score(lines[i], terms);
            if (score >= MIN_SCORE) {
                hits.add(new Scored(i, lines[i].trim(), score));
            }
        }
        if (hits.isEmpty()) {
            return Selection.NONE;
        }
        hits.sort((a, b) -> b.score() != a.score()
                ? Integer.compare(b.score(), a.score()) : Integer.compare(a.index(), b.index()));
        List<Scored> picked = new ArrayList<>();
        int used = 0;
        for (Scored h : hits) {
            if (used + h.text().length() > maxChars && !picked.isEmpty()) {
                break;
            }
            picked.add(h);
            used += h.text().length() + 1;
        }
        picked.sort((a, b) -> Integer.compare(a.index(), b.index()));
        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        for (Scored h : picked) {
            if (sb.length() >= maxChars) {
                break;
            }
            String line = h.text();
            if (sb.length() + line.length() > maxChars) {
                // 预算耗尽：宁可留个省略号，也不能把整段超长文本灌进上下文
                line = line.substring(0, Math.max(0, maxChars - sb.length() - 1)) + "…";
            }
            sb.append(line).append('\n');
            emitted++;
        }
        return new Selection(sb.toString().trim(), emitted, lines.length);
    }

    /** prompt → 词表：中文额外产出二字切片，覆盖「退款订单」命中「退款明细订单」这类情况。 */
    private static List<String> terms(String prompt) {
        Set<String> out = new LinkedHashSet<>();
        if (prompt == null) {
            return List.of();
        }
        Matcher m = TOKEN.matcher(prompt);
        while (m.find() && out.size() < MAX_TERMS) {
            String run = m.group().toLowerCase(Locale.ROOT);
            if (run.length() < 2 || STOPWORDS.contains(run)) {
                continue;
            }
            out.add(run);
            if (isHan(run)) {
                for (int i = 0; i + 2 <= run.length() && out.size() < MAX_TERMS; i++) {
                    out.add(run.substring(i, i + 2));
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static int score(String line, List<String> terms) {
        String lower = line.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            int hits = occurrences(lower, term);
            if (hits > 0) {
                score += Math.min(hits, MAX_HITS_PER_TERM) * (term.length() >= 3 ? 3 : 2);
            }
        }
        return score;
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0 && count < MAX_HITS_PER_TERM) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static boolean isHan(String s) {
        return Character.UnicodeScript.of(s.charAt(0)) == Character.UnicodeScript.HAN;
    }

    /** 一条命中：原文行号（用于恢复顺序）、文本与得分。 */
    private record Scored(int index, String text, int score) {
    }
}
