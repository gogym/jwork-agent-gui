package com.gettyio.gim.agent.core.chat;

import io.agentscope.core.permission.PermissionMode;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.List;

/**
 * 一轮对话请求：承载消息级模型覆盖、图片附件与权限模式。
 * <p>{@code providerCode} 与 {@code modelCode} 同时给出才对本条消息生效，否则回退
 * 智能体自带模型或应用默认；{@code images} 为空即纯文本对话，行为与改造前一致。</p>
 */
@Getter
@Builder(toBuilder = true)
public class ChatRequest {

    private final String agentCode;
    private final String sessionId;
    private final String text;
    /** 消息级模型覆盖：提供商编码（与 modelCode 成对出现才生效）。 */
    private final String providerCode;
    /** 消息级模型覆盖：模型编码（与 providerCode 成对出现才生效）。 */
    private final String modelCode;
    /** 图片附件；无附件时为空列表。 */
    @Singular
    private final List<ImageAttachment> images;
    /** 文档附件（pdf/docx/xlsx/txt/md/csv）；发送时抽取正文拼入模型输入，原件落盘。 */
    @Singular
    private final List<FileAttachment> files;
    /** 本轮工具调用的权限模式；为空按 {@link PermissionMode#ACCEPT_EDITS} 处理（只读自动、写需确认）。 */
    @Builder.Default
    private final PermissionMode permissionMode = PermissionMode.ACCEPT_EDITS;
    /** 自动通过档（配合 ACCEPT_EDITS）：确认事件照常触发拦截链评估，但 ASK 不弹窗直接放行。 */
    @Builder.Default
    private final boolean autoApprove = false;
    /** 规划档：为 true 时本轮先以只读计划模式运行（进入/退出包在流两端），智能体只调查与写计划、不改文件。 */
    @Builder.Default
    private final boolean planMode = false;
}
