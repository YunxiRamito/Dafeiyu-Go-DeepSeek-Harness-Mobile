using System.Diagnostics;
using System.Formats.Tar;
using System.IO.Compression;
using DeepSeekHarnessLauncher;

if (args.Length != 2) throw new ArgumentException("archive and G evidence directory required");
string root = Path.GetFullPath(args[1]);
if (!root.StartsWith("G:\\", StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Evidence must stay on G");
Directory.CreateDirectory(root);
string home = Path.Combine(root, "dsh-home"), profile = Path.Combine(home, "profiles", "web");
string installed = Path.Combine(profile, "node_modules", "dafeiyu-go-connect");
string cache = Path.Combine(root, "cache"), archive = Path.Combine(cache, ConnectionArchivePreload.Version, $"dafeiyu-go-connect-{ConnectionArchivePreload.Version}.tgz");
string owner = Path.Combine(root, "dsh", "node_modules", "@deepseek-ai", "dsh");
Directory.CreateDirectory(installed); Directory.CreateDirectory(Path.GetDirectoryName(archive)!); Directory.CreateDirectory(Path.Combine(owner, "lib"));
File.Copy(args[0], archive, overwrite: false);
byte[] originalArchive = File.ReadAllBytes(archive);
ConnectionPluginUpdateService.ValidatePackage(originalArchive, ConnectionArchivePreload.Version);
using (var source = new MemoryStream(originalArchive, writable: false))
using (var gzip = new GZipStream(source, CompressionMode.Decompress))
using (var reader = new TarReader(gzip))
{
    TarEntry? entry;
    while ((entry = reader.GetNextEntry()) != null)
    {
        string target = Path.Combine(installed, entry.Name.Substring("package/".Length));
        if (entry.EntryType == TarEntryType.Directory) { Directory.CreateDirectory(target); continue; }
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        using var output = File.Create(target); entry.DataStream!.CopyTo(output);
    }
}
File.WriteAllText(Path.Combine(owner, "package.json"), """{"name":"@deepseek-ai/dsh","version":"0.2.1-alpha.1"}""");
string bin = Path.Combine(owner, "lib", "bin.js"), profileFile = Path.Combine(profile, "package.json");
File.WriteAllText(bin, "// Offline fixture, never executed");
void Enabled(bool enabled) => File.WriteAllText(profileFile, enabled
    ? """{"dependencies":{"dafeiyu-go-connect":"0.3.0"},"dsh":{"profile":{"bundles":["dafeiyu-go-connect"]}}}"""
    : """{"dependencies":{"dafeiyu-go-connect":"0.3.0"},"dsh":{"profile":{"bundles":[]}}}""");
ProcessStartInfo Launch() => new() { FileName = "node.exe", Arguments = "\"" + bin + "\" web --no-open", UseShellExecute = false };
int checks = 0;
void Check(bool condition, string name) { if (!condition) throw new InvalidOperationException(name); Console.WriteLine($"PASS {++checks} {name}"); }
void Configure(ProcessStartInfo info) => ConnectionArchivePreload.Configure(info, bin, home, profile, cache);
void Refused(string name)
{
    var info = Launch(); bool refused = false;
    try { Configure(info); } catch (InvalidOperationException) { refused = true; }
    Check(refused && !info.Arguments.StartsWith("--import ", StringComparison.Ordinal), name);
}
Enabled(true);
var valid = Launch(); valid.Environment["DAFEIYU_DSH_INSTALL_ANCHOR"] = "untrusted";
valid.Environment["DAFEIYU_DSH_ARCHIVE_DIRECTORY"] = "untrusted"; Configure(valid);
Check(valid.Arguments.StartsWith("--import \"file:///G:/", StringComparison.OrdinalIgnoreCase), "actual frozen archive and installed files configure a Windows ESM file URL");
Check(valid.Environment["DAFEIYU_DSH_INSTALL_ANCHOR"] == Path.Combine(owner, "package.json")
    && valid.Environment["DAFEIYU_DSH_ARCHIVE_DIRECTORY"] == Path.Combine(home, "dafeiyu-connection-plugin", "workspace-lifecycle"), "owning anchor and lifecycle match the production Host; ambient selectors are replaced");
Enabled(false); var disabled = Launch(); Configure(disabled);
Check(!disabled.Arguments.StartsWith("--import ", StringComparison.Ordinal), "disabled bundle without archive history retains ordinary DSH startup");
string journal = Path.Combine(home, "dafeiyu-connection-plugin", "workspace-lifecycle", "archive-deletion.json");
Directory.CreateDirectory(Path.GetDirectoryName(journal)!); File.WriteAllText(journal, "{bad-json-retained-for-precli}");
var guarded = Launch(); Configure(guarded);
Check(guarded.Arguments.StartsWith("--import ", StringComparison.Ordinal) && File.ReadAllText(journal) == "{bad-json-retained-for-precli}", "existing archive journal requires a verified preload even when the bundle is disabled; parsing remains the actual preloader's responsibility");
string preload = Path.Combine(installed, "scripts", "archive-preload.mjs"); byte[] originalPreload = File.ReadAllBytes(preload);
File.AppendAllText(preload, "\n// changed"); Refused("changed installed script fails closed with durable archive history"); File.WriteAllBytes(preload, originalPreload);
byte[] changed = originalArchive.ToArray(); changed[^1] ^= 1; File.WriteAllBytes(archive, changed);
Refused("changed cached archive fails closed before any startup mutation"); File.WriteAllBytes(archive, originalArchive);
string metadata = Path.Combine(installed, "package.json"); string originalMetadata = File.ReadAllText(metadata);
File.WriteAllText(metadata, """{"name":"dafeiyu-go-connect","version":"0.2.0"}""");
Refused("older plugin cannot bypass durable tombstones"); File.WriteAllText(metadata, originalMetadata);
File.Move(archive, archive + ".retained"); Refused("missing verified package cannot bypass durable tombstones"); File.Move(archive + ".retained", archive);
string library = Path.Combine(installed, "lib", "index.js"); byte[] originalLibrary = File.ReadAllBytes(library);
File.AppendAllText(library, "\n// changed"); Refused("verification covers installed libraries as well as the preloader"); File.WriteAllBytes(library, originalLibrary);
File.Delete(journal); Enabled(true); File.AppendAllText(preload, "\n// changed");
var optional = Launch(); string originalArguments = optional.Arguments; Configure(optional);
Check(optional.Arguments == originalArguments && !optional.Environment.ContainsKey("DAFEIYU_DSH_INSTALL_ANCHOR"), "first install with an unverified optional loader preserves ordinary startup and advertises no archive loader");
Console.WriteLine($"RESULT {checks}/{checks} PASS production validator/helper=true actual Node/DSH startup=false paidRequests=0");
