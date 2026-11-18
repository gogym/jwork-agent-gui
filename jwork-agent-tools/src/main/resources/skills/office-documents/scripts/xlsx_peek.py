#!/usr/bin/env python3
"""xlsx 速查：只看表结构与样本，不把整份表塞进上下文。

用法：
    python xlsx_peek.py <文件.xlsx> [--sheet 名称|序号] [--offset 行号] [--rows N]
                                     [--cols N] [--list]

约定：
  - --offset 是数据行的 1 基起始行（默认 1，即表头之后的第一行）；表头行始终单独打印。
  - 日期与数字原样打印，便于和源表逐格比对。
  - 输出恒定为 UTF-8，避免 Windows 控制台把中文写成乱码。
"""
import argparse
import sys
from datetime import date, datetime


def emit(line=""):
    sys.stdout.write(str(line) + "\n")


def cell_text(v):
    if v is None:
        return ""
    if isinstance(v, datetime):
        return v.strftime("%Y-%m-%d %H:%M:%S") if (v.hour or v.minute or v.second) else v.strftime("%Y-%m-%d")
    if isinstance(v, date):
        return v.strftime("%Y-%m-%d")
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def main():
    try:
        from openpyxl import load_workbook
    except ImportError:
        emit("缺少 openpyxl：请先执行 python -m pip install openpyxl（需联网，应先征得用户同意）。")
        return 2

    ap = argparse.ArgumentParser(description="xlsx 表结构与样本速查")
    ap.add_argument("path")
    ap.add_argument("--sheet", default="", help="工作表名或序号（默认第一个）")
    ap.add_argument("--offset", type=int, default=1, help="数据起始行，1 基（表头之后）")
    ap.add_argument("--rows", type=int, default=20, help="打印多少数据行（默认 20）")
    ap.add_argument("--cols", type=int, default=40, help="最多打印多少列（默认 40）")
    ap.add_argument("--list", action="store_true", help="只列工作表名与行列数")
    args = ap.parse_args()

    wb = load_workbook(args.path, read_only=True, data_only=True)
    names = wb.sheetnames
    if args.list:
        for n in names:
            ws = wb[n]
            emit("%s\trows=%s\tcols=%s" % (n, ws.max_row, ws.max_column))
        wb.close()
        return 0

    if args.sheet.isdigit() or args.sheet == "":
        ws = wb[names[int(args.sheet) if args.sheet else 0]]
    elif args.sheet in names:
        ws = wb[args.sheet]
    else:
        emit("找不到工作表 %r，现有：%s" % (args.sheet, "、".join(names)))
        wb.close()
        return 2

    width = min(ws.max_column or 1, args.cols)
    emit("文件：%s" % args.path)
    emit("工作表：%s（共 %d 个）  行数=%s 列数=%s" % (ws.title, len(names), ws.max_row, ws.max_column))

    rows = ws.iter_rows(min_col=1, max_col=width, values_only=True)
    header = next(rows, None)
    if header is None:
        emit("(空表)")
        wb.close()
        return 0
    emit("表头：" + " | ".join(cell_text(h) for h in header))

    start = max(args.offset, 1)
    shown = 0
    for idx, row in enumerate(rows, start=1):
        if idx < start:
            continue
        if shown >= args.rows:
            break
        emit("%d\t%s" % (start + shown, "\t".join(cell_text(v) for v in row)))
        shown += 1

    emit("---")
    emit("已打印 %d 行数据（起始第 %d 行，全表约 %d 行数据）" % (shown, start, max((ws.max_row or 1) - 1, 0)))
    wb.close()
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
