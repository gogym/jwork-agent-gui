---
name: office-documents
description: 用户要求生成 Excel/Word/PPT/PDF 等办公文档交付物时的本机 Python 生成规范
version: 1.1.0
---

# 办公文档生成技能

当用户要求产出 .xlsx、.docx、.pptx、PDF 或图表类**文件交付物**时，遵循以下工作流。
核心事实：这些格式是二进制格式，**不能用 write_file 直接写文件内容**；必须写一个 Python 生成脚本、经 shell 执行后产出。

本技能自带两份现成脚本（相对本技能目录，本机一般在 `workspace/skills/office-documents/scripts/`；
技能注入头里给了它的绝对路径，`execute` 直接调）：

- `xlsx_peek.py <文件> [--sheet 名|序号] [--offset 行] [--rows N] [--cols N] [--list]`
  —— 看工作表清单、行列数、表头与抽样数据；比把整份表读进上下文省 token，也用来核对产物。
- `xlsx_report.py <spec.json> [--out 路径] [--force]`
  —— 按 JSON 规格直接产出带表头加粗底色、冻结窗格、自适应列宽、数字格式与合计行的 .xlsx，
  成功后打印产物绝对路径；目标已存在时默认拒绝覆盖（退出码 3），确认后才加 `--force`。

命中 xlsx 任务时优先直接用它们；够用就别另写脚本。确需定制（多表关联、公式、图表、docx/pptx）再按下面的流程自己落脚本。

1. **环境探测**（首次生成前执行一次即可）：
   - `python --version`，失败再试 `py --version`，确认解释器命令。
   - 按目标格式检查依赖库：xlsx→`openpyxl`、docx→`python-docx`、pptx→`python-pptx`、PDF→`reportlab`、图表→`matplotlib`。
     用 `python -c "import 库名"` 探测；缺失时先征得用户同意，再执行 `python -m pip install 库名`（需要联网）。
2. **脚本落盘**：用 write_file 把生成脚本写入工作区（如 `scripts/gen_xxx.py`），数据以字面量内嵌脚本；
   不要用超长的 `python -c "..."` 单行命令，失败时无法定位也无法复用。
   改版自己的脚本用 `write_file(overwrite=true)` 原地重写，不要一路换名留下 `_v2/_final` 一堆中间版本。
3. **数据结构先行**：表格数据先在回复中以 Markdown 表梳理并经用户确认（或数据无歧义时直接落脚本），保持脚本可读。
4. **执行、验证与交付**：用 shell 运行脚本（pandas 读大表可能要几十秒，必要时把 timeout 调大）；
   成功后校验产物——文件存在、大小大于 0，表格用 `inspect_file(path, sheet, offset, rows)` 抽查几行，
   核对表头、行数与合计数，把核对结论写进回答（合计金额、订单条数、与源表差值），不要只回一句「已完成」。
   最后必须调用 `deliver_artifact(filePath, fileName, description)` 交付，并把工具返回的路径写进回答。
5. **格式规范**：
   - Excel：首行表头加粗并冻结窗格，列宽按内容调整，数字写成数值类型而非字符串；多主题拆多 sheet。
   - Word：使用标题层级（Heading 1/2）与正文样式，表格加边框；把 Markdown 结构映射为对应样式而非纯文本堆叠。
   - matplotlib 出图需设置中文字体（如 `plt.rcParams['font.sans-serif'] = ['SimHei']`），避免方框乱码。
   - 输出文件名见名知义，落在工作区或用户授权的项目目录；用户给的数据文件一律不改写，
     产物重名时 deliver_artifact 会返回冲突，换名重交付或经用户同意后再覆盖。
6. **降级策略**：本机无 Python 或 pip 不可用时不硬撑，改为交付文本格式并说明限制：
   表格→CSV（Excel 可直接打开）、文档→Markdown（可粘贴进 Word）、需 PDF→打印友好 HTML（浏览器可转 PDF）。
7. **安全边界**：生成与安装过程不向网络上传用户的文档内容；pip 安装属于有副作用操作，须先经用户确认。
