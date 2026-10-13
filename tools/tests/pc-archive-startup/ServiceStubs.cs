using System.Net.Http;

namespace DeepSeekHarnessLauncher;
// The production validator and startup helper run unchanged. Unused download/CLI services refuse execution.
internal sealed class LauncherSettings { }
internal static class DshPluginCliService
{
    internal sealed class CliResult { }
    internal static CliResult Install(LauncherSettings settings, string path, int timeout, Action<string> log,
        Func<bool>? cancelled = null) => throw new InvalidOperationException("This offline test must not invoke DSH CLI");
}
internal static class LauncherSettingsStore { internal static string DirectoryPath => throw new InvalidOperationException(); }
internal sealed class ClientNoticeStore
{
    internal static ClientNoticeStore ForLauncher() => throw new InvalidOperationException();
    internal object LoadSettings() => throw new InvalidOperationException();
}
internal static class ClientNoticeClient { internal static Uri? ResolveBase(object settings) => throw new InvalidOperationException(); }
internal static class NoticeTransport { internal static HttpClient CreateHttpClient(TimeSpan timeout) => throw new InvalidOperationException(); }
internal static class Constants { internal const string Version = "1.8.0"; }
internal static class ConnectionPipeServer { internal const string Protocol = "dafeiyu-go.connection.v1"; }
