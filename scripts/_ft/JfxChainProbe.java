import com.gettyio.gim.agent.ui.jcef.JcefOsrView;
import com.gettyio.gim.agent.ui.jcef.JcefResources;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全链路拖动探针：使用真实 {@link JcefOsrView}（生产类），把手工构造的 JavaFX {@link MouseEvent}
 * 经 {@link Event#fireEvent} 投递到 canvas，完整走「JavaFX 事件 → forwardMouse 合成 AWT →
 * dispatchToBrowser → CefBrowserOsr → Chromium」生产链路。
 *
 * <p>与 DragProbe 的差异：DragProbe 从「合成 AWT 事件」开始测，只覆盖后半段；本探针从「JavaFX 事件」
 * 开始测，覆盖用户真实操作的前半段（上一次修复未被验证的部分）。</p>
 *
 * <p>阶段 1：DRAGGED 携带 isPrimaryButtonDown=true（正常平台语义）→ 期望滚动生效（st1 明显 > 0）
 * 阶段 2：DRAGGED 的 isPrimaryButtonDown=false（模拟平台不回填按钮状态的假设情形）→ 期望
 * pressedButton 兜底逻辑仍令拖动生效（st3 明显 > 0）</p>
 *
 * <p>scrollTop 经 console.log 上报（GIM_ 前缀 → JcefOsrView 的 DisplayHandler 打进日志）。</p>
 */
public final class JfxChainProbe {

    private static final AtomicReference<JcefOsrView> VIEW = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        // CEF 数据目录隔离：主应用可能正在运行并锁着默认目录，指向独立目录避免单实例锁冲突
        System.setProperty("jwork.cef.root", "D:\\GitHub\\gim-agent-gui\\build\\jfx-probe-cef");

        CountDownLatch created = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                VIEW.set(new JcefOsrView("gimQuery", "gimCancel", q -> {}));
                System.out.println("PROBE view created");
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                created.countDown();
            }
        });
        created.await();
        JcefOsrView view = VIEW.get();
        if (view == null) {
            System.out.println("PROBE FAIL: view null");
            System.exit(2);
        }

        Thread.sleep(4000); // CEF 引导 + realize + 首帧 + native browser
        Platform.runLater(() -> view.syncSize(1000, 620));
        Thread.sleep(800);
        
        // 真实聊天页（与生产一致的 chatShell + chat.css 自定义 10px 滚动条）
        final String chatUrl = JcefResources.chatShell("gimQuery");
        Platform.runLater(() -> view.loadUrl(chatUrl));
        Thread.sleep(5000);
        
        // 注入长历史（30 条）产生滚动条；消息内容纯 ASCII 避免干扰
        StringBuilder hist = new StringBuilder("ChatAPI.renderHistory([");
        for (int i = 0; i < 30; i++) {
            if (i > 0) hist.append(',');
            hist.append("{id:").append(i + 1)
                .append(",role:'").append(i % 2 == 0 ? "user" : "assistant")
                .append("',content:'message-").append(i).append(" lorem ipsum dolor sit amet consectetur'}");
        }
        hist.append("]);");
        final String historyJs = hist.toString();
        Platform.runLater(() -> view.executeScript(historyJs));
        Thread.sleep(2000);
        
        logExpr("GIM_ST0", "document.scrollingElement.scrollTop + ' cw=' + document.documentElement.clientWidth"
                + " + ' ch=' + document.documentElement.clientHeight"
                + " + ' sh=' + document.scrollingElement.scrollHeight");
        Thread.sleep(500);

        // ---------- 阶段 1：DRAGGED 携带按钮按下位（正常语义，主路径） ----------
        // x=995：chat 页自定义滚动条 10px，clientWidth 应为 990，995 落在滚动条中部
        System.out.println("PROBE stage1 start");
        fire(view, MouseEvent.MOUSE_PRESSED, 995, 20, MouseButton.PRIMARY, 1, true);
        Thread.sleep(120);
        for (int y = 70; y <= 490; y += 40) {
            fire(view, MouseEvent.MOUSE_DRAGGED, 995, y, MouseButton.NONE, 1, true);
            Thread.sleep(50);
        }
        logExpr("GIM_ST1", "document.scrollingElement.scrollTop");
        Thread.sleep(500);
        fire(view, MouseEvent.MOUSE_RELEASED, 995, 490, MouseButton.PRIMARY, 1, false);
        Thread.sleep(400);

        // ---------- 阶段 2：DRAGGED 不回填按钮状态（兜底路径） ----------
        System.out.println("PROBE stage2 start");
        Platform.runLater(() -> view.executeScript("window.scrollTo(0,0)"));
        Thread.sleep(500);
        logExpr("GIM_ST2", "document.scrollingElement.scrollTop");
        Thread.sleep(300);
        fire(view, MouseEvent.MOUSE_PRESSED, 995, 20, MouseButton.PRIMARY, 1, true);
        Thread.sleep(120);
        for (int y = 70; y <= 490; y += 40) {
            fire(view, MouseEvent.MOUSE_DRAGGED, 995, y, MouseButton.NONE, 1, false);
            Thread.sleep(50);
        }
        logExpr("GIM_ST3", "document.scrollingElement.scrollTop");
        Thread.sleep(500);
        fire(view, MouseEvent.MOUSE_RELEASED, 995, 490, MouseButton.PRIMARY, 1, false);
        Thread.sleep(400);

        // ---------- 阶段 3：模拟用户设备的真实异常（只发 MOUSE_MOVED、按钮字段全丢） ----------
        // 用户实测日志：按住拖动 160px 期间零 MOUSE_DRAGGED 事件，只有普通移动；
        // 本阶段验证 canvas 的 setOnMouseMoved 升级逻辑（pressedButton 兜底 → 合成 DRAGGED）。
        System.out.println("PROBE stage3 start");
        Platform.runLater(() -> view.executeScript("window.scrollTo(0,0)"));
        Thread.sleep(500);
        logExpr("GIM_ST4", "document.scrollingElement.scrollTop");
        Thread.sleep(300);
        fire(view, MouseEvent.MOUSE_PRESSED, 995, 20, MouseButton.PRIMARY, 1, true);
        Thread.sleep(120);
        for (int y = 70; y <= 490; y += 40) {
            // 关键：类型为 MOUSE_MOVED 且 isPrimaryButtonDown=false——完全模拟异常设备上报
            fire(view, MouseEvent.MOUSE_MOVED, 995, y, MouseButton.NONE, 1, false);
            Thread.sleep(50);
        }
        logExpr("GIM_ST5", "document.scrollingElement.scrollTop");
        Thread.sleep(500);
        fire(view, MouseEvent.MOUSE_RELEASED, 995, 490, MouseButton.PRIMARY, 1, false);
        Thread.sleep(400);

        System.out.println("PROBE DONE");
        Platform.exit();
        System.exit(0);
    }

    /** 经 canvas 直接投递 JavaFX 鼠标事件（坐标即画布本地坐标，与真实事件语义一致）。 */
    private static void fire(JcefOsrView view, javafx.event.EventType<MouseEvent> type,
                             double x, double y, MouseButton button, int clickCount,
                             boolean primaryDown) throws Exception {
        CountDownLatch d = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                MouseEvent me = new MouseEvent(type, x, y, x + 2000, y + 2000, button, clickCount,
                        false, false, false, false, primaryDown, false, false,
                        false, false, true, null);
                Event.fireEvent(view.canvas(), me);
            } finally {
                d.countDown();
            }
        });
        d.await(2, TimeUnit.SECONDS);
    }

    /** 以 console.log('LABEL=' + 表达式) 上报数值；日志经 JcefOsrView 的 DisplayHandler 落盘。 */
    private static void logExpr(String label, String jsExpr) throws Exception {
        JcefOsrView v = VIEW.get();
        CountDownLatch d = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                v.executeScript("console.log('" + label + "=' + (" + jsExpr + "))");
            } finally {
                d.countDown();
            }
        });
        d.await(2, TimeUnit.SECONDS);
    }
}
