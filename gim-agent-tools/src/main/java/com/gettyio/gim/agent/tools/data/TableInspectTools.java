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

package com.gettyio.gim.agent.tools.data;

import com.gettyio.gim.agent.core.doc.TabularInspector;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Supplier;

/**
 * 面向 Agent 的表格探查工具：{@code inspect_file}——分片查看 xlsx/csv 的结构与行区间。
 * <p>附件表格不再整份塞进上下文（几十万行会把窗口吃光，抽取还会截断到只剩开头一段）；
 * 本工具让模型先看清单与表头，再按 {@code offset/rows} 取需要的片段，
 * 或直接照返回的绝对路径用 {@code execute} 跑 pandas/openpyxl 做全量统计。</p>
 *
 * @author gogym
 */
public class TableInspectTools {

    /** 相对路径的解析基准（工作区根）：附件就落在 {@code attachments/<sessionId>/} 下。 */
    private final Supplier<Path> workspaceRoot;

    public TableInspectTools(Supplier<Path> workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * 工具入口（{@code inspect_file}）：不传 sheet 返回工作表清单与表头样例，传了则返回指定行区间。
     *
     * @param path   表格文件路径（绝对或相对工作区）
     * @param sheet  工作表名或 1 基序号，可空
     * @param offset 起始行（0 基，0 通常是表头），可空
     * @param rows   行数，可空
     */
    @Tool(name = "inspect_file", readOnly = true,
            description = "分片查看表格文件（.xlsx/.xlsm/.csv/.tsv）的内容，不读取整个文件。"
                    + "不传 sheet 时返回工作表清单与各表的表头样例；传 sheet+offset+rows 返回该行区间（日期/数字按 Excel 显示值返回）。"
                    + "需要全量统计或写出结果文件时，用 execute 跑 Python(pandas/openpyxl) 处理返回的绝对路径，别把整表读进对话。")
    public String inspectFile(
            @ToolParam(name = "path", description = "表格文件路径，绝对路径或相对工作区路径（附件形如 attachments/<会话ID>/xxx.xlsx）")
            String path,
            @ToolParam(name = "sheet", required = false,
                    description = "工作表名或 1 基序号；留空返回全部工作表的清单与表头样例")
            String sheet,
            @ToolParam(name = "offset", required = false,
                    description = "起始行，0 基（0 通常是表头行）；默认 1（跳过表头看数据）")
            Integer offset,
            @ToolParam(name = "rows", required = false,
                    description = "返回行数，默认 50，上限 " + TabularInspector.MAX_ROWS)
            Integer rows) {
        if (path == null || path.isBlank()) {
            return "path 不能为空。";
        }
        Path file = resolve(path.trim());
        if (!TabularInspector.isTabular(file.getFileName() == null ? "" : file.getFileName().toString())) {
            return "inspect_file 只支持表格文件（.xlsx/.xlsm/.csv/.tsv）：" + path + "；文档类请用 read_file。";
        }
        if (sheet == null || sheet.isBlank()) {
            return listSheets(file);
        }
        int from = offset == null ? 1 : Math.max(0, offset);
        int count = rows == null || rows <= 0 ? 50 : rows;
        return TabularInspector.render(file, sheet.trim(), from, count);
    }

    /** 清单模式：工作表 + 规模 + 表头样例行，并说明下一步怎么取数。 */
    private String listSheets(Path file) {
        List<TabularInspector.Sheet> sheets = TabularInspector.sheets(file);
        if (sheets.isEmpty()) {
            return "读取失败：文件不存在或不是有效的表格文件：" + file.toAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(file.toAbsolutePath()).append('\n');
        sb.append("工作表 ").append(sheets.size()).append(" 个：\n");
        for (int i = 0; i < sheets.size(); i++) {
            TabularInspector.Sheet s = sheets.get(i);
            sb.append(i + 1).append(") ").append(s.name());
            if (s.rows() > 0) {
                sb.append("（约 ").append(s.rows()).append(" 行 × ").append(s.cols()).append(" 列）");
            }
            sb.append('\n');
        }
        String overview = TabularInspector.overview(file, TabularInspector.SAMPLE_ROWS);
        if (overview != null && !overview.isBlank()) {
            sb.append("各表前 ").append(TabularInspector.SAMPLE_ROWS).append(" 行（首行通常为表头）：\n").append(overview);
        }
        sb.append("取具体区间：inspect_file(path=上述路径, sheet=名称或序号, offset=起始行(0基), rows=行数)。\n");
        sb.append("全量统计：用 execute 跑 Python 读取上面的绝对路径。");
        return sb.toString();
    }

    /** 相对路径挂到工作区根下；绝对路径原样使用。 */
    private Path resolve(String path) {
        Path p = Paths.get(path);
        return p.isAbsolute() ? p : workspaceRoot.get().resolve(p);
    }
}
