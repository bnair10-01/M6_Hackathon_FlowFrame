import SwiftUI

/// Grid of the glasses' apps; tap one to open it on the glasses.
struct AppsView: View {
    @ObservedObject var link: HubLink
    @ObservedObject var companions: Companions
    @State private var opened: String?
    @State private var path: [GlassesApp] = []

    private let columns = [GridItem(.adaptive(minimum: 88), spacing: 16)]

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if !link.isConnected {
                    ContentUnavailableView("Glasses not connected", systemImage: "eyeglasses",
                                           description: Text(link.state.description))
                } else if link.apps.isEmpty {
                    ProgressView("Loading apps…")
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 20) {
                            ForEach(link.apps) { app in
                                Button {
                                    link.launch(app)
                                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                    if Companions.has(app) { path.append(app) } else { opened = app.label }
                                } label: {
                                    VStack(spacing: 6) {
                                        AppIcon(link: link, app: app)
                                            .overlay(alignment: .topTrailing) {
                                                if Companions.has(app) {
                                                    Image(systemName: "iphone.circle.fill")
                                                        .font(.title3)
                                                        .symbolRenderingMode(.multicolor)
                                                        .background(Circle().fill(.background))
                                                        .offset(x: 6, y: -6)
                                                        .accessibilityLabel("Has phone companion")
                                                }
                                            }
                                        Text(app.label).font(.caption).lineLimit(2).multilineTextAlignment(.center)
                                            .foregroundStyle(.primary)
                                    }
                                }
                            }
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle("Apps")
            .navigationDestination(for: GlassesApp.self) { app in
                CompanionScreen(link: link, companions: companions, app: app)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { link.goHome() } label: { Label("Home", systemImage: "house") }
                        .disabled(!link.isConnected)
                }
            }
            .refreshable { link.refreshApps() }
            .overlay(alignment: .bottom) {
                if let r = link.lastResult, opened != nil {
                    Label(r.message, systemImage: r.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.thinMaterial, in: Capsule())
                        .padding(.bottom, 12)
                        .task(id: r.message) {
                            try? await Task.sleep(for: .seconds(2))
                            opened = nil
                        }
                }
            }
        }
    }
}

/// App icon over Wi-Fi, or a placeholder when Wi-Fi isn't available.
struct AppIcon: View {
    @ObservedObject var link: HubLink
    let app: GlassesApp
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable()
            } else {
                RoundedRectangle(cornerRadius: 14).fill(.tint.opacity(0.15))
                    .overlay(Text(String(app.label.prefix(1))).font(.title2.bold()).foregroundStyle(.tint))
            }
        }
        .frame(width: 60, height: 60)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .task(id: link.status?.wifi.ip) {
            guard let http = HubHTTP(status: link.status, token: link.token) else { return }
            image = await ImageCache.shared.image("apps/\(app.package)/icon", http: http)
        }
    }
}
