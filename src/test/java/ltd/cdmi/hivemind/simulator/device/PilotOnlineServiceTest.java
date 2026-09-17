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

import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import ltd.cdmi.dji.cloudapi.sdk.model.PayloadType;
import ltd.cdmi.dji.cloudapi.sdk.model.RcModel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.device.osd.DroneStateBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.MatriceStateBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M4StateBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.Mavic3StateBuilder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.MapElementSimulator;
import ltd.cdmi.hivemind.simulator.handler.SituationAwarenessSimulator;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PilotOnlineService 单元测试。
 * <p>验证遥控器 state 字段集（TC-ONLINE-009）：dongle_infos/live_status/firmware_version/cloud_control_auth。
 * <p>验证飞行器 state 委托逻辑（TC-ONLINE-010/011）：publishDroneState 委托给 DroneStateBuilder。
 * <p>核实依据：用户提供的 RC Plus 2 行业版 + Mavic 3 + Matrice 4 系列设备属性列表。
 */
class PilotOnlineServiceTest {

    private MqttClientManager mqtt;
    private DeviceState state;
    private ObjectMapper objectMapper;
    private RuntimeConfig runtimeConfig;
    private DiagnosticLogRecorder diagnosticRecorder;
    private List<DroneStateBuilder> stateBuilders;
    private PilotOnlineService service;

    @BeforeEach
    void setUp() {
        mqtt = Mockito.mock(MqttClientManager.class);
        state = Mockito.mock(DeviceState.class);
        objectMapper = new ObjectMapper();
        runtimeConfig = Mockito.mock(RuntimeConfig.class);
        diagnosticRecorder = Mockito.mock(DiagnosticLogRecorder.class);
        when(runtimeConfig.getControllerSn()).thenReturn("RC-TEST-SN");
        when(runtimeConfig.getDroneSn()).thenReturn("DRONE-TEST-SN");
        when(runtimeConfig.getControllerType()).thenReturn(RcModel.RC_PLUS_2);
        when(runtimeConfig.getDroneType()).thenReturn(DroneModel.MAVIC_3E);
        when(runtimeConfig.getLocationLatitude()).thenReturn(22.5);
        when(runtimeConfig.getLocationLongitude()).thenReturn(113.9);
        stateBuilders = List.of(new Mavic3StateBuilder(), new M4StateBuilder(), new MatriceStateBuilder());
        service = new PilotOnlineService(mqtt, state, objectMapper, runtimeConfig, diagnosticRecorder, stateBuilders, mock(MapElementSimulator.class), mock(SituationAwarenessSimulator.class));
    }

    /** 捕获 publishJson 的 envelope 参数并转为 JsonNode */
    private JsonNode capturePublishedData() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mqtt).publishJson(anyString(), payloadCaptor.capture());
        return objectMapper.valueToTree(payloadCaptor.getValue()).path("data");
    }

    @DisplayName("补充测试：遥控器 state 包含全部 pushMode=1 字段")
    @Test
    void controllerStateContainsAllPushMode1Fields() {
        service.publishControllerState();

        JsonNode data = capturePublishedData();
        assertTrue(data.has("dongle_infos"), "state 应包含 dongle_infos (pushMode=1)");
        assertTrue(data.has("live_status"), "state 应包含 live_status (pushMode=1)");
        assertTrue(data.has("firmware_version"), "state 应包含 firmware_version (pushMode=1)");
        assertTrue(data.has("cloud_control_auth"), "state 应包含 cloud_control_auth (pushMode=1)");
    }

    @DisplayName("补充测试：dongle_infos 结构符合 DJI 规格")
    @Test
    void dongleInfosStructureMatchesDjiSpec() {
        service.publishControllerState();

        JsonNode dongleInfos = capturePublishedData().path("dongle_infos");
        assertTrue(dongleInfos.isArray(), "dongle_infos 应为 array");
        assertEquals(1, dongleInfos.size(), "默认 1 个 Dongle");

        JsonNode dongle = dongleInfos.get(0);
        assertTrue(dongle.has("imei"), "dongle 应包含 imei");
        assertTrue(dongle.has("dongle_type"), "dongle 应包含 dongle_type");
        assertTrue(dongle.has("eid"), "dongle 应包含 eid");
        assertTrue(dongle.has("esim_activate_state"), "dongle 应包含 esim_activate_state");
        assertTrue(dongle.has("sim_card_state"), "dongle 应包含 sim_card_state");
        assertTrue(dongle.has("sim_slot"), "dongle 应包含 sim_slot");
        assertTrue(dongle.has("esim_infos"), "dongle 应包含 esim_infos");
        assertTrue(dongle.has("sim_info"), "dongle 应包含 sim_info");

        // esim_infos 子结构
        JsonNode esimInfos = dongle.path("esim_infos");
        assertTrue(esimInfos.isArray(), "esim_infos 应为 array");
        JsonNode esim = esimInfos.get(0);
        assertTrue(esim.has("telecom_operator"), "esim 应包含 telecom_operator");
        assertTrue(esim.has("enabled"), "esim 应包含 enabled");
        assertTrue(esim.has("iccid"), "esim 应包含 iccid");

        // sim_info 子结构
        JsonNode simInfo = dongle.path("sim_info");
        assertTrue(simInfo.has("telecom_operator"), "sim_info 应包含 telecom_operator");
        assertTrue(simInfo.has("sim_type"), "sim_info 应包含 sim_type");
        assertTrue(simInfo.has("iccid"), "sim_info 应包含 iccid");
    }

    @DisplayName("补充测试：live_status 默认空数组")
    @Test
    void liveStatusDefaultsToEmptyArray() {
        service.publishControllerState();

        JsonNode liveStatus = capturePublishedData().path("live_status");
        assertTrue(liveStatus.isArray(), "live_status 应为 array");
        assertEquals(0, liveStatus.size(), "无直播时 live_status 为空数组");
    }

    @DisplayName("补充测试：cloud_control_auth 默认空数组")
    @Test
    void cloudControlAuthDefaultsToEmptyArray() {
        service.publishControllerState();

        JsonNode cloudControlAuth = capturePublishedData().path("cloud_control_auth");
        assertTrue(cloudControlAuth.isArray(), "cloud_control_auth 应为 array");
        assertEquals(0, cloudControlAuth.size(), "无授权时 cloud_control_auth 为空数组");
    }

    @DisplayName("补充测试：firmware_version 为字符串类型")
    @Test
    void firmwareVersionIsString() {
        service.publishControllerState();

        JsonNode firmwareVersion = capturePublishedData().path("firmware_version");
        assertTrue(firmwareVersion.isTextual(), "firmware_version 应为 text");
        assertEquals("0.0.0.0", firmwareVersion.asText(), "默认固件版本 0.0.0.0");
    }

    @DisplayName("补充测试：state 上报到遥控器 SN 的 state Topic")
    @Test
    void statePublishedToControllerSnStateTopic() {
        service.publishControllerState();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqtt).publishJson(topicCaptor.capture(), any());
        assertTrue(topicCaptor.getValue().contains("RC-TEST-SN"), "topic 应包含 controllerSn");
        assertTrue(topicCaptor.getValue().endsWith("/state"), "topic 应以 /state 结尾");
    }

    // ===== 飞行器 state 委托逻辑测试（TC-ONLINE-010/011）=====

    @DisplayName("补充测试：Mavic 3 飞行器 state 字段集")
    @Test
    void droneStateMavic3ContainsAllPushMode1Fields() {
        // setUp 中 droneType=MAVIC_3E，使用 Mavic3StateBuilder
        service.publishDroneState();

        JsonNode data = capturePublishedData();
        assertTrue(data.has("mode_code_reason"), "Mavic 3 drone state 应包含 mode_code_reason");
        assertTrue(data.has("dongle_infos"), "Mavic 3 drone state 应包含 dongle_infos");
        assertTrue(data.has("serious_low_battery_warning_threshold"));
        assertTrue(data.has("low_battery_warning_threshold"));
        assertTrue(data.has("control_source"));
        assertTrue(data.has("home_latitude"));
        assertTrue(data.has("home_longitude"));
        assertTrue(data.has("firmware_upgrade_status"));
        assertTrue(data.has("compatible_status"));
        assertTrue(data.has("firmware_version"), "Mavic 3 firmware_version pushMode=1，在 state 上报");
        assertTrue(data.has("camera_watermark_settings"));
    }

    @DisplayName("补充测试：M4 飞行器 state 字段集")
    @Test
    void droneStateM4ExcludesFirmwareVersionAndIncludesCommanderFields() {
        when(runtimeConfig.getDroneType()).thenReturn(DroneModel.M4E);
        service.publishDroneState();

        JsonNode data = capturePublishedData();
        // Matrice 4 系列特有字段（pushMode=1）
        assertTrue(data.has("offline_map_enable"), "M4E state 应包含 offline_map_enable");
        assertTrue(data.has("current_rth_mode"), "M4E state 应包含 current_rth_mode");
        assertTrue(data.has("rth_mode"), "M4E state 应包含 rth_mode");
        assertTrue(data.has("commander_flight_height"), "M4E state 应包含 commander_flight_height");
        assertTrue(data.has("commander_flight_mode"), "M4E state 应包含 commander_flight_mode");
        assertTrue(data.has("current_commander_flight_mode"), "M4E state 应包含 current_commander_flight_mode");
        assertTrue(data.has("commander_mode_lost_action"), "M4E state 应包含 commander_mode_lost_action");
        // Matrice 4 系列不含 firmware_version（pushMode=0，在 OSD 上报）
        assertFalse(data.has("firmware_version"), "M4E firmware_version pushMode=0，不应在 state 上报");
        // Matrice 4 系列不含 payloads/wpmz_version/psdk_*（属性列表未列）
        assertFalse(data.has("payloads"), "Matrice 4 系列属性列表未列 payloads");
        assertFalse(data.has("wpmz_version"), "Matrice 4 系列属性列表未列 wpmz_version");
    }

    @DisplayName("补充测试：无对应 Builder 时跳过 state 上报")
    @Test
    void droneStateSkipsWhenNoBuilderForDroneType() {
        // MAVIC_3TA 无对应 StateBuilder（无对应 PayloadType 枚举，无特殊处理），应跳过 state 上报
        when(runtimeConfig.getDroneType()).thenReturn(DroneModel.MAVIC_3TA);
        service.publishDroneState();

        verify(mqtt, never()).publishJson(anyString(), any());
    }

    @DisplayName("补充测试：M350 默认 H20 负载字段")
    @Test
    void droneStateM350IncludesPayloadFieldsWithDefaultH20() {
        // TC-ONLINE-012：M350_RTK 默认搭载 H20，state 应包含负载字段 payload_index
        when(runtimeConfig.getDroneType()).thenReturn(DroneModel.M350_RTK);
        when(runtimeConfig.getSelectedPayload()).thenReturn(null); // 回退到默认 H20
        service.publishDroneState();

        JsonNode data = capturePublishedData();
        // M350 RTK state 包含 Mavic 3 共有字段
        assertTrue(data.has("mode_code_reason"), "M350_RTK state 应包含 mode_code_reason");
        assertTrue(data.has("firmware_version"), "M350_RTK state 应包含 firmware_version");
        // 默认 H20 负载索引 42-0-0
        assertTrue(data.has("42-0-0"), "M350_RTK state 应包含 H20 负载字段（42-0-0）");
        JsonNode payload = data.path("42-0-0");
        assertTrue(payload.has("payload_index"), "负载字段应包含 payload_index");
    }

    @DisplayName("补充测试：M350 选择 H20T 负载字段")
    @Test
    void droneStateM350WithH20TPayloadIndex() {
        // TC-ONLINE-012：M350_RTK 用户选择 H20T 时，state 应包含 H20T 负载字段（43-0-0）
        when(runtimeConfig.getDroneType()).thenReturn(DroneModel.M350_RTK);
        when(runtimeConfig.getSelectedPayload()).thenReturn(PayloadType.H20T);
        service.publishDroneState();

        JsonNode data = capturePublishedData();
        // H20T 负载索引 43-0-0
        JsonNode payload = data.path("43-0-0");
        assertTrue(payload.has("payload_index"), "H20T 负载字段应包含 payload_index");
        assertEquals("43-0-0", payload.path("payload_index").asText(), "payload_index 应为 43-0-0");
    }

    @DisplayName("补充测试：飞行器 state 上报到 drone SN 的 state Topic")
    @Test
    void droneStatePublishedToDroneSnStateTopic() {
        service.publishDroneState();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqtt).publishJson(topicCaptor.capture(), any());
        assertTrue(topicCaptor.getValue().contains("DRONE-TEST-SN"), "topic 应包含 droneSn");
        assertTrue(topicCaptor.getValue().endsWith("/state"), "topic 应以 /state 结尾");
    }

    // ===== TC-ONLINE-016：RC Plus 2 update_topo 差异化测试 =====

    /**
     * TC-ONLINE-016：RC Plus 2 的 update_topo 网关设备和子设备不包含 domain，子设备不包含 index。
     * <p>setUp 中 controllerType=RC_PLUS_2，通过 resendTopo() 触发 update_topo 上报。</p>
     */
    @DisplayName("补充测试：RC Plus 2 update_topo 省略 domain/index")
    @Test
    void rcPlus2UpdateTopoOmitsDomainAndIndex() {
        when(state.isOnline()).thenReturn(true);
        service.resendTopo();

        JsonNode data = capturePublishedData();
        // 网关设备不包含 domain
        assertFalse(data.has("domain"), "RC Plus 2 网关设备不应包含 domain");
        assertTrue(data.has("type"), "网关设备应包含 type");
        assertTrue(data.has("sub_type"), "网关设备应包含 sub_type");

        // 子设备不包含 domain 和 index
        JsonNode subDevices = data.path("sub_devices");
        assertTrue(subDevices.isArray() && subDevices.size() == 1, "应有 1 个子设备");
        JsonNode subDevice = subDevices.get(0);
        assertFalse(subDevice.has("domain"), "RC Plus 2 子设备不应包含 domain");
        assertFalse(subDevice.has("index"), "RC Plus 2 子设备不应包含 index");
        assertTrue(subDevice.has("sn"), "子设备应包含 sn");
        assertTrue(subDevice.has("type"), "子设备应包含 type");
        assertTrue(subDevice.has("sub_type"), "子设备应包含 sub_type");
    }

    /**
     * TC-ONLINE-016：RC Plus 2 的 update_topo 使用 thing/product/{sn}/status Topic。
     */
    @DisplayName("补充测试：RC Plus 2 update_topo 使用 thing/product Topic")
    @Test
    void rcPlus2UpdateTopoUsesThingProductTopic() {
        when(state.isOnline()).thenReturn(true);
        service.resendTopo();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqtt).publishJson(topicCaptor.capture(), any());
        String topic = topicCaptor.getValue();
        assertTrue(topic.startsWith("thing/product/"), "RC Plus 2 Topic 应以 thing/product/ 开头");
        assertTrue(topic.endsWith("/status"), "Topic 应以 /status 结尾");
    }

    /**
     * TC-ONLINE-016：其他 Pilot 机型（RC_PLUS）的 update_topo 包含 domain（网关和子设备）和 index（子设备）。
     */
    @DisplayName("补充测试：RC Plus update_topo 包含 domain/index")
    @Test
    void otherPilotUpdateTopoIncludesDomainAndIndex() {
        when(state.isOnline()).thenReturn(true);
        when(runtimeConfig.getControllerType()).thenReturn(RcModel.RC_PLUS);
        // 重新创建 service，使 topicSchema 使用新的 controllerType
        service = new PilotOnlineService(mqtt, state, objectMapper, runtimeConfig, diagnosticRecorder, stateBuilders, mock(MapElementSimulator.class), mock(SituationAwarenessSimulator.class));
        service.resendTopo();

        JsonNode data = capturePublishedData();
        // 网关设备包含 domain
        assertTrue(data.has("domain"), "RC Plus 网关设备应包含 domain");
        assertTrue(data.has("type"), "网关设备应包含 type");
        assertTrue(data.has("sub_type"), "网关设备应包含 sub_type");

        // 子设备包含 domain 和 index
        JsonNode subDevices = data.path("sub_devices");
        assertTrue(subDevices.isArray() && subDevices.size() == 1, "应有 1 个子设备");
        JsonNode subDevice = subDevices.get(0);
        assertTrue(subDevice.has("domain"), "RC Plus 子设备应包含 domain");
        assertTrue(subDevice.has("index"), "RC Plus 子设备应包含 index");
    }

    /**
     * TC-ONLINE-016：其他 Pilot 机型（RC_PLUS）的 update_topo 使用 sys/product/{sn}/status Topic。
     */
    @DisplayName("补充测试：RC Plus update_topo 使用 sys/product Topic")
    @Test
    void otherPilotUpdateTopoUsesSysProductTopic() {
        when(state.isOnline()).thenReturn(true);
        when(runtimeConfig.getControllerType()).thenReturn(RcModel.RC_PLUS);
        // 重新创建 service，使 topicSchema 使用新的 controllerType
        service = new PilotOnlineService(mqtt, state, objectMapper, runtimeConfig, diagnosticRecorder, stateBuilders, mock(MapElementSimulator.class), mock(SituationAwarenessSimulator.class));
        service.resendTopo();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqtt).publishJson(topicCaptor.capture(), any());
        String topic = topicCaptor.getValue();
        assertTrue(topic.startsWith("sys/product/"), "RC Plus Topic 应以 sys/product/ 开头");
        assertTrue(topic.endsWith("/status"), "Topic 应以 /status 结尾");
    }
}
