using System;
using System.IO;
using Microsoft.Win32;

namespace DynamicIsland.Services
{
    /// <summary>
    /// Detects which app is using the microphone / camera right now, the same way Windows' own
    /// privacy indicator does: the ConsentStore keys get LastUsedTimeStop = 0 while in use.
    /// </summary>
    public static class PrivacyService
    {
        const string Root = @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\";

        /// <summary>Name of an app currently using the device, or null.</summary>
        public static string? InUse(string capability) // "microphone" or "webcam"
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(Root + capability);
                if (key == null) return null;
                foreach (var name in key.GetSubKeyNames())
                {
                    using var sub = key.OpenSubKey(name);
                    if (sub == null) continue;
                    if (name == "NonPackaged")
                    {
                        foreach (var exe in sub.GetSubKeyNames())
                        {
                            using var e = sub.OpenSubKey(exe);
                            if (Active(e)) return Pretty(exe);
                        }
                    }
                    else if (Active(sub)) return Pretty(name);
                }
            }
            catch { }
            return null;
        }

        static bool Active(RegistryKey? k) =>
            k != null && k.GetValue("LastUsedTimeStop") is long stop && stop == 0 &&
            k.GetValue("LastUsedTimeStart") is long start && start > 0;

        static string Pretty(string id)
        {
            // NonPackaged: "C:#Program Files#Zoom#bin#Zoom.exe"; packaged: "MSTeams_8wekyb3d8bbwe"
            var last = id.Contains('#') ? id[(id.LastIndexOf('#') + 1)..] : id.Split('_')[0];
            last = Path.GetFileNameWithoutExtension(last);
            if (last.Contains('.')) last = last[(last.LastIndexOf('.') + 1)..];
            return last.Length > 0 ? char.ToUpper(last[0]) + last[1..] : id;
        }
    }
}
