import SwiftUI

@main
struct WornHubApp: App {
    @StateObject private var link = HubLink()
    @StateObject private var companions = Companions()

    var body: some Scene {
        WindowGroup {
            TabView {
                StatusView(link: link)
                    .tabItem { Label("Glasses", systemImage: "eyeglasses") }
                AppsView(link: link, companions: companions)
                    .tabItem { Label("Apps", systemImage: "square.grid.2x2") }
                LiveView(link: link)
                    .tabItem { Label("Live", systemImage: "video") }
                GalleryView(link: link)
                    .tabItem { Label("Gallery", systemImage: "photo.on.rectangle") }
            }
        }
    }
}

/// Shown on Wi-Fi tabs when live view and media aren't reachable yet.
struct WifiNeededView: View {
    @ObservedObject var link: HubLink

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: "wifi.exclamationmark")
        } description: {
            Text(detail)
        } actions: {
            if link.isConnected && link.token == nil {
                Button("Pair with glasses") { link.pair() }.buttonStyle(.borderedProminent)
            }
        }
    }

    private var title: String {
        if !link.isConnected { return "Glasses not connected" }
        if link.token == nil { return "Pairing needed" }
        return "Glasses aren't on Wi-Fi"
    }

    private var detail: String {
        if !link.isConnected { return link.state.description }
        if link.token == nil { return "Pair once so this phone can see the camera and media." }
        return "Put the glasses on the same Wi-Fi as this phone (Glasses tab › Join Wi-Fi)."
    }
}
