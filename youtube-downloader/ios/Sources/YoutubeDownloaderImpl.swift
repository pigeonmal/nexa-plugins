import CryptoKit
import Foundation
import WebKit

/// Resolves YouTube's short-lived media URLs using typed client profiles.
@MainActor
public final class YoutubeDownloaderImpl: YoutubeDownloaderSpec {
    private struct CacheEntry {
        let value: YoutubePlaybackData
        let expiresAt: Date
    }

    private struct Profile {
        let name: String
        let clientName: String
        let version: String
        let id: String
        let userAgent: String
        let osName: String?
        let osVersion: String?
        let deviceMake: String?
        let deviceModel: String?
        let androidSDK: String?
        let platform: String?
        let musicEndpoint: Bool
        let mobileWeb: Bool
        let loginSupported: Bool
        let useSignatureTimestamp: Bool
        let useWebPoTokens: Bool
        let skipPlayabilityValidation: Bool
        let includeUserAgentInContext: Bool

        var origin: String {
            if musicEndpoint { return "https://music.youtube.com" }
            if mobileWeb { return "https://m.youtube.com" }
            return "https://www.youtube.com"
        }

        var endpoint: String { "\(origin)/youtubei/v1/player?prettyPrint=false" }
    }

    private struct Candidate {
        let url: String
        let itag: Int32
        let mimeType: String
        let bitrate: Int64
        let averageBitrate: Int64
        let width: Int32?
        let height: Int32?
        let contentLength: Int64?
        let quality: String
        let fps: Int32?
        let qualityLabel: String?
        let audioSampleRate: Int32?
        let audioChannels: Int32?
        let loudnessDb: Double?
        let isAudio: Bool
        let isVideo: Bool
        let isHLS: Bool
    }

    private enum ExtractionFailure: Error {
        case http(Int)
        case invalid(String)
    }

    private enum CipherOperation {
        case reverse
        case drop(Int)
        case slice(Int)
        case swap(Int, Int)

        func apply(to value: inout [Character]) {
            switch self {
            case .reverse:
                value.reverse()
            case let .drop(count), let .slice(count):
                let bounded = min(max(count, 0), value.count)
                value.removeFirst(bounded)
            case let .swap(first, second):
                guard !value.isEmpty else { return }
                let left = first % value.count
                let right = second % value.count
                value.swapAt(left, right)
            }
        }
    }

    private let session: URLSession
    private var playbackCache: [String: CacheEntry] = [:]
    private var playbackCacheOrder: [String] = []
    private var failedClients: [String: Date] = [:]
    private var scriptCache: [String: String] = [:]
    private var visitorDataCache: (value: String, expiresAt: Date)?
    private var cachedSignatureTimestamp: (value: Int64, expiresAt: Date)?
    private var disposed = false

    public required init() {
        session = NexaNetwork.sharedURLSession
    }

    public func extractYoutubeStream(_ options: YoutubeExtractStreamOptions) async throws(YoutubeDownloaderError) -> YoutubePlaybackData {
        do {
            guard !disposed else { throw ExtractionFailure.invalid("YoutubeDownloader is disposed") }
            let videoID = options.videoID.trimmingCharacters(in: .whitespacesAndNewlines)
            guard Self.isValidVideoID(videoID) else {
                throw YoutubeDownloaderError.invalidVideoID
            }
            guard !options.authenticatedOnly || !(options.cookie ?? "").isEmpty else {
                throw YoutubeDownloaderError.cookieRequired
            }
            let startedAt = ProcessInfo.processInfo.systemUptime
            let playback = try await extract(options, videoID: videoID)
            return YoutubePlaybackData(
                audioConfig: playback.audioConfig,
                videoDetails: playback.videoDetails,
                playbackTracking: playback.playbackTracking,
                streamExpiresInSeconds: playback.streamExpiresInSeconds,
                audioStream: playback.audioStream,
                videoStream: playback.videoStream,
                clientName: playback.clientName,
                extractionDurationMs: max(0, (ProcessInfo.processInfo.systemUptime - startedAt) * 1_000),
                poTokenDurationMs: playback.poTokenDurationMs,
                sabrStreamingURL: playback.sabrStreamingURL
            )
        } catch let error as YoutubeDownloaderError {
            throw error
        } catch let error as ExtractionFailure {
            switch error {
            case let .http(statusCode): throw .requestFailed(statusCode: Int32(statusCode))
            case let .invalid(message): throw .invalidResponse(message: message)
            }
        } catch {
            throw .invalidResponse(message: error.localizedDescription)
        }
    }

    public func generatePoToken(_ videoID: String, _ cookie: String?, _ visitorData: String?) async throws(YoutubeDownloaderError) -> String {
        guard !disposed else { throw .tokenUnavailable(message: "YoutubeDownloader is disposed") }
        let normalizedID = videoID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidVideoID(normalizedID) else {
            throw .invalidVideoID
        }
        do {
            let visitor: String?
            if let supplied = visitorData?.nonEmpty {
                visitor = supplied
            } else {
                visitor = try await fetchVisitorData(cookie: cookie)
            }
            guard let visitor else { throw ExtractionFailure.invalid("Unable to obtain YouTube visitor data") }
            let tokens = try await YoutubeBotGuardTokenProvider.generate(
                videoID: normalizedID,
                visitorData: visitor,
                cookie: cookie
            )
            return tokens.playerRequest
        } catch {
            throw .tokenUnavailable(message: error.localizedDescription)
        }
    }

    public func reportPlaybackFailure(_ videoID: String, _ clientName: String, _ cookie: String?, _ visitorData: String?) {
        guard !disposed else { return }
        failedClients["\(videoID)|\(clientName)|\(cookieFingerprint(cookie))|\(visitorData ?? "")"] = Date().addingTimeInterval(300)
        clearCache(videoID)
    }

    public func clearCache(_ videoID: String?) {
        guard let videoID else {
            playbackCache.removeAll(keepingCapacity: true)
            playbackCacheOrder.removeAll(keepingCapacity: true)
            return
        }
        playbackCache = playbackCache.filter { !$0.key.hasPrefix("\(videoID)|") }
        playbackCacheOrder.removeAll { $0.hasPrefix("\(videoID)|") }
    }

    public func dispose() {
        disposed = true
        clearCache(nil)
        failedClients.removeAll(keepingCapacity: false)
        scriptCache.removeAll(keepingCapacity: false)
        visitorDataCache = nil
    }

    private func extract(_ options: YoutubeExtractStreamOptions, videoID: String) async throws -> YoutubePlaybackData {
        let cacheKey = "\(videoID)|\(options.playlistID ?? "")|\(options.audioQuality)|\(options.videoQuality ?? 0)|\(cookieFingerprint(options.cookie))|\(options.visitorData ?? "")|\(options.authenticatedOnly)"
        if options.clientName?.isEmpty != false,
           let cached = playbackCache[cacheKey], cached.expiresAt > Date().addingTimeInterval(30) {
            return cached.value
        }

        let profiles = try profilesFor(options)
        var lastError: Error = ExtractionFailure.invalid("No YouTube profile returned a stream")
        for profile in profiles where !isTemporarilyExcluded(profile, options: options, videoID: videoID) {
            do {
                let visitorData: String?
                if let supplied = options.visitorData?.nonEmpty {
                    visitorData = supplied
                } else {
                    visitorData = try await fetchVisitorData(cookie: options.cookie)
                }
                let tokens: YoutubeBotGuardTokenProvider.Tokens?
                if profile.useWebPoTokens, let visitorData {
                    tokens = try? await YoutubeBotGuardTokenProvider.generate(
                        videoID: videoID,
                        visitorData: visitorData,
                        cookie: options.cookie
                    )
                } else {
                    tokens = nil
                }
                let response = try await requestPlayer(
                    profile,
                    videoID: videoID,
                    playlistID: options.playlistID,
                    cookie: profile.loginSupported ? options.cookie : nil,
                    visitorData: visitorData,
                    poToken: tokens?.playerRequest
                )
                let playback = try await parsePlayback(
                    response,
                    profile: profile,
                    quality: options.audioQuality,
                    videoQuality: options.videoQuality,
                    token: tokens?.streamingData
                )
                failedClients.removeValue(forKey: "\(videoID)|\(profile.name)|\(cookieFingerprint(options.cookie))|\(options.visitorData ?? "")")
                if options.clientName?.isEmpty != false {
                    store(playback, key: cacheKey)
                }
                return playback
            } catch {
                lastError = error
                continue
            }
        }
        if let error = lastError as? ExtractionFailure {
            throw error
        }
        throw ExtractionFailure.invalid(lastError.localizedDescription)
    }

    private func profilesFor(_ options: YoutubeExtractStreamOptions) throws -> [Profile] {
        let anonymous = Self.profiles.filter { !$0.name.hasPrefix("WEB_REMIX") }
        let authenticated = ["WEB_REMIX", "TVHTML5_SIMPLY", "TVHTML5_EMBEDDED", "ANDROID", "MWEB", "WEB"]
            .compactMap { name in Self.profiles.first { $0.name == name } }
        let available = options.authenticatedOnly ? authenticated : anonymous + ((options.cookie?.isEmpty == false) ? authenticated : [])
        guard let target = options.clientName?.nonEmpty else { return available }
        let matches = Self.profiles.filter { $0.name.caseInsensitiveCompare(target) == .orderedSame || $0.clientName.caseInsensitiveCompare(target) == .orderedSame }
        guard !matches.isEmpty else { throw ExtractionFailure.invalid("Unknown YouTube client: \(target)") }
        return matches
    }

    private func requestPlayer(
        _ profile: Profile,
        videoID: String,
        playlistID: String?,
        cookie: String?,
        visitorData: String?,
        poToken: String?
    ) async throws -> [String: Any] {
        var client: [String: Any] = [
            "clientName": profile.clientName,
            "clientVersion": profile.version,
            "hl": "en",
            "gl": "US",
        ]
        if let visitorData { client["visitorData"] = visitorData }
        if profile.includeUserAgentInContext { client["userAgent"] = profile.userAgent }
        if let value = profile.osName { client["osName"] = value }
        if let value = profile.osVersion { client["osVersion"] = value }
        if let value = profile.deviceMake { client["deviceMake"] = value }
        if let value = profile.deviceModel { client["deviceModel"] = value }
        if let value = profile.androidSDK { client["androidSdkVersion"] = value }
        if let value = profile.platform { client["platform"] = value }

        var body: [String: Any] = [
            "videoId": videoID,
            "context": ["client": client],
            "contentCheckOk": true,
            "racyCheckOk": true,
            "videoCheckOk": true,
        ]
        if let playlistID = playlistID?.nonEmpty { body["playlistId"] = playlistID }
        if profile.useSignatureTimestamp {
            var playbackContext: [String: Any] = ["contentPlaybackContext": [:]]
            if let signatureTimestamp = try await resolveSignatureTimestamp() {
                playbackContext["contentPlaybackContext"] = ["signatureTimestamp": signatureTimestamp]
            }
            body["playbackContext"] = playbackContext
        }
        if let poToken { body["serviceIntegrityDimensions"] = ["poToken": poToken] }

        guard let url = URL(string: profile.endpoint) else { throw ExtractionFailure.invalid("Invalid YouTube player endpoint") }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 25
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        request.setValue(profile.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("1", forHTTPHeaderField: "X-Goog-Api-Format-Version")
        request.setValue(profile.id, forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(profile.version, forHTTPHeaderField: "X-YouTube-Client-Version")
        request.setValue(profile.origin, forHTTPHeaderField: "Origin")
        request.setValue(profile.origin, forHTTPHeaderField: "X-Origin")
        request.setValue(profile.name.contains("EMBEDDED") ? "https://www.reddit.com/" : "\(profile.origin)/", forHTTPHeaderField: "Referer")
        request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        if let visitorData { request.setValue(visitorData, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        if profile.loginSupported, let cookie = normalizeCookie(cookie) {
            request.setValue(cookie, forHTTPHeaderField: "Cookie")
            request.setValue("0", forHTTPHeaderField: "X-Goog-AuthUser")
            if let sapisid = sapisid(from: cookie) {
                let timestamp = Int(Date().timeIntervalSince1970)
                let digest = Insecure.SHA1.hash(data: Data("\(timestamp) \(sapisid) \(profile.origin)".utf8))
                request.setValue("SAPISIDHASH \(timestamp)_\(digest.map { String(format: "%02x", $0) }.joined())", forHTTPHeaderField: "Authorization")
            }
        }
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw ExtractionFailure.invalid("YouTube returned a non-HTTP response") }
        guard (200..<300).contains(response.statusCode) else { throw ExtractionFailure.http(response.statusCode) }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ExtractionFailure.invalid("YouTube player response was not an object")
        }
        return json
    }

    private func parsePlayback(
        _ root: [String: Any],
        profile: Profile,
        quality: YoutubeAudioQuality,
        videoQuality: Int32?,
        token: String?
    ) async throws -> YoutubePlaybackData {
        let playability = root["playabilityStatus"] as? [String: Any]
        let status = playability?["status"] as? String
        if status != "OK", !profile.skipPlayabilityValidation {
            let reason = playability?["reason"] as? String ?? (playability?["messages"] as? [String])?.first ?? "video is not playable"
            throw ExtractionFailure.invalid("YouTube \(profile.name): \(reason)")
        }
        guard let streaming = root["streamingData"] as? [String: Any] else {
            throw ExtractionFailure.invalid("YouTube \(profile.name) returned no streaming data")
        }
        let audioConfigDictionary = (root["playerConfig"] as? [String: Any])?["audioConfig"] as? [String: Any]
        let loudness = number(audioConfigDictionary?["loudnessDb"])
        let audioConfig = audioConfigDictionary.map {
            YoutubeAudioConfig(loudnessDb: number($0["loudnessDb"]), perceptualLoudnessDb: number($0["perceptualLoudnessDb"]))
        }
        var candidates: [Candidate] = []
        for key in ["formats", "adaptiveFormats"] {
            for format in streaming[key] as? [[String: Any]] ?? [] {
                if let candidate = try await parseCandidate(format, root: root, loudnessDb: loudness) { candidates.append(candidate) }
            }
        }

        let hlsURL = streaming["hlsManifestUrl"] as? String
        if candidates.isEmpty, let hlsURL, !hlsURL.isEmpty {
            let metadataRows = (streaming["adaptiveFormats"] as? [[String: Any]]) ?? (streaming["formats"] as? [[String: Any]]) ?? []
            let hlsMetadata = metadataRows.first { format in
                let mime = (format["mimeType"] as? String ?? "").lowercased()
                return mime.hasPrefix("audio/") || format["audioQuality"] != nil || format["audioSampleRate"] != nil
            }
            if let hlsMetadata {
                candidates.append(candidateFromMetadata(hlsMetadata, url: hlsURL, loudnessDb: loudness, isHLS: true, forceAudio: true))
            } else {
                candidates.append(Candidate(
                    url: hlsURL,
                    itag: 234,
                    mimeType: "application/x-mpegURL",
                    bitrate: 128_000,
                    averageBitrate: 128_000,
                    width: nil,
                    height: nil,
                    contentLength: nil,
                    quality: "medium",
                    fps: nil,
                    qualityLabel: nil,
                    audioSampleRate: 44_100,
                    audioChannels: 2,
                    loudnessDb: loudness,
                    isAudio: true,
                    isVideo: false,
                    isHLS: true
                ))
            }
        }

        let audioOnly = candidates.filter { $0.isAudio && !$0.isVideo }
        let audioPool = audioOnly.isEmpty ? candidates.filter(\.isAudio) : audioOnly
        guard let audio = selectAudio(audioPool, quality: quality) else { throw ExtractionFailure.invalid("\(profile.name) returned no playable audio stream") }
        let video: Candidate?
        if let videoQuality {
            let videos = candidates.filter { $0.isVideo && ($0.height ?? 0) > 0 }
            let targetHeight = videoQuality > 0 ? Int(videoQuality) : 1080
            let bounded = videos.filter { Int($0.height ?? 0) <= targetHeight }
            guard let selected = (bounded.isEmpty ? videos : bounded).max(by: videoLessThan) else {
                throw ExtractionFailure.invalid("\(profile.name) returned no requested video stream")
            }
            video = selected
        } else {
            video = nil
        }

        let audioStream = makeStream(audio, profile: profile, token: token)
        let videoStream = video.map { makeStream($0, profile: profile, token: token) }
        let details = root["videoDetails"] as? [String: Any]
        let videoDetails = details.map {
            YoutubeVideoDetails(
                videoID: $0["videoId"] as? String ?? "",
                title: nonEmpty($0["title"]),
                author: nonEmpty($0["author"]),
                channelID: $0["channelId"] as? String ?? "",
                lengthSeconds: $0["lengthSeconds"] as? String ?? "",
                musicVideoType: nonEmpty($0["musicVideoType"]),
                viewCount: nonEmpty($0["viewCount"])
            )
        }
        let tracking = root["playbackTracking"] as? [String: Any]
        let playbackTracking = tracking.map {
            YoutubePlaybackTracking(
                videostatsPlaybackURL: (($0["videostatsPlaybackUrl"] as? [String: Any])?["baseUrl"] as? String),
                videostatsWatchtimeURL: (($0["videostatsWatchtimeUrl"] as? [String: Any])?["baseUrl"] as? String),
                atrURL: (($0["atrUrl"] as? [String: Any])?["baseUrl"] as? String)
            )
        }
        return YoutubePlaybackData(
            audioConfig: audioConfig,
            videoDetails: videoDetails,
            playbackTracking: playbackTracking,
            streamExpiresInSeconds: Int32(max(1, integer(streaming["expiresInSeconds"]) ?? 300)),
            audioStream: audioStream,
            videoStream: videoStream,
            clientName: profile.name,
            extractionDurationMs: 0,
            poTokenDurationMs: nil,
            sabrStreamingURL: nil
        )
    }

    private func parseCandidate(_ format: [String: Any], root: [String: Any], loudnessDb: Double?) async throws -> Candidate? {
        var url = nonEmpty(format["url"])
        if url == nil, let cipher = nonEmpty(format["signatureCipher"]) ?? nonEmpty(format["cipher"]) {
            url = try await resolveCipher(cipher, root: root)
        }
        guard let url else { return nil }
        return candidateFromMetadata(format, url: url, loudnessDb: loudnessDb, isHLS: false)
    }

    private func candidateFromMetadata(_ format: [String: Any], url: String, loudnessDb: Double?, isHLS: Bool, forceAudio: Bool = false) -> Candidate {
        let rawMime = format["mimeType"] as? String ?? ""
        let mime = isHLS && rawMime.isEmpty ? "application/x-mpegURL" : rawMime.components(separatedBy: ";").first?.trimmingCharacters(in: .whitespaces).lowercased() ?? ""
        let audio = forceAudio || mime.hasPrefix("audio/") || (isHLS && !mime.hasPrefix("video/")) || format["audioQuality"] != nil || format["audioSampleRate"] != nil
        return Candidate(
            url: url,
            itag: Int32(integer(format["itag"]) ?? 0),
            mimeType: mime,
            bitrate: Int64(integer(format["bitrate"]) ?? 0),
            averageBitrate: Int64(integer(format["averageBitrate"]) ?? integer(format["bitrate"]) ?? 0),
            width: integer(format["width"]).map(Int32.init),
            height: integer(format["height"]).map(Int32.init),
            contentLength: integer(format["contentLength"]).map { Int64($0) }.flatMap { $0 > 0 ? $0 : nil },
            quality: format["quality"] as? String ?? "",
            fps: integer(format["fps"]).map(Int32.init),
            qualityLabel: nonEmpty(format["qualityLabel"]),
            audioSampleRate: integer(format["audioSampleRate"]).map(Int32.init),
            audioChannels: integer(format["audioChannels"]).map(Int32.init),
            loudnessDb: loudnessDb,
            isAudio: audio,
            isVideo: !forceAudio && mime.hasPrefix("video/"),
            isHLS: isHLS
        )
    }

    private func selectAudio(_ candidates: [Candidate], quality: YoutubeAudioQuality) -> Candidate? {
        guard !candidates.isEmpty else { return nil }
        if quality == .low { return candidates.min { audioScore($0) < audioScore($1) } }
        return candidates.max { audioScore($0) < audioScore($1) }
    }

    private func audioScore(_ candidate: Candidate) -> Int64 {
        let formatBonus: Int64 = candidate.mimeType.contains("webm") ? 25_000 : candidate.mimeType.contains("mp4") ? 10_000 : 0
        return candidate.averageBitrate + formatBonus + Int64((candidate.audioSampleRate ?? 44_100) / 1000) * 100
    }

    private func videoLessThan(_ lhs: Candidate, _ rhs: Candidate) -> Bool {
        if lhs.height != rhs.height { return (lhs.height ?? 0) < (rhs.height ?? 0) }
        let lhsMP4 = lhs.mimeType.contains("mp4") ? 1 : 0
        let rhsMP4 = rhs.mimeType.contains("mp4") ? 1 : 0
        if lhsMP4 != rhsMP4 { return lhsMP4 < rhsMP4 }
        return lhs.bitrate < rhs.bitrate
    }

    private func makeStream(_ candidate: Candidate, profile: Profile, token: String?) -> YoutubeStreamPlayback {
        var url = candidate.url
        if let token, var components = URLComponents(string: url), !components.queryItems.orEmpty.contains(where: { $0.name == "pot" }) {
            components.queryItems = components.queryItems.orEmpty + [URLQueryItem(name: "pot", value: token)]
            url = components.string ?? url
        }
        let headers = [
            "User-Agent": profile.userAgent,
            "Origin": profile.origin,
            "Referer": "\(profile.origin)/",
        ]
        let format = YoutubeStreamFormat(
            itag: candidate.itag,
            mimeType: candidate.mimeType,
            bitrate: candidate.bitrate,
            width: candidate.width,
            height: candidate.height,
            contentLength: candidate.contentLength,
            quality: candidate.quality,
            fps: candidate.fps,
            qualityLabel: candidate.qualityLabel,
            audioSampleRate: candidate.audioSampleRate,
            audioChannels: candidate.audioChannels,
            loudnessDb: candidate.loudnessDb
        )
        return YoutubeStreamPlayback(
            format: format,
            streamURL: url,
            requestHeaders: headers,
            rangeChunkSizeBytes: 10 * 1024 * 1024,
            isHLS: candidate.isHLS,
            isSABR: false,
            sabrUstreamerConfig: nil
        )
    }

    private func fetchVisitorData(cookie: String?) async throws -> String? {
        if let cached = visitorDataCache, cached.expiresAt > Date() { return cached.value }
        let userAgent = Self.profiles[0].userAgent
        if let url = URL(string: "https://www.youtube.com/sw.js_data") {
            var request = URLRequest(url: url)
            request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("application/json,text/plain,*/*", forHTTPHeaderField: "Accept")
            request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
            if let cookie = normalizeCookie(cookie) { request.setValue(cookie, forHTTPHeaderField: "Cookie") }
            if let (data, response) = try? await session.data(for: request), (response as? HTTPURLResponse)?.statusCode == 200,
               let text = String(data: data, encoding: .utf8),
               let parsed = parseVisitorData(text) {
                visitorDataCache = (parsed, Date().addingTimeInterval(1800))
                return parsed
            }
        }
        if let url = URL(string: "https://music.youtube.com") {
            var request = URLRequest(url: url)
            request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("text/html,application/xhtml+xml,*/*;q=0.8", forHTTPHeaderField: "Accept")
            if let cookie = normalizeCookie(cookie) { request.setValue(cookie, forHTTPHeaderField: "Cookie") }
            if let (data, response) = try? await session.data(for: request), (response as? HTTPURLResponse)?.statusCode == 200,
               let text = String(data: data, encoding: .utf8),
               let parsed = text.firstMatch(#"(?:VISITOR_DATA|visitorData)"\s*:\s*"([^"]+)"#) {
                visitorDataCache = (parsed, Date().addingTimeInterval(1800))
                return parsed
            }
        }
        return nil
    }

    private func parseVisitorData(_ text: String) -> String? {
        let jsonText = String(text.drop(while: { $0 != "\n" })).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = jsonText.data(using: .utf8), let root = try? JSONSerialization.jsonObject(with: data) as? [Any],
              let first = root.first as? [Any], first.count > 2,
              let third = first[2] as? [[Any]], let row = third.first?.first as? [Any], row.count > 13 else { return nil }
        return row[13] as? String
    }

    private func resolveSignatureTimestamp() async throws -> Int64? {
        if let cached = cachedSignatureTimestamp, cached.expiresAt > Date() { return cached.value }
        guard let url = URL(string: "https://www.youtube.com/iframe_api") else { return nil }
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await session.data(for: request), (response as? HTTPURLResponse)?.statusCode == 200,
              let text = String(data: data, encoding: .utf8)?.replacingOccurrences(of: "\\/", with: "/"),
              let playerID = text.firstMatch(#"/s/player/([a-zA-Z0-9_-]+)/"#) else { return nil }
        guard let jsURL = URL(string: "https://www.youtube.com/s/player/\(playerID)/player_ias.vflset/en_US/base.js") else { return nil }
        var jsRequest = URLRequest(url: jsURL)
        jsRequest.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        guard let (jsData, jsResponse) = try? await session.data(for: jsRequest), (jsResponse as? HTTPURLResponse)?.statusCode == 200,
              let js = String(data: jsData, encoding: .utf8),
              let value = js.firstMatch(#"signatureTimestamp:(\d+)"#), let timestamp = Int64(value) else { return nil }
        cachedSignatureTimestamp = (timestamp, Date().addingTimeInterval(6 * 60 * 60))
        return timestamp
    }

    private func resolveCipher(_ cipher: String, root: [String: Any]) async throws -> String? {
        var values: [String: String] = [:]
        for part in cipher.split(separator: "&") {
            let pieces = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = String(pieces[0]).removingPercentEncoding ?? String(pieces[0])
            let value = pieces.count > 1 ? (String(pieces[1]).removingPercentEncoding ?? String(pieces[1])) : ""
            values[key] = value
        }
        guard let baseURL = values["url"], !baseURL.isEmpty else { return nil }
        guard let signature = values["s"], !signature.isEmpty else { return baseURL }
        guard let playerURL = nonEmpty((root["assets"] as? [String: Any])?["js"]) ?? nonEmpty(root["playerUrl"]) else {
            return appendQuery(baseURL, name: values["sp"] ?? "sig", value: signature)
        }
        let deciphered = try await decipher(signature, playerURL: playerURL)
        return appendQuery(baseURL, name: values["sp"] ?? "sig", value: deciphered)
    }

    private func decipher(_ signature: String, playerURL: String) async throws -> String {
        let code: String
        if let cached = scriptCache[playerURL] {
            code = cached
        } else {
            let absolute = playerURL.hasPrefix("//") ? "https:\(playerURL)" : (playerURL.hasPrefix("/") ? "https://www.youtube.com\(playerURL)" : playerURL)
            guard let url = URL(string: absolute) else { throw ExtractionFailure.invalid("Invalid player JavaScript URL") }
            var request = URLRequest(url: url)
            request.setValue(Self.profiles[0].userAgent, forHTTPHeaderField: "User-Agent")
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200, let source = String(data: data, encoding: .utf8) else {
                throw ExtractionFailure.invalid("Unable to download YouTube player JavaScript")
            }
            code = source
            scriptCache[playerURL] = source
            if scriptCache.count > 4, let first = scriptCache.keys.first { scriptCache.removeValue(forKey: first) }
        }
        guard let function = signatureFunction(in: code) else { throw ExtractionFailure.invalid("YouTube signature function was not found") }
        let operations = signatureOperations(in: function.body, parameter: function.parameter, source: code)
        guard !operations.isEmpty else { throw ExtractionFailure.invalid("YouTube signature function was empty") }
        var characters = Array(signature)
        for operation in operations { operation.apply(to: &characters) }
        return String(characters)
    }

    private func signatureFunction(in code: String) -> (parameter: String, body: String)? {
        let patterns = [
            #"(?s)(?:^|[,;])\s*(?:var\s+)?[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*function\s*\(\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\)\s*\{(.*?)\}\s*[,;]"#,
            #"(?s)function\s+[A-Za-z_$][A-Za-z0-9_$]*\s*\(\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\)\s*\{(.*?)\}"#,
        ]
        for pattern in patterns {
            for match in allMatches(pattern, in: code) where match.count > 2 {
                let parameter = match[1]
                let body = match[2]
                if body.contains("split(\"\")") || body.contains("split('')"), body.contains("join(\"\")") || body.contains("join('')") {
                    return (parameter, body)
                }
            }
        }
        return nil
    }

    private func signatureOperations(in body: String, parameter: String, source: String) -> [CipherOperation] {
        let escaped = NSRegularExpression.escapedPattern(for: parameter)
        var found: [(Int, CipherOperation)] = []
        for match in locatedMatches("\(escaped)\\.reverse\\(\\)", in: body) { found.append((match.location, .reverse)) }
        for match in locatedMatches("\(escaped)\\.splice\\(\\s*0\\s*,\\s*(\\d+)\\s*\\)", in: body) {
            if let count = match.captures[safe: 1].flatMap(Int.init) { found.append((match.location, .drop(count))) }
        }
        for match in locatedMatches("\(escaped)\\s*=\\s*\(escaped)\\.slice\\(\\s*(\\d+)\\s*\\)", in: body) {
            if let count = match.captures[safe: 1].flatMap(Int.init) { found.append((match.location, .slice(count))) }
        }
        for match in locatedMatches("\(escaped)\\[(\\d+)\\]\\s*=\\s*\(escaped)\\[(\\d+)%\(escaped)\\.length\\]", in: body) {
            if let first = match.captures[safe: 1].flatMap(Int.init), let second = match.captures[safe: 2].flatMap(Int.init) {
                found.append((match.location, .swap(first, second)))
            }
        }
        let helper = #"([A-Za-z_$][A-Za-z0-9_$]*)\.([A-Za-z_$][A-Za-z0-9_$]*)\(\s*"# + escaped + #"(?:\s*,\s*(\d+))?\s*\)"#
        for match in locatedMatches(helper, in: body) where match.captures.count > 2 {
            guard let operation = helperOperation(source, object: match.captures[1], method: match.captures[2], argument: match.captures[safe: 3]) else { continue }
            found.append((match.location, operation))
        }
        return found.sorted { $0.0 < $1.0 }.map(\.1)
    }

    private func helperOperation(_ source: String, object: String, method: String, argument: String?) -> CipherOperation? {
        let objectPattern = #"(?s)(?:var\s+|let\s+|const\s+)?"# + NSRegularExpression.escapedPattern(for: object) + #"\s*=\s*\{(.*?)\}"#
        guard let objectBody = firstMatch(objectPattern, in: source) else { return nil }
        let methodPattern = #"(?s)(?:^|,)\s*"# + NSRegularExpression.escapedPattern(for: method) + #"\s*:\s*function\s*\([^)]*\)\s*\{(.*?)\}"#
        guard let methodBody = firstMatch(methodPattern, in: objectBody) else { return nil }
        if methodBody.contains(".reverse()") { return .reverse }
        if methodBody.contains(".splice(0") { return .drop(Int(argument ?? "0") ?? 0) }
        if methodBody.contains(".slice(") { return .slice(Int(argument ?? "0") ?? 0) }
        return nil
    }

    private func isTemporarilyExcluded(_ profile: Profile, options: YoutubeExtractStreamOptions, videoID: String) -> Bool {
        let key = "\(videoID)|\(profile.name)|\(cookieFingerprint(options.cookie))|\(options.visitorData ?? "")"
        guard let expiresAt = failedClients[key] else { return false }
        if expiresAt > Date() { return true }
        failedClients.removeValue(forKey: key)
        return false
    }

    private func store(_ playback: YoutubePlaybackData, key: String) {
        playbackCache[key] = CacheEntry(value: playback, expiresAt: Date().addingTimeInterval(TimeInterval(playback.streamExpiresInSeconds)))
        playbackCacheOrder.removeAll { $0 == key }
        playbackCacheOrder.append(key)
        while playbackCacheOrder.count > 32 {
            let oldest = playbackCacheOrder.removeFirst()
            playbackCache.removeValue(forKey: oldest)
        }
    }

    private func normalizeCookie(_ cookie: String?) -> String? {
        guard let cookie else { return nil }
        let pairs = cookie.split(separator: ";").compactMap { part -> String? in
            let value = part.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let separator = value.firstIndex(of: "="), separator != value.startIndex else { return nil }
            return value
        }
        return pairs.isEmpty ? nil : pairs.joined(separator: "; ")
    }

    private func sapisid(from cookie: String) -> String? {
        for pair in cookie.split(separator: ";") {
            let pieces = pair.trimmingCharacters(in: .whitespaces).split(separator: "=", maxSplits: 1)
            if pieces.count == 2, ["SAPISID", "__Secure-3PAPISID"].contains(String(pieces[0])) { return String(pieces[1]) }
        }
        return nil
    }

    private func cookieFingerprint(_ cookie: String?) -> String {
        guard let cookie = normalizeCookie(cookie) else { return "none" }
        return SHA256.hash(data: Data(cookie.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func appendQuery(_ string: String, name: String, value: String) -> String {
        guard var components = URLComponents(string: string) else { return string }
        components.queryItems = components.queryItems.orEmpty + [URLQueryItem(name: name, value: value)]
        return components.string ?? string
    }

    private struct LocatedMatch {
        let location: Int
        let captures: [String]
    }

    private func locatedMatches(_ pattern: String, in string: String) -> [LocatedMatch] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let nsString = string as NSString
        return regex.matches(in: string, range: NSRange(location: 0, length: nsString.length)).map { match in
            let captures = (0..<match.numberOfRanges).map { index in
                let range = match.range(at: index)
                return range.location == NSNotFound ? "" : nsString.substring(with: range)
            }
            return LocatedMatch(location: match.range.location, captures: captures)
        }
    }

    private func allMatches(_ pattern: String, in string: String) -> [[String]] {
        locatedMatches(pattern, in: string).map(\.captures)
    }

    private func firstMatch(_ pattern: String, in string: String) -> String? { allMatches(pattern, in: string).first?[safe: 1] }
    private func number(_ value: Any?) -> Double? { (value as? NSNumber)?.doubleValue ?? (value as? String).flatMap(Double.init) }
    private func integer(_ value: Any?) -> Int? { (value as? NSNumber)?.intValue ?? (value as? String).flatMap(Int.init) }
    private func nonEmpty(_ value: Any?) -> String? { (value as? String)?.nonEmpty }

    private static let rangeChunkSize: Int64 = 10 * 1024 * 1024
    private static func isValidVideoID(_ value: String) -> Bool {
        let bytes = value.utf8
        guard bytes.count == 11 else { return false }
        return bytes.allSatisfy { byte in
            (byte >= 48 && byte <= 57) ||
                (byte >= 65 && byte <= 90) ||
                (byte >= 97 && byte <= 122) ||
                byte == 95 || byte == 45
        }
    }
    private static let profiles: [Profile] = {
        let webUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        return [
            Profile(name: "VISIONOS", clientName: "VISIONOS", version: "1.02", id: "101", userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15", osName: "visionOS", osVersion: "26.5.23O471", deviceMake: "Apple", deviceModel: "RealityDevice17,1", androidSDK: nil, platform: nil, musicEndpoint: true, mobileWeb: false, loginSupported: false, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: false, includeUserAgentInContext: false),
            Profile(name: "VISIONOS_0_1", clientName: "VISIONOS", version: "0.1", id: "101", userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15", osName: "VISION_OS", osVersion: "1.3", deviceMake: "Apple", deviceModel: "RealityDevice14,1", androidSDK: nil, platform: "MOBILE", musicEndpoint: true, mobileWeb: false, loginSupported: false, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: true, includeUserAgentInContext: false),
            Profile(name: "ANDROID_VR_1_65_10", clientName: "ANDROID_VR", version: "1.65.10", id: "28", userAgent: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip", osName: "Android", osVersion: "12L", deviceMake: "Oculus", deviceModel: "Quest 3", androidSDK: "32", platform: nil, musicEndpoint: true, mobileWeb: false, loginSupported: false, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "ANDROID_VR_1_61_48", clientName: "ANDROID_VR", version: "1.61.48", id: "28", userAgent: "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)", osName: "Android", osVersion: "12", deviceMake: "Oculus", deviceModel: "Quest 3", androidSDK: "32", platform: nil, musicEndpoint: true, mobileWeb: false, loginSupported: false, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "ANDROID_VR_1_43_32", clientName: "ANDROID_VR", version: "1.43.32", id: "28", userAgent: "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 10; en_US; Oculus Quest 2; Build/QP1A.190711.020; Cronet/107.0.5284.2)", osName: "Android", osVersion: "10", deviceMake: "Oculus", deviceModel: "Quest 2", androidSDK: "29", platform: nil, musicEndpoint: true, mobileWeb: false, loginSupported: false, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "ANDROID", clientName: "ANDROID", version: "21.26.364", id: "3", userAgent: "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip", osName: "Android", osVersion: "11", deviceMake: nil, deviceModel: nil, androidSDK: "30", platform: nil, musicEndpoint: false, mobileWeb: false, loginSupported: false, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "IOS", clientName: "IOS", version: "21.26.4", id: "5", userAgent: "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)", osName: "iOS", osVersion: "17.5.1.21F90", deviceMake: "Apple", deviceModel: "iPhone16,2", androidSDK: nil, platform: "MOBILE", musicEndpoint: false, mobileWeb: false, loginSupported: true, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: false, includeUserAgentInContext: false),
            Profile(name: "IOS_IPADOS", clientName: "IOS", version: "21.26.4", id: "5", userAgent: "com.google.ios.youtube/21.26.4 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)", osName: "iPadOS", osVersion: "17.7.10.21H450", deviceMake: "Apple", deviceModel: "iPad7,6", androidSDK: nil, platform: "TABLET", musicEndpoint: false, mobileWeb: false, loginSupported: true, useSignatureTimestamp: false, useWebPoTokens: false, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "TVHTML5_SIMPLY", clientName: "TVHTML5_SIMPLY", version: "1.0", id: "75", userAgent: "Mozilla/5.0 (Linux armv7l; Android 12) Cobalt/25.lts.30.1034943-gold (unlike Gecko) v8/8.8.278.8-bstar gles Starboard/15, sabrina_TV_cord_release/STTE.231215.005 (Google, Chromecast HD, Wireless)", osName: "Android", osVersion: "12", deviceMake: "Google", deviceModel: "Chromecast HD", androidSDK: nil, platform: "TV", musicEndpoint: false, mobileWeb: false, loginSupported: false, useSignatureTimestamp: true, useWebPoTokens: true, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "TVHTML5_EMBEDDED", clientName: "TVHTML5_SIMPLY", version: "1.0", id: "75", userAgent: "Mozilla/5.0 (Linux armv7l; Android 12) Cobalt/25.lts.30.1034943-gold (unlike Gecko) v8/8.8.278.8-bstar gles Starboard/15, sabrina_TV_cord_release/STTE.231215.005 (Google, Chromecast HD, Wireless)", osName: "Android", osVersion: "12", deviceMake: "Google", deviceModel: "Chromecast HD", androidSDK: nil, platform: "TV", musicEndpoint: false, mobileWeb: false, loginSupported: false, useSignatureTimestamp: true, useWebPoTokens: true, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "MWEB", clientName: "MWEB", version: "2.20260708.05.00", id: "2", userAgent: "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)", osName: nil, osVersion: nil, deviceMake: nil, deviceModel: nil, androidSDK: nil, platform: nil, musicEndpoint: false, mobileWeb: true, loginSupported: true, useSignatureTimestamp: true, useWebPoTokens: true, skipPlayabilityValidation: false, includeUserAgentInContext: true),
            Profile(name: "WEB", clientName: "WEB", version: "2.20260708.00.00", id: "1", userAgent: webUA, osName: nil, osVersion: nil, deviceMake: nil, deviceModel: nil, androidSDK: nil, platform: nil, musicEndpoint: false, mobileWeb: false, loginSupported: true, useSignatureTimestamp: true, useWebPoTokens: true, skipPlayabilityValidation: false, includeUserAgentInContext: false),
            Profile(name: "WEB_REMIX", clientName: "WEB_REMIX", version: "1.20260707.12.00", id: "67", userAgent: webUA, osName: nil, osVersion: nil, deviceMake: nil, deviceModel: nil, androidSDK: nil, platform: nil, musicEndpoint: true, mobileWeb: false, loginSupported: true, useSignatureTimestamp: true, useWebPoTokens: true, skipPlayabilityValidation: false, includeUserAgentInContext: false),
        ]
    }()
}

@MainActor
private final class YoutubeBotGuardTokenProvider: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    struct Tokens {
        let playerRequest: String
        let streamingData: String
    }

    private var webView: WKWebView?
    private var continuation: CheckedContinuation<Tokens, Error>?
    private var timeoutTask: Task<Void, Never>?
    private var visitorData = ""
    private var videoID = ""

    static func generate(videoID: String, visitorData: String, cookie: String?) async throws -> Tokens {
        let runner = YoutubeBotGuardTokenProvider()
        return try await runner.start(videoID: videoID, visitorData: visitorData, cookie: cookie)
    }

    private func start(videoID: String, visitorData: String, cookie: String?) async throws -> Tokens {
        self.videoID = videoID
        self.visitorData = visitorData
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(self, name: "nexaBotGuard")
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        webView = view
        if let cookie { await installCookies(cookie, into: configuration.websiteDataStore.httpCookieStore) }
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            timeoutTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 45_000_000_000)
                self?.finish(.failure(YoutubeDownloaderError.tokenUnavailable(message: "BotGuard token generation timed out")))
            }
            view.loadHTMLString(Self.page, baseURL: URL(string: "https://www.youtube.com"))
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let visitor = Self.javascriptString(visitorData)
        let video = Self.javascriptString(videoID)
        let script = Self.tokenScript
            .replacingOccurrences(of: "__VISITOR_DATA__", with: visitor)
            .replacingOccurrences(of: "__VIDEO_ID__", with: video)
        webView.evaluateJavaScript(script) { _, error in
            if let error {
                Task { @MainActor in self.finish(.failure(error)) }
            }
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let result = message.body as? [String: String] else {
            finish(.failure(YoutubeDownloaderError.tokenUnavailable(message: "Invalid BotGuard response")))
            return
        }
        if let error = result["error"] {
            finish(.failure(YoutubeDownloaderError.tokenUnavailable(message: error)))
            return
        }
        guard let player = result["playerRequest"], let stream = result["streamingData"] else {
            finish(.failure(YoutubeDownloaderError.tokenUnavailable(message: "BotGuard returned no token")))
            return
        }
        finish(.success(Tokens(playerRequest: player, streamingData: stream)))
    }

    private func installCookies(_ rawCookie: String, into store: WKHTTPCookieStore) async {
        let pieces = rawCookie.split(separator: ";").compactMap { pair -> HTTPCookie? in
            let fields = pair.trimmingCharacters(in: .whitespaces).split(separator: "=", maxSplits: 1)
            guard fields.count == 2 else { return nil }
            return HTTPCookie(properties: [
                .domain: ".youtube.com",
                .path: "/",
                .name: String(fields[0]),
                .value: String(fields[1]),
                .secure: "TRUE",
            ])
        }
        for cookie in pieces {
            await withCheckedContinuation { continuation in
                store.setCookie(cookie) { continuation.resume() }
            }
        }
    }

    private func finish(_ result: Result<Tokens, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        timeoutTask?.cancel()
        timeoutTask = nil
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "nexaBotGuard")
        webView = nil
        continuation.resume(with: result)
    }

    private static func javascriptString(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [value]),
              let encoded = String(data: data, encoding: .utf8),
              encoded.count >= 2 else { return "\"\"" }
        return String(encoded.dropFirst().dropLast())
    }

    private static let page = #"""
    <!DOCTYPE html><html><head><meta charset="utf-8"><script>
    function loadBotGuard(challengeData) {
      this.vm = this[challengeData.globalName]; this.program = challengeData.program; this.vmFunctions = {};
      if (!this.vm || !this.vm.a) throw new Error('BotGuard VM unavailable');
      var owner = this; this.vm.a(this.program, function(a,b,c,d){owner.vmFunctions={asyncSnapshotFunction:a,shutdownFunction:b,passEventFunction:c,checkCameraFunction:d};}, true, this.userInteractionElement, function(){}, [[],[]]);
      return new Promise(function(resolve,reject){var i=0;var timer=setInterval(function(){if(owner.vmFunctions.asyncSnapshotFunction){clearInterval(timer);resolve(owner);}else if(i++>=10000){clearInterval(timer);reject('BotGuard VM timed out');}},1);});
    }
    function snapshot(args){return new Promise(function(resolve,reject){if(!this.vmFunctions.asyncSnapshotFunction)return reject(new Error('BotGuard snapshot is unavailable'));this.vmFunctions.asyncSnapshotFunction(function(value){resolve(value);},[args.contentBinding,args.signedTimestamp,args.webPoSignalOutput,args.skipPrivacyBuffer]);}.bind(this));}
    function runBotGuard(data){var code=data.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;if(!code)throw new Error('BotGuard interpreter is unavailable');new Function(code)();var webPoSignalOutput=[];return loadBotGuard({globalName:data.globalName,program:data.program}).then(function(guard){return snapshot.call(guard,{webPoSignalOutput:webPoSignalOutput});}).then(function(response){return{webPoSignalOutput:webPoSignalOutput,botguardResponse:response};});}
    function obtainPoToken(output,integrity,identifier){var minter=output[0];if(!minter)throw new Error('BotGuard minter is unavailable');var mint=minter(integrity);if(typeof mint!=='function')throw new Error('BotGuard token function is unavailable');var token=mint(identifier);if(!(token instanceof Uint8Array))throw new Error('BotGuard token is invalid');return token;}
    </script></head><body></body></html>
    """#

    private static let tokenScript = #"""
    (async function(){
      const key='O43z0dpjhgX20SCx4KAo';
      const headers={'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36','Accept':'application/json','Content-Type':'application/json+protobuf','x-goog-api-key':'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw','x-user-agent':'grpc-web-javascript/0.1'};
      const post=async(url,body)=>{const response=await fetch(url,{method:'POST',headers,body,credentials:'include'});if(!response.ok)throw new Error('BotGuard request failed ('+response.status+')');return await response.text();};
      const b64bytes=(value)=>{let normalized=value.replace(/-/g,'+').replace(/_/g,'/').replace(/\./g,'=');while(normalized.length%4)normalized+='=';return Uint8Array.from(atob(normalized),c=>c.charCodeAt(0));};
      const parseChallenge=(raw)=>{const scrambled=JSON.parse(raw);let challenge;if(scrambled.length>1&&typeof scrambled[1]==='string'){const bytes=b64bytes(scrambled[1]);challenge=JSON.parse(new TextDecoder().decode(Uint8Array.from(bytes,b=>b+97)));}else{challenge=scrambled[0];}const first=(a)=>Array.isArray(a)?a.find(x=>typeof x==='string'):undefined;return{messageId:challenge[0],interpreterJavascript:{privateDoNotAccessOrElseSafeScriptWrappedValue:first(challenge[1]),privateDoNotAccessOrElseTrustedResourceUrlWrappedValue:first(challenge[2])},interpreterHash:challenge[3],program:challenge[4],globalName:challenge[5],clientExperimentsStateBlob:challenge[7]};};
      const toURL64=(bytes)=>{let binary='';for(const value of bytes)binary+=String.fromCharCode(value);return btoa(binary).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');};
      try{const challenge=parseChallenge(await post('https://www.youtube.com/api/jnn/v1/Create',JSON.stringify([key])));const response=await runBotGuard(challenge);const tokenData=JSON.parse(await post('https://www.youtube.com/api/jnn/v1/GenerateIT',JSON.stringify([key,response.botguardResponse])));const integrity=b64bytes(tokenData[0]);const encoder=new TextEncoder();const streaming=obtainPoToken(response.webPoSignalOutput,integrity,encoder.encode(__VISITOR_DATA__));const player=obtainPoToken(response.webPoSignalOutput,integrity,encoder.encode(__VIDEO_ID__));window.webkit.messageHandlers.nexaBotGuard.postMessage({playerRequest:toURL64(player),streamingData:toURL64(streaming)});}catch(error){window.webkit.messageHandlers.nexaBotGuard.postMessage({error:String(error)});}
    })();
    """#
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
    func firstMatch(_ pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: self, range: NSRange(startIndex..., in: self)), match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: self) else { return nil }
        return String(self[range])
    }
}

private extension Optional where Wrapped == [URLQueryItem] {
    var orEmpty: [URLQueryItem] { self ?? [] }
}

private extension Array where Element == String {
    subscript(safe index: Int) -> String? { indices.contains(index) ? self[index] : nil }
}
