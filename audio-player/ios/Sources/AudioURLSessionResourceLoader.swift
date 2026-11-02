@preconcurrency import AVFoundation
import Foundation
import UniformTypeIdentifiers

/// Feeds AVPlayer through Nexa's shared URLSession while retaining native decoding.
@MainActor
final class AudioURLSessionResourceLoader: NSObject, @preconcurrency AVAssetResourceLoaderDelegate {
    private static let chunkSize = 1024 * 1024
    private let headers: [String: String]
    private let isHLS: Bool
    private let session: URLSession
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(headers: [String: String], isHLS: Bool) {
        self.headers = headers
        self.isHLS = isHLS
        session = NexaNetwork.sharedURLSession
        super.init()
    }

    func proxyURL(for sourceURL: URL) -> URL? {
        guard var components = URLComponents(url: sourceURL, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else { return nil }
        components.scheme = "nexa-\(scheme)"
        return components.url
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        let identifier = ObjectIdentifier(loadingRequest)
        tasks[identifier] = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.load(loadingRequest, identifier: identifier)
        }
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        tasks.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    func cancelAll() {
        for task in tasks.values { task.cancel() }
        tasks.removeAll(keepingCapacity: false)
    }

    private func load(_ loadingRequest: AVAssetResourceLoadingRequest, identifier: ObjectIdentifier) async {
        defer { tasks.removeValue(forKey: identifier) }
        do {
            guard let proxy = loadingRequest.request.url,
                  let source = sourceURL(for: proxy) else {
                throw ResourceLoaderError.invalidURL
            }
            if let dataRequest = loadingRequest.dataRequest {
                try await loadData(dataRequest, content: loadingRequest.contentInformationRequest, source: source)
            } else if let content = loadingRequest.contentInformationRequest {
                try await loadContentInformation(content, source: source)
            }
            if !Task.isCancelled { loadingRequest.finishLoading() }
        } catch is CancellationError {
            loadingRequest.finishLoading(with: CancellationError())
        } catch {
            loadingRequest.finishLoading(with: error)
        }
    }

    private func loadContentInformation(
        _ content: AVAssetResourceLoadingContentInformationRequest,
        source: URL
    ) async throws {
        var request = makeRequest(source)
        request.httpMethod = "GET"
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        let (_, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse,
              (200..<300).contains(response.statusCode) else { throw ResourceLoaderError.badResponse }
        applyContentInformation(content, response: response, fallbackLength: nil)
    }

    private func loadData(
        _ dataRequest: AVAssetResourceLoadingDataRequest,
        content: AVAssetResourceLoadingContentInformationRequest?,
        source: URL
    ) async throws {
        let playlistRequest = isHLS && source.pathExtension.lowercased() == "m3u8"
        let initialOffset = max(dataRequest.currentOffset, dataRequest.requestedOffset)
        let targetLength = dataRequest.requestsAllDataToEndOfResource ? Int64.max : Int64(max(0, dataRequest.requestedLength))
        var sent: Int64 = 0
        var offset = initialOffset

        repeat {
            try Task.checkCancellation()
            let size = playlistRequest ? 0 : min(Int64(Self.chunkSize), max(1, targetLength - sent))
            var request = makeRequest(source)
            request.httpMethod = "GET"
            if !playlistRequest {
                request.setValue("bytes=\(offset)-\(offset + size - 1)", forHTTPHeaderField: "Range")
            }
            let (rawData, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse,
                  (200..<300).contains(response.statusCode) else { throw ResourceLoaderError.badResponse }

            let rangedResponse = response.statusCode == 206
            if !playlistRequest {
                guard rangedResponse,
                      let range = Self.contentRange(response),
                      range.start == offset else { throw ResourceLoaderError.invalidRange }
            }
            applyContentInformation(content, response: response, fallbackLength: nil)

            var data = rawData
            if playlistRequest, data.starts(with: Data("#EXTM3U".utf8)) {
                data = rewritePlaylist(data, baseURL: source)
                content?.contentLength = Int64(data.count)
            } else if rangedResponse, let range = Self.contentRange(response) {
                let expectedLength = min(size, range.end - range.start + 1)
                if Int64(data.count) > expectedLength {
                    data = data.prefix(Int(expectedLength))
                }
            }

            guard !data.isEmpty else { break }
            dataRequest.respond(with: data)
            sent += Int64(data.count)
            offset += Int64(data.count)

            if playlistRequest || Int64(data.count) < size { break }
        } while sent < targetLength
    }

    private func makeRequest(_ source: URL) -> URLRequest {
        var request = URLRequest(url: source)
        request.timeoutInterval = 30
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        return request
    }

    private func sourceURL(for proxyURL: URL) -> URL? {
        guard var components = URLComponents(url: proxyURL, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(),
              scheme == "nexa-http" || scheme == "nexa-https" else { return nil }
        components.scheme = String(scheme.dropFirst("nexa-".count))
        return components.url
    }

    private func rewritePlaylist(_ data: Data, baseURL: URL) -> Data {
        guard var playlist = String(data: data, encoding: .utf8) else { return data }
        let uriPattern = try? NSRegularExpression(pattern: #"URI="([^"]+)""#)
        if let uriPattern {
            let matches = uriPattern.matches(in: playlist, range: NSRange(playlist.startIndex..., in: playlist))
            for match in matches.reversed() {
                guard let range = Range(match.range(at: 1), in: playlist),
                      let resolved = URL(string: String(playlist[range]), relativeTo: baseURL)?.absoluteURL,
                      let proxied = proxyURL(for: resolved) else { continue }
                playlist.replaceSubrange(range, with: proxied.absoluteString)
            }
        }
        playlist = playlist.components(separatedBy: .newlines).map { line in
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#"),
                  let resolved = URL(string: trimmed, relativeTo: baseURL)?.absoluteURL,
                  let proxied = proxyURL(for: resolved) else { return line }
            return line.replacingOccurrences(of: trimmed, with: proxied.absoluteString)
        }.joined(separator: "\n")
        return Data(playlist.utf8)
    }

    private func applyContentInformation(
        _ content: AVAssetResourceLoadingContentInformationRequest?,
        response: HTTPURLResponse,
        fallbackLength: Int64?
    ) {
        guard let content else { return }
        if let mime = response.mimeType, let type = UTType(mimeType: mime) {
            content.contentType = type.identifier
        } else if isHLS {
            content.contentType = UTType.m3uPlaylist.identifier
        }
        content.contentLength = Self.contentRange(response)?.total
            ?? fallbackLength
            ?? response.expectedContentLength
        content.isByteRangeAccessSupported = response.statusCode == 206
            || response.value(forHTTPHeaderField: "Accept-Ranges")?.lowercased() == "bytes"
    }

    private static func contentRange(_ response: HTTPURLResponse) -> (start: Int64, end: Int64, total: Int64?)? {
        guard let value = response.value(forHTTPHeaderField: "Content-Range") else { return nil }
        let pattern = try? NSRegularExpression(pattern: #"^bytes\s+(\d+)-(\d+)/(\d+|\*)$"#, options: [.caseInsensitive])
        guard let pattern,
              let match = pattern.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
              let startRange = Range(match.range(at: 1), in: value),
              let endRange = Range(match.range(at: 2), in: value),
              let start = Int64(value[startRange]),
              let end = Int64(value[endRange]) else { return nil }
        let total: Int64? = match.range(at: 3).location == NSNotFound
            ? nil
            : Range(match.range(at: 3), in: value).flatMap { Int64(value[$0]) }
        return (start, end, total)
    }

    private enum ResourceLoaderError: Error {
        case invalidURL
        case badResponse
        case invalidRange
    }
}
