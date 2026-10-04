using System.Text.Json;
using System.Text.Json.Nodes;
using Npgsql;

namespace KelliKanvas.Server;

public sealed record ServiceLinkRequest(Guid DeviceId, string Operation, long ExpectedRevision = 0, string? Endpoint = null, string? Username = null, string? Secret = null, string? Name = null)
{
    public override string ToString() => $"ServiceLinkRequest({Operation}, <redacted>)";
}
public sealed record DiscoveredService(string Endpoint, string Name);
public sealed record ServiceLinkWork(Guid Id, string Operation, string[] Candidates, string? Endpoint, string? Username, string? Secret, string? Name)
{
    public override string ToString() => $"ServiceLinkWork({Id}, {Operation}, <redacted>)";
}
public sealed record ServiceLinkResult(DiscoveredService[] Servers, string? Secret = null, string? Error = null)
{
    public override string ToString() => $"ServiceLinkResult({Error}, <redacted>)";
}
public sealed record ServiceLinkStatus(Guid Id, string Status, DiscoveredService[]? Servers = null, string? ConnectionId = null, string? Error = null);

/// <summary>Temporary encrypted integration work executed inside the owner's home network.</summary>
public sealed class ServiceLinker(NpgsqlDataSource source, IAccountStore accounts, Secrets secrets)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static NpgsqlCommand Command(NpgsqlConnection db, string sql, params object?[] values)
    {
        var command = new NpgsqlCommand(sql, db);
        foreach (var value in values) command.Parameters.Add(new NpgsqlParameter { Value = value ?? DBNull.Value });
        return command;
    }
    public async Task<ServiceLinkStatus> Start(string userId, ServiceLinkRequest request, CancellationToken ct)
    {
        if (request.Operation is not ("discover" or "login" or "key")) throw new ArgumentException("Unsupported integration.");
        var state = await accounts.State(userId, ct);
        if (request.Operation != "discover")
        {
            Validation.Connection(new("integration-check", "IMMICH", request.Name ?? "Immich", new JsonObject { ["endpoint"] = request.Endpoint, ["secret"] = request.Secret }, []));
            if (request.Operation == "login" && (string.IsNullOrWhiteSpace(request.Username) || request.Username.Length > 256)) throw new ArgumentException("Enter your Immich email.");
            if (request.ExpectedRevision != state.Revision) throw new InvalidOperationException("Account changed.");
            if (state.Connections.Length >= 64) throw new ArgumentException("Too many connections.");
        }
        var candidates = state.Connections.Select(c => c.Provider == "SMB" ? c.Configuration["host"]?.GetValue<string>() : c.Provider == "IMMICH" ? c.Configuration["endpoint"]?.GetValue<string>() : null)
            .Where(c => !string.IsNullOrWhiteSpace(c)).Distinct().Take(64).Cast<string>().ToArray();
        var id = Guid.NewGuid();
        var work = new ServiceLinkWork(id, request.Operation, candidates, request.Endpoint, request.Username, request.Secret, request.Name);
        var encrypted = secrets.Encrypt(JsonSerializer.Serialize(work, Json), userId + ":service:" + id);
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await using (var gate = Command(db, "SELECT user_id FROM kanvas.account_state WHERE user_id=$1 FOR UPDATE", userId)) await gate.ExecuteScalarAsync(ct);
        await using (var cleanup = Command(db, "DELETE FROM kanvas.service_requests WHERE expires_at<now()")) await cleanup.ExecuteNonQueryAsync(ct);
        await using (var device = Command(db, "SELECT id FROM kanvas.devices WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL", request.DeviceId, userId))
            if (await device.ExecuteScalarAsync(ct) == null) throw new ArgumentException("Link a screen first.");
        await using (var count = Command(db, "SELECT count(*) FROM kanvas.service_requests WHERE user_id=$1 AND status<>'complete'", userId))
            if ((long)(await count.ExecuteScalarAsync(ct))! >= 3) throw new ArgumentException("Wait for your screen.");
        await using (var insert = Command(db, "INSERT INTO kanvas.service_requests(id,user_id,device_id,operation,expected_revision,encrypted_request,expires_at) VALUES($1,$2,$3,$4,$5,$6,now()+interval '2 minutes')", id, userId, request.DeviceId, request.Operation, request.ExpectedRevision, encrypted)) await insert.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
        return new(id, "pending");
    }
    public async Task<ServiceLinkWork?> Next(string userId, Guid deviceId, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using (var cleanup = Command(db, "DELETE FROM kanvas.service_requests WHERE expires_at<now()")) await cleanup.ExecuteNonQueryAsync(ct);
        await using var command = Command(db, "UPDATE kanvas.service_requests SET status='working',claimed_at=now() WHERE id=(SELECT r.id FROM kanvas.service_requests r JOIN kanvas.devices d ON d.id=r.device_id AND d.user_id=r.user_id WHERE r.user_id=$1 AND r.device_id=$2 AND d.revoked_at IS NULL AND r.expires_at>now() AND (r.status='pending' OR (r.status='working' AND r.claimed_at<now()-interval '90 seconds')) ORDER BY r.expires_at LIMIT 1 FOR UPDATE OF r SKIP LOCKED) RETURNING id,encrypted_request", userId, deviceId);
        await using var row = await command.ExecuteReaderAsync(ct);
        return await row.ReadAsync(ct) ? JsonSerializer.Deserialize<ServiceLinkWork>(secrets.Decrypt(row.GetFieldValue<byte[]>(1), userId + ":service:" + row.GetGuid(0)), Json) : null;
    }
    public async Task<ServiceLinkStatus?> Status(string userId, Guid id, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var command = Command(db, "SELECT status,encrypted_result,expires_at FROM kanvas.service_requests WHERE id=$1 AND user_id=$2", id, userId);
        await using var row = await command.ExecuteReaderAsync(ct);
        if (!await row.ReadAsync(ct)) return null;
        if (row.GetDateTime(2) <= DateTime.UtcNow) return new(id, "expired");
        return row.IsDBNull(1) ? new(id, row.GetString(0)) : JsonSerializer.Deserialize<ServiceLinkStatus>(secrets.Decrypt(row.GetFieldValue<byte[]>(1), userId + ":service-result:" + id), Json);
    }
    public async Task<bool> Complete(string userId, Guid deviceId, Guid id, ServiceLinkResult result, CancellationToken ct)
    {
        if (result.Servers == null || result.Servers.Length > 32 || result.Secret?.Length > 4096 || result.Error is not (null or "authentication" or "network" or "unavailable" or "password_change")) throw new ArgumentException("Invalid integration response.");
        foreach (var server in result.Servers)
            if (server == null || server.Name.Length > 128 || !Uri.TryCreate(server.Endpoint, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") || uri.UserInfo.Length > 0 || uri.Query.Length > 0 || uri.Fragment.Length > 0) throw new ArgumentException("Invalid service.");
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        byte[] encrypted;
        long revision;
        await using (var command = Command(db, "SELECT encrypted_request,expected_revision FROM kanvas.service_requests r WHERE id=$1 AND r.user_id=$2 AND device_id=$3 AND status='working' AND expires_at>now() AND EXISTS(SELECT 1 FROM kanvas.devices d WHERE d.id=r.device_id AND d.user_id=r.user_id AND d.revoked_at IS NULL) FOR UPDATE", id, userId, deviceId))
        await using (var row = await command.ExecuteReaderAsync(ct))
        {
            if (!await row.ReadAsync(ct)) return false;
            encrypted = row.GetFieldValue<byte[]>(0); revision = row.GetInt64(1);
        }
        var work = JsonSerializer.Deserialize<ServiceLinkWork>(secrets.Decrypt(encrypted, userId + ":service:" + id), Json)!;
        string? connectionId = null;
        var error = result.Error;
        if (work.Operation != "discover" && error == null)
        {
            if (string.IsNullOrWhiteSpace(result.Secret)) throw new ArgumentException("Missing integration credential.");
            connectionId = "cloud-" + id;
            var connection = new PhotoConnection(connectionId, "IMMICH", work.Name ?? "Immich", new JsonObject { ["endpoint"] = work.Endpoint, ["username"] = work.Username ?? "", ["secret"] = result.Secret }, []);
            Validation.Connection(connection);
            if (!await accounts.SaveConnection(userId, new(revision, connection), ct)) { error = "conflict"; connectionId = null; }
        }
        var status = new ServiceLinkStatus(id, "complete", work.Operation == "discover" ? result.Servers : [], connectionId, error);
        var response = secrets.Encrypt(JsonSerializer.Serialize(status, Json), userId + ":service-result:" + id);
        await using (var finish = Command(db, "UPDATE kanvas.service_requests SET status='complete',encrypted_request=NULL,encrypted_result=$1 WHERE id=$2", response, id)) await finish.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
        return true;
    }
}
