package com.reactnativereadium.reader

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.util.RNLog
import com.reactnativereadium.utils.LinkOrLocator
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.FileExtension
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

data class PublicationRestrictionConfiguration(
  val allowedHrefs: Set<String>,
  val paywallHTML: String?
)

class ReaderService(
  private val reactContext: ReactApplicationContext
) {
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

  fun locatorFromLinkOrLocator(
    location: LinkOrLocator?,
    publication: Publication,
  ): Locator? {

    if (location == null) return null

    when (location) {
      is LinkOrLocator.Link -> {
        return publication.locatorFromLink(location.link)
      }
      is LinkOrLocator.Locator -> {
        return location.locator
      }
    }

    return null
  }

  suspend fun openPublication(
    fileName: String,
    initialLocation: LinkOrLocator?,
    restriction: PublicationRestrictionConfiguration?,
    callback: suspend (fragment: BaseReaderFragment) -> Unit
  ) {
    val publicationFile = File(fileName).absoluteFile
    if (!publicationFile.exists()) {
      RNLog.e(reactContext, "Failed to open publication: File does not exist: $fileName")
      return
    }
    val publicationUrl = runCatching {
      publicationFile.toUrl()
    }
      .onFailure {
        RNLog.e(
          reactContext,
          "Invalid publication path: $fileName - ${it.message}"
        )
      }
      .getOrNull()
      ?: return

    val fileExtension = publicationFile.extension
      .takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    val asset = assetRetriever
      .retrieve(
        publicationUrl,
        FormatHints(fileExtension = fileExtension?.let { FileExtension(it) })
      )
      .onFailure {
        RNLog.w(reactContext, "Unable to retrieve publication asset: ${it.message}")
      }
      .getOrNull()
      ?: return

    val onCreatePublication: (Publication.Builder) -> Unit = transform@{ builder ->
      val currentRestriction = restriction ?: return@transform
      val restrictedHrefs = restrictedReadingOrderHrefs(
        readingOrder = builder.manifest.readingOrder,
        allowedHrefs = currentRestriction.allowedHrefs
      )

      if (restrictedHrefs.isEmpty()) {
        return@transform
      }

      builder.manifest = builder.manifest.copy(
        readingOrder = filteredReadingOrder(
          readingOrder = builder.manifest.readingOrder,
          allowedHrefs = currentRestriction.allowedHrefs
        )
      )

      val paywallBytes = paywallHTML(currentRestriction.paywallHTML)
        .toByteArray(StandardCharsets.UTF_8)

      builder.container = TransformingContainer(builder.container) { href, resource ->
        val normalizedHref = normalizeHrefForComparison(href.toString())
        if (!restrictedHrefs.contains(normalizedHref)) {
          resource
        } else {
          object : TransformingResource(resource, false) {
            override suspend fun transform(data: Try<ByteArray, ReadError>): Try<ByteArray, ReadError> {
              return Try.Companion.success(paywallBytes)
            }
          }
        }
      }
    }

    publicationOpener
      .open(
        asset = asset,
        credentials = null,
        allowUserInteraction = false,
        onCreatePublication = onCreatePublication,
        warnings = null
      )
      .onSuccess {
        val requestedLocator = locatorFromLinkOrLocator(initialLocation, it)
        val locator = mapLocatorToRestrictionAnchor(requestedLocator, it, restriction)
        val readerFragment = EpubReaderFragment.newInstance()
        readerFragment.initFactory(it, locator)
        callback.invoke(readerFragment)
      }
      .onFailure {
        RNLog.w(
          reactContext,
          "Error executing ReaderService.openPublication: ${it.message}"
        )
        // TODO: implement failure event
      }
  }

  sealed class Event {

    class ImportPublicationFailed(val errorMessage: String?) : Event()

    object UnableToMovePublication : Event()

    object ImportPublicationSuccess : Event()

    object ImportDatabaseFailed : Event()

    class OpenBookError(val errorMessage: String?) : Event()
  }

  private fun normalizeHrefForComparison(href: String): String {
    val trimmed = href.trim().replace(Regex("^/+"), "")
    val withoutFragment = trimmed.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    return runCatching {
      java.net.URLDecoder.decode(withoutQuery, StandardCharsets.UTF_8.name())
    }.getOrDefault(withoutQuery)
  }

  private fun restrictedReadingOrderHrefs(
    readingOrder: List<Link>,
    allowedHrefs: Set<String>
  ): Set<String> = readingOrder
    .map { normalizeHrefForComparison(it.href.toString()) }
    .filterTo(mutableSetOf()) { !allowedHrefs.contains(it) }

  private fun filteredReadingOrder(
    readingOrder: List<Link>,
    allowedHrefs: Set<String>
  ): List<Link> {
    val allowedLinks = readingOrder.filter {
      allowedHrefs.contains(normalizeHrefForComparison(it.href.toString()))
    }
    val firstRestricted = readingOrder.firstOrNull {
      !allowedHrefs.contains(normalizeHrefForComparison(it.href.toString()))
    }

    return if (firstRestricted != null) {
      allowedLinks + firstRestricted
    } else {
      allowedLinks
    }
  }

  private fun restrictionAnchorLink(
    publication: Publication,
    restriction: PublicationRestrictionConfiguration?
  ): Link? {
    val allowedHrefs = restriction?.allowedHrefs ?: return null
    return publication.readingOrder.firstOrNull {
      !allowedHrefs.contains(normalizeHrefForComparison(it.href.toString()))
    }
  }

  private fun mapLocatorToRestrictionAnchor(
    locator: Locator?,
    publication: Publication,
    restriction: PublicationRestrictionConfiguration?
  ): Locator? {
    val allowedHrefs = restriction?.allowedHrefs ?: return locator
    val currentLocator = locator ?: return null
    val href = normalizeHrefForComparison(currentLocator.href.toString())

    if (allowedHrefs.contains(href)) {
      return currentLocator
    }

    return restrictionAnchorLink(publication, restriction)
      ?.let(publication::locatorFromLink)
      ?: currentLocator
  }

  private fun paywallHTML(customHTML: String?): String {
    if (!customHTML.isNullOrBlank()) {
      return customHTML
    }

    return """
      <?xml version="1.0" encoding="utf-8"?>
      <!DOCTYPE html>
      <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>Subscription required</title>
          <style>
            :root { color-scheme: light dark; }
            html, body {
              margin: 0;
              min-height: 100%;
              font-family: sans-serif;
              background: #111111;
              color: #f5f5f5;
            }
            body {
              display: flex;
              align-items: center;
              justify-content: center;
              padding: 2rem;
              box-sizing: border-box;
            }
            main {
              max-width: 28rem;
              text-align: center;
            }
            h1 {
              margin: 0 0 0.75rem;
              font-size: 2rem;
              line-height: 1.1;
            }
            p {
              margin: 0;
              font-size: 1rem;
              line-height: 1.6;
              opacity: 0.82;
            }
          </style>
        </head>
        <body>
          <main>
            <h1>Subscription required</h1>
            <p>Continue reading with an active subscription.</p>
          </main>
        </body>
      </html>
    """.trimIndent()
  }
}
