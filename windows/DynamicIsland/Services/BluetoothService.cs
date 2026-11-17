using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace DynamicIsland.Services
{
    public sealed record BtEvent(string Name, bool Connected, int? Battery);

    /// <summary>
    /// Reports paired Bluetooth (classic + LE) devices connecting / disconnecting, e.g. headphones.
    /// Only real transitions after the initial enumeration; flaps reversed within 3 s are ignored.
    /// </summary>
    public sealed class BluetoothService : IDisposable
    {
        const string IsConnectedKey = "System.Devices.Aep.IsConnected";
        const string ContainerKey = "System.Devices.Aep.ContainerId";
        const string BatteryKey = "{104EA319-6EE2-4701-BD47-8DDBF425BBE5} 2";
        static readonly TimeSpan Debounce = TimeSpan.FromSeconds(3);

        sealed class Dev
        {
            public string Name = "";
            public bool Connected;          // last confirmed state
            public Timer? Pending;          // transition waiting out the debounce window
            public bool PendingState;
        }

        readonly object _gate = new();
        readonly Dictionary<string, Dev> _devs = new();
        readonly List<DeviceWatcher> _watchers = new();
        readonly HashSet<DeviceWatcher> _ready = new();
        bool _disposed;

        /// <summary>Raised on a background thread.</summary>
        public event Action<BtEvent>? Changed;

        public void Start()
        {
            try
            {
                var props = new[] { IsConnectedKey, ContainerKey };
                foreach (var aqs in new[]
                         {
                             BluetoothDevice.GetDeviceSelectorFromPairingState(true),
                             BluetoothLEDevice.GetDeviceSelectorFromPairingState(true),
                         })
                {
                    var w = DeviceInformation.CreateWatcher(aqs, props, DeviceInformationKind.AssociationEndpoint);
                    w.Added += OnAdded;
                    w.Updated += OnUpdated;
                    w.Removed += OnRemoved;
                    w.EnumerationCompleted += (s, _) => { lock (_gate) _ready.Add(s); };
                    _watchers.Add(w);
                    w.Start();
                }
            }
            catch { /* no Bluetooth radio / API unavailable */ }
        }

        static bool? ReadConnected(IReadOnlyDictionary<string, object> props) =>
            props.TryGetValue(IsConnectedKey, out var v) && v is bool b ? b : null;

        void OnAdded(DeviceWatcher w, DeviceInformation info)
        {
            bool connected = ReadConnected(info.Properties) ?? false;
            lock (_gate)
            {
                if (_devs.TryGetValue(info.Id, out var existing))
                {
                    if (!string.IsNullOrEmpty(info.Name)) existing.Name = info.Name;
                    return;
                }
                // A device appearing already connected after startup (e.g. freshly paired) counts as a connect.
                var dev = new Dev { Name = info.Name, Connected = _ready.Contains(w) ? !connected : connected };
                _devs[info.Id] = dev;
                if (_ready.Contains(w) && connected) Transition(info.Id, dev, true);
            }
        }

        void OnUpdated(DeviceWatcher w, DeviceInformationUpdate update)
        {
            var c = ReadConnected(update.Properties);
            if (c == null) return;
            lock (_gate)
            {
                if (!_devs.TryGetValue(update.Id, out var dev)) return;
                if (!_ready.Contains(w)) { dev.Connected = c.Value; return; } // still enumerating: just record
                Transition(update.Id, dev, c.Value);
            }
        }

        void OnRemoved(DeviceWatcher w, DeviceInformationUpdate update)
        {
            lock (_gate)
            {
                if (_devs.Remove(update.Id, out var dev)) dev.Pending?.Dispose();
            }
        }

        /// <summary>Call under _gate. Starts / cancels the debounce window.</summary>
        void Transition(string id, Dev dev, bool now)
        {
            if (dev.Pending != null)
            {
                if (dev.PendingState == now) return; // same transition reported twice
                // Reversed within the window: it was a flap. Drop it.
                dev.Pending.Dispose();
                dev.Pending = null;
                return;
            }
            if (now == dev.Connected) return;

            dev.PendingState = now;
            dev.Pending = new Timer(_ => _ = ConfirmAsync(id), null, Debounce, Timeout.InfiniteTimeSpan);
        }

        async Task ConfirmAsync(string id)
        {
            string name;
            bool state;
            lock (_gate)
            {
                if (_disposed || !_devs.TryGetValue(id, out var dev) || dev.Pending == null) return;
                dev.Pending.Dispose();
                dev.Pending = null;
                if (dev.PendingState == dev.Connected) return;
                dev.Connected = dev.PendingState;
                name = string.IsNullOrWhiteSpace(dev.Name) ? "Bluetooth device" : dev.Name;
                state = dev.Connected;
            }
            int? battery = state ? await BatteryAsync(id).ConfigureAwait(false) : null;
            try { Changed?.Invoke(new BtEvent(name, state, battery)); } catch { }
        }

        /// <summary>Best effort: battery % from the PnP device nodes sharing the endpoint's container.</summary>
        static async Task<int?> BatteryAsync(string aepId)
        {
            try
            {
                var aep = await DeviceInformation.CreateFromIdAsync(aepId, new[] { ContainerKey }, DeviceInformationKind.AssociationEndpoint);
                if (!aep.Properties.TryGetValue(ContainerKey, out var c) || c is not Guid container) return null;
                string aqs = $"System.Devices.ContainerId:=\"{{{container}}}\"";
                var nodes = await DeviceInformation.FindAllAsync(aqs, new[] { BatteryKey }, DeviceInformationKind.Device);
                foreach (var node in nodes)
                {
                    if (node.Properties.TryGetValue(BatteryKey, out var v) && v != null)
                    {
                        int pct = Convert.ToInt32(v);
                        if (pct is >= 0 and <= 100) return pct;
                    }
                }
            }
            catch { }
            return null;
        }

        public void Dispose()
        {
            lock (_gate)
            {
                _disposed = true;
                foreach (var d in _devs.Values) d.Pending?.Dispose();
                _devs.Clear();
            }
            foreach (var w in _watchers)
            {
                try
                {
                    if (w.Status is DeviceWatcherStatus.Started or DeviceWatcherStatus.EnumerationCompleted) w.Stop();
                }
                catch { }
            }
            _watchers.Clear();
        }
    }
}
