using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.Json;
using System.Threading;

namespace MetroWv2Helper
{
    internal record YouTubeCredentials(string cookie, string visitorData, string dataSyncId, string authUser);

    internal sealed class LoginCompletion
    {
        private int _claimed;
        public bool IsCompleted => Volatile.Read(ref _claimed) != 0;

        public bool TryWrite<T>(T payload, Action<T> write)
        {
            if (Interlocked.CompareExchange(ref _claimed, 1, 0) != 0) return false;
            try
            {
                write(payload);
                return true;
            }
            catch
            {
                Volatile.Write(ref _claimed, 0);
                throw;
            }
        }

        // Cookies zingmp3.vn sets only for a logged-in session; the visitor cookies (zmp3_rqid, zmp3_app_version) never
        // match. Mirrors Android's ZingCookieStore.SESSION_COOKIE_NAMES; the app confirms the login via the profile API.
        private static readonly string[] ZingSessionCookieNames = { "zmp3_sid", "zpsid" };

        /// <summary>The full zingmp3.vn cookie header once a session cookie is present, otherwise null.</summary>
        public static string? ReadZingCookieHeader(IReadOnlyDictionary<string, string> cookies)
        {
            if (!ZingSessionCookieNames.Any(name => cookies.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value))) return null;
            return string.Join("; ", cookies.Where(kv => !string.IsNullOrEmpty(kv.Key)).Select(kv => $"{kv.Key}={kv.Value}"));
        }

        public static YouTubeCredentials? ReadYouTubeCredentials(IReadOnlyDictionary<string, string> cookies, string snapshot)
        {
            if (!new[] { "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID" }
                .Any(name => cookies.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value))) return null;
            try
            {
                using var document = JsonDocument.Parse(snapshot);
                var root = document.RootElement;
                if (root.ValueKind != JsonValueKind.Object) return null;
                string? Read(string name) => root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
                    ? value.GetString() : null;
                var visitor = Read("visitorData");
                var dataSyncId = Read("dataSyncId")?.Split("||", StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
                var authUser = Read("authUser");
                if (string.IsNullOrWhiteSpace(visitor) || string.IsNullOrWhiteSpace(dataSyncId) ||
                    !int.TryParse(authUser, NumberStyles.None, CultureInfo.InvariantCulture, out var index) || index < 0) return null;
                return new YouTubeCredentials(string.Join("; ", cookies.Select(kv => $"{kv.Key}={kv.Value}")), visitor, dataSyncId, authUser!);
            }
            catch (JsonException)
            {
                return null;
            }
        }
    }
}
