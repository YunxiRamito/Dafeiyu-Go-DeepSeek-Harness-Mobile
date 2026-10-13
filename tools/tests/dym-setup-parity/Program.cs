using System.Text;
using System.Text.Json;
using DshInstaller.Shared.Backup;

string root = Path.GetFullPath(args[0]);
Directory.CreateDirectory(root);
Environment.SetEnvironmentVariable("DAFEIYU_BACKUP_WORK_DIRECTORY", Path.Combine(root, "backup-work"));
var checks = new List<string>();
void Check(bool value, string label) { if (!value) throw new Exception(label); checks.Add(label); Console.WriteLine("PASS " + label); }
void Write(string dataRoot, string path, string value)
{
    string file = Path.Combine(dataRoot, path.Replace('/', Path.DirectorySeparatorChar));
    Directory.CreateDirectory(Path.GetDirectoryName(file)); File.WriteAllText(file, value, new UTF8Encoding(false));
}
string Text(string dataRoot, string path) => File.ReadAllText(Path.Combine(dataRoot, path));
bool Same(string left, string right, string path) => File.ReadAllBytes(Path.Combine(left, path)).SequenceEqual(File.ReadAllBytes(Path.Combine(right, path)));

string source = Path.Combine(root, "phone-source");
var metadata = new Dictionary<string, string>
{
    [".dafeiyu/preferences/android.json"] = "{\"schema\":1,\"groups\":{\"official\":{\"tokenSavingMode\":true}}}",
    [".dafeiyu/preferences/pc.json"] = "{malformed foreign preference must remain opaque",
    [".dafeiyu/config/providers.json"] = "{\"schema\":1,\"credentials\":{\"deepseek\":{\"key\":\"fixture-provider-key\"}}}",
    [".dafeiyu/config/credentials.json"] = "{\"schema\":1,\"refs\":{\"FIXTURE_PROVIDER_KEY\":\"fixture-provider-key\"}}",
    [".dafeiyu/android/workspace.json"] = "{\"schema\":\"dafeiyu-android-backup\",\"schemaVersion\":4,\"messages\":{\"fixture\":[{\"id\":1,\"role\":\"user\",\"text\":\"not replayed\"}]}}",
    [".dafeiyu/android/model-history/" + new string('a', 64) + ".json"] = "{\"schema\":1,\"events\":[]}",
};
foreach (var item in metadata) Write(source, item.Key, item.Value);
Write(source, ".dafeiyu/device-identity.json", "must-not-travel");
Write(source, ".dafeiyu/push-tokens/token.json", "must-not-travel");
Write(source, ".dafeiyu/config/unknown.json", "not-an-allowed-contract");
var sourceGroups = UserDataBackup.Describe(source);
Check(sourceGroups.Count == 1 && sourceGroups[0].Id == UserDataBackup.GroupPortable && sourceGroups[0].SelectedByDefault,
    "real Setup export discovery exposes a phone-only portable group without Launcher dependencies");
string archive = Path.Combine(root, "phone-only.dym");
Check(UserDataBackup.Export(source, sourceGroups, archive, null, null, CancellationToken.None),
    "real Setup export call shape creates phone-only dym using the embedded sevenzip executable");
Check(File.ReadAllBytes(archive).Take(6).SequenceEqual(new byte[] { 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c }), "Setup output is genuine sevenzip");
var entries = DymArchive.List(archive, null, out string error);
Check(error == null && metadata.Keys.All(path => entries.Any(entry => entry.Path == path)) && entries.Count(entry => !entry.IsDirectory) == metadata.Count,
    "all allowed phone metadata and model-history bytes are packed while identities tokens and unknown paths are excluded");
var importedGroups = UserDataBackup.DescribeFromArchive(archive, null);
Check(importedGroups.Count == 1 && importedGroups[0].Id == UserDataBackup.GroupPortable
    && importedGroups[0].NameZh.Contains("原样保留"), "real Setup import discovery recognizes phone-only archive and states opaque retention");
string target = Path.Combine(root, "setup-target");
Write(target, ".dsh/profiles/default/identity.json", "keep-existing-device-state");
Check(UserDataBackup.Import(archive, target, importedGroups, ConflictPolicy.Overwrite, null, null, null, CancellationToken.None, out error),
    "real Setup import call shape restores portable-only metadata without a Launcher restore callback");
Check(metadata.Keys.All(path => Same(source, target, path)) && Text(target, ".dsh/profiles/default/identity.json") == "keep-existing-device-state",
    "opaque platform preferences credentials and native history survive exactly while existing device identity stays untouched");
Check(!File.Exists(Path.Combine(target, ".dafeiyu/device-identity.json")) && !Directory.Exists(Path.Combine(target, ".dafeiyu/push-tokens")),
    "Setup import does not create phone identity or push-token state");
string reexport = Path.Combine(root, "setup-reexport.dym");
Check(UserDataBackup.Export(target, UserDataBackup.Describe(target), reexport, null, null, CancellationToken.None),
    "actual Setup re-export keeps imported phone metadata available to the Launcher later");
string decoded = Path.Combine(root, "reexport-readback");
Check(DymArchive.Extract(reexport, decoded, null, null, CancellationToken.None) && metadata.Keys.All(path => Same(source, decoded, path)),
    "real sevenzip extraction confirms byte-exact roundtrip including malformed foreign preferences and API keys");

Write(target, ".dafeiyu/preferences/android.json", "keep-newer-target");
Check(UserDataBackup.Import(archive, target, importedGroups, ConflictPolicy.Skip, null, null, null, CancellationToken.None, out error)
    && Text(target, ".dafeiyu/preferences/android.json") == "keep-newer-target", "portable import respects the existing Skip conflict policy");
Check(UserDataBackup.Import(archive, target, importedGroups, ConflictPolicy.Overwrite, null, null, null, CancellationToken.None, out error)
    && metadata.Keys.All(path => Same(source, target, path)), "portable import respects the existing Overwrite conflict policy");

string hostile = Path.Combine(root, "untrusted-source");
foreach (var item in metadata) Write(hostile, item.Key, item.Value);
Write(hostile, ".dafeiyu/android/model-history/device-identity.json", "must-not-travel");
Write(hostile, ".dafeiyu/aries-identity/private-key.json", "must-not-travel");
string malicious = Path.Combine(root, "unfiltered.dym");
Check(DymArchive.Create(malicious, new[] { ".dafeiyu" }, hostile, null, null, CancellationToken.None), "unfiltered archive fixture uses actual embedded sevenzip");
string clean = Path.Combine(root, "untrusted-import");
Check(UserDataBackup.Import(malicious, clean, UserDataBackup.DescribeFromArchive(malicious, null), ConflictPolicy.Overwrite,
    null, null, null, CancellationToken.None, out error) && metadata.Keys.All(path => Same(hostile, clean, path))
    && !File.Exists(Path.Combine(clean, ".dafeiyu/android/model-history/device-identity.json"))
    && !Directory.Exists(Path.Combine(clean, ".dafeiyu/aries-identity")), "Setup independently filters identities from untrusted archives rather than trusting its own exporter");
string unknown = Path.Combine(root, "unknown-source"); Write(unknown, ".dafeiyu/config/unknown.json", "unknown");
string unknownArchive = Path.Combine(root, "unknown-only.dym");
Check(DymArchive.Create(unknownArchive, new[] { ".dafeiyu" }, unknown, null, null, CancellationToken.None)
    && UserDataBackup.DescribeFromArchive(unknownArchive, null).Count == 0, "unknown-only metadata does not present a fake restorable group in the Setup UI");
Check(UserDataBackup.Describe(source).Single().NameEn.Contains("retained") && !Directory.Exists(Path.Combine(target, ".dsh/storages")),
    "Setup retains data without converting native conversations or launching restored tasks");
File.WriteAllText(Path.Combine(root, "result.json"), JsonSerializer.Serialize(new { passed = checks.Count, checks, paidModelCalls = 0,
    actualSetupCore = true, launcherUi = false, phoneArchive = archive, reexport }));
Console.WriteLine($"RESULT {checks.Count}/{checks.Count} production Setup core and actual sevenzip roundtrip checks");
