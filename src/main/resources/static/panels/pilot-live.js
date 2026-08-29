/**
 * Pilot 直播面板组件 — 直播模拟
 *
 * 推流能力状态（RTMP/WHIP）+ 限制清单 + 活跃推流列表
 *
 * 共享状态/方法通过 inject('ctx') 获取。
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotLivePanel(app) 注册。
 */
function registerPilotLivePanel(app) {
    const { inject } = Vue;

    app.component('pilot-live', {
        setup() {
            const ctx = inject('ctx');
            return { ...ctx };
        },
        template: `
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
        `
    });
}
