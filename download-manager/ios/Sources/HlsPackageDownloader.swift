import Foundation

/// Materializes finite audio HLS playlists through Nexa's shared URLSession.
@MainActor
final class HlsPackageDownloader {
    struct Result {
        let playlist: URL
        let receivedBytes: Int64
    }

    private var receivedBytes: Int64 = 0
    private var resourceIndex = 0
    private let maximumPlaylistDepth = 4
    private let maximumPlaylistBytes = 4 * 1024 * 1024
    private let maximumResourceBytes = 128 * 1024 * 1024
    private let session = NexaNetwork.sharedURLSession

    func download(
        sourceURL: String,
        destination: URL,
        headers: [String: String],
        onProgress: (Int64) -> Void
    ) async throws -> Result {
        receivedBytes = 0
        resourceIndex = 0
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        let playlist = try await materialize(
            sourceURL: sourceURL,
            destination: destination,
            headers: headers,
            depth: 0,
            rangeTracker: ByteRangeTracker(),
            onProgress: onProgress
        )
        return Result(playlist: playlist, receivedBytes: receivedBytes)
    }

    private func materialize(
        sourceURL: String,
        destination: URL,
        headers: [String: String],
        depth: Int,
        rangeTracker: ByteRangeTracker,
        onProgress: (Int64) -> Void
    ) async throws -> URL {
        guard depth <= maximumPlaylistDepth else { throw HlsDownloadError.playlistNestingTooDeep }
        guard let playlistURL = URL(string: sourceURL) else { throw HlsDownloadError.invalidResourceURL }
        var playlistRequest = URLRequest(url: playlistURL)
        playlistRequest.timeoutInterval = 30
        playlistRequest.cachePolicy = .reloadIgnoringLocalCacheData
        for (name, value) in headers { playlistRequest.setValue(value, forHTTPHeaderField: name) }
        let (playlistData, rawResponse) = try await session.data(for: playlistRequest)
        guard let response = rawResponse as? HTTPURLResponse else { throw HlsDownloadError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else {
            throw HlsDownloadError.httpStatus(response.statusCode)
        }
        guard playlistData.count <= maximumPlaylistBytes else { throw HlsDownloadError.playlistTooLarge }
        let content = String(decoding: playlistData, as: UTF8.self)
            .trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF}"))
        let lines = content.components(separatedBy: .newlines)
        guard lines.first?.trimmingCharacters(in: .whitespacesAndNewlines) == "#EXTM3U" else {
            throw HlsDownloadError.invalidPlaylist
        }
        if let variant = selectAudioVariant(lines, baseURL: sourceURL) {
            return try await materialize(
                sourceURL: variant,
                destination: destination,
                headers: headers,
                depth: depth + 1,
                rangeTracker: ByteRangeTracker(),
                onProgress: onProgress
            )
        }
        guard lines.contains(where: { $0.trimmingCharacters(in: .whitespacesAndNewlines) == "#EXT-X-ENDLIST" }) else {
            throw HlsDownloadError.livePlaylist
        }

        var outputLines: [String] = []
        var pendingRange: String?
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("#EXT-X-PART:") || trimmed.hasPrefix("#EXT-X-PRELOAD-HINT:") ||
                trimmed.hasPrefix("#EXT-X-SKIP:") || trimmed.hasPrefix("#EXT-X-RENDITION-REPORT:") {
                throw HlsDownloadError.lowLatencyPlaylist
            }
            if trimmed.hasPrefix("#EXT-X-BYTERANGE:") {
                pendingRange = String(trimmed.dropFirst("#EXT-X-BYTERANGE:".count)).trimmingCharacters(in: .whitespaces)
                continue
            }
            if trimmed.hasPrefix("#EXT-X-KEY:") || trimmed.hasPrefix("#EXT-X-SESSION-KEY:") {
                let method = attribute("METHOD", in: trimmed)?.uppercased()
                if let method, method != "NONE", method != "AES-128" {
                    throw HlsDownloadError.unsupportedEncryption(method)
                }
                guard let reference = attribute("URI", in: trimmed) else {
                    outputLines.append(line)
                    continue
                }
                let remote = try resolve(reference, relativeTo: sourceURL)
                let localName = nextResourceName(extension: extensionFor(remote, fallback: "key"))
                let target = destination.appendingPathComponent(localName)
                try await downloadResource(remote, destination: target, headers: headers, byteRange: nil)
                record(target, onProgress: onProgress)
                outputLines.append(replacingURI(in: line, with: localName))
                continue
            }
            if trimmed.hasPrefix("#EXT-X-MAP:") {
                guard let reference = attribute("URI", in: trimmed) else { throw HlsDownloadError.missingMapURI }
                let remote = try resolve(reference, relativeTo: sourceURL)
                let byteRange = try attribute("BYTERANGE", in: trimmed).map { try rangeTracker.resolve($0, url: remote) }
                let localName = nextResourceName(extension: extensionFor(remote, fallback: "mp4"))
                let target = destination.appendingPathComponent(localName)
                try await downloadResource(remote, destination: target, headers: headers, byteRange: byteRange)
                try verify(target, byteRange: byteRange)
                record(target, onProgress: onProgress)
                var rewritten = replacingURI(in: line, with: localName)
                if let byteRange {
                    rewritten = replacingAttribute("BYTERANGE", in: rewritten, with: "\(byteRange.length)@0")
                }
                outputLines.append(rewritten)
                continue
            }
            if trimmed.isEmpty || trimmed.hasPrefix("#") {
                outputLines.append(line)
                continue
            }

            let remote = try resolve(trimmed, relativeTo: sourceURL)
            let byteRange = try pendingRange.map { try rangeTracker.resolve($0, url: remote) }
            pendingRange = nil
            let localName = nextResourceName(extension: extensionFor(remote, fallback: "bin"))
            let target = destination.appendingPathComponent(localName)
            try await downloadResource(remote, destination: target, headers: headers, byteRange: byteRange)
            try verify(target, byteRange: byteRange)
            record(target, onProgress: onProgress)
            outputLines.append(localName)
        }
        guard pendingRange == nil else { throw HlsDownloadError.orphanByteRange }
        let localPlaylist = destination.appendingPathComponent("index.m3u8")
        try (outputLines.joined(separator: "\n") + "\n").write(to: localPlaylist, atomically: true, encoding: .utf8)
        return localPlaylist
    }

    private func selectAudioVariant(_ lines: [String], baseURL: String) -> String? {
        let renditions = lines.filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix("#EXT-X-MEDIA:") }
            .filter { attribute("TYPE", in: $0)?.caseInsensitiveCompare("AUDIO") == .orderedSame }
            .compactMap { line -> (String, Bool)? in
                guard let reference = attribute("URI", in: line), let url = try? resolve(reference, relativeTo: baseURL) else { return nil }
                return (url, attribute("DEFAULT", in: line)?.caseInsensitiveCompare("YES") == .orderedSame)
            }
        if !renditions.isEmpty { return renditions.first(where: \.1)?.0 ?? renditions[0].0 }

        var variants: [(url: String, audioOnly: Bool, bandwidth: Int64)] = []
        for index in lines.indices where lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("#EXT-X-STREAM-INF:") {
            guard let nextLine = lines.dropFirst(index + 1).first(where: {
                !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                    !$0.trimmingCharacters(in: .whitespaces).hasPrefix("#")
            }), let url = try? resolve(nextLine.trimmingCharacters(in: .whitespaces), relativeTo: baseURL) else { continue }
            let codecs = attribute("CODECS", in: lines[index])?.lowercased() ?? ""
            let hasAudioCodec = ["mp4a", "opus", "vorbis"].contains(where: codecs.contains)
            let hasVideoCodec = ["avc", "vp9", "av01", "hev1", "hvc1"].contains(where: codecs.contains)
            let bandwidth = Int64(attribute("AVERAGE-BANDWIDTH", in: lines[index]) ?? attribute("BANDWIDTH", in: lines[index]) ?? "") ?? 0
            variants.append((url, hasAudioCodec && !hasVideoCodec, bandwidth))
        }
        return variants.max { left, right in
            left.audioOnly == right.audioOnly ? left.bandwidth < right.bandwidth : !left.audioOnly && right.audioOnly
        }?.url
    }

    private func downloadResource(
        _ url: String,
        destination: URL,
        headers: [String: String],
        byteRange: ByteRange?
    ) async throws {
        var requestHeaders = headers
        if let byteRange {
            requestHeaders["Range"] = "bytes=\(byteRange.start)-\(byteRange.start + byteRange.length - 1)"
            requestHeaders["Accept-Encoding"] = "identity"
        }
        guard let resourceURL = URL(string: url) else { throw HlsDownloadError.invalidResourceURL }
        var request = URLRequest(url: resourceURL)
        request.timeoutInterval = 120
        request.cachePolicy = .reloadIgnoringLocalCacheData
        for (name, value) in requestHeaders { request.setValue(value, forHTTPHeaderField: name) }
        let (temporaryURL, rawResponse) = try await session.download(for: request)
        guard let response = rawResponse as? HTTPURLResponse else { throw HlsDownloadError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else { throw HlsDownloadError.httpStatus(response.statusCode) }
        guard fileSize(temporaryURL) <= Int64(maximumResourceBytes) else { throw HlsDownloadError.resourceTooLarge }
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: temporaryURL, to: destination)
        guard FileManager.default.fileExists(atPath: destination.path) else { throw HlsDownloadError.resourceDownloadFailed }
    }

    private func record(_ file: URL, onProgress: (Int64) -> Void) {
        receivedBytes += fileSize(file)
        onProgress(receivedBytes)
    }

    private func verify(_ file: URL, byteRange: ByteRange?) throws {
        guard let byteRange else { return }
        guard fileSize(file) == byteRange.length else { throw HlsDownloadError.rangeNotHonored }
    }

    private func resolve(_ reference: String, relativeTo base: String) throws -> String {
        guard let baseURL = URL(string: base), let url = URL(string: reference, relativeTo: baseURL)?.absoluteURL,
              let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else {
            throw HlsDownloadError.invalidResourceURL
        }
        return url.absoluteString
    }

    private func attribute(_ name: String, in line: String) -> String? {
        let pattern = "(?:^|[:,])\\s*\(NSRegularExpression.escapedPattern(for: name))=(?:\"([^\"]*)\"|([^,]*))"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { return nil }
        for index in 1...2 {
            if let range = Range(match.range(at: index), in: line) { return String(line[range]).trimmingCharacters(in: .whitespaces) }
        }
        return nil
    }

    private func replacingURI(in line: String, with localName: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: #"URI=\"[^\"]*\""#, options: [.caseInsensitive]),
              let range = Range(regex.rangeOfFirstMatch(in: line, range: NSRange(line.startIndex..., in: line)), in: line) else { return line }
        var result = line
        result.replaceSubrange(range, with: "URI=\"\(localName)\"")
        return result
    }

    private func replacingAttribute(_ name: String, in line: String, with value: String) -> String {
        guard let regex = try? NSRegularExpression(
            pattern: "(?<=[,:])\\s*\(NSRegularExpression.escapedPattern(for: name))=(?:\"[^\"]*\"|[^,]*)",
            options: [.caseInsensitive]
        ) else { return line }
        return regex.stringByReplacingMatches(in: line, range: NSRange(line.startIndex..., in: line), withTemplate: "\(name)=\(value)")
    }

    private func extensionFor(_ url: String, fallback: String) -> String {
        let candidate = URL(string: url)?.pathExtension.lowercased() ?? ""
        guard (1...8).contains(candidate.count), candidate.allSatisfy({ $0.isLetter || $0.isNumber }) else { return fallback }
        return candidate
    }

    private func nextResourceName(extension fileExtension: String) -> String {
        resourceIndex += 1
        return String(format: "resource-%06d.%@", resourceIndex, fileExtension)
    }

    private func fileSize(_ url: URL) -> Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }

    private struct ByteRange {
        let length: Int64
        let start: Int64
    }

    private final class ByteRangeTracker {
        private var lastURL: String?
        private var nextOffset: Int64 = 0

        func resolve(_ value: String, url: String) throws -> ByteRange {
            let parts = value.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)
            guard let length = Int64(parts[0]), length > 0 else { throw HlsDownloadError.invalidByteRange }
            let start: Int64
            if parts.count == 2 {
                guard let explicitStart = Int64(parts[1]), explicitStart >= 0 else { throw HlsDownloadError.invalidByteRange }
                start = explicitStart
            } else {
                guard lastURL == url else { throw HlsDownloadError.missingByteRangeOffset }
                start = nextOffset
            }
            lastURL = url
            nextOffset = start + length
            return ByteRange(length: length, start: start)
        }
    }

    private enum HlsDownloadError: Error {
        case playlistNestingTooDeep
        case httpStatus(Int)
        case invalidResponse
        case playlistTooLarge
        case resourceTooLarge
        case invalidPlaylist
        case livePlaylist
        case lowLatencyPlaylist
        case unsupportedEncryption(String)
        case missingMapURI
        case orphanByteRange
        case invalidByteRange
        case missingByteRangeOffset
        case invalidResourceURL
        case resourceDownloadFailed
        case rangeNotHonored
    }
}
