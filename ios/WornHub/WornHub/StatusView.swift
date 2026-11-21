import SwiftUI

/// Connection, battery health, storage and Wi-Fi.
struct StatusView: View {
    @ObservedObject var link: HubLink
    @State private var ssid = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Image(systemName: link.isConnected ? "eyeglasses" : "antenna.radiowaves.left.and.right")
                            .foregroundStyle(link.isConnected ? .green : .secondary)
                        Text(link.state.description)
                        Spacer()
                        if link.isConnected { Button("Forget", role: .destructive) { link.forget() }.font(.footnote) }
                    }
                    if !link.isConnected {
                        Text("Open Worn Hub on the glasses once. After that it starts by itself.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if let s = link.status, !s.foreground.isEmpty {
                        LabeledContent("On screen", value: s.foreground)
                    }
                    if let e = link.pairingError { Text(e).font(.footnote).foregroundStyle(.red) }
                }

                if let s = link.status {
                    batterySection(s.battery)
                    Section("Storage") {
                        let used = s.storage.totalBytes - s.storage.freeBytes
                        ProgressView(value: Double(used), total: Double(max(1, s.storage.totalBytes)))
                        LabeledContent("Free", value: "\(bytes(s.storage.freeBytes)) of \(bytes(s.storage.totalBytes))")
                    }
                    Section("Wi-Fi") {
                        if s.wifi.connected {
                            LabeledContent("Network", value: s.wifi.ssid.isEmpty ? "Connected" : s.wifi.ssid)
                            LabeledContent("Address", value: s.wifi.ip)
                        } else {
                            Text(s.wifi.enabled ? "Not connected" : "Wi-Fi is off").foregroundStyle(.secondary)
                        }
                        DisclosureGroup("Join Wi-Fi") {
                            TextField("Network name", text: $ssid).textInputAutocapitalization(.never).autocorrectionDisabled()
                            SecureField("Password", text: $password)
                            Button("Join") { link.joinWifi(ssid: ssid, password: password) }
                                .disabled(ssid.isEmpty || !link.isConnected)
                            Text("Use the same network as this phone for live view and photos.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Section("Device") {
                        LabeledContent("Model", value: s.model)
                        LabeledContent("Android", value: s.android)
                    }
                }
                if let r = link.lastResult {
                    Section { Label(r.message, systemImage: r.ok ? "checkmark.circle" : "exclamationmark.triangle") }
                }
            }
            .navigationTitle("Glasses")
            .refreshable { link.refreshStatus() }
        }
    }

    private func batterySection(_ b: HubStatus.Battery) -> some View {
        Section("Battery") {
            HStack(spacing: 16) {
                ZStack {
                    Circle().stroke(.quaternary, lineWidth: 8)
                    Circle().trim(from: 0, to: Double(max(0, b.level)) / 100)
                        .stroke(levelColor(b.level), style: StrokeStyle(lineWidth: 8, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Text("\(b.level)%").font(.headline.monospacedDigit())
                }
                .frame(width: 64, height: 64)
                VStack(alignment: .leading, spacing: 4) {
                    Label(b.charging ? (b.full ? "Full" : "Charging · \(b.source)") : "On battery",
                          systemImage: b.charging ? "bolt.fill" : "battery.75")
                    Label("Health: \(b.health)", systemImage: b.health == "Good" ? "heart.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(b.health == "Good" ? Color.primary : Color.orange)
                }
            }
            LabeledContent("Temperature", value: String(format: "%.1f °C", b.tempC))
            LabeledContent("Voltage", value: String(format: "%.2f V", Double(b.voltageMv) / 1000))
            if b.currentMa != 0 { LabeledContent("Current", value: "\(b.currentMa) mA") }
            if let full = b.fullMah { LabeledContent("Full-charge capacity", value: "≈ \(full) mAh") }
            if let pct = b.capacityPercent, let design = b.designMah {
                LabeledContent("Capacity left", value: "\(pct)% of \(design) mAh")
            } else if b.fullMah != nil {
                Text("The glasses don't report a reliable original capacity, so wear can't be shown as a percentage.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func levelColor(_ level: Int) -> Color { level < 20 ? .red : level < 50 ? .orange : .green }

    private func bytes(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }
}
