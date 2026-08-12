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
 * DJI Cloud API 设备类型枚举。
 * <p>封装 (domain, type, sub_type) 三元组，提供类型安全的设备型号管理。
 * <p>每个型号内置默认 SN（{@link #defaultSn()}），application.yml 未配置 dock-sn/drone-sn 时自动使用。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/overview/product-support.html">DJI 产品支持</a>
 */
public enum DeviceType {
    // 机场 (domain=3)
    DOCK1(3, 1, 0, "大疆机场", "Dock1",   "1UUXN1Q00A001W"),
    DOCK2(3, 2, 0, "大疆机场2", "Dock2",  "2UUXN1Q00A002W"),
    DOCK3(3, 3, 0, "大疆机场3", "Dock3",  "7UUXN1Q00A008W"),

    // 遥控器 (domain=2) — Pilot to Cloud 网关设备
    RC_PLUS  (2, 119, 0, "DJI RC Plus",        "RC Plus",    "1581F5RCD001001"),
    RC_PLUS_2(2, 174, 0, "DJI RC Plus 2",      "RC Plus 2",  "1581F5RCD002001"),
    RC_PRO   (2, 144, 0, "DJI RC Pro 行业版",  "RC Pro",     "1581F5RCD003001"),

    // 飞行器 (domain=0)
    M30 (0, 67,  0, "Matrice 30",  "M30",  "1581F4HBD12340010101"),
    M30T(0, 67,  1, "Matrice 30T", "M30T", "1581F4HBD12340010201"),
    M3D (0, 91,  0, "Matrice 3D",  "M3D",  "1581F6HGD23110010101"),
    M3TD(0, 91,  1, "Matrice 3TD", "M3TD", "1581F6HGD23110010201"),
    M4D (0, 100, 0, "Matrice 4D",  "M4D",  "1081F8HGD25110010001"),
    M4TD(0, 100, 1, "Matrice 4TD", "M4TD", "1081F8HGD25110010059"),

    // Pilot 飞行器 (domain=0) — 仅 Pilot 模式使用，Dock 不支持
    M350_RTK(0, 89,  0, "Matrice 350 RTK",  "M350 RTK",  "1581F4HBD89110101"),
    M300_RTK(0, 60,  0, "Matrice 300 RTK",  "M300 RTK",  "1581F4HBD60110101"),
    MAVIC_3E(0, 77,  0, "Mavic 3E",         "Mavic 3E",  "1581F4HBD77110101"),
    MAVIC_3T(0, 77,  1, "Mavic 3T",         "Mavic 3T",  "1581F4HBD77110201"),
    M400    (0, 103, 0, "Matrice 400",      "M400",      "1581F4HBD03110101"),
    M4E     (0, 99,  0, "DJI Matrice 4E",   "M4E",       "1581F8HGD99110101"),
    M4T     (0, 99,  1, "DJI Matrice 4T",   "M4T",       "1581F8HGD99110201");

    private final int domain;
    private final int type;
    private final int subType;
    private final String displayName;
    private final String shortName;
    private final String defaultSn;

    DeviceType(int domain, int type, int subType, String displayName, String shortName, String defaultSn) {
        this.domain = domain;
        this.type = type;
        this.subType = subType;
        this.displayName = displayName;
        this.shortName = shortName;
        this.defaultSn = defaultSn;
    }

    public int getDomain() { return domain; }
    public int getType() { return type; }
    public int getSubType() { return subType; }
    public String getDisplayName() { return displayName; }
    public String getShortName() { return shortName; }

    /**
     * 获取该设备型号内置的默认 SN。
     * <p>application.yml 未配置 dock-sn/drone-sn 时，RuntimeConfig 使用此值。
     * <p>SN 格式对齐 DJI 真实设备：机场 15 位，飞行器 20 位。
     */
    public String defaultSn() { return defaultSn; }

    /** model_key 格式: "domain-type-subType"，如 "3-3-0" 表示 Dock3 */
    public String modelKey() {
        return domain + "-" + type + "-" + subType;
    }

    public boolean isDock() { return domain == 3; }
    public boolean isController() { return domain == 2; }
    public boolean isAircraft() { return domain == 0; }

    /** 按 type 查找机场类型（用于 MonitorService 解析网关类型） */
    public static DeviceType fromDockType(int type) {
        for (DeviceType dt : values()) {
            if (dt.isDock() && dt.type == type) {
                return dt;
            }
        }
        return null;
    }

    /** 按 type+subType 查找飞行器类型 */
    public static DeviceType fromAircraftType(int type, int subType) {
        for (DeviceType dt : values()) {
            if (dt.isAircraft() && dt.type == type && dt.subType == subType) {
                return dt;
            }
        }
        return null;
    }
}
