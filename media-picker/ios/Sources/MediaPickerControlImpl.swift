import Foundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

private struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("NexaMediaPicker", isDirectory: true)
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )
            let fileExtension = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let destination = directory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(fileExtension)
            try FileManager.default.copyItem(at: received.file, to: destination)
            return PickedMovie(url: destination)
        }
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
        .onChange(of: selection) { _, item in
            guard let item else { return }
            Task { await importSelection(item) }
        }
    }

    @MainActor
    private func importSelection(_ item: PhotosPickerItem) async {
        do {
            if isVideo {
                guard let movie = try await item.loadTransferable(type: PickedMovie.self) else {
                    throw MediaPickerFailure.unreadableSelection
                }
                onPicked?(movie.url.absoluteString)
            } else {
                guard let data = try await item.loadTransferable(type: Data.self) else {
                    throw MediaPickerFailure.unreadableSelection
                }
                let fileExtension = item.supportedContentTypes.first?.preferredFilenameExtension ?? "img"
                let url = try cacheDirectory()
                    .appendingPathComponent(UUID().uuidString)
                    .appendingPathExtension(fileExtension)
                try data.write(to: url, options: .atomic)
                onPicked?(url.absoluteString)
            }
        } catch {
            onFailed?(error.localizedDescription)
        }
        selection = nil
    }

    private func cacheDirectory() throws -> URL {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NexaMediaPicker", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory
    }
}

private enum MediaPickerFailure: LocalizedError {
    case unreadableSelection

    var errorDescription: String? {
        "The selected media could not be read."
    }
}
