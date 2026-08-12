; NSIS 安装/卸载钩子
; 终止可能残留的应用进程（主进程 + Java sidecar 子进程）
; 避免文件占用导致安装/卸载失败（特别是 runtime\bin\java.dll 被占用）

!macro NSIS_HOOK_PREINSTALL
  ; 终止主应用进程
  nsExec::ExecToLog 'taskkill /F /IM dji-dock-simulator.exe'
  Pop $0
  ; 按路径特征精准终止 Java sidecar（避免影响用户其他 Java 应用）
  nsExec::ExecToLog 'powershell -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like ''*\runtime\bin\java.exe'' } | Stop-Process -Force"'
  Pop $0
  Sleep 1000
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; 卸载前同样终止应用进程，避免文件占用导致卸载失败
  nsExec::ExecToLog 'taskkill /F /IM dji-dock-simulator.exe'
  Pop $0
  nsExec::ExecToLog 'powershell -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like ''*\runtime\bin\java.exe'' } | Stop-Process -Force"'
  Pop $0
  Sleep 1000
!macroend
