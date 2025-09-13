package eu.kanade.tachiyomi.util.system

/**
 * 接口请求入参：包含待解析的URL和鉴权Token
 */
data class DeleteApiParam(
    val sourceUrl: String, // 原始URL（如https://abc.com.cn/api/archives/xxx/files）
    val authToken: String? = null // 可选：鉴权Token
)