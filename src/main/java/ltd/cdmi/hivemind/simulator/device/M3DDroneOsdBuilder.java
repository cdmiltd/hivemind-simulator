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

import org.springframework.stereotype.Component;

/**
 * M3D/M3TD 飞行器 OSD 字段集构造器。
 * <p>M3D 家族与 M4D 家族 OSD 字段集相似（均有 cameras/type_subtype_gimbalindex/is_near_area_limit/is_near_height_limit），
 * 继承 {@link M4DDroneOsdBuilder} 复用字段构造逻辑，仅覆盖机型标识与支持范围。</p>
 * <p>wireless_link_topo（pushMode=1）在 state topic 上报，不在 OSD，由 DockOnlineService.publishDroneState() 推送。</p>
 * <p>distance_limit_status/rth_altitude 已提升到基类（M30/M3D/M4D 共有）。</p>
 * <p>payload_index 差异由 {@link PayloadType#defaultCameraFor(DeviceType)} 自动处理（M3D→80-0-0，M4D→98-0-0）。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m3d-properties.html">M3D/M3TD properties</a></p>
 */
@Component
public class M3DDroneOsdBuilder extends M4DDroneOsdBuilder {

    @Override
    public String aircraftFamily() {
        return "m3d";
    }

    @Override
    public boolean supports(DeviceType droneType) {
        return droneType == DeviceType.M3D || droneType == DeviceType.M3TD;
    }
}
