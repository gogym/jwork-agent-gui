package com.gettyio.gim.agent.ui.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.core.chat.ToolCallRecordCodec;
import com.gettyio.gim.agent.ui.I18n;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用组卡片：把一轮里的多次工具调用合并为一张可折叠卡片——标题行显示「执行工具 N 次（其中 M 次失败）」，
 * 点击整卡展开/收起；展开后逐行列出各次调用（状态圆圈图标 + 工具名 + 参数摘要），点击某一行再展开该次调用的
 * 「输入 / 响应」详情块。
 * <p>摘要取入参 JSON 的首个字符串值（如 path/query），无字符串参数时回退输出首行；输入若为 JSON 则美化缩进展示。
 * 委派类工具（{@code agent_spawn} / {@code agent_send}）行名显示为「委派给 &lt;智能体&gt;」。</p>
 */
final class ToolCallGroupCard {

    /** 折叠/展开箭头与行状态图标（Segoe Fluent Icons 码点；字体族来自全局 .icon 类）。 */
    private static final String CHEVRON_COLLAPSED = "\uE76C";
    private static final String CHEVRON_EXPANDED = "\uE70D";
    private static final String ICON_OK = "\uE930";
    private static final String ICON_ERR = "\uEA39";

    /** 行摘要最大字符数：超长文本先截断再交给 Label 省略，避免拖慢布局。 */
    private static final int SUMMARY_LIMIT = 120;

    /** 子智能体委派工具名：行名命中则渲染为「委派给 &lt;智能体&gt;」。 */
    private static final Set<String> DELEGATE_TOOLS = Set.of("agent_spawn", "agent_send");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallGroupCard() {
    }

    /** 构建整组折叠卡片；默认收起，只露标题行的次数统计。 */
    static Node build(List<ToolCallRecordCodec.ToolCallRecord> records) {
        int failed = 0;
        for (ToolCallRecordCodec.ToolCallRecord r : records) {
            if (!r.ok()) {
                failed++;
            }
        }

        Label chevron = icon(CHEVRON_COLLAPSED, "tool-group-chevron");
        String headerText = failed > 0
                ? I18n.t("chat.tool.group.failed", records.size(), failed)
                : I18n.t("chat.tool.group.count", records.size());
        Label title = new Label(headerText);
        title.getStyleClass().add("tool-group-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(2, chevron, title, spacer);
        header.getStyleClass().add("tool-group-header");
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(2);
        body.getStyleClass().add("tool-group-body");
        body.setVisible(false);
        body.setManaged(false);
        for (ToolCallRecordCodec.ToolCallRecord r : records) {
            body.getChildren().add(item(r));
        }

        header.setOnMouseClicked(e -> {
            boolean show = !body.isVisible();
            body.setVisible(show);
            body.setManaged(show);
            chevron.setText(show ? CHEVRON_EXPANDED : CHEVRON_COLLAPSED);
        });

        VBox card = new VBox(header, body);
        card.getStyleClass().add("tool-group");
        return card;
    }

    /** 单次调用行：状态图标 + 名称（+ 参数摘要）；点击行展开/收起该次调用的输入/响应详情。 */
    private static Node item(ToolCallRecordCodec.ToolCallRecord r) {
        Label status = icon(r.ok() ? ICON_OK : ICON_ERR, "tool-group-icon",
                r.ok() ? "tool-group-icon-ok" : "tool-group-icon-err");
        Label name = new Label(displayName(r));
        name.getStyleClass().add("tool-group-name");
        // 锁定工具名的完整宽度：行内空间不足时优先压缩摘要（否则长摘要把名称挤成省略号）
        name.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(6, status, name);
        row.getStyleClass().add("tool-group-row");
        row.setAlignment(Pos.CENTER_LEFT);
        String summary = summary(r);
        if (summary != null) {
            Label sum = new Label("\u00B7 " + summary);
            sum.getStyleClass().add("tool-group-summary");
            // 摘要吸收全部剩余宽：宽裕时撑满、不足时先被压缩并以省略号收尾（min=0 允许收缩）
            sum.setMinWidth(0);
            sum.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(sum, Priority.ALWAYS);
            row.getChildren().add(sum);
        }

        VBox detail = detail(r);
        row.setOnMouseClicked(e -> {
            boolean show = !detail.isVisible();
            detail.setVisible(show);
            detail.setManaged(show);
        });

        VBox item = new VBox(row, detail);
        item.getStyleClass().add("tool-group-item");
        return item;
    }

    /** 展开详情：输入（JSON 美化）与响应各一个浅底代码块；内容均空时返回隐藏空体。 */
    private static VBox detail(ToolCallRecordCodec.ToolCallRecord r) {
        VBox detail = new VBox(4);
        detail.getStyleClass().add("tool-group-detail");
        detail.setVisible(false);
        detail.setManaged(false);
        if (nonBlank(r.input())) {
            detail.getChildren().addAll(caption("chat.tool.params"), block(pretty(r.input())));
        }
        if (nonBlank(r.output())) {
            detail.getChildren().addAll(caption("chat.tool.output"), block(r.output()));
        }
        return detail;
    }

    /** 行摘要：入参 JSON 首个字符串值（如 path/query），无则取输出首行；压平空白并按上限截断。 */
    private static String summary(ToolCallRecordCodec.ToolCallRecord r) {
        String s = firstString(r.input());
        if (s == null) {
            s = firstLine(r.output());
        }
        if (s == null) {
            return null;
        }
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > SUMMARY_LIMIT ? s.substring(0, SUMMARY_LIMIT) + "\u2026" : s;
    }

    /** 行显示名：委派工具显示「委派给 &lt;智能体&gt;」，其余显示原始工具名。 */
    private static String displayName(ToolCallRecordCodec.ToolCallRecord r) {
        String name = r.name() == null || r.name().isBlank() ? "tool" : r.name();
        if (DELEGATE_TOOLS.contains(name)) {
            Object id = parseArgs(r.input()).get("agent_id");
            if (id != null && !String.valueOf(id).isBlank()) {
                return I18n.t("chat.tool.subagent.delegate", String.valueOf(id));
            }
        }
        return name;
    }

    /** 从入参 JSON 里深度优先取首个非空字符串值；非对象 JSON 或解析失败返回 null。 */
    private static String firstString(String json) {
        if (json == null || json.isBlank() || json.charAt(0) != '{') {
            return null;
        }
        try {
            Map<String, Object> args = MAPPER.readValue(json, new TypeReference<>() {
            });
            return findString(args);
        } catch (Exception e) {
            return null;
        }
    }

    private static String findString(Object value) {
        if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                String s = findString(v);
                if (s != null) {
                    return s;
                }
            }
        } else if (value instanceof List<?> l) {
            for (Object v : l) {
                String s = findString(v);
                if (s != null) {
                    return s;
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int nl = text.indexOf('\n');
        return (nl >= 0 ? text.substring(0, nl) : text).trim();
    }

    /** 入参若为 JSON 则美化缩进展示（非 JSON/解析失败原样返回）。 */
    private static String pretty(String input) {
        String t = input.trim();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
            return input;
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readValue(t, Object.class));
        } catch (Exception e) {
            return input;
        }
    }

    /** 解析委派工具入参（raw args JSON）；空/非对象/解析失败均返回空映射（组卡不能因脏参数崩溃）。 */
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

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 图标 Label：全局 .icon 提供 Fluent 字体族，附加类控制尺寸/颜色。 */
    private static Label icon(String code, String... extraClasses) {
        Label l = new Label(code);
        l.getStyleClass().add("icon");
        l.getStyleClass().addAll(extraClasses);
        return l;
    }

    private static Label caption(String key) {
        Label l = new Label(I18n.t(key));
        l.getStyleClass().add("tool-group-detail-label");
        return l;
    }

    /** 浅底等宽文本块（详情内容）；显式放开最大宽让 VBox 拉伸并随宽度换行。 */
    private static Label block(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.getStyleClass().add("tool-group-detail-text");
        return l;
    }
}
