import Foundation
import UIKit

/// Splits a multipart MJPEG byte stream into JPEG frames. Each part is
/// "--boundary\r\n", headers with Content-Length, a blank line, then the JPEG.
struct MJPEGParser {
    private var buffer = Data()

    mutating func feed(_ data: Data) -> [Data] {
        buffer.append(data)
        var frames: [Data] = []
        while let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) {
            let head = String(decoding: buffer[buffer.startIndex..<headerEnd.lowerBound], as: UTF8.self)
            // Swift treats "\r\n" as one Character, so split on any newline.
            guard let length = head.split(whereSeparator: \.isNewline)
                .first(where: { $0.lowercased().hasPrefix("content-length:") })
                .flatMap({ Int($0.split(separator: ":")[1].trimmingCharacters(in: .whitespacesAndNewlines)) }) else {
                // Not a part header (e.g. stray bytes): drop through the blank line.
                buffer.removeSubrange(buffer.startIndex..<headerEnd.upperBound)
                continue
            }
            let start = headerEnd.upperBound
            guard buffer.distance(from: start, to: buffer.endIndex) >= length else { break }
            let end = buffer.index(start, offsetBy: length)
            frames.append(Data(buffer[start..<end]))
            buffer.removeSubrange(buffer.startIndex..<end)
        }
        if buffer.count > 4_000_000 { buffer.removeAll() } // never hold a runaway stream
        return frames
    }
}

/// Live camera view from the glasses.
@MainActor
final class MJPEGStream: NSObject, ObservableObject, URLSessionDataDelegate {
    @Published private(set) var frame: UIImage?
    @Published private(set) var fps: Double = 0
    @Published private(set) var error: String?
    @Published private(set) var running = false

    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var parser = MJPEGParser()
    private var frameTimes: [Date] = []

    func start(_ http: HubHTTP) {
        stop()
        error = nil
        running = true
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        let s = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        session = s
        task = s.dataTask(with: http.request("live"))
        task?.resume()
    }

    func stop() {
        task?.cancel()
        session?.invalidateAndCancel()
        task = nil
        session = nil
        parser = MJPEGParser()
        running = false
        fps = 0
    }

    nonisolated func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        MainActor.assumeIsolated {
            for jpeg in parser.feed(data) {
                guard let img = UIImage(data: jpeg) else { continue }
                frame = img
                let now = Date()
                frameTimes.append(now)
                frameTimes.removeAll { now.timeIntervalSince($0) > 2 }
                fps = Double(frameTimes.count) / 2
            }
        }
    }

    nonisolated func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                                completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 200
        MainActor.assumeIsolated {
            if code == 401 { error = HubError.unauthorized.localizedDescription }
        }
        completionHandler(code == 200 ? .allow : .cancel)
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        MainActor.assumeIsolated {
            guard running else { return }
            if let error, (error as? URLError)?.code != .cancelled {
                self.error = "Live view stopped: \(error.localizedDescription)"
            }
            running = false
        }
    }
}
