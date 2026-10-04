using System.Text.Json;
using Npgsql;

namespace KelliKanvas.Server;

public sealed record BrowseRequest(Guid DeviceId, string ConnectionId, string? ObjectId = null, string? Cursor = null);
public sealed record BrowseFolder(string ObjectId, string Name);
public sealed record BrowseListing(string ObjectId, string Name, BrowseFolder[] Folders, string? NextCursor, int PhotoCount, string? Error = null);
public sealed record BrowseWork(Guid Id, PhotoConnection Connection, string? ObjectId, string? Cursor);
public sealed record BrowseStatus(Guid Id, string Status, BrowseListing? Listing = null);

/// <summary>Encrypted, short-lived directory requests served by the owner's linked TV.</summary>
public sealed class PhotoBrowser(NpgsqlDataSource source, IAccountStore accounts, Secrets secrets)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static NpgsqlCommand Command(NpgsqlConnection db, string sql, params object?[] values)
    {
        var command = new NpgsqlCommand(sql, db);
        foreach (var value in values) command.Parameters.Add(new NpgsqlParameter { Value = value ?? DBNull.Value });
        return command;
    }
    private static void Text(string? value, int limit)
    {
        if (value?.Length > limit || value?.Any(char.IsControl) == true) throw new ArgumentException("Invalid browsing request.");
    }
    public async Task<BrowseStatus> Start(string userId, BrowseRequest request, CancellationToken ct)
    {
        Text(request.ConnectionId, 128); Text(request.ObjectId, 2048); Text(request.Cursor, 4096);
        var state = await accounts.State(userId, ct);
        var connection = state.Connections.SingleOrDefault(c => c.Id == request.ConnectionId) ?? throw new ArgumentException("Connection not found.");
        if (connection.Provider.StartsWith("GOOGLE_", StringComparison.Ordinal)) throw new ArgumentException("Choose Google albums on the TV.");
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        // Serialize queue creation per account; an authenticated client cannot grow an unbounded queue.
        await using (var gate = Command(db, "SELECT user_id FROM kanvas.account_state WHERE user_id=$1 FOR UPDATE", userId)) await gate.ExecuteScalarAsync(ct);
        await using (var clean = Command(db, "DELETE FROM kanvas.browse_requests WHERE expires_at<now()")) await clean.ExecuteNonQueryAsync(ct);
        await using (var device = Command(db, "SELECT id FROM kanvas.devices WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL", request.DeviceId, userId))
            if (await device.ExecuteScalarAsync(ct) == null) throw new ArgumentException("Link a screen to this account first.");
        await using (var count = Command(db, "SELECT count(*) FROM kanvas.browse_requests WHERE user_id=$1 AND status<>'complete'", userId))
            if ((long)(await count.ExecuteScalarAsync(ct))! >= 5) throw new ArgumentException("Wait for your screen to finish browsing.");
        var id = Guid.NewGuid();
        var encrypted = secrets.Encrypt(JsonSerializer.Serialize(new BrowseWork(id, connection, request.ObjectId, request.Cursor), Json), userId + ":browse:" + id);
        await using (var insert = Command(db, "INSERT INTO kanvas.browse_requests(id,user_id,device_id,connection_id,encrypted_request,expires_at) VALUES($1,$2,$3,$4,$5,now()+interval '2 minutes')", id, userId, request.DeviceId, connection.Id, encrypted)) await insert.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
        return new(id, "pending");
    }
    public async Task<BrowseStatus?> Status(string userId, Guid id, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var command = Command(db, "SELECT status,encrypted_result,expires_at FROM kanvas.browse_requests WHERE id=$1 AND user_id=$2", id, userId);
        await using var row = await command.ExecuteReaderAsync(ct);
        if (!await row.ReadAsync(ct)) return null;
        if (row.GetDateTime(2) <= DateTime.UtcNow) return new(id, "expired");
        var listing = row.IsDBNull(1) ? null : JsonSerializer.Deserialize<BrowseListing>(secrets.Decrypt(row.GetFieldValue<byte[]>(1), userId + ":browse-result:" + id), Json);
        return new(id, row.GetString(0), listing);
    }
    public async Task<BrowseWork?> Next(string userId, Guid deviceId, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await using (var heartbeat = Command(db, "UPDATE kanvas.devices SET last_seen_at=now() WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL RETURNING id", deviceId, userId))
            if (await heartbeat.ExecuteScalarAsync(ct) == null) return null;
        byte[]? request = null;
        Guid id = Guid.Empty;
        await using (var claim = Command(db, "UPDATE kanvas.browse_requests SET status='working',claimed_at=now() WHERE id=(SELECT id FROM kanvas.browse_requests WHERE user_id=$1 AND device_id=$2 AND expires_at>now() AND (status='pending' OR (status='working' AND claimed_at<now()-interval '45 seconds')) ORDER BY expires_at LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING id,encrypted_request", userId, deviceId))
        await using (var row = await claim.ExecuteReaderAsync(ct))
            if (await row.ReadAsync(ct)) { id = row.GetGuid(0); request = row.GetFieldValue<byte[]>(1); }
        await tx.CommitAsync(ct);
        return request == null ? null : JsonSerializer.Deserialize<BrowseWork>(secrets.Decrypt(request, userId + ":browse:" + id), Json);
    }
    public async Task<bool> Complete(string userId, Guid deviceId, Guid id, BrowseListing listing, CancellationToken ct)
    {
        if (listing == null || listing.Folders == null || listing.Folders.Length > 200 || listing.PhotoCount is < 0 or > 200 || listing.Error is not (null or "authentication" or "network" or "unsupported" or "unavailable")) throw new ArgumentException("Invalid browsing response.");
        Text(listing.ObjectId, 2048); Text(listing.Name, 256); Text(listing.NextCursor, 4096);
        foreach (var folder in listing.Folders) { if (folder == null || string.IsNullOrWhiteSpace(folder.ObjectId)) throw new ArgumentException("Invalid folder."); Text(folder.ObjectId, 2048); Text(folder.Name, 256); }
        var encrypted = secrets.Encrypt(JsonSerializer.Serialize(listing, Json), userId + ":browse-result:" + id);
        await using var db = await source.OpenConnectionAsync(ct);
        await using var command = Command(db, "UPDATE kanvas.browse_requests SET status='complete',encrypted_result=$1 WHERE id=$2 AND user_id=$3 AND device_id=$4 AND status='working' AND expires_at>now()", encrypted, id, userId, deviceId);
        return await command.ExecuteNonQueryAsync(ct) == 1;
    }
}
