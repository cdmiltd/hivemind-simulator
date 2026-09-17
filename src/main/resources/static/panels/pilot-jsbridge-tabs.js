/**
 * Pilot JSBridge 模块面板组件 — 7 模块 tab 交互
 *
 * DJI Pilot 2 JSBridge 官方 7 模块（设备上云/直播已在其他面板实现）：
 *   API / WS / 地图元素 / TSA态势感知 / Media媒体 / 航线 / MOP
 *
 * 各 tab 调用对应后端 REST 端点，后端通过 HivemindHttpClient/HivemindWsClient 等
 * 客户端访问 hivemind 平台。Pilot 模式跳过 DJI JSBridge 层。
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotJsbridgeTabsPanel(app) 注册。
 */
function registerPilotJsbridgeTabsPanel(app) {
    const { ref, reactive, inject, watch } = Vue;

    app.component('pilot-jsbridge-tabs', {
        setup() {
            const ctx = inject('ctx');
            const { fetchJSON } = ctx;

            const activeTab = ref('api');

            // ===== API tab：配置状态展示 =====
            const apiConfig = ref(null);
            const loadApiConfig = async () => {
                apiConfig.value = await fetchJSON('/api/config/pilot');
            };

            // ===== WS tab：WS 事件展示（地图+TSA） =====
            const wsEvents = ref([]);
            const wsLoading = ref(false);
            const loadWsEvents = async () => {
                wsLoading.value = true;
                const [mapEvt, tsaEvt] = await Promise.all([
                    fetchJSON('/api/map/ws-events'),
                    fetchJSON('/api/tsa/ws-events')
                ]);
                const merged = [];
                if (mapEvt && mapEvt.events) merged.push(...mapEvt.events.map(e => ({ ...e, source: 'map' })));
                if (tsaEvt && tsaEvt.events) merged.push(...tsaEvt.events.map(e => ({ ...e, source: 'tsa' })));
                wsEvents.value = merged;
                wsLoading.value = false;
            };
            const clearWsEvents = async () => {
                await Promise.all([
                    fetchJSON('/api/map/ws-events', { method: 'DELETE' }),
                    fetchJSON('/api/tsa/ws-events', { method: 'DELETE' })
                ]);
                await loadWsEvents();
            };

            // ===== 地图元素 tab =====
            const mapElements = ref([]);
            const mapLoading = ref(false);
            const loadMapElements = async () => {
                mapLoading.value = true;
                const data = await fetchJSON('/api/map/elements');
                mapElements.value = (data && data.elements) ? data.elements : [];
                mapLoading.value = false;
            };
            const deleteMapElement = async (id) => {
                await fetchJSON('/api/map/elements/' + id, { method: 'DELETE' });
                await loadMapElements();
            };

            // ===== TSA 态势感知 tab =====
            const topoData = ref(null);
            const topoLoading = ref(false);
            const loadTopo = async () => {
                topoLoading.value = true;
                topoData.value = await fetchJSON('/api/tsa/device-topo');
                topoLoading.value = false;
            };

            // ===== Media 媒体 tab =====
            const mediaList = ref([]);
            const mediaLoading = ref(false);
            const mediaUploading = ref(false);
            const loadMedia = async () => {
                mediaLoading.value = true;
                const data = await fetchJSON('/api/media');
                mediaList.value = data ? (Array.isArray(data) ? data : (data.files || [])) : [];
                mediaLoading.value = false;
            };
            const triggerMediaUpload = async () => {
                mediaUploading.value = true;
                await fetchJSON('/api/media/trigger', { method: 'POST' });
                mediaUploading.value = false;
                await loadMedia();
            };

            // ===== 航线 tab =====
            const waylineList = ref([]);
            const waylineLoading = ref(false);
            const loadWayline = async () => {
                waylineLoading.value = true;
                const data = await fetchJSON('/api/wayline/list');
                waylineList.value = data ? (data.waylines || data || []) : [];
                waylineLoading.value = false;
            };
            const toggleFavorite = async (item, favorite) => {
                const method = favorite ? 'POST' : 'DELETE';
                await fetchJSON('/api/wayline/favorites', {
                    method: method,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ wayline_id: item.id })
                });
                await loadWayline();
            };

            // ===== MOP tab =====
            const mopStatus = ref(null);
            const mopLoading = ref(false);
            const mopConnecting = ref(false);
            const loadMopStatus = async () => {
                mopLoading.value = true;
                mopStatus.value = await fetchJSON('/api/mop/status');
                mopLoading.value = false;
            };
            const connectMop = async () => {
                mopConnecting.value = true;
                await fetchJSON('/api/mop/connect', { method: 'POST' });
                mopConnecting.value = false;
                await loadMopStatus();
            };
            const disconnectMop = async () => {
                mopConnecting.value = true;
                await fetchJSON('/api/mop/disconnect', { method: 'POST' });
                mopConnecting.value = false;
                await loadMopStatus();
            };

            // tab 切换时按需加载
            watch(activeTab, (tab) => {
                if (tab === 'api') loadApiConfig();
                else if (tab === 'ws') loadWsEvents();
                else if (tab === 'map') loadMapElements();
                else if (tab === 'tsa') loadTopo();
                else if (tab === 'media') loadMedia();
                else if (tab === 'wayline') loadWayline();
                else if (tab === 'mop') loadMopStatus();
            });

            return {
                activeTab,
                apiConfig,
                wsEvents, wsLoading, clearWsEvents,
                mapElements, mapLoading, deleteMapElement,
                topoData, topoLoading,
                mediaList, mediaLoading, mediaUploading, triggerMediaUpload,
                waylineList, waylineLoading, toggleFavorite,
                mopStatus, mopLoading, mopConnecting, connectMop, disconnectMop,
            };
        },
        template: `
            <el-card class="right-card">
                <template #header>
                    <span>JSBridge 模块</span>
                </template>
                <el-tabs v-model="activeTab" type="border-card">

                    <!-- API 模块：配置状态展示 -->
                    <el-tab-pane label="API" name="api">
                        <div v-if="apiConfig" style="font-size:13px;line-height:2;">
                            <div>HTTP 地址: {{ apiConfig.http_base_url || '-' }}</div>
                            <div>HTTP Token:
                                <el-tag :type="apiConfig.http_token ? 'success' : 'info'" size="small">{{ apiConfig.http_token ? '已配置' : '未配置' }}</el-tag>
                            </div>
                            <div>WS 地址: {{ apiConfig.ws_url || '-' }}</div>
                            <div>WS Token:
                                <el-tag :type="apiConfig.ws_token ? 'success' : 'info'" size="small">{{ apiConfig.ws_token ? '已配置' : '未配置' }}</el-tag>
                            </div>
                        </div>
                        <div v-else style="color:#909399;font-size:13px;">加载中...</div>
                        <p style="color:#909399;font-size:12px;margin-top:8px;">
                            token 配置见上方「JSBridge 鉴权配置」卡片。各模块 API 调用分散到对应 tab。
                        </p>
                    </el-tab-pane>

                    <!-- WS 模块：事件展示 -->
                    <el-tab-pane label="WS" name="ws">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                            <span style="font-size:13px;">WS 事件（{{ wsEvents.length }}）</span>
                            <div>
                                <el-button size="small" text @click="loadWsEvents" :loading="wsLoading">刷新</el-button>
                                <el-button size="small" text type="danger" @click="clearWsEvents">清除</el-button>
                            </div>
                        </div>
                        <div v-if="wsEvents.length === 0" style="color:#909399;font-size:13px;">无事件</div>
                        <div v-for="(evt, i) in wsEvents" :key="i" style="background:#f5f7fa;border-radius:4px;padding:6px;margin-bottom:6px;font-size:12px;">
                            <el-tag size="small" :type="evt.source === 'map' ? 'success' : 'warning'">{{ evt.source }}</el-tag>
                            <span style="margin-left:6px;color:#606266;">{{ JSON.stringify(evt) }}</span>
                        </div>
                    </el-tab-pane>

                    <!-- 地图元素模块 -->
                    <el-tab-pane label="地图" name="map">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                            <span style="font-size:13px;">地图元素（{{ mapElements.length }}）</span>
                            <el-button size="small" text @click="loadMapElements" :loading="mapLoading">刷新</el-button>
                        </div>
                        <div v-if="mapElements.length === 0" style="color:#909399;font-size:13px;">无元素</div>
                        <div v-for="el in mapElements" :key="el.id" style="display:flex;justify-content:space-between;align-items:center;background:#f5f7fa;border-radius:4px;padding:6px;margin-bottom:6px;font-size:12px;">
                            <span>{{ el.name || el.id }}（{{ el.element_type || '-' }}）</span>
                            <el-button size="small" text type="danger" @click="deleteMapElement(el.id)">删除</el-button>
                        </div>
                    </el-tab-pane>

                    <!-- TSA 态势感知模块 -->
                    <el-tab-pane label="TSA" name="tsa">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                            <span style="font-size:13px;">设备拓扑</span>
                            <el-button size="small" text @click="loadTopo" :loading="topoLoading">刷新</el-button>
                        </div>
                        <div v-if="topoData" style="font-size:12px;background:#f5f7fa;border-radius:4px;padding:8px;max-height:300px;overflow:auto;">
                            <pre>{{ JSON.stringify(topoData, null, 2) }}</pre>
                        </div>
                        <div v-else style="color:#909399;font-size:13px;">加载中...</div>
                    </el-tab-pane>

                    <!-- Media 媒体模块 -->
                    <el-tab-pane label="Media" name="media">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                            <span style="font-size:13px;">媒体文件（{{ mediaList.length }}）</span>
                            <div>
                                <el-button size="small" type="primary" @click="triggerMediaUpload" :loading="mediaUploading">触发上传</el-button>
                                <el-button size="small" text @click="loadMedia" :loading="mediaLoading">刷新</el-button>
                            </div>
                        </div>
                        <div v-if="mediaList.length === 0" style="color:#909399;font-size:13px;">无媒体文件</div>
                        <div v-for="(m, i) in mediaList" :key="i" style="background:#f5f7fa;border-radius:4px;padding:6px;margin-bottom:6px;font-size:12px;">
                            <div>{{ m.file_name || m.name || '-' }}</div>
                            <div style="color:#909399;">{{ m.size ? (m.size / 1024).toFixed(1) + ' KB' : '-' }} | {{ m.status || '-' }}</div>
                        </div>
                    </el-tab-pane>

                    <!-- 航线模块 -->
                    <el-tab-pane label="航线" name="wayline">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                            <span style="font-size:13px;">航线列表（{{ waylineList.length }}）</span>
                            <el-button size="small" text @click="loadWayline" :loading="waylineLoading">刷新</el-button>
                        </div>
                        <div v-if="waylineList.length === 0" style="color:#909399;font-size:13px;">无航线</div>
                        <div v-for="(w, i) in waylineList" :key="i" style="display:flex;justify-content:space-between;align-items:center;background:#f5f7fa;border-radius:4px;padding:6px;margin-bottom:6px;font-size:12px;">
                            <span>{{ w.name || w.template_name || '-' }}</span>
                            <el-button size="small" text :type="w.favorited ? 'warning' : 'default'" @click="toggleFavorite(w, !w.favorited)">{{ w.favorited ? '取消收藏' : '收藏' }}</el-button>
                        </div>
                    </el-tab-pane>

                    <!-- MOP 模块 -->
                    <el-tab-pane label="MOP" name="mop">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                            <span style="font-size:13px;">MOP 通道状态</span>
                            <div>
                                <el-button size="small" text @click="loadMopStatus" :loading="mopLoading">刷新</el-button>
                                <el-button v-if="!mopStatus || !mopStatus.connected" size="small" type="primary" @click="connectMop" :loading="mopConnecting">连接</el-button>
                                <el-button v-else size="small" type="danger" @click="disconnectMop" :loading="mopConnecting">断开</el-button>
                            </div>
                        </div>
                        <div v-if="mopStatus" style="font-size:13px;line-height:2;">
                            <div>连接状态:
                                <el-tag :type="mopStatus.connected ? 'success' : 'info'" size="small">{{ mopStatus.connected ? '已连接' : '未连接' }}</el-tag>
                            </div>
                            <div>MOP Host: {{ mopStatus.host || '-' }}</div>
                        </div>
                        <div v-else style="color:#909399;font-size:13px;">加载中...</div>
                    </el-tab-pane>

                </el-tabs>
            </el-card>
        `
    });
}
