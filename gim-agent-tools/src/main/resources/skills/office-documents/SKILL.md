---
name: office-documents
description: 用户要求生成 Excel/Word/PPT/PDF 等办公文档交付物时的本机 Python 生成规范
version: 1.0.0
---

# 办公文档生成技能

当用户要求产出 .xlsx、.docx、.pptx、PDF 或图表类**文件交付物**时，遵循以下工作流。
核心事实：这些格式是二进制格式，**不能用 write_file 直接写文件内容**；必须写一个 Python 生成脚本、经 shell 执行后产出。

1. **环境探测**（首次生成前执行一次即可）：
   - `python --version`，失败再试 `py --version`，确认解释器命令。
   - 按目标格式检查依赖库：xlsx→`openpyxl`、docx→`python-docx`、pptx→`python-pptx`、PDF→`reportlab`、图表→`matplotlib`。
     用 `python -c "import 库名"` 探测；缺失时先征得用户同意，再执行 `python -m pip install 库名`（需要联网）。
2. **脚本落盘**：用 write_file 把生成脚本写入工作区（如 `scripts/gen_xxx.py`），数据以字面量内嵌脚本；
   不要用超长的 `python -c "..."` 单行命令，失败时无法定位也无法复用。
3. **数据结构先行**：表格数据先在回复中以 Markdown 表梳理并经用户确认（或数据无歧义时直接落脚本），保持脚本可读。
4. **执行与验证**：用 shell 运行脚本；成功后校验产物——文件存在、大小大于 0，
   必要时用库读回核对（如 openpyxl 打开确认 sheet 名与行列数），最后向用户报告**产物绝对路径**。
5. **格式规范**：
   - Excel：首行表头加粗并冻结窗格，列宽按内容调整，数字写成数值类型而非字符串；多主题拆多 sheet。
   - Word：使用标题层级（Heading 1/2）与正文样式，表格加边框；把 Markdown 结构映射为对应样式而非纯文本堆叠。
   - matplotlib 出图需设置中文字体（如 `plt.rcParams['font.sans-serif'] = ['SimHei']`），避免方框乱码。
   - 输出文件名见名知义，落在工作区或用户授权的项目目录，不覆盖同名文件（重名时追加序号）。
6. **降级策略**：本机无 Python 或 pip 不可用时不硬撑，改为交付文本格式并说明限制：
   表格→CSV（Excel 可直接打开）、文档→Markdown（可粘贴进 Word）、需 PDF→打印友好 HTML（浏览器可转 PDF）。
7. **安全边界**：生成与安装过程不向网络上传用户的文档内容；pip 安装属于有副作用操作，须先经用户确认。
