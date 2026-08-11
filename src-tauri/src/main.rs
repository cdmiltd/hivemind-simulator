// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};
use tauri::Manager;

const SERVER_PORT: u16 = 19090;
const MAX_WAIT_SECS: u64 = 30;
/// Windows 进程创建标志：隐藏子进程的控制台窗口
const CREATE_NO_WINDOW: u32 = 0x08000000;

/// Java sidecar 进程，窗口关闭时自动终止
struct JavaSidecar(Child);

impl Drop for JavaSidecar {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// 向可选的日志文件写入一行，失败时静默忽略
macro_rules! logln {
    ($log:expr, $($arg:tt)*) => {
        if let Some(ref mut f) = $log {
            let _ = writeln!(f, $($arg)*);
        }
    };
}

/// HTTP 健康检查：发送 GET 请求，检查返回状态码是否为 200。
/// 比 TCP 连接检测更可靠：确保 Spring Boot 已完全启动并可以处理 HTTP 请求，
/// 而非仅 Tomcat 绑定了端口（Spring 上下文可能还在初始化或已失败）。
fn http_health_check(port: u16, path: &str) -> bool {
    let addr = format!("127.0.0.1:{}", port);
    let mut stream = match std::net::TcpStream::connect(&addr) {
        Ok(s) => s,
        Err(_) => return false,
    };
    // 设置读超时，避免 Spring Boot 启动中途端口已开但尚未处理请求时长时间阻塞
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nConnection: close\r\n\r\n",
        path, port
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut response = vec![0u8; 256];
    match stream.read(&mut response) {
        Ok(n) => {
            let response_str = String::from_utf8_lossy(&response[..n]);
            response_str.contains("HTTP/1.1 200") || response_str.contains("HTTP/1.0 200")
        }
        Err(_) => false,
    }
}

/// 轮询等待后端 HTTP 服务就绪（非仅 TCP 端口可连接）
fn wait_for_server(port: u16) -> bool {
    let start = Instant::now();
    while start.elapsed().as_secs() < MAX_WAIT_SECS {
        if http_health_check(port, "/api/connection") {
            return true;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    false
}

/// 启动 sidecar 前清理占用端口的孤儿进程。
/// 场景：上次 Tauri 应用被强制关闭（任务管理器/系统崩溃），Drop 未执行，Java sidecar 成为孤儿进程。
fn kill_orphan_on_port(port: u16, log: &mut Option<File>) {
    let addr = format!("127.0.0.1:{}", port);
    if std::net::TcpStream::connect(&addr).is_err() {
        logln!(log, "端口 {} 空闲，无需清理", port);
        return;
    }
    logln!(log, "端口 {} 被占用，尝试清理孤儿进程...", port);
    // netstat -ano 输出格式：TCP  127.0.0.1:19090  0.0.0.0:0  LISTENING  12345
    let output = Command::new("cmd")
        .args(["/C", &format!("netstat -ano | findstr :{} | findstr LISTENING", port)])
        .creation_flags(CREATE_NO_WINDOW)
        .output();
    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        for line in stdout.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 5 {
                if let Ok(pid) = parts[4].parse::<u32>() {
                    logln!(log, "  发现占用进程 PID={}，尝试终止...", pid);
                    let _ = Command::new("taskkill")
                        .args(["/PID", &pid.to_string(), "/F"])
                        .creation_flags(CREATE_NO_WINDOW)
                        .output();
                }
            }
        }
    }
    // 等待端口释放（最多 5 秒）
    let start = Instant::now();
    while start.elapsed().as_secs() < 5 {
        std::thread::sleep(Duration::from_millis(500));
        if std::net::TcpStream::connect(&addr).is_err() {
            logln!(log, "端口 {} 已释放", port);
            return;
        }
    }
    logln!(log, "[警告] 端口 {} 在 5 秒内未释放", port);
}

/// 去除 Windows 长路径前缀 \\?\，否则 java -jar 无法识别带前缀的 jar 路径
fn strip_long_prefix(p: PathBuf) -> PathBuf {
    p.to_string_lossy()
        .strip_prefix(r"\\?\")
        .map(PathBuf::from)
        .unwrap_or(p)
}

/// 在指定目录下打开日志文件，失败返回 None（不阻断启动）
fn open_log(dir: &Option<PathBuf>, name: &str) -> Option<File> {
    dir.as_ref().and_then(|d| {
        OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(d.join(name))
            .ok()
    })
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let resource_dir = app.path().resource_dir()?;
            let resource_dir = strip_long_prefix(resource_dir);

            // 日志目录：app_log_dir 位于用户可写位置（%LOCALAPPDATA%\<id>\logs）
            // 安装到 Program Files 时 resource_dir 只读，日志必须写到用户目录
            let log_dir = app.path().app_log_dir().ok();
            if let Some(ref d) = log_dir {
                let _ = std::fs::create_dir_all(d);
            }

            let mut log = open_log(&log_dir, "simulator.log");

            logln!(log, "=== DJI Dock Simulator 启动 ===");
            logln!(log, "resource_dir: {}", resource_dir.display());
            logln!(log, "log_dir: {}", log_dir.as_ref().map(|d| d.display().to_string()).unwrap_or_else(|| "<none>".to_string()));

            let java_exe = resource_dir.join("runtime").join("bin").join("java.exe");
            let jar_path = resource_dir.join("app.jar");

            if !java_exe.exists() {
                logln!(log, "[错误] JRE 未找到: {}", java_exe.display());
                if let Some(ref mut f) = log { let _ = f.flush(); }
                return Ok(());
            }
            if !jar_path.exists() {
                logln!(log, "[错误] JAR 未找到: {}", jar_path.display());
                if let Some(ref mut f) = log { let _ = f.flush(); }
                return Ok(());
            }

            logln!(log, "JRE: {}", java_exe.display());
            logln!(log, "JAR: {}", jar_path.display());

            // sidecar 的 stdout/stderr 重定向到 sidecar.log，便于诊断启动失败原因
            let sidecar_log_path = log_dir.as_ref().map(|d| d.join("sidecar.log"));
            let sidecar_out = open_log(&log_dir, "sidecar.log");
            let sidecar_err = sidecar_out.as_ref().and_then(|f| f.try_clone().ok());

            logln!(log, "sidecar 日志: {}", sidecar_log_path.as_ref().map(|p| p.display().to_string()).unwrap_or_else(|| "<null>".to_string()));

            // 启动前清理可能残留的孤儿 sidecar 进程（上次强制退出遗留）
            kill_orphan_on_port(SERVER_PORT, &mut log);
            if let Some(ref mut f) = log { let _ = f.flush(); }

            logln!(log, "启动 sidecar...");
            if let Some(ref mut f) = log { let _ = f.flush(); }

            let stdout = sidecar_out.map(Stdio::from).unwrap_or_else(Stdio::null);
            let stderr = sidecar_err.map(Stdio::from).unwrap_or_else(Stdio::null);

            let child = Command::new(&java_exe)
                .arg("-jar")
                .arg(&jar_path)
                .arg(format!("--server.port={}", SERVER_PORT))
                .stdout(stdout)
                .stderr(stderr)
                .creation_flags(CREATE_NO_WINDOW)
                .spawn();

            let child = match child {
                Ok(c) => c,
                Err(e) => {
                    logln!(log, "[错误] sidecar 进程启动失败: {}", e);
                    if let Some(ref mut f) = log { let _ = f.flush(); }
                    return Ok(());
                }
            };

            app.manage(JavaSidecar(child));

            logln!(log, "等待后端启动 (最多 {} 秒)...", MAX_WAIT_SECS);
            if let Some(ref mut f) = log { let _ = f.flush(); }

            if wait_for_server(SERVER_PORT) {
                logln!(log, "后端已就绪");
            } else {
                logln!(log, "[警告] 后端在 {} 秒内未就绪，仍显示窗口", MAX_WAIT_SECS);
            }
            if let Some(ref mut f) = log { let _ = f.flush(); }

            // 后端就绪后显示窗口
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
