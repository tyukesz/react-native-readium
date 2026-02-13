package com.reactnativereadium

import android.net.Uri
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.reactnativereadium.utils.MetadataNormalizer
import com.reactnativereadium.utils.toWritableArray
import com.reactnativereadium.utils.toWritableMap
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.readium.r2.shared.util.FileExtension
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class HeadlessModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val httpClient = DefaultHttpClient()
  private val assetRetriever = AssetRetriever(
    reactContext.contentResolver,
    httpClient
  )
  private val publicationOpener = PublicationOpener(
    publicationParser = DefaultPublicationParser(
      context = reactContext,
      assetRetriever = assetRetriever,
      httpClient = httpClient,
      pdfFactory = null,
    )
  )

  private val tasks = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<WritableMap>>()

  @ReactMethod
  fun openPublicationHeadless(
    input: ReadableMap,
    promise: Promise
  ) {
    val url = if (input.hasKey("url")) input.getString("url") ?: "" else ""
    val providedId = if (input.hasKey("id")) input.getString("id")?.trim() else null
    val key = if (!providedId.isNullOrEmpty()) providedId else url

    if (url.isEmpty()) {
      promise.reject("E_PUBLICATION_OPEN_FAILED", "openPublicationHeadless: `url` is required")
      return
    }

    val existing = tasks[key]
    if (existing != null) {
      scope.launch {
        try {
          val result = existing.await()
          reactContext.runOnUiQueueThread { promise.resolve(result) }
        } catch (e: CancellationException) {
          reactContext.runOnUiQueueThread { promise.reject("E_CANCELLED", "Cancelled", e) }
        } catch (e: Throwable) {
          // Existing task already mapped errors; still guard.
          reactContext.runOnUiQueueThread {
            promise.reject("E_PUBLICATION_OPEN_FAILED", e.message, e)
          }
        }
      }
      return
    }

    val deferred = scope.async {
      buildIndexPayload(url)
    }

    tasks[key] = deferred

    scope.launch {
      try {
        val result = deferred.await()
        reactContext.runOnUiQueueThread { promise.resolve(result) }
      } catch (e: CancellationException) {
        reactContext.runOnUiQueueThread { promise.reject("E_CANCELLED", "Cancelled", e) }
      } catch (e: HeadlessException) {
        reactContext.runOnUiQueueThread { promise.reject(e.code, e.message, e) }
      } catch (e: Throwable) {
        reactContext.runOnUiQueueThread {
          promise.reject("E_PUBLICATION_OPEN_FAILED", e.message, e)
        }
      } finally {
        // Only clear if we still own the entry.
        val current = tasks[key]
        if (current == deferred) {
          tasks.remove(key)
        }
      }
    }
  }

  @ReactMethod
  fun cancelHeadless(id: String) {
    val key = id.trim()
    if (key.isEmpty()) return

    val task = tasks.remove(key)
    task?.cancel()
  }

  private class HeadlessException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
  ) : Exception(message, cause)

  private fun normalizeInputToFilePath(urlOrPath: String): String {
    val trimmed = urlOrPath.trim()

    // file:///... -> /...
    if (trimmed.startsWith("file:", ignoreCase = true)) {
      return Uri.parse(trimmed).path ?: trimmed
    }

    // Absolute path.
    if (trimmed.startsWith("/")) {
      return trimmed
    }

    // Best-effort fallback.
    return trimmed
  }

  private suspend fun buildIndexPayload(urlOrPath: String): WritableMap {
    val path = normalizeInputToFilePath(urlOrPath)
    val publicationFile = File(path).absoluteFile

    if (!publicationFile.exists()) {
      throw HeadlessException(
        code = "E_PUBLICATION_OPEN_FAILED",
        message = "File does not exist: $path"
      )
    }

    val publicationUrl = runCatching { publicationFile.toUrl() }
      .getOrElse {
        throw HeadlessException(
          code = "E_PUBLICATION_OPEN_FAILED",
          message = "Invalid publication path: $path"
        )
      }

    val fileExtension = publicationFile.extension
      .takeIf { it.isNotEmpty() }
      ?.lowercase(Locale.ROOT)

    var retrieveError: AssetRetriever.RetrieveUrlError? = null
    val asset = assetRetriever
      .retrieve(
        publicationUrl,
        FormatHints(fileExtension = fileExtension?.let { FileExtension(it) })
      )
      .onFailure { retrieveError = it }
      .getOrNull()
      ?: run {
        val s = retrieveError?.toString().orEmpty()
        if (s.contains("FormatNotSupported", ignoreCase = true)) {
          throw HeadlessException("E_UNSUPPORTED_FORMAT", "Unsupported publication format")
        }
        throw HeadlessException("E_PUBLICATION_OPEN_FAILED", "Unable to retrieve publication asset")
      }

    var openError: PublicationOpener.OpenError? = null
    val publication = publicationOpener
      .open(
        asset = asset,
        allowUserInteraction = false
      )
      .onFailure { openError = it }
      .getOrNull()
      ?: run {
        val s = openError?.toString().orEmpty()
        if (
          s.contains("UserInteraction", ignoreCase = true) ||
          s.contains("LCP", ignoreCase = true) ||
          s.contains("passphrase", ignoreCase = true)
        ) {
          throw HeadlessException(
            "E_DRM_NEEDS_USER_INTERACTION",
            "Publication is DRM-protected and needs user interaction"
          )
        }
        if (s.contains("FormatNotSupported", ignoreCase = true)) {
          throw HeadlessException("E_UNSUPPORTED_FORMAT", "Unsupported publication format")
        }
        throw HeadlessException("E_PUBLICATION_OPEN_FAILED", "Failed to open publication")
      }

    val toc = publication.tableOfContents
    val positions = try {
      publication.positions()
    } catch (_: Throwable) {
      emptyList()
    }

    return Arguments.createMap().apply {
      putArray("tableOfContents", toc.toWritableArray())

      putArray(
        "positions",
        positions.map { it.toWritableMap() }.let { list ->
          Arguments.createArray().apply { list.forEach { pushMap(it) } }
        }
      )

      putMap("metadata", MetadataNormalizer.normalize(publication.metadata))

      // Nice-to-have: readingOrder.
      putArray("readingOrder", publication.readingOrder.toWritableArray())
    }
  }

  companion object {
    const val NAME = "HeadlessModule"
  }
}
