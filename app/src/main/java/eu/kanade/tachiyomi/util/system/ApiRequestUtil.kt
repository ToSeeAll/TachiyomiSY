package eu.kanade.tachiyomi.util.system

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API请求工具类：封装OkHttp网络请求
 */
object ApiRequestUtil {

    // 1. 初始化OkHttp客户端（单例，全局复用）
    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // 调试时打印请求日志
        }
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS) // 连接超时
            .readTimeout(15, TimeUnit.SECONDS)    // 读取超时
            .writeTimeout(15, TimeUnit.SECONDS)   // 写入超时
            .build()
    }

    /**
     * 发送DELETE请求（协程异步执行）
     * @param apiUrl 目标API地址（经UrlParserUtil解析后）
     * @param authToken 可选：鉴权Token（如Bearer Token）
     * @return 接口响应体字符串
     * @throws IOException 网络异常或接口返回非200-299状态码时抛出
     */
    suspend fun sendDeleteRequest(
        apiUrl: String,
        authToken: String? = null
    ): String = withContext(Dispatchers.IO) { // 切换到IO线程执行网络请求
        // 2. 构建DELETE请求
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .delete() // 设置请求方法为DELETE
            .addHeader("Content-Type", "application/json") // 按接口要求设置请求头

        // 3. 添加鉴权Token（若有）
        authToken?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }

        // 4. 发送请求并处理响应
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                responseBody // 成功：返回响应体
            } else {
                // 失败：抛出异常（携带状态码和响应信息）
                throw IOException("DELETE请求失败：HTTP ${response.code}，响应：$responseBody")
            }
        }
    }
}