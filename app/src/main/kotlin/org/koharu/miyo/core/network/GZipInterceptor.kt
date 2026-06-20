package org.koharu.miyo.core.network

import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.Response
import okio.IOException
import org.koharu.miyo.core.exceptions.WrapperIOException
import org.koharu.miyo.core.network.CommonHeaders.CONTENT_ENCODING

class GZipInterceptor : Interceptor {

        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "avif")

        override fun intercept(chain: Interceptor.Chain): Response = try {
                val request = chain.request()
                if (request.body is MultipartBody) {
                        chain.proceed(request)
                } else if (isImageRequest(request)) {
                        chain.proceed(request)
                } else {
                        val newRequest = request.newBuilder()
                        newRequest.addHeader(CONTENT_ENCODING, "gzip")
                        chain.proceed(newRequest.build())
                }
        } catch (e: IOException) {
                throw e
        } catch (e: Exception) {
                throw WrapperIOException(e)
        }

        private fun isImageRequest(request: okhttp3.Request): Boolean {
                val acceptHeader = request.header("Accept")
                if (acceptHeader != null && acceptHeader.startsWith("image/")) {
                        return true
                }
                val pathSegments = request.url.pathSegments
                val lastSegment = pathSegments.lastOrNull() ?: return false
                val dotIndex = lastSegment.lastIndexOf('.')
                if (dotIndex >= 0) {
                        val extension = lastSegment.substring(dotIndex + 1).lowercase()
                        if (extension in imageExtensions) {
                                return true
                        }
                }
                return false
        }
}
