/**
 * Pilot 配置面板组件 — JSBridge token 鉴权
 *
 * Pilot 模式跳过 DJI JSBridge 层，直接通过 HTTP/WS 与 hivemind 通信。
 * 真实 Pilot 2 中 H5 页面通过 JSBridge 获取 token，模拟器简化为直接配置 token。
 * token 配置后，API/WS 模块可用这些凭据访问 hivemind 平台。
 *
 * 配置端点：GET/POST /api/config/pilot（部分更新，不持久化，重启回退 yml 默认值）
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotConfigPanel(app) 注册。
 */
function registerPilotConfigPanel(app) {
    const { ref, reactive, computed, inject, onMounted } = Vue;

    app.component('pilot-config', {
        setup() {
            const ctx = inject('ctx');
            const { fetchJSON } = ctx;

            const config = reactive({
                http_base_url: '',
                http_token: '',
                ws_url: '',
                ws_token: '',
            });
            const saving = ref(false);
            const saveResult = ref('');

            const loadConfig = async () => {
                const data = await fetchJSON('/api/config/pilot');
                if (data) {
                    config.http_base_url = data.http_base_url || '';
                    config.http_token = data.http_token || '';
                    config.ws_url = data.ws_url || '';
                    config.ws_token = data.ws_token || '';
                }
            };

            const saveConfig = async () => {
                saving.value = true;
                saveResult.value = '';
                const data = await fetchJSON('/api/config/pilot', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(config)
                });
                saving.value = false;
                saveResult.value = data ? '保存成功' : '保存失败';
                if (data) setTimeout(() => { saveResult.value = ''; }, 3000);
            };

            const httpConfigured = computed(() => !!config.http_token);
            const wsConfigured = computed(() => !!config.ws_token);

            onMounted(loadConfig);

            return { config, saving, saveResult, httpConfigured, wsConfigured, saveConfig };
        },
        template: `
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>JSBridge 鉴权配置</span>
                        <el-button size="small" type="primary" :loading="saving" @click="saveConfig">保存</el-button>
                    </div>
                </template>
                <el-form label-width="90px" size="small">
                    <el-form-item label="HTTP 地址">
                        <el-input v-model="config.http_base_url" placeholder="http://hivemind:8080"></el-input>
                    </el-form-item>
                    <el-form-item label="HTTP Token">
                        <el-input v-model="config.http_token" placeholder="hivemind HTTP 鉴权 token">
                            <template #append>
                                <el-tag :type="httpConfigured ? 'success' : 'info'" size="small">{{ httpConfigured ? '已配置' : '未配置' }}</el-tag>
                            </template>
                        </el-input>
                    </el-form-item>
                    <el-form-item label="WS 地址">
                        <el-input v-model="config.ws_url" placeholder="ws://hivemind:8081"></el-input>
                    </el-form-item>
                    <el-form-item label="WS Token">
                        <el-input v-model="config.ws_token" placeholder="hivemind WS 鉴权 token">
                            <template #append>
                                <el-tag :type="wsConfigured ? 'success' : 'info'" size="small">{{ wsConfigured ? '已配置' : '未配置' }}</el-tag>
                            </template>
                        </el-input>
                    </el-form-item>
                </el-form>
                <div v-if="saveResult" style="font-size:12px;color:#67c23a;text-align:right;">{{ saveResult }}</div>
                <p style="color:#909399;font-size:12px;line-height:1.6;margin-top:8px;">
                    配置 token 后，API/WS 模块可用这些凭据访问 hivemind 平台。<br>
                    Pilot 模式跳过 DJI JSBridge 层，token 直接用于 HTTP/WS 通信。
                </p>
            </el-card>
        `
    });
}
