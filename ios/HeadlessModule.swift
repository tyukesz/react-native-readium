import Foundation
import React
import ReadiumShared
import ReadiumStreamer

@objc(HeadlessModule)
final class HeadlessModule: NSObject, RCTBridgeModule {
  static func moduleName() -> String! {
    return "HeadlessModule"
  }

  static func requiresMainQueueSetup() -> Bool {
    // No UI access.
    return false
  }

  private enum HeadlessError: Error {
    case cancelled
    case drmNeedsUserInteraction(String?)
    case unsupportedFormat(String?)
    case openFailed(String?)

    var code: String {
      switch self {
      case .cancelled:
        return "E_CANCELLED"
      case .drmNeedsUserInteraction:
        return "E_DRM_NEEDS_USER_INTERACTION"
      case .unsupportedFormat:
        return "E_UNSUPPORTED_FORMAT"
      case .openFailed:
        return "E_PUBLICATION_OPEN_FAILED"
      }
    }

    var message: String {
      switch self {
      case .cancelled:
        return "Cancelled"
      case .drmNeedsUserInteraction(let msg):
        return msg ?? "Publication is DRM-protected and needs user interaction"
      case .unsupportedFormat(let msg):
        return msg ?? "Unsupported publication format"
      case .openFailed(let msg):
        return msg ?? "Failed to open publication"
      }
    }
  }

  private let httpClient = DefaultHTTPClient()
  private lazy var assetRetriever = AssetRetriever(httpClient: httpClient)
  private lazy var publicationOpener: PublicationOpener = {
    let parser = DefaultPublicationParser(
      httpClient: httpClient,
      assetRetriever: assetRetriever,
      pdfFactory: DefaultPDFDocumentFactory()
    )
    return PublicationOpener(parser: parser)
  }()

  private let syncQueue = DispatchQueue(label: "react-native-readium.HeadlessModule.sync")
  private var tasks: [String: Task<[String: Any], Error>] = [:]

  @objc(openPublicationHeadless:resolver:rejecter:)
  func openPublicationHeadless(
    _ input: NSDictionary,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    let urlString = (input["url"] as? String) ?? ""
    let mediaTypeHint = input["mediaType"] as? String
    let providedId = (input["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
    let key = (providedId?.isEmpty == false) ? providedId! : urlString

    if urlString.isEmpty {
      rejecter("E_PUBLICATION_OPEN_FAILED", "openPublicationHeadless: `url` is required", nil)
      return
    }

    let task: Task<[String: Any], Error> = syncQueue.sync {
      if let existing = tasks[key] {
        return existing
      }
      let created = Task.detached(priority: .utility) { [weak self] in
        guard let self else { throw HeadlessError.openFailed("Module deallocated") }
        return try await self.buildIndexPayload(urlString: urlString, mediaTypeHint: mediaTypeHint)
      }
      tasks[key] = created
      return created
    }

    Task {
      defer {
        self.syncQueue.sync {
          self.tasks.removeValue(forKey: key)
        }
      }

      do {
        let payload = try await task.value
        resolver(payload)
      } catch {
        self.reject(error, rejecter: rejecter)
      }
    }
  }

  @objc(cancelHeadless:)
  func cancelHeadless(_ id: String) {
    let key = id.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !key.isEmpty else { return }

    let task: Task<[String: Any], Error>? = syncQueue.sync {
      let t = tasks[key]
      tasks.removeValue(forKey: key)
      return t
    }
    task?.cancel()
  }

  private func reject(_ error: Error, rejecter: @escaping RCTPromiseRejectBlock) {
    if error is CancellationError {
      let e = HeadlessError.cancelled
      rejecter(e.code, e.message, nil)
      return
    }

    if let e = error as? HeadlessError {
      rejecter(e.code, e.message, nil)
      return
    }

    rejecter("E_PUBLICATION_OPEN_FAILED", error.localizedDescription, error)
  }

  private func resolveInputURL(_ path: String) throws -> URL {
    // Absolute URL.
    if let url = URL(string: path), url.scheme != nil {
      return url
    }

    // Absolute file path.
    if path.hasPrefix("/") {
      return URL(fileURLWithPath: path)
    }

    throw HeadlessError.openFailed("Unable to locate file: \(path)")
  }

  private func buildIndexPayload(urlString: String, mediaTypeHint: String?) async throws -> [String: Any] {
    try Task.checkCancellation()

    let url = try resolveInputURL(urlString)
    let absoluteURLCandidate = AnyURL(url: url)
    guard let absoluteURL = absoluteURLCandidate.absoluteURL else {
      throw HeadlessError.openFailed("Invalid URL: \(urlString)")
    }

    try Task.checkCancellation()

    let assetResult = await assetRetriever.retrieve(url: absoluteURL)

    let asset: Asset
    switch assetResult {
    case .success(let retrievedAsset):
      asset = retrievedAsset
    case .failure(let error):
      switch error {
      case .schemeNotSupported:
        throw HeadlessError.openFailed("URL scheme not supported")
      case .formatNotSupported:
        throw HeadlessError.unsupportedFormat(nil)
      case .reading(let readError):
        throw HeadlessError.openFailed(readError.localizedDescription)
      }
    }

    _ = mediaTypeHint // kept for API parity; toolkit derives format.

    try Task.checkCancellation()

    let openResult = await publicationOpener.open(
      asset: asset,
      allowUserInteraction: false,
      sender: nil
    )

    let publication: Publication
    switch openResult {
    case .success(let pub):
      publication = pub
    case .failure(let error):
      switch error {
      case .formatNotSupported:
        throw HeadlessError.unsupportedFormat(nil)
      case .reading(let readError):
        throw HeadlessError.openFailed(readError.localizedDescription)
      }
    }

    try Task.checkCancellation()

    if publication.isRestricted {
      let msg = publication.protectionError?.localizedDescription
      throw HeadlessError.drmNeedsUserInteraction(msg)
    }

    let tocResult = await publication.tableOfContents()
    let positionsResult = await publication.positions()

    var payload: [String: Any] = [:]

    switch tocResult {
    case .success(let links):
      payload["tableOfContents"] = links.map { $0.json }
    case .failure:
      payload["tableOfContents"] = []
    }

    switch positionsResult {
    case .success(let positions):
      payload["positions"] = positions.map { $0.json }
    case .failure:
      payload["positions"] = []
    }

    payload["metadata"] = publication.metadata.json

    // Nice-to-have: readingOrder.
    payload["readingOrder"] = publication.readingOrder.map { $0.json }

    return payload
  }
}
