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

package ltd.cdmi.simulator.device;

/**
 * DJI Cloud API 负载类型枚举。
 * <p>负载标识格式为 {type}-{subtype}-{gimbalindex}（注意：不含 domain，与设备 model_key 不同）。
 * <p>参考：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/overview/product-support.html">DJI 产品支持</a>
 */
public enum PayloadType {
    // 飞行器主相机（与飞行器一一对应）
    M30_CAMERA  (52,  0, 0, "Matrice 30 Camera",  DeviceType.M30),
    M30T_CAMERA (53,  0, 0, "Matrice 30T Camera", DeviceType.M30T),
    M3D_CAMERA  (80,  0, 0, "Matrice 3D Camera",  DeviceType.M3D),
    M3TD_CAMERA (81,  0, 0, "Matrice 3TD Camera", DeviceType.M3TD),
    M4D_CAMERA  (98,  0, 0, "Matrice 4D Camera",  DeviceType.M4D),
    M4TD_CAMERA (99,  0, 0, "Matrice 4TD Camera", DeviceType.M4TD),

    // 通用云台负载（可挂载于 M300/M350 等多款飞行器）
    Z30  (20, 0, 0, "禅思 Z30",  null),
    XT2  (26, 0, 0, "禅思 XT2",  null),
    XTS  (41, 0, 0, "禅思 XTS",  null),
    H20  (42, 0, 0, "禅思 H20",  null),
    H20T (43, 0, 0, "禅思 H20T", null),
    H20N (61, 0, 0, "禅思 H20N", null),
    H30  (82, 0, 0, "禅思 H30",  null),
    H30T (83, 0, 0, "禅思 H30T", null),

    // FPV / 辅助影像
    FPV_CAMERA(39, 0, 7, "FPV 相机", null),

    // 机场相机（所有机场共用 type=165，通过 camera_position 区分舱内/舱外）
    DOCK_CAMERA(165, 0, 7, "机场相机", null);

    private final int type;
    private final int subType;
    private final int gimbalIndex;
    private final String displayName;
    private final DeviceType compatibleAircraft;

    PayloadType(int type, int subType, int gimbalIndex, String displayName, DeviceType compatibleAircraft) {
        this.type = type;
        this.subType = subType;
        this.gimbalIndex = gimbalIndex;
        this.displayName = displayName;
        this.compatibleAircraft = compatibleAircraft;
    }

    public int getType() { return type; }
    public int getSubType() { return subType; }
    public int getGimbalIndex() { return gimbalIndex; }
    public String getDisplayName() { return displayName; }

    /** 负载标识: {type}-{subtype}-{gimbalindex}，如 "98-0-0" 表示 M4D Camera */
    public String cameraIndex() {
        return type + "-" + subType + "-" + gimbalIndex;
    }

    public boolean isDockCamera() { return type == 165; }
    public boolean isFpvCamera() { return type == 39; }

    /** 获取飞行器默认主相机 */
    public static PayloadType defaultCameraFor(DeviceType aircraft) {
        if (aircraft == null || !aircraft.isAircraft()) return null;
        for (PayloadType p : values()) {
            if (p.compatibleAircraft == aircraft) {
                return p;
            }
        }
        return null;
    }
}
