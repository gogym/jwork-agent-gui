import com.gettyio.gim.agent.ui.jcef.JcefOsrView;
import com.gettyio.gim.agent.ui.jcef.JcefResources;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;

import javax.imageio.ImageIO;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot JCEF OSR emoji render probe (throwaway diagnostic tool).
 *
 * Stage A: data-URL page, default Chromium font chain, emoji + basic text.
 * Stage B: real chat shell page (JcefResources.chatShell) + injected DOM using the
 *          same font-family chain as chat.css (Microsoft YaHei UI / Segoe UI / ...).
 *
 * Every onPaint frame overwrites <outDir>/<stage>.png so the last frame per stage
 * reflects the settled rendering. Source kept ASCII-only on purpose.
 */
public final class EmojiProbe {

    static final String OUT = "D:/GitHub/gim-agent-gui/scripts/_ft/probe/";
    static final AtomicInteger FRAMES = new AtomicInteger();
    static volatile String stage = "boot";

    public static void main(String[] args) throws Exception {
        new File(OUT).mkdirs();

        // Dedicated CEF instance: the main app may be running concurrently and CEF
        // locks root_cache_path per instance (exit code 21 on conflict).
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new File(System.getProperty("user.home"), ".jwork/cef"));
        CefSettings st = builder.getCefSettings();
        st.windowless_rendering_enabled = true;
        st.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        st.root_cache_path = OUT + "user-data";
        st.cache_path = OUT + "user-data/cache";
        CefApp app = builder.build();
        CefClient client = app.createClient();
        CefBrowser browser = client.createBrowser("about:blank", true, false);
        browser.setWindowlessFrameRate(10);

        Component ui = browser.getUIComponent();
        System.out.println("BROWSER=" + browser.getClass().getName() + " UI=" + ui.getClass().getName());
        client.addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser b, org.cef.browser.CefFrame f,
                                    org.cef.network.CefRequest.TransitionType t) {
                if (f != null && f.isMain()) System.out.println("LOAD_START " + f.getURL());
            }

            @Override
            public void onLoadEnd(CefBrowser b, org.cef.browser.CefFrame f, int code) {
                if (f != null && f.isMain()) System.out.println("LOAD_END " + f.getURL() + " http=" + code);
            }
        });
        org.cef.handler.CefRenderHandler rh = browser.getRenderHandler();
        System.out.println("RENDER_HANDLER=" + (rh == null ? "null" : rh.getClass().getName()));
        rh.setOnPaintListener(evt -> {
            ByteBuffer bf = evt.getRenderedFrame();
            int w = evt.getWidth(), h = evt.getHeight();
            if (bf == null || w <= 0 || h <= 0) return;
            FRAMES.incrementAndGet();
            try {
                byte[] arr = new byte[w * h * 4];
                bf.rewind();
                bf.get(arr, 0, Math.min(arr.length, bf.remaining()));
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] px = new int[w * h];
                for (int i = 0; i < px.length; i++) {
                    int b = arr[i * 4] & 0xFF, g = arr[i * 4 + 1] & 0xFF, r = arr[i * 4 + 2] & 0xFF, a = arr[i * 4 + 3] & 0xFF;
                    px[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                img.setRGB(0, 0, w, h, px, 0, w);
                ImageIO.write(img, "png", new File(OUT + stage + ".png"));
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });

        ui.setSize(new Dimension(1, 1));
        EventQueue.invokeAndWait(() -> {
            Frame f = new Frame("emoji-probe");
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

        Thread.sleep(3500); // wait for native browser creation (GLCanvas first paint)

        // --- Stage A: data page + raw injection (control: known garbled) --------
        stage = "A";
        String a = "<!DOCTYPE html><html><head><meta charset='utf-8'></head>"
                + "<body style='margin:0;background:#ffffff'><div id='list'></div></body></html>";
        browser.loadURL(dataUrl(a));
        Thread.sleep(1500);
        browser.executeJavaScript(matrixJs(), "about:blank", 0);
        Thread.sleep(2500);

        // --- Stage B: data page + asciiEscape injection (expect colored) --------
        stage = "B";
        browser.loadURL(dataUrl(a));
        Thread.sleep(1500);
        browser.executeJavaScript(JcefOsrView.asciiEscape(matrixJs()), "about:blank", 0);
        Thread.sleep(2500);

        // --- Stage C: real chat shell + ChatAPI.renderHistory (escaped) ---------
        // Mirrors the production path: ChatWebView.renderHistory -> executeScript
        // -> asciiEscape -> executeJavaScript. Message payload matches the user's
        // real report: "ni shi \uD83D\uDC4C\uD83D\uDE01\uD83D\uDE12".
        stage = "C";
        String url = JcefResources.chatShell("gimQuery");
        browser.loadURL(url);
        Thread.sleep(4500);
        String history = "ChatAPI.renderHistory(["
                + "{id:1,role:'user',content:'\u4f60\u662f\uD83D\uDC4C\uD83D\uDE01\uD83D\uDE12'},"
                + "{id:2,role:'assistant',content:'\u597d\u558f\uD83C\uDF89\uD83D\uDCCB\uD83D\uDD01\u2764\uFE0F\u26A0\uFE0FOK'}"
                + "]);";
        browser.executeJavaScript(JcefOsrView.asciiEscape(history), "about:blank", 0);
        // Re-assert the OSR viewport: AWT layout may shrink it back to the Frame size.
        ui.setSize(new Dimension(1000, 620));
        Thread.sleep(4500);

        System.out.println("PROBE_DONE frames=" + FRAMES.get());
        System.exit(0);
    }

    static String dataUrl(String html) throws Exception {
        return "data:text/html;charset=utf-8,"
                + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Font-family matrix injected identically into stage A (data page) and B (chat shell). */
    static String matrixJs() {
        return "var rows=["
                + "['DEFAULT',''],"
                + "['Segoe UI Emoji',\"'Segoe UI Emoji'\"],"
                + "['Segoe UI Symbol',\"'Segoe UI Symbol'\"],"
                + "['Segoe UI',\"'Segoe UI'\"],"
                + "['Microsoft YaHei UI',\"'Microsoft YaHei UI'\"],"
                + "['sans-serif',\"sans-serif\"],"
                + "['serif',\"serif\"],"
                + "['Arial',\"'Arial'\"],"
                + "['Tahoma',\"'Tahoma'\"],"
                + "['Emoji+fallback',\"'Segoe UI Emoji',sans-serif\"]];"
                + "var E='\uD83D\uDC4C\uD83D\uDE01\uD83C\uDF89\uD83D\uDCCB\uD83D\uDD01\u2764\uFE0F\u26A0\uFE0F';"
                + "var h='';for(var i=0;i<rows.length;i++){var ff=rows[i][1]?('font-family:'+rows[i][1]+';'):'';"
                + "h+='<div style=\"font-size:22px;padding:2px 16px;'+ff+'\">'+rows[i][0]+': '+E+'</div>';}"
                + "document.getElementById('list').innerHTML=h;";
    }
}
