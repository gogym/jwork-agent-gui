---
name: software-automation
description: 用户要求操作 Photoshop/Blender/LibreOffice 等桌面软件或命令行工具完成任务时的脚本化驱动规范
version: 1.0.0
---

# 软件自动化技能

当用户要求**驱动某个桌面软件或外部工具**完成工作（改图、建模、格式转换、批量处理、渲染导出等）时，
遵循以下工作流。核心原则：走软件**自己开放的脚本 / 命令行接口**（语义级、可复现、可审计），
不要靠模拟鼠标键盘点界面（不可靠且无法验证）。

1. **优先专用接口，其次 shell**：读写文件用 read_file/write_file 专用工具；只有确需运行外部程序时才用 shell 工具 `execute`。
   `execute` 只能启动**已列入程序白名单**的可执行文件，白名单外的程序会被安全拦截直接拒绝。
2. **探测接口**：先判断目标软件对外暴露哪种驱动方式，按可行性排序：
   - 命令行/脚本引擎：Adobe 全家桶（ExtendScript/UXP，`photoshop.exe script.jsx`）、Blender（`blender --python x.py`）、
     LibreOffice（`soffice --headless --convert-to`）、ImageMagick（`magick`）、ffmpeg、Ghostscript 等。
   - 无头转换工具：文档/图片/音视频格式转换优先找 CLI，不开 GUI。
   - 本机脚本桥接：确有 COM/AppleScript 接口时，用 `python`+`pywin32` 等脚本驱动，而非点击界面。
   探测命令是否存在：`where 程序名`（Windows）或 `which 程序名`（类 Unix）。
3. **脚本落盘再执行**：把驱动脚本用 write_file 写入工作区（如 `automate/ps_batchexport.jsx`、`automate/blender_render.py`），
   再由 `execute` 调用；不要用超长单行命令——失败无法定位也无法复用。
4. **数据结构先行**：需要喂给软件的参数/清单，先在回复里以清晰结构（表格或键值）确认，再落进脚本。
5. **执行与验证**：运行后校验**产物**——文件存在、大小大于 0、必要时打开核对（如导出图片的分辨率、PDF 页数），
   最后向用户报告产物的**绝对路径**。失败要回显退出码与关键报错，不要假装成功。
6. **安全边界**（重要）：
   - 启动外部程序有副作用，`execute` 每次都会经用户确认；被白名单拒绝时如实告知用户，并说明可把该程序名加入
     `~/.gim-agent/shell-allowlist.txt`（每行一个程序名）后重试，**不要**为绕过限制而改写命令去调用 shell 解释器拼接。
   - 本地优先：默认不向网络下载或上传内容；确需联网（如装依赖、拉素材）先征得用户同意。
   - 只在授权的项目目录/工作区内读写，产物不覆盖同名文件（重名追加序号），不越界删改用户其他文件。
7. **降级策略**：目标软件未安装、无脚本接口或其 CLI 不在白名单时，不要硬撑模拟点击；
   改用可编程的替代方案（如图片处理走 ImageMagick/Python Pillow、文档转换走 LibreOffice 无头模式），
   并向用户说明能力边界与所需授权。
