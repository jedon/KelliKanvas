namespace KelliKanvas.Server;

public interface IAccountStore
{
    Task Initialize(CancellationToken ct);
    Task UpsertUser(AccountUser user, CancellationToken ct);
    Task<string> CreateSession(string userId, string kind, Guid? deviceId, CancellationToken ct);
    Task<AccountSession?> Session(string token, CancellationToken ct);
    Task DeleteSession(string token, CancellationToken ct);
    Task<PairStart> StartPairing(string name, CancellationToken ct);
    Task<PairDetails?> Pairing(string code, CancellationToken ct);
    Task<bool> ApprovePairing(string code, string userId, CancellationToken ct);
    Task<PairResult> PollPairing(Guid id, string secret, CancellationToken ct);
    Task CancelPairing(Guid id, string secret, CancellationToken ct);
    Task<Device[]> Devices(string userId, CancellationToken ct);
    Task RevokeDevice(string userId, Guid id, CancellationToken ct);
    Task<CloudState> State(string userId, CancellationToken ct);
    Task<bool> SaveState(string userId, StateUpdate update, CancellationToken ct);
    Task<bool> SaveSettings(string userId, SettingsUpdate update, CancellationToken ct);
    Task<bool> SaveConnection(string userId, ConnectionUpdate update, CancellationToken ct);
    Task<bool> DeleteConnection(string userId, string id, long revision, CancellationToken ct);
}
