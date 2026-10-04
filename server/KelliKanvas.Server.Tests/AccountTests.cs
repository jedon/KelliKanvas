using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using KelliKanvas.Server;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Npgsql;
using Xunit;

[assembly: CollectionBehavior(DisableTestParallelization = true)]
namespace KelliKanvas.Server.Tests;

public sealed class AccountTests : IAsyncLifetime
{
    // Dedicated local disposable database; no production connection-string fallback.
    private const string Database = "Host=127.0.0.1;Port=16439;Database=kellikanvas_test;Username=kanvas_test;Password=local-test-only";
    private readonly ServerOptions options = new("https://kanvas.example", Database, Convert.ToBase64String(Enumerable.Range(1, 32).Select(i => (byte)i).ToArray()), "client_fixture", "sk_fixture", null, []);
    private NpgsqlDataSource source = null!;
    private PostgresAccountStore store = null!;
    private readonly string userId = "user_" + Guid.NewGuid().ToString("N");
    public async Task InitializeAsync()
    {
        source = NpgsqlDataSource.Create(Database);
        store = new(source, new Secrets(options), options);
        await store.Initialize(default);
        await store.UpsertUser(new(userId, "frame@example.test", "Kelli Fixture"), default);
    }
    public async Task DisposeAsync() { await source.DisposeAsync(); }
    private static JsonObject Settings(string theme = "KELLI") => JsonNode.Parse("{\"theme\":\"" + theme + "\",\"slideDurationMillis\":15000}")!.AsObject();
    private static PhotoConnection Connection(string secret = "fixture-only-password") => new("fixture-source", "JELLYFIN", "Family photographs",
        JsonNode.Parse("{\"endpoint\":\"https://photos.example/\",\"username\":\"fixture\",\"secret\":\"" + secret + "\"}")!.AsObject(), [new("root", "Family photographs")]);

    [Fact] public async Task Pairing_requires_private_device_secret_and_is_delivered_exactly_once()
    {
        var pair = await store.StartPairing("Hisense Art TV", default);
        Assert.DoesNotContain(pair.DeviceSecret, pair.VerificationUri);
        Assert.Equal("pending", (await store.PollPairing(pair.Id, pair.DeviceSecret, default)).Status);
        Assert.Equal("invalid", (await store.PollPairing(pair.Id, Secrets.Token(), default)).Status);
        Assert.True(await store.ApprovePairing(pair.UserCode, userId, default));
        Assert.False(await store.ApprovePairing(pair.UserCode, userId, default));
        var results = await Task.WhenAll(store.PollPairing(pair.Id, pair.DeviceSecret, default), store.PollPairing(pair.Id, pair.DeviceSecret, default));
        var granted = Assert.Single(results, r => r.Status == "approved");
        Assert.Single(results, r => r.Status == "consumed");
        Assert.Equal(userId, (await store.Session(granted.Token!, default))!.User.Id);
        await store.RevokeDevice(userId, granted.DeviceId!.Value, default);
        Assert.Null(await store.Session(granted.Token!, default));
    }
    [Fact] public async Task Expired_cancelled_and_revoked_pairings_cannot_issue_tokens()
    {
        var pair = await store.StartPairing("Expired TV", default);
        await using (var db = await source.OpenConnectionAsync())
        await using (var cmd = new NpgsqlCommand("UPDATE kanvas.pairings SET expires_at=now()-interval '1 second' WHERE id=$1", db))
        { cmd.Parameters.AddWithValue(pair.Id); await cmd.ExecuteNonQueryAsync(); }
        Assert.False(await store.ApprovePairing(pair.UserCode, userId, default));
        Assert.Equal("expired", (await store.PollPairing(pair.Id, pair.DeviceSecret, default)).Status);
        var cancelled = await store.StartPairing("Cancelled TV", default);
        Assert.True(await store.ApprovePairing(cancelled.UserCode, userId, default));
        await store.CancelPairing(cancelled.Id, cancelled.DeviceSecret, default);
        Assert.Equal("expired", (await store.PollPairing(cancelled.Id, cancelled.DeviceSecret, default)).Status);
        var revoked = await store.StartPairing("Revoked TV", default);
        Assert.True(await store.ApprovePairing(revoked.UserCode, userId, default));
        var device = (await store.Devices(userId, default)).First(d => d.Name == "Revoked TV");
        await store.RevokeDevice(userId, device.Id, default);
        Assert.Equal("denied", (await store.PollPairing(revoked.Id, revoked.DeviceSecret, default)).Status);
    }
    [Fact] public async Task Connections_are_encrypted_persistent_and_scoped_to_owner()
    {
        var connection = Connection();
        Assert.True(await store.SaveState(userId, new(0, Settings(), [connection]), default));
        await using (var db = await source.OpenConnectionAsync())
        await using (var cmd = new NpgsqlCommand("SELECT encrypted_payload FROM kanvas.connections WHERE user_id=$1", db))
        { cmd.Parameters.AddWithValue(userId); var encrypted = (byte[])(await cmd.ExecuteScalarAsync())!; Assert.DoesNotContain("fixture-only-password", Encoding.UTF8.GetString(encrypted)); }
        await using var restartedSource = NpgsqlDataSource.Create(Database);
        var restarted = new PostgresAccountStore(restartedSource, new Secrets(options), options);
        Assert.Equal("fixture-only-password", (await restarted.State(userId, default)).Connections.Single().Configuration["secret"]!.GetValue<string>());
        var otherId = "user_" + Guid.NewGuid().ToString("N");
        await store.UpsertUser(new(otherId, "other@example.test", "Other"), default);
        Assert.Empty((await store.State(otherId, default)).Connections);
        Assert.True(await store.SaveConnection(otherId, new(0, Connection("other-fixture-password")), default));
        await store.DeleteConnection(otherId, connection.Id, 1, default);
        Assert.Single((await store.State(userId, default)).Connections);
        var wrongKey = options with { EncryptionKey = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)) };
        await Assert.ThrowsAnyAsync<CryptographicException>(() => new PostgresAccountStore(restartedSource, new Secrets(wrongKey), wrongKey).State(userId, default));
    }
    [Fact] public async Task Concurrent_saves_cannot_overwrite_a_newer_revision()
    {
        var results = await Task.WhenAll(store.SaveSettings(userId, new(0, Settings("PAPER")), default), store.SaveSettings(userId, new(0, Settings("MIDNIGHT")), default));
        Assert.Single(results, v => v); Assert.Single(results, v => !v);
        Assert.Equal(1, (await store.State(userId, default)).Revision);
    }
    [Fact] public void Auth_state_is_bound_to_browser_expiring_and_tamper_proof()
    {
        var secrets = new Secrets(options); var state = new LoginState(Secrets.Token(), Secrets.Token(), "/pair?code=ABCDEFGH", DateTime.UtcNow.AddMinutes(10));
        var cookie = WebEncoders.Base64UrlEncode(secrets.Encrypt(System.Text.Json.JsonSerializer.Serialize(state), "oauth-state"));
        Assert.Equal(state, BrowserAuth.Validate(secrets, cookie, state.State));
        Assert.Null(BrowserAuth.Validate(secrets, cookie, Secrets.Token()));
        Assert.Null(BrowserAuth.Validate(secrets, cookie[..^5] + "AAAAA", state.State));
        Assert.Equal("/", BrowserAuth.SafeReturn("//evil.example"));
        Assert.Equal("/", BrowserAuth.SafeReturn("/pair?code=ABCDEFGH&next=https://evil.example"));
        var expired = state with { Expires = DateTime.UtcNow.AddMinutes(-1) };
        Assert.Null(BrowserAuth.Validate(secrets, WebEncoders.Base64UrlEncode(secrets.Encrypt(System.Text.Json.JsonSerializer.Serialize(expired), "oauth-state")), state.State));
    }
    [Fact] public void Validation_rejects_invalid_settings_connections_and_reserved_credentials()
    {
        Assert.Throws<ArgumentException>(() => Validation.Settings(JsonNode.Parse("{\"theme\":\"INVALID\"}")!.AsObject()));
        Assert.Throws<ArgumentException>(() => Validation.Settings(JsonNode.Parse("{\"secret\":\"password\"}")!.AsObject()));
        Assert.Throws<ArgumentException>(() => Validation.Settings(JsonNode.Parse("{\"slideDurationMillis\":1000,\"transitionDurationMillis\":2000}")!.AsObject()));
        Assert.Throws<ArgumentException>(() => Validation.Connection(Connection() with { Provider = "NOT_REAL" }));
        Assert.Throws<ArgumentException>(() => Validation.State(new(0, Settings(), [Connection(), Connection()])));
        Assert.Throws<ArgumentException>(() => Validation.Connection(Connection() with { Id = "kanvas-account-v1" }));
        Validation.State(new(0, Settings(), [Connection()]));
    }
    [Fact] public async Task Immich_linking_scopes_discovery_and_discards_password_after_saving_only_integration_key()
    {
        var linker = new ServiceLinker(source, store, new Secrets(options));
        var pair = await store.StartPairing("Discovery TV", default);
        Assert.True(await store.ApprovePairing(pair.UserCode, userId, default));
        var linked = await store.PollPairing(pair.Id, pair.DeviceSecret, default);
        var tv = linked.DeviceId!.Value;
        var discover = await linker.Start(userId, new(tv, "discover"), default);
        var work = await linker.Next(userId, tv, default);
        Assert.Equal(discover.Id, work!.Id);
        Assert.Null(await linker.Next("different-user", tv, default));
        Assert.Null(await linker.Status("different-user", discover.Id, default));
        Assert.False(await linker.Complete(userId, Guid.NewGuid(), discover.Id, new([new("http://192.168.7.216:2283/", "Immich")]), default));
        Assert.True(await linker.Complete(userId, tv, discover.Id, new([new("http://192.168.7.216:2283/", "Immich")]), default));
        Assert.Single((await linker.Status(userId, discover.Id, default))!.Servers!);
        var login = await linker.Start(userId, new(tv, "login", 0, "http://darklingnas:2283", "kelli@example.test", "fixture-password", "Kelli's Immich"), default);
        await linker.Next(userId, tv, default);
        await using (var db = await source.OpenConnectionAsync())
        await using (var cmd = new NpgsqlCommand("SELECT encrypted_request FROM kanvas.service_requests WHERE id=$1", db))
        { cmd.Parameters.AddWithValue(login.Id); Assert.DoesNotContain("fixture-password", Encoding.UTF8.GetString((byte[])(await cmd.ExecuteScalarAsync())!)); }
        Assert.True(await linker.Complete(userId, tv, login.Id, new([], "fixture-api-key"), default));
        Assert.False(await linker.Complete(userId, tv, login.Id, new([], "fixture-api-key"), default));
        var state = await store.State(userId, default);
        var connection = Assert.Single(state.Connections);
        Assert.Equal("fixture-api-key", connection.Configuration["secret"]!.GetValue<string>());
        Assert.DoesNotContain("fixture-password", connection.Configuration.ToJsonString());
        Assert.DoesNotContain("fixture-api-key", System.Text.Json.JsonSerializer.Serialize(await linker.Status(userId, login.Id, default)));
        await using (var db = await source.OpenConnectionAsync())
        await using (var cmd = new NpgsqlCommand("SELECT encrypted_request FROM kanvas.service_requests WHERE id=$1", db))
        { cmd.Parameters.AddWithValue(login.Id); Assert.Equal(DBNull.Value, await cmd.ExecuteScalarAsync()); }
        var revoked = await linker.Start(userId, new(tv, "discover"), default);
        await linker.Next(userId, tv, default);
        await store.RevokeDevice(userId, tv, default);
        Assert.False(await linker.Complete(userId, tv, revoked.Id, new([]), default));
    }
    [Fact] public async Task Http_auth_enforces_csrf_device_scope_and_metadata_only_browser_responses()
    {
        using var factory = new Factory(options);
        using var client = factory.CreateClient(new() { BaseAddress = new(options.PublicOrigin), AllowAutoRedirect = false });
        var login = await client.GetAsync("/api/auth/login?returnTo=/pair%3Fcode%3DABCDEFGH");
        var query = QueryHelpers.ParseQuery(login.Headers.Location!.Query);
        var callback = await client.GetAsync("/api/auth/callback?code=fixture-code&state=" + query["state"]);
        Assert.Equal("/pair?code=ABCDEFGH", callback.Headers.Location!.OriginalString);
        Assert.Equal(43, factory.WorkOs.Verifier!.Length);
        var account = (await client.GetFromJsonAsync<JsonObject>("/api/account/"))!;
        var authenticatedUser = account["user"]!["id"]!.GetValue<string>();
        Assert.Equal(HttpStatusCode.Forbidden, (await client.PutAsJsonAsync("/api/account/settings", new SettingsUpdate(0, Settings()))).StatusCode);
        client.DefaultRequestHeaders.Add("Origin", "https://evil.example");
        client.DefaultRequestHeaders.Add("X-CSRF-Token", account["csrfToken"]!.GetValue<string>());
        Assert.Equal(HttpStatusCode.Forbidden, (await client.PutAsJsonAsync("/api/account/settings", new SettingsUpdate(0, Settings()))).StatusCode);
        client.DefaultRequestHeaders.Remove("Origin"); client.DefaultRequestHeaders.Add("Origin", options.PublicOrigin);
        Assert.Equal(HttpStatusCode.NoContent, (await client.PutAsJsonAsync("/api/account/connections", new ConnectionUpdate(0, Connection()))).StatusCode);
        var browserBody = await client.GetStringAsync("/api/account/");
        Assert.DoesNotContain("fixture-only-password", browserBody); Assert.DoesNotContain("configuration", browserBody);
        Assert.Equal(HttpStatusCode.NoContent, (await client.PutAsJsonAsync("/api/account/connections/fixture-source/credential", new CredentialUpdate(1, "renewed-fixture-password"))).StatusCode);
        var updated = await store.State(authenticatedUser, default);
        Assert.Equal("root", Assert.Single(Assert.Single(updated.Connections).Roots).ObjectId);
        Assert.Equal("renewed-fixture-password", updated.Connections.Single().Configuration["secret"]!.GetValue<string>());
        Assert.Equal(HttpStatusCode.Conflict, (await client.PutAsJsonAsync("/api/account/connections/fixture-source/credential", new CredentialUpdate(1, "stale-password"))).StatusCode);
        var pair = await client.PostAsJsonAsync("/api/pairings", new PairRequest("HTTP test TV"));
        var started = (await pair.Content.ReadFromJsonAsync<PairStart>())!;
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsJsonAsync("/api/account/pairings/approve", new PairApprove(started.UserCode))).StatusCode);
        var poll = await client.PostAsJsonAsync($"/api/pairings/{started.Id}/poll", new PairPoll(started.DeviceSecret));
        var granted = (await poll.Content.ReadFromJsonAsync<PairResult>())!;
        using var tv = factory.CreateClient(new() { BaseAddress = new(options.PublicOrigin), HandleCookies = false });
        tv.DefaultRequestHeaders.Authorization = new("Bearer", granted.Token);
        Assert.Contains("renewed-fixture-password", await tv.GetStringAsync("/api/device/state"));
        Assert.Equal(HttpStatusCode.Unauthorized, (await tv.GetAsync("/api/account/")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/device/state")).StatusCode);
        var browseResponse = await client.PostAsJsonAsync("/api/account/browse", new BrowseRequest(granted.DeviceId!.Value, "fixture-source"));
        Assert.Equal(HttpStatusCode.OK, browseResponse.StatusCode);
        var browse = (await browseResponse.Content.ReadFromJsonAsync<BrowseStatus>())!;
        var claimed = (await (await tv.PostAsJsonAsync("/api/device/browse/next", new { })).Content.ReadFromJsonAsync<BrowseWork>())!;
        Assert.Equal(browse.Id, claimed.Id);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync("/api/device/browse/next", new { })).StatusCode);
        var result = new BrowseListing("root", "Photos", [new("album:kanvas", "Kanvas")], null, 0);
        Assert.Equal(HttpStatusCode.NoContent, (await tv.PostAsJsonAsync($"/api/device/browse/{browse.Id}/complete", result)).StatusCode);
        Assert.Contains("Kanvas", await client.GetStringAsync($"/api/account/browse/{browse.Id}"));
        Assert.Equal(HttpStatusCode.NoContent, (await client.PutAsJsonAsync("/api/account/connections/fixture-source/roots", new RootsUpdate(2, [new("album:kanvas", "Kanvas")]))).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await client.PutAsJsonAsync("/api/account/connections/fixture-source/roots", new RootsUpdate(2, []))).StatusCode);
        var selected = (await tv.GetFromJsonAsync<CloudState>("/api/device/state"))!;
        Assert.True(selected.Settings["accountPhotosOnly"]!.GetValue<bool>());
        Assert.Equal("album:kanvas", Assert.Single(selected.Connections.Single().Roots).ObjectId);
        Assert.Equal("renewed-fixture-password", selected.Connections.Single().Configuration["secret"]!.GetValue<string>());
        await client.DeleteAsync("/api/account/devices/" + granted.DeviceId);
        Assert.Equal(HttpStatusCode.Unauthorized, (await tv.GetAsync("/api/device/state")).StatusCode);
    }
    [Fact] public async Task Wrong_auth_state_cannot_create_browser_session()
    {
        using var factory = new Factory(options);
        using var client = factory.CreateClient(new() { BaseAddress = new(options.PublicOrigin), AllowAutoRedirect = false });
        await client.GetAsync("/api/auth/login?returnTo=/");
        var callback = await client.GetAsync("/api/auth/callback?code=fixture-code&state=" + Secrets.Token());
        Assert.Equal("/?error=signin", callback.Headers.Location!.OriginalString);
        Assert.Null(factory.WorkOs.Verifier);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/account/")).StatusCode);
    }
    [Fact] public async Task Phone_browsing_is_encrypted_owned_claimed_once_and_survives_sync()
    {
        Assert.True(await store.SaveConnection(userId, new(0, Connection() with { Roots = [] }), default));
        var pair = await store.StartPairing("Browse TV", default);
        Assert.True(await store.ApprovePairing(pair.UserCode, userId, default));
        var device = (await store.PollPairing(pair.Id, pair.DeviceSecret, default)).DeviceId!.Value;
        var browser = new PhotoBrowser(source, store, new Secrets(options));
        var request = await browser.Start(userId, new(device, "fixture-source"), default);
        Assert.Null(await browser.Status("another-user", request.Id, default));
        Assert.Null(await browser.Next("another-user", device, default));
        var state = await store.State(userId, default);
        Assert.True(await store.SaveState(userId, new(state.Revision, state.Settings, state.Connections), default));
        var work = await browser.Next(userId, device, default);
        Assert.Equal(request.Id, work!.Id);
        Assert.Equal("fixture-only-password", work.Connection.Configuration["secret"]!.GetValue<string>());
        Assert.Null(await browser.Next(userId, device, default));
        await using (var cmd = source.CreateCommand("SELECT encrypted_request FROM kanvas.browse_requests WHERE id=@id")) {
            cmd.Parameters.AddWithValue("id", request.Id);
            Assert.DoesNotContain("fixture-only-password", Encoding.UTF8.GetString((byte[])(await cmd.ExecuteScalarAsync())!));
        }
        var listing = new BrowseListing("root", "Family photographs", [new("album:one", "Kanvas")], null, 0);
        Assert.False(await browser.Complete(userId, Guid.NewGuid(), request.Id, listing, default));
        Assert.True(await browser.Complete(userId, device, request.Id, listing, default));
        Assert.False(await browser.Complete(userId, device, request.Id, listing, default));
        var result = await browser.Status(userId, request.Id, default);
        Assert.Equal("complete", result!.Status);
        Assert.Equal("Kanvas", Assert.Single(result.Listing!.Folders).Name);
        Assert.DoesNotContain("fixture-only-password", System.Text.Json.JsonSerializer.Serialize(result));
        Assert.NotNull(Assert.Single(await store.Devices(userId, default)).LastSeenAt);
        await Assert.ThrowsAsync<ArgumentException>(() => browser.Start(userId, new(Guid.NewGuid(), "fixture-source"), default));
        var expired = await browser.Start(userId, new(device, "fixture-source"), default);
        await using (var cmd = source.CreateCommand("UPDATE kanvas.browse_requests SET expires_at=now()-interval '1 second' WHERE id=@id")) { cmd.Parameters.AddWithValue("id", expired.Id); await cmd.ExecuteNonQueryAsync(); }
        Assert.Equal("expired", (await browser.Status(userId, expired.Id, default))!.Status);
        Assert.Null(await browser.Next(userId, device, default));
        await store.RevokeDevice(userId, device, default);
        Assert.Null(await browser.Next(userId, device, default));
    }
    private sealed class FakeWorkOs : IWorkOs
    {
        public string? Verifier;
        private readonly string user = "user_http_" + Guid.NewGuid().ToString("N");
        public string Authorize(string state, string verifier) => "https://workos.example/login?state=" + state;
        public Task<AccountUser?> Authenticate(string code, string verifier, CancellationToken ct) { Verifier = verifier; return Task.FromResult<AccountUser?>(new(user, "fixture@example.test", "Kelli Fixture")); }
    }
    private sealed class Factory(ServerOptions options) : WebApplicationFactory<Program>
    {
        public readonly FakeWorkOs WorkOs = new();
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Testing");
            builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string,string?>
            {
                ["PUBLIC_BASE_URL"] = options.PublicOrigin, ["ConnectionStrings:DefaultConnection"] = Database,
                ["CONNECTION_ENCRYPTION_KEY"] = options.EncryptionKey, ["WORKOS_CLIENT_ID"] = options.WorkOsClientId, ["WORKOS_API_KEY"] = options.WorkOsApiKey
            }));
            builder.ConfigureTestServices(services => { services.RemoveAll<IWorkOs>(); services.AddSingleton<IWorkOs>(WorkOs); });
        }
    }
}
