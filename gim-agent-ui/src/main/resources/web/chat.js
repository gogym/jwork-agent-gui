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

    function mdToHtml(raw) {
        var text = norm(raw);
        try {
            var html = window.marked.parse(text,
                { breaks: true, gfm: true });
            return window.DOMPurify.sanitize(html, { ADD_ATTR: ['target'], ALLOWED_URI_REGEXP: ALLOWED_URI });
        } catch (e) {
            return window.DOMPurify.sanitize('<pre>' + esc(text) + '</pre>');
        }
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
        acts.appendChild(artifactBtn(LABELS.artifactOpen || 'Open', 'open', f.path));
        acts.appendChild(artifactBtn(LABELS.artifactReveal || 'Show in Folder', 'reveal', f.path));
        card.appendChild(acts);
        return card;
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

    function buildThinkingCard(thinking, live) {
        var card = el('div', 'thinking-card');
        var head = el('div', 'thinking-header');
        if (live) {
            head.appendChild(el('span', 'thinking-live-dot'));
        } else {
            head.appendChild(iconEl(CHECK_SVG, 'thinking-icon'));
        }
        head.appendChild(el('span', 'thinking-title', live ? (LABELS.thinking || 'Thinking…') : (LABELS.thinkingDone || 'Thought')));
        var body = el('div', 'thinking-body');
        if (!live) {
            var sum = (thinking || '').replace(/\s+/g, ' ').trim();
            if (sum.length > 200) { sum = sum.substring(0, 200) + '…'; }
            head.appendChild(el('span', 'thinking-summary', sum ? ('· ' + sum) : ''));
            var md = mdToElem(thinking);
            body.appendChild(md);
        } else {
            var pre = el('pre', 'thinking-text');
            pre.textContent = thinking || '';
            body.appendChild(pre);
            body._pre = pre;
        }
        // 默认收起（live 与完成态一致）：只露标题行，点击展开查看全文
        body.style.display = 'none';
        card.appendChild(head);
        card.appendChild(body);
        head.addEventListener('click', function () {
            body.style.display = body.style.display === 'none' ? 'block' : 'none';
        }, false);
        return card;
    }

    // 活动组卡标题：流式实时追加与历史重放共用，计数口径只此一处
    function groupTitle(tools, failed) {
        if (tools <= 0) { return LABELS.processed || 'Processed'; }
        return failed > 0
            ? (LABELS.toolFailed || 'Executed {0} tool call(s), {1} failed').replace('{0}', tools).replace('{1}', failed)
            : (LABELS.toolCount || 'Executed {0} tool call(s)').replace('{0}', tools);
    }

    // 活动组卡：相邻的思考与工具调用聚合为一组，组内按发生顺序交错显示（参考时序分组样式）
    // items 元素：{kind:'tool', tool:…} 或 {kind:'thinking', text:…}
    function buildActivityCard(items) {
        var tools = 0;
        var failed = 0;
        for (var i = 0; i < items.length; i++) {
            if (items[i].kind === 'tool') { tools++; if (!items[i].tool.ok) { failed++; } }
        }
        var card = el('div', 'tool-group');
        var head = el('div', 'tool-group-header');
        head.appendChild(iconEl(CHEV_SVG, 'tool-group-chevron'));
        head.appendChild(el('span', 'tool-group-title', groupTitle(tools, failed)));
        var body = el('div', 'tool-group-body');
        for (var j = 0; j < items.length; j++) {
            body.appendChild(items[j].kind === 'tool' ? toolRow(items[j].tool) : thinkingRow(items[j].text));
        }
        card.appendChild(head);
        card.appendChild(body);
        head.addEventListener('click', function () { card.classList.toggle('open'); }, false);
        return card;
    }

    // 组内思考行：与工具行同构，默认收起，点击展开 Markdown 全文
    function thinkingRow(text) {
        var item = el('div', 'tool-group-item');
        var row = el('div', 'tool-group-row');
        row.appendChild(iconEl(CHECK_SVG, 'tool-group-icon ok'));
        row.appendChild(el('span', 'tool-group-name', LABELS.thinkingDone || 'Thought'));
        var sum = (text || '').replace(/\s+/g, ' ').trim();
        if (sum.length > 200) { sum = sum.substring(0, 200) + '…'; }
        if (sum) { row.appendChild(el('span', 'tool-group-summary', '· ' + sum)); }
        var detail = el('div', 'tool-group-detail thinking-detail');
        detail.appendChild(wrapContent(text));
        row.addEventListener('click', function () { item.classList.toggle('open'); }, false);
        item.appendChild(row);
        item.appendChild(detail);
        return item;
    }

    function toolRow(r) {
        var item = el('div', 'tool-group-item');
        var row = el('div', 'tool-group-row');
        row.appendChild(iconEl(r.ok ? CHECK_SVG : ERR_SVG, 'tool-group-icon ' + (r.ok ? 'ok' : 'err')));
        row.appendChild(el('span', 'tool-group-name', toolName(r)));
        var summary = toolSummary(r);
        if (summary) { row.appendChild(el('span', 'tool-group-summary', '· ' + summary)); }
        var detail = el('div', 'tool-group-detail');
        if (r.input) {
            detail.appendChild(el('div', 'tool-detail-label', LABELS.toolInput || 'Input'));
            detail.appendChild(el('pre', 'tool-detail-text', pretty(r.input)));
        }
        if (r.output) {
            detail.appendChild(el('div', 'tool-detail-label', LABELS.toolOutput || 'Response'));
            detail.appendChild(el('pre', 'tool-detail-text', String(r.output)));
        }
        row.addEventListener('click', function () { item.classList.toggle('open'); }, false);
        item.appendChild(row);
        item.appendChild(detail);
        return item;
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

    /** 一条助手消息里清单的最终态（segments 优先，退回扁平 tools 列表）；没有 todo_write 返回 null。 */
    function lastTodo(segments, tools) {
        var found = null;
        var scan = function (list) {
            for (var i = 0; i < list.length; i++) {
                var items = todoItems(list[i]);
                if (items) { found = items; }
            }
        };
        if (segments && segments.length) {
            for (var s = 0; s < segments.length; s++) {
                if (segments[s].type === 'tools' && segments[s].tools) { scan(segments[s].tools); }
            }
        } else if (tools && tools.length) {
            scan(tools);
        }
        return found;
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
            var textBubbles = [];
            // 相邻的思考/工具聚合为一组活动卡，正文段按发生顺序穿插；文本段出现即收尾当前组
            var group = null;
            var flushGroup = function () {
                if (group && group.length) { hostBox.appendChild(buildActivityCard(group)); }
                group = null;
            };
            // 清单只画一张：整表替换语义下中途的每次 todo_write 都是同一张单的旧状态
            var finalTodo = lastTodo(m.segments, m.tools);
            var todoShown = false;
            var showTodo = function () {
                if (todoShown || !finalTodo) { return; }
                todoShown = true;
                flushGroup();
                hostBox.appendChild(todoCard(finalTodo));
            };
            if (m.segments && m.segments.length) {
                // 时序分段：思考/工具/正文按发生顺序交错渲染；组卡与思考行默认收起，点击展开
                for (var si = 0; si < m.segments.length; si++) {
                    var seg = m.segments[si];
                    if (seg.type === 'text' && seg.text) {
                        flushGroup();
                        var sb = el('div', 'bubble-assistant');
                        sb.appendChild(wrapContent(seg.text));
                        hostBox.appendChild(sb);
                        textBubbles.push(sb);
                    } else if (seg.type === 'thinking' && seg.text) {
                        if (!group) { group = []; }
                        group.push({ kind: 'thinking', text: seg.text });
                    } else if (seg.type === 'tools' && seg.tools && seg.tools.length) {
                        if (!group) { group = []; }
                        for (var ti = 0; ti < seg.tools.length; ti++) {
                            if (todoItems(seg.tools[ti])) { showTodo(); continue; }
                            group.push({ kind: 'tool', tool: seg.tools[ti] });
                        }
                    }
                }
                flushGroup();
            } else {
                if (m.thinking) { group = [{ kind: 'thinking', text: m.thinking }]; }
                if (m.tools && m.tools.length) {
                    if (!group) { group = []; }
                    for (var li = 0; li < m.tools.length; li++) {
                        if (todoItems(m.tools[li])) { continue; }
                        group.push({ kind: 'tool', tool: m.tools[li] });
                    }
                }
                flushGroup();
                if (finalTodo) { hostBox.appendChild(todoCard(finalTodo)); }
                var fb = el('div', 'bubble-assistant');
                fb.appendChild(wrapContent(m.content));
                hostBox.appendChild(fb);
                textBubbles.push(fb);
            }
            // 操作条挂最后一个正文气泡；无正文段（纯思考/工具轮）时补一个空气泡承载操作
            var body = textBubbles.length ? textBubbles[textBubbles.length - 1] : null;
            if (!body) {
                body = el('div', 'bubble-assistant');
                body.appendChild(wrapContent(''));
                hostBox.appendChild(body);
            }
            if (m.files && m.files.length) {
                body.appendChild(artifactCards(m.files));
            }
            var footer = actionBar('assistant', m.id, m.rawContent || m.content, true);
            footer.appendChild(el('span', 'msg-ai-label', LABELS.aiGenerated || ''));
            if (m.time) {
                var t = el('span', 'msg-time', m.time);
                footer.insertBefore(t, footer.querySelector('.msg-ai-label'));
            }
            body.appendChild(footer);
        }
        row.appendChild(hostBox);
        return row;
    }

    /** 助手正文容器：有文本才建 Markdown 节点（空段只留容器承载操作条）。 */
    function wrapContent(text) {
        var cb = el('div', 'bubble-content');
        if (text) { cb.appendChild(mdToElem(text)); }
        return cb;
    }

    function list() { return document.getElementById('list'); }

    function updateEmpty() {
        if (bridge()) { bridge().onEmpty(list().children.length === 0); }
    }

    // ---------- 公开 API ----------
    var api = {
        setTheme: function (mode, font, lang, labels) {
            var h = document.documentElement;
            h.setAttribute('data-theme', mode);
            h.setAttribute('data-font', font);
            h.setAttribute('lang', lang);
            if (labels) {
                LABELS = labels;
                PREVIEW_LANGS = previewLangs(labels.previewLangs);
            }
        },

        renderHistory: function (msgs) {
            var l = list();
            l.innerHTML = '';
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
        },

        // 顶部回收（长会话控内存）：贴底后 DOM 过重时，从列表头部移除最旧的已落盘消息行。
        // 数据仍在库中——上滚触顶时 Java 侧照常补页，把被回收段重新加载回来。
        // 只回收连续位于顶部的已落盘行（data-id>0）：遇非消息节点或临时流式行即停止。
        // 由调用方保证贴底时触发：移除部分全在视口上方，scrollTop 被浏览器
        // clamp 到新的底部，视觉零跳变。回收完成后把新首条主键回抛 Java 前移补页游标。
        trimOldest: function (keep) {
            var l = list();
            var removeCount = l.children.length - keep;
            if (removeCount <= 0) { return; }
            var removed = 0;
            while (removed < removeCount && l.firstChild) {
                var node = l.firstChild;
                if (!node.classList || !node.classList.contains('bubble-row')) { break; }
                var id = parseInt(node.getAttribute('data-id'), 10);
                if (isNaN(id) || id <= 0 || streaming[id]) { break; }
                l.removeChild(node);
                removed++;
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
            streaming = {};
            updateEmpty();
        },

        // 追加单条消息（发送即时上屏的用户气泡，尚未落库无主键）
        appendMessage: function (m) {
            list().appendChild(buildMessage(m));
            updateEmpty();
            followBottom = true;
            scrollToBottom(false);
        },

        streamStart: function (id) {
            var row = el('div', 'bubble-row assistant');
            row.setAttribute('data-id', id);
            var hostBox = el('div', 'bubble-host');
            var body = el('div', 'bubble-assistant');
            var contentBox = el('div', 'bubble-content');
            var thinking = el('div', 'bubble-thinking pulsing', LABELS.thinking || 'Thinking…');
            contentBox.appendChild(thinking);
            body.appendChild(contentBox);
            hostBox.appendChild(body);
            row.appendChild(hostBox);
            list().appendChild(row);
            streaming[id] = {
                raw: '', think: '', row: row, contentBox: contentBox, thinkingBox: thinking,
                thinkCard: null, toolCard: null, todoCard: null
            };
            followBottom = true;
            scrollToBottom(false);
            updateEmpty();
        },

        streamDelta: function (id, text) {
            var s = streaming[id];
            if (!s || text == null) { return; }
            if (s.thinkingBox) { s.contentBox.removeChild(s.thinkingBox); s.thinkingBox = null; }
            s.raw += text;
            if (!s.live) { s.live = el('div', 'bubble-live'); s.contentBox.appendChild(s.live); }
            s.live.textContent = norm(s.raw);
            if (followBottom) { scrollToBottom(false); }
        },

        streamThinking: function (id, text) {
            var s = streaming[id];
            if (!s || text == null) { return; }
            if (s.thinkingBox) { s.contentBox.removeChild(s.thinkingBox); s.thinkingBox = null; }
            if (!s.thinkCard) {
                s.thinkCard = buildThinkingCard('', true);
                s.contentBox.insertBefore(s.thinkCard, s.contentBox.firstChild);
            }
            s.think += text;
            var pre = s.thinkCard.querySelector('.thinking-body')._pre;
            if (pre) { pre.textContent = norm(s.think); }
            if (followBottom) { scrollToBottom(false); }
        },

        streamToolCard: function (id, tool) {
            var s = streaming[id];
            if (!s || !tool || !tool.name) { return; }
            if (tool.name === 'todo_write') {
                var items = todoItems(tool);
                if (items) {
                    // 清单卡就是这次调用的呈现，不再重复塞一条工具行；后续调用刷新同一张卡
                    if (!s.todoCard) {
                        s.todoCard = todoCard(items);
                        s.contentBox.insertBefore(s.todoCard, s.live || null);
                    } else {
                        renderTodo(s.todoCard, items);
                    }
                    if (followBottom) { scrollToBottom(false); }
                    return;
                }
            }
            if (!s.toolCard) {
                s.toolCard = buildActivityCard([]);
                // 排在逐字正文之前：思考卡 → 工具轨迹 → 正文，与收尾重放的层次一致
                s.contentBox.insertBefore(s.toolCard, s.live || null);
            }
            var body = s.toolCard.querySelector('.tool-group-body');
            body.appendChild(toolRow(tool));
            var failed = s.toolCard.querySelectorAll('.tool-group-icon.err').length;
            s.toolCard.querySelector('.tool-group-title').textContent =
                groupTitle(body.querySelectorAll('.tool-group-item').length, failed);
            if (followBottom) { scrollToBottom(false); }
        },

        streamEnd: function (id) {
            var s = streaming[id];
            if (!s) { return; }
            // 实时工具轨迹卡与任务清单就地保留：正文与思考卡按定格文本重排，不该在收尾一瞬间把它们抹掉
            var trace = s.toolCard;
            var todo = s.todoCard;
            s.contentBox.innerHTML = '';
            if (s.think) { s.contentBox.appendChild(buildThinkingCard(s.think, false)); }
            if (trace) { s.contentBox.appendChild(trace); }
            if (todo) { s.contentBox.appendChild(todo); }
            if (s.raw) { s.contentBox.appendChild(mdToElem(s.raw)); }
            else if (!s.think) { s.contentBox.appendChild(el('div', 'compare-col-text', '')); }
            delete streaming[id];
            if (followBottom) { scrollToBottom(false); }
        },

        setFlags: function (lastAssistantId, lastUserId) {
            flags.lastAssistantId = lastAssistantId;
            flags.lastUserId = lastUserId;
            var rows = list().querySelectorAll('.bubble-row');
            for (var i = 0; i < rows.length; i++) {
                var id = parseInt(rows[i].getAttribute('data-id'), 10);
                var regen = rows[i].querySelector('.act-regen');
                if (regen) { regen.classList.toggle('hidden', id !== lastAssistantId); }
                var edit = rows[i].querySelector('.act-edit');
                if (edit) { edit.classList.toggle('hidden', id !== lastUserId); }
            }
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
    // 仅消息卡片本体响应（气泡/思考卡/工具卡/附件行/操作条）：行容器与宿主容器的留白
    // （气泡旁空区、卡片间的缝隙）不弹菜单，已有菜单则按「菜单外点击」收起
    // ctxMenuOpen 跟踪菜单显示态：JCEF(OSR) 区域点击不触发 JavaFX 弹窗自动隐藏，
    // 由下面的 mousedown/wheel 兜底回抛收起（仅菜单开着时过桥，避免每次点击无谓跨进程）
    var ctxMenuOpen = false;
    var MSG_CARD_SEL = '.bubble-user,.bubble-assistant,.bubble-footer,.thinking-card,'
            + '.tool-group,.bubble-images img,.file-row';
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
