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

import java.util.List;

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

    // 飞行器 (domain=0)
    M30 (0, 67,  0, "Matrice 30",  "M30",  "1581F4HBD12340010101"),
    M30T(0, 67,  1, "Matrice 30T", "M30T", "1581F4HBD12340010201"),
    M3D (0, 91,  0, "Matrice 3D",  "M3D",  "1581F6HGD23110010101"),
    M3TD(0, 91,  1, "Matrice 3TD", "M3TD", "1581F6HGD23110010201"),
    M4D (0, 100, 0, "Matrice 4D",  "M4D",  "1081F8HGD25110010001"),
    M4TD(0, 100, 1, "Matrice 4TD", "M4TD", "1081F8HGD25110010059");

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
    public boolean isAircraft() { return domain == 0; }

    /** 从 model_key 字符串解析设备类型，如 "3-3-0" → DOCK3 */
    public static DeviceType fromModelKey(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            throw new IllegalArgumentException("model_key 不能为空");
        }
        String[] parts = modelKey.split("-");
        if (parts.length != 3) {
            throw new IllegalArgumentException("model_key 格式错误: " + modelKey + "，期望: domain-type-subType");
        }
        try {
            int d = Integer.parseInt(parts[0].trim());
            int t = Integer.parseInt(parts[1].trim());
            int s = Integer.parseInt(parts[2].trim());
            for (DeviceType dt : values()) {
                if (dt.domain == d && dt.type == t && dt.subType == s) {
                    return dt;
                }
            }
            throw new IllegalArgumentException("不支持的设备类型: " + modelKey);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("model_key 格式错误: " + modelKey, e);
        }
    }

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

    /** 机场与飞行器兼容性校验 */
    public static boolean isCompatible(DeviceType dock, DeviceType drone) {
        if (dock == null || !dock.isDock() || drone == null || !drone.isAircraft()) {
            return false;
        }
        return switch (dock) {
            case DOCK1 -> drone == M30 || drone == M30T;
            case DOCK2 -> drone == M3D || drone == M3TD;
            case DOCK3 -> drone == M4D || drone == M4TD;
            default -> false;
        };
    }

    /** 获取机场兼容的飞行器列表 */
    public List<DeviceType> getCompatibleAircraft() {
        if (!isDock()) return List.of();
        return switch (this) {
            case DOCK1 -> List.of(M30, M30T);
            case DOCK2 -> List.of(M3D, M3TD);
            case DOCK3 -> List.of(M4D, M4TD);
            default -> List.of();
        };
    }
}
