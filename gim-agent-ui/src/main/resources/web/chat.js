/* ============================================================
 * Jwork 聊天区 WebView DOM 控制器。
 * Java 侧经 engine.executeScript("ChatAPI.xxx(...)") 调用；JS→Java 经 window.javaBridge。
 * Markdown→HTML 用 marked.parse + DOMPurify.sanitize；代码块本地样式化（无 token 高亮）。
 * 流式：streamStart 建空气泡 → streamDelta/streamThinking 明文增量（避免半截栅栏闪烁）→
 * streamEnd 以 Markdown 定格；收尾后 Java 重放整页（renderHistory）拿真实主键与操作。
 * ============================================================ */
(function () {
    'use strict';

    // 内联 SVG 图标（Material Symbols 路径，fill=currentColor；避免依赖 icon 字体）
    var COPY_SVG = '<svg viewBox="0 0 24 24"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>';
    var SPEAK_SVG = '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>';
    var REFRESH_SVG = '<svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>';
    var EDIT_SVG = '<svg viewBox="0 0 24 24"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 0 0 0-1.41l-2.34-2.34a.996.996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>';
    var CHECK_SVG = '<svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>';
    var ERR_SVG = '<svg viewBox="0 0 24 24"><path d="M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm5 13.59L15.59 17 12 13.41 8.41 17 7 15.59 10.59 12 7 8.41 8.41 7 12 10.59 15.59 7 17 8.41 13.41 12 17 15.59z"/></svg>';
    var CHEV_SVG = '<svg viewBox="0 0 24 24"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg>';
    var SPINNER_SVG = '<svg viewBox="0 0 24 24"><path d="M12 4V2A10 10 0 0 0 2 12h2a8 8 0 0 1 8-8z"/></svg>';
    var EYE_SVG = '<svg viewBox="0 0 24 24"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-8a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/></svg>';

    var LABELS = {};              // Java 经 setTheme 推入的本地化文案
    var PREVIEW_LANGS = {};       // Java 经 setTheme 推入的可预览类型集（空=尚未推送，预览钮不出现）
    var flags = { lastAssistantId: 0, lastUserId: 0 };
    var followBottom = true;      // 是否自动跟随到底部
    var wasAtBottom = true;       // 最近一次滚动事件时是否已贴底（视口尺寸变化时据此补滚回底）
    var bottomSnapTimer = null;   // 整页重放后的贴底兜底定时器（待图片/布局结算后再齐一次底）
    var streaming = {};           // id -> {raw, thinking, node, ...}
    var TRIM_THRESHOLD = 800;     // DOM 消息行超此数且贴底时触发顶部回收（长会话控内存）
    var TRIM_KEEP = 400;          // 回收后保留的最新消息行数
    var IDLE_MS = 1000;           // 轮内心跳：超此时长无新事件且无在途行自报动作，就补「思考中」占位
    var SECOND_MS = 1000;         // 每秒毫秒数：读秒文案与两处读秒定时器共用
    var WHOLE_SECOND_MS = 10000;  // 读秒精度阈值：低于它保留一位小数，达到后整秒四舍五入

    /** 「思考中」文案：本地化未推送时用英文兜底（占位行/在途思考行/块头同一口径）。 */
    function thinkingLabel() {
        return LABELS.thinking || 'Thinking…';
    }

    /** 「已思考」文案：本地化未推送时用英文兜底。 */
    function thoughtLabel() {
        return LABELS.thinkingDone || 'Thought';
    }

    function esc(s) {
        return s == null ? '' : String(s);
    }

    // 显示前清洗：JCEF(Chromium) 原生正确渲染 emoji 连字（ZWJ/变体选择符 FE0F/键帽/旗帜 Tag 字符），
    // 无需再像旧 WebView 那样剥离这些组合符（剥离反而破坏彩色 emoji）。故此处保留原文，仅去除零宽空格
    // U+200B 与 BOM U+FEFF 这两个绝不属于任何 emoji 序列、且可能干扰排版的不可见字符。
    // 仅影响显示，不改写存储原文（复制/重发仍取原 content）。
    function norm(s) {
        return s == null ? '' : String(s).replace(/[\u200B\uFEFF]/g, '');
    }

    function el(tag, cls, text) {
        var n = document.createElement(tag);
        if (cls) { n.className = cls; }
        if (text != null) { n.textContent = text; }
        return n;
    }

    function bridge() {
        return window.javaBridge || null;
    }

    // 安全消毒：marked 解析 → DOMPurify 过滤
    // ALLOWED_URI_REGEXP 在默认放行表上只多补一个 file:——产物链接（交付文件的绝对路径）此前会被
    // 整条抹掉 href，点击静默无响应。路径是否真在本工作区内由 Java 侧 chatLink 再判一次，越界拒绝。
    var ALLOWED_URI = /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|matrix|file):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;

    // 表单/交互标签一律剔除：本页面拥有 Java 桥（window.javaBridge），模型产出的 Markdown 属于
    // 不可信输入。默认放行表允许 <form>＋<input>＋<button>，一句 <form action=...> 就能把气泡变成
    // 可点击的自动提交表单（配合 target=_blank 即桥外带数据）；<style> 可做点击劫持式遮挡，
    // <template>/<slot>/<shadow> 则能把已消毒的节点重新接进未消毒的子树（mXSS 回环）。
    var FORBID_TAGS = ['form', 'input', 'button', 'textarea', 'select', 'option', 'label',
        'style', 'template', 'slot', 'shadow', 'iframe', 'frame', 'frameset', 'object', 'embed',
        'marquee', 'base', 'meta', 'link', 'noscript', 'math', 'annotation-xml', 'annotation',
        'description', 'title'];

    function mdToHtml(raw) {
        var text = norm(raw);
        try {
            var html = window.marked.parse(text,
                { breaks: true, gfm: true });
            return sanitize(html);
        } catch (e) {
            return sanitize('<pre>' + esc(text) + '</pre>');
        }
    }

    // 统一消毒出口：正文解析失败时退化为纯文本 <pre>，两条路径都必须过同一套限制。
    function sanitize(html) {
        return window.DOMPurify.sanitize(html, {
            ADD_ATTR: ['target'],
            ALLOWED_URI_REGEXP: ALLOWED_URI,
            FORBID_TAGS: FORBID_TAGS,
            // on* 内联处理器与 style 属性在 HTML 注入链上是常规跳板（onerror=...、
            // style="background:url(javascript:...)"），本页样式全部由 Java 侧注入的 CSS 负责，无需行内。
            FORBID_ATTR: ['style', 'srcdoc', 'formaction', 'formenctype', 'formmethod',
                'formtarget', 'formnovalidate', 'sandbox', 'http-equiv', 'integrity', 'nonce']
        });
    }

    function mdToElem(raw) {
        var box = el('div', 'md');
        box.innerHTML = mdToHtml(raw);
        decorateCode(box);
        box.addEventListener('click', onContentClick, false);
        return box;
    }

    // 链接/图片点击委托：拦截跳转，回调 Java（Desktop.browse / 图片查看）
    function onContentClick(ev) {
        var a = ev.target.closest ? ev.target.closest('a') : null;
        if (a && a.getAttribute('href')) {
            ev.preventDefault();
            if (bridge()) { bridge().onLink(a.getAttribute('href')); }
            return;
        }
        var img = ev.target;
        if (img && img.tagName === 'IMG') {
            var p = img.getAttribute('data-path') || img.getAttribute('src');
            if (p && bridge()) { bridge().onImage(p); }
        }
    }

    // ---------- Mermaid 图表（与产物预览共用同一份 mermaid.min.js） ----------
    // 库 2.7MB，不内联进 index.html：首次真出现 ```mermaid 才从同目录（同源，不受 file://
    // 跨目录限制）按需装载。装载中/装载失败/语法错误一律保持代码块原样——图源码本身可读，
    // 功能零退化，也与聊天页启动成本解耦。
    var mermaidStage = 0;            // 0 未开始 / 1 装载中 / 2 可用 / -1 不可用
    var mermaidSeq = 0;
    var mermaidWaits = [];           // 装载期间排队的代码块

    function mermaidTheme() {
        return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'default';
    }

    function loadMermaid() {
        if (mermaidStage !== 0) { return; }
        mermaidStage = 1;
        var s = document.createElement('script');
        s.src = 'mermaid.min.js';
        s.onload = function () {
            try {
                mermaidInit();
                mermaidStage = 2;
            } catch (e) {
                mermaidStage = -1;
            }
            var waits = mermaidWaits;
            mermaidWaits = [];
            for (var i = 0; i < waits.length; i++) { drawMermaid(waits[i]); }
        };
        s.onerror = function () { mermaidStage = -1; };
        document.head.appendChild(s);
    }

    function mermaidInit() {
        // securityLevel 必须 strict：图表源码是模型输出，宽松模式会把 HTML 直接注进页面
        window.mermaid.initialize({ startOnLoad: false, theme: mermaidTheme(), securityLevel: 'strict' });
    }

    /** 把一个 mermaid 代码块升级成图：成功则在代码体位置上图、隐源码，失败保持代码块。 */
    function drawMermaid(wrap) {
        if (mermaidStage === 0) {
            loadMermaid();
            mermaidWaits.push(wrap);
            return;
        }
        if (mermaidStage !== 2) { return; }
        var p = null;
        try { p = window.mermaid.render('gim-mmd-' + (++mermaidSeq), wrap._mmd); } catch (e) { return; }
        if (!p || typeof p.then !== 'function') { return; }
        p.then(function (out) { showMermaid(wrap, out && out.svg); }, function () {});
    }

    function showMermaid(wrap, svg) {
        var old = wrap.querySelector('.mmd-live');
        if (old && old.parentNode) { old.parentNode.removeChild(old); }
        if (!svg) { return; }
        var box = el('div', 'mmd-live');
        box.innerHTML = svg;
        var pre = wrap.querySelector('pre');
        wrap.insertBefore(box, pre);
        if (pre) { pre.style.display = 'none'; }
    }

    /** 换明暗主题：主题只在 initialize 时生效一次，已出图的块按新主题重画。 */
    function restyleMermaid() {
        if (mermaidStage !== 2) { return; }
        try { mermaidInit(); } catch (e) { return; }
        var wraps = list().querySelectorAll('.code-block.is-mermaid');
        for (var i = 0; i < wraps.length; i++) { drawMermaid(wraps[i]); }
    }

    // 代码块：包一层头部（语言名 + 复制 + 可选预览）
    function decorateCode(root) {
        var pres = root.querySelectorAll('pre');
        for (var i = 0; i < pres.length; i++) {
            var pre = pres[i];
            var code = pre.querySelector('code');
            if (!code) { continue; }
            var lang = '';
            var m = (code.className || '').match(/language-([\w-]+)/);
            if (m) { lang = m[1]; }
            var wrap = el('div', 'code-block');
            if (lang === 'mermaid') { wrap.className += ' is-mermaid'; }
            pre.parentNode.insertBefore(wrap, pre);
            var head = el('div', 'code-head');
            head.appendChild(el('span', 'code-lang', lang || 'code'));
            var text = code.textContent;
            head.appendChild(iconBtn(COPY_SVG, LABELS.copy || 'Copy', function (t) {
                return function () { if (bridge()) { bridge().onCopyCode(t); } };
            }(text)));
            if (isPreviewable(lang)) {
                head.appendChild(iconBtn(EYE_SVG, LABELS.preview || 'Preview', function (c, l) {
                    return function () { if (bridge()) { bridge().onPreviewCode(c, l); } };
                }(text, lang)));
            }
            wrap.appendChild(head);
            wrap.appendChild(pre);
            if (lang === 'mermaid') {
                wrap._mmd = text;
                drawMermaid(wrap);
            }
        }
    }

    function isPreviewable(lang) {
        return PREVIEW_LANGS[String(lang || '').toLowerCase()] === 1;
    }

    /** 可预览类型清单由 Java 下发（LABELS.previewLangs）：前端不自带一份，免得两端清单各自漂移。 */
    function previewLangs(list) {
        var m = {};
        if (list) {
            for (var i = 0; i < list.length; i++) {
                m[String(list[i]).toLowerCase()] = 1;
            }
        }
        return m;
    }

    function iconBtn(svg, title, onclick) {
        var b = el('button', 'code-btn');
        b.type = 'button';
        b.title = title;
        b.innerHTML = svg;
        b.addEventListener('click', onclick, false);
        return b;
    }

    // ---------- 消息节点构建 ----------
    // 文件类型→徽标底色/字母（对齐参考图：白页+彩色圆角徽标+白字，xlsx 绿 X）
    var FILE_TYPE_META = {
        xlsx: { color: '#1e7145', letter: 'X' },
        xls: { color: '#1e7145', letter: 'X' },
        csv: { color: '#1e7145', letter: 'X' },
        docx: { color: '#2b579a', letter: 'W' },
        doc: { color: '#2b579a', letter: 'W' },
        pdf: { color: '#c94a3f', letter: 'P' }
    };

    function fileExt(name) {
        var i = String(name).lastIndexOf('.');
        return i >= 0 ? String(name).substring(i + 1).toLowerCase() : '';
    }

    function fileTypeIcon(name) {
        var ext = fileExt(name);
        var meta = FILE_TYPE_META[ext]
                || { color: '#8a8a83', letter: ext ? ext.charAt(0).toUpperCase() : 'F' };
        return '<svg viewBox="0 0 24 24">'
            + '<path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6z" '
            + 'fill="var(--gim-content)" stroke="var(--gim-border-2)" stroke-width="1"/>'
            + '<path d="M14 2v6h6" fill="none" stroke="var(--gim-border-2)" stroke-width="1"/>'
            + '<rect x="3" y="12" width="12" height="10" rx="2" fill="' + meta.color + '"/>'
            + '<text x="9" y="19.6" text-anchor="middle" font-size="8" font-weight="700" '
            + 'fill="#fff" font-family="Segoe UI, sans-serif">' + meta.letter + '</text>'
            + '</svg>';
    }

    function fileRow(f) {
        var row = el('div', 'file-row');
        row.appendChild(iconEl(fileTypeIcon(f.name), 'file-ic'));
        var text = el('div', 'file-text');
        text.appendChild(el('div', 'file-name', esc(f.name)));
        text.appendChild(el('div', 'file-type', esc(fileTypeLabel(f.name))));
        row.appendChild(text);
        row.title = f.name + (f.size ? ' · ' + f.size : '');
        if (f.path && bridge()) {
            row.addEventListener('click', function () { bridge().onImage(f.path); }, false);
        }
        return row;
    }

    function fileTypeLabel(name) {
        var ext = fileExt(name);
        return ext ? ext.toUpperCase() : 'FILE';
    }

    function iconEl(svg, cls) {
        var s = el('span', cls);
        s.innerHTML = svg;
        return s;
    }

    // 产物卡：助手消息里本轮经 deliver_artifact 交到本机的文件（名称/类型/大小 + 预览·打开·所在文件夹）。
    // 与用户附件行区分：附件是「用户给模型的」，产物是「模型给用户的」，动作也不同，故单独一套 DOM。
    function artifactCards(files) {
        var box = el('div', 'artifact-cards');
        box.appendChild(el('div', 'artifact-caption', LABELS.artifactTitle || 'Artifacts'));
        for (var i = 0; i < files.length; i++) {
            box.appendChild(artifactCard(files[i]));
        }
        return box;
    }

    function artifactCard(f) {
        var card = el('div', 'artifact-card');
        card.appendChild(iconEl(fileTypeIcon(f.name), 'artifact-ic'));
        var text = el('div', 'artifact-text');
        text.appendChild(el('div', 'artifact-name', esc(f.name)));
        var meta = [fileTypeLabel(f.name), f.size];
        text.appendChild(el('div', 'artifact-meta', meta.filter(Boolean).join(' · ')));
        card.appendChild(text);
        var acts = el('div', 'artifact-acts');
        acts.appendChild(artifactBtn(LABELS.artifactPreview || 'Preview', 'preview', f.path));
        acts.appendChild(artifactMore(f));
        card.appendChild(acts);
        return card;
    }

    /** 「⋯」浮层：系统程序打开与定位文件夹不常按，收进二级菜单，卡片面只留主操作。 */
    function artifactMore(f) {
        var more = el('div', 'artifact-more');
        var btn = el('button', 'artifact-act dots');
        btn.type = 'button';
        btn.textContent = '⋯';
        btn.setAttribute('aria-label', LABELS.artifactMore || 'More');
        btn.addEventListener('click', function (ev) {
            ev.stopPropagation();
            more.classList.toggle('open');
        }, false);
        var menu = el('div', 'artifact-menu');
        menu.appendChild(menuItem(LABELS.artifactOpen || 'Open', 'open', f.path));
        menu.appendChild(menuItem(LABELS.artifactReveal || 'Show in Folder', 'reveal', f.path));
        more.appendChild(btn);
        more.appendChild(menu);
        return more;
    }

    function menuItem(label, action, path) {
        var b = el('button', 'artifact-menu-item');
        b.type = 'button';
        b.textContent = label;
        b.addEventListener('click', function (ev) {
            ev.stopPropagation();
            closeMenus();
            if (path && bridge()) { bridge().onArtifact(path, action); }
        }, false);
        return b;
    }

    // 浮层只可能同时开一个之外的多个：点别处就全部收起（JCEF 里没有 :focus-within 之外的托管弹窗层）
    document.addEventListener('click', closeMenus, false);

    function closeMenus() {
        var open = document.querySelectorAll('.artifact-more.open');
        for (var i = 0; i < open.length; i++) { open[i].classList.remove('open'); }
    }

    function artifactBtn(label, action, path) {
        var b = el('button', 'artifact-act');
        b.type = 'button';
        b.textContent = label;
        b.addEventListener('click', function (ev) {
            ev.stopPropagation();
            if (path && bridge()) { bridge().onArtifact(path, action); }
        }, false);
        return b;
    }

    function imageThumbs(images) {
        if (!images || !images.length) { return null; }
        var box = el('div', 'bubble-images');
        for (var i = 0; i < images.length; i++) {
            var im = el('img');
            im.src = images[i].dataUri || '';
            if (images[i].path) { im.setAttribute('data-path', images[i].path); }
            (function (p) {
                im.addEventListener('click', function () { if (bridge()) { bridge().onImage(p); } }, false);
            })(images[i].path || im.src);
            box.appendChild(im);
        }
        return box;
    }

    // ---------- 轮次布局：流式与历史重放共用 ----------
    // 一轮助手消息 = 若干「过程块」（相邻的思考/工具/旁白合成一块，默认收起成一行标题）
    // 与若干「正文块」（Markdown 裸排，不带边框），外加清单卡与产物卡。
    // 段序即真相：同样的 items 必然产出同样的 DOM，收尾整页重放才不会让已上屏的一轮重新排版。

    /** 毫秒 → 本地化秒数文案（不足 1 秒返回空串，标题里不出现）。 */
    function seconds(ms) {
        if (!ms || ms < SECOND_MS) { return ''; }
        var v = ms >= WHOLE_SECOND_MS ? Math.round(ms / SECOND_MS) : Math.round(ms / (SECOND_MS / 10)) / 10;
        return (LABELS.thinkingElapsed || '{0}s').replace('{0}', v);
    }

    /** 过程块标题：有工具报步数，纯思考报「已思考 · Ns」；收起且在途时改报当前动作。 */
    function processTitle(tools, failed, thinkMs, running, liveMs) {
        if (running) {
            // 思考在途时块头也带读秒：与行尾同口径，收起态不丢时长
            var ts = running === thinkingLabel() ? seconds(liveMs) : '';
            return ts ? running + ' · ' + ts : running;
        }
        if (tools > 0) {
            return failed > 0
                ? (LABELS.toolFailed || 'Executed {0} tool call(s), {1} failed')
                    .replace('{0}', tools).replace('{1}', failed)
                : (LABELS.toolCount || 'Executed {0} tool call(s)').replace('{0}', tools);
        }
        var t = thoughtLabel();
        var el2 = seconds(thinkMs);
        return el2 ? t + ' · ' + el2 : t;
    }

    /** 过程块外壳：折叠头 + 分行容器。标题随内容重算，流式逐步追加与整块重绘共用同一外壳。 */
    function processShell() {
        var box = el('div', 'process');
        var head = el('div', 'process-head');
        head.appendChild(iconEl(CHEV_SVG, 'process-chev'));
        var title = el('span', 'process-title', '');
        head.appendChild(title);
        var body = el('div', 'process-body');
        box.appendChild(head);
        box.appendChild(body);
        var p = { box: box, body: body, title: title, items: [], manual: false };
        // 用户手动开合过就不再自动折：收尾只收系统开的折叠态；开合后块头文案职责随之切换
        head.addEventListener('click', function () {
            p.manual = true;
            box.classList.toggle('open');
            refreshProcess(p);
        }, false);
        return p;
    }

    /** 重算块头：展开时在途行自带脉冲自报当前动作，块头只报统计免与行文案重复；
     *  收起后块头是唯一可见行，改报当前动作。无工具的分组不套折叠头（行自解释）。 */
    function refreshProcess(p) {
        var tools = 0, failed = 0, thinkMs = 0, liveMs = 0, running = '';
        for (var i = 0; i < p.items.length; i++) {
            var it = p.items[i];
            if (it.kind === 'tool') {
                tools++;
                if (it.live) { running = runningTool(it.tool); }
                else if (!it.tool.ok) { failed++; }
            } else if (it.kind === 'think') {
                // 统计只算已定格的思考；在途耗时单独走 running 读秒，免得展开态块头误报「已思考」
                if (it.live) { liveMs += it.ms || 0; running = thinkingLabel(); }
                else if (it.ms) { thinkMs += it.ms; }
            }
        }
        var open = p.box.classList.contains('open');
        p.title.textContent = processTitle(tools, failed, thinkMs, open ? '' : running, liveMs);
        // 纯思考/旁白的分组不套折叠头：行文案已自解释，多一层壳只会把「思考中/已思考」重复一遍
        p.box.classList.toggle('bare', tools === 0);
    }

    /** 追加分行并同步块头——流式与重放唯一的过程区写入口。 */
    function addProcessItem(p, it) {
        it.proc = p;
        p.items.push(it);
        p.body.appendChild(it.kind === 'tool' ? toolLine(it) : (it.kind === 'think' ? thinkLine(it) : noticeLine(it)));
        refreshProcess(p);
    }

    /** items → 整块过程记录（重放路径：一次成型，与流式逐步追加产出同构 DOM）。 */
    function processBlock(items) {
        var p = processShell();
        for (var i = 0; i < items.length; i++) { addProcessItem(p, items[i]); }
        return p.box;
    }

    /** 在途工具的动作摘要（标题用）：委派类显出目标智能体，其余用工具名。 */
    function runningTool(tool) {
        var name = toolName(tool);
        return (LABELS.toolRunning || 'Running {0}…').replace('{0}', name);
    }

    /**
     * 思考详情的呈现方式。
     * <p>在途只铺纯文本：整段 Markdown 重解析的代价随已积累字数线性增长，
     * 每个增量重跑一遍就成了二次方；排版留到定格（或重放）那一次。</p>
     */
    function renderThinkDetail(it) {
        if (!it.detail) { return; }
        it.detail.innerHTML = '';
        it.raw = null;
        if (it.live) {
            it.raw = el('div', 'log-detail-raw');
            it.raw.textContent = it.text || '';
            it.detail.appendChild(it.raw);
            return;
        }
        if (it.text) { it.detail.appendChild(mdToElem(it.text)); }
    }

    /** 思考行：只露「已思考 · Ns」，点开才渲染全文（全文只在首次展开时构建一次）。 */
    function thinkLine(it) {
        var item = el('div', 'log-item');
        var row = el('div', 'log-row');
        var ic = iconEl(it.live ? SPINNER_SVG : CHECK_SVG, 'log-ic' + (it.live ? ' live' : ''));
        row.appendChild(ic);
        var name = el('span', 'log-name', it.live ? thinkingLabel() : thoughtLabel());
        row.appendChild(name);
        var time = el('span', 'log-time', seconds(it.ms));
        row.appendChild(time);
        var detail = el('div', 'log-detail');
        row.addEventListener('click', function () {
            if (!detail.firstChild && it.text) { renderThinkDetail(it); }
            item.classList.toggle('open');
        }, false);
        item.appendChild(row);
        item.appendChild(detail);
        it.refs = { ic: ic, name: name, time: time };
        it.detail = detail;
        return item;
    }

    /** 思考在途→定格的原地改写：换图标、换文案、补耗时，用户已展开的态与滚动位置都不受影响。 */
    function sealThink(it) {
        it.live = false;
        if (it.refs) {
            it.refs.ic.innerHTML = CHECK_SVG;
            it.refs.ic.classList.remove('live');
            it.refs.name.textContent = thoughtLabel();
            it.refs.time.textContent = seconds(it.ms);
        }
        if (it.detail && it.detail.firstChild) {
            renderThinkDetail(it);
        }
    }

    /** 思考增量：明文缓冲；若用户已点开就续上纯文本（未点开则等点开时一次成型）。 */
    function appendThink(it, text) {
        it.text = (it.text || '') + text;
        if (!it.detail || !it.detail.firstChild) { return; }
        if (it.raw) {
            it.raw.textContent = it.text;
            return;
        }
        renderThinkDetail(it);
    }

    /** 工具行详情：入参与输出两段（缺项不占标签）。 */
    function fillToolDetail(detail, r) {
        detail.innerHTML = '';
        if (r.input) {
            detail.appendChild(el('div', 'log-detail-label', LABELS.toolInput || 'Input'));
            detail.appendChild(el('pre', 'log-detail-text', pretty(r.input)));
        }
        if (r.output) {
            detail.appendChild(el('div', 'log-detail-label', LABELS.toolOutput || 'Response'));
            detail.appendChild(el('pre', 'log-detail-text', String(r.output)));
        }
    }

    /** 工具行：状态图标 + 工具名 + 入参摘要（+ 耗时），点开给出入参与输出。 */
    function toolLine(it) {
        var r = it.tool;
        var item = el('div', 'log-item');
        var row = el('div', 'log-row');
        var ic = iconEl(it.live ? SPINNER_SVG : (r.ok ? CHECK_SVG : ERR_SVG),
                'log-ic' + (it.live ? ' live' : (r.ok ? ' ok' : ' err')));
        row.appendChild(ic);
        row.appendChild(el('span', 'log-name', toolName(r)));
        // 入参在 TOOL_START 时已完整，在途行就给出摘要；耗时未知，留空到定格再补
        var summary = el('span', 'log-summary', toolSummary(r) || '');
        row.appendChild(summary);
        var time = el('span', 'log-time', it.live ? '' : seconds(r.durationMs));
        row.appendChild(time);
        var detail = el('div', 'log-detail');
        fillToolDetail(detail, r);
        row.addEventListener('click', function () { item.classList.toggle('open'); }, false);
        item.appendChild(row);
        item.appendChild(detail);
        it.refs = { ic: ic, summary: summary, time: time, detail: detail };
        return item;
    }

    /** 在途工具行就地定格：脉冲停成成败图标、补摘要与耗时、追加输出，展开态不动。 */
    function sealTool(it) {
        it.live = false;
        var r = it.tool, x = it.refs;
        if (!x) { return; }
        x.ic.innerHTML = r.ok ? CHECK_SVG : ERR_SVG;
        x.ic.classList.remove('live');
        x.ic.classList.add(r.ok ? 'ok' : 'err');
        if (!x.summary.textContent) { x.summary.textContent = toolSummary(r) || ''; }
        x.time.textContent = seconds(r.durationMs);
        fillToolDetail(x.detail, r);
    }

    /** 运行体旁白行：不折叠、不可点，只作一行说明。 */
    function noticeLine(it) {
        return el('div', 'notice', it.text || '');
    }

    /** 定格正文块：整段 Markdown 一次渲染。 */
    function proseBlock(text) {
        var box = el('div', 'prose');
        box.appendChild(mdToElem(text || ''));
        return box;
    }

    /** 在途正文块：明文节点逐字追加，避免半截 Markdown 反复重排造成的闪烁。 */
    function liveProseNode(it) {
        var box = el('div', 'prose');
        var node = el('div', 'prose-live');
        node.textContent = norm(it.text || '');
        box.appendChild(node);
        it.refs = { node: node };
        return box;
    }

    /** 在途正文的增量：只改文本节点，不重建 DOM。 */
    function appendProse(it, text) {
        it.text = (it.text || '') + text;
        it.refs.node.textContent = norm(it.text);
    }

    /** 正文定格：明文节点换成 Markdown 渲染结果。 */
    function sealProse(it) {
        if (!it.refs) { return; }
        var box = it.refs.node.parentNode;
        box.innerHTML = '';
        box.appendChild(mdToElem(it.text || ''));
        delete it.refs;
    }

    /** 按 items 重画整轮：过程块与正文块按发生顺序穿插，清单卡在原位替换成卡。 */
    function layoutTurn(turn, items) {
        turn.innerHTML = '';
        var group = null;
        var flush = function () {
            if (group && group.length) { turn.appendChild(processBlock(group)); }
            group = null;
        };
        for (var i = 0; i < items.length; i++) {
            var it = items[i];
            if (it.kind === 'text') {
                flush();
                turn.appendChild(it.live ? liveProseNode(it) : proseBlock(it.text));
            } else if (it.kind === 'todo') {
                flush();
                turn.appendChild(todoCard(it.items));
            } else {
                if (!group) { group = []; }
                group.push(it);
            }
        }
        flush();
    }

    /** 落库分段 → items：与流式累积同构，两条路径进同一个 layoutTurn。 */
    function turnItems(m) {
        var items = [];
        var todoSlot = null;
        var pushTools = function (list) {
            for (var i = 0; i < list.length; i++) {
                var todo = todoItems(list[i]);
                if (todo) {
                    // 整表替换语义：一轮只有一张清单卡，卡在首次调用处、内容取最后一次——与实时流就地刷新一致
                    if (todoSlot) { todoSlot.items = todo; }
                    else {
                        todoSlot = { kind: 'todo', items: todo };
                        items.push(todoSlot);
                    }
                    continue;
                }
                items.push({ kind: 'tool', tool: list[i] });
            }
        };
        if (m.segments && m.segments.length) {
            for (var s = 0; s < m.segments.length; s++) {
                var seg = m.segments[s];
                if (seg.type === 'text' && seg.text) { items.push({ kind: 'text', text: seg.text }); }
                else if (seg.type === 'thinking' && seg.text) { items.push({ kind: 'think', text: seg.text, ms: seg.ms }); }
                else if (seg.type === 'notice' && seg.text) { items.push({ kind: 'notice', text: seg.text }); }
                else if (seg.type === 'tools' && seg.tools) { pushTools(seg.tools); }
            }
            return items;
        }
        if (m.thinking) { items.push({ kind: 'think', text: m.thinking }); }
        if (m.tools && m.tools.length) { pushTools(m.tools); }
        if (m.content) { items.push({ kind: 'text', text: m.content }); }
        return items;
    }

    // ---------- 流式累积：分组边界与 layoutTurn 完全一致 ----------
    // 在途轮只握三个指针：当前过程块 s.proc、在途思考行 s.think、在途正文块 s.prose。
    // 正文与清单是分组边界（封掉过程块），思考结束只封思考行、块留着继续接工具——
    // 于是流式逐段落地的 DOM 就是收尾重放画出来的 DOM，收尾一瞬间不跳版。

    /** 取（或开）在途过程块：流式期间默认展开，跑着的工具与思考逐行上屏，收尾才折回一行统计。 */
    function liveProc(s) {
        if (!s.proc) {
            s.proc = processShell();
            s.proc.box.classList.add('open');
            s.host.appendChild(s.proc.box);
        }
        return s.proc;
    }

    /** 封过程块：块内已无在途行，标题由当前动作换回步数/耗时统计，并把展开的块折回。 */
    function closeProc(s) {
        if (!s.proc) { return; }
        refreshProcess(s.proc);
        if (!s.proc.manual) { s.proc.box.classList.remove('open'); }
        s.proc = null;
    }

    /** 思考到此为止：就地定格这一行（耗时按本地墙钟测得，收尾重放以库中值为准）。 */
    function stopThink(s) {
        var it = s.think;
        if (!it) { return; }
        stopThinkTick(it);
        it.ms = Math.max(0, Date.now() - it.started);
        sealThink(it);
        s.think = null;
        if (it.proc) { refreshProcess(it.proc); }
    }

    /** 思考增量：必要时先开出在途思考行。 */
    function liveThink(s, text) {
        if (!s.think) {
            s.think = { kind: 'think', text: '', live: true, started: Date.now() };
            addProcessItem(liveProc(s), s.think);
            armThinkTick(s.think);
        }
        appendThink(s.think, text);
    }

    /** 思考在途读秒：每秒把耗时写回 it.ms 并刷新行尾与块头，「思考中…」右侧始终有可读时长。 */
    function armThinkTick(it) {
        stopThinkTick(it);
        it.tick = setInterval(function () {
            it.ms = Math.max(0, Date.now() - it.started);
            if (it.refs) { it.refs.time.textContent = seconds(it.ms); }
            if (it.proc) { refreshProcess(it.proc); }
        }, SECOND_MS);
    }

    function stopThinkTick(it) {
        if (it && it.tick) { clearInterval(it.tick); it.tick = null; }
    }

    /** 调用发起即上屏一行在途记录：长耗时工具跑着的时候用户看得到当前动作，而不是整块静默。 */
    function liveToolStart(s, tool) {
        if (tool.name === 'todo_write') { return; }
        stopThink(s);
        var it = { kind: 'tool', tool: tool, live: true };
        addProcessItem(liveProc(s), it);
        s.open.push(it);
    }

    /** 调用结束：按同名最早在途行就地定格（一轮可并发多个同名调用，故 FIFO 配对）；对不上则补一条已完成行。 */
    function liveToolEnd(s, tool) {
        stopThink(s);
        var open = null;
        for (var i = 0; i < s.open.length; i++) {
            if (s.open[i].tool.name === tool.name) { open = s.open[i]; s.open.splice(i, 1); break; }
        }
        if (open) {
            open.tool = tool;
            sealTool(open);
            if (open.proc) { refreshProcess(open.proc); }
        } else {
            addProcessItem(liveProc(s), { kind: 'tool', tool: tool });
        }
    }

    /** 运行体旁白：与思考/工具同处过程区，独立一行，不混进正文。 */
    function liveNotice(s, text) {
        stopThink(s);
        addProcessItem(liveProc(s), { kind: 'notice', text: text });
    }

    /** 正文增量：必要时开出在途正文块（正文是新块，先封掉上面的过程块）。 */
    function liveProse(s, text) {
        stopThink(s);
        closeProc(s);
        if (!s.prose) {
            s.prose = { kind: 'text', text: '', live: true };
            s.host.appendChild(liveProseNode(s.prose));
        }
        appendProse(s.prose, text);
    }

    /** 清单：一轮一张卡，首次出现定位置，后续调用整表刷新同一张卡。 */
    function liveTodo(s, items) {
        stopThink(s);
        closeProc(s);
        if (s.todo) { renderTodo(s.todo, items); }
        else {
            s.todo = todoCard(items);
            s.host.appendChild(s.todo);
        }
    }

    /** 在途标记：本轮还没有任何内容时占位一行，首个事件到达即让位。 */
    function clearPending(s) {
        stopPendingTick(s);
        if (s.pending) {
            s.pending.parentNode.removeChild(s.pending);
            s.pending = null;
        }
    }

    /** 占位行：旋转图标 + 脉动文本 + 右对齐读秒——轮未结束消息区就始终有活着的信号。 */
    function pendingLine() {
        var box = el('div', 'pending');
        box.appendChild(iconEl(SPINNER_SVG, 'pending-ic'));
        box.appendChild(el('span', 'pending-text pulsing', thinkingLabel()));
        box.appendChild(el('span', 'pending-time', ''));
        return box;
    }

    /** 占位读秒：从本条「思考中…」亮起计起每秒报一次耗时；让位即停，下次亮起重新计。 */
    function armPendingTick(s) {
        stopPendingTick(s);
        s.pendingSince = Date.now();
        s.pendingTimer = setInterval(function () {
            if (!s.pending) { return; }
            var t = s.pending.querySelector('.pending-time');
            if (t) { t.textContent = seconds(Date.now() - s.pendingSince); }
        }, SECOND_MS);
    }

    function stopPendingTick(s) {
        if (s && s.pendingTimer) { clearInterval(s.pendingTimer); s.pendingTimer = null; }
    }

    /**
     * 轮内心跳：事件落地后武装定时器，IDLE_MS 内无新事件且没有在途行（思考/工具）
     * 自带脉冲时，在轮尾亮一条「思考中…」占位——正文换段/首 token 延迟期间明文停更，
     * 不补的话整轮看起来像停了。任何新事件到达即让位（clearPending）并重新武装；
     * 只要轮未结束，消息区就始终有活着的信号。
     */
    function armIdle(s) {
        disarmIdle(s);
        s.idleTimer = setTimeout(function () {
            s.idleTimer = null;
            if (s.think || s.open.length > 0 || s.pending) { return; }
            s.pending = pendingLine();
            s.host.appendChild(s.pending);
            armPendingTick(s);
            if (followBottom) { scrollToBottom(false); }
        }, IDLE_MS);
    }

    function disarmIdle(s) {
        if (s && s.idleTimer) { clearTimeout(s.idleTimer); s.idleTimer = null; }
    }

    /** 整页重放/清空前解除所有在途轮的心跳与读秒：定时器不得写进已脱离页面的气泡。 */
    function disarmAllIdle() {
        for (var k in streaming) {
            var s = streaming[k];
            disarmIdle(s);
            stopPendingTick(s);
            stopThinkTick(s.think);
        }
    }

    // ---------- 任务清单卡（todo_write） ----------
    // 框架的 todo_write 是「整表替换」语义：每次调用都带完整清单，最后一次就是当前状态。
    // 因此同一轮里只维持一张卡：实时流就地刷新，历史回放取该条消息里最后一次调用。
    var TODO_STATE = { pending: 1, in_progress: 1, completed: 1 };

    /** 从 todo_write 的入参解析条目；解析不出（非 JSON / 空表）返回 null，调用方退回普通工具行。 */
    function todoItems(tool) {
        if (!tool || tool.name !== 'todo_write' || !tool.input || tool.input.charAt(0) !== '{') {
            return null;
        }
        var o = JSON.parseSafe(tool.input);
        var list = o ? o.todos : null;
        if (!list || typeof list.length !== 'number') { return null; }
        var items = [];
        for (var i = 0; i < list.length; i++) {
            var t = list[i];
            var text = t ? (t.content || t.subject || t.text) : null;
            if (!text || !String(text).trim()) { continue; }
            var state = t && TODO_STATE[t.status] ? t.status : 'pending';
            items.push({ text: String(text).trim(), state: state });
        }
        return items.length ? items : null;
    }

    function todoCard(items) {
        var card = el('div', 'todo-card');
        var head = el('div', 'todo-head');
        head.appendChild(iconEl(CHECK_SVG, 'todo-icon'));
        head.appendChild(el('span', 'todo-title', LABELS.todoTitle || 'Tasks'));
        head.appendChild(el('span', 'todo-progress', ''));
        card.appendChild(head);
        card.appendChild(el('div', 'todo-body'));
        renderTodo(card, items);
        return card;
    }

    /** 重画清单：整表替换后条目会增减，进度数也要跟着变。 */
    function renderTodo(card, items) {
        var body = card.querySelector('.todo-body');
        body.innerHTML = '';
        var done = 0;
        for (var i = 0; i < items.length; i++) {
            if (items[i].state === 'completed') { done++; }
            var row = el('div', 'todo-item ' + items[i].state);
            row.appendChild(el('span', 'todo-mark'));
            row.appendChild(el('span', 'todo-text', items[i].text));
            body.appendChild(row);
        }
        card.querySelector('.todo-progress').textContent =
            (LABELS.todoProgress || '{0}/{1}').replace('{0}', done).replace('{1}', items.length);
    }

    var DELEGATE = { agent_spawn: 1, agent_send: 1 };

    function toolName(r) {
        var name = r.name || 'tool';
        if (DELEGATE[name] && r.input) {
            try {
                var o = JSON.parse(r.input);
                if (o && o.agent_id) {
                    return (LABELS.delegate || 'Delegate to {0}').replace('{0}', String(o.agent_id));
                }
            } catch (e) { /* 非 JSON，忽略 */ }
        }
        return name;
    }

    function toolSummary(r) {
        var s = null;
        if (r.input && r.input.charAt(0) === '{') {
            s = firstString(JSON.parseSafe(r.input));
        }
        if (!s && r.output) { s = String(r.output).split('\n')[0]; }
        if (!s) { return null; }
        s = String(s).replace(/\s+/g, ' ').trim();
        return s.length > 200 ? s.substring(0, 200) + '…' : (s || null);
    }

    // 深度优先取首个非空字符串值
    function firstString(v) {
        if (v && typeof v === 'object') {
            var keys = Object.keys(v);
            for (var i = 0; i < keys.length; i++) {
                var s = firstString(v[keys[i]]);
                if (s) { return s; }
            }
        } else if (typeof v === 'string' && v.trim()) {
            return v;
        }
        return null;
    }

    function pretty(s) {
        try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s; }
    }

    // JSON.parse 安全包装（脏数据不抛）
    if (!window.JSON.parseSafe) {
        JSON.parseSafe = function (s) { try { return JSON.parse(s); } catch (e) { return null; } };
    }

    function actionBar(role, id, raw, canSpeak) {
        var footer = el('div', 'bubble-footer');
        var setRaw = function (b) { b.setAttribute('data-raw', raw || ''); return b; };
        footer.appendChild(setRaw(msgBtn(COPY_SVG, LABELS.copy, 'copy', id)));
        if (role === 'assistant' && canSpeak) { footer.appendChild(setRaw(msgBtn(SPEAK_SVG, LABELS.speak, 'speak', id))); }
        if (role === 'assistant') { footer.appendChild(setRaw(msgBtn(REFRESH_SVG, LABELS.regenerate, 'regenerate', id, 'act-regen'))); }
        if (role === 'user') { footer.appendChild(setRaw(msgBtn(EDIT_SVG, LABELS.editResend, 'edit', id, 'act-edit'))); }
        return footer;
    }

    function msgBtn(svg, title, kind, id, extraCls) {
        var b = el('button', 'msg-action-btn' + (extraCls ? ' ' + extraCls : ''));
        b.type = 'button';
        b.title = title || '';
        b.innerHTML = svg;
        b.setAttribute('data-action', kind);
        b.setAttribute('data-id', id);
        b.setAttribute('data-raw', '');
        b.addEventListener('click', function (ev) {
            ev.stopPropagation();
            if (bridge()) { bridge().onAction(kind, id, b.getAttribute('data-raw') || ''); }
        }, false);
        return b;
    }

    function buildMessage(m) {
        var isUser = m.role === 'user';
        var row = el('div', 'bubble-row ' + (isUser ? 'user' : 'assistant'));
        row.setAttribute('data-id', m.id);
        var hostBox = el('div', 'bubble-host');

        if (isUser) {
            var files = m.files && m.files.length ? (function () {
                var b = el('div', 'bubble-files');
                for (var i = 0; i < m.files.length; i++) { b.appendChild(fileRow(m.files[i])); }
                return b;
            })() : null;
            if (files) { hostBox.appendChild(files); }
            var thumbs = imageThumbs(m.images);
            if (thumbs) { hostBox.appendChild(thumbs); }
            if (m.content) { hostBox.appendChild(el('div', 'bubble-user', norm(m.content))); }
            hostBox.appendChild(actionBar('user', m.id, m.content, false));
        } else {
            // 与流式同一条布局路径：落库分段 → items → layoutTurn，收尾重放画出与当时一致的一轮
            var items = turnItems(m);
            layoutTurn(hostBox, items);
            if (m.files && m.files.length) {
                hostBox.appendChild(artifactCards(m.files));
            }
            var footer = actionBar('assistant', m.id, m.rawContent || m.content, true);
            footer.appendChild(el('span', 'msg-ai-label', LABELS.aiGenerated || ''));
            if (m.time) {
                var t = el('span', 'msg-time', m.time);
                footer.insertBefore(t, footer.querySelector('.msg-ai-label'));
            }
            hostBox.appendChild(footer);
        }
        row.appendChild(hostBox);
        // 留住原始载荷供换语言时就地重建（relocalize），本地化片段随之重取。
        // 带内联图片的行例外：data URI 单张可达 8MB，重建一屏就等于钉住整屏图片字节，
        // 而这类用户行里唯一本地化的东西是两个按钮提示——不值这个价，留旧文案即可。
        if (!m.images || !m.images.length) { row._msg = m; }
        return row;
    }

    function list() { return document.getElementById('list'); }

    function updateEmpty() {
        if (bridge()) { bridge().onEmpty(list().children.length === 0); }
    }

    /**
     * 按 flags 显隐「重新生成/编辑重发」：只有本轮末条可用。
     * <p>凡新建行的路径都得重跑一遍——整页重放由 Java 显式调 setFlags，但向上补页与换语言
     * 重建只是往列表里塞新节点，不重跑就把按钮留成默认可见，早先每一轮都像是可重生成的末轮。</p>
     */
    function applyFlags() {
        var rows = list().querySelectorAll('.bubble-row');
        for (var i = 0; i < rows.length; i++) {
            var id = parseInt(rows[i].getAttribute('data-id'), 10);
            var regen = rows[i].querySelector('.act-regen');
            if (regen) { regen.classList.toggle('hidden', id !== flags.lastAssistantId); }
            var edit = rows[i].querySelector('.act-edit');
            if (edit) { edit.classList.toggle('hidden', id !== flags.lastUserId); }
        }
    }

    // ---------- 公开 API ----------
    var api = {
        setTheme: function (mode, font, lang, labels) {
            var h = document.documentElement;
            var prevMode = h.getAttribute('data-theme');
            h.setAttribute('data-theme', mode);
            h.setAttribute('data-font', font);
            h.setAttribute('lang', lang);
            if (labels) {
                LABELS = labels;
                PREVIEW_LANGS = previewLangs(labels.previewLangs);
            }
            if (prevMode !== mode) { restyleMermaid(); }
        },

        renderHistory: function (msgs) {
            var l = list();
            l.innerHTML = '';
            disarmAllIdle();
            streaming = {};
            for (var i = 0; i < msgs.length; i++) { l.appendChild(buildMessage(msgs[i])); }
            updateEmpty();
            // 整页重放（会话切换/收尾重放）默认定位到最新消息：重建 DOM 会把滚动位置复位到顶部，
            // 这里立即贴底并恢复跟随态；稍后再齐一次底，等图片解码等异步布局结算后仍不漂离底部。
            followBottom = true;
            wasAtBottom = true;
            scrollToBottom(false);
            if (bottomSnapTimer) { clearTimeout(bottomSnapTimer); }
            bottomSnapTimer = setTimeout(function () {
                bottomSnapTimer = null;
                if (wasAtBottom) { scrollToBottom(false); }
            }, 32);
        },

        prependOlder: function (msgs) {
            var l = list();
            var oldH = l.scrollHeight;
            var frag = document.createDocumentFragment();
            for (var i = 0; i < msgs.length; i++) { frag.appendChild(buildMessage(msgs[i])); }
            l.insertBefore(frag, l.firstChild);
            var d = l.scrollHeight - oldH;
            if (d > 0) {
                var se = document.scrollingElement || document.documentElement;
                se.scrollTop += d;
            }
            updateEmpty();
            applyFlags();
        },

        // 换语言：逐行按原始载荷就地重建（buildMessage 重取 LABELS 里的文案），滚动位置原样保住。
        // 在途临时气泡与对比卡没有 _msg、不参与重建：它们挂在运行态引用上，收尾定格时自会用新文案。
        relocalize: function () {
            var l = list();
            var se = document.scrollingElement || document.documentElement;
            var top = se.scrollTop;
            var kids = l.children;
            for (var i = 0; i < kids.length; i++) {
                var node = kids[i];
                if (node._msg) { l.replaceChild(buildMessage(node._msg), node); }
            }
            // 重建后行高可能因译文长短而变：贴底/跟随态直接对齐底部，否则按原偏移继续读。
            if (followBottom || wasAtBottom) { scrollToBottom(false); }
            else { se.scrollTop = top; }
            updateEmpty();
            applyFlags();
        },

        // 顶部回收（长会话控内存）：贴底后 DOM 过重时，从列表头部移除最旧的已落盘消息行。
        // 数据仍在库中——上滚触顶时 Java 侧照常补页，把被回收段重新加载回来。
        // 只回收已落盘的行（data-id>0 且非在途）：其余节点留在原位，跳过继续向后扫。
        // 由调用方保证贴底时触发：移除部分全在视口上方，scrollTop 被浏览器
        // clamp 到新的底部，视觉零跳变。回收完成后把新首条主键回抛 Java 前移补页游标。
        trimOldest: function (keep) {
            var l = list();
            var removeCount = l.children.length - keep;
            if (removeCount <= 0) { return; }
            var removed = 0;
            var node = l.firstChild;
            while (removed < removeCount && node) {
                var next = node.nextSibling;
                if (node.classList && node.classList.contains('bubble-row')) {
                    var id = parseInt(node.getAttribute('data-id'), 10);
                    if (!isNaN(id) && id > 0 && !streaming[id]) {
                        l.removeChild(node);
                        removed++;
                    }
                }
                node = next;
            }
            if (removed <= 0) { return; }
            var newOldest = 0;
            var rows = l.querySelectorAll('.bubble-row');
            for (var i = 0; i < rows.length; i++) {
                var rid = parseInt(rows[i].getAttribute('data-id'), 10);
                if (!isNaN(rid) && rid > 0) { newOldest = rid; break; }
            }
            if (bridge() && newOldest > 0) { bridge().onTrimmed(newOldest); }
        },

        clear: function () {
            if (bottomSnapTimer) { clearTimeout(bottomSnapTimer); bottomSnapTimer = null; }
            list().innerHTML = '';
            disarmAllIdle();
            streaming = {};
            updateEmpty();
        },

        // 追加单条消息（发送即时上屏的用户气泡，尚未落库无主键）
        appendMessage: function (m) {
            list().appendChild(buildMessage(m));
            updateEmpty();
            applyFlags();
            followBottom = true;
            scrollToBottom(false);
        },

        streamStart: function (id) {
            var row = el('div', 'bubble-row assistant');
            row.setAttribute('data-id', id);
            var hostBox = el('div', 'bubble-host');
            row.appendChild(hostBox);
            list().appendChild(row);
            var pending = pendingLine();
            hostBox.appendChild(pending);
            var s = { host: hostBox, row: row, pending: pending, proc: null, think: null,
                      prose: null, todo: null, open: [], idleTimer: null, pendingTimer: null };
            streaming[id] = s;
            armPendingTick(s);
            armIdle(s);
            followBottom = true;
            scrollToBottom(false);
            updateEmpty();
        },

        streamDelta: function (id, text) {
            var s = streaming[id];
            if (!s || text == null) { return; }
            clearPending(s);
            liveProse(s, text);
            armIdle(s);
            if (followBottom) { scrollToBottom(false); }
        },

        streamThinking: function (id, text) {
            var s = streaming[id];
            if (!s || text == null) { return; }
            clearPending(s);
            liveThink(s, text);
            armIdle(s);
            if (followBottom) { scrollToBottom(false); }
        },

        /** 调用发起：过程区先亮一行在途记录，长耗时工具跑着时不再整块静默。 */
        streamToolStart: function (id, tool) {
            var s = streaming[id];
            if (!s || !tool || !tool.name) { return; }
            clearPending(s);
            liveToolStart(s, tool);
            armIdle(s);
            if (followBottom) { scrollToBottom(false); }
        },

        /** 调用结束：把在途行就地定格（成功/失败、摘要、耗时、输出）。 */
        streamToolEnd: function (id, tool) {
            var s = streaming[id];
            if (!s || !tool || !tool.name) { return; }
            clearPending(s);
            if (tool.name === 'todo_write') {
                var items = todoItems(tool);
                if (items) {
                    // 清单卡就是这次调用的呈现：不占工具行，后续调用整表刷新同一张卡
                    liveTodo(s, items);
                    armIdle(s);
                    if (followBottom) { scrollToBottom(false); }
                    return;
                }
            }
            liveToolEnd(s, tool);
            armIdle(s);
            if (followBottom) { scrollToBottom(false); }
        },

        streamNotice: function (id, text) {
            var s = streaming[id];
            if (!s || !text) { return; }
            clearPending(s);
            liveNotice(s, text);
            armIdle(s);
            if (followBottom) { scrollToBottom(false); }
        },

        streamEnd: function (id) {
            var s = streaming[id];
            if (!s) { return; }
            disarmIdle(s);
            clearPending(s);
            // 收尾只做定格，不重排：正文明文换 Markdown，思考行补耗时，在途工具按失败收口
            stopThink(s);
            for (var i = 0; i < s.open.length; i++) {
                var it = s.open[i];
                it.tool = { name: it.tool.name, input: it.tool.input, output: '', ok: false, durationMs: 0 };
                sealTool(it);
                refreshProcess(it.proc);
            }
            s.open = [];
            closeProc(s);
            if (s.prose) { sealProse(s.prose); s.prose = null; }
            delete streaming[id];
            // 定格后仍是空气泡（一个 token 都没收到就点了停止）：把这行整体撤掉。留着不只是
            // 多一道空白，trimOldest 与 setFlags 也会把它当成一条消息参与回收与标志位比对。
            if (s.host.childNodes.length === 0) {
                s.row.parentNode.removeChild(s.row);
                updateEmpty();
            }
            if (followBottom) { scrollToBottom(false); }
        },

        setFlags: function (lastAssistantId, lastUserId) {
            flags.lastAssistantId = lastAssistantId;
            flags.lastUserId = lastUserId;
            applyFlags();
        },

        reveal: function (id) {
            var row = list().querySelector('.bubble-row[data-id="' + id + '"]');
            if (!row) { return; }
            // 搜索命中定位优先：取消重放后的贴底兜底，避免稍后的齐底打断本处的平滑定位
            if (bottomSnapTimer) { clearTimeout(bottomSnapTimer); bottomSnapTimer = null; }
            followBottom = false;
            row.scrollIntoView({ behavior: 'smooth', block: 'center' });
            row.classList.add('hit');
            setTimeout(function () { row.classList.remove('hit'); }, 1700);
        },

        scrollToBottom: function (smooth) { scrollToBottom(smooth !== false); },

        compareStart: function (cols) {
            // 先撤上一张：对比卡不入库、也不随整页重放重建，早先每张都留在列表里，
            // 而 streaming.compare 只记最新一张——旧卡就此成为没人引用的孤行（算进
            // 回收行数却永远回收不掉，直到下次整页重放才顺带清掉）。
            api.compareClear();
            var row = el('div', 'compare-card');
            row.setAttribute('data-cols', '1');
            for (var i = 0; i < cols.length; i++) {
                var col = el('div', 'compare-col');
                var head = el('div', 'compare-col-head');
                head.appendChild(el('span', 'compare-col-title', cols[i].label));
                head.appendChild(el('span', 'badge badge-blue', cols[i].running));
                var scroll = el('div', 'compare-col-scroll');
                var live = el('div', 'compare-col-text');
                scroll.appendChild(live);
                var foot = el('div', 'compare-col-foot');
                col.appendChild(head);
                col.appendChild(scroll);
                col.appendChild(foot);
                col._live = live;
                col._badge = head.querySelector('.badge');
                col._foot = foot;
                col._scroll = scroll;
                col._raw = '';
                row.appendChild(col);
            }
            list().appendChild(row);
            streaming.compare = row;
            updateEmpty();
            if (followBottom) { scrollToBottom(false); }
        },

        compareDelta: function (i, text) {
            var row = streaming.compare;
            if (!row) { return; }
            var col = row.children[i];
            if (!col) { return; }
            col._raw += (text || '');
            col._live.textContent = norm(col._raw);
            col._scroll.scrollTop = col._scroll.scrollHeight;
        },

        compareNote: function (i, note) { api.compareDelta(i, note); },

        compareEnd: function (i, state, foot) {
            var row = streaming.compare;
            if (!row) { return; }
            var col = row.children[i];
            if (!col) { return; }
            col._live.innerHTML = '';
            col._live.appendChild(mdToElem(col._raw));
            var cls = state === 'done' ? 'badge-green' : state === 'failed' ? 'badge-red' : 'badge-gray';
            col._badge.className = 'badge ' + cls;
            col._badge.textContent = foot.badge;
            col._foot.textContent = foot.text;
        },

        compareClear: function () {
            if (streaming.compare) { streaming.compare.parentNode.removeChild(streaming.compare); streaming.compare = null; }
        },

        // 复制到系统剪贴板：优先当前选区文本（Ctrl+C 兜底，id 传 null），无选区且给定消息 id 时
        // 回退该条消息原文（右键菜单「复制」，与悬停操作条复制同源 data-raw）。经既有 onAction('copy')
        // 通道交 Java 写系统剪贴板，不依赖「合成按键 → Chromium 编辑命令」链路（OSR 下该链不产生编辑效果）。
        copyText: function (id) {
            var text = window.getSelection ? String(window.getSelection()) : '';
            if (!text && id != null) {
                var rows = list().querySelectorAll('.bubble-row[data-id="' + id + '"]');
                var holder = rows.length === 1 ? rows[0].querySelector('[data-raw]') : null;
                text = holder ? (holder.getAttribute('data-raw') || '') : '';
            }
            if (text && bridge()) { bridge().onAction('copy', id, text); }
        }
    };

    function scrollToBottom(smooth) {
        var max = document.documentElement.scrollHeight;
        if (smooth) { window.scrollTo({ top: max, behavior: 'smooth' }); }
        else { window.scrollTo(0, max); }
    }

    // 滚动状态上报（回到底部按钮显隐 + 触顶补页）
    // 节流 120ms：窗口内的中间事件合并，但必须「尾随补报」最后一次状态——否则滚动停止前
    // 落进窗口的最终状态被直接丢弃（滑到底部的最后一次 scroll 事件大概率如此，且停止后
    // 再无 scroll 事件修正），回到底部按钮便一直不隐藏。
    var lastReport = 0;
    var reportTimer = null;
    var pendingReport = null;
    function reportScrollState(atBottom, scrollable) {
        if (!bridge()) { return; }
        var now = Date.now();
        if (now - lastReport > 120) {
            lastReport = now;
            if (reportTimer) { clearTimeout(reportTimer); reportTimer = null; }
            pendingReport = null;
            bridge().onScrollState(atBottom, scrollable);
            return;
        }
        pendingReport = [atBottom, scrollable];
        if (!reportTimer) {
            reportTimer = setTimeout(function () {
                reportTimer = null;
                lastReport = Date.now();
                var p = pendingReport;
                pendingReport = null;
                if (p && bridge()) { bridge().onScrollState(p[0], p[1]); }
            }, lastReport + 120 - now);
        }
    }
    window.addEventListener('scroll', function () {
        var y = window.pageYOffset || document.documentElement.scrollTop;
        var h = document.documentElement.scrollHeight;
        var ch = document.documentElement.clientHeight;
        var scrollable = h > ch + 4;
        var atBottom = (h - ch - y) <= 48;
        followBottom = atBottom || (h - ch - y) <= 120;
        wasAtBottom = atBottom;
        if (bridge()) {
            if (y <= 4) { bridge().onScrollTop(); }
            reportScrollState(atBottom, scrollable);
            // 贴底且 DOM 过重：顺带回收顶部旧行（移除后 scrollTop 被 clamp 回新底，不触发视觉跳动）
            if (atBottom && list().children.length > TRIM_THRESHOLD) { api.trimOldest(TRIM_KEEP); }
        }
    }, { passive: true });

    // 视口尺寸变化（窗口缩放 / 终端抽屉开合）：CEF 重排只会保留 scrollTop，贴底位置会漂离
    // 底部（最新内容被裁到折叠线下方）；曾贴底则补滚回底——与 OSR 过渡帧的贴底锚定一致，
    // 收尾零跳变（未贴底时不干预：重排保留滚动位置，过渡帧顶端对齐即最终位置）。
    window.addEventListener('resize', function () {
        if (wasAtBottom) { scrollToBottom(false); }
    });

    // 原生右键：拦截并回调 Java（Java 弹原生 ContextMenu）
    // 仅消息内容本体响应（用户气泡/正文块/工作日志/清单卡/产物卡/附件行/操作条）：行容器与
    // 留白（气泡旁空区、块间缝隙）不弹菜单，已有菜单则按「菜单外点击」收起
    // ctxMenuOpen 跟踪菜单显示态：JCEF(OSR) 区域点击不触发 JavaFX 弹窗自动隐藏，
    // 由下面的 mousedown/wheel 兜底回抛收起（仅菜单开着时过桥，避免每次点击无谓跨进程）
    var ctxMenuOpen = false;
    var MSG_CARD_SEL = '.bubble-user,.prose,.process,.todo-card,.artifact-card,'
            + '.bubble-footer,.bubble-images img,.file-row';
    document.addEventListener('contextmenu', function (ev) {
        ev.preventDefault();
        var t = ev.target;
        var card = t && t.closest ? t.closest(MSG_CARD_SEL) : null;
        var row = card && card.closest ? card.closest('.bubble-row') : null;
        if (!row) {
            if (ctxMenuOpen) {
                ctxMenuOpen = false;
                if (bridge()) { bridge().onDismissMenu(); }
            }
            return;
        }
        var id = parseInt(row.getAttribute('data-id'), 10);
        ctxMenuOpen = true;
        if (bridge()) { bridge().onContext(isNaN(id) ? 0 : id, ev.clientX, ev.clientY); }
    }, false);

    // 菜单外按下/滚动：收起右键菜单（mousedown 先于后续点击行为触发，左键/中键按下与滚轮立即收）
    document.addEventListener('mousedown', function (ev) {
        if (!ctxMenuOpen) { return; }
        // 右键自身不在此收起：换目标/重开由 Java 侧「先收再弹」在同一 FX 消息内原子完成；
        // 若此处先收，mousedown 与随后 contextmenu 之间隔着 mouseup 与两次桥调用，菜单会闪出空档
        if (ev.button === 2) { return; }
        ctxMenuOpen = false;
        if (bridge()) { bridge().onDismissMenu(); }
    }, true);
    window.addEventListener('wheel', function () {
        if (!ctxMenuOpen) { return; }
        ctxMenuOpen = false;
        if (bridge()) { bridge().onDismissMenu(); }
    }, { passive: true });

    // 选区有无变化上报（Ctrl+C 兜底让位判断）：仅在 true/false 翻转时过桥，拖选过程不产生桥流量。
    // 例：用户刚在消息区选好文字时，输入框里的滞留选区不应劫持 Ctrl+C（Java 侧据此让位）。
    var chatSelState = false;
    document.addEventListener('selectionchange', function () {
        var sel = window.getSelection();
        var has = !!sel && sel.rangeCount > 0 && !sel.isCollapsed;
        if (has === chatSelState) { return; }
        chatSelState = has;
        if (bridge()) { bridge().onSelection(has); }
    });

    window.ChatAPI = api;
    if (bridge()) { bridge().onReady(); }
})();
