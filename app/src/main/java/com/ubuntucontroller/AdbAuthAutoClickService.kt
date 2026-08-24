package com.ubuntucontroller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ADB 授权弹窗自动点击服务（无障碍）。
 *
 * 解决「经典网络 ADB（TCP 5555，RSA 握手）」通道在 TV Launcher 上收不到/点不到
 * 「允许 USB 调试」系统弹窗的问题——这正是应用管家（AccService）的做法：
 * 监听窗口变化，命中 ADB 授权类弹窗后自动点「允许」。
 *
 * 命中规则（保守，避免误点其它弹窗）：
 *  - 仅作用于系统 UI / 设置包（com.android.systemui / com.android.settings）；
 *  - 且窗口文本含 ADB 授权关键字（USB 调试 / 无线调试 / OTG / USB 设备）；
 *  - 点击文本为「允许 / 确定 / 始终允许 / 允许并进行无线调试 / 允许该设备」的可点击节点。
 *
 * 启用方式：见 [AdbAccessibilityHelper]（设置页「ADB 授权自动点击」开关）。
 */
class AdbAuthAutoClickService : AccessibilityService() {

    companion object {
        const val SERVICE_FLAT = "com.ubuntucontroller/com.ubuntucontroller.AdbAuthAutoClickService"

        /** 可点击的「确认」类按钮文本（命中其一即点击）。 */
        private val CONFIRM_TEXTS = setOf(
            "允许", "确定", "始终允许", "允许并进行无线调试",
            "允许该设备", "允许USB调试", "允许 USB 调试"
        )

        /** 窗口文本命中任一关键字即判定为 ADB 授权类弹窗（避免误点文件传输等无关弹窗）。 */
        private val WINDOW_KEYWORDS = listOf(
            "USB 调试", "USB调试", "无线调试", "OTG", "USB 设备", "USB设备"
        )

        /**
         * 自动点击冷却时间（ms）。
         *
         * 必须存在：performAction(ACTION_CLICK) 会让按钮 pressed/焦点变化并触发
         * TYPE_WINDOW_CONTENT_CHANGED，若不节流会形成「点击→事件→再点击」的自触发死循环，
         * 刷爆 UI 线程并抢走遥控器焦点（真机实测：循环点授权框、人工无法操作、也点不到最终确认）。
         * 冷却期内一律忽略窗口事件；授权流程出现二次确认框（如「始终允许」）时，新的
         * WINDOW_STATE_CHANGED 会在冷却结束后正常触发下一次点击。
         */
        private const val CLICK_COOLDOWN_MS = 4000L

        @Volatile
        private var lastClickTime = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // 冷却期：上一次自动点击后短时间内不再动作——切断「点击→内容变化→再点击」自触发死循环，
        // 同时留出人工介入窗口（遥控器仍可操作授权框）
        if (SystemClock.elapsedRealtime() - lastClickTime < CLICK_COOLDOWN_MS) return
        val root = rootInActiveWindow ?: return
        try {
            handle(root)
        } finally {
            root.recycle()
        }
    }

    private fun handle(root: AccessibilityNodeInfo) {
        // 1) 限定系统包，缩小作用面
        val pkg = root.packageName?.toString().orEmpty()
        val inSystem = pkg == "com.android.systemui" || pkg == "com.android.settings"
        if (!inSystem) return

        // 2) 窗口文本必须含 ADB 授权关键字
        val allText = StringBuilder()
        collectText(root, allText)
        val keywordHit = WINDOW_KEYWORDS.any { allText.contains(it) }
        if (!keywordHit) return

        // 3) 找「确认」按钮并点击（精确匹配确认类文本，绝不点取消/拒绝）
        val target = findConfirmButton(root) ?: return
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            // 点击成功才进冷却；失败则等下一次窗口事件自然重试（不主动刷事件，避免风暴）
            lastClickTime = SystemClock.elapsedRealtime()
            RuntimeLog.append("adb", "已自动点击 ADB 授权确认按钮")
        }
        target.recycle()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.toString()?.let { sb.append(it) }
        node.contentDescription?.toString()?.let { sb.append(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, sb) }
        }
    }

    private fun findConfirmButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = node.text?.toString().orEmpty()
        if (node.isClickable && CONFIRM_TEXTS.any { t == it || t.contains(it) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findConfirmButton(c)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}
}
