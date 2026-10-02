@preconcurrency import AVFoundation
import Foundation
import SwiftUI
import UIKit

@MainActor
public struct CameraViewImpl: View {
    public let facing: CameraFacing
    public let photoRequestId: Int32
    public let recording: Bool
    public let recordAudio: Bool
    public let imageStreamEnabled: Bool
    public let barcodeScanningEnabled: Bool
    public let barcodeFormat: CameraBarcodeFormat
    public let frameResolution: CameraFrameResolution
    public let maxFramesPerSecond: Int32
    public let onPhotoCaptured: ((String) -> Void)?
    public let onVideoCaptured: ((String) -> Void)?
    public let onBarcodeDetected: ((CameraBarcode) -> Void)?
    public let onFrameAvailable: ((CameraFrame) -> Void)?
    public let onFailed: ((CameraError) -> Void)?

    @StateObject private var coordinator: CameraViewCoordinator

    public init(
        facing: CameraFacing,
        photoRequestId: Int32,
        recording: Bool,
        recordAudio: Bool,
        imageStreamEnabled: Bool,
        barcodeScanningEnabled: Bool,
        barcodeFormat: CameraBarcodeFormat,
        frameResolution: CameraFrameResolution,
        maxFramesPerSecond: Int32,
        onPhotoCaptured: ((String) -> Void)?,
        onVideoCaptured: ((String) -> Void)?,
        onBarcodeDetected: ((CameraBarcode) -> Void)?,
        onFrameAvailable: ((CameraFrame) -> Void)?,
        onFailed: ((CameraError) -> Void)?
    ) {
        self.facing = facing
        self.photoRequestId = photoRequestId
        self.recording = recording
        self.recordAudio = recordAudio
        self.imageStreamEnabled = imageStreamEnabled
        self.barcodeScanningEnabled = barcodeScanningEnabled
        self.barcodeFormat = barcodeFormat
        self.frameResolution = frameResolution
        self.maxFramesPerSecond = maxFramesPerSecond
        self.onPhotoCaptured = onPhotoCaptured
        self.onVideoCaptured = onVideoCaptured
        self.onBarcodeDetected = onBarcodeDetected
        self.onFrameAvailable = onFrameAvailable
        self.onFailed = onFailed
        _coordinator = StateObject(wrappedValue: CameraViewCoordinator())
    }

    private var configuration: CameraConfiguration {
        CameraConfiguration(
            facing: facing,
            recording: recording,
            recordAudio: recordAudio,
            imageStreamEnabled: imageStreamEnabled,
            barcodeScanningEnabled: barcodeScanningEnabled,
            barcodeFormat: barcodeFormat,
            frameResolution: frameResolution,
            maxFramesPerSecond: maxFramesPerSecond
        )
    }

    public var body: some View {
        CameraPreviewSurface(
            coordinator: coordinator,
            configuration: configuration,
            photoRequestId: photoRequestId,
            onPhotoCaptured: onPhotoCaptured,
            onVideoCaptured: onVideoCaptured,
            onBarcodeDetected: onBarcodeDetected,
            onFrameAvailable: onFrameAvailable,
            onFailed: onFailed
        )
            .aspectRatio(4.0 / 3.0, contentMode: .fit)
            .onDisappear { coordinator.stop() }
    }
}

private struct CameraConfiguration: Equatable, @unchecked Sendable {
    let facing: CameraFacing
    let recording: Bool
    let recordAudio: Bool
    let imageStreamEnabled: Bool
    let barcodeScanningEnabled: Bool
    let barcodeFormat: CameraBarcodeFormat
    let frameResolution: CameraFrameResolution
    let maxFramesPerSecond: Int32
}

@MainActor
private final class CameraViewCoordinator: ObservableObject {
    private let engine = CameraCaptureEngine()
    var session: AVCaptureSession { engine.session }

    func updateCallbacks(
        photo: ((String) -> Void)?,
        video: ((String) -> Void)?,
        barcode: ((CameraBarcode) -> Void)?,
        frame: ((CameraFrame) -> Void)?,
        failure: ((CameraError) -> Void)?
    ) {
        engine.updateCallbacks(
            photo: photo,
            video: video,
            barcode: barcode,
            frame: frame,
            failure: failure
        )
    }

    func update(configuration: CameraConfiguration, photoRequestId: Int32) {
        engine.update(configuration: configuration, photoRequestId: photoRequestId)
    }

    func requestPhoto(_ photoRequestId: Int32) {
        engine.requestPhoto(photoRequestId)
    }

    func stop() {
        engine.stop()
    }
}

private struct CameraPreviewSurface: UIViewRepresentable {
    let coordinator: CameraViewCoordinator
    let configuration: CameraConfiguration
    let photoRequestId: Int32
    let onPhotoCaptured: ((String) -> Void)?
    let onVideoCaptured: ((String) -> Void)?
    let onBarcodeDetected: ((CameraBarcode) -> Void)?
    let onFrameAvailable: ((CameraFrame) -> Void)?
    let onFailed: ((CameraError) -> Void)?

    func makeUIView(context: Context) -> CameraPreviewLayerView {
        let view = CameraPreviewLayerView()
        view.previewLayer.session = coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        update(view)
        return view
    }

    func updateUIView(_ view: CameraPreviewLayerView, context: Context) {
        if view.previewLayer.session !== coordinator.session {
            view.previewLayer.session = coordinator.session
        }
        update(view)
    }

    static func dismantleUIView(_ view: CameraPreviewLayerView, coordinator: ()) {
        view.previewLayer.session = nil
    }

    private func update(_ view: CameraPreviewLayerView) {
        coordinator.updateCallbacks(
            photo: onPhotoCaptured,
            video: onVideoCaptured,
            barcode: onBarcodeDetected,
            frame: onFrameAvailable,
            failure: onFailed
        )
        coordinator.update(configuration: configuration, photoRequestId: photoRequestId)
    }
}

private final class CameraPreviewLayerView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        guard let layer = layer as? AVCaptureVideoPreviewLayer else {
            preconditionFailure("CameraPreviewLayerView must use AVCaptureVideoPreviewLayer")
        }
        return layer
    }
}

private final class CameraCallbackInvocation<Value>: @unchecked Sendable {
    let value: Value
    let callback: ((Value) -> Void)?

    init(value: Value, callback: ((Value) -> Void)?) {
        self.value = value
        self.callback = callback
    }
}

private final class CameraCallbackBox: @unchecked Sendable {
    private let lock = NSLock()
    private var photo: ((String) -> Void)?
    private var video: ((String) -> Void)?
    private var barcode: ((CameraBarcode) -> Void)?
    private var frame: ((CameraFrame) -> Void)?
    private var failure: ((CameraError) -> Void)?

    func update(
        photo: ((String) -> Void)?,
        video: ((String) -> Void)?,
        barcode: ((CameraBarcode) -> Void)?,
        frame: ((CameraFrame) -> Void)?,
        failure: ((CameraError) -> Void)?
    ) {
        lock.lock()
        self.photo = photo
        self.video = video
        self.barcode = barcode
        self.frame = frame
        self.failure = failure
        lock.unlock()
    }

    func clear() {
        update(photo: nil, video: nil, barcode: nil, frame: nil, failure: nil)
    }

    func emitPhoto(_ value: String) { emit(value, keyPath: \.photo) }
    func emitVideo(_ value: String) { emit(value, keyPath: \.video) }
    func emitBarcode(_ value: CameraBarcode) { emit(value, keyPath: \.barcode) }
    func emitFrame(_ value: CameraFrame) { emit(value, keyPath: \.frame) }
    func emitFailure(_ value: CameraError) { emit(value, keyPath: \.failure) }

    private func emit<Value>(
        _ value: Value,
        keyPath: KeyPath<CameraCallbackBox, ((Value) -> Void)?>
    ) {
        lock.lock()
        let callback = self[keyPath: keyPath]
        lock.unlock()
        let invocation = CameraCallbackInvocation(value: value, callback: callback)
        DispatchQueue.main.async { invocation.callback?(invocation.value) }
    }
}

/// Serializes all capture-session mutations away from the UI thread.
private final class CameraCaptureEngine: NSObject, @unchecked Sendable,
    AVCapturePhotoCaptureDelegate, AVCaptureFileOutputRecordingDelegate,
    AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureMetadataOutputObjectsDelegate {
    let session = AVCaptureSession()

    private let sessionQueue = DispatchQueue(label: "dev.nexa.camera.session")
    private let videoQueue = DispatchQueue(label: "dev.nexa.camera.frames", qos: .userInitiated)
    private let callbackBox = CameraCallbackBox()
    private let delegateLock = NSLock()
    private let configurationLock = NSLock()
    private var photoDelegates: [Int64: CameraPhotoDelegate] = [:]
    private var configuration: CameraConfiguration?
    private var photoOutput: AVCapturePhotoOutput?
    private var movieOutput: AVCaptureMovieFileOutput?
    private var metadataOutput: AVCaptureMetadataOutput?
    private var videoDataOutput: AVCaptureVideoDataOutput?
    private var lastPhotoRequestId: Int32 = 0
    private var pendingPhotoRequestId: Int32?
    private var nextFrameSequence: Int64 = 0
    private var lastFrameDispatchNanos: UInt64 = 0
    private var lastBarcodeValue: String?
    private var lastBarcodeDispatchNanos: UInt64 = 0
    private var videoPermissionRequestInFlight = false
    private var audioPermissionRequestInFlight = false
    private var isStopped = true
    private var wantsRunning = false

    func updateCallbacks(
        photo: ((String) -> Void)?,
        video: ((String) -> Void)?,
        barcode: ((CameraBarcode) -> Void)?,
        frame: ((CameraFrame) -> Void)?,
        failure: ((CameraError) -> Void)?
    ) {
        callbackBox.update(photo: photo, video: video, barcode: barcode, frame: frame, failure: failure)
    }

    func update(configuration: CameraConfiguration, photoRequestId: Int32) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.wantsRunning = true
            let previous = self.configuration
            self.configurationLock.lock()
            self.configuration = configuration
            self.configurationLock.unlock()
            if photoRequestId != self.lastPhotoRequestId {
                self.lastPhotoRequestId = photoRequestId
                if photoRequestId > 0 { self.pendingPhotoRequestId = photoRequestId }
            }

            guard (1...30).contains(configuration.maxFramesPerSecond) else {
                self.callbackBox.emitFailure(.invalidFrameRate(frameRate: configuration.maxFramesPerSecond))
                return
            }

            if self.movieOutput?.isRecording == true {
                if !configuration.recording {
                    self.movieOutput?.stopRecording()
                }
                return
            }

            guard self.isStopped || previous != configuration else {
                self.capturePendingPhoto()
                return
            }
            self.configureAndRun()
        }
    }

    func requestPhoto(_ requestId: Int32) {
        sessionQueue.async { [weak self] in
            guard let self, requestId != self.lastPhotoRequestId else { return }
            self.lastPhotoRequestId = requestId
            guard requestId > 0 else { return }
            self.pendingPhotoRequestId = requestId
            self.capturePendingPhoto()
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.wantsRunning = false
            if self.movieOutput?.isRecording == true {
                self.movieOutput?.stopRecording()
            }
            if self.session.isRunning { self.session.stopRunning() }
            self.isStopped = true
            self.callbackBox.clear()
        }
    }

    private func configureAndRun() {
        guard wantsRunning, let configuration else { return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorizeAudioThenConfigure(configuration)
        case .notDetermined:
            guard !videoPermissionRequestInFlight else { return }
            videoPermissionRequestInFlight = true
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                self.sessionQueue.async {
                    self.videoPermissionRequestInFlight = false
                    guard self.wantsRunning, granted else {
                        if !granted { self.callbackBox.emitFailure(.cameraPermissionDenied) }
                        return
                    }
                    self.authorizeAudioThenConfigure(self.configuration ?? configuration)
                }
            }
        default:
            callbackBox.emitFailure(.cameraPermissionDenied)
        }
    }

    private func authorizeAudioThenConfigure(_ configuration: CameraConfiguration) {
        guard configuration.recording && configuration.recordAudio else {
            configureSession(configuration)
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            configureSession(configuration)
        case .notDetermined:
            guard !audioPermissionRequestInFlight else { return }
            audioPermissionRequestInFlight = true
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                guard let self else { return }
                self.sessionQueue.async {
                    self.audioPermissionRequestInFlight = false
                    guard self.wantsRunning, granted else {
                        if !granted { self.callbackBox.emitFailure(.microphonePermissionDenied) }
                        return
                    }
                    self.configureSession(self.configuration ?? configuration)
                }
            }
        default:
            callbackBox.emitFailure(.microphonePermissionDenied)
        }
    }

    private func configureSession(_ configuration: CameraConfiguration) {
        guard let device = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: configuration.facing == .front ? .front : .back
        ) else {
            callbackBox.emitFailure(.cameraUnavailable)
            return
        }

        if session.isRunning { session.stopRunning() }
        isStopped = true
        do {
            try configureGraph(configuration, device: device)
        } catch let error as CameraError {
            callbackBox.emitFailure(error)
            return
        } catch {
            callbackBox.emitFailure(.cameraUnavailable)
            return
        }
        session.startRunning()
        isStopped = false

        if configuration.recording, let movieOutput {
            do {
                let url = try CameraFileStore.makeURL(prefix: "video-", suffix: "mov")
                movieOutput.startRecording(to: url, recordingDelegate: self)
            } catch {
                callbackBox.emitFailure(.recordingFailed(message: error.localizedDescription))
            }
        }
        capturePendingPhoto()
    }

    private func configureGraph(_ configuration: CameraConfiguration, device: AVCaptureDevice) throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        for output in session.outputs { session.removeOutput(output) }
        for input in session.inputs { session.removeInput(input) }

        let preset: AVCaptureSession.Preset
        if configuration.recording {
            preset = .high
        } else if configuration.imageStreamEnabled || configuration.barcodeScanningEnabled {
            preset = configuration.frameResolution == .hd ? .hd1280x720 : .vga640x480
        } else {
            preset = .photo
        }
        session.sessionPreset = session.canSetSessionPreset(preset) ? preset : .high

        let videoInput = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(videoInput) else { throw CameraError.cameraUnavailable }
        session.addInput(videoInput)
        if configuration.recording && configuration.recordAudio {
            guard let audioDevice = AVCaptureDevice.default(for: .audio) else {
                throw CameraError.microphonePermissionDenied
            }
            let audioInput = try AVCaptureDeviceInput(device: audioDevice)
            guard session.canAddInput(audioInput) else {
                throw CameraError.microphonePermissionDenied
            }
            session.addInput(audioInput)
        }

        photoOutput = nil
        movieOutput = nil
        metadataOutput = nil
        videoDataOutput = nil

        if configuration.recording {
            let output = AVCaptureMovieFileOutput()
            guard session.canAddOutput(output) else { throw CameraError.cameraUnavailable }
            session.addOutput(output)
            movieOutput = output
        } else {
            let output = AVCapturePhotoOutput()
            guard session.canAddOutput(output) else { throw CameraError.cameraUnavailable }
            session.addOutput(output)
            photoOutput = output
        }

        if configuration.barcodeScanningEnabled {
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { throw CameraError.cameraUnavailable }
            session.addOutput(output)
            let acceptedTypes = Set(output.availableMetadataObjectTypes)
                .intersection(configuration.barcodeFormat.avFoundationTypes)
            output.metadataObjectTypes = Array(acceptedTypes)
            output.setMetadataObjectsDelegate(self, queue: sessionQueue)
            metadataOutput = output
        }

        if configuration.imageStreamEnabled {
            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            ]
            guard session.canAddOutput(output) else { throw CameraError.cameraUnavailable }
            session.addOutput(output)
            output.setSampleBufferDelegate(self, queue: videoQueue)
            videoDataOutput = output
        }
    }

    private func capturePendingPhoto() {
        guard pendingPhotoRequestId != nil,
              let photoOutput,
              movieOutput?.isRecording != true else { return }
        pendingPhotoRequestId = nil
        let settings = AVCapturePhotoSettings()
        let captureDelegate = CameraPhotoDelegate(
            callbacks: callbackBox,
            release: { [weak self] id in self?.releasePhotoDelegate(id) }
        )
        delegateLock.lock()
        photoDelegates[settings.uniqueID] = captureDelegate
        delegateLock.unlock()
        photoOutput.capturePhoto(with: settings, delegate: captureDelegate)
    }

    private func releasePhotoDelegate(_ id: Int64) {
        delegateLock.lock()
        photoDelegates.removeValue(forKey: id)
        delegateLock.unlock()
    }

    func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        if let error {
            callbackBox.emitFailure(.recordingFailed(message: error.localizedDescription))
        } else {
            callbackBox.emitVideo(outputFileURL.absoluteString)
        }
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard self.wantsRunning else { return }
            if self.session.isRunning { self.session.stopRunning() }
            self.configureAndRun()
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let configuration = configurationSnapshot,
              configuration.imageStreamEnabled,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let now = DispatchTime.now().uptimeNanoseconds
        let interval = 1_000_000_000 / UInt64(configuration.maxFramesPerSecond)
        guard now &- lastFrameDispatchNanos >= interval else { return }
        lastFrameDispatchNanos = now
        nextFrameSequence &+= 1
        guard let pixels = Self.copyI420(pixelBuffer) else { return }
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let nanos: Int64 = presentationTime.isValid
            ? CMTimeConvertScale(presentationTime, timescale: 1_000_000_000, method: .default).value
            : 0
        let frame = CameraFrame(
            sequence: nextFrameSequence,
            width: Int32(CVPixelBufferGetWidth(pixelBuffer)),
            height: Int32(CVPixelBufferGetHeight(pixelBuffer)),
            rotationDegrees: Int32(connection.videoRotationAngle),
            presentationTimeNanoseconds: nanos,
            pixels: pixels
        )
        callbackBox.emitFrame(frame)
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let configuration = configurationSnapshot, configuration.barcodeScanningEnabled else { return }
        let now = DispatchTime.now().uptimeNanoseconds
        for metadata in metadataObjects {
            guard let code = metadata as? AVMetadataMachineReadableCodeObject,
                  let value = code.stringValue,
                  let format = code.type.toNexaFormat(selectedFormat: configuration.barcodeFormat),
                  format == configuration.barcodeFormat else { continue }
            // AVFoundation pads UPC-A symbols with a leading zero and reports
            // them as EAN-13. Match Android's 12-digit UPC-A payload.
            let normalizedValue: String
            if code.type == .ean13, format == .upcA, value.count == 13, value.first == "0" {
                normalizedValue = String(value.dropFirst())
            } else {
                normalizedValue = value
            }
            if normalizedValue == lastBarcodeValue, now &- lastBarcodeDispatchNanos < 1_000_000_000 { continue }
            lastBarcodeValue = normalizedValue
            lastBarcodeDispatchNanos = now
            callbackBox.emitBarcode(CameraBarcode(value: normalizedValue, format: format))
        }
    }

    private static func copyI420(_ pixelBuffer: CVPixelBuffer) -> Data? {
        guard CVPixelBufferGetPlaneCount(pixelBuffer) >= 2 else { return nil }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let chromaWidth = CVPixelBufferGetWidthOfPlane(pixelBuffer, 1)
        let chromaHeight = CVPixelBufferGetHeightOfPlane(pixelBuffer, 1)
        guard let ySource = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0),
              let uvSource = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1) else { return nil }
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
        let ySize = width * height
        let chromaSize = chromaWidth * chromaHeight
        let uv = uvSource.assumingMemoryBound(to: UInt8.self)
        var pixels = Data(count: ySize + chromaSize * 2)
        pixels.withUnsafeMutableBytes { destinationRaw in
            guard let destination = destinationRaw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            let yDestination = destination
            let uDestination = destination.advanced(by: ySize)
            let vDestination = uDestination.advanced(by: chromaSize)
            for row in 0..<height {
                memcpy(yDestination.advanced(by: row * width), ySource.advanced(by: row * yStride), width)
            }
            for row in 0..<chromaHeight {
                let sourceRow = uv.advanced(by: row * uvStride)
                let uRow = uDestination.advanced(by: row * chromaWidth)
                let vRow = vDestination.advanced(by: row * chromaWidth)
                for column in 0..<chromaWidth {
                    uRow[column] = sourceRow[column * 2]
                    vRow[column] = sourceRow[column * 2 + 1]
                }
            }
        }
        return pixels
    }

    private var configurationSnapshot: CameraConfiguration? {
        configurationLock.lock()
        defer { configurationLock.unlock() }
        return configuration
    }
}

private extension CameraBarcodeFormat {
    var avFoundationTypes: [AVMetadataObject.ObjectType] {
        switch self {
        case .aztec: [.aztec]
        case .codabar: [.codabar]
        case .code39: [.code39]
        case .code93: [.code93]
        case .code128: [.code128]
        case .dataMatrix: [.dataMatrix]
        case .ean8: [.ean8]
        case .ean13: [.ean13]
        case .itf: [.interleaved2of5]
        case .pdf417: [.pdf417]
        case .qr: [.qr]
        case .upcA: [.ean13]
        case .upcE: [.upce]
        }
    }
}

private extension AVMetadataObject.ObjectType {
    func toNexaFormat(selectedFormat: CameraBarcodeFormat) -> CameraBarcodeFormat? {
        // AVFoundation reports UPC-A symbols as EAN-13 metadata. Preserve the
        // requested Nexa format so UPC-A scanning works like the Android API.
        if self == .ean13, selectedFormat == .upcA { return .upcA }
        switch self {
        case .aztec: .aztec
        case .codabar: .codabar
        case .code39: .code39
        case .code93: .code93
        case .code128: .code128
        case .dataMatrix: .dataMatrix
        case .ean8: .ean8
        case .ean13: .ean13
        case .interleaved2of5: .itf
        case .pdf417: .pdf417
        case .qr: .qr
        case .upce: .upcE
        default: nil
        }
    }
}

private final class CameraPhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    private let callbacks: CameraCallbackBox
    private let release: (Int64) -> Void

    init(callbacks: CameraCallbackBox, release: @escaping (Int64) -> Void) {
        self.callbacks = callbacks
        self.release = release
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if let error {
            callbacks.emitFailure(.captureFailed(message: error.localizedDescription))
        } else {
            do {
                guard let data = photo.fileDataRepresentation() else {
                    throw CameraFileError.photoDataUnavailable
                }
                let url = try CameraFileStore.makeURL(prefix: "photo-", suffix: "jpg")
                try data.write(to: url, options: .atomic)
                callbacks.emitPhoto(url.absoluteString)
            } catch {
                callbacks.emitFailure(.captureFailed(message: error.localizedDescription))
            }
        }
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
        error: Error?
    ) {
        release(resolvedSettings.uniqueID)
    }
}

private enum CameraFileError: LocalizedError {
    case photoDataUnavailable

    var errorDescription: String? {
        "The camera did not return image data."
    }
}

private enum CameraFileStore {
    static func makeURL(prefix: String, suffix: String) throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NexaCamera", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("\(prefix)\(UUID().uuidString).\(suffix)")
    }
}
