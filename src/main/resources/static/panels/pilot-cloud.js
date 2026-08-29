/**
 * Pilot 设备上云面板组件 — 设备上云模块说明
 *
 * 注册功能位于顶部工具栏的「注册到第三方平台」按钮。
 * Pilot 模式跳过 DJI 注册流程，MQTT 连接成功后直接上线。
 *
 * 共享状态/方法通过 inject('ctx') 获取。
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotCloudPanel(app) 注册。
 */
function registerPilotCloudPanel(app) {
    const { inject } = Vue;

    app.component('pilot-cloud', {
        setup() {
            const ctx = inject('ctx');
            return { ...ctx };
        },
        template: `
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
        `
    });
}
