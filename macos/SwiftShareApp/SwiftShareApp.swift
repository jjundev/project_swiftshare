import SwiftShareDomain
import SwiftUI
import CoreImage.CIFilterBuiltins
import ServiceManagement
import UniformTypeIdentifiers

@main
struct SwiftShareApp: App {
    @StateObject private var model = MacTransferModel()
    @StateObject private var pairing = MacPairingModel()
    @StateObject private var launchAtLogin = MacLaunchAtLoginModel()
    @StateObject private var inbound = MacInboundRuntime.shared

    var body: some Scene {
        MenuBarExtra {
            FixedEndpointTransferView(model: model, pairing: pairing, launchAtLogin: launchAtLogin, inbound: inbound)
        } label: {
            Image(systemName: "arrow.left.arrow.right.circle.fill")
                .accessibilityLabel(ProductBoundary.title)
        }
        .menuBarExtraStyle(.window)
    }
}

private struct FixedEndpointTransferView: View {
    @ObservedObject var model: MacTransferModel
    @ObservedObject var pairing: MacPairingModel
    @ObservedObject var launchAtLogin: MacLaunchAtLoginModel
    @ObservedObject var inbound: MacInboundRuntime
    @State private var scanningEndpointQR = false
    @State private var isDropTargeted = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    Image(systemName: "arrow.left.arrow.right.circle.fill")
                        .font(.title)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(LocalizedStringKey(ProductBoundary.title)).font(.title2.bold())
                        Text("LAN Mode · Mac ↔ Android")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Text(LocalizedStringKey(ProductBoundary.message)).font(.headline)
                Text("Android destination: Downloads/SwiftShare")
                    .font(.subheadline)

                PairingSection(model: pairing) { peer in
                    pairing.unpair(peer) {
                        model.unpair(peerID: peer.id)
                        inbound.unpair(peerID: peer.id)
                    }
                }

                GroupBox("Receive from Android") {
                    VStack(alignment: .leading, spacing: 8) {
                        LabeledContent("Destination", value: inbound.destinationName)
                        Text(inbound.availabilityMessage)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Button(inbound.destinationName == "Not configured" ? "Choose destination…" : "Change destination…") {
                            inbound.chooseDestination()
                        }
                        if let summary = inbound.incomingSummary, let phase = inbound.incomingPhase {
                            Divider()
                            Text("Incoming from \(summary.authenticatedSender)").fontWeight(.semibold)
                            LabeledContent("File", value: summary.displayName)
                            LabeledContent("Total", value: ByteCountFormatter.string(fromByteCount: Int64(summary.byteCount), countStyle: .file))
                            if phase == .awaitingApproval {
                                HStack {
                                    Button("Accept", action: inbound.approve).buttonStyle(.borderedProminent)
                                    Button("Decline", role: .destructive, action: inbound.reject)
                                }
                            }
                            if [.transferring, .verifying, .committing].contains(phase) {
                                ProgressView(value: Double(inbound.verifiedBytes), total: Double(max(summary.byteCount, 1)))
                                Text("\(ByteCountFormatter.string(fromByteCount: Int64(inbound.verifiedBytes), countStyle: .file)) verified")
                                    .font(.caption).foregroundStyle(.secondary)
                                Button("Cancel", role: .destructive, action: inbound.cancel)
                            }
                            if phase == .completed, let path = inbound.committedPath {
                                Text("Saved \(URL(fileURLWithPath: path).lastPathComponent)").foregroundStyle(.green)
                            }
                        }
                        if let error = inbound.errorMessage {
                            Text(error).font(.caption).foregroundStyle(.red)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                GroupBox("Availability") {
                    VStack(alignment: .leading, spacing: 8) {
                        Toggle("Launch SwiftShare at login", isOn: Binding(
                            get: { launchAtLogin.isEnabled },
                            set: { launchAtLogin.setEnabled($0) }
                        ))
                        Text(launchAtLogin.statusMessage)
                            .font(.caption)
                            .foregroundStyle(launchAtLogin.hasError ? .red : .secondary)
                        Text("The Mac is available while this menu-bar application is running.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                GroupBox("Development identity") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Mac SPKI pin")
                            .font(.caption.bold())
                        Text(model.localPin)
                            .font(.system(.caption2, design: .monospaced))
                            .textSelection(.enabled)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                GroupBox("Android destination") {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(pairing.peers) { peer in
                            Button { model.select(peer: peer) } label: {
                                HStack {
                                    Image(systemName: model.selectedPeerID == peer.id ? "checkmark.circle.fill" : "circle")
                                    Text(peer.displayName)
                                    Spacer()
                                    Text(model.isOnline(peer.id) ? "Online" : "Needs QR").foregroundStyle(model.isOnline(peer.id) ? .green : .secondary)
                                }
                            }.buttonStyle(.plain)
                        }
                        TextField("Paste endpoint QR payload when Discovery is unavailable", text: $model.endpointQR)
                            .textFieldStyle(.roundedBorder)
                        Button("Scan endpoint QR…") { scanningEndpointQR = true }
                        Text("The QR locates the Peer; pinned authentication and approval are still required.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }

                GroupBox("Payload review") {
                    VStack(alignment: .leading, spacing: 8) {
                        if !model.preparedFiles.isEmpty {
                            LabeledContent("Destination", value: model.destinationLabel)
                            LabeledContent("Items", value: "\(model.itemCount)")
                            LabeledContent("Total", value: ByteCountFormatter.string(fromByteCount: Int64(model.batchByteCount), countStyle: .file))
                            ForEach(model.preparedFiles, id: \.itemID) { file in
                                HStack {
                                    Image(systemName: "doc")
                                    Text(file.displayName).lineLimit(1).truncationMode(.middle)
                                    Spacer()
                                    Text(ByteCountFormatter.string(fromByteCount: Int64(file.byteCount), countStyle: .file))
                                        .foregroundStyle(.secondary).font(.caption)
                                }
                            }
                        } else {
                            Text("No files selected").foregroundStyle(.secondary)
                        }
                        if !model.rejectedItems.isEmpty {
                            Text("Skipped (unsupported or unreadable): \(model.rejectedItems.joined(separator: ", "))")
                                .font(.caption).foregroundStyle(.orange)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        HStack {
                            Button("Choose files…", action: model.selectFiles)
                            if !model.preparedFiles.isEmpty {
                                Button("Clear", role: .destructive, action: model.clearSelection)
                            }
                        }
                        Text("Tip: drag files onto this window to add them.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                if !model.preparedFiles.isEmpty, let phase = model.phase {
                    VStack(alignment: .leading, spacing: 7) {
                        HStack {
                            Text(model.statusMessage).fontWeight(.semibold)
                            Spacer()
                            if model.totalBytes > 0 {
                                Text("\(Int((Double(model.verifiedBytes) / Double(model.totalBytes)) * 100))%")
                                    .monospacedDigit()
                            }
                        }
                        if model.totalItems > 1 {
                            Text("Item \(min(model.completedItems + 1, model.totalItems)) of \(model.totalItems)\(model.currentItemName.isEmpty ? "" : " · \(model.currentItemName)")")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        ProgressView(value: Double(model.verifiedBytes), total: Double(max(model.totalBytes, 1)))
                        Text("\(ByteCountFormatter.string(fromByteCount: Int64(model.verifiedBytes), countStyle: .file)) of \(ByteCountFormatter.string(fromByteCount: Int64(model.totalBytes), countStyle: .file)) verified")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if [.connecting, .awaitingApproval, .transferring, .verifying].contains(phase) {
                            Button("Cancel", role: .destructive, action: model.cancel)
                        }
                    }
                } else {
                    Text(model.statusMessage)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let error = model.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }

                HStack {
                    Button("Send to Android", action: model.send)
                        .buttonStyle(.borderedProminent)
                        .disabled(!model.canSend)
                    Spacer()
                    Button("Quit") { NSApplication.shared.terminate(nil) }
                        .keyboardShortcut("q")
                }
            }
            .padding(18)
        }
        .frame(width: 420, height: 760)
        .overlay {
            if isDropTargeted {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [8]))
                    .padding(6)
                    .allowsHitTesting(false)
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
            Task {
                let urls = await Self.loadDroppedURLs(providers)
                await MainActor.run { model.dropFiles(urls) }
            }
            return true
        }
        .sheet(isPresented: $scanningEndpointQR) {
            EndpointQRScannerView { value in model.endpointQR = value; scanningEndpointQR = false }
        }
        .onChange(of: pairing.peers) { _, _ in
            Task { await inbound.restart() }
        }
    }

    /// Loads dropped file URLs sequentially so the batch preserves drop order.
    private static func loadDroppedURLs(_ providers: [NSItemProvider]) async -> [URL] {
        var urls: [URL] = []
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            let url: URL? = await withCheckedContinuation { continuation in
                provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                    if let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) {
                        continuation.resume(returning: url)
                    } else if let url = item as? URL {
                        continuation.resume(returning: url)
                    } else {
                        continuation.resume(returning: nil)
                    }
                }
            }
            if let url { urls.append(url) }
        }
        return urls
    }
}

private struct PairingSection: View {
    @ObservedObject var model: MacPairingModel
    let onUnpair: (MacStoredPeer) -> Void
    var body: some View {
        GroupBox("Paired devices") {
            VStack(alignment: .leading, spacing: 9) {
                switch model.state {
                case .idle:
                    Button("Pair Android device", action: model.start).buttonStyle(.borderedProminent)
                case .waiting(let qr, let expiresAt):
                    if let image = qrImage(qr) { Image(nsImage: image).interpolation(.none).resizable().frame(width: 180, height: 180).accessibilityLabel("SwiftShare pairing QR code") }
                    Text("Scan with SwiftShare on Android · expires \(expiresAt, style: .relative)").font(.caption)
                    Button("Cancel", role: .destructive, action: model.cancel)
                case .confirming(let name, let platform, let code, let keyID):
                    Text("Confirm \(name)").fontWeight(.semibold)
                    Text("\(platform) · key \(keyID.prefix(12))").font(.caption).monospaced()
                    Text(code).font(.system(.title2, design: .monospaced).bold()).accessibilityLabel("Comparison code \(code)")
                    HStack { Button("Pair", action: model.approve).buttonStyle(.borderedProminent); Button("Reject", role: .destructive, action: model.reject) }
                case .waitingForAndroid:
                    ProgressView("Waiting for Android to commit trust…")
                case .paired(let name):
                    Label("Paired with \(name)", systemImage: "checkmark.shield.fill").foregroundStyle(.green)
                    Button("Pair another device", action: model.start)
                case .failed(let code):
                    Text("Pairing failed: \(code)").foregroundStyle(.red).font(.caption)
                    Button("Try again", action: model.start)
                }
                ForEach(model.peers) { peer in
                    Divider()
                    VStack(alignment: .leading, spacing: 7) {
                        HStack {
                            VStack(alignment: .leading) { Text(peer.displayName); Text("\(peer.platform) · \(peer.keyIDHex.prefix(12))").font(.caption2).foregroundStyle(.secondary) }
                            Spacer(); Button("Unpair", role: .destructive) { onUnpair(peer) }
                        }
                        Toggle(
                            "Auto-accept incoming transfers from this Peer",
                            isOn: Binding(
                                get: { peer.approvalPolicy == .autoAccept },
                                set: { model.setApprovalPolicy(peer, autoAccept: $0) }
                            )
                        )
                        .toggleStyle(.switch)
                    }
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func qrImage(_ value: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let representation = NSCIImageRep(ciImage: output); let image = NSImage(size: representation.size); image.addRepresentation(representation); return image
    }
}

@MainActor
private final class MacLaunchAtLoginModel: ObservableObject {
    @Published private(set) var isEnabled = false
    @Published private(set) var statusMessage = "Launch at login is off."
    @Published private(set) var hasError = false

    init() { refresh() }

    func setEnabled(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() }
            else { try SMAppService.mainApp.unregister() }
            refresh()
        } catch {
            hasError = true
            statusMessage = "Launch at login could not be changed: \(error.localizedDescription)"
            refreshEnabledOnly()
        }
    }

    private func refresh() {
        hasError = false
        switch SMAppService.mainApp.status {
        case .enabled:
            isEnabled = true
            statusMessage = "SwiftShare will launch after you sign in."
        case .requiresApproval:
            isEnabled = false
            statusMessage = "Allow SwiftShare in System Settings › General › Login Items."
        case .notFound:
            isEnabled = false
            hasError = true
            statusMessage = "The login item could not be found for this build."
        case .notRegistered:
            isEnabled = false
            statusMessage = "Launch at login is off."
        @unknown default:
            isEnabled = false
            hasError = true
            statusMessage = "Launch-at-login status is unavailable."
        }
    }

    private func refreshEnabledOnly() {
        isEnabled = SMAppService.mainApp.status == .enabled
    }
}
