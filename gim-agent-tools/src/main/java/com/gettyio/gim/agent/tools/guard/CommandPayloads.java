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

package com.gettyio.gim.agent.tools.guard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解释器载荷展开：把「一条 shell 命令里由解释器再执行一遍的那段脚本」还原成命令文本，
 * 供黑名单正则与程序白名单共用。
 *
 * <p>背景：程序名白名单只看每段的<b>首个</b> token，于是 {@code cmd /c "curl ..."}、
 * {@code bash -c 'rm -rf ~'}、{@code python -c 'import os; os.system(...)'}、
 * {@code powershell -EncodedCommand AAAA…}（UTF-16LE 的 base64）里真正的动作全都处在检查视野之外——
 * 一个白名单内的解释器就足以承载任意命令。不展开载荷，黑名单与白名单都只是纸面约束。</p>
 *
 * <p>只在「该程序确实会把后随实参当脚本执行」时才展开：{@code git -c core.pager=cat} 里的
 * {@code -c} 是配置项而非命令，误当载荷会把 {@code cat} 当成待查程序。深度、条数与总字符数都有上限，
 * 恶意构造的超长/超深命令不会被完整展开（展开失败不表态，维持链尾默认询问）。</p>
 *
 * @author gogym
 */
public final class CommandPayloads {

    /** 递归展开深度上限（{@code cmd /c "powershell -c 'bash -c \"…\"'"} 这类套娃到此为止）。 */
    private static final int MAX_DEPTH = 4;

    /** 单次展开最多产出的载荷条数（防构造命令把检查环节拖长）。 */
    private static final int MAX_PAYLOADS = 32;

    /** 参与匹配的单条载荷最大字符数（黑名单正则在兆级文本上纯属浪费）。 */
    private static final int MAX_PAYLOAD_CHARS = 64_000;

    /**
     * 解释器 → 载荷标志规格。
     *
     * @param flags        其后实参被视为「另一段命令」的标志（已小写、去引号）
     * @param restIsScript 标志之后的<b>全部</b>实参都属于脚本（{@code cmd /c del /q x}）；
     *                     false 时只取紧随的那一个实参（{@code python -c 'code' 其余是脚本参数}）
     */
    private record Spec(Set<String> flags, boolean restIsScript) {
    }

    /** 只把 PowerShell 系独有的编码命令标志当作 base64 载荷。 */
    private static final Set<String> ENCODED_FLAGS = Set.of("-encodedcommand", "-enc");

    private static final Map<String, Spec> SPECS = Map.ofEntries(
            Map.entry("cmd", new Spec(Set.of("/c", "/k"), true)),
            Map.entry("powershell", new Spec(Set.of("/c", "-c", "-command", "-e", "-enc",
                    "-encodedcommand"), true)),
            Map.entry("pwsh", new Spec(Set.of("/c", "-c", "-command", "-e", "-enc",
                    "-encodedcommand"), true)),
            Map.entry("bash", new Spec(Set.of("-c"), true)),
            Map.entry("sh", new Spec(Set.of("-c"), true)),
            Map.entry("dash", new Spec(Set.of("-c"), true)),
            Map.entry("zsh", new Spec(Set.of("-c"), true)),
            Map.entry("python", new Spec(Set.of("-c"), false)),
            Map.entry("python3", new Spec(Set.of("-c"), false)),
            Map.entry("py", new Spec(Set.of("-c"), false)),
            Map.entry("node", new Spec(Set.of("-e", "-c", "--eval", "-p", "--print"), false)),
            Map.entry("deno", new Spec(Set.of("-e", "--eval"), false)),
            Map.entry("perl", new Spec(Set.of("-e"), false)),
            Map.entry("ruby", new Spec(Set.of("-e"), false)));

    /**
     * 仍需人工过目的解释器（「自动」审批档不得替用户放过它们）。
     *
     * <p>这些程序本身在白名单里，因为日常工作离不开；但它们都能把任意代码包在一条命令里，
     * 自动档若照放，等于把整个本机交给模型。除 {@link #SPECS} 登记的脚本解释器外，
     * {@code git} 也在此列——它不接 {@code -c "脚本"}（那是配置项），却能经
     * {@code core.editor}/{@code pager}/{@code ext.*} 等配置调用任意外部程序，风险同级。</p>
     */
    public static final Set<String> INTERPRETERS = buildInterpreters();

    private static Set<String> buildInterpreters() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String name : SPECS.keySet()) {
            out.add(name.toLowerCase(Locale.ROOT));
        }
        out.add("git");
        return Set.copyOf(out);
    }

    private CommandPayloads() {
    }

    /**
     * 一段命令的<b>逐层</b>载荷（含 base64 解码结果，去重保序，深度/条数受限）。
     *
     * @param command 原始命令文本（非 shell 工具或解析不出时返回空表）
     */
    public static List<String> expand(String command) {
        List<String> out = new ArrayList<>();
        collect(command, 0, out);
        return out;
    }

    /** 该程序名是否属于「能承载任意代码」的解释器（判定用 {@link #INTERPRETERS}，含 git）。 */
    public static boolean isInterpreter(String executable) {
        return executable != null && INTERPRETERS.contains(executable.toLowerCase(Locale.ROOT));
    }

    private static void collect(String command, int depth, List<String> out) {
        if (command == null || command.isBlank() || depth >= MAX_DEPTH || out.size() >= MAX_PAYLOADS) {
            return;
        }
        for (String segment : splitSegments(command)) {
            List<String> tokens = tokenize(segment);
            String exe = ExecutableAllowlistPolicy.firstExecutableOf(tokens);
            if (exe == null) {
                continue;
            }
            Spec spec = SPECS.get(exe);
            if (spec == null) {
                continue;
            }
            for (int i = 0; i < tokens.size() - 1; i++) {
                String flag = normalizeFlag(tokens.get(i));
                if (flag == null || !spec.flags().contains(flag)) {
                    continue;
                }
                String payload = ENCODED_FLAGS.contains(flag)
                        ? decodeBase64(tokens.get(i + 1))
                        : joinPayload(tokens, i + 1, spec.restIsScript());
                if (payload == null || payload.isBlank() || payload.length() > MAX_PAYLOAD_CHARS
                        || out.contains(payload)) {
                    continue;
                }
                out.add(payload);
                collect(payload, depth + 1, out);
            }
        }
    }

    /** 取标志之后的脚本文本：整段余下实参（cmd/sh 系）或紧随的单个实参（python/node 系）。 */
    private static String joinPayload(List<String> tokens, int from, boolean restIsScript) {
        if (from >= tokens.size()) {
            return null;
        }
        if (!restIsScript) {
            return tokens.get(from);
        }
        return String.join(" ", tokens.subList(from, tokens.size()));
    }

    /**
     * 解码 {@code -EncodedCommand} 的 base64 载荷。
     *
     * <p>PowerShell 规定该载荷是 UTF-16LE，其 ASCII 文本在 base64 之后表现为「奇数位字节成对为
     * {@code 0x00}」；据此判别编码，判不出来才按 UTF-8 解。解不出可打印文本即视为无载荷——
     * 宁可放过一条读不懂的命令（仍会落到默认询问），也不把二进制垃圾喂给黑名单正则误伤。</p>
     */
    static String decodeBase64(String raw) {
        if (raw == null || raw.length() < 8) {
            return null;
        }
        String token = raw.strip().replace("\"", "").replace("'", "");
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length == 0 || bytes.length > MAX_PAYLOAD_CHARS * 4) {
            return null;
        }
        String text = looksUtf16Le(bytes)
                ? new String(bytes, StandardCharsets.UTF_16LE)
                : new String(bytes, StandardCharsets.UTF_8);
        return looksLikeText(text) ? text : null;
    }

    /** UTF-16LE 的 ASCII 文本：每对字节的后一位是 {@code 0x00}。半数以上如此即认定该编码。 */
    private static boolean looksUtf16Le(byte[] b) {
        if (b.length % 2 != 0) {
            return false;
        }
        int zeros = 0;
        for (int i = 1; i < b.length; i += 2) {
            if (b[i] == 0) {
                zeros++;
            }
        }
        return zeros * 4 >= b.length;
    }

    /** 解码结果须是「能继续当命令看」的文本：不含 NUL，且可打印占比够高。 */
    private static boolean looksLikeText(String s) {
        if (s.isEmpty() || s.length() > MAX_PAYLOAD_CHARS) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0) {
                return false;
            }
            if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                printable++;
            }
        }
        return printable * 10 >= s.length() * 9;
    }

    /**
     * 按 shell 分隔符（{@code &&}、{@code ||}、管道、分号、{@code &}、换行）切段；引号内的分隔符不算分隔符。
     *
     * <p>纯正则切分会把 {@code bash -c "a | b"} 拦腰斩断，载荷被截成残句后再展开就什么都认不出来——
     * 检查形同虚设，故必须带引号状态机。引号字符原样保留，交由 {@link #tokenize} 成对剥离。</p>
     */
    static List<String> splitSegments(String command) {
        List<String> out = new ArrayList<>();
        if (command == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                cur.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == ';' || c == '\n' || c == '\r') {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            if (c == '|' || c == '&') {
                if (i + 1 < command.length() && command.charAt(i + 1) == c) {
                    i++; // 成对的 && 与 || 算一个分隔符
                }
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** 按空白切分并剥引号（引号内空白保留，成对引号本身丢弃）。 */
    static List<String> tokenize(String segment) {
        List<String> out = new ArrayList<>();
        if (segment == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean started = false;
        char quote = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                started = true;
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (started) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
                continue;
            }
            cur.append(c);
            started = true;
        }
        if (started) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 标志归一：小写（tokenize 已去引号）；空则 null。 */
    private static String normalizeFlag(String token) {
        if (token == null) {
            return null;
        }
        String s = token.strip().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
