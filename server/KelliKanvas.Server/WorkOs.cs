using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.WebUtilities;

namespace KelliKanvas.Server;

public interface IWorkOs
{
    string Authorize(string state, string verifier);
    Task<AccountUser?> Authenticate(string code, string verifier, CancellationToken ct);
}

public sealed class WorkOs(HttpClient client, ServerOptions options) : IWorkOs
{
    public string Authorize(string state, string verifier)
    {
        var query = new Dictionary<string, string?> { ["client_id"] = options.WorkOsClientId, ["redirect_uri"] = options.Callback,
            ["response_type"] = "code", ["provider"] = "authkit", ["state"] = state,
            ["code_challenge"] = WebEncoders.Base64UrlEncode(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.ASCII.GetBytes(verifier))), ["code_challenge_method"] = "S256" };
        if (!string.IsNullOrWhiteSpace(options.OrganizationId)) query["organization_id"] = options.OrganizationId;
        return QueryHelpers.AddQueryString("https://api.workos.com/user_management/authorize", query);
    }
    public async Task<AccountUser?> Authenticate(string code, string verifier, CancellationToken ct)
    {
        using var response = await client.PostAsJsonAsync("https://api.workos.com/user_management/authenticate", new
        { client_id = options.WorkOsClientId, client_secret = options.WorkOsApiKey, grant_type = "authorization_code", code, code_verifier = verifier }, ct);
        if (!response.IsSuccessStatusCode) return null;
        var payload = await response.Content.ReadFromJsonAsync<JsonObject>(ct);
        if (payload?["user"] is not JsonObject user || user["email_verified"]?.GetValue<bool>() != true) return null;
        var id = user["id"]?.GetValue<string>(); var email = user["email"]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(id) || string.IsNullOrWhiteSpace(email)) return null;
        if (options.AllowedEmails.Length > 0 && !options.AllowedEmails.Contains(email, StringComparer.OrdinalIgnoreCase)) return null;
        var name = string.Join(" ", new[] { user["first_name"]?.GetValue<string>(), user["last_name"]?.GetValue<string>() }.Where(s => !string.IsNullOrWhiteSpace(s)));
        return new(id, email, name.Length > 0 ? name : email);
    }
}

public sealed record LoginState(string State, string Verifier, string ReturnTo, DateTime Expires);
public static class BrowserAuth
{
    public const string Cookie = "kanvas_session";
    public const string StateCookie = "kanvas_login";
    public static CookieOptions Cookies(ServerOptions options, TimeSpan age) => new()
    { HttpOnly = true, Secure = options.SecureCookies, SameSite = SameSiteMode.Lax, Path = "/", MaxAge = age, IsEssential = true };
    public static string SafeReturn(string? path)
    {
        if (path == "/") return path;
        if (path != null && System.Text.RegularExpressions.Regex.IsMatch(path, "^/pair\\?code=[A-Z2-9]{8}$")) return path;
        return "/";
    }
    public static LoginState? Validate(Secrets secrets, string? cookie, string? state)
    {
        try
        {
            if (cookie == null || state == null || cookie.Length > 4096 || state.Length != 43) return null;
            var login = JsonSerializer.Deserialize<LoginState>(secrets.Decrypt(WebEncoders.Base64UrlDecode(cookie), "oauth-state"));
            return login != null && login.Expires > DateTime.UtcNow && Secrets.Matches(login.State, state) ? login : null;
        }
        catch (Exception e) when (e is FormatException or System.Security.Cryptography.CryptographicException or JsonException or ArgumentException) { return null; }
    }
}
