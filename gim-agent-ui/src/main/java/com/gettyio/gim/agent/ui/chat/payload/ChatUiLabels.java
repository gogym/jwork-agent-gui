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

package com.gettyio.gim.agent.ui.chat.payload;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.gettyio.gim.agent.ui.i18n.I18n;

/**
 * 聊天页前端文案包：{@code chat.js} 的 {@code setTheme(mode, font, lang, labels)} 契约视图。
 * <p>Java 侧 I18n 文案快照整体推给前端，切换语言时重推替换；组件名与前端
 * {@code LABELS.xxx} 键一一对应，属于对 {@code chat.js} 的稳定 ABI。各字段均为
 * 本地化文案（可能含 {@code {0}/{1}} 占位符，由前端替换实际计数）。</p>
 *
 * @param copy        复制按钮
 * @param copied      复制成功提示
 * @param preview     代码块预览按钮
 * @param speak       朗读按钮
 * @param regenerate  重新生成按钮
 * @param editResend  编辑重发按钮
 * @param aiGenerated 助手气泡「AI 生成」标签
 * @param thinking    思考卡进行中文案
 * @param thinkingDone 思考卡完成文案
 * @param toolCount   工具卡标题模板（{@code {0}} 为调用次数）
 * @param toolFailed  工具卡失败标题模板（{@code {0}} 次数、{@code {1}} 失败数）
 * @param toolInput   工具详情「入参」标签
 * @param toolOutput  工具详情「输出」标签
 * @param delegate    子智能体委派显示模板（{@code {0}} 为目标智能体）
 *
 * @author gogym
 */
@JsonPropertyOrder({"copy", "copied", "preview", "speak", "regenerate", "editResend", "aiGenerated",
        "thinking", "thinkingDone", "toolCount", "toolFailed", "toolInput", "toolOutput", "delegate"})
public record ChatUiLabels(
        String copy,
        String copied,
        String preview,
        String speak,
        String regenerate,
        String editResend,
        String aiGenerated,
        String thinking,
        String thinkingDone,
        String toolCount,
        String toolFailed,
        String toolInput,
        String toolOutput,
        String delegate) {

    /** 从当前 I18n 语言包整包装配全部前端文案键（键清单只此一处，杜绝漏推漂移）。 */
    public static ChatUiLabels fromI18n() {
        return new ChatUiLabels(
                I18n.t("chat.msg.copy"),
                I18n.t("chat.msg.copied"),
                I18n.t("chat.artifact.preview"),
                I18n.t("chat.msg.speak"),
                I18n.t("chat.msg.regenerate"),
                I18n.t("chat.msg.editResend"),
                I18n.t("chat.msg.aiGenerated"),
                I18n.t("chat.thinking"),
                I18n.t("chat.thinking.done"),
                I18n.t("chat.tool.group.count", "{0}", "{1}"),
                I18n.t("chat.tool.group.failed", "{0}", "{1}"),
                I18n.t("chat.tool.params"),
                I18n.t("chat.tool.output"),
                I18n.t("chat.tool.subagent.delegate", "{0}"));
    }
}
