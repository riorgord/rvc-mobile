package io.mo.glassmic.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.mo.glassmic.xposed.XposedHookGate

/**
 * 运行态决策 Provider(P0 最小版)。
 *
 * 注入侧查询协议(与 GlassMic 一致):
 *   content://com.rvc.app.provider.runtime/resolve
 *   selectionArgs = [callerPackage, apiVersion]
 *   返回 Cursor:第一列 = SourceType.name()
 */
class RuntimeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val pkg = selectionArgs?.getOrNull(0)
        val source = if (XposedHookGate.shouldSkipPackage(pkg)) {
            io.mo.glassmic.core.model.SourceType.REAL_MIC
        } else {
            RackState.resolve(pkg)
        }
        // P0 调试:看哪些进程来查、拿到什么决策
        android.util.Log.i(
            "GlassMic-Runtime",
            "resolve pkg=$pkg -> $source (enabled=${RackState.enabled} src=${RackState.source})"
        )
        val c = MatrixCursor(arrayOf("source"))
        c.addRow(arrayOf(source.name))
        return c
    }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    // XBridge 会上报拦截统计/心跳,这里 P0 全部 no-op,保证调用不崩。
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? = null
}
