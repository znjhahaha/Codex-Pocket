package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Describe every requested permission before enabling approval. Unknown shapes stay on the desktop. */
internal fun assistantPermissionLines(value: JsonElement): List<String>? = runCatching {
    fun objectFields(value: JsonElement, keys: Set<String>): JsonObject = (value as JsonObject).also {require(it.keys.all(keys::contains))}
    fun text(value: JsonElement?): String = (value as JsonPrimitive).also {require(it.isString && it.content.isNotBlank())}.content
    fun present(value: JsonElement?) = value != null && value != JsonNull
    val profile=objectFields(value,setOf("network","fileSystem"))
    val lines=mutableListOf<String>()
    profile["network"]?.takeIf(::present)?.let {raw ->
        val network=objectFields(raw,setOf("enabled"))
        network["enabled"]?.takeIf(::present)?.let {enabled ->
            val granted=(enabled as JsonPrimitive).also {require(!it.isString)}.boolean
            lines += if(granted) "允许网络访问" else "禁用网络访问"
        }
    }
    profile["fileSystem"]?.takeIf(::present)?.let {raw ->
        val fs=objectFields(raw,setOf("read","write","entries","globScanMaxDepth"))
        for((key,label) in listOf("read" to "读取","write" to "写入")) {
            fs[key]?.takeIf(::present)?.let {paths -> (paths as JsonArray).forEach {lines += "$label · ${text(it)}"}}
        }
        fs["entries"]?.takeIf(::present)?.let {entries -> (entries as JsonArray).forEach {rawEntry ->
            val entry=objectFields(rawEntry,setOf("access","path"))
            val access=when(text(entry["access"])) {"read"->"读取";"write"->"写入";"deny"->"禁止访问";else->error("Unknown access")}
            val path=entry["path"] as JsonObject
            val display=when(text(path["type"])) {
                "path" -> {objectFields(path,setOf("type","path"));text(path["path"])}
                "glob_pattern" -> {objectFields(path,setOf("type","pattern"));"匹配路径 ${text(path["pattern"])}"}
                "special" -> {
                    objectFields(path,setOf("type","value"))
                    val special=path["value"] as JsonObject
                    val kind=text(special["kind"])
                    objectFields(special,if(kind=="project_roots") setOf("kind","subpath") else setOf("kind"))
                    val base=when(kind) {"root"->"整个文件系统";"minimal"->"最小系统路径";"project_roots"->"项目根目录";"tmpdir"->"系统临时目录";"slash_tmp"->"/tmp";else->error("Unknown path")}
                    base+(special["subpath"]?.takeIf(::present)?.let {" / ${text(it)}"} ?: "")
                }
                else -> error("Unknown path")
            }
            lines += "$access · $display"
        }}
        fs["globScanMaxDepth"]?.takeIf(::present)?.let {depth ->
            val count=(depth as JsonPrimitive).also {require(!it.isString)}.int.also {require(it>0)}
            lines += "路径匹配最多扫描 $count 层"
        }
    }
    lines.ifEmpty {listOf("未请求额外权限")}
}.getOrNull()
