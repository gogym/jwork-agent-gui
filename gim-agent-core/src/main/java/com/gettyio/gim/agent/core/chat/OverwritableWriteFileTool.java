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

package com.gettyio.gim.agent.core.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * write_file 的本地化替代：加 overwrite 参数，其余语义与框架内置实现逐字对齐。
 *
 * <p>为什么要替代：框架的 FilesystemTool.writeFile 只认 path+content，目标已存在即由文件系统层回绝
 * （"Cannot write to ... because it already exists"）。模型据此不会改写自己上一轮写的脚本，而是换名
 * （report_v2.py、report_final.py）绕开——工作区堆满中间版本，交付出去的也可能是旧那份。
 * 这里给一个显式开关：不传即保持框架原行为，传了才覆盖；覆盖前先取旧字节、写失败回滚，
 * 避免「删了旧的、新的没写成」的两头空。</p>
 *
 * <p>注册方式与 SandboxShellTool 同：build 后以同名注册覆盖框架内置工具，故工具名、只读档
 * （不设 readOnly）与入参名 path 都不变，HITL 确认与路径白名单照常生效。</p>
 *
 * @author gogym
 */
public final class OverwritableWriteFileTool {

    private static final Logger log = LoggerFactory.getLogger(OverwritableWriteFileTool.class);

    /** 被覆盖的工具名（与框架 FilesystemTool.writeFile 注册名一致）。 */
    public static final String NAME = "write_file";

    private final AbstractFilesystem filesystem;

    public OverwritableWriteFileTool(AbstractFilesystem filesystem) {
        this.filesystem = filesystem;
    }

    @Tool(name = NAME, description = "Write content to a file, creating parent directories if needed."
            + " An existing file is left untouched unless overwrite=true; use overwrite=true only when you"
            + " are deliberately replacing a file you own in this task (your own script or draft), and never"
            + " to silently rewrite user-provided data.")
    public String writeFile(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Target file path") String path,
            @ToolParam(name = "content", description = "File content to write") String content,
            @ToolParam(name = "overwrite", required = false,
                    description = "Replace the file if it already exists (default: false)") Boolean overwrite) {
        if (path == null || path.isBlank()) {
            return "Error: path must not be empty";
        }
        String target = normalize(path, runtimeContext);
        boolean forced = Boolean.TRUE.equals(overwrite);
        try {
            if (!forced || !filesystem.exists(runtimeContext, target)) {
                WriteResult r = filesystem.write(runtimeContext, target, content);
                return r.isSuccess() ? "Written to " + r.path() : "Error: " + r.error();
            }
            return overwriteInPlace(runtimeContext, target, content);
        } catch (SecurityException | IllegalArgumentException e) {
            log.warn("write_file 失败: {}", target, e);
            return "Error: " + e.getMessage();
        }
    }

    /** 覆盖写：先快照旧内容，删旧再写新；新内容写不进去就回滚快照，绝不留空文件。 */
    private String overwriteInPlace(RuntimeContext runtimeContext, String target, String content) {
        byte[] backup = snapshot(runtimeContext, target);
        WriteResult deleted = filesystem.delete(runtimeContext, target);
        if (!deleted.isSuccess()) {
            return "Error: " + deleted.error();
        }
        WriteResult written = filesystem.write(runtimeContext, target, content);
        if (written.isSuccess()) {
            return "Overwrote " + written.path();
        }
        if (backup == null) {
            return "Error: " + written.error()
                    + " (the previous version was removed and could not be read back;"
                    + " re-create the file with a full write)";
        }
        if (restore(runtimeContext, target, backup)) {
            return "Error: " + written.error() + " (previous version restored)";
        }
        return "Error: " + written.error()
                + " (the previous version was removed and the new content could not be written;"
                + " write it again in smaller chunks or via execute)";
    }

    /** 旧内容快照；读不到返回 null（此时覆盖不做回滚，由调用方给出提示）。 */
    private byte[] snapshot(RuntimeContext runtimeContext, String target) {
        try {
            List<FileDownloadResponse> rs = filesystem.downloadFiles(runtimeContext, List.of(target));
            if (rs.isEmpty() || !rs.get(0).isSuccess()) {
                return null;
            }
            return rs.get(0).content();
        } catch (SecurityException | IllegalArgumentException e) {
            log.debug("覆盖写快照失败，将不做回滚: {}", target, e);
            return null;
        }
    }

    private boolean restore(RuntimeContext runtimeContext, String target, byte[] backup) {
        try {
            List<FileUploadResponse> rs =
                    filesystem.uploadFiles(runtimeContext, List.of(Map.entry(target, backup)));
            return !rs.isEmpty() && rs.get(0).isSuccess();
        } catch (SecurityException | IllegalArgumentException e) {
            log.warn("覆盖写回滚失败: {}", target, e);
            return false;
        }
    }

    /** 与框架一致的路径归一：模型常按提示里的绝对路径回写，需剥掉当前模式的工作区前缀与命名空间。 */
    private static String normalize(String path, RuntimeContext runtimeContext) {
        WorkspacePathNormalizer normalizer =
                runtimeContext == null ? null : runtimeContext.get(WorkspacePathNormalizer.class);
        return normalizer == null ? path : normalizer.normalize(path, runtimeContext);
    }
}
