using System;
using System.Linq;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace DynamicIsland.Services
{
    /// <summary>Cheap CPU / RAM / network sampling. Only called while the System tab is open.</summary>
    public sealed class SystemStats
    {
        [StructLayout(LayoutKind.Sequential)] struct FILETIME { public uint Low, High; public ulong V => ((ulong)High << 32) | Low; }
        [DllImport("kernel32.dll")] static extern bool GetSystemTimes(out FILETIME idle, out FILETIME kernel, out FILETIME user);

        [StructLayout(LayoutKind.Sequential)]
        struct MEMORYSTATUSEX
        {
            public uint dwLength, dwMemoryLoad;
            public ulong ullTotalPhys, ullAvailPhys, ullTotalPageFile, ullAvailPageFile, ullTotalVirtual, ullAvailVirtual, ullAvailExtendedVirtual;
        }
        [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX m);

        ulong _idle, _total;
        long _rx, _tx;
        DateTime _netStamp;

        public double Cpu { get; private set; }          // 0..1
        public double Ram { get; private set; }          // 0..1
        public double RamUsedGb { get; private set; }
        public double RamTotalGb { get; private set; }
        public double DownBps { get; private set; }
        public double UpBps { get; private set; }

        public void Sample()
        {
            if (GetSystemTimes(out var i, out var k, out var u))
            {
                ulong idle = i.V, total = k.V + u.V;
                if (_total != 0 && total > _total)
                    Cpu = Math.Clamp(1.0 - (double)(idle - _idle) / (total - _total), 0, 1);
                _idle = idle; _total = total;
            }

            var m = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
            if (GlobalMemoryStatusEx(ref m))
            {
                RamTotalGb = m.ullTotalPhys / 1073741824.0;
                RamUsedGb = (m.ullTotalPhys - m.ullAvailPhys) / 1073741824.0;
                Ram = m.dwMemoryLoad / 100.0;
            }

            try
            {
                long rx = 0, tx = 0;
                foreach (var n in NetworkInterface.GetAllNetworkInterfaces().Where(n =>
                             n.OperationalStatus == OperationalStatus.Up &&
                             n.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
                             n.NetworkInterfaceType != NetworkInterfaceType.Tunnel))
                {
                    var s = n.GetIPStatistics();
                    rx += s.BytesReceived; tx += s.BytesSent;
                }
                var now = DateTime.UtcNow;
                if (_netStamp != default)
                {
                    double sec = Math.Max(0.2, (now - _netStamp).TotalSeconds);
                    DownBps = Math.Max(0, (rx - _rx) / sec);
                    UpBps = Math.Max(0, (tx - _tx) / sec);
                }
                _rx = rx; _tx = tx; _netStamp = now;
            }
            catch { }
        }

        public static string Speed(double bps) =>
            bps >= 1048576 ? $"{bps / 1048576:0.0} MB/s" : bps >= 1024 ? $"{bps / 1024:0} KB/s" : $"{bps:0} B/s";
    }
}
