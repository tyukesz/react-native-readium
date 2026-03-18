import Combine
import Foundation
import ReadiumShared
import ReadiumStreamer
import UIKit

struct PublicationRestrictionConfiguration {
  let allowedHrefs: Set<String>
  let paywallHTML: String?
}

final class ReaderService: Loggable {
  var app: AppModule?
  private let assetRetriever: AssetRetriever
  private let publicationOpener: PublicationOpener
  private var subscriptions = Set<AnyCancellable>()

  init() {
    do {
      self.app = try AppModule()
    } catch {
      print("TODO: An error occurred instantiating the ReaderService")
      print(error)
    }

    let httpClient = DefaultHTTPClient()
    let assetRetriever = AssetRetriever(httpClient: httpClient)
    let parser = DefaultPublicationParser(
      httpClient: httpClient,
      assetRetriever: assetRetriever,
      pdfFactory: DefaultPDFDocumentFactory()
    )

    self.assetRetriever = assetRetriever
    self.publicationOpener = PublicationOpener(parser: parser)
  }
  
  static func locatorFromLocation(
    _ location: NSDictionary?,
    _ publication: Publication?
  ) async -> Locator? {
    guard location != nil else {
      return nil
    }

    let hasLocations = location?["locations"] != nil
    let hasType = (location?["type"] as? String)?.isEmpty == false
    let hasChildren = location?["children"] != nil
    let hasHashHref = (location?["href"] as? String)?.contains("#") == true
    let hasTemplated = location?["templated"] != nil

    // check that we're not dealing with a Link
    if ((!hasType || hasChildren || hasHashHref || hasTemplated) && !hasLocations) {
      guard let publication = publication else {
        return nil
      }
      guard let link = try? Link(json: location) else {
        return nil
      }

      return await publication.locate(link)
    } else {
      return try? Locator(json: location)
    }
  }

  func buildViewController(
    url: String,
    bookId: String,
    location: NSDictionary?,
    restriction: PublicationRestrictionConfiguration?,
    sender: UIViewController?,
    completion: @escaping (ReaderViewController) -> Void
  ) {
    guard let reader = self.app?.reader else { return }
    self.url(path: url)
      .flatMap { self.openPublication(at: $0, allowUserInteraction: true, restriction: restriction, sender: sender ) }
      .flatMap { (pub, _) in self.checkIsReadable(publication: pub) }
      .sink(
        receiveCompletion: { error in
          print(">>>>>>>>>>> TODO: handle me", error)
        },
        receiveValue: { pub in
          Task { @MainActor in
            let requestedLocator = await ReaderService.locatorFromLocation(location, pub)
            let locator = await Self.mapLocatorToRestrictionAnchor(requestedLocator, in: pub, restriction: restriction)
            guard let viewController = reader.getViewController(
              for: pub,
              bookId: bookId,
              locator: locator
            ) else {
              return
            }

            completion(viewController)
          }
        }
      )
      .store(in: &subscriptions)
  }

  func url(path: String) -> AnyPublisher<URL, ReaderError> {
    // Absolute URL.
    if let url = URL(string: path), url.scheme != nil {
      return .just(url)
    }

    // Absolute file path.
    if path.hasPrefix("/") {
      return .just(URL(fileURLWithPath: path))
    }

    let error = NSError(
      domain: "react-native-readium",
      code: 404,
      userInfo: [NSLocalizedDescriptionKey: "Unable to locate file: \(path)"]
    )
    return .fail(ReaderError.fileNotFound(error))
  }

  private func openPublication(
    at url: URL,
    allowUserInteraction: Bool,
    restriction: PublicationRestrictionConfiguration?,
    sender: UIViewController?
  ) -> AnyPublisher<(Publication, MediaType), ReaderError> {
    Deferred {
      Future<(Publication, MediaType), ReaderError> { promise in
        Task {
          let absoluteURLCandidate = AnyURL(url: url)
          guard let absoluteURL = absoluteURLCandidate.absoluteURL else {
            promise(.failure(.fileNotFound(URLError(.badURL))))
            return
          }

          let assetResult = await self.assetRetriever.retrieve(url: absoluteURL)

          let asset: Asset
          switch assetResult {
          case .success(let retrievedAsset):
            asset = retrievedAsset
          case .failure(let error):
            switch error {
            case .schemeNotSupported:
              promise(.failure(.openFailed(error)))
            case .formatNotSupported:
              promise(.failure(.formatNotSupported))
            case .reading(let readError):
              promise(.failure(.openFailed(readError)))
            }
            return
          }

          let mediaType = asset.format.mediaType ?? .binary

          let paywallHTML = Self.paywallHTML(from: restriction?.paywallHTML)
          let paywallData = Data(paywallHTML.utf8)

          let openResult = await self.publicationOpener.open(
            asset: asset,
            allowUserInteraction: allowUserInteraction,
            onCreatePublication: { manifest, container, _ in
              guard let restriction else {
                return
              }

              let restrictedHrefs = Self.restrictedReadingOrderHrefs(
                in: manifest.readingOrder,
                allowedHrefs: restriction.allowedHrefs
              )

              guard !restrictedHrefs.isEmpty else {
                return
              }

              manifest.readingOrder = Self.filteredReadingOrder(
                from: manifest.readingOrder,
                allowedHrefs: restriction.allowedHrefs
              )

              container = TransformingContainer(container: container, transformer: { href, resource in
                let normalizedHref = Self.normalizeHrefForComparison(href.url.relativeString)
                guard restrictedHrefs.contains(normalizedHref) else {
                  return resource
                }

                return TransformingResource(resource) { _ in
                  .success(paywallData)
                }
              })
            },
            sender: sender
          )

          switch openResult {
          case .success(let publication):
            promise(.success((publication, mediaType)))
          case .failure(let error):
            switch error {
            case .formatNotSupported:
              promise(.failure(.formatNotSupported))
            case .reading(let readError):
              promise(.failure(.openFailed(readError)))
            }
          }
        }
      }
    }
    .eraseToAnyPublisher()
  }

  private func checkIsReadable(publication: Publication) -> AnyPublisher<Publication, ReaderError> {
    guard !publication.isRestricted else {
      if let error = publication.protectionError {
        return .fail(.openFailed(error))
      } else {
        return .fail(.cancelled)
      }
    }
    return .just(publication)
  }

  private static func normalizeHrefForComparison(_ href: String) -> String {
    let trimmed = href.trimmingCharacters(in: .whitespacesAndNewlines)
      .replacingOccurrences(of: "^/+", with: "", options: .regularExpression)
    let withoutFragment = trimmed.components(separatedBy: "#").first ?? trimmed
    let withoutQuery = withoutFragment.components(separatedBy: "?").first ?? withoutFragment
    return withoutQuery.removingPercentEncoding ?? withoutQuery
  }

  private static func restrictedReadingOrderHrefs(
    in readingOrder: [Link],
    allowedHrefs: Set<String>
  ) -> Set<String> {
    Set(
      readingOrder
        .map { normalizeHrefForComparison($0.href) }
        .filter { !allowedHrefs.contains($0) }
    )
  }

  private static func filteredReadingOrder(
    from readingOrder: [Link],
    allowedHrefs: Set<String>
  ) -> [Link] {
    let allowedLinks = readingOrder.filter {
      allowedHrefs.contains(normalizeHrefForComparison($0.href))
    }
    let firstRestricted = readingOrder.first {
      !allowedHrefs.contains(normalizeHrefForComparison($0.href))
    }

    if let firstRestricted {
      return allowedLinks + [firstRestricted]
    }

    return allowedLinks
  }

  private static func restrictionAnchorLink(
    in publication: Publication,
    restriction: PublicationRestrictionConfiguration?
  ) -> Link? {
    guard let allowedHrefs = restriction?.allowedHrefs else {
      return nil
    }

    return publication.readingOrder.first {
      !allowedHrefs.contains(normalizeHrefForComparison($0.href))
    }
  }

  private static func mapLocatorToRestrictionAnchor(
    _ locator: Locator?,
    in publication: Publication,
    restriction: PublicationRestrictionConfiguration?
  ) async -> Locator? {
    guard let locator, let allowedHrefs = restriction?.allowedHrefs else {
      return locator
    }

    let href = normalizeHrefForComparison(locator.href.url.relativeString)
    guard !allowedHrefs.contains(href),
          let anchor = restrictionAnchorLink(in: publication, restriction: restriction) else {
      return locator
    }

    return await publication.locate(anchor) ?? locator
  }

  private static func paywallHTML(from customHTML: String?) -> String {
    if let customHTML, !customHTML.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      return customHTML
    }

    return """
    <?xml version=\"1.0\" encoding=\"utf-8\"?>
    <!DOCTYPE html>
    <html xmlns=\"http://www.w3.org/1999/xhtml\">
      <head>
        <meta charset=\"utf-8\" />
        <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
        <title>Subscription required</title>
        <style>
          :root { color-scheme: light dark; }
          html, body {
            margin: 0;
            min-height: 100%;
            font-family: var(--RS__baseFontFamily, -apple-system, BlinkMacSystemFont, sans-serif);
            background: var(--RS__backgroundColor, #111111);
            color: var(--RS__textColor, #f5f5f5);
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
    """
  }
}
