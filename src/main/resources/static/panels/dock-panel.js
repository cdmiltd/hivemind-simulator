/**
 * Dock 面板组件（机场上云模式专属）
 *
 * 包含：状态模拟、HMS 异常、自定义飞行区、PSDK 喊话器、ESDK 互联互通、
 *       远程日志、固件升级、直播模拟、任务模拟、位置模拟
 *
 * 共享状态/方法通过 inject('ctx') 获取，方法内部以 ctx 前缀访问。
 * 模板中变量名不带 ctx 前缀（return ...ctx 展开后为顶层属性）。
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerDockPanel(app) 注册。
 */
function registerDockPanel(app) {
    const { ref, reactive, computed, watch, onMounted, onUnmounted, inject } = Vue;

    app.component('dock-panel', {
        setup() {
            const ctx = inject('ctx');

            // ===== Dock 专属状态 =====

            // 异常模拟（HMS 上报）
            // 选项 value 与后端 HmsSimulator.AlarmType 枚举常量名一致（小写）
            const hmsOptions = [
                { value: 'wind_high',       label: '风速过大（≥9 m/s）' },
                { value: 'rain_heavy',      label: '雨量过大' },
                { value: 'signal_weak',     label: '图传信号弱' },
                { value: 'signal_lost',     label: '图传信号丢失' },
                { value: 'altitude_limit',  label: '高度超限' },
                { value: 'distance_limit',  label: '距离超限（限远）' },
                { value: 'battery_low',     label: '电池电量低（严重低电量）' },
            ];
            const hmsSelected = ref([]);
            const hmsReporting = ref(false);
            const activeTab = ref('status');

            // 自定义飞行区模拟（Dock3）
            const faLocation = reactive({ area_id: '', area_distance: 100.0, is_in_area: true });
            const faReporting = ref(false);
            const faSync = reactive({ status: 'synchronized', reason: 0, file_name: '', file_checksum: '' });
            const faSyncReporting = ref(false);
            const faGetting = ref(false);
            const faSyncStatusOptions = [
                { value: 'synchronized', label: '已同步' },
                { value: 'synchronizing', label: '同步中' },
                { value: 'wait_sync', label: '待同步' },
                { value: 'fail', label: '失败' },
                { value: 'switch_fail', label: '使能开关失败' },
            ];

            // PSDK 喊话器与负载事件模拟（Dock3）
            const psdk = reactive({
                psdkIndex: 2,             // psdk_index 必填，默认 2（与 DJI Example 一致）
                state: null,              // 查询返回的状态
                lastSpokenTtsText: '',    // 上次朗读的 tts.text（变化检测）
                lastPlayedAudioUrl: '',   // 上次播放的 audio url（变化检测）
                // TTS 播放进度
                ttsStatus: 'in_progress',
                ttsPercent: 50,
                ttsStepKey: 'upload',
                ttsMd5: '',
                ttsReporting: false,
                // 音频播放进度
                audioStatus: 'in_progress',
                audioPercent: 89,
                audioStepKey: 'upload',
                audioMd5: '',
                audioReporting: false,
                // 浮窗文本
                floatValue: 'System time : 1193683 ms',
                floatReporting: false,
                // UI 资源包上传结果
                uiObjectKey: 'f4a4a171/widget',
                uiSize: 43488,
                uiResult: 0,
                uiReporting: false,
                uiUploading: false,
                customDataValue: '',
                lastCustomData: '',
            });

            // ESDK 互联互通状态
            const esdk = reactive({
                customDataValue: '',
                lastCustomData: '',
            });

            // 远程日志状态
            const remoteLog = reactive({
                uploading: false,
                files: [],
                progressStatus: 'ok',
                progressPercent: 100,
            });

            // 固件升级状态
            const ota = reactive({
                upgrading: false,
                devices: [],
                progressStatus: 'in_progress',
                progressStep: 'download_firmware',
                progressPercent: 50,
            });

            // 固件升级：选择终态时自动设置合理默认值（体现过程语义，DJI 文档未明确终态 percent 取值）
            watch(() => ota.progressStatus, (status) => {
                if (status === 'sent' || status === 'rejected') {
                    ota.progressPercent = 0;
                    ota.progressStep = 'download_firmware';
                } else if (status === 'ok') {
                    ota.progressPercent = 100;
                    ota.progressStep = 'upgrade_firmware';
                }
                // in_progress/paused/failed/canceled/timeout 保持用户值（进度不定，可手动覆盖）
            });

            // ===== Dock 专属方法 =====

            // HMS 异常模拟上报
            const canReportHms = computed(() =>
                ctx.mqttConnected.value && hmsSelected.value.length > 0 && !hmsReporting.value
            );

            async function triggerHms() {
                if (!canReportHms.value) return;
                hmsReporting.value = true;
                const data = await ctx.fetchJSON('/api/hms/trigger', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ types: hmsSelected.value })
                });
                hmsReporting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('HMS 已上报：' + (data.count || 0) + ' 条告警');
                    } else {
                        ElementPlus.ElMessage.error(data.message || 'HMS 上报失败');
                    }
                    // 上报后立即刷新日志，便于查看实际发出的报文
                    await ctx.refreshLogs();
                }
            }

            // 自定义飞行区 - 位置告警推送
            async function triggerFaLocation() {
                if (!ctx.mqttConnected.value) return;
                faReporting.value = true;
                const data = await ctx.fetchJSON('/api/flight-areas/drone-location', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ locations: [{ area_id: faLocation.area_id, area_distance: faLocation.area_distance, is_in_area: faLocation.is_in_area }] })
                });
                faReporting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('位置告警已上报');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '位置告警上报失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // 自定义飞行区 - 同步进度上报
            async function triggerFaSync() {
                if (!ctx.mqttConnected.value) return;
                faSyncReporting.value = true;
                const body = { status: faSync.status, reason: faSync.reason };
                if (faSync.file_name) {
                    body.file = { name: faSync.file_name, checksum: faSync.file_checksum || 'sha256' };
                }
                const data = await ctx.fetchJSON('/api/flight-areas/sync-progress', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                faSyncReporting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('同步进度已上报');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '同步进度上报失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // 自定义飞行区 - 获取文件请求
            async function triggerFaGet() {
                if (!ctx.mqttConnected.value) return;
                faGetting.value = true;
                const data = await ctx.fetchJSON('/api/flight-areas/get', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' }
                });
                faGetting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('飞行区文件获取成功');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '飞行区文件获取失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // PSDK - 查询状态（轮询时检测平台下发指令的联动）
            async function loadPsdkState() {
                const data = await ctx.fetchJSON('/api/psdk/state?psdk_index=' + psdk.psdkIndex, {});
                if (data && data.success) {
                    psdk.state = data;
                    // 若 TTS/Audio md5 为空，用默认值填充占位
                    if (!psdk.ttsMd5) psdk.ttsMd5 = '';
                    if (!psdk.audioMd5) psdk.audioMd5 = '';

                    // 平台下发 speaker_tts_play_start 后，自动朗读 tts.text
                    const ttsText = data.last_tts ? data.last_tts.text : '';
                    if (ttsText && ttsText !== psdk.lastSpokenTtsText) {
                        psdk.lastSpokenTtsText = ttsText;
                        speakTts(ttsText);
                    }

                    // 平台下发 speaker_audio_play_start 后，尝试播放音频 url
                    const audioUrl = data.last_audio_file ? data.last_audio_file.url : '';
                    if (audioUrl && audioUrl !== psdk.lastPlayedAudioUrl) {
                        psdk.lastPlayedAudioUrl = audioUrl;
                        playAudioUrl(audioUrl);
                    }

                    // 更新 cloud→PSDK 自定义消息
                    psdk.lastCustomData = data.last_custom_data || '';
                } else if (data) {
                    ElementPlus.ElMessage.error(data.message || 'PSDK 状态查询失败');
                }
            }

            // PSDK - 触发 TTS 播放进度（同时浏览器朗读内置 TTS 文本，让用户听到"喊话"效果）
            async function triggerPsdkTts() {
                if (!ctx.mqttConnected.value) return;
                psdk.ttsReporting.value = true;
                const body = {
                    psdk_index: psdk.psdkIndex,
                    status: psdk.ttsStatus,
                    percent: psdk.ttsPercent,
                    step_key: psdk.ttsStepKey,
                };
                if (psdk.ttsMd5) body.md5 = psdk.ttsMd5;
                const data = await ctx.fetchJSON('/api/psdk/tts-play-progress', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                psdk.ttsReporting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('TTS 进度已上报');
                        // 同时使用浏览器 SpeechSynthesis 朗读内置 TTS 文本
                        speakTts();
                    } else {
                        ElementPlus.ElMessage.error(data.message || 'TTS 进度上报失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // PSDK - 触发音频播放进度
            async function triggerPsdkAudio() {
                if (!ctx.mqttConnected.value) return;
                psdk.audioReporting.value = true;
                const body = {
                    psdk_index: psdk.psdkIndex,
                    status: psdk.audioStatus,
                    percent: psdk.audioPercent,
                    step_key: psdk.audioStepKey,
                };
                if (psdk.audioMd5) body.md5 = psdk.audioMd5;
                const data = await ctx.fetchJSON('/api/psdk/audio-play-progress', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                psdk.audioReporting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('音频进度已上报');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '音频进度上报失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // PSDK - 推送浮窗文本
            async function triggerPsdkFloat() {
                if (!ctx.mqttConnected.value) return;
                psdk.floatReporting.value = true;
                const data = await ctx.fetchJSON('/api/psdk/floating-window-text', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ psdk_index: psdk.psdkIndex, value: psdk.floatValue })
                });
                psdk.floatReporting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('浮窗文本已推送');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '浮窗文本推送失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // PSDK - 上报 UI 资源包上传结果
            async function triggerPsdkUiResource() {
                if (!ctx.mqttConnected.value) return;
                psdk.uiReporting.value = true;
                const data = await ctx.fetchJSON('/api/psdk/ui-resource-upload-result', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        psdk_index: psdk.psdkIndex,
                        object_key: psdk.uiObjectKey,
                        size: psdk.uiSize,
                        result: psdk.uiResult,
                    })
                });
                psdk.uiReporting.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('UI 资源包上传结果已上报');
                    } else {
                        ElementPlus.ElMessage.error(data.message || 'UI 资源包上传结果上报失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // PSDK - UI 资源完整上传流程（storage_config_get → 上传文件 → 上报事件）
            async function uploadPsdkUiResource() {
                if (!ctx.mqttConnected.value) return;
                psdk.uiUploading.value = true;
                const data = await ctx.fetchJSON('/api/psdk/ui-resource-upload', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ psdk_index: psdk.psdkIndex })
                });
                psdk.uiUploading.value = false;
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('PSDK UI 资源上传流程已完成');
                    } else {
                        ElementPlus.ElMessage.error(data.message || 'PSDK UI 资源上传失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // PSDK - 互联互通（PSDK→Cloud 自定义消息推送）
            async function triggerPsdkCustomData() {
                if (!ctx.mqttConnected.value) return;
                if (!psdk.customDataValue) {
                    ElementPlus.ElMessage.warning('请输入数据内容');
                    return;
                }
                const data = await ctx.fetchJSON('/api/psdk/custom-data-from-psdk', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ value: psdk.customDataValue })
                });
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('自定义消息已推送');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '推送失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // ESDK - 互联互通（ESDK→Cloud 自定义消息推送）
            async function triggerEsdkCustomData() {
                if (!ctx.mqttConnected.value) return;
                if (!esdk.customDataValue) {
                    ElementPlus.ElMessage.warning('请输入数据内容');
                    return;
                }
                const data = await ctx.fetchJSON('/api/esdk/custom-data-from-esdk', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ value: esdk.customDataValue })
                });
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('自定义消息已推送');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '推送失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // ESDK - 加载状态（查询 Cloud→ESDK 消息）
            async function loadEsdkState() {
                if (!ctx.mqttConnected.value) return;
                const data = await ctx.fetchJSON('/api/esdk/state');
                if (data) {
                    esdk.lastCustomData = data.last_custom_data || '';
                }
            }

            // 远程日志 - 加载状态
            async function loadRemoteLogState() {
                const data = await ctx.fetchJSON('/api/remote-log/state');
                if (data) {
                    remoteLog.uploading = data.uploading || false;
                    remoteLog.files = data.files || [];
                }
            }

            // 远程日志 - 触发进度
            async function triggerRemoteLogProgress() {
                if (!ctx.mqttConnected.value) return;
                const data = await ctx.fetchJSON('/api/remote-log/progress', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ status: remoteLog.progressStatus, percent: remoteLog.progressPercent })
                });
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('远程日志进度已触发');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '触发失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // 固件升级 - 加载状态
            async function loadOtaState() {
                const data = await ctx.fetchJSON('/api/ota/state');
                if (data) {
                    ota.upgrading = data.upgrading || false;
                    ota.devices = data.devices || [];
                }
            }

            // 固件升级 - 触发进度
            async function triggerOtaProgress() {
                if (!ctx.mqttConnected.value) return;
                const data = await ctx.fetchJSON('/api/ota/progress', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        status: ota.progressStatus,
                        current_step: ota.progressStep,
                        percent: ota.progressPercent
                    })
                });
                if (data) {
                    if (data.success) {
                        ElementPlus.ElMessage.success('固件升级进度已触发');
                    } else {
                        ElementPlus.ElMessage.error(data.message || '触发失败');
                    }
                    await ctx.refreshLogs();
                }
            }

            // ===== 手动触发媒体上传 =====
            const mediaTriggerVisible = ref(false);
            const mediaTriggerLoading = ref(false);
            // 默认 flight_id: 当前任务 flight_id,没任务则用 MANUAL-时间戳;默认 3 个文件
            const mediaTriggerForm = reactive({
                flight_id: '',
                file_count: 3,
            });

            function openMediaTriggerDialog() {
                mediaTriggerForm.flight_id = ctx.task.value?.flight_id
                    || ('MANUAL-' + new Date().toISOString().slice(0, 19).replace(/[-:T]/g, ''));
                mediaTriggerForm.file_count = 3;
                mediaTriggerVisible.value = true;
            }

            async function doTriggerMediaUpload() {
                if (!mediaTriggerForm.flight_id.trim()) {
                    ElementPlus.ElMessage.warning('Flight ID 不能为空');
                    return;
                }
                if (!mediaTriggerForm.file_count || mediaTriggerForm.file_count < 1) {
                    ElementPlus.ElMessage.warning('文件数量必须 ≥ 1');
                    return;
                }
                mediaTriggerLoading.value = true;
                try {
                    const data = await ctx.fetchJSON('/api/media/trigger', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            flight_id: mediaTriggerForm.flight_id.trim(),
                            file_count: Number(mediaTriggerForm.file_count),
                        }),
                    });
                    if (data && data.success) {
                        ElementPlus.ElMessage.success(
                            `媒体上报已异步触发（Flight: ${data.flight_id}, 数量: ${data.file_count}），10~30 秒后在下方列表可见上报结果`
                        );
                        mediaTriggerVisible.value = false;
                        // 上传是异步的,立即 refreshMedia 看不到,等 5 秒再刷一次
                        setTimeout(async () => { await ctx.refreshMedia(); }, 5000);
                    } else {
                        ElementPlus.ElMessage.error((data && data.message) || '触发失败');
                    }
                    await ctx.refreshLogs();
                } finally {
                    mediaTriggerLoading.value = false;
                }
            }

            // 浏览器内置 TTS 朗读（让用户听到"喊话"效果）
            // text 可选：传入则朗读指定文本（平台下发的 tts.text），不传则朗读默认 TTS 文本
            function speakTts(text) {
                try {
                    if (!('speechSynthesis' in window)) {
                        ElementPlus.ElMessage.warning('浏览器不支持语音合成，无法朗读 TTS');
                        return;
                    }
                    window.speechSynthesis.cancel();
                    const utter = new SpeechSynthesisUtterance(
                        text || psdk.state?.default_tts_text || '模拟器TTS测试喊话内容');
                    utter.lang = 'zh-CN';
                    utter.rate = 1.0;
                    window.speechSynthesis.speak(utter);
                } catch (e) {
                    console.warn('TTS 朗读失败', e);
                }
            }

            // 尝试播放平台下发的音频文件 url（可能因鉴权失败）
            function playAudioUrl(url) {
                try {
                    const audio = new Audio(url);
                    audio.crossOrigin = 'anonymous';
                    audio.play().catch(() => {
                        ElementPlus.ElMessage.warning('音频播放失败（可能需要鉴权）：' + url.substring(0, 50) + '...');
                    });
                } catch (e) {
                    console.warn('音频播放失败', e);
                }
            }

            // ===== Dock 专属轮询（从外壳 pollAll 拆出，组件挂载时启动） =====
            let dockPollTimer = null;
            onMounted(() => {
                dockPollTimer = setInterval(async () => {
                    await Promise.all([loadPsdkState(), loadEsdkState()]);
                }, 2000);
            });
            onUnmounted(() => {
                if (dockPollTimer) {
                    clearInterval(dockPollTimer);
                    dockPollTimer = null;
                }
            });

            return {
                ...ctx,
                // Dock 专属状态
                activeTab,
                hmsOptions, hmsSelected, hmsReporting,
                faLocation, faReporting, faSync, faSyncReporting, faGetting, faSyncStatusOptions,
                psdk, esdk, remoteLog, ota,
                mediaTriggerVisible, mediaTriggerLoading, mediaTriggerForm,
                // Dock 专属方法
                canReportHms, triggerHms,
                triggerFaLocation, triggerFaSync, triggerFaGet,
                loadPsdkState, triggerPsdkTts, triggerPsdkAudio, triggerPsdkFloat,
                triggerPsdkUiResource, uploadPsdkUiResource, triggerPsdkCustomData,
                triggerEsdkCustomData, loadEsdkState,
                loadRemoteLogState, triggerRemoteLogProgress,
                loadOtaState, triggerOtaProgress,
                openMediaTriggerDialog, doTriggerMediaUpload,
            };
        },
        template: `
            <el-tabs v-model="activeTab" class="dock-tabs">
            <el-tab-pane label="设备状态" name="status">
            <!-- 状态模拟（Dock 版：电量/温度/湿度/风速/舱盖/飞行器/静音模式） -->
            <el-card class="right-card" header="状态模拟">
                <el-form label-width="72px" size="small">
                    <el-form-item label="电量(%)">
                        <el-slider v-model="editState.batteryPercent" :min="0" :max="100" @change="saveState"></el-slider>
                    </el-form-item>
                    <el-form-item label="温度(℃)">
                        <el-input-number v-model="editState.dockTemperature" :precision="1" :step="0.5" size="small" @change="saveState"></el-input-number>
                    </el-form-item>
                    <el-form-item label="湿度(%)">
                        <el-input-number v-model="editState.dockHumidity" :min="0" :max="100" :step="1" size="small" @change="saveState"></el-input-number>
                    </el-form-item>
                    <el-form-item label="风速(m/s)">
                        <el-input-number v-model="editState.windSpeed" :min="0" :step="0.5" size="small" @change="saveState"></el-input-number>
                    </el-form-item>
                    <el-form-item label="舱盖">
                        <el-switch v-model="editState.coverOpen" @change="saveState"></el-switch>
                    </el-form-item>
                    <el-form-item label="飞行器">
                        <el-switch v-model="editState.droneInDock" @change="saveState"></el-switch>
                        <span style="margin-left: 4px; font-size: 12px; color: #909399;">{{ editState.droneInDock ? '在舱' : '不在舱' }}</span>
                        <el-radio-group v-model="editState.droneActivated" size="small" :disabled="!editState.droneInDock" @change="saveState" style="margin-left: 12px;">
                            <el-radio-button :value="false">休眠</el-radio-button>
                            <el-radio-button :value="true">激活</el-radio-button>
                        </el-radio-group>
                    </el-form-item>
                    <el-form-item v-if="connConfig.dock_type === 'DOCK1' || connConfig.dock_type === 'DOCK2'" label="静音模式">
                        <el-switch v-model="editState.silentMode" :active-value="1" :inactive-value="0" @change="saveState"></el-switch>
                    </el-form-item>
                </el-form>
            </el-card>

            <!-- 异常模拟（HMS 告警上报） -->
            <el-card class="right-card">
                <template #header>
                    <span>异常模拟</span>
                    <el-tag v-if="hmsReporting" type="warning" size="small" style="margin-left: 8px;">上报中</el-tag>
                </template>
                <el-checkbox-group v-model="hmsSelected" size="small">
                    <div v-for="opt in hmsOptions" :key="opt.value" style="margin-bottom: 6px;">
                        <el-checkbox :label="opt.value">{{ opt.label }}</el-checkbox>
                    </div>
                </el-checkbox-group>
                <div style="margin-top: 12px;">
                    <el-button type="warning" size="small" :disabled="!canReportHms" :loading="hmsReporting" @click="triggerHms">上报 HMS</el-button>
                    <span v-if="!mqttConnected" style="margin-left: 8px; font-size: 12px; color: #909399;">需先连接 MQTT</span>
                </div>
            </el-card>

            </el-tab-pane>
            <el-tab-pane label="飞行任务" name="task">

            <!-- 任务模拟（Dock 版：航线任务+媒体文件） -->
            <el-card class="right-card">
                <template #header>
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <span>任务模拟</span>
                        <el-button size="small" type="success" :disabled="!mqttConnected" :loading="mediaTriggerLoading" @click="openMediaTriggerDialog">上报媒体</el-button>
                    </div>
                </template>
                <el-descriptions :column="1" border size="small">
                    <el-descriptions-item label="任务状态">
                        <el-tag :type="task.active ? 'warning' : 'info'" size="small">
                            {{ task.active ? (task.paused ? '暂停' : '执行中') : '空闲' }}
                        </el-tag>
                    </el-descriptions-item>
                    <el-descriptions-item label="Flight ID">{{ task.flight_id || '-' }}</el-descriptions-item>
                    <el-descriptions-item label="进度">{{ task.percent || 0 }}%</el-descriptions-item>
                    <el-descriptions-item label="步骤">{{ task.current_step || '-' }} / 35</el-descriptions-item>
                </el-descriptions>
                <el-progress :percentage="task.percent || 0" :status="task.active ? undefined : 'success'" style="margin-top: 8px;"></el-progress>
                <div style="margin-top: 8px;" v-if="!mqttConnected">
                    <span style="font-size: 12px; color: #909399;">需先连接 MQTT 才能上报媒体</span>
                </div>
                <div style="margin-top: 8px;">
                    <div style="font-size: 12px; color: #909399; margin-bottom: 4px;">媒体文件 ({{ media.length }})</div>
                    <div v-for="f in media.slice(-5)" :key="f.name" style="font-size: 12px; color: #606266;">
                        {{ f.name }} - {{ f.upload_time }}
                    </div>
                </div>
            </el-card>

            <!-- 手动触发媒体上报弹窗（协议流程触发；素材文件经直播配置弹窗「上传媒体」按钮传入 media-dir） -->
            <el-dialog v-model="mediaTriggerVisible" title="触发媒体上报" width="420px" :close-on-click-modal="false">
                <el-form :model="mediaTriggerForm" label-width="90px" size="small">
                    <el-form-item label="Flight ID" required>
                        <el-input v-model="mediaTriggerForm.flight_id" maxlength="64" placeholder="例如:MANUAL-20260819153000"></el-input>
                    </el-form-item>
                    <el-form-item label="文件数量" required>
                        <el-input-number v-model="mediaTriggerForm.file_count" :min="1" :max="50"></el-input-number>
                        <span style="margin-left: 8px; font-size: 12px; color: #909399;">(1~50,默认 3)</span>
                    </el-form-item>
                    <el-form-item label="说明">
                        <div style="font-size: 12px; color: #606266; line-height: 1.6;">
                            按 DJI 完整三阶段上传：<br>
                            ① storage_config_get 获取 STS 凭证 →<br>
                            ② S3 上传(照片+缩略图+DNG/视频等)→<br>
                            ③ file_upload_callback 通知平台。<br>
                            单文件约 5~10 秒,异步执行。
                        </div>
                    </el-form-item>
                </el-form>
                <template #footer>
                    <el-button @click="mediaTriggerVisible = false">取消</el-button>
                    <el-button type="primary" :loading="mediaTriggerLoading" @click="doTriggerMediaUpload">确认上报</el-button>
                </template>
            </el-dialog>

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

            <!-- 位置模拟（Dock 版：机场位置=起飞点=返航点） -->
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
                    机场位置作为起飞点与返航点，保存后重启依然有效。
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

            </el-tab-pane>
            <el-tab-pane label="负载设备" name="payload">

            <!-- PSDK 喊话器与负载事件模拟（Dock3） -->
            <el-card class="right-card">
                <template #header><span>PSDK 喊话器</span></template>
                <!-- psdk_index 必填 -->
                <div style="margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px dashed #e4e7ed;">
                    <div style="display: flex; gap: 8px; align-items: center;">
                        <span style="font-size: 13px; font-weight: 500;">psdk_index</span>
                        <el-input-number v-model="psdk.psdkIndex" size="small" :controls="false" :min="0" :max="3" style="flex: 1;"></el-input-number>
                        <el-button size="small" text @click="loadPsdkState">查询状态</el-button>
                    </div>
                    <div v-if="psdk.state" style="margin-top: 8px; font-size: 12px; color: #606266;">
                        音量: {{ psdk.state.play_volume }} | 模式: {{ psdk.state.play_mode === 0 ? '单次' : '循环' }} | 播放: {{ psdk.state.playing ? '是' : '否' }}<br>
                        默认TTS: {{ psdk.state.default_tts_text }} (md5: {{ psdk.state.default_tts_md5.substring(0, 8) }}...)<br>
                        <span v-if="psdk.state.last_tts">平台TTS: {{ psdk.state.last_tts.text }} (md5: {{ psdk.state.last_tts.md5.substring(0, 8) }}...)</span><br>
                        <span v-if="psdk.state.last_audio_file">平台音频: {{ psdk.state.last_audio_file.name }} ({{ psdk.state.last_audio_file.format }})</span><br>
                        <span v-if="psdk.state.input_box_text != null">文本框: {{ psdk.state.input_box_text }}</span><br>
                        <span v-if="psdk.state.widget_values">控件值: <span v-for="(v, k) in psdk.state.widget_values" :key="k">[{{ k }}]={{ v }} </span></span>
                    </div>
                </div>
                <!-- TTS 播放进度 -->
                <div style="margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px dashed #e4e7ed;">
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">TTS 播放进度（同时朗读内置文本）</div>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-select v-model="psdk.ttsStatus" size="small" style="flex: 1;">
                            <el-option label="处理中" value="in_progress"></el-option>
                            <el-option label="播放成功" value="ok"></el-option>
                        </el-select>
                        <el-select v-model="psdk.ttsStepKey" size="small" style="flex: 1;">
                            <el-option label="切换工作模式" value="change_work_mode"></el-option>
                            <el-option label="下载音频" value="download"></el-option>
                            <el-option label="编码 opus" value="encoding"></el-option>
                            <el-option label="开始播放" value="play"></el-option>
                            <el-option label="上传音频" value="upload"></el-option>
                        </el-select>
                    </div>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-input-number v-model="psdk.ttsPercent" size="small" :controls="false" :min="0" :max="100" placeholder="percent" style="flex: 1;"></el-input-number>
                        <el-input v-model="psdk.ttsMd5" size="small" placeholder="md5（可选，留空用默认）" style="flex: 2;"></el-input>
                    </div>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" :loading="psdk.ttsReporting" @click="triggerPsdkTts">上报 TTS 进度</el-button>
                </div>
                <!-- 音频播放进度 -->
                <div style="margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px dashed #e4e7ed;">
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">音频播放进度</div>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-select v-model="psdk.audioStatus" size="small" style="flex: 1;">
                            <el-option label="处理中" value="in_progress"></el-option>
                            <el-option label="播放成功" value="ok"></el-option>
                        </el-select>
                        <el-select v-model="psdk.audioStepKey" size="small" style="flex: 1;">
                            <el-option label="切换工作模式" value="change_work_mode"></el-option>
                            <el-option label="下载音频" value="download"></el-option>
                            <el-option label="编码 opus" value="encoding"></el-option>
                            <el-option label="开始播放" value="play"></el-option>
                            <el-option label="上传音频" value="upload"></el-option>
                        </el-select>
                    </div>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-input-number v-model="psdk.audioPercent" size="small" :controls="false" :min="0" :max="100" placeholder="percent" style="flex: 1;"></el-input-number>
                        <el-input v-model="psdk.audioMd5" size="small" placeholder="md5（可选，留空用默认）" style="flex: 2;"></el-input>
                    </div>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" :loading="psdk.audioReporting" @click="triggerPsdkAudio">上报音频进度</el-button>
                </div>
                <!-- 浮窗文本推送 -->
                <div style="margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px dashed #e4e7ed;">
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">浮窗文本推送</div>
                    <el-input v-model="psdk.floatValue" size="small" placeholder="浮窗内容" style="margin-bottom: 6px;"></el-input>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" :loading="psdk.floatReporting" @click="triggerPsdkFloat">推送浮窗文本</el-button>
                </div>
                <!-- UI 资源包上传结果 -->
                <div>
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">UI 资源包上传结果</div>
                    <el-input v-model="psdk.uiObjectKey" size="small" placeholder="object_key" style="margin-bottom: 6px;"></el-input>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-input-number v-model="psdk.uiSize" size="small" :controls="false" placeholder="size" style="flex: 1;"></el-input-number>
                        <el-input-number v-model="psdk.uiResult" size="small" :controls="false" placeholder="result" style="flex: 1;"></el-input-number>
                    </div>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" :loading="psdk.uiReporting" @click="triggerPsdkUiResource">上报上传结果</el-button>
                    <el-button size="small" :disabled="!mqttConnected" :loading="psdk.uiUploading" @click="uploadPsdkUiResource">完整上传流程</el-button>
                </div>
                <!-- PSDK 互联互通 -->
                <div style="border-top: 1px dashed #e4e7ed; padding-top: 12px;">
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">互联互通（自定义消息）</div>
                    <el-input v-model="psdk.customDataValue" size="small" placeholder="数据内容（< 256 字节）" style="margin-bottom: 6px;"></el-input>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" @click="triggerPsdkCustomData">推送 PSDK→Cloud</el-button>
                    <div v-if="psdk.lastCustomData" style="font-size: 12px; color: #909399; margin-top: 6px;">
                        Cloud→PSDK: {{ psdk.lastCustomData }}
                    </div>
                </div>
            </el-card>

            <!-- ESDK 互联互通 -->
            <el-card class="right-card">
                <template #header><span>ESDK 互联互通</span></template>
                <div style="margin-bottom: 8px;">
                    <el-input v-model="esdk.customDataValue" size="small" placeholder="数据内容（< 256 字节）" style="margin-bottom: 6px;"></el-input>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" @click="triggerEsdkCustomData">推送 ESDK→Cloud</el-button>
                    <div v-if="esdk.lastCustomData" style="font-size: 12px; color: #909399; margin-top: 6px;">
                        Cloud→ESDK: {{ esdk.lastCustomData }}
                    </div>
                </div>
            </el-card>

            </el-tab-pane>
            <el-tab-pane label="系统维护" name="system">

            <!-- 远程日志 -->
            <el-card class="right-card">
                <template #header><span>远程日志</span></template>
                <div style="margin-bottom: 8px;">
                    <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 6px;">
                        <el-button size="small" text @click="loadRemoteLogState">查询状态</el-button>
                        <span v-if="remoteLog.uploading" style="font-size: 12px; color: #e6a23c;">上传中...</span>
                    </div>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-select v-model="remoteLog.progressStatus" size="small" placeholder="状态" style="width: 120px;">
                            <el-option label="处理中" value="in_progress"></el-option>
                            <el-option label="完成" value="ok"></el-option>
                        </el-select>
                        <el-input-number v-model="remoteLog.progressPercent" size="small" :controls="false" :min="0" :max="100" style="flex: 1;"></el-input-number>
                        <el-button size="small" :disabled="!mqttConnected" @click="triggerRemoteLogProgress">触发进度</el-button>
                    </div>
                    <div v-if="remoteLog.files.length > 0" style="font-size: 12px; color: #909399;">
                        文件列表：{{ remoteLog.files.length }} 个
                    </div>
                </div>
            </el-card>

            <!-- 固件升级 -->
            <el-card class="right-card">
                <template #header><span>固件升级</span></template>
                <div style="margin-bottom: 8px;">
                    <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 6px;">
                        <el-button size="small" text @click="loadOtaState">查询状态</el-button>
                        <span v-if="ota.upgrading" style="font-size: 12px; color: #e6a23c;">升级中...</span>
                    </div>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-select v-model="ota.progressStatus" size="small" placeholder="状态" style="width: 110px;">
                            <el-option label="已下发" value="sent"></el-option>
                            <el-option label="执行中" value="in_progress"></el-option>
                            <el-option label="暂停" value="paused"></el-option>
                            <el-option label="成功" value="ok"></el-option>
                            <el-option label="失败" value="failed"></el-option>
                            <el-option label="取消" value="canceled"></el-option>
                            <el-option label="拒绝" value="rejected"></el-option>
                            <el-option label="超时" value="timeout"></el-option>
                        </el-select>
                        <el-select v-model="ota.progressStep" size="small" placeholder="步骤" style="width: 130px;">
                            <el-option label="下载固件" value="download_firmware"></el-option>
                            <el-option label="更新固件" value="upgrade_firmware"></el-option>
                        </el-select>
                        <el-input-number v-model="ota.progressPercent" size="small" :controls="false" :min="0" :max="100" style="flex: 1;"></el-input-number>
                        <el-button size="small" :disabled="!mqttConnected" @click="triggerOtaProgress">触发进度</el-button>
                    </div>
                    <div v-if="ota.devices.length > 0" style="font-size: 12px; color: #909399;">
                        升级设备：{{ ota.devices.length }} 台
                    </div>
                </div>
            </el-card>

            <!-- 自定义飞行区模拟（Dock3） -->
            <el-card class="right-card">
                <template #header><span>自定义飞行区</span></template>
                <!-- 位置告警 -->
                <div style="margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px dashed #e4e7ed;">
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">位置告警推送</div>
                    <el-input v-model="faLocation.area_id" size="small" placeholder="飞行区 ID" style="margin-bottom: 6px;"></el-input>
                    <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 6px;">
                        <el-input-number v-model="faLocation.area_distance" size="small" :controls="false" style="flex: 1;"></el-input-number>
                        <el-checkbox v-model="faLocation.is_in_area">在区内</el-checkbox>
                    </div>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" :loading="faReporting" @click="triggerFaLocation">推送位置告警</el-button>
                </div>
                <!-- 同步进度 -->
                <div style="margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px dashed #e4e7ed;">
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">同步进度上报</div>
                    <el-select v-model="faSync.status" size="small" style="width: 100%; margin-bottom: 6px;">
                        <el-option v-for="opt in faSyncStatusOptions" :key="opt.value" :label="opt.label" :value="opt.value"></el-option>
                    </el-select>
                    <div style="display: flex; gap: 8px; margin-bottom: 6px;">
                        <el-input-number v-model="faSync.reason" size="small" :controls="false" placeholder="reason" style="flex: 1;"></el-input-number>
                        <el-input v-model="faSync.file_name" size="small" placeholder="文件名" style="flex: 1;"></el-input>
                    </div>
                    <el-input v-model="faSync.file_checksum" size="small" placeholder="checksum（SHA256）" style="margin-bottom: 6px;"></el-input>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" :loading="faSyncReporting" @click="triggerFaSync">上报同步进度</el-button>
                </div>
                <!-- 获取文件 -->
                <div>
                    <div style="font-size: 13px; font-weight: 500; margin-bottom: 8px;">获取飞行区文件</div>
                    <el-button type="primary" size="small" :disabled="!mqttConnected" :loading="faGetting" @click="triggerFaGet">发起获取请求</el-button>
                </div>
            </el-card>

            </el-tab-pane>
            </el-tabs>
        `
    });
}
