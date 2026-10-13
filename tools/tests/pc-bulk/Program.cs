using DeepSeekHarnessLauncher;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

internal static class Program
{
    private const string Protocol = "dafeiyu-go.remote-dsh.v1";
    private static int passed;
    private static JsonElement Json(object value) => JsonSerializer.SerializeToElement(value, AriesCrypto.Json);
    private static FieldInfo Field(string name) => typeof(AriesConnectionService).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic);
    private static void Check(bool condition, string name) { if (!condition) throw new Exception("FAIL " + name); Console.WriteLine("PASS " + ++passed + " " + name); }
    private static async Task<JsonElement> Call(AriesConnectionService client, string method, object parameters)
        => Json(await client.DispatchProviderAsync(method, Json(parameters)));
    private static async Task Reject(Func<Task> action, string code, string name)
    {
        try { await action(); } catch (AriesProviderException error) when (error.Code == code) { Check(true, name); return; }
        throw new Exception("FAIL " + name);
    }

    private sealed class Wire : HttpMessageHandler
    {
        internal readonly byte[] PeerSeed = RandomNumberGenerator.GetBytes(32);
        internal readonly byte[] Key = RandomNumberGenerator.GetBytes(32);
        internal string Peer => AriesCrypto.DeviceId(AriesCrypto.PublicKey(PeerSeed));
        internal string Local;
        internal readonly List<JsonElement> Items = new();
        internal int Posts;
        internal void Incoming(string kind, object body)
        {
            ulong sequence = (ulong)Items.Count + 1;
            string cipher = AriesCrypto.SealContent(PeerSeed, Key, new AriesContentEnvelope { Type = "message", NodeId = "node-test",
                GroupId = "group-test", Epoch = 1, SenderId = Peer, ObjectId = "object-" + sequence }, JsonSerializer.SerializeToUtf8Bytes(new { kind, body }, AriesCrypto.Json));
            Items.Add(Json(new { id = "message-" + sequence, sequence, group_id = "group-test", sender_id = Peer, epoch = 1, ciphertext = cipher }));
        }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (request.Method == HttpMethod.Get)
            {
                var query = request.RequestUri.Query.TrimStart('?').Split('&').Select(entry => entry.Split('=')).ToDictionary(entry => entry[0], entry => entry[1]);
                ulong cursor = ulong.Parse(query["after"]);
                var page = Items.Where(entry => entry.GetProperty("sequence").GetUInt64() > cursor).Take(4).ToArray();
                return Response(new { items = page, next_cursor = (page.Length == 0 ? cursor : page.Last().GetProperty("sequence").GetUInt64()).ToString() });
            }
            Posts++;
            using var document = JsonDocument.Parse(await request.Content.ReadAsByteArrayAsync(token));
            ulong sequence = (ulong)Items.Count + 1;
            Items.Add(Json(new { id = "message-" + sequence, sequence, group_id = "group-test", sender_id = Local, epoch = 1,
                ciphertext = document.RootElement.GetProperty("ciphertext").GetString() }));
            return Response(new { message_id = "message-" + sequence, generation = 1 });
        }
        private static HttpResponseMessage Response(object value) => new(HttpStatusCode.OK)
            { Content = new StringContent(JsonSerializer.Serialize(value, AriesCrypto.Json), Encoding.UTF8, "application/json") };
    }

    private static AriesConnectionService Client(string directory, Wire wire)
    {
        var client = new AriesConnectionService(new HttpClient(wire, disposeHandler: false), directory);
        client.Initialize(new LauncherSettings { AriesDeviceName = "isolated-bulk-check" });
        byte[] seed = (byte[])Field("seed").GetValue(client);
        string local = AriesCrypto.DeviceId(AriesCrypto.PublicKey(seed));
        wire.Local = local;
        Field("baseUri").SetValue(client, new Uri("https://fixture.invalid"));
        Field("binding").SetValue(client, new AriesServerBinding { NodeId = "node-test", DeviceId = local, GroupId = "group-test",
            ContentKey = AriesCrypto.Encode(wire.Key), Epoch = 1, PeerDeviceId = wire.Peer, PeerPublicKey = AriesCrypto.PublicKey(wire.PeerSeed) });
        var own = new AriesDeviceInfo { DeviceId = local, PublicKey = AriesCrypto.PublicKey(seed), IsLocal = true, IsPaired = true };
        var peer = new AriesDeviceInfo { DeviceId = wire.Peer, PublicKey = AriesCrypto.PublicKey(wire.PeerSeed), IsPaired = true };
        Field("state").SetValue(client, new AriesConnectionState { ServerConnected = true, PairingStatus = AriesPairingStatus.Paired,
            LocalDevice = own, Members = new[] { own, peer }, GroupId = "group-test", LastServerSeenUtc = DateTimeOffset.UtcNow });
        return client;
    }
    private static AriesProviderStore Store(AriesConnectionService client) => (AriesProviderStore)Field("providerStore").GetValue(client);
    private static async Task<JsonElement[]> Read(AriesConnectionService client, string cursor = "0")
        => (await Call(client, "list_incoming_messages", new { group_id = "group-test", cursor })).GetProperty("messages").EnumerateArray().Select(item => item.Clone()).ToArray();
    private static Task<JsonElement> Ack(AriesConnectionService client, string id)
        => Call(client, "acknowledge_message", new { group_id = "group-test", message_id = id });

    public static async Task Main(string[] args)
    {
        string root = Path.GetFullPath(args[0]); Directory.CreateDirectory(root);
        Environment.SetEnvironmentVariable("PC_PROVIDER_TEST_SETTINGS", Path.Combine(root, "settings"));
        using var wire = new Wire();
        using var client = Client(Path.Combine(root, "identity"), wire);
        Check(!(await Call(new AriesConnectionService(new HttpClient(), Path.Combine(root, "blank")), "get_connection_state", new { })).GetProperty("server_connected").GetBoolean(), "blank snapshot remains read-only without provider store");
        const string request = "bulk-read-check-123";
        wire.Incoming("dsh_remote_request", new { protocol = Protocol, request_id = request, target_device_id = wire.Local,
            operation = "artifact.read", payload = new { offset = 0, length = 16, ticket = "ticket-test" } });
        var received = (await Read(client)).Single();
        Check(Store(client).BulkRequests.Count == 1, "authenticated actual crypto request creates a scoped bulk receipt");
        await Ack(client, received.GetProperty("message_id").GetString());
        Check(Store(client).BulkRequests.Count == 1, "local request ACK alone does not retire an undelivered result");
        await Call(client, "send_encrypted_message", new { group_id = "group-test", kind = "dsh_remote_event", idempotency_key = "event-check-123",
            body = new { protocol = Protocol, request_id = request, target_device_id = wire.Peer, sequence = 0, type = "result", payload = new { bytes = "dGVzdA==" } } });
        Check(Store(client).Mutations.Count == 1 && wire.Posts == 1, "exact result ciphertext is journaled before the actual HTTP transport");
        wire.Incoming("dsh_remote_ack", new { protocol = Protocol, request_id = request, target_device_id = wire.Local, operation = "artifact.read", sequence = 1 });
        await Reject(() => Read(client), "permission_denied", "future terminal sequence cannot retire a delivered result");
        wire.Items.RemoveAt(wire.Items.Count - 1);
        wire.Incoming("dsh_remote_ack", new { protocol = Protocol, request_id = request, target_device_id = wire.Local, operation = "artifact.read", sequence = 0 });
        var deliveredAck = (await Read(client)).Single();
        Check(Store(client).Mutations.Count == 1, "receiving authenticated ACK retains ciphertext until host consumption is locally durable");
        await Ack(client, deliveredAck.GetProperty("message_id").GetString());
        Check(Store(client).Mutations.Count == 0 && Store(client).BulkRequests.Count == 0 && Store(client).MessageReceipts.Count == 0,
            "host durable ACK atomically retires only the exact safe transfer ciphertext and receipts");
        Check((await Read(client)).Length == 0, "cold cursor cannot redeliver retired bulk messages");
        using (var restored = Client(Path.Combine(root, "identity"), wire))
            Check((await Read(restored)).Length == 0 && Store(restored).Mutations.Count == 0, "real DPAPI state reload preserves retired ranges across process recreation");
        wire.Incoming("dsh_remote_ack", new { protocol = Protocol, request_id = request, target_device_id = wire.Local, operation = "artifact.read", sequence = 0 });
        var late = (await Read(client)).Single(); await Ack(client, late.GetProperty("message_id").GetString());
        Check(Store(client).MessageReceipts.Count == 0 && Store(client).BulkLateAcknowledgements.Count == 0, "late authenticated bulk ACK is consumed without creating a request or a receipt leak");
        await Call(client, "send_encrypted_message", new { group_id = "group-test", kind = "dsh_remote_event", idempotency_key = "task-result-123",
            body = new { protocol = Protocol, request_id = "ordinary-task-123", target_device_id = wire.Peer, sequence = 0, type = "result", payload = new { accepted = true } } });
        Check(Store(client).Mutations.Count == 1, "ordinary task result keeps its original durable journal");
        wire.Incoming("dsh_remote_ack", new { protocol = Protocol, request_id = "ordinary-task-123", target_device_id = wire.Local, operation = "artifact.read", sequence = 0 });
        // Advance past the already consumed first page, preserving its actual
        // server cursor instead of inventing a consumed sequence from a body.
        var ordinaryAck = (await Read(client, "4")).Single(); await Ack(client, ordinaryAck.GetProperty("message_id").GetString());
        Check(Store(client).Mutations.Count == 1, "claiming a bulk operation in an unknown ACK cannot retire an ordinary executable result");
        const string second = "bulk-second-check-123";
        wire.Incoming("dsh_remote_request", new { protocol = Protocol, request_id = second, target_device_id = wire.Local,
            operation = "attachment.status", payload = new { uploadId = "upload-test" } });
        var secondRequest = (await Read(client, "6")).Single(); await Ack(client, secondRequest.GetProperty("message_id").GetString());
        await Call(client, "send_encrypted_message", new { group_id = "group-test", kind = "dsh_remote_event", idempotency_key = "second-event-123",
            body = new { protocol = Protocol, request_id = second, target_device_id = wire.Peer, sequence = 0, type = "result", payload = new { receivedBytes = 0 } } });
        wire.Incoming("dsh_remote_ack", new { protocol = Protocol, request_id = second, target_device_id = wire.Local, operation = "attachment.status", sequence = 0 });
        var secondAck = (await Read(client, "7")).Single();
        string stateFile = Path.Combine(root, "identity", "aries-provider", "state.dpapi");
        string retained = stateFile + ".failure-check-backup";
        File.Move(stateFile, retained); Directory.CreateDirectory(stateFile);
        try
        {
            await Reject(() => Ack(client, secondAck.GetProperty("message_id").GetString()), "disk_space_low", "failed durable retirement reports a storage error");
            Check(Store(client).Mutations.Count == 2 && Store(client).BulkRequests.Count == 1,
                "failed durable retirement restores the original in-memory ciphertext and bulk evidence");
        }
        finally { Directory.Delete(stateFile); File.Move(retained, stateFile); }
        await Ack(client, secondAck.GetProperty("message_id").GetString());
        Check(Store(client).Mutations.Count == 1 && Store(client).BulkRequests.Count == 0,
            "retry after real filesystem failure retires only the same bulk receipt and preserves task intent");
        string block = Convert.ToBase64String(new byte[64 * 1024]);
        for (int index = 0; index < 1100; index++)
        {
            string cursor = wire.Items.Count.ToString(), id = "long-bulk-" + index + "-123";
            wire.Incoming("dsh_remote_request", new { protocol = Protocol, request_id = id, target_device_id = wire.Local,
                operation = "artifact.read", payload = new { offset = index * 65536L, length = 65536, ticket = "ticket-long" } });
            var incoming = (await Read(client, cursor)).Single(); await Ack(client, incoming.GetProperty("message_id").GetString());
            await Call(client, "send_encrypted_message", new { group_id = "group-test", kind = "dsh_remote_event", idempotency_key = "long-event-" + index,
                body = new { protocol = Protocol, request_id = id, target_device_id = wire.Peer, sequence = 0, type = "result", payload = new { bytes = block } } });
            wire.Incoming("dsh_remote_ack", new { protocol = Protocol, request_id = id, target_device_id = wire.Local, operation = "artifact.read", sequence = 0 });
            var consumed = (await Read(client, cursor)).Single(); await Ack(client, consumed.GetProperty("message_id").GetString());
            if (Store(client).Mutations.Count != 1 || Store(client).BulkRequests.Count != 0 || Store(client).MessageReceipts.Count != 0)
                throw new Exception("Bulk retirement failed to remain bounded at chunk " + index);
            if ((index + 1) % 200 == 0) Console.WriteLine("PROGRESS actual crypto/DPAPI bulk " + (index + 1) + "/1100");
        }
        Check(wire.Posts == 1103 && Store(client).Mutations.Count == 1 && Store(client).BulkSuppressed.Values.Single().Count == 1,
            "1100 actual encrypted 64KiB result POSTs retire with real DPAPI writes; the ordinary journal survives and ranges stay bounded");
        var rules = new AriesProviderStore();
        AriesProviderBulkPolicy.Suppress(rules, "scope", 1); AriesProviderBulkPolicy.Suppress(rules, "scope", 3);
        Check(!AriesProviderBulkPolicy.Suppressed(rules, "scope", 2), "range compression preserves an unacknowledged foreign-message gap");
        AriesProviderBulkPolicy.Suppress(rules, "scope", 2);
        for (ulong sequence = 4; sequence <= 12000; sequence++) AriesProviderBulkPolicy.Suppress(rules, "scope", sequence);
        Check(rules.BulkSuppressed["scope"].Count == 1 && !AriesProviderBulkPolicy.Suppressed(rules, "other", 12000), "12000 known contiguous sequences compact into one exact-scope range");
        var old = JsonSerializer.Deserialize<AriesProviderStore>("{\"version\":1,\"mutations\":{},\"transfers\":{},\"messageReceipts\":{},\"remoteRequests\":{}}", AriesCrypto.Json);
        AriesProviderBulkPolicy.Validate(old);
        Check(old.BulkRequests.Count == 0, "existing version1 DPAPI payload gains empty bulk fields without migration or identity replacement");
        Console.WriteLine(passed + " PASS; actual PC Provider, crypto and DPAPI with synthetic HTTP; no Aries, Android or model requests.");
    }
}
