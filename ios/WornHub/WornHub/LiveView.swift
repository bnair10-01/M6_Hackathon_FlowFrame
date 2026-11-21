import Photos
import SwiftUI

/// What the glasses' camera sees, live.
struct LiveView: View {
    @ObservedObject var link: HubLink
    @StateObject private var stream = MJPEGStream()
    @State private var saved = false

    var body: some View {
        NavigationStack {
            Group {
                if let http = HubHTTP(status: link.status, token: link.token) {
                    VStack(spacing: 16) {
                        ZStack {
                            Rectangle().fill(.black)
                            if let frame = stream.frame {
                                Image(uiImage: frame).resizable().scaledToFit()
                            } else if stream.running {
                                ProgressView().tint(.white)
                            }
                        }
                        .aspectRatio(4 / 3, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(alignment: .topLeading) {
                            if stream.running && stream.frame != nil {
                                Label(String(format: "LIVE · %.0f fps", stream.fps), systemImage: "circle.fill")
                                    .font(.caption.bold()).foregroundStyle(.white)
                                    .padding(6).background(.red.opacity(0.8), in: Capsule()).padding(10)
                            }
                        }
                        if let e = stream.error { Text(e).font(.footnote).foregroundStyle(.red) }
                        HStack {
                            Button(stream.running ? "Stop" : "Start") {
                                stream.running ? stream.stop() : stream.start(http)
                            }
                            .buttonStyle(.bordered)
                            Button { snapshot() } label: { Label(saved ? "Saved" : "Snapshot", systemImage: "camera") }
                                .buttonStyle(.borderedProminent)
                                .disabled(stream.frame == nil)
                        }
                        Text("The glasses' camera stays on only while you watch.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Spacer()
                    }
                    .padding()
                    .onAppear { if !stream.running { stream.start(http) } }
                    .onDisappear { stream.stop() }
                } else {
                    WifiNeededView(link: link)
                }
            }
            .navigationTitle("Live")
        }
    }

    private func snapshot() {
        guard let frame = stream.frame else { return }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else { return }
            UIImageWriteToSavedPhotosAlbum(frame, nil, nil, nil)
            Task { @MainActor in
                saved = true
                try? await Task.sleep(for: .seconds(1.5))
                saved = false
            }
        }
    }
}
