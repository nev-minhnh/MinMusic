using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Timer = System.Windows.Forms.Timer;

namespace MetroWv2Helper
{
    static class Program
    {
        private static string? _outputPath;
        private static string _provider = "youtube";
        private static string? _loginUrl;
        private static readonly ZingChromeText _zingText = new();

        [STAThread]
        static void Main(string[] args)
        {
            // Parse arguments
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--output" && i + 1 < args.Length)
                {
                    _outputPath = args[i + 1];
                }
                else if (args[i] == "--login-url" && i + 1 < args.Length)
                {
                    var uri = new Uri(args[i + 1]);
                    if (uri.Scheme != "https" || (uri.Host != "music.youtube.com" && uri.Host != "www.youtube.com" && uri.Host != "accounts.google.com"))
                        throw new ArgumentException("Unsupported login URL");
                    _loginUrl = uri.AbsoluteUri;
                }
                else if (args[i] == "--provider" && i + 1 < args.Length)
                {
                    _provider = args[i + 1].ToLowerInvariant();
                }
                // Localized UI text for the Zing window chrome (display only; never credentials).
                else if (args[i] == "--title" && i + 1 < args.Length) _zingText.Title = args[i + 1];
                else if (args[i] == "--read-only-label" && i + 1 < args.Length) _zingText.ReadOnly = args[i + 1];
                else if (args[i] == "--banner" && i + 1 < args.Length) _zingText.Banner = args[i + 1];
                else if (args[i] == "--status" && i + 1 < args.Length) _zingText.Status = args[i + 1];
                else if (args[i] == "--open-browser-label" && i + 1 < args.Length) _zingText.OpenInBrowser = args[i + 1];
                else if (args[i] == "--cancel-label" && i + 1 < args.Length) _zingText.Cancel = args[i + 1];
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new LoginForm(_outputPath, _provider, _loginUrl, _zingText));
        }
    }

    /// <summary>Text for the Zing login window (layout spec 6); MinMusic passes it localized, these are the fallbacks.</summary>
    public sealed class ZingChromeText
    {
        public string Title { get; set; } = "Sign in to Zing MP3 - MinMusic";
        public string ReadOnly { get; set; } = "Read only";
        public string Banner { get; set; } = "You sign in on Zing's own page. MinMusic never sees your password and keeps only the session cookie, encrypted on this device.";
        public string Status { get; set; } = "Waiting for sign-in...";
        public string OpenInBrowser { get; set; } = "Open in browser";
        public string Cancel { get; set; } = "Cancel";
    }

    public class LoginForm : Form
    {
        private WebView2 _webView;
        private readonly string? _outputPath;
        private string _provider;
        private readonly string? _loginUrl;
        private readonly LoginCompletion _completion = new();
        private readonly Timer _extractionTimer = new() { Interval = 500 };
        private bool _extracting;
        private string? _userDataFolder;
        private TextBox? _addressBox;

        public LoginForm(string? outputPath, string provider, string? loginUrl, ZingChromeText zingText)
        {
            _outputPath = outputPath;
            _provider = provider;
            _loginUrl = loginUrl;
            Text = provider == "spotify" ? "MinMusic login for Spotify" : "MinMusic login for YouTube Music";
            Size = new System.Drawing.Size(800, 800);
            StartPosition = FormStartPosition.CenterScreen;

            _webView = new WebView2 { Dock = DockStyle.Fill };
            if (provider == "zing")
            {
                BuildZingChrome(zingText);
            }
            else
            {
                Controls.Add(_webView);
            }

            InitializeAsync();
        }

        // ---- Zing window chrome (MinMusic desktop layout spec 6) ----
        // 560x760 (min 480x600), not maximizable; read-only address bar (lock + URL + "Read only" + reload), privacy
        // banner, the WebView2 surface with radius 16, and a footer with the status, "Open in browser" and "Cancel".
        // Purely visual: the cookie extraction below is unchanged.

        private sealed class ZingPalette
        {
            public Color Background, Surface1, Ink, Ink2, Ink3, Line, Accent, AccentContainer, OnAccentContainer;
        }

        private static ZingPalette CurrentPalette()
        {
            bool light = false;
            try
            {
                using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
                light = key?.GetValue("AppsUseLightTheme") is int value && value != 0;
            }
            catch
            {
                // Dark is the MinMusic default.
            }
            return light
                ? new ZingPalette
                {
                    Background = ColorTranslator.FromHtml("#F6F7F8"), Surface1 = ColorTranslator.FromHtml("#ECEEF1"),
                    Ink = ColorTranslator.FromHtml("#0B0B0B"), Ink2 = ColorTranslator.FromHtml("#434445"), Ink3 = ColorTranslator.FromHtml("#6E6F70"),
                    Line = ColorTranslator.FromHtml("#D9DADB"), Accent = ColorTranslator.FromHtml("#3550C8"),
                    AccentContainer = ColorTranslator.FromHtml("#E1E7FF"), OnAccentContainer = ColorTranslator.FromHtml("#1B2A6B"),
                }
                : new ZingPalette
                {
                    Background = ColorTranslator.FromHtml("#0B0B0B"), Surface1 = ColorTranslator.FromHtml("#151617"),
                    Ink = ColorTranslator.FromHtml("#F2F4F3"), Ink2 = ColorTranslator.FromHtml("#BABCBB"), Ink3 = ColorTranslator.FromHtml("#8A8C8B"),
                    Line = ColorTranslator.FromHtml("#2A2B2C"), Accent = ColorTranslator.FromHtml("#9DB0FF"),
                    AccentContainer = ColorTranslator.FromHtml("#1D2547"), OnAccentContainer = ColorTranslator.FromHtml("#DCE2FF"),
                };
        }

        // Segoe MDL2 Assets glyph (lock, refresh, shield), written as a code point to keep this file ASCII.
        private static string Glyph(int codePoint) => ((char)codePoint).ToString();

        [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

        private static System.Drawing.Drawing2D.GraphicsPath RoundedRect(Rectangle bounds, int radius)
        {
            var path = new System.Drawing.Drawing2D.GraphicsPath();
            int d = Math.Max(1, Math.Min(radius * 2, Math.Min(bounds.Width, bounds.Height)));
            path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
            path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
            path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
            path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        private static void RoundCorners(Control control, int radius)
        {
            void Apply()
            {
                if (control.Width <= 0 || control.Height <= 0) return;
                try
                {
                    using var path = RoundedRect(new Rectangle(0, 0, control.Width, control.Height), radius);
                    var old = control.Region;
                    control.Region = new Region(path);
                    old?.Dispose();
                }
                catch
                {
                    // Square corners are an acceptable fallback.
                }
            }
            control.SizeChanged += (_, _) => Apply();
            Apply();
        }

        private static Button FlatButton(string text, ZingPalette palette, Color foreground, bool outlined)
        {
            var button = new Button
            {
                Text = text,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                FlatStyle = FlatStyle.Flat,
                ForeColor = foreground,
                BackColor = palette.Background,
                Padding = new Padding(10, 2, 10, 2),
                Margin = new Padding(8, 0, 0, 0),
                Anchor = AnchorStyles.Right,
                Cursor = Cursors.Default,
            };
            button.FlatAppearance.BorderSize = outlined ? 1 : 0;
            button.FlatAppearance.BorderColor = palette.Line;
            button.FlatAppearance.MouseOverBackColor = palette.Surface1;
            button.FlatAppearance.MouseDownBackColor = palette.Surface1;
            return button;
        }

        private void BuildZingChrome(ZingChromeText text)
        {
            var palette = CurrentPalette();
            var bodyFont = new Font("Segoe UI", 10.5f, FontStyle.Regular, GraphicsUnit.Point);
            var smallFont = new Font("Segoe UI", 9f, FontStyle.Regular, GraphicsUnit.Point);
            var iconFont = new Font("Segoe MDL2 Assets", 10f, FontStyle.Regular, GraphicsUnit.Point);

            Text = text.Title;
            Size = new Size(560, 760);
            MinimumSize = new Size(480, 600);
            MaximizeBox = false;
            BackColor = palette.Background;
            ForeColor = palette.Ink;
            Font = bodyFont;
            HandleCreated += (_, _) =>
            {
                // Dark caption when Windows is in dark app mode (DWMWA_USE_IMMERSIVE_DARK_MODE = 20).
                int dark = palette.Background.GetBrightness() < 0.5f ? 1 : 0;
                try { DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int)); } catch { }
            };

            var root = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 1,
                RowCount = 4,
                BackColor = palette.Background,
                Padding = new Padding(16, 8, 16, 0),
            };
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 52f));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 56f));

            // Read-only address bar: lock, URL, "Read only", reload. 40 high, radius 20, surface1.
            var address = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 40,
                ColumnCount = 4,
                RowCount = 1,
                BackColor = palette.Surface1,
                Padding = new Padding(12, 0, 4, 0),
                Margin = new Padding(0, 0, 0, 12),
            };
            address.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            address.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            address.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            address.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            address.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
            var lockIcon = new Label { Text = Glyph(0xE72E), Font = iconFont, ForeColor = palette.Ink2, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 0, 8, 0) };
            _addressBox = new TextBox
            {
                ReadOnly = true,
                TabStop = false,
                BorderStyle = BorderStyle.None,
                BackColor = palette.Surface1,
                ForeColor = palette.Ink2,
                Font = smallFont,
                Anchor = AnchorStyles.Left | AnchorStyles.Right,
                Text = ZingLoginUrl,
            };
            var readOnlyLabel = new Label
            {
                Text = text.ReadOnly,
                Font = smallFont,
                ForeColor = palette.Ink3,
                AutoSize = true,
                Anchor = AnchorStyles.Right,
                Margin = new Padding(8, 0, 4, 0),
            };
            var reload = FlatButton(Glyph(0xE72C), palette, palette.Ink2, outlined: false);
            reload.Font = iconFont;
            reload.BackColor = palette.Surface1;
            reload.Margin = new Padding(0);
            reload.Click += (_, _) => _webView.CoreWebView2?.Reload();
            address.Controls.Add(lockIcon, 0, 0);
            address.Controls.Add(_addressBox, 1, 0);
            address.Controls.Add(readOnlyLabel, 2, 0);
            address.Controls.Add(reload, 3, 0);
            RoundCorners(address, 20);

            // Privacy banner: accent container, radius 16, shield + text.
            var banner = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                ColumnCount = 2,
                RowCount = 1,
                BackColor = palette.AccentContainer,
                Padding = new Padding(16, 12, 16, 12),
                Margin = new Padding(0, 0, 0, 12),
            };
            banner.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            banner.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            var shield = new Label { Text = Glyph(0xEA18), Font = iconFont, ForeColor = palette.OnAccentContainer, AutoSize = true, Margin = new Padding(0, 2, 12, 0) };
            var bannerText = new Label { Text = text.Banner, Font = smallFont, ForeColor = palette.OnAccentContainer, AutoSize = true, Margin = new Padding(0) };
            banner.Controls.Add(shield, 0, 0);
            banner.Controls.Add(bannerText, 1, 0);
            void WrapBanner() => bannerText.MaximumSize = new Size(Math.Max(120, root.ClientSize.Width - root.Padding.Horizontal - 76), 0);
            root.SizeChanged += (_, _) => WrapBanner();
            WrapBanner();
            RoundCorners(banner, 16);

            // WebView2 surface, radius 16.
            var webHost = new Panel { Dock = DockStyle.Fill, BackColor = palette.Surface1, Margin = new Padding(0) };
            webHost.Controls.Add(_webView);
            RoundCorners(webHost, 16);

            // Footer: status, "Open in browser", "Cancel".
            var footer = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1, BackColor = palette.Background, Margin = new Padding(0) };
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            footer.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
            var status = new Label { Text = text.Status, Font = smallFont, ForeColor = palette.Ink3, AutoSize = true, Anchor = AnchorStyles.Left };
            var openInBrowser = FlatButton(text.OpenInBrowser, palette, palette.Accent, outlined: false);
            openInBrowser.Click += (_, _) => OpenInBrowser();
            var cancel = FlatButton(text.Cancel, palette, palette.Ink, outlined: true);
            cancel.Click += (_, _) => Close();
            CancelButton = cancel;
            footer.Controls.Add(status, 0, 0);
            footer.Controls.Add(openInBrowser, 1, 0);
            footer.Controls.Add(cancel, 2, 0);

            root.Controls.Add(address, 0, 0);
            root.Controls.Add(banner, 0, 1);
            root.Controls.Add(webHost, 0, 2);
            root.Controls.Add(footer, 0, 3);
            Controls.Add(root);
        }

        private void OpenInBrowser()
        {
            try
            {
                string url = ZingLoginUrl;
                string? current = _webView.CoreWebView2?.Source;
                if (current != null && Uri.TryCreate(current, UriKind.Absolute, out var uri) && uri.Scheme == "https") url = uri.AbsoluteUri;
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Open in browser failed: " + ex.Message);
            }
        }

        private async void InitializeAsync()
        {
            try
            {
                string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                TryDeleteDirectory(Path.Combine(localAppData, "MinMusic", "spotify-login-helper"));
                TryDeleteDirectory(Path.Combine(localAppData, "MinMusic", "login-helper"));
                _userDataFolder = Path.Combine(Path.GetTempPath(), "MinMusic-login-" + Guid.NewGuid().ToString("N"));

                var env = await CoreWebView2Environment.CreateAsync(null, _userDataFolder);
                var controllerOptions = env.CreateCoreWebView2ControllerOptions();
                controllerOptions.IsInPrivateModeEnabled = true;
                await _webView.EnsureCoreWebView2Async(env, controllerOptions);

                _webView.CoreWebView2.SourceChanged += OnSourceChanged;
                _extractionTimer.Tick += async (_, _) => await ExtractForCurrentSourceAsync();
                _extractionTimer.Start();
                string loginUrl = _provider == "spotify"
                    ? "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
                    : _loginUrl ?? "https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com";
                if (_provider == "zing")
                {
                    // The user signs in through zingmp3.vn's own login button (Zalo / VNG ID) and lands back on zingmp3.vn.
                    loginUrl = ZingLoginUrl;
                }
                _webView.CoreWebView2.Navigate(loginUrl);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Login initialization failed: " + ex.Message);
                Close();
            }
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            _extractionTimer.Dispose();
            _webView.Dispose();
            if (_userDataFolder != null) TryDeleteDirectory(_userDataFolder);
            base.OnFormClosed(e);
        }

        private static void TryDeleteDirectory(string path)
        {
            for (int attempt = 0; attempt < 5 && Directory.Exists(path); attempt++)
            {
                try
                {
                    Directory.Delete(path, true);
                }
                catch
                {
                    System.Threading.Thread.Sleep(200);
                }
            }
        }

        private async void OnSourceChanged(object? sender, CoreWebView2SourceChangedEventArgs e)
        {
            // Zing window: the read-only address bar shows where the user is signing in.
            if (_addressBox != null && !_addressBox.IsDisposed) _addressBox.Text = _webView.CoreWebView2.Source;
            await ExtractForCurrentSourceAsync();
        }

        private async Task ExtractForCurrentSourceAsync()
        {
            if (_completion.IsCompleted || _extracting || IsDisposed) return;
            _extracting = true;
            try
            {
                var uri = new Uri(_webView.CoreWebView2.Source);
                if (uri.Scheme != "https") return;
                if (_provider == "spotify" && uri.Host == "open.spotify.com") await TryExtractSpotifyAsync();
                else if (_provider == "zing")
                {
                    if (uri.Host == "zingmp3.vn" || uri.Host.EndsWith(".zingmp3.vn", StringComparison.Ordinal)) await TryExtractZingAsync();
                }
                else if (_provider != "spotify" && uri.Host == "music.youtube.com") await TryExtractAsync();
            }
            finally
            {
                _extracting = false;
            }
        }

        private async Task TryExtractSpotifyAsync()
        {
            try
            {
                var cookies = await _webView.CoreWebView2.CookieManager.GetCookiesAsync("https://open.spotify.com");
                var sessionCookie = cookies.FirstOrDefault(cookie => cookie.Name == "sp_dc")?.Value;
                if (string.IsNullOrWhiteSpace(sessionCookie)) return;

                var result = new { spotifyCookie = sessionCookie };
                if (WriteResult(result))
                {
                    _webView.CoreWebView2.CookieManager.DeleteAllCookies();
                    Close();
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Spotify extraction failed: " + ex.Message);
            }
        }

        private const string ZingLoginUrl = "https://zingmp3.vn/";

        // Never logs cookie names or values; only the exception message on failure.
        private async Task TryExtractZingAsync()
        {
            try
            {
                var cookieManager = _webView.CoreWebView2.CookieManager;
                var cookies = await cookieManager.GetCookiesAsync("https://zingmp3.vn");
                var byName = new Dictionary<string, string>();
                foreach (var c in cookies) byName[c.Name] = c.Value;
                var header = LoginCompletion.ReadZingCookieHeader(byName);
                if (header == null) return;

                var result = new { zingCookie = header };
                if (WriteResult(result))
                {
                    cookieManager.DeleteAllCookies();
                    Close();
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Zing extraction failed: " + ex.Message);
            }
        }

        private async Task TryExtractAsync()
        {
            try
            {
                var cookieManager = _webView.CoreWebView2.CookieManager;

                var musicCookies = await cookieManager.GetCookiesAsync("https://music.youtube.com");
                var youtubeCookies = await cookieManager.GetCookiesAsync("https://www.youtube.com");

                var allCookies = new Dictionary<string, string>();
                foreach (var c in youtubeCookies) allCookies[c.Name] = c.Value;
                foreach (var c in musicCookies) allCookies[c.Name] = c.Value;

                // Read one page snapshot; cookies can arrive before ytcfg is populated.
                string snapshot = await _webView.CoreWebView2.ExecuteScriptAsync(@"(() => {
                    const yt = window.yt?.config_;
                    const cfg = window.ytcfg?.data_;
                    const index = yt?.SESSION_INDEX ?? cfg?.SESSION_INDEX;
                    return {
                        visitorData: yt?.VISITOR_DATA || cfg?.VISITOR_DATA,
                        dataSyncId: yt?.DATASYNC_ID || cfg?.DATASYNC_ID,
                        authUser: index == null ? null : String(index)
                    };
                })()");
                var result = LoginCompletion.ReadYouTubeCredentials(allCookies, snapshot);
                if (result != null && WriteResult(result))
                {
                    cookieManager.DeleteAllCookies();
                    Close();
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Extraction failed: " + ex.Message);
            }
        }

        private bool WriteResult<T>(T result) => _completion.TryWrite(result, payload =>
        {
            string json = JsonSerializer.Serialize(payload);
            if (_outputPath == null)
            {
                Console.Out.Write(json);
                Console.Out.Flush();
            }
            else
            {
                File.WriteAllText(_outputPath, json);
            }
        });
    }
}
