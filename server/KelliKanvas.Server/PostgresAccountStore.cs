using System.Text.Json;
using System.Text.Json.Nodes;
using Npgsql;

namespace KelliKanvas.Server;

public sealed class PostgresAccountStore(NpgsqlDataSource source, Secrets secrets, ServerOptions options) : IAccountStore
{
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static NpgsqlCommand Command(NpgsqlConnection db, string sql, params object?[] values)
    {
        var cmd = new NpgsqlCommand(sql, db);
        foreach (var value in values) cmd.Parameters.Add(new NpgsqlParameter { Value = value ?? DBNull.Value });
        return cmd;
    }
    public async Task Initialize(CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var transaction = await db.BeginTransactionAsync(ct);
        await using (var mutex = Command(db, "SELECT pg_advisory_xact_lock(684611904)")) await mutex.ExecuteNonQueryAsync(ct);
        await using (var cmd = Command(db, await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "schema.sql"), ct)))
            await cmd.ExecuteNonQueryAsync(ct);
        await transaction.CommitAsync(ct);
    }
    public async Task UpsertUser(AccountUser user, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await using (var cmd = Command(db, "INSERT INTO kanvas.users(id,email,name) VALUES($1,$2,$3) ON CONFLICT(id) DO UPDATE SET email=$2,name=$3", user.Id, user.Email, user.Name)) await cmd.ExecuteNonQueryAsync(ct);
        await using (var cmd = Command(db, "INSERT INTO kanvas.account_state(user_id) VALUES($1) ON CONFLICT DO NOTHING", user.Id)) await cmd.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
    }
    public async Task<string> CreateSession(string userId, string kind, Guid? deviceId, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        return await InsertSession(db, userId, kind, deviceId, ct);
    }
    private static async Task<string> InsertSession(NpgsqlConnection db, string userId, string kind, Guid? deviceId, CancellationToken ct)
    {
        var token = Secrets.Token();
        await using var cmd = Command(db, "INSERT INTO kanvas.sessions(token_hash,user_id,kind,device_id,expires_at) VALUES($1,$2,$3,$4,$5)",
            Secrets.Hash(token), userId, kind, deviceId, DateTime.UtcNow.Add(kind == "browser" ? TimeSpan.FromHours(12) : TimeSpan.FromDays(30)));
        // Typed NULL is required by PostgreSQL's prepared parameter inference.
        cmd.Parameters[3].NpgsqlDbType = NpgsqlTypes.NpgsqlDbType.Uuid;
        await cmd.ExecuteNonQueryAsync(ct);
        return token;
    }
    public async Task<AccountSession?> Session(string token, CancellationToken ct)
    {
        if (token.Length != 43) return null;
        await using var db = await source.OpenConnectionAsync(ct);
        await using var cmd = Command(db, "SELECT u.id,u.email,u.name,s.kind,s.device_id FROM kanvas.sessions s JOIN kanvas.users u ON u.id=s.user_id LEFT JOIN kanvas.devices d ON d.id=s.device_id WHERE s.token_hash=$1 AND s.expires_at>now() AND (s.device_id IS NULL OR d.revoked_at IS NULL)", Secrets.Hash(token));
        await using var row = await cmd.ExecuteReaderAsync(ct);
        return await row.ReadAsync(ct) ? new(new(row.GetString(0), row.GetString(1), row.GetString(2)), row.GetString(3), row.IsDBNull(4) ? null : row.GetGuid(4)) : null;
    }
    public async Task DeleteSession(string token, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var cmd = Command(db, "DELETE FROM kanvas.sessions WHERE token_hash=$1", Secrets.Hash(token));
        await cmd.ExecuteNonQueryAsync(ct);
    }
    public async Task<PairStart> StartPairing(string name, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Length > 80 || name.Any(char.IsControl)) throw new ArgumentException("Enter a device name of 1–80 characters.");
        await using var db = await source.OpenConnectionAsync(ct);
        await using (var clean = Command(db, "DELETE FROM kanvas.pairings WHERE expires_at<now()-interval '1 hour'; DELETE FROM kanvas.sessions WHERE expires_at<now()")) await clean.ExecuteNonQueryAsync(ct);
        const string alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var code = new string(Enumerable.Range(0, 8).Select(_ => alphabet[System.Security.Cryptography.RandomNumberGenerator.GetInt32(alphabet.Length)]).ToArray());
            var id = Guid.NewGuid(); var secret = Secrets.Token();
            await using var cmd = Command(db, "INSERT INTO kanvas.pairings(id,secret_hash,user_code,name,expires_at) VALUES($1,$2,$3,$4,now()+interval '10 minutes') ON CONFLICT(user_code) DO NOTHING", id, Secrets.Hash(secret), code, name.Trim());
            if (await cmd.ExecuteNonQueryAsync(ct) > 0) return new(id, secret, code, options.PublicOrigin + "/pair?code=" + code);
        }
        throw new InvalidOperationException("Could not create a pairing code.");
    }
    public async Task<PairDetails?> Pairing(string code, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var cmd = Command(db, "SELECT id,name,user_code FROM kanvas.pairings WHERE user_code=$1 AND expires_at>now() AND user_id IS NULL AND NOT delivered", code);
        await using var row = await cmd.ExecuteReaderAsync(ct);
        return await row.ReadAsync(ct) ? new(row.GetGuid(0), row.GetString(1), row.GetString(2)) : null;
    }
    public async Task<bool> ApprovePairing(string code, string userId, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        Guid id; string name;
        await using (var cmd = Command(db, "SELECT id,name FROM kanvas.pairings WHERE user_code=$1 AND expires_at>now() AND user_id IS NULL AND NOT delivered FOR UPDATE", code))
        await using (var row = await cmd.ExecuteReaderAsync(ct))
        { if (!await row.ReadAsync(ct)) return false; id = row.GetGuid(0); name = row.GetString(1); }
        var device = Guid.NewGuid();
        await using (var cmd = Command(db, "INSERT INTO kanvas.devices(id,user_id,name) VALUES($1,$2,$3)", device, userId, name)) await cmd.ExecuteNonQueryAsync(ct);
        await using (var cmd = Command(db, "UPDATE kanvas.pairings SET user_id=$1,device_id=$2 WHERE id=$3", userId, device, id)) await cmd.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct); return true;
    }
    public async Task<PairResult> PollPairing(Guid id, string secret, CancellationToken ct)
    {
        if (secret.Length != 43) return new("invalid");
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        string? userId; Guid? deviceId; bool delivered; DateTime expires;
        await using (var cmd = Command(db, "SELECT user_id,device_id,delivered,expires_at FROM kanvas.pairings WHERE id=$1 AND secret_hash=$2 FOR UPDATE", id, Secrets.Hash(secret)))
        await using (var row = await cmd.ExecuteReaderAsync(ct))
        {
            if (!await row.ReadAsync(ct)) return new("invalid");
            userId = row.IsDBNull(0) ? null : row.GetString(0); deviceId = row.IsDBNull(1) ? null : row.GetGuid(1); delivered = row.GetBoolean(2); expires = row.GetDateTime(3);
        }
        if (expires <= DateTime.UtcNow) return new("expired");
        if (delivered) return new("consumed");
        if (userId == null || deviceId == null) return new("pending");
        // Revocation may race with approval/polling. Lock the device as well.
        await using (var device = Command(db, "SELECT id FROM kanvas.devices WHERE id=$1 AND revoked_at IS NULL FOR UPDATE", deviceId))
            if (await device.ExecuteScalarAsync(ct) == null) return new("denied");
        var token = await InsertSession(db, userId, "device", deviceId, ct);
        await using (var cmd = Command(db, "UPDATE kanvas.pairings SET delivered=true WHERE id=$1", id)) await cmd.ExecuteNonQueryAsync(ct);
        AccountUser user;
        await using (var cmd = Command(db, "SELECT id,email,name FROM kanvas.users WHERE id=$1", userId))
        await using (var row = await cmd.ExecuteReaderAsync(ct)) { await row.ReadAsync(ct); user = new(row.GetString(0), row.GetString(1), row.GetString(2)); }
        await tx.CommitAsync(ct); return new("approved", token, deviceId, user);
    }
    public async Task CancelPairing(Guid id, string secret, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await using var cmd = Command(db, "UPDATE kanvas.pairings SET expires_at=now() WHERE id=$1 AND secret_hash=$2 AND NOT delivered RETURNING device_id", id, Secrets.Hash(secret));
        var device = await cmd.ExecuteScalarAsync(ct);
        if (device is Guid deviceId)
        { await using var revoke = Command(db, "UPDATE kanvas.devices SET revoked_at=now() WHERE id=$1", deviceId); await revoke.ExecuteNonQueryAsync(ct); }
        await tx.CommitAsync(ct);
    }
    public async Task<Device[]> Devices(string userId, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var cmd = Command(db, "SELECT id,name,created_at,last_seen_at FROM kanvas.devices WHERE user_id=$1 AND revoked_at IS NULL ORDER BY created_at DESC", userId);
        await using var rows = await cmd.ExecuteReaderAsync(ct); var result = new List<Device>();
        while (await rows.ReadAsync(ct)) result.Add(new(rows.GetGuid(0), rows.GetString(1), rows.GetDateTime(2), rows.IsDBNull(3) ? null : rows.GetDateTime(3)));
        return result.ToArray();
    }
    public async Task RevokeDevice(string userId, Guid id, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await using (var cmd = Command(db, "UPDATE kanvas.devices SET revoked_at=now() WHERE id=$1 AND user_id=$2", id, userId)) await cmd.ExecuteNonQueryAsync(ct);
        await using (var cmd = Command(db, "DELETE FROM kanvas.sessions WHERE device_id=$1 AND user_id=$2", id, userId)) await cmd.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
    }
    public async Task<CloudState> State(string userId, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        long revision; JsonObject settings;
        await using (var cmd = Command(db, "SELECT revision,settings::text FROM kanvas.account_state WHERE user_id=$1 FOR SHARE", userId))
        await using (var row = await cmd.ExecuteReaderAsync(ct))
        { if (!await row.ReadAsync(ct)) throw new InvalidOperationException("Account not found."); revision = row.GetInt64(0); settings = JsonNode.Parse(row.GetString(1))!.AsObject(); }
        var connections = new List<PhotoConnection>();
        await using (var cmd = Command(db, "SELECT id,encrypted_payload FROM kanvas.connections WHERE user_id=$1 ORDER BY id", userId))
        await using (var rows = await cmd.ExecuteReaderAsync(ct))
            while (await rows.ReadAsync(ct)) connections.Add(JsonSerializer.Deserialize<PhotoConnection>(secrets.Decrypt(rows.GetFieldValue<byte[]>(1), userId + ":" + rows.GetString(0)), Json)!);
        await tx.CommitAsync(ct); return new(revision, settings, connections.ToArray());
    }
    private async Task<bool> Mutate(string userId, long revision, Func<NpgsqlConnection, Task> change, CancellationToken ct)
    {
        await using var db = await source.OpenConnectionAsync(ct);
        await using var tx = await db.BeginTransactionAsync(ct);
        await using (var cmd = Command(db, "SELECT revision FROM kanvas.account_state WHERE user_id=$1 FOR UPDATE", userId))
        { if (await cmd.ExecuteScalarAsync(ct) is not long current || current != revision) return false; }
        await change(db);
        await using (var cmd = Command(db, "UPDATE kanvas.account_state SET revision=revision+1 WHERE user_id=$1", userId)) await cmd.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct); return true;
    }
    private static async Task Settings(NpgsqlConnection db, string userId, JsonObject settings, CancellationToken ct)
    {
        await using var cmd = Command(db, "UPDATE kanvas.account_state SET settings=$1::jsonb WHERE user_id=$2", settings.ToJsonString(), userId);
        await cmd.ExecuteNonQueryAsync(ct);
    }
    private async Task Connection(NpgsqlConnection db, string userId, PhotoConnection connection, CancellationToken ct)
    {
        var encrypted = secrets.Encrypt(JsonSerializer.Serialize(connection, Json), userId + ":" + connection.Id);
        await using var cmd = Command(db, "INSERT INTO kanvas.connections(user_id,id,provider,name,encrypted_payload) VALUES($1,$2,$3,$4,$5) ON CONFLICT(user_id,id) DO UPDATE SET provider=$3,name=$4,encrypted_payload=$5", userId, connection.Id, connection.Provider, connection.Name, encrypted);
        await cmd.ExecuteNonQueryAsync(ct);
    }
    public Task<bool> SaveState(string userId, StateUpdate update, CancellationToken ct) => Mutate(userId, update.ExpectedRevision, async db =>
    {
        await Settings(db, userId, update.Settings, ct);
        await using (var cmd = Command(db, "DELETE FROM kanvas.connections WHERE user_id=$1 AND NOT(id=ANY($2))", userId, update.Connections.Select(c => c.Id).ToArray())) await cmd.ExecuteNonQueryAsync(ct);
        foreach (var connection in update.Connections) await Connection(db, userId, connection, ct);
    }, ct);
    public Task<bool> SaveSettings(string userId, SettingsUpdate update, CancellationToken ct) => Mutate(userId, update.ExpectedRevision, db => Settings(db, userId, update.Settings, ct), ct);
    public Task<bool> SaveConnection(string userId, ConnectionUpdate update, CancellationToken ct) => Mutate(userId, update.ExpectedRevision, async db =>
    {
        await using (var cmd = Command(db, "SELECT count(*) FROM kanvas.connections WHERE user_id=$1 AND id<>$2", userId, update.Connection.Id))
            if ((long)(await cmd.ExecuteScalarAsync(ct))! >= 64) throw new ArgumentException("Maximum 64 connections per account.");
        await Connection(db, userId, update.Connection, ct);
    }, ct);
    public Task<bool> DeleteConnection(string userId, string id, long revision, CancellationToken ct) => Mutate(userId, revision, async db =>
    { await using var cmd = Command(db, "DELETE FROM kanvas.connections WHERE user_id=$1 AND id=$2", userId, id); await cmd.ExecuteNonQueryAsync(ct); }, ct);
}
