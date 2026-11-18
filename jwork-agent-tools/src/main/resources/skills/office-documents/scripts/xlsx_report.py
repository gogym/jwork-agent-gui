#!/usr/bin/env python3
"""按 JSON 规格生成带样式的 xlsx 报表，省掉每次手写 openpyxl 的重复代码。

用法：
    python xlsx_report.py <spec.json> [--out 覆盖输出路径]

规格（顶层对象）：
    {
      "output": "退款差异统计.xlsx",
      "sheets": [
        {
          "name": "差异明细",
          "header": ["商家订单号", "订单编号", "金额", "支付时间"],
          "rows": [["T200P123", "123", 128.5, "2026-09-03"], ...],
          "freeze": "A2",                     // 默认有表头即 A2；写 "none" 关闭
          "widths": {"A": 22, "C": 14},       // 手工列宽，未给的列按内容自动
          "number_formats": {"3": "#,##0.00"}, // 1 基列号 → Excel 数字格式
          "totals": true,                      // 末尾追加合计行（对数字列求和）
          "autofilter": true                   // 表头筛选，默认 true
        }
      ]
    }

约定：
  - rows 里的值按 Python 原类型写入：数字写成数字（可求和、可排序），日期字符串保持字符串，
    需要真正的日期单元格请在单元格里用 {"date": "2026-09-03"} 形式。
  - 输出文件已存在时默认拒绝覆盖（--force 才覆盖），避免误盖用户自己的表。
  - 成功后打印产物绝对路径，供 deliver_artifact 直接引用。
"""
import argparse
import json
import os
import sys
from datetime import date, datetime


def emit(line=""):
    sys.stdout.write(str(line) + "\n")


def display_width(v):
    s = "" if v is None else str(v)
    return sum(2 if ord(ch) > 0x2E7F else 1 for ch in s)


def normalize(value):
    """{"date": "..."} / {"datetime": "..."} 写成真正的日期单元格。"""
    if isinstance(value, dict) and len(value) == 1:
        key, text = next(iter(value.items()))
        if key == "date":
            return datetime.strptime(text, "%Y-%m-%d").date()
        if key == "datetime":
            return datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
    return value


def build_sheet(ws, spec, styles):
    header = spec.get("header") or []
    rows = spec.get("rows") or []
    bold, head_fill, thin = styles["bold"], styles["head_fill"], styles["thin"]
    from openpyxl.utils import get_column_letter

    r = 1
    if header:
        for c, name in enumerate(header, start=1):
            cell = ws.cell(row=r, column=c, value=name)
            cell.font = bold
            cell.fill = head_fill
            cell.border = thin
        r += 1

    first_data = r
    for row in rows:
        for c, raw in enumerate(row, start=1):
            v = normalize(raw)
            cell = ws.cell(row=r, column=c, value=v)
            cell.border = thin
            if isinstance(v, date):
                cell.number_format = "yyyy-mm-dd"
            if isinstance(v, datetime):
                cell.number_format = "yyyy-mm-dd hh:mm:ss"
        r += 1
    last_data = r - 1

    fmts = spec.get("number_formats") or {}
    for col, fmt in fmts.items():
        for row_i in range(first_data, last_data + 1):
            ws.cell(row=row_i, column=int(col)).number_format = fmt

    ncols = max(len(header), max((len(r) for r in rows), default=0))
    if spec.get("totals"):
        from openpyxl.styles import Alignment
        r = last_data + 1
        label = ws.cell(row=r, column=1, value="合计")
        label.font = bold
        label.alignment = Alignment(horizontal="left")
        for c in range(2, ncols + 1):
            s = 0.0
            counted = 0
            for row_i in range(first_data, last_data + 1):
                v = ws.cell(row=row_i, column=c).value
                if isinstance(v, (int, float)) and not isinstance(v, bool):
                    s += v
                    counted += 1
            if counted:
                cell = ws.cell(row=r, column=c, value=int(s) if s.is_integer() else round(s, 6))
                cell.font = bold
                fmt = fmts.get(str(c))
                cell.number_format = fmt if fmt else "#,##0.00"

    widths = {k.upper(): v for k, v in (spec.get("widths") or {}).items()}
    for c in range(1, ws.max_column + 1):
        letter = get_column_letter(c)
        if letter in widths:
            ws.column_dimensions[letter].width = widths[letter]
            continue
        longest = 0
        for row_i in range(1, ws.max_row + 1):
            longest = max(longest, display_width(ws.cell(row=row_i, column=c).value))
        ws.column_dimensions[letter].width = min(max(longest + 2, 8), 60)

    freeze = spec.get("freeze")
    if freeze == "none":
        pass
    elif freeze:
        ws.freeze_panes = freeze
    elif header:
        ws.freeze_panes = "A2"

    if header and spec.get("autofilter", True):
        ws.auto_filter.ref = "A1:%s%d" % (get_column_letter(len(header)), max(last_data, 1))


def main():
    try:
        from openpyxl import Workbook
        from openpyxl.styles import Alignment, Font, PatternFill, Border, Side
    except ImportError:
        emit("缺少 openpyxl：请先执行 python -m pip install openpyxl（需联网，应先征得用户同意）。")
        return 2

    ap = argparse.ArgumentParser(description="按 JSON 规格生成 xlsx 报表")
    ap.add_argument("spec")
    ap.add_argument("--out", default="", help="覆盖规格里的输出路径")
    ap.add_argument("--force", action="store_true", help="允许覆盖已存在的输出文件")
    args = ap.parse_args()

    with open(args.spec, encoding="utf-8") as f:
        doc = json.load(f)

    out = args.out or doc.get("output")
    if not out:
        emit("规格里缺少 output（输出文件名）。")
        return 2
    out = os.path.abspath(out)
    if os.path.exists(out) and not args.force:
        emit("目标已存在，未做任何修改：%s" % out)
        emit("确认要覆盖就加 --force，或换个文件名。")
        return 3

    styles = {
        "bold": Font(bold=True),
        "head_fill": PatternFill("solid", fgColor="F2F5F9"),
        "thin": Border(left=Side("thin", color="D0D7DE"), right=Side("thin", color="D0D7DE"),
                       top=Side("thin", color="D0D7DE"), bottom=Side("thin", color="D0D7DE")),
    }
    wb = Workbook()
    wb.remove(wb.active)
    sheets = doc.get("sheets") or []
    if not sheets:
        emit("规格里没有任何 sheets。")
        return 2
    for spec in sheets:
        ws = wb.create_sheet(title=(spec.get("name") or "Sheet")[:31])
        build_sheet(ws, spec, styles)
        emit("工作表 %s：%d 行数据" % (ws.title, len(spec.get("rows") or [])))

    parent = os.path.dirname(out)
    if parent:
        os.makedirs(parent, exist_ok=True)
    wb.save(out)
    emit("---")
    emit("产物绝对路径：%s" % out)
    emit("大小：%d 字节" % os.path.getsize(out))
    emit("下一步：调用 deliver_artifact(filePath=%r, fileName=..., description=...) 交付。" % out)
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
