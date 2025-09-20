package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class WebDAVSyncService(context: Context, json: Json, syncPreferences: SyncPreferences) :
    SyncService(
        context,
        json,
        syncPreferences,
    ) {
    constructor(context: Context) : this(
        context,
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        },
        Injekt.get<SyncPreferences>(),
    )

    private val appName = context.stringResource(MR.strings.app_name)
    private val protoBuf: ProtoBuf = Injekt.get()
    private val remoteFileName = "${appName}_sync.proto.gz"
    private val deviceIdFileName = "${appName}_device_id.txt"

    private fun getOkHttpClient(): OkHttpClient {
        val username = syncPreferences.webDavUsername().get()
        val password = syncPreferences.webDavPassword().get()

        // 设置超时时间
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // 添加基本身份验证
        if (username.isNotEmpty() && password.isNotEmpty()) {
            val credential = "$username:$password"
            val encodedCredential = Base64.getEncoder().encodeToString(credential.toByteArray())
            builder.addInterceptor {
                val request = it.request().newBuilder()
                    .header("Authorization", "Basic $encodedCredential")
                    .build()
                it.proceed(request)
            }
        }

        return builder.build()
    }

    private suspend fun doesFileExist(url: String): Boolean {
        val client = getOkHttpClient()
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Depth", "0")
            .build()

        return try {
            val response = client.newCall(request).await()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        try {
            val remoteSData = pullSyncData()

            val finalSyncData = if (remoteSData != null) {
                // Get local unique device ID
                val localDeviceId = syncPreferences.uniqueDeviceID()
                val lastSyncDeviceId = remoteSData.deviceId

                logcat(LogPriority.DEBUG, "WebDAVSyncService") {
                    "Local device ID: $localDeviceId, Last sync device ID: $lastSyncDeviceId"
                }

                // Check if the last sync was done by the same device
                if (lastSyncDeviceId == localDeviceId) {
                    // Overwrite remote data with local data
                    syncData
                } else {
                    // Merge local and remote data
                    mergeSyncData(syncData, remoteSData)
                }
            } else {
                // No remote data, use local data
                syncData
            }

            pushSyncData(finalSyncData)
            return finalSyncData.backup
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, "WebDAVSyncService") { "Error syncing: ${e.message}" }
            return null
        }
    }

    private suspend fun pullSyncData(): SyncData? {
        val baseUrl = syncPreferences.webDavServerUrl().get().trimEnd('/')
        val syncFileUrl = "$baseUrl/$remoteFileName"
        val deviceIdFileUrl = "$baseUrl/$deviceIdFileName"

        if (baseUrl.isEmpty() || syncPreferences.webDavUsername().get()
                .isEmpty() || syncPreferences.webDavPassword().get().isEmpty()
        ) {
            throw Exception(context.stringResource(SYMR.strings.webdav_not_configured))
        }

        return try {
            if (doesFileExist(syncFileUrl)) {
                logcat(
                    LogPriority.DEBUG,
                    "WebDAVSyncService"
                ) { "Found remote sync file: $syncFileUrl" }

                // Read device ID from separate file
                var deviceId = ""
                if (doesFileExist(deviceIdFileUrl)) {
                    val deviceIdResponse = getOkHttpClient().newCall(GET(deviceIdFileUrl)).await()
                    if (deviceIdResponse.isSuccessful) {
                        deviceId = deviceIdResponse.body?.string() ?: ""
                        logcat(
                            LogPriority.DEBUG,
                            "WebDAVSyncService"
                        ) { "Retrieved device ID: $deviceId" }
                    }
                }

                val syncResponse = getOkHttpClient().newCall(GET(syncFileUrl)).await()
                if (syncResponse.isSuccessful) {
                    syncResponse.body?.byteStream()?.use { inputStream ->
                        GZIPInputStream(inputStream).use { gzipInputStream ->
                            val byteArray = gzipInputStream.readBytes()
                            val backup =
                                protoBuf.decodeFromByteArray(Backup.serializer(), byteArray)
                            return SyncData(deviceId = deviceId, backup = backup)
                        }
                    }
                }
                throw Exception("Failed to download sync file")
            } else {
                logcat(
                    LogPriority.INFO,
                    "WebDAVSyncService"
                ) { "Remote sync file not found: $syncFileUrl" }
                null
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error downloading file from WebDAV: ${e.message}" }
            throw Exception("Failed to download sync data: ${e.message}", e)
        }
    }

    private suspend fun pushSyncData(syncData: SyncData) {
        val backup = syncData.backup ?: return
        val baseUrl = syncPreferences.webDavServerUrl().get().trimEnd('/')
        val syncFileUrl = "$baseUrl/$remoteFileName"
        val deviceIdFileUrl = "$baseUrl/$deviceIdFileName"

        if (baseUrl.isEmpty() || syncPreferences.webDavUsername().get()
                .isEmpty() || syncPreferences.webDavPassword().get().isEmpty()
        ) {
            throw Exception(context.stringResource(SYMR.strings.webdav_not_configured))
        }

        try {
            val client = getOkHttpClient()

            // Upload device ID
            val deviceId = syncPreferences.uniqueDeviceID()
            val deviceIdBody = deviceId.toRequestBody("text/plain".toMediaType())
            val deviceIdRequest = PUT(deviceIdFileUrl, body = deviceIdBody)
            val deviceIdResponse = client.newCall(deviceIdRequest).await()
            if (deviceIdResponse.isSuccessful) {
                logcat(LogPriority.DEBUG, "WebDAVSyncService") { "Uploaded device ID: $deviceId" }
            } else {
                throw Exception("Failed to upload device ID: ${deviceIdResponse.code}")
            }

            // Upload sync data
            val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { gzipOutputStream ->
                    gzipOutputStream.write(byteArray)
                }
                val compressedData = baos.toByteArray()
                val syncBody =
                    compressedData.toRequestBody("application/octet-stream".toMediaType())
                val syncRequest = PUT(syncFileUrl, body = syncBody)
                val syncResponse = client.newCall(syncRequest).await()
                if (syncResponse.isSuccessful) {
                    logcat(
                        LogPriority.DEBUG,
                        "WebDAVSyncService"
                    ) { "Successfully uploaded sync data to $syncFileUrl" }
                } else {
                    throw Exception("Failed to upload sync data: ${syncResponse.code}")
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error uploading file to WebDAV: ${e.message}" }
            throw Exception("Failed to upload sync data: ${e.message}", e)
        }
    }
}
