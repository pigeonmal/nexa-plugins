import Foundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

private struct PickedMedia: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            try importFile(received.file)
        }
        FileRepresentation(importedContentType: .movie) { received in
            try importFile(received.file)
        }
    }

    private static func importFile(_ source: URL) throws -> PickedMedia {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NexaMediaPicker", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        let fileExtension = source.pathExtension.isEmpty ? "media" : source.pathExtension
        let destination = directory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(fileExtension)
        try FileManager.default.copyItem(at: source, to: destination)
        return PickedMedia(url: destination)
    }
}

/// Presents Apple's permissionless photo and video picker and returns a local file URI.
public struct MediaPickerControlImpl<Content: View>: View {
    public let isVideo: Bool
    public let onPicked: ((String) -> Void)?
    public let onFailed: ((String) -> Void)?
    public let content: Content

    @State private var isPresented = false
    @State private var selection: PhotosPickerItem?

    public init(
        isVideo: Bool,
        onPicked: ((String) -> Void)?,
        onFailed: ((String) -> Void)?,
        content: Content
    ) {
        self.isVideo = isVideo
        self.onPicked = onPicked
        self.onFailed = onFailed
        self.content = content
    }

    public var body: some View {
        Button {
            isPresented = true
        } label: {
            content
        }
        .buttonStyle(.plain)
        .photosPicker(
            isPresented: $isPresented,
            selection: $selection,
            matching: isVideo ? .videos : .images
        )
        .onChange(of: selection) { item in
            guard let item else { return }
            Task { await importSelection(item) }
        }
    }

    @MainActor
    private func importSelection(_ item: PhotosPickerItem) async {
        do {
            guard let media = try await item.loadTransferable(type: PickedMedia.self) else {
                throw MediaPickerFailure.unreadableSelection
            }
            onPicked?(media.url.absoluteString)
        } catch {
            onFailed?(error.localizedDescription)
        }
        selection = nil
    }
}

private enum MediaPickerFailure: LocalizedError {
    case unreadableSelection

    var errorDescription: String? {
        "The selected media could not be read."
    }
}
