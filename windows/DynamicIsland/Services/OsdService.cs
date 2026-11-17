using System;
using System.Management;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace DynamicIsland.Services
{
    /// <summary>Watches system volume (Core Audio) and screen brightness (WMI) so the island can show its own OSD.</summary>
    public sealed class OsdService : IMMNotificationClient, IDisposable
    {
        MMDeviceEnumerator? _enum;
        MMDevice? _device;
        ManagementEventWatcher? _brightness;

        public event Action<int, bool>? VolumeChanged;   // (percent, muted), background thread
        public event Action<int>? BrightnessChanged;     // percent, background thread
        public event Action? DefaultDeviceChanged;       // background thread; caller re-hooks on UI thread

        public void Start()
        {
            try
            {
                _enum = new MMDeviceEnumerator();
                _enum.RegisterEndpointNotificationCallback(this);
                HookDefaultDevice();
            }
            catch { }

            try
            {
                _brightness = new ManagementEventWatcher(new ManagementScope(@"root\wmi"),
                    new EventQuery("SELECT * FROM WmiMonitorBrightnessEvent"));
                _brightness.EventArrived += (_, e) =>
                {
                    try { BrightnessChanged?.Invoke(Convert.ToInt32(e.NewEvent.Properties["Brightness"].Value)); } catch { }
                };
                _brightness.Start();
            }
            catch { /* external monitor without WMI brightness */ }
        }

        public void HookDefaultDevice()
        {
            try
            {
                if (_device != null) _device.AudioEndpointVolume.OnVolumeNotification -= OnVolume;
                _device = _enum?.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                if (_device != null) _device.AudioEndpointVolume.OnVolumeNotification += OnVolume;
            }
            catch { _device = null; }
        }

        /// <summary>Current output loudness 0..1 — drives the visualizer and Drip's dancing.</summary>
        public float Peak
        {
            get { try { return _device?.AudioMeterInformation.MasterPeakValue ?? 0; } catch { return 0; } }
        }

        /// <summary>Scroll-on-island volume control.</summary>
        public void NudgeVolume(float delta)
        {
            try
            {
                var v = _device?.AudioEndpointVolume;
                if (v == null) return;
                if (v.Mute && delta > 0) v.Mute = false;
                v.MasterVolumeLevelScalar = Math.Clamp(v.MasterVolumeLevelScalar + delta, 0f, 1f);
            }
            catch { }
        }

        void OnVolume(AudioVolumeNotificationData d) =>
            VolumeChanged?.Invoke((int)Math.Round(d.MasterVolume * 100), d.Muted);

        public void Dispose()
        {
            try { _brightness?.Stop(); _brightness?.Dispose(); } catch { }
            try { _enum?.UnregisterEndpointNotificationCallback(this); } catch { }
        }

        // IMMNotificationClient
        public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
        {
            if (flow == DataFlow.Render && role == Role.Multimedia) DefaultDeviceChanged?.Invoke();
        }
        public void OnDeviceStateChanged(string deviceId, DeviceState newState) { }
        public void OnDeviceAdded(string pwstrDeviceId) { }
        public void OnDeviceRemoved(string deviceId) { }
        public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key) { }
    }
}
