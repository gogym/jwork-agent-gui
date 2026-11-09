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

package com.gettyio.gim.agent.ui.export;

import com.gettyio.gim.agent.ui.i18n.I18n;

import java.util.Locale;
import java.util.Map;

/**
 * 导出格式化共享纯函数：Markdown / HTML / PDF / DOCX 四种导出器共用的
 * 工具入参摘要与角色本地化标签。
 * <p>仅 {@code ui.export} 包内部使用（包私有）。摘要口径与标签文案在此收敛一处，
 * 避免各导出器各自实现导致展示漂移。</p>
 *
 * @author gogym
 */
final class ExportFormats {

    private ExportFormats() {
    }

    /** 工具入参摘要：{@code (k=v, ...)}，单值超 80 字符截断为「前 77 字符 + ...」。 */
    static String summarizeInput(Map<String, Object> input) {
        StringBuilder sb = new StringBuilder();
        input.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            String s = String.valueOf(v);
            if (s.length() > 80) {
                s = s.substring(0, 77) + "...";
            }
            sb.append(k).append("=").append(s);
        });
        return "(" + sb + ")";
    }

    /** 角色 → 带 emoji 的本地化标签（Markdown 导出用；{@code null} → "消息"，未知角色原样返回）。 */
    static String roleLabelMd(String role) {
        if (role == null) {
            return I18n.t("export.role.message");
        }
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "USER" -> "🧑 " + I18n.t("export.role.user");
            case "ASSISTANT" -> "🤖 " + I18n.t("export.role.assistant");
            case "SYSTEM" -> "⚙️ " + I18n.t("export.role.system");
            case "TOOL" -> "🔧 " + I18n.t("export.role.tool");
            default -> role;
        };
    }

    /** 角色 → 纯文本本地化标签（PDF / DOCX 与 HTML 导出用；{@code null} → "消息"）。 */
    static String rolePlain(String role) {
        if (role == null) {
            return I18n.t("export.role.message");
        }
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "USER" -> I18n.t("export.role.user");
            case "ASSISTANT" -> I18n.t("export.role.assistant");
            case "SYSTEM" -> I18n.t("export.role.system");
            case "TOOL" -> I18n.t("export.role.tool");
            default -> role;
        };
    }
}
