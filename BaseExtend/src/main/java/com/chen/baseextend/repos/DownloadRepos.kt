package com.chen.baseextend.repos

import com.chen.baseextend.BaseExtendApplication
import com.chen.baseextend.BuildConfig
import com.chen.baseextend.base.BaseSimpleRepos
import com.chen.basemodule.constant.LiveBusKey
import com.chen.basemodule.event_bus.BaseProgressEvent
import com.chen.basemodule.event_bus.ProgressBean
import com.chen.basemodule.network.base.BaseNetException
import com.chen.basemodule.network.base.BaseResponse
import com.chen.basemodule.network.constant.NetConfig
import com.chen.basemodule.util.FileUtil
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit


object DownloadRepos : BaseSimpleRepos<DownloadRepos.DownloadService>() {

    override fun createRetrofit(): Retrofit {
        return Retrofit.Builder()
                .baseUrl(BuildConfig.HOST)
                .client(createHttpClient())
                .build()
    }

    override fun createHttpClient(): OkHttpClient {

        return OkHttpClient.Builder()
                .retryOnConnectionFailure(NetConfig.RETRY_TO_CONNECT)
                .connectTimeout(NetConfig.CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(NetConfig.CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(NetConfig.CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .addInterceptor(createAuthInterceptor())
                .build()
    }

    suspend fun downloadFile(fileUrl: String?, fileLength: Long = -1L, saveName: String? = null): BaseResponse<String> {
        if (fileUrl.isNullOrEmpty()) return BaseResponse(null, 300, "文件不存在")

        val fileName = if (saveName.isNullOrEmpty()) fileUrl.substringAfterLast("/") else saveName
        val type = fileName.substringBeforeLast(".")

        val downloadDir = File(BaseExtendApplication.app!!.getExternalFilesDir(""), "download")

        if (!downloadDir.exists()) {
            if (!downloadDir.mkdirs()) {
                throw BaseNetException(409, "无法创建文件下载目录，请检查是否关闭了相关权限")
            }
        }
        val file = File("${downloadDir.absolutePath}/$fileName")

        val randomAccessFile = RandomAccessFile(file, "rwd")

        val lock = randomAccessFile.channel.tryLock() ?: return BaseResponse(null, 301, "文件正在下载")

        var length = if (file.exists()) file.length() else 0L

        var totalByte = if (fileLength > 0) fileLength else 0L

        val fileInfo = File("${downloadDir.absolutePath}/$fileName.tmp")

        if (length > 0 && length == totalByte) {
            lock.release()
            return BaseResponse(file.path, 200, "下载完成")
        } else if (length > 0) {
            if (!fileInfo.exists()) {
                lock.release()
                return BaseResponse(file.path, 200, "下载完成")
            } else {
                fileInfo.bufferedReader().use {
                    totalByte = it.readText().toLong()
                    if (length >= totalByte) {
                        fileInfo.delete()
                        if (totalByte > 0) {
                            lock.release()
                            return BaseResponse(file.path, 200, "下载完成")
                        } else {
                            file.delete()
                        }
                    }
                }
            }
        }

        val responseBody = service.downloadFile(fileUrl, "bytes=${length}-")

        if (length <= 0) {
            totalByte = responseBody.contentLength()

            fileInfo.bufferedWriter().use {
                it.write(totalByte.toString())
            }
        }

        var downloadByte: Long = length

        val buffer = ByteArray(1024 * 64)

        randomAccessFile.seek(length)

        coroutineScope {
            launch {
                try {
                    val progressOb = LiveEventBus.get(LiveBusKey.EVENT_PROGRESS, BaseProgressEvent::class.java)!!

                    while (isActive) {
                        val len: Int = responseBody.byteStream().read(buffer)
                        if (len == -1) {
                            break
                        }
                        randomAccessFile.write(buffer, 0, len)
                        downloadByte += len.toLong()
                        progressOb.post(BaseProgressEvent(this@DownloadRepos::class, fileName, obj = ProgressBean(fileName, String.format("%.1f", downloadByte.times(100f).div(totalByte)), totalByte, downloadByte)))
                        yield()
                    }
                    if (totalByte <= downloadByte) fileInfo.delete()
                } catch (e: IOException) {
                    throw BaseNetException(410, "文件下载错误")
                } finally {
                    lock.release()
                    randomAccessFile.close()
                }
            }
        }

        return BaseResponse(file.path, 200, "下载完成")
    }

    fun getFileSize(fileUrl: String): String? {
        if (fileUrl.isNotEmpty()) {
            val fileName = fileUrl.substringAfterLast("/")

            val downloadDir = File(BaseExtendApplication.app!!.getExternalFilesDir(""), "download")

            if (downloadDir.exists()) {

                val file = File("${downloadDir.absolutePath}/$fileName")

                var length = if (file.exists()) file.length() else 0

                val fileInfo = File("${downloadDir.absolutePath}/$fileName.tmp")

                if (length > 0) {
                    if (!fileInfo.exists()) {
                        return FileUtil.getFileSize(length)
                    } else {
                        fileInfo.bufferedReader().use { reader ->
                            val totalByte = reader.readText().toLong()
                            return FileUtil.getFileSize(totalByte)
                        }
                    }
                }
            }
        }
        return null
    }

    fun getFileByUrl(fileUrl: String): String? {
        if (fileUrl.isNotEmpty()) {
            val fileName = fileUrl.substringAfterLast("/")
            val downloadDir = File(BaseExtendApplication.app!!.getExternalFilesDir(""), "download")
            if (downloadDir.exists()) {

                val file = File("${downloadDir.absolutePath}/$fileName")

                var length = if (file.exists()) file.length() else 0

                val fileInfo = File("${downloadDir.absolutePath}/$fileName.tmp")

                if (length > 0) {
                    if (!fileInfo.exists()) {
                        return file.path
                    } else {
                        fileInfo.bufferedReader().use { reader ->
                            val totalByte = reader.readText().toLong()
                            if (length >= totalByte) {
                                fileInfo.delete()
                                return file.path
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    interface DownloadService {

        @Streaming
        @GET
        suspend fun downloadFile(@Url fileUrl: String?, @Header("Range") range: String): ResponseBody
    }
}