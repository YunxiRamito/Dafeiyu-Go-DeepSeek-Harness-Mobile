namespace DeepSeekHarnessLauncher
{
    internal static class Constants { public const string Version = "bulk-offline-check/0.1.25"; }
    internal static class LauncherSettingsStore
    {
        public static string DirectoryPath => System.Environment.GetEnvironmentVariable("PC_PROVIDER_TEST_SETTINGS")
            ?? throw new System.InvalidOperationException("An isolated settings path is required.");
        public static void Save(LauncherSettings value) { }
    }
}
