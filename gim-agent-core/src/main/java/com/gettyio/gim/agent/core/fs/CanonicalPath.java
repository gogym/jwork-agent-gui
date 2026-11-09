/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gettyio.gim.agent.core.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;

/**
 * 边界判定用的规范路径。
 *
 * <p>授权目录判断以前只做 {@code toAbsolutePath().normalize()} 再加前缀比较，于是
 * {@code workspace\link -> C:\Users\me\.ssh} 这样的符号链接或 Windows junction 完全合法：
 * 归一化只看字符串里有没有 {@code ..}，看不见「这一级其实指向别处」。写文件、开文件、
 * 生成「总是允许」的目录键这几条路都从同一个前缀比较走出去，所以它们必须共用同一个会真的
 * 走一遍文件系统的规范形。</p>
 *
 * <p>取舍：读不出规范形（权限不足、悬空链接、路径根本不在本地文件系统上）时退回
 * 已归一的绝对路径，而不是抛异常或返回 {@code null}。退回绝对路径意味着这一笔判定
 * 退化成旧行为——更保守（可能多问一次），但绝不会因为解析失败而误判成「在授权范围内」。</p>
 *
 * @author gogym
 */
public final class CanonicalPath {

    private CanonicalPath() {
    }

    /**
     * 取 {@code path} 的规范形：逐级跟随符号链接/junction。
     * <p>目标尚不存在（新建文件）时，对最近的<b>已存在</b>祖先求规范形，再把尚未创建的那几段补回去——
     * 逃逸发生在已存在的目录层级上，这正是写入前必须先看清的部分。</p>
     *
     * @return 规范形；入参为 null 时返回 null；解析不出时退回归一后的绝对路径
     */
    public static Path real(Path path) {
        if (path == null) {
            return null;
        }
        Path abs = path.toAbsolutePath().normalize();
        if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return abs.toRealPath();
            } catch (IOException | SecurityException e) {
                return abs;
            }
        }
        // 从叶子往回找第一个已存在的祖先，剩下的段名按相对路径原样补回
        Path cursor = abs;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return abs;
        }
        Path head;
        try {
            head = cursor.toRealPath();
        } catch (IOException | SecurityException e) {
            return abs;
        }
        Path out = head;
        for (Path segment : cursor.relativize(abs)) {
            out = out.resolve(segment.toString());
        }
        return out.normalize();
    }

    /**
     * {@code candidate} 是否落在 {@code root} 内（含等于 root 本身）。
     * <p>两侧都先取规范形，否则一个链接就能把「在工作区内」变成「在任意位置」。</p>
     */
    public static boolean within(Path candidate, Path root) {
        Path c = real(candidate);
        Path r = real(root);
        return c != null && r != null && c.startsWith(r);
    }

    /** 落在任一 {@code roots} 内则返回其规范形，一处都不在则返回 {@code null}。 */
    public static Path withinAny(Path candidate, Collection<Path> roots) {
        if (candidate == null || roots == null) {
            return null;
        }
        Path canonical = real(candidate);
        for (Path root : roots) {
            Path r = real(root);
            if (r != null && canonical.startsWith(r)) {
                return canonical;
            }
        }
        return null;
    }
}
