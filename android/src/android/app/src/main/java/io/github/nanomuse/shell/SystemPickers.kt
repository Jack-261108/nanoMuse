package io.github.nanomuse.shell

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/**
 * 目标 App 调起的系统界面所在的包 (权限申请框、文档/图片选择器、系统设置与无桌面入口的认证服务)。
 * 这些界面必须停留在用户物理主屏上供真实人手点击授权，绝不能被当作外溢应用拉回到虚拟屏中。
 */
object SystemPickers {
    const val SYSTEM_PACKAGE = "android"
    private const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"

    private val STATIC_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.settings",
        SYSTEM_PACKAGE,
    )

    fun resolve(context: Context): Set<String> {
        val pm = context.packageManager
        val dynamic = intents().mapNotNull {
            runCatching { pm.resolveActivity(it, 0)?.activityInfo?.packageName }.getOrNull()
        }.toSet()

        val authenticators = runCatching {
            AccountManager.get(context).authenticatorTypes.mapNotNull { it.packageName }.filter { pkg ->
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
                pm.queryIntentActivities(launcher, 0).isEmpty()
            }
        }.getOrDefault(emptyList()).toSet()

        return STATIC_PACKAGES + dynamic + authenticators
    }

    private fun intents(): List<Intent> = buildList {
        add(Intent(ACTION_REQUEST_PERMISSIONS))
        add(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain"), null))
        add(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        add(Intent(Settings.ACTION_SETTINGS))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Intent(MediaStore.ACTION_PICK_IMAGES))
        }
    }
}
