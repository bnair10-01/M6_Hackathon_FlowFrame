import AVKit
import Photos
import SwiftUI

/// Photos and videos stored on the glasses.
struct GalleryView: View {
    @ObservedObject var link: HubLink
    @State private var items: [MediaItem] = []
    @State private var filter = Filter.all
    @State private var error: String?
    @State private var loading = false

    enum Filter: String, CaseIterable, Identifiable {
        case all = "All", photos = "Photos", videos = "Videos"
        var id: String { rawValue }
    }

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 2)]

    var body: some View {
        NavigationStack {
            Group {
                if let http = HubHTTP(status: link.status, token: link.token) {
                    ScrollView {
                        Picker("Show", selection: $filter) {
                            ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                        }
                        .pickerStyle(.segmented).padding(.horizontal)
                        if let error { Text(error).font(.footnote).foregroundStyle(.red).padding() }
                        if !loading && shown.isEmpty && error == nil {
                            ContentUnavailableView("Nothing here yet", systemImage: "photo",
                                                   description: Text("Photos and videos taken with the glasses show up here."))
                        }
                        LazyVGrid(columns: columns, spacing: 2) {
                            ForEach(shown) { item in
                                NavigationLink(value: item) { Thumbnail(http: http, item: item) }
                            }
                        }
                    }
                    .refreshable { await load(http) }
                    .task(id: http.base) { await load(http) }
                    .navigationDestination(for: MediaItem.self) { MediaDetail(http: http, item: $0) }
                } else {
                    WifiNeededView(link: link)
                }
            }
            .navigationTitle("Gallery")
        }
    }

    private var shown: [MediaItem] {
        switch filter {
        case .all: return items
        case .photos: return items.filter { !$0.isVideo }
        case .videos: return items.filter(\.isVideo)
        }
    }

    private func load(_ http: HubHTTP) async {
        loading = true
        defer { loading = false }
        do {
            items = try await http.media()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct Thumbnail: View {
    let http: HubHTTP
    let item: MediaItem
    @State private var image: UIImage?

    var body: some View {
        Color(.secondarySystemBackground)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill() }
            }
            .clipped()
            .overlay(alignment: .bottomTrailing) {
                if item.isVideo {
                    Label(item.durationMs > 0 ? duration(item.durationMs) : "", systemImage: "play.fill")
                        .font(.caption2.bold()).foregroundStyle(.white).padding(4)
                        .background(.black.opacity(0.5), in: Capsule()).padding(4)
                }
            }
            .task { image = await ImageCache.shared.image("media/\(item.id)/thumb", http: http) }
    }
}

struct MediaDetail: View {
    let http: HubHTTP
    let item: MediaItem
    @State private var image: UIImage?
    @State private var player: AVPlayer?
    @State private var message: String?
    @State private var saving = false

    var body: some View {
        VStack {
            if item.isVideo {
                if let player {
                    VideoPlayer(player: player)
                        .onAppear { player.play() }
                        .onDisappear { player.pause() }
                }
            } else if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                ProgressView("Loading full photo…").frame(maxHeight: .infinity)
            }
            if let message { Text(message).font(.footnote).foregroundStyle(.secondary).padding(.horizontal) }
            Text("\(item.taken.formatted(date: .abbreviated, time: .shortened)) · \(ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file))")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let image {
                    ShareLink(item: Image(uiImage: image), preview: SharePreview(item.name, image: Image(uiImage: image)))
                }
                Button { save() } label: {
                    if saving { ProgressView() } else { Label("Save", systemImage: "square.and.arrow.down") }
                }
                .disabled(saving)
            }
        }
        .task { await load() }
    }

    private func load() async {
        if item.isVideo {
            let p = AVPlayer(url: http.videoURL(item))
            player = p
            // Recordings cut off mid-way have no index and can't be played anywhere.
            let asset = AVURLAsset(url: http.videoURL(item))
            if (try? await asset.load(.isPlayable)) != true {
                message = "This video can't be played. The recording probably wasn't finished (for example, the glasses restarted while recording)."
            }
        } else {
            image = try? await http.image("media/\(item.id)")
            if image == nil { message = "Couldn't load the photo." }
        }
    }

    private func save() {
        saving = true
        Task {
            defer { saving = false }
            let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard status == .authorized || status == .limited else {
                message = "Allow Photos access in Settings to save."
                return
            }
            do {
                if item.isVideo {
                    let url = try await http.download(item)
                    try await PHPhotoLibrary.shared().performChanges {
                        PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                    }
                } else if let image {
                    try await PHPhotoLibrary.shared().performChanges {
                        PHAssetChangeRequest.creationRequestForAsset(from: image)
                    }
                }
                message = "Saved to Photos"
            } catch {
                message = "Couldn't save: \(error.localizedDescription)"
            }
        }
    }
}

func duration(_ ms: Int64) -> String {
    let s = ms / 1000
    return String(format: "%d:%02d", s / 60, s % 60)
}
