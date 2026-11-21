import CoreBluetooth
import Foundation

/// The phone's side of the BLE link: finds the glasses by the Worn Run
/// service, remembers them, reconnects automatically (also in the
/// background), and sends fixes, routes and commands.
@available(iOS 17.0, *)
@MainActor
final class RunLink: NSObject, ObservableObject {
    enum State: Equatable {
        case bluetoothOff, unauthorized, searching, connecting, ready(String)

        var description: String {
            switch self {
            case .bluetoothOff: return "Bluetooth is off"
            case .unauthorized: return "Bluetooth permission needed"
            case .searching: return "Looking for glasses…"
            case .connecting: return "Connecting…"
            case .ready(let name): return "Connected to \(name)"
            }
        }
    }

    struct GlassesStatus: Decodable, Equatable {
        let state: String
        let autoPaused: Bool
        let elapsedMs: Int
        let distanceM: Double
        let videoRecording: Bool?
        let gpsLatitude: Double?
        let gpsLongitude: Double?
        let gpsFixAgeMs: Int?
    }

    @Published private(set) var state: State = .searching
    @Published private(set) var status: GlassesStatus?
    @Published private(set) var routeProgress: Double?
    @Published private(set) var statusReceivedAt: Date?
    /// Called with the previous and new status each time the glasses report.
    var onStatus: ((GlassesStatus?, GlassesStatus) -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var writeQueue: [(CBUUID, Data)] = []
    private var writing = false
    private var routeChunksTotal = 0
    private var routeChunksSent = 0
    private static let knownKey = "glassesIdentifier"

    var isReady: Bool { if case .ready = state { return true } else { return false } }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "WornRunCentral"])
    }

    /// Forget the remembered glasses and search again.
    func forget() {
        UserDefaults.standard.removeObject(forKey: Self.knownKey)
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        startSearch()
    }

    @discardableResult
    func send(fix: Data) -> Bool {
        guard let p = peripheral, let c = characteristics[RunProtocol.fix], p.canSendWriteWithoutResponse else { return false }
        p.writeValue(fix, for: c, type: .withoutResponse)
        return true
    }

    func send(control fields: [String: Any]) {
        enqueue(RunProtocol.control, RunProtocol.control(fields))
    }

    func send(route: PlannedRoute) {
        let body = RunProtocol.encodeRoute(route)
        let max = peripheral?.maximumWriteValueLength(for: .withResponse) ?? 180
        let chunks = RunProtocol.chunks(body, maxChunk: min(max, 512))
        routeChunksTotal = chunks.count
        routeChunksSent = 0
        routeProgress = 0
        chunks.forEach { enqueue(RunProtocol.route, $0) }
    }

    // MARK: Writes with response go one at a time.

    private func enqueue(_ uuid: CBUUID, _ data: Data) {
        writeQueue.append((uuid, data))
        pump()
    }

    private func pump() {
        guard !writing, let p = peripheral, isReady, let (uuid, data) = writeQueue.first,
              let c = characteristics[uuid] else { return }
        writing = true
        p.writeValue(data, for: c, type: .withResponse)
    }

    private func startSearch() {
        guard central.state == .poweredOn else { return }
        if let id = UserDefaults.standard.string(forKey: Self.knownKey).flatMap(UUID.init),
           let known = central.retrievePeripherals(withIdentifiers: [id]).first {
            connect(known)
            return
        }
        if let connected = central.retrieveConnectedPeripherals(withServices: [RunProtocol.service]).first {
            connect(connected)
            return
        }
        state = .searching
        central.scanForPeripherals(withServices: [RunProtocol.service])
    }

    private func connect(_ p: CBPeripheral) {
        central.stopScan()
        peripheral = p
        p.delegate = self
        state = .connecting
        // Connection requests don't time out: this also waits for the glasses to come back in range.
        central.connect(p)
    }
}

@available(iOS 17.0, *)
extension RunLink: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn: startSearch()
            case .unauthorized: state = .unauthorized
            default: state = .bluetoothOff
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        MainActor.assumeIsolated {
            if let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
                peripheral = p
                p.delegate = self
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated { connect(peripheral) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated {
            UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Self.knownKey)
            peripheral.discoverServices([RunProtocol.service])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { connect(peripheral) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            characteristics = [:]
            writing = false
            status = nil // so the first status after reconnecting counts as new
            state = .connecting
            connect(peripheral) // reconnect whenever the glasses are back
        }
    }
}

@available(iOS 17.0, *)
extension RunLink: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard let service = peripheral.services?.first(where: { $0.uuid == RunProtocol.service }) else {
                // Remembered device no longer runs Worn Run.
                forget()
                return
            }
            peripheral.discoverCharacteristics(
                [RunProtocol.fix, RunProtocol.route, RunProtocol.control, RunProtocol.status], for: service)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            for c in service.characteristics ?? [] { characteristics[c.uuid] = c }
            if let s = characteristics[RunProtocol.status] { peripheral.setNotifyValue(true, for: s) }
            state = .ready(peripheral.name ?? "glasses")
            pump()
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            writing = false
            if !writeQueue.isEmpty { writeQueue.removeFirst() }
            if characteristic.uuid == RunProtocol.route, routeChunksTotal > 0 {
                routeChunksSent += 1
                routeProgress = Double(routeChunksSent) / Double(routeChunksTotal)
                if routeChunksSent == routeChunksTotal { routeProgress = nil }
            }
            pump()
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard characteristic.uuid == RunProtocol.status, let data = characteristic.value else { return }
            guard let new = try? JSONDecoder().decode(GlassesStatus.self, from: data) else { return }
            let old = status
            status = new
            statusReceivedAt = Date()
            onStatus?(old, new)
        }
    }
}
