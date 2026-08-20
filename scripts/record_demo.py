"""
hivemind-simulator 演示视频自动录制脚本

依赖安装：
    pip install selenium webdriver-manager

使用方式：
    1. 启动模拟器：mvn spring-boot:run
    2. 运行脚本：python scripts/record_demo.py
    3. 截图保存在 scripts/screenshots/ 目录

可选参数：
    --url          模拟器地址（默认 http://localhost:9090）
    --monitor-url  监控器地址（默认 http://localhost:9090/monitor.html）
    --output       截图输出目录（默认 scripts/screenshots）
    --delay        每步操作间隔秒数（默认 2，录屏时建议调大）
"""

import argparse
import os
import sys
import time
from pathlib import Path

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
from selenium.webdriver.common.action_chains import ActionChains
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from webdriver_manager.chrome import ChromeDriverManager


class SimulatorRecorder:
    """模拟器演示视频录制器"""

    def __init__(self, url, monitor_url, output_dir, delay):
        self.url = url
        self.monitor_url = monitor_url
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.delay = delay
        self.wait = None
        self.driver = None
        self.step = 0

    def setup_driver(self):
        """启动 Chrome 浏览器"""
        options = Options()
        options.add_argument("--window-size=1920,1080")
        options.add_argument("--disable-gpu")
        options.add_argument("--no-sandbox")
        options.add_argument("--disable-dev-shm-usage")
        # 禁用图片加载加速（如需展示地图则注释此行）
        # prefs = {"profile.managed_default_content_settings.images": 2}
        # options.add_experimental_option("prefs", prefs)

        service = Service(ChromeDriverManager().install())
        self.driver = webdriver.Chrome(service=service, options=options)
        self.driver.implicitly_wait(5)
        self.wait = WebDriverWait(self.driver, 15)
        print(f"浏览器已启动，窗口 1920x1080")

    def screenshot(self, name):
        """截图并保存"""
        self.step += 1
        filename = f"{self.step:02d}_{name}.png"
        filepath = self.output_dir / filename
        self.driver.save_screenshot(str(filepath))
        print(f"  截图: {filename}")
        time.sleep(self.delay)

    def wait_for_element(self, by, value, timeout=15):
        """等待元素出现"""
        return WebDriverWait(self.driver, timeout).until(
            EC.presence_of_element_located((by, value))
        )

    def wait_for_clickable(self, by, value, timeout=15):
        """等待元素可点击"""
        return WebDriverWait(self.driver, timeout).until(
            EC.element_to_be_clickable((by, value))
        )

    def click_if_exists(self, by, value, timeout=3):
        """如果元素存在则点击"""
        try:
            elem = WebDriverWait(self.driver, timeout).until(
                EC.element_to_be_clickable((by, value))
            )
            elem.click()
            return True
        except Exception:
            return False

    def navigate_simulator(self):
        """导航到模拟器页面"""
        print("\n[场景1] 打开模拟器主页面")
        self.driver.get(f"{self.url}?v={int(time.time())}")
        time.sleep(4)  # 等待 Vue 渲染
        self.screenshot("simulator_initial")

    def show_register_dialog(self):
        """展示注册弹窗"""
        print("\n[场景2] 打开注册配置弹窗")
        # 点击"注册到第三方平台"按钮
        buttons = self.driver.find_elements(By.CSS_SELECTOR, "button")
        for btn in buttons:
            if "注册到第三方平台" in btn.text:
                btn.click()
                break
        time.sleep(2)
        self.screenshot("register_dialog")

    def close_register_dialog(self):
        """关闭注册弹窗"""
        self.click_if_exists(By.CSS_SELECTOR, ".el-dialog__headerbtn")
        time.sleep(1)

    def show_device_status_tab(self):
        """展示设备状态 Tab"""
        print("\n[场景3] 设备状态 Tab")
        # 点击"设备状态" tab
        tabs = self.driver.find_elements(By.CSS_SELECTOR, ".el-tabs__item")
        for tab in tabs:
            if "设备状态" in tab.text:
                tab.click()
                break
        time.sleep(1)
        self.screenshot("tab_device_status")

    def show_flight_task_tab(self):
        """展示飞行任务 Tab"""
        print("\n[场景4] 飞行任务 Tab")
        tabs = self.driver.find_elements(By.CSS_SELECTOR, ".el-tabs__item")
        for tab in tabs:
            if "飞行任务" in tab.text:
                tab.click()
                break
        time.sleep(1)
        self.screenshot("tab_flight_task")

    def show_payload_tab(self):
        """展示负载设备 Tab"""
        print("\n[场景5] 负载设备 Tab")
        tabs = self.driver.find_elements(By.CSS_SELECTOR, ".el-tabs__item")
        for tab in tabs:
            if "负载设备" in tab.text:
                tab.click()
                break
        time.sleep(1)
        self.screenshot("tab_payload")

    def show_system_tab(self):
        """展示系统维护 Tab"""
        print("\n[场景6] 系统维护 Tab")
        tabs = self.driver.find_elements(By.CSS_SELECTOR, ".el-tabs__item")
        for tab in tabs:
            if "系统维护" in tab.text:
                tab.click()
                break
        time.sleep(1)
        self.screenshot("tab_system")

    def show_message_log(self):
        """展示指令通讯窗口"""
        print("\n[场景7] 指令通讯窗口（MQTT 消息日志）")
        # 滚动到日志区域顶部
        log_container = self.driver.find_elements(By.CSS_SELECTOR, ".log-container")
        if log_container:
            self.driver.execute_script(
                "arguments[0].scrollTop = 0;", log_container[0]
            )
        time.sleep(1)
        self.screenshot("message_log")

    def show_location_simulation(self):
        """展示位置模拟"""
        print("\n[场景8] 位置模拟（地图模式）")
        # 切到飞行任务 tab 找位置模拟
        tabs = self.driver.find_elements(By.CSS_SELECTOR, ".el-tabs__item")
        for tab in tabs:
            if "飞行任务" in tab.text:
                tab.click()
                break
        time.sleep(1)
        self.screenshot("location_simulation")

    def navigate_monitor(self):
        """导航到监控器页面"""
        print("\n[场景9] 监控器页面")
        self.driver.get(f"{self.monitor_url}?v={int(time.time())}")
        time.sleep(4)  # 等待 Vue 渲染
        self.screenshot("monitor_initial")

    def show_monitor_dropdown(self):
        """展示监控器下拉菜单"""
        print("\n[场景10] 监控器菜单下拉")
        # hover 到"工具"下拉菜单
        dropdowns = self.driver.find_elements(By.CSS_SELECTOR, ".header-dropdown")
        for dd in dropdowns:
            btn = dd.find_elements(By.CSS_SELECTOR, ".header-dropdown-btn")
            if btn and "工具" in btn[0].text:
                ActionChains(self.driver).move_to_element(dd).perform()
                time.sleep(1)
                self.screenshot("monitor_dropdown_tools")
                break

    def show_monitor_diagnosis(self):
        """展示监控器诊断功能"""
        print("\n[场景11] 监控器诊断日志")
        # 尝试点击诊断日志按钮
        buttons = self.driver.find_elements(By.CSS_SELECTOR, ".el-button")
        for btn in buttons:
            if "诊断" in btn.text:
                btn.click()
                time.sleep(2)
                self.screenshot("monitor_diagnosis")
                break

    def switch_device_mode(self):
        """切换设备模式（Dock1 -> Dock2 -> Dock3）"""
        print("\n[场景12] 切换设备型号")
        self.driver.get(f"{self.url}?v={int(time.time())}")
        time.sleep(4)

        # 尝试打开注册弹窗切换 Dock 类型
        buttons = self.driver.find_elements(By.CSS_SELECTOR, "button")
        for btn in buttons:
            if "注册" in btn.text:
                btn.click()
                time.sleep(2)
                break

        # 截图当前配置
        self.screenshot("switch_device_config")

        # 关闭弹窗
        self.click_if_exists(By.CSS_SELECTOR, ".el-dialog__headerbtn")
        time.sleep(1)

    def final_shot(self):
        """最终全景截图"""
        print("\n[场景13] 最终全景")
        self.driver.get(f"{self.url}?v={int(time.time())}")
        time.sleep(4)
        self.screenshot("final_overview")

    def run(self):
        """执行完整录制流程"""
        print("=" * 60)
        print("hivemind-simulator 演示视频录制")
        print(f"模拟器地址: {self.url}")
        print(f"监控器地址: {self.monitor_url}")
        print(f"截图目录: {self.output_dir}")
        print(f"操作间隔: {self.delay}s")
        print("=" * 60)

        try:
            self.setup_driver()

            # ===== 模拟器页面录制 =====
            self.navigate_simulator()
            self.show_register_dialog()
            self.close_register_dialog()

            # Tab 分组展示
            self.show_device_status_tab()
            self.show_flight_task_tab()
            self.show_payload_tab()
            self.show_system_tab()

            # 功能展示
            self.show_message_log()
            self.show_location_simulation()

            # 切换设备型号
            self.switch_device_mode()

            # ===== 监控器页面录制 =====
            self.navigate_monitor()
            self.show_monitor_dropdown()
            self.show_monitor_diagnosis()

            # 最终全景
            self.final_shot()

            print("\n" + "=" * 60)
            print(f"录制完成！共 {self.step} 张截图")
            print(f"保存在: {self.output_dir.absolute()}")
            print("=" * 60)

        except Exception as e:
            print(f"\n录制出错: {e}", file=sys.stderr)
            # 保存错误截图
            if self.driver:
                self.screenshot("error")
            raise
        finally:
            if self.driver:
                input("\n按 Enter 关闭浏览器...")
                self.driver.quit()


def main():
    parser = argparse.ArgumentParser(
        description="hivemind-simulator 演示视频自动录制脚本"
    )
    parser.add_argument(
        "--url",
        default="http://localhost:9090",
        help="模拟器地址（默认 http://localhost:9090）",
    )
    parser.add_argument(
        "--monitor-url",
        default="http://localhost:9090/monitor.html",
        help="监控器地址（默认 http://localhost:9090/monitor.html）",
    )
    parser.add_argument(
        "--output",
        default="scripts/screenshots",
        help="截图输出目录（默认 scripts/screenshots）",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=2.0,
        help="每步操作间隔秒数（默认 2，录屏时建议调大）",
    )
    args = parser.parse_args()

    recorder = SimulatorRecorder(
        url=args.url,
        monitor_url=args.monitor_url,
        output_dir=args.output,
        delay=args.delay,
    )
    recorder.run()


if __name__ == "__main__":
    main()
