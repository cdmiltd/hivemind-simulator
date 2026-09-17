// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the the implied warranty of
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OSD 构建器边界情况单元测试。
 * <p>覆盖 wind_speed / batteryPercent / windDirection 在边界值下的 OSD 输出正确性，
 * 以及 DeviceState 字段默认值验证。
 * <p>核实依据：DJI M30 properties 文档 wind_speed 单位 0.1 m/s（上报值 ×10 转换），
 * capacity_percent 单位 %，wind_direction 枚举 1-8（1=正北…8=西北）。
 */
class OsdBoundaryTest {

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getBattery(Map<String, Object> droneOsd) {
        return (Map<String, Object>) droneOsd.get("battery");
    }

    private static Object findFieldInDockOsd(List<Map<String, Object>> dockOsdMessages, String field) {
        for (Map<String, Object> msg : dockOsdMessages) {
            if (msg.containsKey(field)) {
                return msg.get(field);
            }
        }
        return null;
    }

    private OsdContext ctxWith(double windSpeed, double droneWindSpeed,
                                int batteryPercent, int windDirection) {
        DeviceState state = Mockito.mock(DeviceState.class);
        Mockito.lenient().when(state.getWindSpeed()).thenReturn(windSpeed);
        Mockito.lenient().when(state.getDroneWindSpeed()).thenReturn(droneWindSpeed);
        Mockito.lenient().when(state.getBatteryPercent()).thenReturn(batteryPercent);
        Mockito.lenient().when(state.getWindDirection()).thenReturn(windDirection);
        Mockito.lenient().when(state.getDroneLatitude()).thenReturn(22.5);
        Mockito.lenient().when(state.getDroneLongitude()).thenReturn(113.9);

        RuntimeConfig config = Mockito.mock(RuntimeConfig.class);
        Mockito.lenient().when(config.getDroneType()).thenReturn(DroneModel.M4D);

        SimulatorProperties props = Mockito.mock(SimulatorProperties.class);
        Mockito.lenient().when(props.location()).thenReturn(
                new SimulatorProperties.Location(22.5, 113.9, 100.0));
        return new OsdContext(state, props, config);
    }

    // ==================== DeviceState 默认值 ====================

    @Nested
    @DisplayName("DeviceState 默认值")
    class DeviceStateDefaults {

        @Test
        @DisplayName("新建 DeviceState 时 windSpeed 默认 3.0")
        void windSpeedDefaultIs3() {
            DeviceState state = new DeviceState();
            assertEquals(3.0, state.getWindSpeed(), "windSpeed 默认值应为 3.0 m/s");
        }

        @Test
        @DisplayName("新建 DeviceState 时 droneWindSpeed 默认 3.0")
        void droneWindSpeedDefaultIs3() {
            DeviceState state = new DeviceState();
            assertEquals(3.0, state.getDroneWindSpeed(), "droneWindSpeed 默认值应为 3.0 m/s");
        }

        @Test
        @DisplayName("新建 DeviceState 时 windDirection 默认 1（正北）")
        void windDirectionDefaultIs1() {
            DeviceState state = new DeviceState();
            assertEquals(1, state.getWindDirection(), "windDirection 默认值应为 1（正北）");
        }

        @Test
        @DisplayName("新建 DeviceState 时 batteryPercent 默认 100")
        void batteryPercentDefaultIs100() {
            DeviceState state = new DeviceState();
            assertEquals(100, state.getBatteryPercent(), "batteryPercent 默认值应为 100");
        }

        @Test
        @DisplayName("windSpeed 和 droneWindSpeed 是独立字段，互不影响")
        void windSpeedAndDroneWindSpeedAreIndependent() {
            DeviceState state = new DeviceState();
            state.setWindSpeed(5.0);
            state.setDroneWindSpeed(10.0);
            assertEquals(5.0, state.getWindSpeed(), "windSpeed 应为 5.0");
            assertEquals(10.0, state.getDroneWindSpeed(), "droneWindSpeed 应为 10.0");
            assertNotEquals(state.getWindSpeed(), state.getDroneWindSpeed(),
                    "两字段应独立，值不同");
        }
    }

    // ==================== wind_speed 边界 ====================

    @Nested
    @DisplayName("wind_speed 边界值")
    class WindSpeedBoundary {

        @Test
        @DisplayName("droneWindSpeed=0 时飞行器 OSD wind_speed=0（无风边界）")
        void droneWindSpeedZero() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            Map<String, Object> osd = builder.buildDroneOsd(
                    ctxWith(3.0, 0.0, 100, 1));
            assertEquals(0.0, osd.get("wind_speed"),
                    "droneWindSpeed=0 时飞行器 wind_speed 应为 0");
        }

        @Test
        @DisplayName("droneWindSpeed=99.9 时飞行器 OSD wind_speed=999（极大值边界）")
        void droneWindSpeedLarge() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            Map<String, Object> osd = builder.buildDroneOsd(
                    ctxWith(3.0, 99.9, 100, 1));
            assertEquals(999.0, osd.get("wind_speed"),
                    "droneWindSpeed=99.9 时飞行器 wind_speed 应为 999（×10 转换）");
        }

        @Test
        @DisplayName("windSpeed=0 时机场 OSD wind_speed=0（无风边界）")
        void dockWindSpeedZero() {
            Dock3OsdBuilder builder = new Dock3OsdBuilder();
            List<Map<String, Object>> messages = builder.buildDockOsd(
                    ctxWith(0.0, 8.0, 100, 1));
            assertEquals(0.0, findFieldInDockOsd(messages, "wind_speed"),
                    "windSpeed=0 时机场 wind_speed 应为 0");
        }

        @Test
        @DisplayName("windSpeed=99.9 时机场 OSD wind_speed=999（极大值边界）")
        void dockWindSpeedLarge() {
            Dock3OsdBuilder builder = new Dock3OsdBuilder();
            List<Map<String, Object>> messages = builder.buildDockOsd(
                    ctxWith(99.9, 8.0, 100, 1));
            assertEquals(999.0, findFieldInDockOsd(messages, "wind_speed"),
                    "windSpeed=99.9 时机场 wind_speed 应为 999（×10 转换）");
        }

        @Test
        @DisplayName("droneWindSpeed=0 + windSpeed=10 时飞行器=0 机场=100（独立边界）")
        void droneAndDockIndependentAtZeroBoundary() {
            M4DDroneOsdBuilder droneBuilder = new M4DDroneOsdBuilder();
            Dock3OsdBuilder dockBuilder = new Dock3OsdBuilder();
            OsdContext ctx = ctxWith(10.0, 0.0, 100, 1);

            Map<String, Object> droneOsd = droneBuilder.buildDroneOsd(ctx);
            List<Map<String, Object>> dockMessages = dockBuilder.buildDockOsd(ctx);

            assertEquals(0.0, droneOsd.get("wind_speed"),
                    "飞行器 wind_speed 应为 0（droneWindSpeed=0）");
            assertEquals(100.0, findFieldInDockOsd(dockMessages, "wind_speed"),
                    "机场 wind_speed 应为 100（windSpeed=10 ×10）");
        }
    }

    // ==================== batteryPercent 边界 ====================

    @Nested
    @DisplayName("batteryPercent 边界值")
    class BatteryBoundary {

        @Test
        @DisplayName("batteryPercent=0 时 capacity_percent=0, remain_flight_time=0")
        void batteryZero() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            Map<String, Object> osd = builder.buildDroneOsd(
                    ctxWith(3.0, 3.0, 0, 1));
            Map<String, Object> battery = getBattery(osd);
            assertEquals(0, battery.get("capacity_percent"),
                    "batteryPercent=0 时 capacity_percent 应为 0");
            assertEquals(0L, battery.get("remain_flight_time"),
                    "batteryPercent=0 时 remain_flight_time 应为 0（Math.max(0, 0)）");
        }

        @Test
        @DisplayName("batteryPercent=100 时 capacity_percent=100, remain_flight_time=30")
        void batteryFull() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            Map<String, Object> osd = builder.buildDroneOsd(
                    ctxWith(3.0, 3.0, 100, 1));
            Map<String, Object> battery = getBattery(osd);
            assertEquals(100, battery.get("capacity_percent"),
                    "batteryPercent=100 时 capacity_percent 应为 100");
            assertEquals(30L, battery.get("remain_flight_time"),
                    "batteryPercent=100 时 remain_flight_time 应为 30（100*30/100）");
        }

        @Test
        @DisplayName("batteryPercent=50 时 capacity_percent=50, remain_flight_time=15")
        void batteryHalf() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            Map<String, Object> osd = builder.buildDroneOsd(
                    ctxWith(3.0, 3.0, 50, 1));
            Map<String, Object> battery = getBattery(osd);
            assertEquals(50, battery.get("capacity_percent"),
                    "batteryPercent=50 时 capacity_percent 应为 50");
            assertEquals(15L, battery.get("remain_flight_time"),
                    "batteryPercent=50 时 remain_flight_time 应为 15（50*30/100）");
        }
    }

    // ==================== windDirection 边界 ====================

    @Nested
    @DisplayName("windDirection 边界值")
    class WindDirectionBoundary {

        @Test
        @DisplayName("windDirection=1 时飞行器 OSD wind_direction=1（正北边界）")
        void windDirectionNorth() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            Map<String, Object> osd = builder.buildDroneOsd(
                    ctxWith(3.0, 3.0, 100, 1));
            assertEquals(1, osd.get("wind_direction"),
                    "windDirection=1 时 wind_direction 应为 1（正北）");
        }

        @Test
        @DisplayName("windDirection=8 时飞行器 OSD wind_direction=8（西北边界）")
        void windDirectionNorthWest() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            Map<String, Object> osd = builder.buildDroneOsd(
                    ctxWith(3.0, 3.0, 100, 8));
            assertEquals(8, osd.get("wind_direction"),
                    "windDirection=8 时 wind_direction 应为 8（西北）");
        }

        @Test
        @DisplayName("windDirection 透传不转换（DJI 枚举值 1-8 直接上报）")
        void windDirectionPassThrough() {
            M4DDroneOsdBuilder builder = new M4DDroneOsdBuilder();
            for (int dir = 1; dir <= 8; dir++) {
                Map<String, Object> osd = builder.buildDroneOsd(
                        ctxWith(3.0, 3.0, 100, dir));
                assertEquals(dir, osd.get("wind_direction"),
                        "windDirection=" + dir + " 时 wind_direction 应为 " + dir);
            }
        }
    }
}
