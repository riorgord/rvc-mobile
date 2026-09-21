package com.rvc.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** 角色信息(从 filesDir/roles/<model_id>/manifest.json 读)。 */
data class RoleInfo(val modelId: String, val name: String, val dir: File)

/**
 * 角色包/共享件管理。
 *
 * 目录约定:
 *   filesDir/roles/<model_id>/  —— 角色包解压根目录(含 manifest.json)
 *   filesDir/models|periphery  —— 共享件(shared.zip 解压进来)
 *
 * 导入流程:SAF 选 zip → 读 manifest → 逐文件 SHA256 校验 → 解压。
 */
object RoleManager {
    private const val TAG = "RoleManager"
    const val PREF_ROLE = "current_role_id"

    /** 共享件最小集合(shared.zip manifest 里应包含这些)。 */
    val REQUIRED_SHARED = arrayOf(
        "models/hubert_mix_def_t4800.bin",
        "models/rmvpe_fp32_64.bin.bin",
        "models/fcpe_256.bin",
        "models/df3r_T18_emb.onnx",
        "periphery/mel_basis.bin",
        "periphery/emb_params.npz",
        "periphery/sine_params.json",
        "periphery/cent_table.bin",
        "periphery/proj.bin"
    )

    fun rolesDir(filesDir: File): File = File(filesDir, "roles").apply { mkdirs() }

    fun scanRoles(filesDir: File): List<RoleInfo> {
        val dir = rolesDir(filesDir)
        return dir.listFiles { f -> f.isDirectory }?.mapNotNull { d ->
            val mf = File(d, "manifest.json")
            if (!mf.isFile) return@mapNotNull null
            try {
                val j = JSONObject(mf.readText())
                RoleInfo(
                    modelId = j.getString("model_id"),
                    name = j.optString("name", j.getString("model_id")),
                    dir = d
                )
            } catch (e: Exception) {
                Log.w(TAG, "bad role manifest: $mf", e)
                null
            }
        }?.sortedBy { it.name } ?: emptyList()
    }

    fun currentRoleId(prefs: SharedPreferences): String? =
        prefs.getString(PREF_ROLE, null)

    fun currentRoleDir(filesDir: File, prefs: SharedPreferences): String? {
        val id = currentRoleId(prefs) ?: return null
        val dir = File(rolesDir(filesDir), id)
        return if (File(dir, "manifest.json").isFile) dir.absolutePath else null
    }

    fun setCurrentRole(prefs: SharedPreferences, id: String?) {
        prefs.edit().putString(PREF_ROLE, id).apply()
    }

    fun isSharedReady(filesDir: File): Boolean =
        REQUIRED_SHARED.all { File(filesDir, it).isFile }

    /** 从 SAF content:// 导入 shared.zip 或 role.zip;返回 model_id(shared 返回 "shared")。 */
    fun importUri(context: Context, uri: Uri, filesDir: File): String {
        val ins = context.contentResolver.openInputStream(uri)
            ?: throw RuntimeException("无法打开所选文件")
        val tmp = File(filesDir, "import_${System.currentTimeMillis()}.zip")
        try {
            ins.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return importZipFile(tmp, filesDir)
        } finally {
            tmp.delete()
        }
    }

    /** 导入本地 zip 文件(下载/复制后的路径)。 */
    fun importZipFile(zip: File, filesDir: File): String {
        val manifest = readManifest(zip)
        val type = manifest.optString("type", "role")
        return if (type == "shared") {
            extractShared(zip, filesDir, manifest)
            "shared"
        } else {
            val modelId = manifest.getString("model_id")
            val dest = File(rolesDir(filesDir), modelId)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            extractVerified(zip, dest, manifest)
            modelId
        }
    }

    private fun readManifest(zip: File): JSONObject {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "manifest.json" || e.name.endsWith("/manifest.json")) {
                    return JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                }
                e = zis.nextEntry
            }
        }
        throw RuntimeException("zip 里没有 manifest.json")
    }

    private fun extractShared(zip: File, filesDir: File, manifest: JSONObject) {
        val expected = readExpected(manifest)
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    if (name != "manifest.json" && expected.containsKey(name)) {
                        val out = File(filesDir, name)
                        out.parentFile?.mkdirs()
                        zis.copyTo(out.outputStream())
                    }
                }
                e = zis.nextEntry
            }
        }
        verifyFiles(filesDir, expected)
    }

    private fun extractVerified(zip: File, dest: File, manifest: JSONObject) {
        val expected = readExpected(manifest)
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    if (name != "manifest.json" && expected.containsKey(name)) {
                        val out = File(dest, name)
                        out.parentFile?.mkdirs()
                        zis.copyTo(out.outputStream())
                    }
                }
                e = zis.nextEntry
            }
        }
        verifyFiles(dest, expected)
    }

    private fun readExpected(manifest: JSONObject): Map<String, String> {
        val arr = manifest.getJSONArray("files")
        val map = HashMap<String, String>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            map[f.getString("name")] = f.getString("sha256")
        }
        return map
    }

    private fun verifyFiles(root: File, expected: Map<String, String>) {
        val missing = expected.keys.filter { !File(root, it).isFile }
        if (missing.isNotEmpty()) throw RuntimeException("解压后缺文件: ${missing.joinToString()}")
        val bad = expected.filter { (name, sha) -> sha256(File(root, name)) != sha }.keys
        if (bad.isNotEmpty()) throw RuntimeException("SHA256 校验失败: ${bad.joinToString()}")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
