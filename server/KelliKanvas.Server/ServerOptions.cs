namespace KelliKanvas.Server;

public sealed record ServerOptions(string PublicOrigin, string Database, string EncryptionKey,
    string WorkOsClientId, string WorkOsApiKey, string? OrganizationId, string[] AllowedEmails)
{
    public static ServerOptions FromConfiguration(IConfiguration config, bool development)
    {
        string Required(string name) => config[name]?.Trim() is { Length: > 0 } value ? value
            : throw new InvalidOperationException($"Configure {name} before starting the server.");
        var origin = new Uri(Required("PUBLIC_BASE_URL"));
        if (origin.UserInfo.Length > 0 || origin.Query.Length > 0 || origin.Fragment.Length > 0 || origin.AbsolutePath != "/" ||
            (origin.Scheme != "https" && !(development && origin.Scheme == "http" && origin.IsLoopback)))
            throw new InvalidOperationException("PUBLIC_BASE_URL must be an HTTPS origin (localhost HTTP is allowed in Development).");
        var key = Required("CONNECTION_ENCRYPTION_KEY");
        if (Convert.FromBase64String(key).Length != 32) throw new InvalidOperationException("CONNECTION_ENCRYPTION_KEY must be a base64 32-byte key.");
        return new(origin.GetLeftPart(UriPartial.Authority), Required("ConnectionStrings:DefaultConnection"), key,
            Required("WORKOS_CLIENT_ID"), Required("WORKOS_API_KEY"), config["WORKOS_ORGANIZATION_ID"],
            (config["ALLOWED_EMAILS"] ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
    }
    public string Callback => PublicOrigin + "/api/auth/callback";
    public bool SecureCookies => PublicOrigin.StartsWith("https://", StringComparison.Ordinal);
}
