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

/**
 * PSDK 负载设备标识常量（单一真相源）。
 * <p>所有 PSDK 相关上报必须引用本类常量，避免分散硬编码导致不一致：
 * <ul>
 *   <li>{@link DockOnlineService#buildPsdkWidgetValues} — state topic 的 psdk_widget_values（设备声明）</li>
 *   <li>{@link DeviceSimulator#publishPsdkAndAiEvents} — DRC 通道的 drc_psdk_state_info / drc_speaker_play_progress（实时状态）</li>
 * </ul>
 * <p>对齐 DJI M30 properties 文档：psdk_widget_values 数组元素含 psdk_index/psdk_name/psdk_sn。
 */
public final class PsdkConstants {

    private PsdkConstants() {
    }

    /** 喊话器 PSDK 设备索引 */
    public static final int SPEAKER_INDEX = 2;
    /** 喊话器 PSDK 设备名称 */
    public static final String SPEAKER_NAME = "Speaker";
    /** 喊话器 PSDK 设备序列号 */
    public static final String SPEAKER_SN = "psdk_speaker_sn";
}
