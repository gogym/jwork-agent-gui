package com.gettyio.gim.agent.ui.view;

import com.gettyio.gim.agent.domain.entity.ScheduledTask;
import com.gettyio.gim.agent.domain.enums.EnabledStatus;
import com.gettyio.gim.agent.domain.enums.TaskStatus;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.config.TaskAdminService;
import com.gettyio.gim.agent.ui.window.GimDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 定时任务整页（对标 Qoder「自动化」页）：页头 + 任务卡片网格 + 空状态引导。
 * <p>新建/编辑使用轻量表单弹窗，操作直连 {@link TaskAdminService}（含 Quartz 联动）。
 * 全部文案走 {@link I18n}，语言切换时刷新页头并重绘卡片。</p>
 */
@Component
public class TasksView {

    @Autowired
    private TaskAdminService taskAdminService;

    private final VBox root = new VBox();
    private final TilePane grid = new TilePane();
    private final StackPane body = new StackPane();
    private ScrollPane scroller;
    private Label titleLabel;
    private Label descLabel;
    private Button createButton;
    private Button refreshButton;

    public TasksView() {
        buildUi();
        I18n.onChange(lang -> {
            applyI18n();
            reload();
        });
    }

    public VBox getNode() {
        return root;
    }

    private void buildUi() {
        root.getStyleClass().add("content-page");

        titleLabel = new Label();
        titleLabel.getStyleClass().add("page-h1");
        descLabel = new Label();
        descLabel.getStyleClass().add("page-desc");
        // 窄窗口下描述不换行，压缩到极限后省略号隐藏，始终保住右侧操作区
        descLabel.setMinWidth(0);
        descLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        refreshButton = iconButton("\uE72C", this::reload);
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);
        createButton = new Button();
        createButton.getStyleClass().add("btn-primary");
        createButton.setMinWidth(Region.USE_PREF_SIZE);
        createButton.setOnAction(e -> openForm(null));

        // 右侧控件与描述同一行：行内中心对齐使控件中心线与描述文字中心共线，标题独占上一行
        HBox descRow = new HBox(10, descLabel, spacer, refreshButton, createButton);
        descRow.setAlignment(Pos.CENTER_LEFT);
        VBox headText = new VBox(4, titleLabel, descRow);
        headText.setMaxWidth(Double.MAX_VALUE);

        HBox header = new HBox(headText);
        HBox.setHgrow(headText, Priority.ALWAYS);
        header.getStyleClass().add("page-header");

        grid.getStyleClass().add("card-grid");
        grid.setHgap(14);
        grid.setVgap(14);
        grid.setPrefColumns(2);
        grid.setTileAlignment(Pos.TOP_LEFT);

        scroller = new ScrollPane(grid);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        body.getChildren().add(scroller);
        VBox.setVgrow(body, Priority.ALWAYS);

        root.getChildren().addAll(header, body);
        applyI18n();
    }

    private void applyI18n() {
        titleLabel.setText(I18n.t("tasks.title"));
        descLabel.setText(I18n.t("tasks.desc"));
        createButton.setText(I18n.t("tasks.new"));
        refreshButton.getTooltip().setText(I18n.t("common.refresh"));
    }

    /** 重新加载任务卡片（切页或语言切换时调用） */
    public void reload() {
        List<ScheduledTask> tasks = taskAdminService.list();
        grid.getChildren().clear();
        if (tasks.isEmpty()) {
            body.getChildren().setAll(scroller, emptyState());
            return;
        }
        body.getChildren().setAll(scroller);
        tasks.stream()
                .sorted(Comparator.comparing(ScheduledTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(t -> grid.getChildren().add(card(t)));
    }

    // ---- 卡片 ----

    private Node card(ScheduledTask t) {
        VBox card = new VBox(6);
        card.getStyleClass().add("task-card");
        card.setMinWidth(380);
        card.setMaxWidth(500);

        Label name = new Label(t.getTaskName() == null || t.getTaskName().isBlank()
                ? t.getTaskCode() : t.getTaskName());
        name.getStyleClass().add("card-title");
        HBox.setHgrow(name, Priority.ALWAYS);
        Label badge = statusBadge(t.getStatus());
        HBox top = new HBox(10, name, badge);
        top.setAlignment(Pos.CENTER_LEFT);

        Label cron = new Label(t.getCronExpression());
        cron.getStyleClass().add("mono");

        Label prompt = new Label(truncate(t.getPrompt(), 90));
        prompt.getStyleClass().add("card-desc");
        prompt.setWrapText(true);

        Label next = new Label(nextRunText(t));
        next.getStyleClass().add("card-meta");

        boolean ready = TaskStatus.READY.name().equalsIgnoreCase(t.getStatus());
        HBox actions = new HBox(4);
        actions.getStyleClass().add("card-actions");
        if (!TaskStatus.FINISHED.name().equalsIgnoreCase(t.getStatus())) {
            actions.getChildren().add(linkButton(ready ? I18n.t("tasks.action.pause") : I18n.t("tasks.action.resume"), () -> {
                taskAdminService.setStatus(t.getTaskCode(),
                        ready ? TaskStatus.PAUSED : TaskStatus.READY);
                reload();
            }));
        }
        actions.getChildren().add(linkButton(I18n.t("tasks.action.runNow"), () -> taskAdminService.runNow(t)));
        actions.getChildren().add(linkButton(I18n.t("tasks.action.edit"), () -> openForm(t)));
        Button del = linkButton(I18n.t("tasks.action.delete"), () -> confirmDelete(t));
        del.getStyleClass().add("link-btn-danger");
        actions.getChildren().add(del);

        card.getChildren().addAll(top, cron, prompt, next, new Separator(), actions);
        return card;
    }

    private Label statusBadge(String status) {
        Label b = new Label();
        b.getStyleClass().add("badge");
        String s = status == null ? "" : status.toUpperCase();
        switch (s) {
            case "READY" -> { b.setText(I18n.t("tasks.status.ready")); b.getStyleClass().add("badge-green"); }
            case "PAUSED" -> { b.setText(I18n.t("tasks.status.paused")); b.getStyleClass().add("badge-gray"); }
            case "FINISHED" -> { b.setText(I18n.t("tasks.status.finished")); b.getStyleClass().add("badge-gray"); }
            case "FAILED" -> { b.setText(I18n.t("tasks.status.failed")); b.getStyleClass().add("badge-red"); }
            default -> { b.setText(s); b.getStyleClass().add("badge-gray"); }
        }
        return b;
    }

    private String nextRunText(ScheduledTask t) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (TaskStatus.READY.name().equalsIgnoreCase(t.getStatus()) && t.getCronExpression() != null) {
            try {
                Date next = new CronExpression(t.getCronExpression().trim())
                        .getNextValidTimeAfter(new Date());
                if (next != null) {
                    return I18n.t("tasks.nextRun", fmt.format(next));
                }
            } catch (ParseException ignored) {
                // 非法 cron 交由保存时校验，这里降级显示
            }
        }
        if (t.getLastRunAt() != null) {
            return I18n.t("tasks.lastRun", t.getLastRunAt().format(java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        return "";
    }

    private Node emptyState() {
        VBox box = new VBox();
        box.getStyleClass().add("empty-state");
        box.setMaxWidth(Double.MAX_VALUE);
        Label icon = new Label("\uE823");
        icon.getStyleClass().add("empty-icon");
        Label t = new Label(I18n.t("tasks.empty.title"));
        t.getStyleClass().add("empty-title");
        Label d = new Label(I18n.t("tasks.empty.desc"));
        d.getStyleClass().add("empty-desc");
        d.setWrapText(true);
        d.setMaxWidth(460);
        box.getChildren().addAll(icon, t, d);
        return box;
    }

    // ---- 新建 / 编辑表单弹窗 ----

    private void openForm(ScheduledTask editing) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        GimDialog.Shell shell = GimDialog.shell(owner,
                editing == null ? I18n.t("tasks.form.newTitle") : I18n.t("tasks.form.editTitle"));

        TextField nameField = new TextField(editing == null ? "" : editing.getTaskName());
        nameField.setPromptText(I18n.t("tasks.form.namePrompt"));
        TextField cronField = new TextField(editing == null ? "" : editing.getCronExpression());
        cronField.setPromptText(I18n.t("tasks.form.cronPrompt"));
        TextArea promptArea = new TextArea(editing == null ? "" : editing.getPrompt());
        promptArea.setPrefRowCount(4);
        promptArea.setPromptText(I18n.t("tasks.form.promptHint"));
        CheckBox onceCheck = new CheckBox(I18n.t("tasks.form.once"));
        onceCheck.setSelected(editing != null && EnabledStatus.isEnabled(editing.getOnce()));

        GridPane form = new GridPane();
        form.getStyleClass().add("form-card");
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label(I18n.t("tasks.form.name")), nameField);
        form.addRow(1, new Label(I18n.t("tasks.form.cron")), cronField);
        form.addRow(2, new Label(I18n.t("tasks.form.prompt")), promptArea);
        form.addRow(3, new Label(""), onceCheck);
        form.setPrefWidth(420);

        Label err = new Label();
        err.setStyle("-fx-text-fill: #b3403f; -fx-font-size: 12px;");
        err.setWrapText(true);

        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            if (cronField.getText().isBlank() || promptArea.getText().isBlank()) {
                err.setText(I18n.t("tasks.form.err.empty"));
                return;
            }
            ScheduledTask t = editing != null ? editing : new ScheduledTask();
            if (editing == null) {
                t.setTaskCode("task-" + System.currentTimeMillis());
                t.setStatus(TaskStatus.READY.name());
                t.setAgentCode("gim-agent");
            }
            t.setTaskName(nameField.getText().isBlank() ? t.getTaskCode() : nameField.getText().trim());
            t.setCronExpression(cronField.getText().trim());
            t.setPrompt(promptArea.getText());
            t.setOnce(onceCheck.isSelected() ? EnabledStatus.ENABLED : EnabledStatus.DISABLED);
            try {
                taskAdminService.save(t);
                shell.stage().close();
                reload();
            } catch (Exception ex) {
                err.setText(I18n.t("tasks.form.err.save", ex.getMessage()));
            }
        });
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setOnAction(e -> shell.stage().close());
        HBox btns = new HBox(8, save, cancel);
        btns.setAlignment(Pos.CENTER_RIGHT);

        shell.body().getChildren().addAll(form, err, btns);
        shell.show();
    }

    private void confirmDelete(ScheduledTask t) {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        if (GimDialog.confirm(owner, I18n.t("nav.tasks"),
                I18n.t("tasks.deleteConfirm", t.getTaskName()))) {
            taskAdminService.delete(t.getTaskCode());
            reload();
        }
    }

    // ---- 小工具 ----

    private Button iconButton(String iconText, Runnable action) {
        Button b = new Button();
        b.getStyleClass().add("icon-btn");
        b.setTooltip(new javafx.scene.control.Tooltip());
        Label icon = new Label(iconText);
        icon.getStyleClass().add("icon");
        b.setGraphic(icon);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button linkButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("link-btn");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
