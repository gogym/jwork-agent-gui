import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * One-shot JCEF OSR scrollbar-drag probe (throwaway diagnostic tool).
 *
 * Loads a tall data-URL page, then drags the scrollbar thumb twice:
 *   phase 1: MOUSE_DRAGGED WITHOUT button modifier  -> pre-fix behaviour (expect no scroll)
 *   phase 2: MOUSE_DRAGGED WITH BUTTON1_DOWN_MASK   -> fixed behaviour  (expect scroll)
 * scrollTop is sampled through console.log captured by CefDisplayHandler.
 * Source kept ASCII-only on purpose.
 */
public final class DragProbe {

    static CefBrowser browser;
    static Component ui;

    public static void main(String[] args) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new java.io.File(System.getProperty("user.home"), ".jwork/cef"));
        CefSettings st = builder.getCefSettings();
        st.windowless_rendering_enabled = true;
        st.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        st.root_cache_path = "D:/GitHub/gim-agent-gui/scripts/_ft/probe/user-data";
        st.cache_path = "D:/GitHub/gim-agent-gui/scripts/_ft/probe/user-data/cache";
        CefApp app = builder.build();
        CefClient client = app.createClient();
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level,
                                            String message, String source, int line) {
                System.out.println("CONSOLE " + message);
                return false;
            }
        });
        browser = client.createBrowser("about:blank", true, false);
        ui = browser.getUIComponent();
        ui.setSize(new Dimension(1, 1));

        EventQueue.invokeAndWait(() -> {
            Frame f = new Frame("drag-probe");
            f.setUndecorated(true);
            f.setLayout(new BorderLayout());
            f.add(ui, BorderLayout.CENTER);
            f.setSize(1000, 620);
            f.setLocation(-32000, -32000);
            f.setVisible(true);
            browser.setWindowVisibility(true);
            browser.setFocus(true);
            ui.setSize(new Dimension(1000, 620));
        });
        Thread.sleep(3500);

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            rows.append("<div style='height:600px;border-bottom:2px solid #ccc;font:20px sans-serif'>BLOCK ")
                .append(i).append("</div>");
        }
        String page = "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body style='margin:0'>"
                + rows + "</body></html>";
        browser.loadURL(dataUrl(page));
        Thread.sleep(2500);

        eval("console.log('GEOM cw=' + document.documentElement.clientWidth"
                + " + ' ch=' + document.documentElement.clientHeight"
                + " + ' sh=' + document.scrollingElement.scrollHeight"
                + " + ' st=' + document.scrollingElement.scrollTop);");

        // --- Phase 1: drag without button modifier (pre-fix behaviour) ----------
        eval("console.log('P1_START st=' + document.scrollingElement.scrollTop);");
        press(993, 20);
        Thread.sleep(200);
        eval("console.log('P1_AFTER_PRESS st=' + document.scrollingElement.scrollTop);");
        for (int y = 60; y <= 500; y += 40) {
            drag(993, y, false);
            Thread.sleep(80);
        }
        eval("console.log('P1_AFTER_DRAG st=' + document.scrollingElement.scrollTop);");
        release(993, 500);
        Thread.sleep(300);

        // --- Phase 2: drag WITH BUTTON1_DOWN_MASK (fixed behaviour) -------------
        eval("console.log('P2_START st=' + document.scrollingElement.scrollTop);");
        press(993, 20);
        Thread.sleep(200);
        for (int y = 60; y <= 500; y += 40) {
            drag(993, y, true);
            Thread.sleep(80);
        }
        eval("console.log('P2_AFTER_DRAG st=' + document.scrollingElement.scrollTop);");
        release(993, 500);
        Thread.sleep(300);

        eval("console.log('DONE st=' + document.scrollingElement.scrollTop);");
        Thread.sleep(500);
        System.out.println("DRAG_PROBE_DONE");
        System.exit(0);
    }

    static void eval(String js) {
        browser.executeJavaScript(js, "about:blank", 0);
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
        }
    }

    static void press(int x, int y) throws Exception {
        EventQueue.invokeAndWait(() -> ui.dispatchEvent(new MouseEvent(ui, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1)));
    }

    static void drag(int x, int y, boolean withMask) throws Exception {
        final int mods = withMask ? InputEvent.BUTTON1_DOWN_MASK : 0;
        EventQueue.invokeAndWait(() -> ui.dispatchEvent(new MouseEvent(ui, MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), mods, x, y, 0, false, MouseEvent.NOBUTTON)));
    }

    static void release(int x, int y) throws Exception {
        EventQueue.invokeAndWait(() -> ui.dispatchEvent(new MouseEvent(ui, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1)));
    }

    static String dataUrl(String html) throws Exception {
        return "data:text/html;charset=utf-8,"
                + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
