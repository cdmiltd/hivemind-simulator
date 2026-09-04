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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DockOnlineService.buildPsdkWidgetValues 单元测试（TC-ONLINE-007-PSDK）。
 * <p>验证 drone state 中 psdk_widget_values 字段的内容：
 * <ul>
 *   <li>Dock 模式（M30/M3D/M3TD/M4D/M4TD）填充喊话器设备标识（psdk_index/psdk_name/psdk_sn）</li>
 *   <li>M400 Pilot 模式返回空数组（属性列表未列此字段，TC-ONLINE-007-A）</li>
 * </ul>
 * <p>核实依据：SDK {@code StateField.PSDK_WIDGET_VALUES} 为飞行器通用 state 字段（不区分机型），
 * DJI M30/M4D properties 文档均含此字段（psdk_index/psdk_name/psdk_sn，pushMode=1, accessMode=r）。
 * 常量值引用 {@link PsdkConstants}（单一真相源）。
 */
class DockOnlineServicePsdkWidgetTest {

    @Test
    @DisplayName("M30 填充喊话器设备标识：psdk_index=2, psdk_name=Speaker, psdk_sn=psdk_speaker_sn")
    void m30_shouldContainSpeakerDevice() {
        List<Map<String, Object>> widgets = DockOnlineService.buildPsdkWidgetValues(DroneModel.M30);
        verifySpeakerWidget(widgets);
    }

    @Test
    @DisplayName("M3D 填充喊话器设备标识")
    void m3d_shouldContainSpeakerDevice() {
        List<Map<String, Object>> widgets = DockOnlineService.buildPsdkWidgetValues(DroneModel.M3D);
        verifySpeakerWidget(widgets);
    }

    @Test
    @DisplayName("M4D 填充喊话器设备标识")
    void m4d_shouldContainSpeakerDevice() {
        List<Map<String, Object>> widgets = DockOnlineService.buildPsdkWidgetValues(DroneModel.M4D);
        verifySpeakerWidget(widgets);
    }

    @Test
    @DisplayName("M3TD 填充喊话器设备标识：psdk_index=2, psdk_name=Speaker, psdk_sn=psdk_speaker_sn")
    void m3td_shouldContainSpeakerDevice() {
        List<Map<String, Object>> widgets = DockOnlineService.buildPsdkWidgetValues(DroneModel.M3TD);
        verifySpeakerWidget(widgets);
    }

    @Test
    @DisplayName("M4TD 填充喊话器设备标识：psdk_index=2, psdk_name=Speaker, psdk_sn=psdk_speaker_sn")
    void m4td_shouldContainSpeakerDevice() {
        List<Map<String, Object>> widgets = DockOnlineService.buildPsdkWidgetValues(DroneModel.M4TD);
        verifySpeakerWidget(widgets);
    }

    @Test
    @DisplayName("M400 Pilot 模式返回空数组（属性列表未列 psdk_widget_values）")
    void m400_shouldReturnEmptyList() {
        List<Map<String, Object>> widgets = DockOnlineService.buildPsdkWidgetValues(DroneModel.M400);
        assertNotNull(widgets, "M400 psdk_widget_values 不应为 null");
        assertTrue(widgets.isEmpty(), "M400 psdk_widget_values 应为空数组");
    }

    /** 验证喊话器设备标识的三个字段，对齐 DJI M30 properties 文档定义 */
    private static void verifySpeakerWidget(List<Map<String, Object>> widgets) {
        assertNotNull(widgets, "psdk_widget_values 不应为 null");
        assertEquals(1, widgets.size(), "psdk_widget_values 应包含 1 个喊话器设备");
        Map<String, Object> speaker = widgets.get(0);
        assertEquals(2, speaker.get("psdk_index"), "psdk_index 应为 2（喊话器）");
        assertEquals("Speaker", speaker.get("psdk_name"), "psdk_name 应为 Speaker");
        assertEquals("psdk_speaker_sn", speaker.get("psdk_sn"), "psdk_sn 应为 psdk_speaker_sn");
        // 对齐 DJI M30 properties 文档：仅含 psdk_index/psdk_name/psdk_sn 三个字段
        assertEquals(3, speaker.size(), "psdk_widget_values 元素应仅含 psdk_index/psdk_name/psdk_sn 三个字段");
    }
}
