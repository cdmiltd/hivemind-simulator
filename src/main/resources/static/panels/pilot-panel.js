/**
 * Pilot 面板组件（飞行器上云模式专属）— 纯容器组件
 *
 * 编排 5 个子组件：
 *   pilot-status（状态+位置模拟）/ pilot-live（直播模拟）/ pilot-cloud（设备上云）
 *   pilot-config（JSBridge token 鉴权）/ pilot-jsbridge-tabs（7 模块 tab）
 *
 * 共享状态/方法通过 inject('ctx') 获取。
 * DJI Pilot 2 JSBridge 官方 9 模块：
 *   设备上云 / 直播 / API / WS / 地图元素 / TSA态势感知 / Media媒体 / 航线 / MOP
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotPanel(app) 注册。
 */
function registerPilotPanel(app) {
    const { inject } = Vue;

    app.component('pilot-panel', {
        setup() {
            const ctx = inject('ctx');
            return { ...ctx };
        },
        template: `
            <pilot-status></pilot-status>
            <pilot-live></pilot-live>
            <pilot-cloud></pilot-cloud>
            <pilot-config></pilot-config>
            <pilot-jsbridge-tabs></pilot-jsbridge-tabs>
        `
    });
}
