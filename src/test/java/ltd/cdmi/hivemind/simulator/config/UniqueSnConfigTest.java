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

package ltd.cdmi.hivemind.simulator.config;

import ltd.cdmi.dji.cloudapi.sdk.model.DockModel;
import ltd.cdmi.dji.cloudapi.sdk.model.DroneModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 唯一 SN 模式（simulator.sn.unique-enabled）单元测试。
 * <p>覆盖 TDD-SPEC：
 * <ul>
 *   <li>TC-REG-015：默认模式基线（SN 取设备型号 defaultSn）</li>
 *   <li>TC-REG-028：首启生成实例唯一 SN（格式同构 + 立即持久化 + 随机性）</li>
 *   <li>TC-REG-029：重启后恢复持久化的 SN 与型号（其他配置不受影响）</li>
 *   <li>TC-REG-030：运行时切换型号重新生成 SN（幂等）</li>
 *   <li>TC-REG-031：旧格式配置文件向后兼容</li>
 * </ul>
 * <p>通过系统属性 {@code simulator.config.dir} 将 live-config.json 定位到
 * {@code @TempDir} 临时目录，隔离测试与真实用户目录。
 */
class UniqueSnConfigTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty(LiveConfigStore.PROP_CONFIG_DIR, tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(LiveConfigStore.PROP_CONFIG_DIR);
    }

    private SimulatorProperties props(boolean uniqueSn) {
        return new SimulatorProperties(null, null, null, null, null, null, null, null,
                new SimulatorProperties.Sn(uniqueSn));
    }

    private RuntimeConfig newRuntimeConfig(boolean uniqueSn) {
        return new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                props(uniqueSn),
                new LiveConfigStore());
    }

    /** 断言唯一 SN 与 defaultSn 同构：同长度、[0-9A-Z] 字符集、共享型号前缀（长度 - max(4, len/3)） */
    private void assertSnFormat(String sn, String defaultSn) {
        assertEquals(defaultSn.length(), sn.length(), "SN 长度应与 defaultSn 相同");
        assertTrue(sn.matches("[0-9A-Z]+"), "SN 字符集应为大写字母数字，实际: " + sn);
        int prefixLen = defaultSn.length() - Math.max(4, defaultSn.length() / 3);
        assertEquals(defaultSn.substring(0, prefixLen), sn.substring(0, prefixLen), "SN 应保留型号前缀");
    }

    // ==================== TC-REG-015：默认模式基线 ====================

    @DisplayName("TC-REG-015：默认模式 SN 取 defaultSn（unique-enabled 缺省 false）")
    @Test
    void defaultModeUsesModelDefaultSn() {
        RuntimeConfig rc = newRuntimeConfig(false);
        assertFalse(rc.isSnUniqueEnabled());
        assertEquals(DockModel.DOCK3.defaultSn(), rc.getDockSn());
        assertEquals(DroneModel.M4TD.defaultSn(), rc.getDroneSn());
    }

    // ==================== TC-REG-032~036：SN 手动覆盖 ====================

    @DisplayName("TC-REG-032：覆盖值优先于派生值，清空覆盖恢复派生值（默认模式）")
    @Test
    void overrideTakesPrecedenceAndClearRestoresDerivedSn() {
        RuntimeConfig rc = newRuntimeConfig(false);
        assertEquals(DockModel.DOCK3.defaultSn(), rc.getDockSn(), "未覆盖时取 defaultSn");

        assertTrue(rc.setDockSnOverride("MYDOCK001"));
        assertTrue(rc.setDroneSnOverride("MYDRONE001"));
        assertEquals("MYDOCK001", rc.getDockSn(), "覆盖值应优先于 defaultSn");
        assertEquals("MYDRONE001", rc.getDroneSn());
        assertEquals("MYDOCK001", rc.getDockSnOverride());
        assertEquals("MYDRONE001", rc.getDroneSnOverride());

        // 清空覆盖 → 恢复机型派生值
        assertTrue(rc.setDockSnOverride(null));
        assertTrue(rc.setDroneSnOverride(""));
        assertNull(rc.getDockSnOverride());
        assertNull(rc.getDroneSnOverride());
        assertEquals(DockModel.DOCK3.defaultSn(), rc.getDockSn(), "清空覆盖后应恢复 defaultSn");
        assertEquals(DroneModel.M4TD.defaultSn(), rc.getDroneSn());
    }

    @DisplayName("TC-REG-032：唯一模式下覆盖优先，清空恢复此前生成的唯一 SN（身份可恢复）")
    @Test
    void overrideInUniqueModeClearRestoresGeneratedSn() {
        RuntimeConfig rc = newRuntimeConfig(true);
        String generatedDockSn = rc.getDockSn();
        String generatedDroneSn = rc.getDroneSn();

        assertTrue(rc.setDockSnOverride("MYDOCK001"));
        assertTrue(rc.setDroneSnOverride("MYDRONE001"));
        assertEquals("MYDOCK001", rc.getDockSn());
        assertEquals("MYDRONE001", rc.getDroneSn());

        // 清空覆盖 → 恢复唯一模式生成的 SN（而非重新生成）
        assertTrue(rc.setDockSnOverride(null));
        assertTrue(rc.setDroneSnOverride(""));
        assertEquals(generatedDockSn, rc.getDockSn(), "清空覆盖后应恢复此前生成的唯一 SN");
        assertEquals(generatedDroneSn, rc.getDroneSn());
    }

    @DisplayName("TC-REG-033：覆盖持久化，重启后恢复且继续优先；旧格式文件覆盖为 null")
    @Test
    void overridePersistsAcrossRestartAndOldFormatCompatible() throws IOException {
        RuntimeConfig first = newRuntimeConfig(false);
        assertTrue(first.setDockSnOverride("MYDOCK001"));
        assertTrue(first.setDroneSnOverride("MYDRONE001"));

        // 重启（同配置目录重新构造）→ 覆盖恢复且优先
        RuntimeConfig restarted = newRuntimeConfig(false);
        assertEquals("MYDOCK001", restarted.getDockSn());
        assertEquals("MYDRONE001", restarted.getDroneSn());
        assertEquals("MYDOCK001", restarted.getDockSnOverride());

        // 旧格式文件（无 override 字段）→ 不报错，覆盖为 null
        String oldJson = "{\"realPushEnabled\":false,\"ffmpegPath\":\"ffmpeg\",\"videoDir\":\"/v\","
                + "\"mediaDir\":\"/media/old\",\"locationLatitude\":30.0,\"locationLongitude\":104.0,"
                + "\"locationHeight\":500.0,\"pilotHttpBaseUrl\":\"\",\"pilotHttpToken\":\"\","
                + "\"pilotWsUrl\":\"\",\"pilotWsToken\":\"\"}";
        Files.writeString(tempDir.resolve(LiveConfigStore.CONFIG_FILE), oldJson, StandardCharsets.UTF_8);
        RuntimeConfig oldFormat = newRuntimeConfig(false);
        assertNull(oldFormat.getDockSnOverride());
        assertEquals(DockModel.DOCK3.defaultSn(), oldFormat.getDockSn());
    }

    @DisplayName("TC-REG-034：机型切换不清除覆盖，清除覆盖后按新机型派生")
    @Test
    void modelSwitchDoesNotClearOverride() {
        RuntimeConfig rc = newRuntimeConfig(false);
        assertTrue(rc.setDockSnOverride("MYDOCK001"));

        rc.setDockType(DockModel.DOCK1);
        assertEquals("MYDOCK001", rc.getDockSn(), "机型切换不应清除覆盖");

        // 清除覆盖 → 按新机型派生
        assertTrue(rc.setDockSnOverride(null));
        assertEquals(DockModel.DOCK1.defaultSn(), rc.getDockSn(), "清除覆盖后按新机型 defaultSn 派生");
    }

    @DisplayName("TC-REG-035：SN 覆盖格式校验——非法字符/超长拒绝，合法字符集通过")
    @Test
    void overrideFormatValidation() {
        RuntimeConfig rc = newRuntimeConfig(false);
        // 非法：含 / + # 空格、超长
        assertFalse(rc.setDockSnOverride("sn/with/slash"));
        assertFalse(rc.setDockSnOverride("sn+plus"));
        assertFalse(rc.setDockSnOverride("sn#hash"));
        assertFalse(rc.setDockSnOverride("sn space"));
        assertFalse(rc.setDockSnOverride("a".repeat(33)));
        assertNull(rc.getDockSnOverride(), "非法值不应生效");

        // 合法：字母/数字/下划线/中划线，长度 1~32
        assertTrue(rc.setDockSnOverride("Dock_3-TEST-001"));
        assertEquals("Dock_3-TEST-001", rc.getDockSn());
        assertTrue(rc.setDockSnOverride("a".repeat(32)));
    }

    // ==================== TC-REG-028：首启生成实例唯一 SN ====================

    @DisplayName("TC-REG-028：首启生成实例唯一 SN（格式同构）并立即持久化")
    @Test
    void firstStartupGeneratesUniqueSnAndPersists() {
        RuntimeConfig rc = newRuntimeConfig(true);
        assertTrue(rc.isSnUniqueEnabled());

        String dockSn = rc.getDockSn();
        String droneSn = rc.getDroneSn();
        assertNotEquals(DockModel.DOCK3.defaultSn(), dockSn, "首启 dockSn 不应取 defaultSn");
        assertNotEquals(DroneModel.M4TD.defaultSn(), droneSn, "首启 droneSn 不应取 defaultSn");
        assertSnFormat(dockSn, DockModel.DOCK3.defaultSn());
        assertSnFormat(droneSn, DroneModel.M4TD.defaultSn());

        // 启动后立即持久化（无需等待用户修改其他配置）
        LiveConfigStore.LiveConfig saved = new LiveConfigStore().load();
        assertNotNull(saved, "首启后 live-config.json 应已写入");
        assertEquals(DockModel.DOCK3.name(), saved.dockType());
        assertEquals(DroneModel.M4TD.name(), saved.droneType());
        assertEquals(dockSn, saved.dockSn());
        assertEquals(droneSn, saved.droneSn());
    }

    @DisplayName("TC-REG-028：同型号多次生成的 SN 互不相同（随机性）")
    @Test
    void snGeneratorProducesDistinctSnForSameModel() {
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            distinct.add(SnGenerator.uniqueFor(DockModel.DOCK3));
        }
        assertTrue(distinct.size() > 1, "200 次生成应产生不止一个不同 SN");
    }

    // ==================== TC-REG-029：重启恢复持久化的 SN 与型号 ====================

    @DisplayName("TC-REG-029：重启后恢复持久化的 SN 与型号，其他配置不受影响")
    @Test
    void restartRestoresPersistedSnAndModel() {
        RuntimeConfig first = newRuntimeConfig(true);
        first.setMediaDir("/media/keep");
        first.setDockType(DockModel.DOCK1);   // 切换型号 → 重新生成 + 持久化
        String dockSn = first.getDockSn();
        String droneSn = first.getDroneSn();
        assertSnFormat(dockSn, DockModel.DOCK1.defaultSn());

        // 模拟重启：同一配置目录重新构造 RuntimeConfig
        RuntimeConfig restarted = newRuntimeConfig(true);
        assertEquals(dockSn, restarted.getDockSn(), "重启后 dockSn 应与持久化值完全相同");
        assertEquals(droneSn, restarted.getDroneSn(), "重启后 droneSn 应与持久化值完全相同");
        assertEquals(DockModel.DOCK1, restarted.getDockType(), "机场型号应与 SN 成对恢复");
        assertEquals(DroneModel.M4TD, restarted.getDroneType(), "飞行器型号应与 SN 成对恢复");
        assertEquals("/media/keep", restarted.getMediaDir(), "持久化文件中的其他配置不受影响");
    }

    // ==================== TC-REG-030：运行时切换型号重新生成 SN（幂等） ====================

    @DisplayName("TC-REG-030：切换型号重新生成唯一 SN 并立即持久化，重复设置相同型号保持不变")
    @Test
    void switchModelRegeneratesSnIdempotently() {
        RuntimeConfig rc = newRuntimeConfig(true);
        String oldDockSn = rc.getDockSn();
        String oldDroneSn = rc.getDroneSn();

        // 型号实际变化 → 重新生成（DOCK3 前缀 → DOCK1 前缀）
        rc.setDockType(DockModel.DOCK1);
        String newDockSn = rc.getDockSn();
        assertNotEquals(oldDockSn, newDockSn, "切换型号后 dockSn 应重新生成");
        assertNotEquals(DockModel.DOCK1.defaultSn(), newDockSn, "新 dockSn 不应取 defaultSn");
        assertSnFormat(newDockSn, DockModel.DOCK1.defaultSn());

        // 新 SN 立即持久化
        LiveConfigStore.LiveConfig saved = new LiveConfigStore().load();
        assertEquals(newDockSn, saved.dockSn());
        assertEquals(DockModel.DOCK1.name(), saved.dockType());

        // 重复设置相同型号 → SN 不变（幂等，防 /connect 重复提交导致设备身份漂移）
        rc.setDockType(DockModel.DOCK1);
        assertEquals(newDockSn, rc.getDockSn(), "重复设置相同型号 SN 应保持不变");

        // 飞行器型号切换逻辑对称
        rc.setDroneType(DroneModel.M3TD);
        String newDroneSn = rc.getDroneSn();
        assertNotEquals(oldDroneSn, newDroneSn, "切换飞行器型号后 droneSn 应重新生成");
        assertNotEquals(DroneModel.M3TD.defaultSn(), newDroneSn, "新 droneSn 不应取 defaultSn");
        assertSnFormat(newDroneSn, DroneModel.M3TD.defaultSn());
        rc.setDroneType(DroneModel.M3TD);
        assertEquals(newDroneSn, rc.getDroneSn(), "重复设置相同飞行器型号 SN 应保持不变");
    }

    @DisplayName("TC-REG-030：唯一 SN 模式关闭时 setDockType 保持原状（SN=defaultSn，不写入 sn 字段）")
    @Test
    void switchModelInDefaultModeKeepsDefaultSnBehavior() {
        RuntimeConfig rc = newRuntimeConfig(false);
        rc.setDockType(DockModel.DOCK1);
        assertEquals(DockModel.DOCK1.defaultSn(), rc.getDockSn(), "默认模式切换型号后 SN 应取 defaultSn");

        // 保存时不写入 sn 字段（保持默认模式文件格式不变）
        rc.persistLiveConfig();
        LiveConfigStore.LiveConfig saved = new LiveConfigStore().load();
        assertNull(saved.dockType());
        assertNull(saved.droneType());
        assertNull(saved.dockSn());
        assertNull(saved.droneSn());
    }

    // ==================== TC-REG-031：旧格式配置文件向后兼容 ====================

    @DisplayName("TC-REG-031：旧格式配置文件（无 sn 字段）不报错，按首启生成新 SN 并保留旧字段")
    @Test
    void oldFormatConfigFileIsHandledGracefully() throws IOException {
        // 模拟唯一 SN 模式引入前的 11 字段旧格式（无 sn 相关字段）
        String oldJson = "{\"realPushEnabled\":false,\"ffmpegPath\":\"ffmpeg\",\"videoDir\":\"/v\","
                + "\"mediaDir\":\"/media/old\",\"locationLatitude\":30.0,\"locationLongitude\":104.0,"
                + "\"locationHeight\":500.0,\"pilotHttpBaseUrl\":\"\",\"pilotHttpToken\":\"\","
                + "\"pilotWsUrl\":\"\",\"pilotWsToken\":\"\"}";
        Files.writeString(tempDir.resolve(LiveConfigStore.CONFIG_FILE), oldJson, StandardCharsets.UTF_8);

        RuntimeConfig rc = newRuntimeConfig(true);   // 不抛异常（旧文件反序列化不阻断启动）
        assertNotEquals(DockModel.DOCK3.defaultSn(), rc.getDockSn(), "缺 sn 字段应按首启生成新 SN");
        assertNotEquals(DroneModel.M4TD.defaultSn(), rc.getDroneSn(), "缺 sn 字段应按首启生成新 SN");

        // 生成后持久化，旧字段保留
        LiveConfigStore.LiveConfig saved = new LiveConfigStore().load();
        assertEquals(rc.getDockSn(), saved.dockSn());
        assertEquals("/media/old", saved.mediaDir(), "旧格式中的 mediaDir 应保留");
        assertEquals("/v", saved.videoDir(), "旧格式中的 videoDir 应保留");
    }

    @DisplayName("TC-REG-031：唯一 SN 模式关闭时，文件中存在 sn 字段也不恢复")
    @Test
    void defaultModeIgnoresSnFieldsInConfigFile() {
        // 先以唯一 SN 模式生成含 sn 字段的文件
        RuntimeConfig unique = newRuntimeConfig(true);
        unique.setDockType(DockModel.DOCK1);
        assertNotEquals(unique.getDockSn(), DockModel.DOCK1.defaultSn());

        // 切回默认模式启动：SN 恒为 defaultSn，持久化型号不恢复
        RuntimeConfig defaultMode = newRuntimeConfig(false);
        assertEquals(DockModel.DOCK3.defaultSn(), defaultMode.getDockSn(), "默认模式应忽略文件中的 sn 字段");
        assertEquals(DockModel.DOCK3, defaultMode.getDockType(), "默认模式应忽略文件中的型号字段");

        // 保存时不写入 sn 字段（下次启动仍是默认模式语义）
        defaultMode.persistLiveConfig();
        LiveConfigStore.LiveConfig saved = new LiveConfigStore().load();
        assertNull(saved.dockType());
        assertNull(saved.dockSn());
        assertNull(saved.droneSn());
    }

    @DisplayName("TC-REG-031 补充：持久化型号无法识别时按当前型号重新生成（容错降级）")
    @Test
    void unknownPersistedModelFallsBackToRegeneration() throws IOException {
        String json = "{\"realPushEnabled\":false,\"ffmpegPath\":\"ffmpeg\",\"videoDir\":\"\","
                + "\"mediaDir\":\"\",\"locationLatitude\":30.0,\"locationLongitude\":104.0,"
                + "\"locationHeight\":500.0,\"pilotHttpBaseUrl\":\"\",\"pilotHttpToken\":\"\","
                + "\"pilotWsUrl\":\"\",\"pilotWsToken\":\"\","
                + "\"dockType\":\"DOCK_X\",\"droneType\":\"M4TD\","
                + "\"dockSn\":\"1UUXN1Q00A00XY\",\"droneSn\":\"1081F8HGD2511001ABCD\"}";
        Files.writeString(tempDir.resolve(LiveConfigStore.CONFIG_FILE), json, StandardCharsets.UTF_8);

        RuntimeConfig rc = newRuntimeConfig(true);
        // dockType 无法识别（如 SDK 枚举变更）→ 按当前型号（默认 DOCK3）重新生成
        assertEquals(DockModel.DOCK3, rc.getDockType());
        assertSnFormat(rc.getDockSn(), DockModel.DOCK3.defaultSn());
        assertNotEquals("1UUXN1Q00A00XY", rc.getDockSn(), "无法识别的持久化 SN 不应被恢复");
        // droneType 可识别 → 正常恢复
        assertEquals(DroneModel.M4TD, rc.getDroneType());
        assertEquals("1081F8HGD2511001ABCD", rc.getDroneSn());
    }
}
