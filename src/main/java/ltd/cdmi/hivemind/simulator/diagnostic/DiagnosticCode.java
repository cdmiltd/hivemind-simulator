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

package ltd.cdmi.hivemind.simulator.diagnostic;

/**
 * 诊断错误码枚举：按责任方分类，用字母前缀区分。
 * <ul>
 *   <li>{@code P-*} = 第三方平台问题（反馈平台修复）</li>
 *   <li>{@code S-*} = 模拟器问题（需开发者处理）</li>
 *   <li>{@code M-*} = 待验证/监控（模拟器推断行为待真机验证 + 监控器问题）</li>
 * </ul>
 * <p>DJI result 码（如 0/1/210229）是协议层码，直接透传到 MQTT 回复；
 * P/S/M 诊断码是诊断层码，只进日志和 UI，不放入 MQTT 回复。</p>
 * <p>真相源：设计文档 §8 错误码体系、TDD-SPEC §2.5 错误码。</p>
 */
public enum DiagnosticCode {
    // ==================== P 类：第三方平台问题 ====================
    /** 平台无响应（requests/events 超时未收到 reply） */
    PLATFORM_NO_REPLY("P-1", "平台无响应", "platform"),
    /** 地址不可达（MQTT 连接地址错误） */
    PLATFORM_HOST_UNREACHABLE("P-2", "地址不可达", "platform"),
    /** 凭证错误（MQTT 认证失败） */
    PLATFORM_AUTH_FAILED("P-3", "凭证错误", "platform"),
    /** License 不匹配（config 回复的 app_license 与本地配置不符） */
    PLATFORM_LICENSE_MISMATCH("P-4", "License 不匹配", "platform"),
    /** JSON 格式错误（平台下发非合法 JSON） */
    PLATFORM_JSON_FORMAT_ERROR("P-5", "JSON 格式错误", "platform"),
    /** 必填字段缺失（缺 tid/bid/method/data） */
    PLATFORM_FIELD_MISSING("P-6", "必填字段缺失", "platform"),
    /** 字段类型错误（method 非字符串、data 非对象等） */
    PLATFORM_FIELD_TYPE_ERROR("P-7", "字段类型错误", "platform"),
    /** Dock 能力不匹配（平台给当前 Dock 下发了不支持的指令） */
    PLATFORM_DOCK_CAPABILITY_MISMATCH("P-8", "Dock 能力不匹配", "platform"),
    /** 平台调用了废弃接口（DJI 文档明确标注"已废弃"的下行接口，平台不应再调用） */
    PLATFORM_DEPRECATED_API_CALLED("P-9", "平台调用废弃接口", "platform"),
    /** 非法枚举值（平台下发了设备不认同的枚举值，如 camera_mode 超出 0-3 范围） */
    PLATFORM_INVALID_ENUM("P-10", "非法枚举值", "platform"),

    // ==================== S 类：模拟器问题 ====================
    /** MQTT 未连接（模拟器未建立 MQTT 连接） */
    SIMULATOR_MQTT_NOT_CONNECTED("S-1", "MQTT 未连接", "simulator"),
    /** 未覆盖指令（method 在 DJI 规范存在但模拟器无 handler） */
    SIMULATOR_METHOD_NOT_IMPLEMENTED("S-2", "未覆盖指令", "simulator"),
    /** 解析异常（NPE/ClassCastException 等模拟器内部 Bug） */
    SIMULATOR_PARSE_BUG("S-3", "解析异常（疑似Bug）", "simulator"),
    /** FFmpeg 不支持 WHIP 推流（本机 ffmpeg 未启用 --enable-muxer=whip），降级为协议模拟 */
    SIMULATOR_FFMPEG_WHIP_NOT_SUPPORTED("S-4", "FFmpeg不支持WHIP", "simulator"),

    // ==================== M 类：待验证/监控 ====================
    /** 监控器 MQTT 未连接 */
    MONITOR_MQTT_NOT_CONNECTED("M-1", "监控器 MQTT 未连接", "monitor"),
    /** 模拟器推断行为（未得到 DJI 官方文档确认，选择最优方案实现，待真机验证） */
    MONITOR_SIMULATOR_INFERENCE("M-2", "模拟器推断（待验证）", "monitor");

    private final String code;
    private final String description;
    private final String category;

    DiagnosticCode(String code, String description, String category) {
        this.code = code;
        this.description = description;
        this.category = category;
    }

    /** 字符串码，如 "P-1" */
    public String code() { return code; }

    /** 中文描述，如 "平台无响应" */
    public String description() { return description; }

    /** 责任方分类："platform" / "simulator" / "monitor" */
    public String category() { return category; }
}
