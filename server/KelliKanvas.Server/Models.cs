using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace KelliKanvas.Server;

public sealed record AccountUser(string Id, string Email, string Name);
public sealed record AccountSession(AccountUser User, string Kind, Guid? DeviceId);
public sealed record Device(Guid Id, string Name, DateTime CreatedAt, DateTime? LastSeenAt = null);
public sealed record RootsUpdate(long ExpectedRevision, Root[] Roots, bool AccountPhotosOnly = true);
public sealed record Root(string ObjectId, string Name, bool IncludeDescendants = true, string[]? IncludedFilterIds = null);
public sealed record PhotoConnection(string Id, string Provider, string Name, JsonObject Configuration, Root[] Roots)
{
    public override string ToString() => $"PhotoConnection({Provider}, <redacted>)";
}
public sealed record CloudState(long Revision, JsonObject Settings, PhotoConnection[] Connections);
public sealed record StateUpdate(long ExpectedRevision, JsonObject Settings, PhotoConnection[] Connections);
public sealed record SettingsUpdate(long ExpectedRevision, JsonObject Settings);
public sealed record ConnectionUpdate(long ExpectedRevision, PhotoConnection Connection);
public sealed record CredentialUpdate(long ExpectedRevision, string Secret);
public sealed record PairRequest(string Name);
public sealed record PairStart(Guid Id, string DeviceSecret, string UserCode, string VerificationUri, int ExpiresIn = 600, int Interval = 5);
public sealed record PairDetails(Guid Id, string Name, string UserCode);
public sealed record PairApprove(string UserCode);
public sealed record PairPoll(string DeviceSecret);
public sealed record PairResult(string Status, string? Token = null, Guid? DeviceId = null, AccountUser? User = null);

public static partial class Validation
{
    public static readonly string[] Providers = ["SMB", "JELLYFIN", "EMBY", "PLEX", "IMMICH", "NEXTCLOUD", "OWNCLOUD",
        "SYNOLOGY", "SEAFILE", "WEBDAV", "PHOTOPRISM", "DROPBOX", "ONEDRIVE", "BOX", "S3", "FLICKR", "GOOGLE_DRIVE", "GOOGLE_PHOTOS"];
    private static readonly Dictionary<string, string[]> Enums = new()
    {
        ["theme"] = ["KELLI", "GALLERY", "MIDNIGHT", "PAPER", "SYSTEM"],
        ["landscapeLayout"] = ["FULL_PHOTO", "FILL_SCREEN", "BLURRED_BORDER", "SOLID_BACKGROUND", "STRETCH"],
        ["singlePortraitLayout"] = ["FULL_PHOTO", "FILL_SCREEN", "BLURRED_BORDER", "SOLID_BACKGROUND", "STRETCH"],
        ["singlePortraitFit"] = ["FULL_HEIGHT", "FILL_SCREEN"], ["portraitPairingMode"] = ["AUTO", "OFF", "ALWAYS"],
        ["blurStrength"] = ["LOW", "MEDIUM", "HIGH"], ["transitionType"] = ["CUT", "CROSSFADE", "FADE_THROUGH_BLACK", "SLIDE_LEFT", "SLIDE_RIGHT", "PAN_ZOOM", "RANDOM"],
        ["playbackOrder"] = ["SHUFFLE", "NAME", "CAPTURE_DATE_ASC", "CAPTURE_DATE_DESC", "MODIFIED_DATE_ASC", "MODIFIED_DATE_DESC"], ["newPhotosPolicy"] = ["NEXT_CYCLE"],
        ["brightnessMode"] = ["FOLLOW_TV", "AMBIENT_SENSOR", "SCHEDULE"]
    };
    private static readonly string[] Bools = ["loopEnabled", "resumeEnabled", "metadataOverlayEnabled", "clockOverlayEnabled",
        "captureDateOverlayEnabled", "filenameOverlayEnabled", "presenceEnabled", "reducedMotion", "accountPhotosOnly"];
    public static void Settings(JsonObject settings)
    {
        if (settings == null || settings.Count > 32) throw new ArgumentException("Too many settings.");
        foreach (var (key, node) in settings)
        {
            if (node is not JsonValue value) throw new ArgumentException("Invalid setting value.");
            if (Enums.TryGetValue(key, out var values))
            {
                if (!value.TryGetValue<string>(out var text) || !values.Contains(text)) throw new ArgumentException("Unknown setting choice.");
            }
            else if (Bools.Contains(key)) { if (!value.TryGetValue<bool>(out _)) throw new ArgumentException("Invalid boolean setting."); }
            else
            {
                var range = key switch
                {
                    "slideDurationMillis" => (1000d, 86400000d), "transitionDurationMillis" => (0d, 60000d),
                    "portraitLookAhead" => (1d, 4d), "pairGutterDp" => (0d, 512d), "blurDimAmount" => (0d, 1d),
                    _ => throw new ArgumentException("Unknown setting.")
                };
                if (!value.TryGetValue<double>(out var n) || !double.IsFinite(n) || n < range.Item1 || n > range.Item2 ||
                    (key != "blurDimAmount" && n != Math.Truncate(n))) throw new ArgumentException("Setting is out of range.");
            }
        }
        if ((settings["transitionDurationMillis"]?.GetValue<double>() ?? 700) >= (settings["slideDurationMillis"]?.GetValue<double>() ?? 15000))
            throw new ArgumentException("Transition must be shorter than the slide duration.");
    }
    public static void Connection(PhotoConnection connection)
    {
        if (connection == null || connection.Id == "kanvas-account-v1" || connection.Id == null || connection.Configuration == null || connection.Roots == null || !IdPattern().IsMatch(connection.Id) || !Providers.Contains(connection.Provider) || string.IsNullOrWhiteSpace(connection.Name) || connection.Name.Length > 128)
            throw new ArgumentException("Invalid connection.");
        if (connection.Configuration.ToJsonString().Length > 20000 || connection.Roots.Length > 128) throw new ArgumentException("Connection is too large.");
        if (connection.Roots.Any(r => r == null || r.Name == null || string.IsNullOrWhiteSpace(r.ObjectId) || r.ObjectId.Length > 2048 || r.Name.Length > 256 || (r.IncludedFilterIds?.Length ?? 0) > 256))
            throw new ArgumentException("Invalid folder.");
        if (connection.Roots.Select(r => r.ObjectId).Distinct().Count() != connection.Roots.Length) throw new ArgumentException("Duplicate folder.");
        if (connection.Provider.StartsWith("GOOGLE_", StringComparison.Ordinal))
        {
            if (connection.Configuration["vault"] is not JsonValue v || !v.TryGetValue<string>(out var vault) || vault.Length > 16384)
                throw new ArgumentException("Invalid Google backup.");
            return;
        }
        var config = connection.Configuration;
        if (string.IsNullOrWhiteSpace(config["secret"]?.GetValue<string>()) || config["secret"]!.GetValue<string>().Length > 4096)
            throw new ArgumentException("Enter a password or access token.");
        if (connection.Provider == "SMB")
        {
            if (string.IsNullOrWhiteSpace(config["host"]?.GetValue<string>()) || string.IsNullOrWhiteSpace(config["username"]?.GetValue<string>()) ||
                string.IsNullOrWhiteSpace(config["share"]?.GetValue<string>()) || config["share"]!.GetValue<string>().IndexOfAny(['/', '\\']) >= 0 ||
                config["port"]?.GetValue<int>() is not >= 1 or > 65535)
                throw new ArgumentException("Enter the NAS host, port, share and username.");
        }
        else if (!Uri.TryCreate(config["endpoint"]?.GetValue<string>(), UriKind.Absolute, out var endpoint) ||
            endpoint.Scheme is not ("https" or "http") || endpoint.UserInfo.Length > 0 || endpoint.Query.Length > 0 || endpoint.Fragment.Length > 0)
            throw new ArgumentException("Enter the server URL without login details.");
    }
    public static void State(StateUpdate update)
    {
        Settings(update.Settings);
        if (update.Connections.Length > 64 || update.Connections.Select(c => c.Id).Distinct().Count() != update.Connections.Length)
            throw new ArgumentException("Invalid connection list.");
        foreach (var connection in update.Connections) Connection(connection);
    }
    [GeneratedRegex("^[A-Za-z0-9_-]{1,160}$")] private static partial Regex IdPattern();
}
