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

package ltd.cdmi.hivemind.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.handler.UnlockLicenseSimulator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UnlockLicenseSimulator 单元测试。
 * <p>覆盖 2 个同步 Service 指令：
 * <ul>
 *   <li>unlock_license_switch：启用/禁用证书，reply 含 result + license_id</li>
 *   <li>unlock_license_update：更新证书（file 可缺省），reply 仅含 result</li>
 * </ul>
 * <p>核实依据：[Dock3 wayline.html] 远程解禁</p>
 */
class UnlockLicenseSimulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode data(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    // ==================== TC-UNLOCK-001：unlock_license_switch 启用证书 ====================

    @Test
    void switchEnableLicense() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": true}"));

        assertEquals(0, output.get("result"));
        assertEquals(240330, output.get("license_id"));
        assertEquals(true, simulator.getLicenses().get(240330));
    }

    // ==================== TC-UNLOCK-002：unlock_license_switch 禁用证书 ====================

    @Test
    void switchDisableLicense() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": false}"));

        assertEquals(0, output.get("result"));
        assertEquals(240330, output.get("license_id"));
        assertEquals(false, simulator.getLicenses().get(240330));
    }

    // ==================== TC-UNLOCK-003：unlock_license_update 带文件 ====================

    @Test
    void updateWithFile() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleUpdate(
                data("{\"file\": {\"url\": \"https://xx.oss-cn-hangzhou.aliyuncs.com/xx.kmz\", \"fingerprint\": \"xxxx\"}}"));

        assertEquals(0, output.get("result"));
        assertEquals(1, output.size(), "update reply 应仅含 result");
    }

    // ==================== TC-UNLOCK-004：unlock_license_update 无文件 ====================

    @Test
    void updateWithoutFile() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleUpdate(data("{}"));

        assertEquals(0, output.get("result"));
        assertEquals(1, output.size(), "update reply 应仅含 result");
    }

    // ==================== TC-UNLOCK-005：isUnlockLicenseMethod 指令识别 ====================

    @Test
    void isUnlockLicenseMethodRecognition() {
        assertTrue(UnlockLicenseSimulator.isUnlockLicenseMethod("unlock_license_switch"));
        assertTrue(UnlockLicenseSimulator.isUnlockLicenseMethod("unlock_license_update"));
        assertTrue(UnlockLicenseSimulator.isUnlockLicenseMethod("unlock_license_list"));
        assertFalse(UnlockLicenseSimulator.isUnlockLicenseMethod("flight_areas_update"));
        assertFalse(UnlockLicenseSimulator.isUnlockLicenseMethod("camera_photo_take"));
    }

    // ==================== 补充：resetLicenses 清空状态 ====================

    @Test
    void resetClearsLicenses() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();
        simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": true}"));
        simulator.handleSwitch(data("{\"license_id\": 240331, \"enable\": false}"));
        assertEquals(2, simulator.getLicenses().size());

        simulator.resetLicenses();

        assertTrue(simulator.getLicenses().isEmpty());
    }

    // ==================== 补充：switch 同一 license_id 覆盖状态 ====================

    @Test
    void switchSameLicenseIdOverwrites() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": true}"));
        assertEquals(true, simulator.getLicenses().get(240330));

        simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": false}"));
        assertEquals(false, simulator.getLicenses().get(240330));
        assertEquals(1, simulator.getLicenses().size(), "同一 license_id 应覆盖，不新增");
    }

    // ==================== TC-UNLOCK-006：unlock_license_list 返回 7 种证书类型 ====================

    @SuppressWarnings("unchecked")
    @Test
    void listReturnsSevenLicenseTypes() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleList(data("{\"device_model_domain\": 0}"));

        assertEquals(0, output.get("result"));
        assertEquals(0, output.get("device_model_domain"));
        assertEquals(true, output.get("consistence"));

        List<Map<String, Object>> licenses = (List<Map<String, Object>>) output.get("licenses");
        assertEquals(7, licenses.size(), "应返回 7 种证书类型");

        for (int i = 0; i < 7; i++) {
            Map<String, Object> common = (Map<String, Object>) licenses.get(i).get("common_fields");
            assertEquals(i, common.get("type"), "证书 type 应为 " + i);
            assertEquals(240330 + i, common.get("license_id"));
            assertNotNull(common.get("name"));
            assertNotNull(common.get("group_id"));
            assertNotNull(common.get("user_id"));
            assertNotNull(common.get("device_sn"));
            assertNotNull(common.get("begin_time"));
            assertNotNull(common.get("end_time"));
            assertNotNull(common.get("user_only"));
            assertNotNull(common.get("enabled"));
        }
    }

    // ==================== TC-UNLOCK-007：unlock_license_list 回显 device_model_domain ====================

    @Test
    void listEchoesDeviceModelDomain() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleList(data("{\"device_model_domain\": 3}"));

        assertEquals(3, output.get("device_model_domain"), "应回显请求的 device_model_domain");
    }

    // ==================== TC-UNLOCK-008：switch 修改的 enabled 状态反映到 list ====================

    @SuppressWarnings("unchecked")
    @Test
    void switchEnabledReflectsInList() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        // 先启用 license_id=240330
        simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": true}"));

        // 查询列表
        Map<String, Object> output = simulator.handleList(data("{\"device_model_domain\": 0}"));
        List<Map<String, Object>> licenses = (List<Map<String, Object>>) output.get("licenses");

        // license_id=240330 应为 enabled=true
        Map<String, Object> common0 = (Map<String, Object>) licenses.get(0).get("common_fields");
        assertEquals(240330, common0.get("license_id"));
        assertEquals(true, common0.get("enabled"), "switch 启用后 list 应反映 enabled=true");

        // 其他证书应为 enabled=false（默认）
        Map<String, Object> common1 = (Map<String, Object>) licenses.get(1).get("common_fields");
        assertEquals(false, common1.get("enabled"), "未 switch 的证书应为 enabled=false");
    }

    // ==================== TC-UNLOCK-009：各类型 unlock 结构完整性 ====================

    @SuppressWarnings("unchecked")
    @Test
    void listUnlockStructureCompleteness() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();
        Map<String, Object> output = simulator.handleList(data("{\"device_model_domain\": 0}"));
        List<Map<String, Object>> licenses = (List<Map<String, Object>>) output.get("licenses");

        // type=0 授权区
        Map<String, Object> areaUnlock = (Map<String, Object>) licenses.get(0).get("area_unlock");
        assertNotNull(areaUnlock);
        assertNotNull(areaUnlock.get("area_ids"));

        // type=1 圆形
        Map<String, Object> circleUnlock = (Map<String, Object>) licenses.get(1).get("circle_unlock");
        assertNotNull(circleUnlock);
        assertNotNull(circleUnlock.get("radius"));
        assertNotNull(circleUnlock.get("latitude"));
        assertNotNull(circleUnlock.get("longitude"));
        assertNotNull(circleUnlock.get("height"));

        // type=2 国家
        Map<String, Object> countryUnlock = (Map<String, Object>) licenses.get(2).get("country_unlock");
        assertNotNull(countryUnlock);
        assertNotNull(countryUnlock.get("country_number"));
        assertNotNull(countryUnlock.get("height"));

        // type=3 限高
        Map<String, Object> heightUnlock = (Map<String, Object>) licenses.get(3).get("height_unlock");
        assertNotNull(heightUnlock);
        assertNotNull(heightUnlock.get("height"));

        // type=4 多边形
        Map<String, Object> polygonUnlock = (Map<String, Object>) licenses.get(4).get("polygon_unlock");
        assertNotNull(polygonUnlock);
        assertNotNull(polygonUnlock.get("points"));

        // type=5 功率（空 struct）
        Map<String, Object> powerUnlock = (Map<String, Object>) licenses.get(5).get("power_unlock");
        assertNotNull(powerUnlock, "power_unlock 应存在（空 struct）");

        // type=6 RID
        Map<String, Object> ridUnlock = (Map<String, Object>) licenses.get(6).get("rid_unlock");
        assertNotNull(ridUnlock);
        assertNotNull(ridUnlock.get("level"));
    }

    // ==================== TC-UNLOCK-010：switch 不同 license_id 状态独立 ====================

    @Test
    void switchDifferentLicenseIdsAreIndependent() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": true}"));
        simulator.handleSwitch(data("{\"license_id\": 240331, \"enable\": false}"));

        assertEquals(true, simulator.getLicenses().get(240330));
        assertEquals(false, simulator.getLicenses().get(240331));
        assertEquals(2, simulator.getLicenses().size(), "两个 license_id 应独立维护");
    }

    // ==================== TC-UNLOCK-011：reset 后 list 的 enabled 恢复默认 false ====================

    @SuppressWarnings("unchecked")
    @Test
    void resetRestoresListEnabledToDefault() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();
        simulator.handleSwitch(data("{\"license_id\": 240330, \"enable\": true}"));

        simulator.resetLicenses();

        Map<String, Object> output = simulator.handleList(data("{\"device_model_domain\": 0}"));
        List<Map<String, Object>> licenses = (List<Map<String, Object>>) output.get("licenses");
        for (Map<String, Object> license : licenses) {
            Map<String, Object> common = (Map<String, Object>) license.get("common_fields");
            assertEquals(false, common.get("enabled"),
                    "reset 后所有证书 enabled 应恢复默认 false");
        }
    }

    // ==================== TC-UNLOCK-012：list 缺省 device_model_domain 默认 0 ====================

    @Test
    void listDefaultsDeviceModelDomainToZero() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleList(data("{}"));

        assertEquals(0, output.get("device_model_domain"), "device_model_domain 缺省时应默认 0");
        assertEquals(0, output.get("result"));
    }

    // ==================== TC-UNLOCK-013：unlock_license_update file 显式 null ====================

    @Test
    void updateWithExplicitNullFile() throws Exception {
        UnlockLicenseSimulator simulator = new UnlockLicenseSimulator();

        Map<String, Object> output = simulator.handleUpdate(data("{\"file\": null}"));

        assertEquals(0, output.get("result"));
        assertEquals(1, output.size(), "update reply 应仅含 result");
    }
}
