package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class WebDAVSyncService(context: Context, json: Json, syncPreferences: SyncPreferences) : SyncService(
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

    private fun getSardineClient(): Sardine {
        val url = syncPreferences.webDavServerUrl().get()
        val username = syncPreferences.webDavUsername().get()
        val password = syncPreferences.webDavPassword().get()

        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw Exception(context.stringResource(SYMR.strings.webdav_not_configured))
        }

        val sardine = OkHttpSardine()
        sardine.setCredentials(username, password)
        return sardine
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

    private fun pullSyncData(): SyncData? {
        val sardine = getSardineClient()
        val baseUrl = syncPreferences.webDavServerUrl().get().trimEnd('/')
        val syncFileUrl = "$baseUrl/$remoteFileName"
        val deviceIdFileUrl = "$baseUrl/$deviceIdFileName"

        return try {
            if (sardine.exists(syncFileUrl)) {
                logcat(LogPriority.DEBUG, "WebDAVSyncService") { "Found remote sync file: $syncFileUrl" }

                // Read device ID from separate file
                var deviceId = ""
                if (sardine.exists(deviceIdFileUrl)) {
                    deviceId = sardine.get(deviceIdFileUrl).bufferedReader().use { it.readText() }
                    logcat(LogPriority.DEBUG, "WebDAVSyncService") { "Retrieved device ID: $deviceId" }
                }

                sardine.get(syncFileUrl).use { inputStream ->
                    GZIPInputStream(inputStream).use { gzipInputStream ->
                        val byteArray = gzipInputStream.readBytes()
                        val backup = protoBuf.decodeFromByteArray(Backup.serializer(), byteArray)
                        SyncData(deviceId = deviceId, backup = backup)
                    }
                }
            } else {
                logcat(LogPriority.INFO, "WebDAVSyncService") { "Remote sync file not found: $syncFileUrl" }
                null
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR,) { "Error downloading file from WebDAV" }
            throw Exception("Failed to download sync data: ${e.message}", e)
        }
    }

    private fun pushSyncData(syncData: SyncData) {
        val backup = syncData.backup ?: return
        val sardine = getSardineClient()
        val baseUrl = syncPreferences.webDavServerUrl().get().trimEnd('/')
        val syncFileUrl = "$baseUrl/$remoteFileName"
        val deviceIdFileUrl = "$baseUrl/$deviceIdFileName"

        try {
            // Upload device ID
            val deviceId = syncPreferences.uniqueDeviceID()
            sardine.put(deviceIdFileUrl, deviceId.toByteArray(), "text/plain")
            logcat(LogPriority.DEBUG, "WebDAVSyncService") { "Uploaded device ID: $deviceId" }

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

                sardine.put(syncFileUrl, compressedData, "application/octet-stream")
                    logcat(LogPriority.DEBUG, "WebDAVSyncService") { "Successfully uploaded sync data to $syncFileUrl" }
                }

        } catch (e: Exception) {
            logcat(LogPriority.ERROR,) { "Error uploading file to WebDAV" }
            throw Exception("Failed to upload sync data: ${e.message}", e)
        }
    }
}
