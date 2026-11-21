import CoreBluetooth
import Foundation

/// Bluetooth link to Worn Hub on the glasses: status, app list, launching,
/// Wi-Fi setup, and the access token for the Wi-Fi features (reading it makes
/// iOS pair with the glasses once). Reconnects automatically.
@MainActor
final class HubLink: NSObject, ObservableObject {
    enum State: Equatable {
        case bluetoothOff, unauthorized, searching, connecting, connected(String)

        var description: String {
            switch self {
            case .bluetoothOff: return "Bluetooth is off"
            case .unauthorized: return "Bluetooth permission needed"
            case .searching: return "Looking for glasses…"
            case .connecting: return "Connecting…"
            case .connected(let name): return "Connected to \(name)"
            }
        }
    }

    @Published private(set) var state: State = .searching
    @Published private(set) var status: HubStatus?
    @Published private(set) var apps: [GlassesApp] = []
    @Published private(set) var lastResult: (ok: Bool, message: String)?
    /// Needed for live view and media; nil until the glasses are paired.
    @Published private(set) var token: String? = UserDefaults.standard.string(forKey: "hubToken")
    @Published private(set) var pairingError: String?

    var isConnected: Bool { if case .connected = state { return true } else { return false } }

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var chars: [CBUUID: CBCharacteristic] = [:]
    private var assembler = ChunkAssembler()
    private var writeQueue: [Data] = []
    private var writing = false
    private static let knownKey = "hubGlasses"

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "WornHubCentral"])
    }

    // MARK: Commands

    func refreshApps() { send(["cmd": "listApps"]) }
    func refreshStatus() { send(["cmd": "status"]) }
    func launch(_ app: GlassesApp) { send(["cmd": "launch", "package": app.package]) }
    func goHome() { send(["cmd": "home"]) }
    func joinWifi(ssid: String, password: String) { send(["cmd": "joinWifi", "ssid": ssid, "password": password]) }

    /// Reads the token again (prompts pairing if the glasses aren't paired yet).
    func pair() {
        pairingError = nil
        if let p = peripheral, let c = chars[HubProtocol.token] { p.readValue(for: c) }
    }

    func forget() {
        UserDefaults.standard.removeObject(forKey: Self.knownKey)
        UserDefaults.standard.removeObject(forKey: "hubToken")
        token = nil
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        startSearch()
    }

    private func send(_ fields: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: fields) else { return }
        let max = peripheral?.maximumWriteValueLength(for: .withResponse) ?? 180
        writeQueue.append(contentsOf: HubProtocol.split(data, maxChunk: min(max, 512)))
        pump()
    }

    private func pump() {
        guard !writing, isConnected, let p = peripheral, let c = chars[HubProtocol.cmd], let next = writeQueue.first else { return }
        writing = true
        p.writeValue(next, for: c, type: .withResponse)
    }

    private func startSearch() {
        guard central.state == .poweredOn else { return }
        if let id = UserDefaults.standard.string(forKey: Self.knownKey).flatMap(UUID.init),
           let known = central.retrievePeripherals(withIdentifiers: [id]).first {
            return connect(known)
        }
        if let connected = central.retrieveConnectedPeripherals(withServices: [HubProtocol.service]).first {
            return connect(connected)
        }
        state = .searching
        central.scanForPeripherals(withServices: [HubProtocol.service])
    }

    private func connect(_ p: CBPeripheral) {
        central.stopScan()
        peripheral = p
        p.delegate = self
        state = .connecting
        central.connect(p) // never times out: also waits for the glasses to come back
    }

    private func handle(_ event: HubEvent) {
        switch event {
        case .status(let s): status = s
        case .apps(let a): apps = a
        case .result(_, let ok, let message): lastResult = (ok, message)
        }
    }
}

extension HubLink: CBCentralManagerDelegate {
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
            peripheral.discoverServices([HubProtocol.service])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { connect(peripheral) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            chars = [:]
            writing = false
            writeQueue = []
            assembler = ChunkAssembler()
            state = .connecting
            connect(peripheral)
        }
    }
}

extension HubLink: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard let service = peripheral.services?.first(where: { $0.uuid == HubProtocol.service }) else {
                forget() // remembered device no longer runs Worn Hub
                return
            }
            peripheral.discoverCharacteristics([HubProtocol.cmd, HubProtocol.evt, HubProtocol.token], for: service)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            for c in service.characteristics ?? [] { chars[c.uuid] = c }
            if let e = chars[HubProtocol.evt] { peripheral.setNotifyValue(true, for: e) }
            state = .connected(peripheral.name ?? "glasses")
            pair()
            refreshStatus()
            refreshApps()
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            writing = false
            if !writeQueue.isEmpty { writeQueue.removeFirst() }
            pump()
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard let value = characteristic.value else {
                if characteristic.uuid == HubProtocol.token, let error {
                    pairingError = "Pairing didn't finish: \(error.localizedDescription)"
                }
                return
            }
            switch characteristic.uuid {
            case HubProtocol.token:
                if let t = String(data: value, encoding: .utf8), !t.isEmpty {
                    token = t
                    UserDefaults.standard.set(t, forKey: "hubToken")
                }
            case HubProtocol.evt:
                if let message = assembler.accept(value), let event = HubEvent.decode(message) { handle(event) }
            default:
                break
            }
        }
    }
}
