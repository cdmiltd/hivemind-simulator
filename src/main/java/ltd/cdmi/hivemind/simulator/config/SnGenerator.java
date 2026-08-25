// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator.config;

import ltd.cdmi.dji.cloudapi.sdk.model.DeviceModelProvider;

import java.security.SecureRandom;

/**
 * 实例唯一 SN 生成器（唯一 SN 模式，设计文档 §7.1，TDD-SPEC TC-REG-028~031）。
 * <p>算法：保留型号 {@link DeviceModelProvider#defaultSn()} 的前缀（型号可辨识，
 * 如 {@code 1UUXN}/{@code 2UUXN}/{@code 7UUXN} 区分 Dock1/2/3），尾部替换为随机
 * 大写字母数字后缀，长度与字符集与 defaultSn 同构：
 * <ul>
 *   <li>机场 SN（14 位）：前 10 位型号前缀 + 随机 4 位后缀</li>
 *   <li>飞行器 SN（20 位）：前 14 位型号前缀 + 随机 6 位后缀</li>
 * </ul>
 * 随机后缀位数取 {@code max(4, length/3)}，4 位后缀组合空间 36⁴≈168 万，
 * 少量实例间碰撞概率可忽略。</p>
 * <p>协议推断（M-2，待真机验证）：DJI Cloud API 未定义 SN 内部编码规则，
 * 模拟器假设平台将 SN 作为不透明字符串处理（同长度同字符集即可被接受）。</p>
 */
public final class SnGenerator {

    /** DJI SN 字符集：大写字母 + 数字 */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SnGenerator() {
    }

    /**
     * 为指定设备型号生成实例唯一 SN。
     *
     * @param model 设备型号（DockModel / DroneModel / RcModel）
     * @return 与 defaultSn 同长度、同字符集、共享型号前缀的唯一 SN
     */
    public static String uniqueFor(DeviceModelProvider model) {
        String base = model.defaultSn();
        int suffixLen = Math.max(4, base.length() / 3);
        StringBuilder sb = new StringBuilder(base.length());
        sb.append(base, 0, base.length() - suffixLen);
        for (int i = 0; i < suffixLen; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
