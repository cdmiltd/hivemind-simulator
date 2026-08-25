// Copyright (C) 2026 CDMI.LTD
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
import ltd.cdmi.hivemind.simulator.media.*;
import ltd.cdmi.hivemind.simulator.mqtt.MqttClientManager;
import ltd.cdmi.hivemind.simulator.web.SimulatorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatorController 消息日志导出接口单元测试。
 * <p>覆盖 TC-LOG-001~006：按 SN 过滤、按 direction 过滤、保留所有消息类型、limit 限制、多 SN 支持、SN 为空时不过滤。
 * <p>注：原 OSD 专用导出已重构为通用消息导出（/api/logs/export），不再按 topic 后缀过滤。
 */
class OsdExportTest {

    private static final String SN_DOCK3 = "7UUXN1Q00A008W";
    private static final String SN_OTHER = "1081F8HGD25110010059";

    /** 构造一条日志条目（含 ts 字段，与 {@link ltd.cdmi.hivemind.simulator.diagnostic.MessageLogStore} 输出格式一致） */
    private static Map<String, Object> logEntry(String direction, String topic, String payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", System.currentTimeMillis());
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

    /** services 指令 payload（无 latitude/sub_device） */
    private static String servicePayload() {
        return "{\"method\":\"flight_areas_update\",\"data\":{\"id\":\"fa-1\"}}";
    }

    @SuppressWarnings("unchecked")
    private SimulatorController newController(List<Map<String, Object>> logs) {
        MqttClientManager mqtt = Mockito.mock(MqttClientManager.class);
        // exportLogs 从 queryHistory 读取数据（limit 放大 5 倍后过滤）
        Mockito.when(mqtt.queryHistory(Mockito.isNull(), Mockito.anyInt())).thenReturn(logs);

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
                Mockito.mock(ltd.cdmi.hivemind.simulator.media.LocalFileUploadService.class),
                Mockito.mock(ltd.cdmi.hivemind.simulator.media.MediaSampleInitializer.class),
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

    @DisplayName("TC-LOG-001：消息导出按 SN 过滤")
    @Test
    void filterBySn() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/" + SN_OTHER + "/osd", osdPayloadWithLatitude()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportLogs(SN_DOCK3, "send", 200);

        assertEquals(1, result.size(), "应只返回 SN_DOCK3 的日志");
        assertTrue(result.get(0).get("topic").toString().contains(SN_DOCK3));
    }

    // ==================== TC-LOG-002：按 direction 过滤 ====================

    @DisplayName("TC-LOG-002：消息导出按 direction 过滤")
    @Test
    void filterByDirection() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("recv", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportLogs(SN_DOCK3, "send", 200);

        assertEquals(1, result.size(), "应只返回 direction=send 的日志");
    }

    // ==================== TC-LOG-003：保留所有消息类型（不仅 OSD） ====================

    @DisplayName("TC-LOG-003：消息导出保留所有消息类型")
    @Test
    @SuppressWarnings("unchecked")
    void keepAllMessageTypes() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/services", servicePayload()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportLogs(SN_DOCK3, "send", 200);

        assertEquals(2, result.size(), "应保留 OSD 和 services 等所有消息类型");
        // 每条都应含 ts/time/topic/method/data 字段
        for (Map<String, Object> item : result) {
            assertNotNull(item.get("ts"));
            assertNotNull(item.get("time"));
            assertNotNull(item.get("topic"));
            assertNotNull(item.get("method"));
            assertNotNull(item.get("data"));
        }
    }

    // ==================== TC-LOG-004：limit 限制条数 ====================

    @DisplayName("TC-LOG-004：消息导出 limit 限制条数")
    @Test
    void limitResults() {
        List<Map<String, Object>> logs = new ArrayList<>();
        // 添加 5 条日志
        for (int i = 0; i < 5; i++) {
            logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        }

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportLogs(SN_DOCK3, "send", 3);

        assertEquals(3, result.size(), "应只返回最新 3 条");
    }

    // ==================== TC-LOG-005：多 SN 支持 ====================

    @DisplayName("TC-LOG-005：消息导出多 SN 支持")
    @Test
    void multipleSn() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/" + SN_OTHER + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/UNKNOWN_SN/osd", osdPayloadWithLatitude()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportLogs(
                SN_DOCK3 + "," + SN_OTHER, "send", 200);

        assertEquals(2, result.size(), "应返回两个 SN 的日志合并");
    }

    // ==================== TC-LOG-006：SN 为空时不过滤（返回所有设备消息） ====================

    @DisplayName("TC-LOG-006：消息导出 SN 为空时不过滤")
    @Test
    void nullSnReturnsAll() {
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(logEntry("send", "thing/product/" + SN_DOCK3 + "/osd", osdPayloadWithLatitude()));
        logs.add(logEntry("send", "thing/product/" + SN_OTHER + "/osd", osdPayloadWithLatitude()));

        SimulatorController controller = newController(logs);
        List<Map<String, Object>> result = controller.exportLogs(null, "send", 200);

        assertEquals(2, result.size(), "SN 为空时应返回所有设备的消息");
    }
}
