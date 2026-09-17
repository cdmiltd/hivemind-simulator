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
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.osd.Dock3OsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.M4DDroneOsdBuilder;
import ltd.cdmi.hivemind.simulator.device.osd.OsdContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 飞行器 OSD wind_speed 与机场 OSD wind_speed 字段分离单元测试（TC-BUILDER-014-WIND）。
 * <p>验证：
 * <ul>
 *   <li>飞行器 OSD wind_speed 用 DeviceState.droneWindSpeed（飞行器环境风速）</li>
 *   <li>机场 OSD wind_speed 用 DeviceState.windSpeed（机场环境风速）</li>
 *   <li>两者独立可配，互不影响</li>
 * </ul>
 * <p>核实依据：DJI M30 properties 文档机场 OSD wind_speed（机场环境）与飞行器 OSD wind_speed（飞行器环境）是两个独立字段。
 */
class DroneWindSpeedSeparationTest {

    /** 机场环境风速 */
    private static final double DOCK_WIND_SPEED = 3.0;
    /** 飞行器环境风速（与机场不同，验证分离） */
    private static final double DRONE_WIND_SPEED = 8.0;

    private OsdContext ctx(DroneModel droneType) {
        DeviceState state = Mockito.mock(DeviceState.class);
        // 机场风速与飞行器风速设为不同值，验证分离
        Mockito.lenient().when(state.getWindSpeed()).thenReturn(DOCK_WIND_SPEED);
        Mockito.lenient().when(state.getDroneWindSpeed()).thenReturn(DRONE_WIND_SPEED);
        Mockito.lenient().when(state.getDroneLatitude()).thenReturn(22.5);
        Mockito.lenient().when(state.getDroneLongitude()).thenReturn(113.9);

        RuntimeConfig config = Mockito.mock(RuntimeConfig.class);
        Mockito.lenient().when(config.getDroneType()).thenReturn(droneType);

        SimulatorProperties props = Mockito.mock(SimulatorProperties.class);
        // 机场 OSD buildDockOsd 调用 props.location().latitude()/longitude()/height()，需设置非 null
        Mockito.lenient().when(props.location()).thenReturn(
                new SimulatorProperties.Location(22.5, 113.9, 100.0));
        return new OsdContext(state, props, config);
    }

    @Test
    @DisplayName("飞行器 OSD wind_speed 用 droneWindSpeed（=80，不是机场 windSpeed=30）")
    void droneOsdWindSpeedUsesDroneWindSpeed() {
        M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
        Map<String, Object> osd = builder.buildDroneOsd(ctx(DroneModel.M4D));

        // DJI 文档 wind_speed 单位 0.1 m/s，上报值 = droneWindSpeed * 10 = 80
        assertEquals(80.0, osd.get("wind_speed"),
                "飞行器 OSD wind_speed 应为 droneWindSpeed * 10 = 80，不是机场 windSpeed * 10 = 30");
    }

    @Test
    @DisplayName("机场 OSD wind_speed 用 windSpeed（=30，不是飞行器 droneWindSpeed=80）")
    void dockOsdWindSpeedUsesWindSpeed() {
        Dock3OsdBuilder builder = new Dock3OsdBuilder();
        List<Map<String, Object>> osdMessages = builder.buildDockOsd(ctx(DroneModel.M4D));

        // 机场 OSD 分多条消息，遍历找到含 wind_speed 的消息
        Object windSpeed = null;
        for (Map<String, Object> msg : osdMessages) {
            if (msg.containsKey("wind_speed")) {
                windSpeed = msg.get("wind_speed");
                break;
            }
        }
        assertNotNull(windSpeed, "机场 OSD 应包含 wind_speed 字段");
        // DJI 文档 wind_speed 单位 0.1 m/s，上报值 = windSpeed * 10 = 30
        assertEquals(30.0, windSpeed,
                "机场 OSD wind_speed 应为 windSpeed * 10 = 30，不是飞行器 droneWindSpeed * 10 = 80");
    }

    @Test
    @DisplayName("飞行器与机场 wind_speed 独立可配，互不影响")
    void windSpeedFieldsAreIndependent() {
        M4DDroneOsdBuilder droneBuilder = new M4DDroneOsdBuilder();
        Dock3OsdBuilder dockBuilder = new Dock3OsdBuilder();
        OsdContext ctx = ctx(DroneModel.M4D);

        Map<String, Object> droneOsd = droneBuilder.buildDroneOsd(ctx);
        List<Map<String, Object>> dockOsdMessages = dockBuilder.buildDockOsd(ctx);

        // 飞行器 wind_speed = 80（droneWindSpeed * 10）
        assertEquals(80.0, droneOsd.get("wind_speed"), "飞行器 wind_speed 应反映 droneWindSpeed");

        // 机场 wind_speed = 30（windSpeed * 10）
        Object dockWindSpeed = null;
        for (Map<String, Object> msg : dockOsdMessages) {
            if (msg.containsKey("wind_speed")) {
                dockWindSpeed = msg.get("wind_speed");
                break;
            }
        }
        assertEquals(30.0, dockWindSpeed, "机场 wind_speed 应反映 windSpeed");

        // 两者不同，证明独立
        assertNotEquals(droneOsd.get("wind_speed"), dockWindSpeed,
                "飞行器 wind_speed 与机场 wind_speed 应不同（独立字段）");
    }
}
