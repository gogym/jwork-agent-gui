package com.gettyio.gim.agent.ui.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.core.chat.ToolCallRecordCodec;
import com.gettyio.gim.agent.ui.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.Set;

/**
 * 工具调用折叠卡片：标题行显示「工具名 + 状态（成功/失败）+ 耗时」，点击展开/收起正文，
 * 正文分别以只读等宽文本域呈现「参数」与「输出」（各已在落盘前截断）。
 * <p>实时流式与历史重放共用本卡片：前者由 {@code TOOL_END} 事件即时构建，后者由
 * {@code conversations.tool_calls} 列解码而来。样式类挂节点上，颜色全部走 {@code -gim-*} 变量，明暗自适应。</p>
 * <p>委派类工具（{@code agent_spawn} / {@code agent_send}）以专属卡片呈现：从入参解析归属智能体与
 * 子任务，标题改为「委派给 &lt;智能体&gt;」，正文以「子任务」与「结果」取代通用的「参数」与「输出」。</p>
 */
final class ToolCallCard {

    private static final String CHEVRON_COLLAPSED = "\u25B6";
    private static final String CHEVRON_EXPANDED = "\u25BC";

    /** 子智能体委派工具名：命中则渲染为专属委派卡片 */
    private static final Set<String> DELEGATE_TOOLS = Set.of("agent_spawn", "agent_send");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallCard() {
    }

    /** 构建一张折叠卡片节点。 */
    static Node build(ToolCallRecordCodec.ToolCallRecord record) {
        boolean delegate = record.name() != null && DELEGATE_TOOLS.contains(record.name());
        String agentId = null;
        String task = null;
        if (delegate) {
            Map<String, Object> args = parseArgs(record.input());
            agentId = text(args.get("agent_id"));
            task = text(args.get("task"));
        }

        Label chevron = new Label(CHEVRON_COLLAPSED);
        chevron.getStyleClass().add("tool-card-chevron");

        String titleText = delegate && agentId != null && !agentId.isBlank()
                ? I18n.t("chat.tool.subagent.delegate", agentId)
                : (record.name() == null || record.name().isBlank() ? "tool" : record.name());
        Label title = new Label(titleText);
        title.getStyleClass().add("tool-card-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label status = new Label(I18n.t(record.ok() ? "chat.tool.ok" : "chat.tool.failed"));
        status.getStyleClass().addAll("tool-card-status", record.ok() ? "tool-card-ok" : "tool-card-err");

        HBox header = new HBox(6, chevron, title, spacer, status);
        header.getStyleClass().add("tool-card-header");
        header.setAlignment(Pos.CENTER_LEFT);
        if (record.durationMillis() > 0) {
            Label duration = new Label(I18n.t("chat.tool.duration", record.durationMillis()));
            duration.getStyleClass().add("tool-card-duration");
            header.getChildren().add(1, duration);
        }

        VBox body = new VBox(6);
        body.getStyleClass().add("tool-card-body");
        body.setVisible(false);
        body.setManaged(false);
        if (delegate) {
            if (task != null && !task.isBlank()) {
                body.getChildren().add(field(I18n.t("chat.tool.subagent.task"), task));
            }
            if (record.output() != null && !record.output().isBlank()) {
                body.getChildren().add(field(I18n.t("chat.tool.subagent.result"), record.output()));
            }
        } else {
            if (record.input() != null && !record.input().isBlank()) {
                body.getChildren().add(field(I18n.t("chat.tool.params"), record.input()));
            }
            if (record.output() != null && !record.output().isBlank()) {
                body.getChildren().add(field(I18n.t("chat.tool.output"), record.output()));
            }
        }
        if (body.getChildren().isEmpty()) {
            body.getChildren().add(field(I18n.t(delegate ? "chat.tool.subagent.result" : "chat.tool.output"), ""));
        }

        header.setOnMouseClicked(e -> {
            boolean show = !body.isVisible();
            body.setVisible(show);
            body.setManaged(show);
            chevron.setText(show ? CHEVRON_EXPANDED : CHEVRON_COLLAPSED);
        });

        VBox card = new VBox(header, body);
        card.getStyleClass().add("tool-card");
        if (delegate) {
            card.getStyleClass().add("tool-card-subagent");
        }
        return card;
    }

    /** 解析委派工具入参（raw args JSON）；空/非对象/解析失败均返回空映射（委派卡片不能因脏参数崩溃）。 */
    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank() || json.charAt(0) != '{') {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 一段带标签的只读等宽文本域（参数或输出）。 */
    private static VBox field(String label, String value) {
        Label caption = new Label(label);
        caption.getStyleClass().add("tool-card-field-label");
        TextArea area = new TextArea(value);
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("tool-card-field");
        VBox.setMargin(area, new Insets(0, 0, 0, 0));
        return new VBox(2, caption, area);
    }
}
