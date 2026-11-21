import SwiftUI

/// Phone-side screens for glasses apps that have one. The Apps tab badges
/// these apps and opens their screen when tapped.
///
/// Companion sources live with their standalone app (e.g. ios/WornRun) and are
/// compiled into Worn Hub too; see project.yml.
@MainActor
final class Companions: ObservableObject {
    /// Worn Run's link and session. Created at launch, like the standalone
    /// app, so a run started from the glasses' touchpad still gets GPS.
    let runLink: RunLink
    let runSession: RunSession

    init() {
        let link = RunLink()
        runLink = link
        runSession = RunSession(link: link)
    }

    /// Glasses package → companion. Add new companions here.
    static let packages: Set<String> = ["com.stevencastro.wornrun"]

    static func has(_ app: GlassesApp) -> Bool { packages.contains(app.package) }

    @ViewBuilder
    func view(for app: GlassesApp) -> some View {
        switch app.package {
        case "com.stevencastro.wornrun":
            WornRunForm(session: runSession, link: runLink)
        default:
            EmptyView()
        }
    }
}

/// A companion screen with a button to bring its app up on the glasses.
struct CompanionScreen: View {
    @ObservedObject var link: HubLink
    @ObservedObject var companions: Companions
    let app: GlassesApp

    var body: some View {
        companions.view(for: app)
            .navigationTitle(app.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { link.launch(app) } label: { Label("Show on glasses", systemImage: "eyeglasses") }
                        .disabled(!link.isConnected)
                }
            }
    }
}
