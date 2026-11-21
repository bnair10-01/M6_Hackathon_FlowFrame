import SwiftUI

@main
struct WornRunApp: App {
    @StateObject private var link: RunLink
    @StateObject private var session: RunSession

    init() {
        let link = RunLink()
        _link = StateObject(wrappedValue: link)
        _session = StateObject(wrappedValue: RunSession(link: link))
    }

    var body: some Scene {
        WindowGroup {
            SetupView(session: session, link: link)
        }
    }
}
