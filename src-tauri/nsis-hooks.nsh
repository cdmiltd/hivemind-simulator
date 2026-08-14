; NSIS 安装/卸载钩子
; 终止可能残留的应用进程（主进程 + Java sidecar 子进程）
; 避免文件占用导致安装/卸载失败（特别是 runtime\bin\java.dll 被占用）
; 注意：NSIS 中 PowerShell 单引号转义不可靠，改用 cmd /c taskkill 直接终止

!macro NSIS_HOOK_PREINSTALL
  ; 终止主应用进程
  nsExec::ExecToLog 'cmd /c taskkill /F /IM dji-dock-simulator.exe'
  Pop $0
  ; 终止残留的 Java sidecar 进程
  ; 模拟器安装目录下的 java.exe 是 sidecar，安装/卸载场景下应已退出
  ; 若残留则强制终止，避免 java.dll 文件占用
  nsExec::ExecToLog 'cmd /c taskkill /F /IM java.exe'
  Pop $0
  ; 等待文件句柄释放（Windows 文件系统延迟）
  Sleep 2000
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; 卸载前同样终止应用进程，避免文件占用导致卸载失败
  nsExec::ExecToLog 'cmd /c taskkill /F /IM dji-dock-simulator.exe'
  Pop $0
  nsExec::ExecToLog 'cmd /c taskkill /F /IM java.exe'
  Pop $0
  Sleep 2000
!macroend
