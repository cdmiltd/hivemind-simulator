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

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.device.DockOnlineService;
import ltd.cdmi.hivemind.simulator.device.PilotOnlineService;
import ltd.cdmi.hivemind.simulator.diagnostic.CoverageRecorder;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import ltd.cdmi.hivemind.simulator.handler.*;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.web.SimulatorController;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatorController OSD 日志导出接口单元测试。
 * <p>覆盖 TC-LOG-001~005：按 SN 过滤、按 direction 过滤、仅保留 OSD 数据、limit 限制、多 SN 支持。
 */
class OsdExportTest {

    private static final String SN_DOCK3 = "7UUXN1Q00A008W";
    private static final String SN_OTHER = "1081F8HGD25110010059";

    /** 构造一条日志条目 */
    private static Map<String, Object> logEntry(String direction, String topic, String payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", "12:00:00.000");
        entry.put("direction", direction);
        entry.put("topic", topic);
        entry.put("method", "");
        entry.put("payload", payload);
        return entry;
    }

    /** OSD 上报 payload（含 latitude） */
    private static String osdPayloadWithLatitude() {
        return "{\"method\":\"update_topo\",\"data\":{\"latitude\":22.5,\"longitude\":113.9}}";
    }

    /** OSD 上报 payload（含 sub_device） */
    private static String osdPayloadWithSubDevice() {
        return "{\"method\":\"update_topo\",\"data\":{\"sub_device\":{\"sn\":\"DRONE-SN\"}}}";
    }

    /** services 指令 payload（无 latitude/sub_device） */
    private static String servicePayload() {
        return "{\"method\":\"flight_areas_update\",\"data\":null}";
    }

    @SuppressWarnings("unchecked")
    private SimulatorController newController(List<Map<String, Object>> logs) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        Mockito.when(mqtt.getLogs()).thenReturn(logs);

        SimulatorProperties props = Mockito.mock(SimulatorProperties.class);

        return new SimulatorController(
                Mockito.mock(DockOnlineService.class),
                Mockito.mock(PilotOnlineService.class),
                Mockito.mock(DeviceState.class),
                mqtt,
                Mockito.mock(WaylineTaskSimulator.class),
                Mockito.mock(LiveStreamSimulator.class),
                Mockito.mock(MediaUploadSimulator.class),
                Mockito.mock(HmsSimulator.class),
                Mockito.mock(AirSenseSimulator.class),
                Mockito.mock(FlightAreaSimulator.class),
                Mockito.mock(UnlockLicenseSimulator.class),
                Mockito.mock(PsdkSimulator.class),
                Mockito.mock(EsdkSimulator.class),
                Mockito.mock(RemoteLogSimulator.class),
                Mockito.mock(OtaSimulator.class),
                Mockito.mock(FlightCommandSimulator.class),
                Mockito.mock(FfmpegWhipPusher.class),
                Mockito.mock(FfmpegInstaller.class),
                Mockito.mock(RuntimeConfig.class),
                props,
                Mockito.mock(DiagnosticLogRecorder.class),
                Mockito.mock(CoverageRecorder.class),
                new ObjectMapper(),
                Mockito.mock(MapElementSimulator.class),
                Mockito.mock(SituationAwarenessSimulator.class),
                Mockito.mock(ltd.cdmi.hivemind.simulator.handler.PilotHttpSimulator.class),
                Mockito.mock(ltd.cdmi.hivemind.simulator.ws.MopClient.class)
        );
    }

    // ==================== TC-LOG-001：按 SN 过滤 ====================

    @Test
    void filterBySn() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/" + SN_OTHER + "/osd", osdPayloadWithLatitude()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportOsdLogs(SN_DOCK3, "send", 200);

        assertEquals(1, result.size(), "应只返回 SN_DOCK3 的日志");
        assertTrue(result.get(0).get("topic").toString().contains(SN_DOCK3));
    }

    // ==================== TC-LOG-002：按 direction 过滤 ====================

    @Test
    void filterByDirection() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("recv", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportOsdLogs(SN_DOCK3, "send", 200);

        assertEquals(1, result.size(), "应只返回 direction=send 的日志");
    }

    // ==================== TC-LOG-003：仅保留 OSD 数据 ====================

    @Test
    @SuppressWarnings("unchecked")
    void onlyOsdData() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/services", servicePayload()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportOsdLogs(SN_DOCK3, "send", 200);

        assertEquals(1, result.size(), "应只保留含 latitude/sub_device 的 OSD 数据");
        Map<String, Object> item = result.get(0);
        assertNotNull(item.get("topic"));
        assertNotNull(item.get("data"));
        Map<String, Object> data = (Map<String, Object>) item.get("data");
        assertNotNull(data.get("latitude"), "data 应含 latitude 字段");
    }

    // ==================== TC-LOG-004：limit 限制条数 ====================

    @Test
    void limitResults() {
        List<Map<String, Object>> logs = new ArrayList<>();
        // 添加 5 条 OSD 日志
        for (int i = 0; i < 5; i++) {
            logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        }

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportOsdLogs(SN_DOCK3, "send", 3);

        assertEquals(3, result.size(), "应只返回最新 3 条");
    }

    // ==================== TC-LOG-005：多 SN 支持 ====================

    @Test
    void multipleSn() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/" + SN_OTHER + "/osd", osdPayloadWithSubDevice()));
        logs.add(logEntry("send", "thing/product/UNKNOWN_SN/osd", osdPayloadWithLatitude()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportOsdLogs(
                SN_DOCK3 + "," + SN_OTHER, "send", 200);

        assertEquals(2, result.size(), "应返回两个 SN 的日志合并");
    }
}
