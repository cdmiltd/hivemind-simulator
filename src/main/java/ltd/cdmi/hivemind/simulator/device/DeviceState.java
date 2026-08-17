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

package ltd.cdmi.hivemind.simulator.device;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 设备模拟状态模型（Dock + Drone）。
 * <p>可变容器，Web 控制台可修改字段值，{@link DeviceSimulator} 据此构造 OSD 报文上报。</p>
 */
@Getter
@Setter
@Component
public class DeviceState {

    // ==================== 全局状态 ====================

    /** 设备是否在线（是否已执行上线流程） */
    private volatile boolean online = false;

    // ==================== 机场设备状态 ====================

    /** 机场工作模式：0=待机,1=全程放飞,2=升级中,3=排查中 */
    private volatile int dockModeCode = 0;
    /** 舱盖状态：false=关闭,true=打开 */
    private volatile boolean coverOpen = false;
    /** 推杆状态：false=归位,true=展开 */
    private volatile boolean putterExpanded = false;
    /** 无人机是否在舱内 */
    private volatile boolean droneInDock = true;
    /** 飞行器是否激活（开机）：true=激活（推送 drone OSD），false=休眠（不推送 drone OSD）。默认休眠，对齐真实场景（需 open_drone_cover 等指令才激活） */
    private volatile boolean droneActivated = false;
    /** 无人机充电状态：0=未充电,1=充电中,2=充满 */
    private volatile int droneChargeState = 2;
    /** 供电电压 (V) */
    private volatile double electricSupplyVoltage = 230.0;
    /** 机场温度 (℃) */
    private volatile double dockTemperature = 25.0;
    /** 机场湿度 (%) */
    private volatile double dockHumidity = 50.0;
    /** 风速（m/s，上报时 ×10 转换为 0.1 m/s 单位，DJI 文档定义 wind_speed 单位为 0.1 m/s） */
    private volatile double windSpeed = 3.0;
    /** 风向：1=正北,2=东北,3=东,4=东南,5=南,6=西南,7=西,8=西北 */
    private volatile int windDirection = 1;
    /** 降雨等级：0=无雨,1=小雨,2=中雨,3=大雨 */
    private volatile int rainfall = 0;
    /** 备用电池温度 (°C) */
    private volatile double backupBatteryTemperature = 25.0;
    /** 机场静音模式：0=非静音, 1=静音（三版共有，accessMode=rw，可通过 property/set 设置） */
    private volatile int silentMode = 0;
    /** 空中回传：false=关闭, true=开启（Dock2/Dock3 共有，accessMode=rw，可通过 property/set 设置） */
    private volatile boolean airTransferEnable = true;
    /** 用户体验改善计划：0=初始, 1=拒绝, 2=同意（Dock2/Dock3 共有，accessMode=rw，可通过 property/set 设置） */
    private volatile int userExperienceImprovement = 0;

    // ==================== 无人机 (M4D) 状态 ====================

    /** 飞行器模式：0=待机,1=起飞准备,2=起飞准备完毕,4=自动起飞,5=航线飞行,9=自动返航,10=自动降落,13=返航降落 */
    private volatile int droneModeCode = 0;
    /** 隐蔽模式状态（DRC drc_drone_state_push）：false=关闭, true=开启 */
    private volatile boolean stealthState = false;
    /** 夜航灯状态（DRC drc_drone_state_push）：false=关闭, true=开启 */
    private volatile boolean nightLightsState = false;

    // ===== DRC 相机状态（drc_camera_state_push）=====
    /** 相机枚举值，格式 {type-subtype-gimbalindex} */
    private volatile String payloadIndex = "81-0-0";
    /** 相机模式：0=拍照,1=录像,2=智能低光,3=全景拍照,4=定时拍照 */
    private volatile int cameraMode = 0;
    /** 录像状态：0=空闲,1=录像中 */
    private volatile int recordingState = 0;
    /** 拍照状态：0=空闲,1=拍照中 */
    private volatile int photoState = 0;
    /** 视频录制时长（秒） */
    private volatile int recordTime = 0;
    /** 剩余拍照张数 */
    private volatile int remainPhotoNum = 6727;
    /** 剩余录像时间（秒） */
    private volatile int remainRecordDuration = 0;
    /** 联动变焦状态：false=关闭, true=开启 */
    private volatile boolean linkageZoomState = false;
    /** 照片大小：0=默认,1=特小,2=小,3=中,4=大,5=特大 */
    private volatile int photoSize = 1;
    /** 定时拍照间隔（秒） */
    private volatile double intervalPhotoInterval = 2.5;
    /** 视频分辨率：0=1920x1080, 1=3840x2160 */
    private volatile String videoResolution = "0";

    // ===== DRC 夜景模式设置（drc_camera_state_push night_mode_settings + Phase 3 指令）=====
    /** 夜景模式：0=关闭, 1=开启, 2=自动 */
    private volatile int nightMode = 0;
    /** 降噪等级：0=关闭, 1=标准降噪, 2=增强降噪15fps, 3=超强降噪5fps */
    private volatile int denoiseLevel = 1;
    /** 黑白夜视使能 */
    private volatile boolean nightVisionEnable = true;
    /** 近红外补光使能 */
    private volatile boolean infraredFillLightEnable = true;

    // ===== 探照灯状态（Dock3 专属，Phase 4 指令控制）=====
    /** 探照灯亮度：1-100 */
    private volatile int lightBrightness = 50;
    /** 探照灯模式：0=关闭, 1=常亮, 2=爆闪, 3=快速爆闪, 4=交替爆闪 */
    private volatile int lightMode = 0;
    /** 左灯角度微调：-3 ~ +3 */
    private volatile int lightLeftAngle = 0;
    /** 右灯角度微调：-3 ~ +3 */
    private volatile int lightRightAngle = 0;

    // ===== 喊话器状态（Dock3 专属，Phase 4 指令控制）=====
    /** 喊话器播放模式：0=单次播放, 1=循环播放 */
    private volatile int speakerPlayMode = 0;
    /** 喊话器音量：1-100 */
    private volatile int speakerVolume = 50;
    /** 喊话器是否正在播放 */
    private volatile boolean speakerPlaying = false;

    // ===== DRC 摄像头 OSD（drc_camera_osd_info_push）=====
    /** 变焦倍数 */
    private volatile double zoomFactor = 7.0;
    /** 变焦对焦值 */
    private volatile int zoomFocusValue = 34;
    /** 变焦最大对焦值 */
    private volatile int zoomMaxFocusValue = 64;
    /** 变焦最小对焦值 */
    private volatile int zoomMinFocusValue = 33;
    /** 红外全局最低温度（°C） */
    private volatile double thermalGlobalTempMin = 31.65;
    /** 红外全局最高温度（°C） */
    private volatile double thermalGlobalTempMax = 40.04;
    /** 激光测距目标经度 */
    private volatile double measureTargetLongitude = 113.703454;
    /** 激光测距目标纬度 */
    private volatile double measureTargetLatitude = 22.907620;
    /** 激光测距目标海拔（m） */
    private volatile double measureTargetAltitude = 34.6;
    /** 激光测距距离（m） */
    private volatile double measureTargetDistance = 0;
    /** 无人机经度 */
    private volatile double droneLongitude;
    /** 无人机纬度 */
    private volatile double droneLatitude;
    /** 无人机高度 (m，相对起飞点) */
    private volatile double droneHeight = 0.0;
    /** 无人机海拔 (m) */
    private volatile double droneElevation = 500.0;
    /** 俯仰角 (°) */
    private volatile double attitudePitch = 0.0;
    /** 横滚角 (°) */
    private volatile double attitudeRoll = 0.0;
    /** 偏航角 (°) */
    private volatile double attitudeYaw = 0.0;
    /** 水平速度 (m/s) */
    private volatile double horizontalSpeed = 0.0;
    /** 垂直速度 (m/s) */
    private volatile double verticalSpeed = 0.0;
    /** 电池电量 (%) */
    private volatile int batteryPercent = 100;
    /** 电池电压 (mV) */
    private volatile int batteryVoltage = 17000;
    /** 电池温度 (℃) */
    private volatile int batteryTemperature = 25;
    /** 飞行器位置状态：0=未定位,1=GPS定位,2=RTK固定解 */
    private volatile int positionState = 2;
    /** 控制模式：0=非控制中,1=云端控制中 */
    private volatile int controlMode = 0;
    /** DRC 状态：0=空闲, 1=连接中, 2=已连接, 3=断开中 */
    private volatile int drcState = 0;

    // ==================== Pilot 模式遥控器状态 ====================

    /** 遥控器剩余电量 (%) — Pilot 模式专用 */
    private volatile int controllerCapacity = 100;

    // ===== 指令飞行任务（drc.html，fly_to_point / takeoff_to_point）=====
    /** 当前 fly_to_point 任务 ID */
    private volatile String currentFlyToId;
    /** 当前 takeoff_to_point 任务 ID（flight_id） */
    private volatile String currentFlightId;
    /** 当前航迹 ID（track_id，takeoff_to_point 专用） */
    private volatile String currentTrackId;
    /** 目标点纬度 */
    private volatile double targetLatitude;
    /** 目标点经度 */
    private volatile double targetLongitude;
    /** 目标点高度（椭球高，m） */
    private volatile double targetHeight;
    /** 最大飞行速度（m/s） */
    private volatile int maxSpeed;
    /** 安全起飞高度（相对起飞点 ALT，m），takeoff_to_point 专用：飞行器先升到此高度再水平飞行 */
    private volatile double securityTakeoffHeight;
    /** 返航高度（相对起飞点 ALT，m） */
    private volatile int rthAltitude;
    /** 返航模式（0=智能高度, 1=设定高度），Dock3 特有 */
    private volatile int rthMode;
    /** 遥控器失控动作（0=悬停, 1=着陆, 2=返航） */
    private volatile int rcLostAction;
    /** 指点飞行失控动作（0=继续执行, 1=退出执行普通失控行为） */
    private volatile int commanderModeLostAction;
    /** 指点飞行模式（0=智能高度飞行, 1=设定高度飞行），Dock3 特有 */
    private volatile int commanderFlightMode;
    /** 指点飞行高度（相对起飞点 ALT，m） */
    private volatile double commanderFlightHeight;
    /** 飞行安全预检查（0=关闭, 1=开启），Dock3 特有 */
    private volatile int flightSafetyAdvanceCheck;
    /** 是否开启模拟器任务（0=不开启, 1=开启） */
    private volatile int simulateMissionEnable;
    /** 模拟任务纬度 */
    private volatile double simulateMissionLatitude;
    /** 模拟任务经度 */
    private volatile double simulateMissionLongitude;

    /** 飞行累计时长 (秒) */
    private volatile long flightTimeSeconds = 0;
    /** 上次状态更新时间 */
    private volatile LocalDateTime lastUpdate = LocalDateTime.now();
}
