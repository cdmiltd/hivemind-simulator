/**
 * Pilot 面板组件（飞行器上云模式专属）
 *
 * 包含：状态模拟（遥控电量）、直播模拟、位置模拟、JSBridge 模块占位卡片
 *
 * 共享状态/方法通过 inject('ctx') 获取。
 *
 * DJI Pilot 2 JSBridge 官方 9 模块：
 *   设备上云 / 直播 / API / WS / 地图元素 / TSA态势感知 / Media媒体 / 航线 / MOP
 * 已实现：设备上云（外壳顶部工具栏注册按钮）、直播（直播模拟卡片）
 * 待补全：API / WS / 地图元素 / TSA / Media / 航线 / MOP（7 个占位卡片）
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotPanel(app) 注册。
 */
function registerPilotPanel(app) {
    const { inject } = Vue;

    app.component('pilot-panel', {
        setup() {
            const ctx = inject('ctx');
            // Pilot 面板暂无专属状态/方法，仅 inject 共享上下文并展开到模板
            return { ...ctx };
        },
        template: `
            <!-- 状态模拟（Pilot 版：仅遥控电量） -->
            <el-card class="right-card" header="状态模拟">
                <el-form label-width="72px" size="small">
                    <el-form-item label="遥控电量">
                        <el-slider v-model="editState.controllerCapacity" :min="0" :max="100" @change="saveState"></el-slider>
                    </el-form-item>
                </el-form>
            </el-card>

            <!-- 直播模拟 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <span>直播模拟</span>
                        <el-button size="small" text @click="refreshLiveCapability">重新检测</el-button>
                    </div>
                </template>
                <!-- 推流能力状态 -->
                <div v-if="liveCapability" style="margin-bottom: 10px; padding-bottom: 10px; border-bottom: 1px dashed #e4e7ed;">
                    <div style="display: flex; align-items: center; gap: 6px; margin-bottom: 6px;">
                        <span style="font-size: 13px; color: #606266;">推流能力:</span>
                        <el-tag v-if="liveCapability.whipSupported && liveCapability.rtmpSupported" type="success" size="small">完整 (RTMP+WHIP)</el-tag>
                        <el-tag v-else-if="liveCapability.rtmpSupported" type="success" size="small">RTMP</el-tag>
                        <el-tag v-else-if="liveCapability.whipSupported" type="success" size="small">WHIP</el-tag>
                        <el-tag v-else type="warning" size="small">受限</el-tag>
                        <span v-if="liveCapability.activePushCount > 0" style="font-size: 12px; color: #909399;">（{{ liveCapability.activePushCount }} 路推流中）</span>
                        <el-button size="small" text type="primary" @click="openLiveConfigDialog" style="margin-left: auto;">配置</el-button>
                    </div>
                    <!-- 限制清单 -->
                    <div v-if="liveCapability.limitations && liveCapability.limitations.length > 0">
                        <div v-for="(lim, i) in liveCapability.limitations" :key="i" style="background: #fdf6ec; border: 1px solid #f5dab1; border-radius: 4px; padding: 8px; margin-bottom: 6px; font-size: 12px;">
                            <div style="font-weight: bold; color: #e6a23c;">
                                <span v-if="lim.code !== 'CONFIG'" style="margin-right: 4px;">[{{ lim.code }}]</span>{{ lim.title }}
                            </div>
                            <div style="color: #606266; margin-top: 4px;"><b>原因:</b> {{ lim.reason }}</div>
                            <div style="color: #606266; margin-top: 2px;"><b>操作:</b> {{ lim.action }}</div>
                            <div style="color: #67c23a; margin-top: 2px;"><b>突破后:</b> {{ lim.afterFix }}</div>
                        </div>
                    </div>
                    <!-- 已有资源（完整时展示） -->
                    <div v-else style="font-size: 12px; color: #67c23a;">
                        ffmpeg: {{ liveCapability.ffmpegPath }} {{ liveCapability.whipSupported ? '(支持WHIP)' : '' }}<br>
                        视频目录: {{ liveCapability.videoDir || '-' }}<br>
                        <span v-if="liveCapability.videosFound && liveCapability.videosFound.length > 0">已有视频: {{ liveCapability.videosFound.join(', ') }}</span>
                    </div>
                </div>
                <!-- 活跃推流列表 -->
                <div v-if="streams.length === 0" style="color: #909399; font-size: 13px;">无活跃推流</div>
                <div v-for="s in streams" :key="s.video_id" style="margin-bottom: 8px; font-size: 13px;">
                    <el-tag type="success" size="small">推流中</el-tag>
                    <div style="margin-top: 4px;">videoId: {{ s.video_id }}</div>
                    <div>清晰度: {{ ['自适应','流畅','标清','高清','超清'][s.quality] }}</div>
                </div>
            </el-card>

            <!-- 位置模拟（Pilot 版：遥控器位置=飞行器返航点 Home） -->
            <el-card class="right-card">
                <template #header>
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <span>位置模拟</span>
                            <el-radio-group v-model="mapMode" size="small" @change="onMapModeChange">
                                <el-radio-button value="map" :disabled="!amapKeyApplied">地图</el-radio-button>
                                <el-radio-button value="manual">手动</el-radio-button>
                            </el-radio-group>
                            <el-button size="small" text @click="showAmapConfigDialog = true">配置</el-button>
                        </div>
                        <el-button v-if="mapMode === 'manual'" size="small" type="primary" @click="saveLocation">保存</el-button>
                    </div>
                </template>
                <!-- 地图模式 -->
                <div v-if="mapMode === 'map'">
                    <!-- 地址搜索 + 选点按钮 -->
                    <div style="display: flex; gap: 4px; margin-bottom: 4px;">
                        <el-autocomplete
                            v-model="addressSearch"
                            :fetch-suggestions="fetchAddressSuggestions"
                            placeholder="输入地址搜索"
                            size="small"
                            clearable
                            value-key="value"
                            style="flex: 1;"
                            @select="onAddressSelect"
                        ></el-autocomplete>
                        <el-button size="small" :type="selectingAirport ? 'warning' : 'default'" @click="startSelectAirport">
                            {{ selectingAirport ? '点击地图' : '选点' }}
                        </el-button>
                        <el-button v-if="selectingAirport" size="small" @click="cancelSelectAirport">取消</el-button>
                    </div>
                    <!-- 地图容器 -->
                    <div id="mapContainer" style="width: 100%; height: 240px; border: 1px solid #e4e7ed; border-radius: 4px;"></div>
                </div>
                <!-- 手动模式 -->
                <div v-if="mapMode === 'manual'">
                    <!-- 未配置 Key 时提示 -->
                    <div v-if="!amapKeyApplied" style="margin-bottom: 8px; padding: 8px; background: #f5f7fa; border-radius: 4px; font-size: 12px; color: #909399;">
                        点击「配置」按钮申请并填写高德地图 Key，可切换到地图模式
                    </div>
                    <!-- 经纬度+高度输入 -->
                    <el-form label-width="60px" size="small">
                        <el-form-item label="纬度">
                            <el-input-number v-model="locationEdit.latitude" :precision="6" :step="0.000001" :controls="false" style="width: 100%;"></el-input-number>
                        </el-form-item>
                        <el-form-item label="经度">
                            <el-input-number v-model="locationEdit.longitude" :precision="6" :step="0.000001" :controls="false" style="width: 100%;"></el-input-number>
                        </el-form-item>
                        <el-form-item label="高度(m)">
                            <el-input-number v-model="locationEdit.height" :precision="1" :step="0.1" :controls="false" style="width: 100%;"></el-input-number>
                        </el-form-item>
                    </el-form>
                </div>
                <div style="font-size: 12px; color: #909399; margin-top: 4px;">
                    遥控器位置作为飞行器返航点（Home 点），保存后重启依然有效。
                </div>

                <!-- 无人机实时位置 -->
                <div style="margin-top: 12px; padding-top: 10px; border-top: 1px dashed #e4e7ed;">
                    <div style="font-size: 13px; color: #303133; margin-bottom: 6px;">无人机位置</div>
                    <el-descriptions :column="1" border size="small">
                        <el-descriptions-item label="纬度">{{ dronePosition.activated ? dronePosition.latitude.toFixed(6) : '-' }}</el-descriptions-item>
                        <el-descriptions-item label="经度">{{ dronePosition.activated ? dronePosition.longitude.toFixed(6) : '-' }}</el-descriptions-item>
                        <el-descriptions-item label="高度(m)">{{ dronePosition.activated ? dronePosition.height.toFixed(1) : '-' }}</el-descriptions-item>
                        <el-descriptions-item label="状态">{{ droneStatusLabel }}</el-descriptions-item>
                    </el-descriptions>
                    <div style="margin-top: 8px; display: flex; align-items: center; gap: 8px;">
                        <el-button size="small" type="warning" :disabled="!canTriggerRcLost" @click="triggerRcLost">模拟失联</el-button>
                        <el-select size="small" v-model="dronePosition.rc_lost_action" style="width: 110px;" @change="saveRcLostAction">
                            <el-option label="悬停" :value="0"></el-option>
                            <el-option label="降落" :value="1"></el-option>
                            <el-option label="返航" :value="2"></el-option>
                        </el-select>
                    </div>
                </div>
            </el-card>

            <!-- 设备上云模块说明（注册功能在顶部工具栏） -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>设备上云模块 (cloud)</span>
                        <el-tag type="success" size="small">已实现</el-tag>
                    </div>
                </template>
                <p style="color:#606266;font-size:12px;line-height:1.6;">
                    注册到第三方平台功能位于顶部工具栏的「注册到第三方平台」按钮。<br>
                    Pilot 模式跳过 DJI 注册流程，MQTT 连接成功后直接上线。
                </p>
            </el-card>

            <!-- API 模块占位 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>API 模块 (token)</span>
                        <el-tag type="info" size="small">UI 待填充</el-tag>
                    </div>
                </template>
                <p style="color:#909399;font-size:12px;line-height:1.6;">
                    后端已实现 HivemindHttpClient<br>
                    待补全：token 配置、HTTP 请求触发
                </p>
            </el-card>

            <!-- WS 模块占位 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>WS 模块 (ws)</span>
                        <el-tag type="info" size="small">UI 待填充</el-tag>
                    </div>
                </template>
                <p style="color:#909399;font-size:12px;line-height:1.6;">
                    后端已实现 HivemindWsClient<br>
                    待补全：WebSocket 连接控制、消息展示
                </p>
            </el-card>

            <!-- 地图元素模块占位 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>地图元素模块 (map)</span>
                        <el-tag type="info" size="small">UI 待填充</el-tag>
                    </div>
                </template>
                <p style="color:#909399;font-size:12px;line-height:1.6;">
                    后端已实现 MapElementApi<br>
                    待补全：元素列表、创建/更新/删除操作
                </p>
            </el-card>

            <!-- TSA 态势感知模块占位 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>TSA 态势感知模块 (tsa)</span>
                        <el-tag type="info" size="small">UI 待填充</el-tag>
                    </div>
                </template>
                <p style="color:#909399;font-size:12px;line-height:1.6;">
                    后端已实现 DeviceTopoApi<br>
                    待补全：设备拓扑查询、态势推送展示
                </p>
            </el-card>

            <!-- Media 媒体模块占位 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>Media 媒体模块 (media)</span>
                        <el-tag type="info" size="small">UI 待填充</el-tag>
                    </div>
                </template>
                <p style="color:#909399;font-size:12px;line-height:1.6;">
                    后端已实现 MediaApi<br>
                    待补全：文件快传、上传结果上报
                </p>
            </el-card>

            <!-- 航线模块占位 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>航线模块 (wayline)</span>
                        <el-tag type="info" size="small">UI 待填充</el-tag>
                    </div>
                </template>
                <p style="color:#909399;font-size:12px;line-height:1.6;">
                    后端已实现 WaylineApi<br>
                    待补全：航线列表、上传/下载/收藏
                </p>
            </el-card>

            <!-- MOP 模块占位 -->
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>MOP 模块 (mop)</span>
                        <el-tag type="info" size="small">UI 待填充</el-tag>
                    </div>
                </template>
                <p style="color:#909399;font-size:12px;line-height:1.6;">
                    后端已实现 MopClient<br>
                    待补全：MOP 连接控制、消息收发
                </p>
            </el-card>
        `
    });
}
