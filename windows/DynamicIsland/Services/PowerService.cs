using System;
using Windows.System.Power;

namespace DynamicIsland.Services
{
    /// <summary>Battery / charger / energy-saver events from Windows.</summary>
    public sealed class PowerService
    {
        public event Action<bool, int>? PluggedChanged;   // (plugged, percent)
        public event Action<int>? PercentChanged;
        public event Action<bool>? EnergySaverChanged;

        bool _plugged;

        public bool HasBattery => PowerManager.BatteryStatus != BatteryStatus.NotPresent;
        public int Percent => PowerManager.RemainingChargePercent;
        public bool Plugged => PowerManager.PowerSupplyStatus != PowerSupplyStatus.NotPresent;

        public void Start()
        {
            try
            {
                _plugged = Plugged;
                PowerManager.PowerSupplyStatusChanged += (_, _) =>
                {
                    bool p = Plugged;
                    if (p != _plugged) { _plugged = p; PluggedChanged?.Invoke(p, Percent); }
                };
                PowerManager.RemainingChargePercentChanged += (_, _) => PercentChanged?.Invoke(Percent);
                PowerManager.EnergySaverStatusChanged += (_, _) =>
                    EnergySaverChanged?.Invoke(PowerManager.EnergySaverStatus == EnergySaverStatus.On);
            }
            catch { }
        }
    }
}
