using System.Text.Json;
using System.Text.Json.Nodes;
using DeepSeekHarnessLauncher;
using DeepSeekHarnessLauncher.Backup;

string testRoot = Path.GetFullPath(args[0]);
string bundle = Path.GetFullPath(args[1]);
string node = Path.GetFullPath(args[2]);
string dataRoot = Path.Combine(testRoot, "owned-data"); Directory.CreateDirectory(dataRoot);
Directory.CreateDirectory(testRoot);
Environment.SetEnvironmentVariable("DAFEIYU_BACKUP_WORK_DIRECTORY", Path.Combine(testRoot, "backup-work"));
Environment.SetEnvironmentVariable("DAFEIYU_LAUNCHER_SETTINGS_DIRECTORY", Path.Combine(testRoot, "settings"));
var checks = new List<string>();
void Check(bool value, string name) { if (!value) throw new Exception(name); checks.Add(name); Console.WriteLine("PASS " + name); }
void SaveReport() => File.WriteAllText(Path.Combine(testRoot, "result.json"), JsonSerializer.Serialize(new { passed = checks.Count, checks, paidModelCalls = 0, root = testRoot }));
DymPortableContext Context(LauncherSettings settings) { var value = DymLauncherBackupContext.Create(settings); value.BundleRoot = bundle; return value; }
var sourceSettings = new LauncherSettings { NodePath = node, DshRoot = dataRoot, Theme = "Dark", AriesDeviceId = "fixture-local-id",
    GitHubTokenProtected = "fixture-keep-token", AdminTokenProtected = "fixture-keep-admin", DeveloperModeUnlocked = true };
LauncherSettingsStore.SetApiKey(sourceSettings, "fixture-launcher-key");
var source = Context(sourceSettings);
Check(!source.PcPreferences.ToJsonString().Contains("fixture-local-id") && !source.PcPreferences.ToJsonString().Contains("fixture-keep-token"), "preference capture excludes identity and tokens");
var warnings = new List<string>();
var captured = DymPortableData.Capture(source, dataRoot, Path.Combine(testRoot, "source-home"), warnings, CancellationToken.None);
File.WriteAllText(Path.Combine(testRoot, "capture-warnings.json"), JsonSerializer.Serialize(warnings));
Check(warnings.Count == 0 && captured.ContainsKey(DymPortableData.ProvidersPath), "actual pinned provider and home credentials captured");
Check(!captured.Values.Any(value => value.Contains("UNRELATED_IDENTITY")), "unrelated home identity reference excluded");
Check(captured[DymPortableData.CredentialsPath].Contains("fixture-other-key") && captured[DymPortableData.CredentialsPath].Contains("fixture-launcher-key"), "portable provider and launcher keys included");
var android = new JsonObject { ["schema"] = "dafeiyu-android-backup", ["schemaVersion"] = 4, ["exportedAtEpochMillis"] = 1700000000000L,
    ["selectedWorkspaceId"] = "ws", ["selectedConversationId"] = "chat", ["workspaces"] = new JsonArray(new JsonObject { ["id"] = "ws", ["name"] = "Phone workspace" }),
    ["conversations"] = new JsonArray(new JsonObject { ["id"] = "chat", ["title"] = "Phone history", ["workspaceId"] = "ws", ["archivedAtMillis"] = null }),
    ["messages"] = new JsonObject { ["chat"] = new JsonArray(new JsonObject { ["id"] = 1, ["role"] = "user", ["text"] = "fixture prompt" },
        new JsonObject { ["id"] = 2, ["role"] = "assistant", ["text"] = "fixture answer" }) },
    ["settings"] = new JsonObject { ["themeMode"] = "system", ["reduceTransparency"] = false, ["reduceMotion"] = false,
        ["notificationsEnabled"] = true, ["keepRunning"] = false, ["downloadSource"] = "cdn" } };
var portableFiles = captured.ToDictionary(entry => entry.Key, entry => entry.Value);
portableFiles[".dafeiyu/android/workspace.json"] = android.ToJsonString();
portableFiles[".dafeiyu/preferences/android.json"] = new JsonObject { ["schema"] = 1, ["groups"] = new JsonObject { ["fixture"] = new JsonObject { ["keep"] = true } } }.ToJsonString();
var pcPreferences = JsonNode.Parse(portableFiles[DymPortableData.PcPreferencesPath]) as JsonObject;
pcPreferences["groups"]["appearance"]["Theme"] = "malformed-theme";
pcPreferences["groups"]["notifications"]["NotificationMuted"] = true;
portableFiles[DymPortableData.PcPreferencesPath] = pcPreferences.ToJsonString();
string archive = Path.Combine(testRoot, "pc-original.dym");
Check(UserDataBackup.Export(dataRoot, new[] { UserDataBackup.PortableGroup() }, archive, null, null, CancellationToken.None,
    Path.Combine(testRoot, "source-home"), portableFiles), "actual embedded PC 7zr created cross-platform dym");
Check(File.ReadAllBytes(archive).Take(6).SequenceEqual(new byte[] { 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c }), "archive is genuine sevenzip");
var groups = BackupFlow.ListArchiveGroups(archive, null, out string error);
Check(error == null && groups.Single().Id == UserDataBackup.GroupPortable, "pure phone portable archive recognized by launcher");
if (args.Length > 3 && args[3] == "capture") { SaveReport(); return; }
string incoming = args.Length > 3 ? args[3] : archive;
var targetSettings = new LauncherSettings { NodePath = node, DshRoot = dataRoot, Theme = "Light", AriesDeviceId = "target-original-id",
    GitHubTokenProtected = "target-keep-token", AdminTokenProtected = "target-keep-admin", StartWithWindows = true };
LauncherSettingsStore.SetApiKey(targetSettings, "target-original-key");
var expected = Context(targetSettings);
var selected = BackupFlow.ListArchiveGroups(incoming, null, out error);
var imported = BackupFlow.Import(incoming, dataRoot, selected, ConflictPolicy.Overwrite, null, null, CancellationToken.None,
    Path.Combine(testRoot, "target-home"), expected);
File.WriteAllText(Path.Combine(testRoot, "import-warnings.json"), JsonSerializer.Serialize(imported.Warnings));
Check(imported.Ok && imported.PortableRestore?.HistoryRestored == 1, "native phone body restored to actual first-party persisted DSH history");
DymLauncherBackupContext.Apply(targetSettings, expected, imported);
Check(targetSettings.Theme == "Light" && targetSettings.NotificationMuted && imported.Partial, "invalid preference group skipped while valid group saved and partial signaled");
Check(LauncherSettingsStore.ReadApiKey(targetSettings) == "fixture-launcher-key", "portable key restored through real Windows DPAPI");
Check(targetSettings.AriesDeviceId == "target-original-id" && targetSettings.AdminTokenProtected == "target-keep-admin"
    && targetSettings.GitHubTokenProtected == "target-keep-token" && targetSettings.StartWithWindows, "identity tokens OS grants and startup choices preserved");
var persisted = JsonNode.Parse(File.ReadAllText(LauncherSettingsStore.FilePath));
Check(persisted["notificationMuted"].GetValue<bool>() && persisted["apiKeyProtected"].GetValue<string>() != "fixture-launcher-key", "real platform settings file contains applied choices and protected credential");
var second = BackupFlow.Import(incoming, dataRoot, selected, ConflictPolicy.Overwrite, null, null, CancellationToken.None,
    Path.Combine(testRoot, "target-home"), Context(targetSettings));
Check(second.Ok && second.PortableRestore?.HistoryRestored == 1, "repeat import accepted without duplicate history overwrite");
var newer = Context(targetSettings);
targetSettings.NotificationMuted = false;
var raceResult = new BackupResult { Ok = true, PortableRestore = imported.PortableRestore };
DymLauncherBackupContext.Apply(targetSettings, newer, raceResult);
Check(!targetSettings.NotificationMuted && raceResult.Warnings.Any(value => value.Contains("较新的设置")), "UI preference edits during background import preserved by group CAS");
string missingRoot = Path.Combine(testRoot, "no-components"); Directory.CreateDirectory(missingRoot);
var noComponent = BackupFlow.Import(incoming, missingRoot, selected, ConflictPolicy.Overwrite, null, null, CancellationToken.None,
    Path.Combine(testRoot, "missing-home"), new DymPortableContext { PcPreferences = source.PcPreferences });
Check(noComponent.Ok && noComponent.Partial && noComponent.PortableRestore.HistoryRestored == 0
    && File.Exists(Path.Combine(missingRoot, ".dafeiyu/android/workspace.json")), "no-component import retains source and reports pending instead of false restored history");
var exported = BackupFlow.Export(dataRoot, BackupFlow.ListExportGroups(dataRoot, null, Path.Combine(testRoot, "target-home")),
    Path.Combine(testRoot, "reexport"), null, null, CancellationToken.None, Path.Combine(testRoot, "target-home"), Context(targetSettings));
File.WriteAllText(Path.Combine(testRoot, "reexport-warnings.json"), JsonSerializer.Serialize(exported.Warnings));
Check(exported.Ok, "PC re-export preserves mobile metadata and actual restored resources");
var entries = DymArchive.List(exported.ArchivePath, null, out error);
Check(entries.Any(item => item.Path == ".dafeiyu/preferences/android.json") && entries.Any(item => item.Path == ".dafeiyu/android/workspace.json"), "foreign Android preferences and native source survive PC re-export");
Check(!entries.Any(item => item.Path.Contains("identity") || item.Path.Contains("push-token")), "re-export contains no device identity or push token file");
SaveReport();
