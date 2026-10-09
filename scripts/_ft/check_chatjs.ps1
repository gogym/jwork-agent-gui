# 校验 chat.js 语法（node --check 只做语法解析，不执行浏览器全局）；结果写入文件避免环境输出污染
$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\node_check_result.txt'
Remove-Item $out -ErrorAction SilentlyContinue
& 'D:\Program Files\nodejs\node.exe' --check 'D:\GitHub\gim-agent-gui\gim-agent-ui\src\main\resources\web\chat.js' *> $out
"EXIT=$LASTEXITCODE" | Out-File -Append -FilePath $out
