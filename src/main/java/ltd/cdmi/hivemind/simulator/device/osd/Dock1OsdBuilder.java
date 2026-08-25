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

package ltd.cdmi.hivemind.simulator.device.osd;

import ltd.cdmi.dji.cloudapi.sdk.telemetry.OsdField;
import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dock1 机场 OSD 字段集构造器。
 * <p>Dock1 特有字段：electric_supply_voltage（市电电压）、putter_state（推杆状态）、drone_authority_info（飞行器控制权状态，control_source + locked 在 OSD，payloads 在 state）。
 * 这三个字段仅 Dock1 OSD 定义，Dock2/Dock3 文档未定义。</p>
 * <p>sub_device 字段名三版统一使用 device_model_key（Dock1 属性列表标注 product_type，但 OSD 示例用 device_model_key，待真机验证）。</p>
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html">Dock1 properties</a></p>
 */
@Component
public class Dock1OsdBuilder extends AbstractDockOsdBuilder {

    @Override
    public String version() {
        return "dock1";
    }

    @Override
    public boolean supports(DockModel dockType) {
        return dockType == DockModel.DOCK1;
    }

    @Override
    protected String subDeviceModelKeyField() {
        return "device_model_key"; // 三版统一使用 device_model_key（Dock1 属性列表标注 product_type，但 OSD 示例用 device_model_key，待真机验证）
    }

    @Override
    protected void appendDockSpecific(OsdContext ctx,
                                        Map<String, Object> powerAndBattery,
                                        Map<String, Object> taskAndLink,
                                        Map<String, Object> positionAndEnv) {
        DeviceState state = ctx.getState();
        // Dock1 特有字段：electric_supply_voltage（市电电压，仅 Dock1 OSD 定义，Dock2/Dock3 未定义）
        powerAndBattery.put(OsdField.ELECTRIC_SUPPLY_VOLTAGE.fieldName(), state.getElectricSupplyVoltage());
        // Dock1 特有字段：putter_state（推杆状态，仅 Dock1 OSD 定义，Dock2/Dock3 未定义）
        positionAndEnv.put(OsdField.PUTTER_STATE.fieldName(), state.isPutterExpanded() ? 1 : 0);
        // Dock1 特有字段：drone_authority_info（飞行器控制权状态，pushMode=0 子字段在 OSD）
        positionAndEnv.put(OsdField.DRONE_AUTHORITY_INFO.fieldName(), buildDroneAuthorityInfo());
    }

    /**
     * 构造 drone_authority_info（飞行器控制权状态）。
     * <p>OSD 上报 control_source + locked（pushMode=0）；payloads（pushMode=1）由 state topic 上报。</p>
     */
    private Map<String, Object> buildDroneAuthorityInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("control_source", "A");  // 飞行控制权（A控）
        m.put("locked", false);          // 飞行控制权未锁定
        return m;
    }
}
