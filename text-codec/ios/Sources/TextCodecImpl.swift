import Foundation

public final class TextCodecImpl: TextCodecSpec {
    public required init() {}

    public func decodeBase64Utf8(_ encoded: String) -> String? {
        let normalized = encoded.components(separatedBy: .whitespacesAndNewlines).joined()
        guard let data = Data(base64Encoded: normalized) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }
}
