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

package ltd.cdmi.hivemind.simulator.handler;

import com.fasterxml.jackson.databind.JsonNode;
import ltd.cdmi.dji.cloudapi.sdk.codec.MessageCodec;
import ltd.cdmi.dji.cloudapi.sdk.command.service.flysafe.UnlockLicenseListRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.flysafe.UnlockLicenseSwitchRequest;
import ltd.cdmi.dji.cloudapi.sdk.command.service.flysafe.UnlockLicenseUpdateRequest;
import ltd.cdmi.dji.cloudapi.sdk.protocol.method.ServiceMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程解禁模拟器（Dock1/Dock2/Dock3）。
 * <p>协议参考：DJI Cloud API 远程解禁（Topic=thing/product/{gateway_sn}/services）。
 * 覆盖 3 个同步 Service 指令：
 * <ul>
 *   <li>unlock_license_switch（Dock1/Dock2/Dock3）：启用/禁用单个解禁证书，reply 含 result + license_id</li>
 *   <li>unlock_license_update（Dock1/Dock2/Dock3）：更新解禁证书（file 可缺省），reply 仅含 result</li>
 *   <li>unlock_license_list（Dock1/Dock2/Dock3）：获取解禁证书列表，reply 含 result + device_model_domain + consistence + licenses 数组</li>
 * </ul>
 * <p>模拟器预置 7 种类型的示例证书（type 0~6），switch 修改的 enabled 状态会反映到 list 返回中。
 * 可通过 REST API 查询/重置证书状态，便于调试时验证平台下发是否正确。</p>
 * <p>核实依据：[Dock1 wayline.html] [Dock2 wayline.html] [Dock3 wayline.html] 远程解禁</p>
 */
@Component
public class UnlockLicenseSimulator {

    private static final Logger log = LoggerFactory.getLogger(UnlockLicenseSimulator.class);

    private static final Set<String> UNLOCK_METHODS =
            Set.of(ServiceMethod.UNLOCK_LICENSE_SWITCH.methodName(),
                    ServiceMethod.UNLOCK_LICENSE_UPDATE.methodName(),
                    ServiceMethod.UNLOCK_LICENSE_LIST.methodName());

    /** 证书启用状态：license_id → enabled（switch 修改此状态，list 读取此状态） */
    private final ConcurrentHashMap<Integer, Boolean> licenseEnabled = new ConcurrentHashMap<>();

    /**
     * 判断是否为远程解禁指令。
     *
     * @param method services method
     * @return true 表示是远程解禁指令
     */
    public static boolean isUnlockLicenseMethod(String method) {
        return UNLOCK_METHODS.contains(method);
    }

    // ==================== unlock_license_switch ====================

    /**
     * 处理 unlock_license_switch 指令：启用/禁用单个解禁证书。
     * <p>reply Data: {result: 0, license_id: &lt;id&gt;}</p>
     *
     * @param data 指令 data，含 license_id（int）和 enable（bool）
     * @return services_reply 的 output
     */
    public Map<String, Object> handleSwitch(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), UnlockLicenseSwitchRequest.class);
        int licenseId = req.licenseId();
        boolean enable = req.enable();

        licenseEnabled.put(licenseId, enable);
        log.info("unlock_license_switch: license_id={}, enable={}", licenseId, enable);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", 0);
        output.put("license_id", licenseId);
        return output;
    }

    // ==================== unlock_license_update ====================

    /**
     * 处理 unlock_license_update 指令：更新解禁证书。
     * <p>file 可缺省（按 Flysafe 服务器最新证书更新）。模拟器不实际下载文件，仅模拟更新成功。</p>
     * <p>reply Data: {result: 0}</p>
     *
     * @param data 指令 data，可含 file（struct: url + fingerprint）
     * @return services_reply 的 output
     */
    public Map<String, Object> handleUpdate(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), UnlockLicenseUpdateRequest.class);
        UnlockLicenseUpdateRequest.LicenseFile file = req.file();
        if (file == null) {
            log.info("unlock_license_update: 无 file，按 Flysafe 服务器最新证书更新");
        } else {
            String url = file.url();
            String fingerprint = file.fingerprint();
            log.info("unlock_license_update: file url={}, fingerprint={}", url, fingerprint);
        }

        return Map.of("result", 0);
    }

    // ==================== unlock_license_list ====================

    /**
     * 处理 unlock_license_list 指令：获取设备的解禁证书列表。
     * <p>reply Data: {result: 0, device_model_domain: &lt;回显&gt;, consistence: true, licenses: [...]}</p>
     * <p>预置 7 种类型的示例证书（type 0~6），enabled 状态从 {@link #licenseEnabled} 读取（默认 false）。</p>
     *
     * @param data 指令 data，含 device_model_domain（0=飞行器, 3=机场）
     * @return services_reply 的 output
     */
    public Map<String, Object> handleList(JsonNode data) {
        var req = MessageCodec.fromJson(data.toString(), UnlockLicenseListRequest.class);
        int deviceModelDomain = req.deviceModelDomain();
        log.info("unlock_license_list: device_model_domain={}", deviceModelDomain);

        List<Map<String, Object>> licenses = new ArrayList<>();
        for (int type = 0; type <= 6; type++) {
            licenses.add(buildLicense(type));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", 0);
        output.put("device_model_domain", deviceModelDomain);
        output.put("consistence", true);
        output.put("licenses", licenses);
        return output;
    }

    // ==================== 证书模板构造（7 种类型） ====================

    /** 证书名称前缀 */
    private static final String[] LICENSE_NAMES = {
            "模拟区域解禁", "模拟圆形解禁", "模拟国家解禁",
            "模拟高度解禁", "模拟多边形解禁", "模拟功率解禁", "模拟RID解禁"
    };

    /** 证书 license_id 起始值（240330~240336，与 DJI Example 一致） */
    private static final int LICENSE_ID_BASE = 240330;

    /** 有效期起始（秒级 UNIX 时间戳） */
    private static final int BEGIN_TIME = 1696948115;

    /** 有效期终止（秒级 UNIX 时间戳，2038 年） */
    private static final int END_TIME = 2145916800;

    /** 分组 ID */
    private static final int GROUP_ID = 2896;

    /** 设备 SN（示例占位） */
    private static final String DEVICE_SN = "SIM-DOCK-SN";

    /** 用户 ID（示例占位） */
    private static final String USER_ID = "sim-user";

    /**
     * 构造指定类型的证书（含 common_fields + 对应类型的 unlock 结构）。
     *
     * @param type 证书类型（0~6）
     * @return 证书结构
     */
    private Map<String, Object> buildLicense(int type) {
        int licenseId = LICENSE_ID_BASE + type;

        Map<String, Object> license = new LinkedHashMap<>();
        license.put("common_fields", buildCommonFields(licenseId, type));

        switch (type) {
            case 0 -> license.put("area_unlock", buildAreaUnlock());
            case 1 -> license.put("circle_unlock", buildCircleUnlock());
            case 2 -> license.put("country_unlock", buildCountryUnlock());
            case 3 -> license.put("height_unlock", buildHeightUnlock());
            case 4 -> license.put("polygon_unlock", buildPolygonUnlock());
            case 5 -> license.put("power_unlock", Map.of());
            case 6 -> license.put("rid_unlock", Map.of("level", 1));
            default -> log.warn("未知证书类型: {}", type);
        }
        return license;
    }

    private Map<String, Object> buildCommonFields(int licenseId, int type) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("license_id", licenseId);
        fields.put("name", LICENSE_NAMES[type]);
        fields.put("type", type);
        fields.put("group_id", GROUP_ID);
        fields.put("user_id", USER_ID);
        fields.put("device_sn", DEVICE_SN);
        fields.put("begin_time", BEGIN_TIME);
        fields.put("end_time", END_TIME);
        fields.put("user_only", false);
        fields.put("enabled", licenseEnabled.getOrDefault(licenseId, false));
        return fields;
    }

    /** type=0 授权区解禁 */
    private Map<String, Object> buildAreaUnlock() {
        return Map.of("area_ids", List.of(115001769, 8724));
    }

    /** type=1 自定义圆形区域解禁 */
    private Map<String, Object> buildCircleUnlock() {
        Map<String, Object> unlock = new LinkedHashMap<>();
        unlock.put("radius", 1581);
        unlock.put("latitude", 22.60309);
        unlock.put("longitude", 113.947815);
        unlock.put("height", 500);
        return unlock;
    }

    /** type=2 国家/地区解禁 */
    private Map<String, Object> buildCountryUnlock() {
        Map<String, Object> unlock = new LinkedHashMap<>();
        unlock.put("country_number", 156);
        unlock.put("height", 500);
        return unlock;
    }

    /** type=3 限高解禁 */
    private Map<String, Object> buildHeightUnlock() {
        return Map.of("height", 500);
    }

    /** type=4 自定义多边形区域解禁 */
    private Map<String, Object> buildPolygonUnlock() {
        List<Map<String, Object>> points = List.of(
                Map.of("latitude", 22.55403932, "longitude", 113.90488828),
                Map.of("latitude", 22.55520018, "longitude", 113.92180215),
                Map.of("latitude", 22.54656858, "longitude", 113.92051272)
        );
        return Map.of("points", points);
    }

    // ==================== REST API 辅助 ====================

    /**
     * 获取当前证书启用状态（供 REST API 查询）。
     *
     * @return license_id → enabled 的快照
     */
    public Map<Integer, Boolean> getLicenses() {
        return new LinkedHashMap<>(licenseEnabled);
    }

    /**
     * 清空证书启用状态（供 REST API 重置）。
     */
    public void resetLicenses() {
        licenseEnabled.clear();
        log.info("已清空解禁证书启用状态");
    }
}
