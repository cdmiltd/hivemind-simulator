/**
 * Pilot 状态面板组件 — 状态模拟 + 位置模拟
 *
 * 状态模拟：遥控电量（Pilot 版，无机场状态）
 * 位置模拟：遥控器位置=飞行器返航点 Home，地图/手动模式 + 无人机实时位置
 *
 * 共享状态/方法通过 inject('ctx') 获取。
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotStatusPanel(app) 注册。
 */
function registerPilotStatusPanel(app) {
    const { inject } = Vue;

    app.component('pilot-status', {
        setup() {
            const ctx = inject('ctx');
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
                <!-- 地图模式（使用 v-show 而非 v-if，避免切换模式时 mapContainer DOM 被销毁导致 amapInstance 失效） -->
                <div v-show="mapMode === 'map'">
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
                <!-- 手动模式（v-show 配合地图模式 v-show，二者共用 mapContainer DOM，避免 amapInstance 失效） -->
                <div v-show="mapMode === 'manual'">
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
                        <el-descriptions-item label="DRC">
                            <el-tag :type="dronePosition.drc_state === 2 ? 'success' : 'info'" size="small" effect="plain">{{ drcStatusLabel }}</el-tag>
                        </el-descriptions-item>
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
        `
    });
}
