package eu.kanade.tachiyomi.util.system

import java.net.MalformedURLException
import java.net.URL

/**
 * URL解析工具类：提取DELETE请求目标地址
 */
object UrlParserUtil {

    /**
     * 解析原始URL，移除末尾的"/files"（兼容多格式）
     * @param originalUrl 原始URL（如https://abc.com.cn/api/archives/xxx/files）
     * @return 目标API地址（如https://abc.com.cn/api/archives/xxx）
     * @throws MalformedURLException URL格式非法时抛出
     * @throws IllegalArgumentException URL不含"files"后缀时抛出
     */
    @Throws(MalformedURLException::class, IllegalArgumentException::class)
    fun parseDeleteApiUrl(originalUrl: String): String {
        // 1. 验证URL合法性
        val url = URL(originalUrl)
        // 2. 拼接完整路径（协议+域名+路径）
        val fullPath = "${url.protocol}://${url.authority}${url.path}"
        // 3. 移除末尾"files"相关后缀
        return when {
            fullPath.endsWith("/files/") -> fullPath.removeSuffix("/files/")
            fullPath.endsWith("/files") -> fullPath.removeSuffix("/files")
            fullPath.endsWith("files/") -> fullPath.removeSuffix("files/")
            fullPath.endsWith("files") -> fullPath.removeSuffix("files")
            fullPath.endsWith("/thumbnail/") -> fullPath.removeSuffix("/thumbnail/")
            fullPath.endsWith("/thumbnail") -> fullPath.removeSuffix("/thumbnail")
            fullPath.endsWith("thumbnail/") -> fullPath.removeSuffix("thumbnail/")
            fullPath.endsWith("thumbnail") -> fullPath.removeSuffix("thumbnail")
            fullPath.startsWith("/reader?id=") -> "https://lanrar.youcan.eu.org/api/archives/" + fullPath.removePrefix(
                "/reader?id="
            )

            else -> throw IllegalArgumentException("URL格式错误：末尾不含'files'，URL=$originalUrl")
        }
    }
}