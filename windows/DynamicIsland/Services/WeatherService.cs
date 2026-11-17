using System;
using System.Collections.Concurrent;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;

namespace DynamicIsland.Services
{
    public enum WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Thunder }

    public sealed record WeatherInfo(string Place, double TempC, double HighC, double LowC, WeatherKind Kind, string Description, bool IsDay);

    /// <summary>Current weather for a city via the free Open-Meteo APIs (no key). Cached 20 min per city.</summary>
    public sealed class WeatherService
    {
        static readonly HttpClient Http = CreateClient();
        readonly ConcurrentDictionary<string, (DateTime at, WeatherInfo? info)> _cache = new();

        static HttpClient CreateClient()
        {
            var h = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            h.DefaultRequestHeaders.UserAgent.ParseAdd("DynamicIsland/2.0");
            return h;
        }

        /// <summary>Never throws. Null for an empty city or any failure.</summary>
        public async Task<WeatherInfo?> GetAsync(string city)
        {
            if (string.IsNullOrWhiteSpace(city)) return null;
            string key = city.Trim().ToLowerInvariant();

            if (_cache.TryGetValue(key, out var hit))
            {
                var age = DateTime.UtcNow - hit.at;
                if (hit.info != null && age < TimeSpan.FromMinutes(20)) return hit.info;
                if (hit.info == null && age < TimeSpan.FromMinutes(2)) return null; // don't hammer after a failure
            }

            WeatherInfo? info = null;
            try { info = await FetchAsync(city.Trim()).ConfigureAwait(false); } catch { }
            _cache[key] = (DateTime.UtcNow, info);
            return info;
        }

        /// <summary>6 digits = Indian PIN code.</summary>
        public static bool IsPin(string q) => q.Trim().Length == 6 && q.Trim().All(char.IsDigit);

        static async Task<WeatherInfo?> FetchAsync(string query)
        {
            var inv = CultureInfo.InvariantCulture;
            var loc = IsPin(query) ? await LocatePinAsync(query.Trim()).ConfigureAwait(false) : null;
            loc ??= await LocateCityAsync(query).ConfigureAwait(false);
            if (loc is not var (lat, lon, name)) return null;

            string url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat.ToString(inv) + "&longitude=" + lon.ToString(inv) +
                         "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
            using var fc = JsonDocument.Parse(await Http.GetStringAsync(url).ConfigureAwait(false));
            var cur = fc.RootElement.GetProperty("current");
            var daily = fc.RootElement.GetProperty("daily");

            double temp = cur.GetProperty("temperature_2m").GetDouble();
            int code = cur.GetProperty("weather_code").GetInt32();
            bool isDay = cur.TryGetProperty("is_day", out var d) && d.GetInt32() == 1;
            double hi = daily.GetProperty("temperature_2m_max")[0].GetDouble();
            double lo = daily.GetProperty("temperature_2m_min")[0].GetDouble();

            var (kind, desc) = Describe(code, isDay);
            return new WeatherInfo(name, temp, hi, lo, kind, desc, isDay);
        }

        static async Task<(double lat, double lon, string name)?> LocateCityAsync(string city)
        {
            using var geo = JsonDocument.Parse(await Http.GetStringAsync(
                $"https://geocoding-api.open-meteo.com/v1/search?name={Uri.EscapeDataString(city)}&count=1").ConfigureAwait(false));
            if (!geo.RootElement.TryGetProperty("results", out var results) || results.GetArrayLength() == 0) return null;
            var place = results[0];
            return (place.GetProperty("latitude").GetDouble(), place.GetProperty("longitude").GetDouble(),
                    place.TryGetProperty("name", out var n) ? n.GetString() ?? city : city);
        }

        /// <summary>
        /// Indian PIN code → coordinates. Open-Meteo has no PIN codes, so: OpenStreetMap's Nominatim
        /// (exact PIN area), falling back to India Post's PIN directory (district) + Open-Meteo.
        /// </summary>
        static async Task<(double lat, double lon, string name)?> LocatePinAsync(string pin)
        {
            // 1) The official area name from India Post: the PIN's own (Head/Sub) post office, e.g. 382405 → "Narol".
            string? area = null, district = null, state = null;
            try
            {
                using var doc = JsonDocument.Parse(await Http.GetStringAsync($"https://api.postalpincode.in/pincode/{pin}").ConfigureAwait(false));
                var offices = doc.RootElement[0].GetProperty("PostOffice").EnumerateArray().ToList();
                var main = offices.FirstOrDefault(o => o.GetProperty("BranchType").GetString() == "Head Post Office");
                if (main.ValueKind == JsonValueKind.Undefined) main = offices.FirstOrDefault(o => o.GetProperty("BranchType").GetString() == "Sub Post Office");
                if (main.ValueKind == JsonValueKind.Undefined && offices.Count > 0) main = offices[0];
                if (main.ValueKind != JsonValueKind.Undefined)
                {
                    area = main.GetProperty("Name").GetString();
                    district = main.GetProperty("District").GetString();
                    state = main.GetProperty("State").GetString();
                }
            }
            catch { }

            // 2) Coordinates: that area by name, else the PIN's centre, else the district.
            if (area != null)
            {
                var spot = await NominatimAsync($"q={Uri.EscapeDataString($"{area}, {district}, {state}, India")}").ConfigureAwait(false);
                if (spot is var (lat, lon)) return (lat, lon, $"{area}, {district}");
            }
            var pinSpot = await NominatimAsync($"postalcode={pin}&country=India").ConfigureAwait(false);
            if (pinSpot is var (plat, plon)) return (plat, plon, area != null ? $"{area}, {district}" : $"PIN {pin}");
            if (district != null && await LocateCityAsync(district).ConfigureAwait(false) is var (dlat, dlon, _))
                return (dlat, dlon, area != null ? $"{area}, {district}" : district);
            return null;
        }

        static async Task<(double lat, double lon)?> NominatimAsync(string query)
        {
            try
            {
                var inv = CultureInfo.InvariantCulture;
                using var doc = JsonDocument.Parse(await Http.GetStringAsync(
                    $"https://nominatim.openstreetmap.org/search?{query}&format=json&limit=1").ConfigureAwait(false));
                if (doc.RootElement.ValueKind != JsonValueKind.Array || doc.RootElement.GetArrayLength() == 0) return null;
                var p = doc.RootElement[0];
                return (double.Parse(p.GetProperty("lat").GetString()!, inv), double.Parse(p.GetProperty("lon").GetString()!, inv));
            }
            catch { return null; }
        }

        /// <summary>WMO weather code → kind + short label.</summary>
        static (WeatherKind, string) Describe(int code, bool day) => code switch
        {
            0 => (WeatherKind.Clear, day ? "Sunny" : "Clear"),
            1 => (WeatherKind.PartlyCloudy, "Mostly clear"),
            2 => (WeatherKind.PartlyCloudy, "Partly cloudy"),
            3 => (WeatherKind.Cloudy, "Overcast"),
            45 or 48 => (WeatherKind.Fog, "Fog"),
            51 or 53 or 55 => (WeatherKind.Drizzle, "Drizzle"),
            56 or 57 => (WeatherKind.Drizzle, "Freezing drizzle"),
            61 => (WeatherKind.Rain, "Light rain"),
            63 => (WeatherKind.Rain, "Rain"),
            65 => (WeatherKind.Rain, "Heavy rain"),
            66 or 67 => (WeatherKind.Rain, "Freezing rain"),
            71 or 73 or 75 or 77 => (WeatherKind.Snow, "Snow"),
            80 or 81 => (WeatherKind.Rain, "Showers"),
            82 => (WeatherKind.Rain, "Heavy showers"),
            85 or 86 => (WeatherKind.Snow, "Snow showers"),
            95 => (WeatherKind.Thunder, "Thunderstorm"),
            96 or 99 => (WeatherKind.Thunder, "Thunderstorm, hail"),
            _ => (WeatherKind.Cloudy, "Cloudy"),
        };
    }
}
