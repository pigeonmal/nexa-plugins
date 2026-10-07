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
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
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

    func removeFromCache() throws {
        try FileManager.default.removeItem(at: url)
    }
}

/// Presents Apple's permissionless photo and video picker and returns a local file URI.
public struct MediaPickerControlImpl<Content: View>: View {
    public let isVideo: Bool
    public let selectionLimit: Int32
    public let onPicked: (([String]) -> Void)?
    public let onFailed: ((String) -> Void)?
    public let content: Content

    @State private var isPresented = false
    @State private var isImporting = false
    @State private var selection: [PhotosPickerItem] = []
    @State private var importTask: Task<Void, Never>?

    public init(
        isVideo: Bool,
        selectionLimit: Int32,
        onPicked: (([String]) -> Void)?,
        onFailed: ((String) -> Void)?,
        content: Content
    ) {
        self.isVideo = isVideo
        self.selectionLimit = selectionLimit
        self.onPicked = onPicked
        self.onFailed = onFailed
        self.content = content
    }

    public var body: some View {
        Button {
            guard selectionLimit >= 0 else {
                onFailed?("selectionLimit must be zero or greater.")
                return
            }
            isPresented = true
        } label: {
            content
        }
        .buttonStyle(.plain)
        .photosPicker(
            isPresented: $isPresented,
            selection: $selection,
            maxSelectionCount: selectionLimit > 0 ? Int(selectionLimit) : nil,
            matching: isVideo ? .videos : .images,
            preferredItemEncoding: .current
        )
        .onChange(of: selection) { items in
            guard !items.isEmpty else { return }
            importTask?.cancel()
            isImporting = true
            importTask = Task { await importSelection(items) }
        }
        .onDisappear {
            importTask?.cancel()
        }
        .disabled(isImporting)
    }

    @MainActor
    private func importSelection(_ items: [PhotosPickerItem]) async {
        var imported: [PickedMedia] = []
        do {
            imported.reserveCapacity(items.count)
            for item in items {
                try Task.checkCancellation()
                guard let media = try await item.loadTransferable(type: PickedMedia.self) else {
                    throw MediaPickerFailure.unreadableSelection
                }
                imported.append(media)
            }
            try Task.checkCancellation()
            onPicked?(imported.map { $0.url.absoluteString })
        } catch {
            for media in imported {
                try? media.removeFromCache()
            }
            if !Task.isCancelled {
                onFailed?(error.localizedDescription)
            }
        }
        selection = []
        isImporting = false
        importTask = nil
    }
}

private enum MediaPickerFailure: LocalizedError {
    case unreadableSelection

    var errorDescription: String? {
        "The selected media could not be read."
    }
}
