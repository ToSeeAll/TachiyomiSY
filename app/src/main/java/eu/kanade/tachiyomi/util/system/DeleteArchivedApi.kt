package eu.kanade.tachiyomi.util.system

import android.util.Log
import java.io.IOException
import java.net.MalformedURLException

/**
 * 业务函数：调用URL解析和API请求工具类，执行DELETE操作
 * @param param 入参对象（含原始URL和Token）
 * @param onSuccess 成功回调（返回响应体）
 * @param onFailure 失败回调（返回错误信息）
 */
suspend fun executeDeleteApi(
    param: DeleteApiParam,
    onSuccess: (String) -> Unit,
    onFailure: (String) -> Unit
) {
    try {
        // 步骤1：调用UrlParserUtil解析URL，获取目标API地址
        val targetApiUrl = UrlParserUtil.parseDeleteApiUrl(param.sourceUrl)
        Log.d("DeleteApi", "解析后的API地址：$targetApiUrl")

        // 步骤2：调用ApiRequestUtil发送DELETE请求
        val response = ApiRequestUtil.sendDeleteRequest(
            apiUrl = targetApiUrl,
            authToken = param.authToken
        )

        // 步骤3：请求成功，回调结果
        onSuccess(response)

    } catch (e: MalformedURLException) {
        // URL格式错误
        onFailure("URL格式非法：${e.message}")
    } catch (e: IllegalArgumentException) {
        // URL不含"files"后缀
        onFailure("URL解析失败：${e.message}")
    } catch (e: IOException) {
        // 网络异常或接口错误
        onFailure("网络请求失败：${e.message}")
    } catch (e: Exception) {
        // 其他未知异常
        onFailure("操作失败：${e.message ?: "未知错误"}")
    }
}