package com.ubuntucontroller

import android.app.Application

/**
 * 应用级 Application，保证任何入口（Launcher Activity / BootReceiver / Service）
 * 启动前都先初始化 RuntimeLog，使运行时日志在全部场景都能落盘到外部私有目录。
 */
class UbuntuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeLog.init(applicationContext)
    }
}
