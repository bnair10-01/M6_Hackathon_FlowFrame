import Foundation
import UIKit

struct MediaItem: Decodable, Identifiable, Hashable {
    let id: String
    let kind: String
    let name: String
    let mime: String
    let takenMs: Int64
    let size: Int64
    let width: Int
    let height: Int
    let durationMs: Int64

    var isVideo: Bool { kind == "video" }
    var taken: Date { Date(timeIntervalSince1970: Double(takenMs) / 1000) }
}

/// The glasses' Wi-Fi side: media, thumbnails, app icons and the live stream.
/// Every request carries the access token read over Bluetooth.
struct HubHTTP {
    let base: URL
    let token: String

    init?(status: HubStatus?, token: String?) {
        guard let status, status.wifi.connected, !status.wifi.ip.isEmpty, let token,
              let url = URL(string: "http://\(status.wifi.ip):\(status.wifi.port)") else { return nil }
        base = url
        self.token = token
    }

    func request(_ path: String) -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent(path))
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.timeoutInterval = 15
        return r
    }

    func media() async throws -> [MediaItem] {
        struct List: Decodable { let items: [MediaItem] }
        let (data, response) = try await URLSession.shared.data(for: request("media"))
        try Self.check(response)
        return try JSONDecoder().decode(List.self, from: data).items
    }

    func image(_ path: String) async throws -> UIImage {
        let (data, response) = try await URLSession.shared.data(for: request(path))
        try Self.check(response)
        guard let img = UIImage(data: data) else { throw URLError(.cannotDecodeContentData) }
        return img
    }

    /// AVPlayer can't add headers, so videos carry the token in the URL.
    func videoURL(_ item: MediaItem) -> URL {
        var c = URLComponents(url: base.appendingPathComponent("media/\(item.id)"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "t", value: token)]
        return c.url!
    }

    func download(_ item: MediaItem) async throws -> URL {
        let (tmp, response) = try await URLSession.shared.download(for: request("media/\(item.id)"))
        try Self.check(response)
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(item.name)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        return dest
    }

    static func check(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { return }
        if http.statusCode == 401 { throw HubError.unauthorized }
        guard (200..<300).contains(http.statusCode) else { throw HubError.http(http.statusCode) }
    }
}

enum HubError: LocalizedError {
    case unauthorized, http(Int)
    var errorDescription: String? {
        switch self {
        case .unauthorized: return "The glasses didn't accept this phone. Pair again from the Status tab."
        case .http(let code): return "The glasses answered with error \(code)."
        }
    }
}

/// Small in-memory cache for thumbnails and icons.
actor ImageCache {
    static let shared = ImageCache()
    private var images: [String: UIImage] = [:]

    func image(_ path: String, http: HubHTTP) async -> UIImage? {
        if let hit = images[path] { return hit }
        guard let img = try? await http.image(path) else { return nil }
        images[path] = img
        return img
    }
}
