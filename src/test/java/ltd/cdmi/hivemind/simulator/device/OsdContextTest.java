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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import ltd.cdmi.hivemind.simulator.device.osd.OsdContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OsdContext.getSelectedPayload() 单元测试。
 * <p>验证相机选择链路核心逻辑：用户选择负载时优先返回，未选择时回退 defaultCameraFor()。
 */
class OsdContextTest {

    private OsdContext ctx(DroneModel droneType, PayloadType selectedPayload) {
        DeviceState state = Mockito.mock(DeviceState.class);
        RuntimeConfig config = Mockito.mock(RuntimeConfig.class);
        Mockito.when(config.getDroneType()).thenReturn(droneType);
        Mockito.lenient().when(config.getSelectedPayload()).thenReturn(selectedPayload);
        SimulatorProperties props = Mockito.mock(SimulatorProperties.class);
        return new OsdContext(state, props, config);
    }

    @DisplayName("TC-BUILDER-006：OsdContext 封装依赖 — 用户选择负载时优先返回")
    @Test
    void getSelectedPayloadReturnsUserSelectionWhenSet() {
        // M400 可挂载通用云台，用户选择 H20 → 应返回 H20
        OsdContext ctx = ctx(DroneModel.M400, PayloadType.H20);
        assertEquals(PayloadType.H20, ctx.getSelectedPayload(), "用户选择了 H20 时应返回 H20");
    }

    @DisplayName("TC-BUILDER-006：OsdContext 封装依赖 — 未选择时回退默认主相机（M30→M30_CAMERA）")
    @Test
    void getSelectedPayloadFallsBackToDefaultWhenNotSet() {
        // M30 有内置主相机 M30_CAMERA，未选择时应回退
        OsdContext ctx = ctx(DroneModel.M30, null);
        assertEquals(PayloadType.M30_CAMERA, ctx.getSelectedPayload(), "M30 未选择负载时应回退到 M30_CAMERA");
    }

    @DisplayName("TC-BUILDER-006：OsdContext 封装依赖 — 未选择时回退默认主相机（M400→H30）")
    @Test
    void getSelectedPayloadFallsBackToH30ForM400() {
        // M400 默认搭载 H30（defaultCameraFor 特殊处理），未选择时应回退到 H30
        OsdContext ctx = ctx(DroneModel.M400, null);
        assertEquals(PayloadType.H30, ctx.getSelectedPayload(), "M400 未选择负载时应回退到 H30");
    }

    @DisplayName("TC-BUILDER-006：OsdContext 封装依赖 — 未选择时回退默认主相机（M350/M300→H20）")
    @Test
    void getSelectedPayloadFallsBackToH20ForM350AndM300() {
        // M350 RTK / M300 RTK 默认搭载 H20 通用云台负载（defaultCameraFor 特殊处理），未选择时应回退到 H20
        OsdContext ctx350 = ctx(DroneModel.M350_RTK, null);
        assertEquals(PayloadType.H20, ctx350.getSelectedPayload(), "M350_RTK 未选择负载时应回退到 H20");
        OsdContext ctx300 = ctx(DroneModel.M300_RTK, null);
        assertEquals(PayloadType.H20, ctx300.getSelectedPayload(), "M300_RTK 未选择负载时应回退到 H20");
    }

    @DisplayName("TC-BUILDER-006：OsdContext 封装依赖 — 无默认且未选择时返回 null")
    @Test
    void getSelectedPayloadReturnsNullWhenNoDefaultAndNoSelection() {
        // MAVIC_3TA 无内置主相机（无对应 PayloadType 枚举，无特殊处理），未选择时 defaultCameraFor 返回 null
        OsdContext ctx = ctx(DroneModel.MAVIC_3TA, null);
        assertNull(ctx.getSelectedPayload(), "MAVIC_3TA 未选择负载且无默认主相机时应返回 null");
    }
}
