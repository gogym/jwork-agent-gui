/*
 * 输入框（JCEF contenteditable）前端逻辑。
 *
 * 职责分工（与 Java 侧 JcefComposer 配合）：
 *  - 字符输入：Java 键盘转发决定，字符经 ComposerAPI.insertText（execCommand）注入本页；
 *    中文 IME 由 Java 侧 InputMethodEvent 驱动 setComposition/commitText（绕开 OSR IME 上游缺陷）。
 *  - 文本真相源：本页 DOM；每次文本/选择变化经桥上报 onState（text/caret/selected/caretRect），
 *    Java 侧维护镜像；发送时 Java 经 askSend 取 DOM 最新全文（杜绝转发链路竞态）。
 *  - DOM 结构约束：只产出文本节点 / <br> / 组合 <span.composing>，文本提取用自研 walker
 *    （组合文本可排除），不依赖 innerText 的块级换算行为。
 */
(function () {
    'use strict';

    var Q = 'gimComposerQuery';
    var editor = document.getElementById('editor');

    // ---------- 桥 ----------

    /* JSON 文本 ASCII 化：JCEF 的 JS→Java JNI 桥（NewStringUTF/Modified UTF-8）承载不了标准
       UTF-8 的 4 字节序列（astral emoji 会被解码成乱码甚至截断整串 JSON），与 Java 侧
       executeScript 的 asciiEscape 对称——非 ASCII 全部转 \uXXXX（JSON 合法转义）再过关。 */
    function asciiSafeJson(s) {
        return s.replace(/[^\x20-\x7e]/g, function (ch) {
            return '\\u' + ('0000' + ch.charCodeAt(0).toString(16)).slice(-4);
        });
    }

    function send(fn, args) {
        try {
            if (typeof window[Q] === 'function') {
                window[Q]({
                    request: asciiSafeJson(JSON.stringify({ fn: fn, args: args || [] })),
                    onSuccess: function () {},
                    onFailure: function () {}
                });
            } else {
                console.error('GIM_COMPOSER_BRIDGE_MISSING: window.' + Q + ' 未注入 fn=' + fn);
            }
        } catch (e) {
            console.error('GIM_COMPOSER_BRIDGE_ERROR: ' + e);
        }
    }

    // ---------- 文本提取（walker：text / br / span.composing） ----------

    var composingSpan = null;

    function walkText(node, includeComposing) {
        var buf = '';
        for (var c = node.firstChild; c; c = c.nextSibling) {
            if (c.nodeType === 3) {
                buf += c.nodeValue;
            } else if (c.nodeName === 'BR') {
                buf += '\n';
            } else if (c.nodeType === 1) {
                if (c === composingSpan && !includeComposing) {
                    continue;
                }
                var inner = walkText(c, includeComposing);
                // 防御：块级元素（正常路径不应出现）按段落语义补换行
                if (/^(DIV|P)$/.test(c.nodeName)) {
                    buf += inner + '\n';
                } else {
                    buf += inner;
                }
            }
        }
        return buf;
    }

    function getText() {
        return walkText(editor, false).replace(/\r\n/g, '\n');
    }

    function syncEmpty() {
        var empty = getText().trim() === '' && !editor.querySelector('img');
        editor.classList.toggle('is-empty', empty);
    }

    // ---------- 状态上报 ----------

    /** 文本 offset（walker 累计：文本节点计长度、BR 计 1；近似定位即可，用于 caret 镜像与 setCaret）。 */
    function textOffsetOf(container, offset) {
        var total = 0;
        var walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
        var n;
        while ((n = walker.nextNode())) {
            if (n === container) {
                return total + (n.nodeType === 3 ? offset : 0);
            }
            if (n.nodeType === 3) {
                total += n.nodeValue.length;
            } else if (n.nodeName === 'BR') {
                total += 1;
            }
        }
        return total;
    }

    function caretInfo() {
        var info = { caret: 0, selected: '', x: 0, y: 0, w: 1, h: 18 };
        var sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) {
            return info;
        }
        var r = sel.getRangeAt(0);
        info.caret = textOffsetOf(r.endContainer, r.endOffset);
        info.selected = sel.toString();
        var rr = r.cloneRange();
        rr.collapse(false);
        var rect = rr.getBoundingClientRect();
        var base = editor.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) {
            // 空行 caret 的退化矩形：取编辑器左下
            info.x = 1;
            info.y = Math.max(0, base.height - 20);
            info.w = 1;
            info.h = 18;
        } else {
            info.x = rect.left - base.left;
            info.y = rect.top - base.top;
            info.w = Math.max(1, rect.width);
            info.h = Math.max(1, rect.height);
        }
        return info;
    }

    function reportState() {
        syncEmpty();
        var c = caretInfo();
        send('onState', [getText(), c.caret, c.selected, c.x, c.y, c.w, c.h]);
    }

    // ---------- 组合态（IME 预编辑） ----------

    function setComposition(text) {
        if (!composingSpan) {
            var sel = window.getSelection();
            if (!sel || sel.rangeCount === 0) {
                return;
            }
            var r = sel.getRangeAt(0).cloneRange();
            r.collapse(false);
            composingSpan = document.createElement('span');
            composingSpan.className = 'composing';
            composingSpan.textContent = text;
            r.insertNode(composingSpan);
        } else {
            composingSpan.textContent = text;
        }
        // caret 保持在组合文本之后：后续 IME 更新与候选窗定位都以它为准
        var after = document.createRange();
        after.setStartAfter(composingSpan);
        after.collapse(true);
        var s = window.getSelection();
        s.removeAllRanges();
        s.addRange(after);
        reportState();
    }

    function dropComposition() {
        if (!composingSpan) {
            return;
        }
        composingSpan = null;
        // 注意：此处仅清除引用；DOM 清理由调用方决定（提交=替换为正式文本 / 取消=直接删除）
    }

    function clearComposition() {
        if (!composingSpan) {
            return;
        }
        var span = composingSpan;
        dropComposition();
        var r = document.createRange();
        r.selectNode(span);
        r.deleteContents();
        var s = window.getSelection();
        s.removeAllRanges();
        var r2 = document.createRange();
        r2.setStart(r.startContainer, r.startOffset);
        r2.collapse(true);
        s.addRange(r2);
        reportState();
    }

    function commitText(text) {
        var replaced = false;
        if (composingSpan) {
            var span = composingSpan;
            dropComposition();
            var r = document.createRange();
            r.selectNode(span);
            var s = window.getSelection();
            s.removeAllRanges();
            s.addRange(r);
            replaced = true;
        }
        if (text) {
            document.execCommand('insertText', false, text);
        } else if (replaced) {
            // 提交空串：删除组合文本即可（已在选区中）
            document.execCommand('delete');
        }
        reportState();
    }

    // ---------- caret 定位（文本 offset → Range） ----------

    function caretRangeFromOffset(pos) {
        var total = 0;
        var walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
        var n;
        while ((n = walker.nextNode())) {
            if (n.nodeType === 3) {
                var len = n.nodeValue.length;
                if (pos <= total + len) {
                    var r = document.createRange();
                    r.setStart(n, Math.max(0, Math.min(len, pos - total)));
                    r.collapse(true);
                    return r;
                }
                total += len;
            } else if (n.nodeName === 'BR') {
                if (pos < total + 1) {
                    var rb = document.createRange();
                    rb.setStartBefore(n);
                    rb.collapse(true);
                    return rb;
                }
                total += 1;
            }
        }
        var rEnd = document.createRange();
        rEnd.selectNodeContents(editor);
        rEnd.collapse(false);
        return rEnd;
    }

    function setCaret(pos) {
        var r = caretRangeFromOffset(Math.max(0, pos));
        editor.focus();
        var s = window.getSelection();
        s.removeAllRanges();
        s.addRange(r);
    }

    // ---------- 键盘删除（Backspace/Delete，Java 键盘链本地注入） ----------

    /**
     * 键盘语义删除：与字符输入同一 JS 链路（合成 AWT 键盘转发在键盘经 JavaFX 路由时
     * 不产生 Chromium 编辑效果——同 Shift+Enter 的处理）。非折叠选区整体删除；折叠
     * caret 用 Selection.modify 按「字符/词组」粒度扩展选区后 execCommand('delete')。
     * forward=true 为 Delete（向前删）；byWord=true（Ctrl 修饰）按词删。
     */
    function deleteByKeyboard(forward, byWord) {
        editor.focus();
        var sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) {
            return;
        }
        if (sel.getRangeAt(0).collapsed) {
            sel.modify('extend', forward ? 'forward' : 'backward', byWord ? 'word' : 'character');
            if (sel.getRangeAt(0).collapsed) {
                return; // 已在文档边界：无可删内容
            }
        }
        document.execCommand('delete');
        reportState();
    }

    // ---------- Java→JS API ----------

    window.ComposerAPI = {
        setText: function (t) {
            editor.textContent = t == null ? '' : String(t);
            reportState();
        },
        clear: function () {
            dropComposition();
            editor.textContent = '';
            reportState();
        },
        insertText: function (t) {
            editor.focus();
            document.execCommand('insertText', false, t == null ? '' : String(t));
            reportState();
        },
        insertLineBreak: function () {
            editor.focus();
            document.execCommand('insertLineBreak');
            reportState();
        },
        deleteText: function (forward, byWord) {
            deleteByKeyboard(!!forward, !!byWord);
        },
        focus: function () {
            editor.focus();
            var s = window.getSelection();
            if (!s || s.rangeCount === 0) {
                var r = document.createRange();
                r.selectNodeContents(editor);
                r.collapse(false);
                s.removeAllRanges();
                s.addRange(r);
            }
            reportState();
        },
        setCaret: function (pos) {
            setCaret(pos);
            reportState();
        },
        setPlaceholder: function (t) {
            editor.setAttribute('data-placeholder', t == null ? '' : String(t));
            syncEmpty();
        },
        setTheme: function (mode) {
            document.documentElement.setAttribute('data-theme', mode === 'dark' ? 'dark' : 'light');
        },
        setComposition: function (t) {
            setComposition(t == null ? '' : String(t));
        },
        commitText: function (t) {
            commitText(t == null ? '' : String(t));
        },
        clearComposition: function () {
            clearComposition();
        },
        askSend: function () {
            send('sendNow', [getText()]);
        }
    };

    // ---------- DOM 事件 ----------

    editor.addEventListener('input', function () {
        reportState();
    });

    document.addEventListener('selectionchange', function () {
        reportState();
    });

    // 粘贴一律取纯文本（防富文本样式混入；多行文本由 insertText 原样插入）
    editor.addEventListener('paste', function (e) {
        e.preventDefault();
        var t = (e.clipboardData || window.clipboardData).getData('text/plain');
        if (t) {
            document.execCommand('insertText', false, t);
        }
    });

    // Enter 兜底提交：正常路径下 Enter 由 Java 侧 KEY_PRESSED 拦截消费（不会到达本页）；
    // 若因平台焦点差异 Enter 直达 Chromium（屏幕外宿主窗口被系统激活等），此处转交
    // Java 走同一发送链路（Java 再经 askSend 取全文）。输入法组合态与 Shift+Enter 放行。
    editor.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
            e.preventDefault();
            send('enterSend', []);
        }
    });

    // 段落插入统一转行内换行：DOM 只产出 <br>，文本提取保持简单
    editor.addEventListener('beforeinput', function (e) {
        if (e.inputType === 'insertParagraph') {
            e.preventDefault();
            document.execCommand('insertLineBreak');
        }
    });

    // 拖放仅接受纯文本
    editor.addEventListener('dragover', function (e) {
        e.preventDefault();
    });

    editor.addEventListener('drop', function (e) {
        e.preventDefault();
        var t = e.dataTransfer ? e.dataTransfer.getData('text/plain') : '';
        if (t) {
            document.execCommand('insertText', false, t);
        }
    });

    // 加载后不主动聚焦编辑区（启动不抢焦点，点击输入框才聚焦）；插入类 API 各自自保 focus
    syncEmpty();
    console.log('GIM_COMPOSER_BOOT bridge=' + typeof window[Q] + ' url=' + location.href);
})();
