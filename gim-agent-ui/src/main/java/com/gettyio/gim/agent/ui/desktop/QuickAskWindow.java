package com.gettyio.gim.agent.ui.desktop;

import com.gettyio.gim.agent.core.chat.ChatRequest;
import com.gettyio.gim.agent.core.chat.ChatService;
import com.gettyio.gim.agent.core.chat.ImageAttachment;
import com.gettyio.gim.agent.core.chat.StreamEvent;
import com.gettyio.gim.agent.domain.enums.StreamEventType;
import com.gettyio.gim.agent.ui.I18n;
import com.gettyio.gim.agent.ui.UiTheme;
import com.gettyio.gim.agent.ui.voice.MicHoldButton;
import com.gettyio.gim.agent.ui.voice.VoiceInputService;
import com.gettyio.gim.agent.ui.window.GimDialog;
import io.agentscope.core.permission.PermissionMode;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 快速问答小窗（全局热键 Ctrl+Alt+Q 唤起）：一个常驻、置顶、独立的轻量问答面板，
 * 复用 {@link ChatService} 的流式对话链路，可在任意界面快速提问并把截屏作为图片附件发送。
 * <p>为规避小窗内的副作用确认，统一以只读档（{@link PermissionMode#EXPLORE}）运行：读类工具可用，
 * 写/Shell 被框架拒绝。会话为独立「快速会话」，首次发送时才创建。</p>
 * <p>同一次仅允许一条在途生成（避免并发占坑）；隐藏/关闭小窗即取消在途订阅。所有 FX 操作经
 * {@link Platform#runLater} 投递，兼容从热键消息泵线程调用。</p>
 */
@Component
public class QuickAskWindow {

    private static final Logger log = LoggerFactory.getLogger(QuickAskWindow.class);

    private final ChatService chatService;
    private final VoiceInputService voiceInputService;

    @Autowired
    public QuickAskWindow(ChatService chatService, VoiceInputService voiceInputService) {
        this.chatService = chatService;
        this.voiceInputService = voiceInputService;
    }

    private Stage stage;
    private TextArea input;
    private VBox thread;
    private ScrollPane scroll;
    private Label attachLabel;
    private final List<ImageAttachment> pendingImages = new ArrayList<>();
    private String quickSessionId;
    private Disposable active;

    /** 热键入口：显示中则隐藏，否则唤回并聚焦输入框。 */
    public void toggle() {
        if (stage != null && stage.isShowing()) {
            Platform.runLater(this::hideAndStop);
        } else {
            show();
        }
    }

    /**
     * 划词助手入口：唤出小窗并预置待处理文本。
     *
     * @param text     预置到输入框的文本（光标置于末尾）
     * @param autoSend 为 true 时预置后立即发送（翻译/润色）；false 仅预填待用户补充问题（问 AI）
     */
    public void askWith(String text, boolean autoSend) {
        Platform.runLater(() -> {
            buildIfNeeded();
            if (!stage.isShowing()) {
                stage.show();
            }
            stage.toFront();
            stage.requestFocus();
            if (input != null) {
                input.setText(text);
                input.positionCaret(text.length());
                input.requestFocus();
            }
            if (autoSend) {
                sendNow();
            }
        });
    }

    /** 唤起小窗（首次惰性构建），置于屏幕中部并聚焦输入框。 */
    public void show() {
        Platform.runLater(() -> {
            buildIfNeeded();
            if (!stage.isShowing()) {
                stage.show();
            }
            stage.toFront();
            stage.requestFocus();
            if (input != null) {
                input.requestFocus();
            }
        });
    }

    private void buildIfNeeded() {
        if (stage != null) {
            return;
        }
        Label title = new Label(I18n.t("quickask.title"));
        title.getStyleClass().add("quickask-title");
        Button close = closeButton();
        HBox header = new HBox(8, title, spacer(), close);
        header.setAlignment(Pos.CENTER_LEFT);

        thread = new VBox(10);
        thread.setPadding(new Insets(4));
        thread.setMaxWidth(Double.MAX_VALUE);
        scroll = new ScrollPane(thread);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("quickask-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        attachLabel = new Label("");
        attachLabel.getStyleClass().add("quickask-attach");

        Button shot = new Button(I18n.t("quickask.screenshot"));
        shot.getStyleClass().add("quickask-btn");
        shot.setOnAction(e -> capture());
        // 按住说话：识别文本填入输入框，未配置/失败给提示弹窗
        Button mic = new Button();
        MicHoldButton.install(mic, voiceInputService,
                text -> {
                    input.appendText(text + " ");
                    input.requestFocus();
                },
                () -> GimDialog.message(stage, I18n.t("voice.title"), null, I18n.t("voice.notConfigured")),
                (errTitle, msg) -> GimDialog.message(stage, errTitle, null, msg));
        Button send = new Button(I18n.t("quickask.send"));
        send.getStyleClass().add("btn-primary");
        send.setOnAction(e -> sendNow());
        HBox footer = new HBox(8, shot, mic, attachLabel, spacer(), send);
        footer.setAlignment(Pos.CENTER_LEFT);

        input = new TextArea();
        input.setPromptText(I18n.t("quickask.input.hint"));
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                sendNow();
            }
        });

        VBox root = new VBox(10, header, scroll, input, footer);
        root.setPadding(new Insets(14));
        root.getStyleClass().add("quickask-root");

        stage = new Stage(StageStyle.UNDECORATED);
        stage.setAlwaysOnTop(true);
        stage.setTitle(I18n.t("quickask.title"));
        Scene scene = new Scene(root, 520, 460);
        UiTheme.apply(scene);
        stage.setScene(scene);
        stage.centerOnScreen();
    }

    private Button closeButton() {
        Button b = new Button("\u2715");
        b.getStyleClass().add("quickask-close");
        b.setOnAction(e -> hideAndStop());
        return b;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** 截屏提问：圈选区域后作为待发图片附件挂上，等待用户补充问题再发送。 */
    private void capture() {
        ScreenshotCapture.captureRegion(bytes -> Platform.runLater(() -> {
            String b64 = Base64.getEncoder().encodeToString(bytes);
            pendingImages.add(new ImageAttachment("screenshot.png", "image/png", b64));
            refreshAttachLabel();
            if (input != null) {
                input.requestFocus();
            }
        }));
    }

    private void refreshAttachLabel() {
        attachLabel.setText(pendingImages.isEmpty() ? ""
                : I18n.t("quickask.attach.count", pendingImages.size()));
    }

    private void sendNow() {
        if (active != null && !active.isDisposed()) {
            return; // 在途生成期间不并发
        }
        String text = input.getText();
        boolean hasText = text != null && !text.isBlank();
        if (!hasText && pendingImages.isEmpty()) {
            return;
        }
        if (quickSessionId == null) {
            quickSessionId = chatService.createSession(null);
        }
        List<ImageAttachment> images = List.copyOf(pendingImages);
        appendUser(hasText ? text : I18n.t("quickask.imageonly"));
        Label assistant = appendAssistant();
        input.clear();
        pendingImages.clear();
        refreshAttachLabel();

        ChatRequest req = ChatRequest.builder()
                .sessionId(quickSessionId)
                .text(hasText ? text : "")
                .images(images)
                .permissionMode(PermissionMode.EXPLORE)
                .build();
        StringBuilder acc = new StringBuilder();
        active = chatService.chat(req)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ev -> Platform.runLater(() -> onEvent(ev, assistant, acc)),
                        err -> {
                            log.warn("快速问答失败", err);
                            Platform.runLater(() -> {
                                assistant.setText(I18n.t("chat.failed", String.valueOf(err.getMessage())));
                                active = null;
                            });
                        },
                        () -> Platform.runLater(() -> active = null));
    }

    private void onEvent(StreamEvent ev, Label assistant, StringBuilder acc) {
        if (ev.getType() == StreamEventType.TEXT_DELTA && ev.getText() != null) {
            acc.append(ev.getText());
            assistant.setText(acc.toString());
            scrollToBottom();
        }
    }

    private void appendUser(String text) {
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.getStyleClass().add("quickask-user");
        bubble.setMaxWidth(440);
        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_RIGHT);
        thread.getChildren().add(row);
        scrollToBottom();
    }

    private Label appendAssistant() {
        Label bubble = new Label(I18n.t("quickask.thinking"));
        bubble.setWrapText(true);
        bubble.getStyleClass().add("quickask-assistant");
        bubble.setMaxWidth(460);
        thread.getChildren().add(bubble);
        scrollToBottom();
        return bubble;
    }

    private void scrollToBottom() {
        thread.applyCss();
        thread.layout();
        scroll.setVvalue(1.0);
    }

    private void hideAndStop() {
        Disposable a = active;
        if (a != null && !a.isDisposed()) {
            a.dispose();
        }
        active = null;
        if (stage != null) {
            stage.hide();
        }
    }

    @PreDestroy
    public void dispose() {
        try {
            hideAndStop();
        } catch (RuntimeException e) {
            log.debug("快速问答小窗销毁时忽略", e);
        }
    }
}
