package com.rvc.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线目录(catalog.json)拉取与解析。
 *
 * 索引文件放在 rvc-mobile-share 仓库根目录(魔塔/抱脸各一份),App 先拉这个小 JSON,
 * 再按本机 SoC 过滤出能跑的共享件/角色,最后走 SharedDownloader 下载。
 */
object Catalog {
    private const val TAG = "Catalog"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 30000

    data class Device(val soc: String, val socId: Int, val v: String)

    data class SharedEntry(
        val id: String,
        val name: String,
        val soc: String,
        val version: Int,
        val file: String,
        val sha256: String,
        val size: Long,
        val mirrors: Map<String, String>
    )

    data class RoleFile(
        val file: String,
        val sha256: String,
        val size: Long,
        val mirrors: Map<String, String>
    )

    data class RoleEntry(
        val id: String,
        val name: String,
        val license: String?,
        val permission: String,
        val sourceAuthor: String?,
        val files: Map<String, RoleFile>
    )

    data class CatalogData(
        val schema: Int,
        val devices: Map<String, Device>,
        val shared: List<SharedEntry>,
        val roles: List<RoleEntry>
    )

    private val CATALOG_URLS = listOf(
        Triple("huggingface",
            "https://huggingface.co/riorgord/rvc-mobile-share/resolve/main/catalog.json", true),
        Triple("modelscope",
            "https://www.modelscope.cn/models/rirogord/rvc-mobile-share/resolve/master/catalog.json", true)
    )

    /** 拉 catalog.json,成功返回解析结果,失败返回 null。 */
    fun fetch(): CatalogData? {
        val src = SharedDownloader.probeAndPick(CATALOG_URLS.filter { it.third }
            .map { SharedDownloader.Source(it.first, it.second) }) ?: return null
        return try {
            val conn = URL(src.url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().readText()
                parse(text)
            } else {
                Log.w(TAG, "fetch ${src.name} code=${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $e")
            null
        }
    }

    fun parse(text: String): CatalogData? {
        return try {
            val root = JSONObject(text)

            val devices = mutableMapOf<String, Device>()
            val dObj = root.optJSONObject("devices") ?: JSONObject()
            val dKeys = dObj.keys()
            while (dKeys.hasNext()) {
                val soc = dKeys.next()
                val v = dObj.getJSONObject(soc)
                devices[soc] = Device(soc, v.optInt("soc_id"), v.optString("v"))
            }

            val shared = mutableListOf<SharedEntry>()
            val sArr = root.optJSONArray("shared") ?: JSONArray()
            for (i in 0 until sArr.length()) {
                val o = sArr.getJSONObject(i)
                val mirrors = mutableMapOf<String, String>()
                val m = o.optJSONObject("mirrors") ?: JSONObject()
                val mKeys = m.keys()
                while (mKeys.hasNext()) {
                    val k = mKeys.next()
                    mirrors[k] = m.getString(k)
                }
                shared.add(SharedEntry(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    soc = o.optString("soc"),
                    version = o.optInt("version", 1),
                    file = o.optString("file"),
                    sha256 = o.optString("sha256"),
                    size = o.optLong("size"),
                    mirrors = mirrors
                ))
            }

            val roles = mutableListOf<RoleEntry>()
            val rArr = root.optJSONArray("roles") ?: JSONArray()
            for (i in 0 until rArr.length()) {
                val o = rArr.getJSONObject(i)
                val files = mutableMapOf<String, RoleFile>()
                val fo = o.optJSONObject("files") ?: JSONObject()
                val fKeys = fo.keys()
                while (fKeys.hasNext()) {
                    val key = fKeys.next()
                    val f = fo.getJSONObject(key)
                    val mirrors = mutableMapOf<String, String>()
                    val m = f.optJSONObject("mirrors") ?: JSONObject()
                    val mKeys = m.keys()
                    while (mKeys.hasNext()) {
                        val mk = mKeys.next()
                        mirrors[mk] = m.getString(mk)
                    }
                    files[key] = RoleFile(
                        file = f.optString("file"),
                        sha256 = f.optString("sha256"),
                        size = f.optLong("size"),
                        mirrors = mirrors
                    )
                }
                roles.add(RoleEntry(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    license = o.optString("license").ifEmpty { null },
                    permission = o.optString("permission", "local-only"),
                    sourceAuthor = o.optString("source_author").ifEmpty { null },
                    files = files
                ))
            }

            CatalogData(root.optInt("schema"), devices, shared, roles)
        } catch (e: Exception) {
            Log.w(TAG, "parse failed: $e")
            null
        }
    }

    /** 本机 SoC 对应的共享件(catalog 里没这设备就返回 null)。 */
    fun compatibleShared(catalog: CatalogData, soc: String): SharedEntry? =
        catalog.shared.firstOrNull { it.soc.equals(soc, ignoreCase = true) }

    /** 本机 SoC 能跑的授权角色列表(按 V/soc_id 匹配)。 */
    fun compatibleRoles(catalog: CatalogData, soc: String): List<Pair<RoleEntry, RoleFile>> {
        val dev = catalog.devices[soc.lowercase()] ?: return emptyList()
        val groupKey = "${dev.v}/${dev.socId}"
        return catalog.roles.filter { it.permission == "authorized" }.mapNotNull { role ->
            role.files[groupKey]?.let { role to it }
        }
    }
}
