import Foundation

private actor DataExtractorWorker {
    static let shared = DataExtractorWorker()

    private let detector: NSDataDetector?

    private init() {
        let types = NSTextCheckingResult.CheckingType.date.rawValue
            | NSTextCheckingResult.CheckingType.address.rawValue
            | NSTextCheckingResult.CheckingType.link.rawValue
            | NSTextCheckingResult.CheckingType.phoneNumber.rawValue
        detector = try? NSDataDetector(types: types)
    }

    func extract(_ text: String) -> [ExtractedData] {
        guard !Task.isCancelled, !text.isEmpty, let detector else { return [] }

        let sourceRange = NSRange(text.startIndex..., in: text)
        let source = text as NSString
        return detector.matches(in: text, range: sourceRange).compactMap { match in
            guard let kind = Self.kind(for: match) else { return nil }
            let timestampMillis: Int64?

            if match.resultType == .date, let date = match.date {
                timestampMillis = Int64((date.timeIntervalSince1970 * 1_000).rounded())
            } else {
                timestampMillis = nil
            }

            guard let start = Int32(exactly: match.range.location),
                  let length = Int32(exactly: match.range.length) else { return nil }

            return ExtractedData(
                kind: kind,
                text: source.substring(with: match.range),
                start: start,
                length: length,
                timestampMillis: timestampMillis,
                hasTime: false,
                timePrecisionAvailable: false
            )
        }
    }

    private static func kind(for match: NSTextCheckingResult) -> ExtractedDataKind? {
        switch match.resultType {
        case .date:
            return match.date == nil ? nil : .date
        case .phoneNumber:
            return .phoneNumber
        case .address:
            return .address
        case .link:
            return match.url?.scheme?.lowercased() == "mailto" ? .email : .url
        default:
            return nil
        }
    }
}

@MainActor
public final class DataExtractorImpl: DataExtractorSpec {
    public init() {}

    public func extract(_ text: String) async -> [ExtractedData] {
        guard !Task.isCancelled else { return [] }
        let matches = await DataExtractorWorker.shared.extract(text)
        guard !Task.isCancelled else { return [] }
        return matches
    }
}

// These contract values contain only scalar, string, and optional scalar fields.
// The generated contract is a separate source file, so Swift requires an
// explicitly unchecked retroactive conformance here for actor return values.
extension ExtractedDataKind: @unchecked Sendable {}
extension ExtractedData: @unchecked Sendable {}
