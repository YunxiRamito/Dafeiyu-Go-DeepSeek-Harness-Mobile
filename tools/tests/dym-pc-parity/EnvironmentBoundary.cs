namespace DeepSeekHarnessLauncher
{
    // Uncalled OS/setup boundaries only. Tests use real settings persistence and DPAPI.
    internal static class StartupSupport { internal static bool IsEnabled() => throw new System.InvalidOperationException("Startup must not be queried by backup tests."); }
    internal static class LauncherLocator { internal static string FindNode() => throw new System.InvalidOperationException("Node must be explicit in isolated tests."); }
    internal static class ProxySupport { internal static void Apply(System.Net.WebRequest request) => throw new System.InvalidOperationException("Paid requests are forbidden in backup tests."); }
    internal static class BalanceLedger
    {
        internal static string AccountKey(string key) => throw new System.InvalidOperationException("Balance access is forbidden in backup tests.");
        internal static void Observe(decimal amount, string currency, string key) => throw new System.InvalidOperationException("Balance access is forbidden in backup tests.");
    }
    internal static class CornerRadiusHelper
    {
        internal static string NormalizeWindowStyle(string style) => style is "System" or "Windows10" or "Windows11" ? style : "System";
    }
}
