using System.Text.Json;
using System.Threading.RateLimiting;
using KelliKanvas.Server;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.WebUtilities;
using Npgsql;

var builder = WebApplication.CreateBuilder(args);
builder.Logging.AddFilter("Microsoft.AspNetCore.Hosting.Diagnostics", LogLevel.Warning);
builder.WebHost.ConfigureKestrel(k => k.Limits.MaxRequestBodySize = 2 * 1024 * 1024);
builder.Services.AddSingleton(services => ServerOptions.FromConfiguration(services.GetRequiredService<IConfiguration>(), services.GetRequiredService<IHostEnvironment>().IsDevelopment()));
builder.Services.AddSingleton<Secrets>();
builder.Services.AddSingleton(services => NpgsqlDataSource.Create(services.GetRequiredService<ServerOptions>().Database));
builder.Services.AddSingleton<IAccountStore, PostgresAccountStore>();
builder.Services.AddSingleton<PhotoBrowser>();
builder.Services.AddSingleton<ServiceLinker>();
builder.Services.AddHttpClient<IWorkOs, WorkOs>(client => client.Timeout = TimeSpan.FromSeconds(20))
    .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });
builder.Services.Configure<ForwardedHeadersOptions>(forwarded =>
{
    forwarded.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    foreach (var cidr in (builder.Configuration["TRUSTED_PROXY_CIDRS"] ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        forwarded.KnownIPNetworks.Add(System.Net.IPNetwork.Parse(cidr));
    forwarded.ForwardLimit = 1;
});
builder.Services.AddRateLimiter(rate =>
{
    rate.RejectionStatusCode = 429;
    rate.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(ctx => RateLimitPartition.GetFixedWindowLimiter(
        ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new() { PermitLimit = 240, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
    rate.AddPolicy("pair-start", ctx => RateLimitPartition.GetFixedWindowLimiter(ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new() { PermitLimit = 10, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
});

var app = builder.Build();
var options = app.Services.GetRequiredService<ServerOptions>();
app.UseForwardedHeaders();
app.Use(async (ctx, next) =>
{
    ctx.Response.Headers["Cache-Control"] = "no-store";
    ctx.Response.Headers["Referrer-Policy"] = "no-referrer";
    ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
    ctx.Response.Headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
    if (options.SecureCookies) ctx.Response.Headers["Strict-Transport-Security"] = "max-age=31536000";
    try { await next(); }
    catch (OperationCanceledException) when (ctx.RequestAborted.IsCancellationRequested) { }
    catch (Exception error) when (!ctx.Response.HasStarted)
    {
        // Do not log provider responses, request bodies, tokens or credential-containing exceptions.
        var invalid = error is ArgumentException or JsonException or BadHttpRequestException or InvalidOperationException;
        ctx.Response.StatusCode = invalid ? 400 : 503;
        await ctx.Response.WriteAsJsonAsync(new { error = invalid ? "Check the submitted fields." : "The server is temporarily unavailable." }, ctx.RequestAborted);
    }
});
app.UseRateLimiter();
app.UseDefaultFiles();
app.UseStaticFiles();

app.MapGet("/health/live", () => Results.Ok(new { status = "ok" }));
app.MapGet("/health/ready", async (NpgsqlDataSource db, CancellationToken ct) =>
{
    await using var connection = await db.OpenConnectionAsync(ct);
    await using var command = new NpgsqlCommand("SELECT 1", connection);
    await command.ExecuteScalarAsync(ct); return Results.Ok(new { status = "ready" });
});
app.MapGet("/api/auth/login", (HttpContext ctx, string? returnTo, IWorkOs workOs, Secrets secrets) =>
{
    var login = new LoginState(Secrets.Token(), Secrets.Token(), BrowserAuth.SafeReturn(returnTo), DateTime.UtcNow.AddMinutes(10));
    ctx.Response.Cookies.Append(BrowserAuth.StateCookie, WebEncoders.Base64UrlEncode(secrets.Encrypt(JsonSerializer.Serialize(login), "oauth-state")), BrowserAuth.Cookies(options, TimeSpan.FromMinutes(10)));
    return Results.Redirect(workOs.Authorize(login.State, login.Verifier));
});
app.MapGet("/api/auth/callback", async (HttpContext ctx, string? code, string? state, string? error, IWorkOs workOs, Secrets secrets, IAccountStore store, CancellationToken ct) =>
{
    var login = BrowserAuth.Validate(secrets, ctx.Request.Cookies[BrowserAuth.StateCookie], state);
    if (login == null || error != null || string.IsNullOrWhiteSpace(code) || code.Length > 2048) return Results.Redirect("/?error=signin");
    ctx.Response.Cookies.Delete(BrowserAuth.StateCookie, BrowserAuth.Cookies(options, TimeSpan.Zero));
    var user = await workOs.Authenticate(code, login.Verifier, ct);
    if (user == null) return Results.Redirect("/?error=signin");
    await store.UpsertUser(user, ct);
    var token = await store.CreateSession(user.Id, "browser", null, ct);
    ctx.Response.Cookies.Append(BrowserAuth.Cookie, token, BrowserAuth.Cookies(options, TimeSpan.FromHours(12)));
    return Results.Redirect(login.ReturnTo);
});
app.MapPost("/api/pairings", async (PairRequest body, IAccountStore store, CancellationToken ct) => Results.Ok(await store.StartPairing(body.Name, ct)))
    .RequireRateLimiting("pair-start");
app.MapPost("/api/pairings/{id:guid}/poll", async (Guid id, PairPoll body, IAccountStore store, CancellationToken ct) => Results.Ok(await store.PollPairing(id, body.DeviceSecret, ct)));
app.MapPost("/api/pairings/{id:guid}/cancel", async (Guid id, PairPoll body, IAccountStore store, CancellationToken ct) =>
{ await store.CancelPairing(id, body.DeviceSecret, ct); return Results.NoContent(); });

var account = app.MapGroup("/api/account").AddEndpointFilter(new AccountFilter("browser"));
account.MapGet("/", async (HttpContext ctx, Secrets secrets, IAccountStore store, CancellationToken ct) =>
{
    var user = AccountFilter.Session(ctx).User; var snapshot = await store.State(user.Id, ct);
    // Browser lists metadata only. Secrets are delivered solely to a linked device.
    return Results.Ok(new { user, csrfToken = secrets.Csrf(ctx.Request.Cookies[BrowserAuth.Cookie]!), snapshot.Revision, snapshot.Settings,
        connections = snapshot.Connections.Select(c => new { c.Id, c.Provider, c.Name, folderCount = c.Roots.Length, roots = c.Roots }), devices = await store.Devices(user.Id, ct) });
});
account.MapPost("/logout", async (HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{
    await store.DeleteSession(ctx.Request.Cookies[BrowserAuth.Cookie]!, ct);
    ctx.Response.Cookies.Delete(BrowserAuth.Cookie, BrowserAuth.Cookies(options, TimeSpan.Zero)); return Results.NoContent();
});
account.MapGet("/pairings/{code}", async (string code, IAccountStore store, CancellationToken ct) =>
    await store.Pairing(code, ct) is { } pairing ? Results.Ok(pairing) : Results.NotFound(new { error = "This code expired or was already used." }));
account.MapPost("/pairings/approve", async (PairApprove body, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
    await store.ApprovePairing(body.UserCode, AccountFilter.Session(ctx).User.Id, ct) ? Results.NoContent() : Results.Conflict(new { error = "This code expired or was already used." }));
account.MapDelete("/devices/{id:guid}", async (Guid id, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{ await store.RevokeDevice(AccountFilter.Session(ctx).User.Id, id, ct); return Results.NoContent(); });
account.MapPut("/settings", async (SettingsUpdate body, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{
    Validation.Settings(body.Settings);
    return await store.SaveSettings(AccountFilter.Session(ctx).User.Id, body, ct) ? Results.NoContent() : Conflict();
});
account.MapPut("/connections", async (ConnectionUpdate body, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{
    Validation.Connection(body.Connection);
    return await store.SaveConnection(AccountFilter.Session(ctx).User.Id, body, ct) ? Results.NoContent() : Conflict();
});
account.MapPut("/connections/{id}/credential", async (string id, CredentialUpdate body, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{
    var user = AccountFilter.Session(ctx).User;
    var snapshot = await store.State(user.Id, ct);
    if (snapshot.Revision != body.ExpectedRevision) return Conflict();
    var connection = snapshot.Connections.SingleOrDefault(c => c.Id == id);
    if (connection == null) return Results.NotFound();
    if (connection.Provider.StartsWith("GOOGLE_", StringComparison.Ordinal)) return Results.BadRequest();
    var configuration = (System.Text.Json.Nodes.JsonObject)connection.Configuration.DeepClone();
    configuration["secret"] = body.Secret;
    var updated = connection with { Configuration = configuration };
    Validation.Connection(updated);
    return await store.SaveConnection(user.Id, new(body.ExpectedRevision, updated), ct) ? Results.NoContent() : Conflict();
});
account.MapDelete("/connections/{id}", async (string id, long revision, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
    await store.DeleteConnection(AccountFilter.Session(ctx).User.Id, id, revision, ct) ? Results.NoContent() : Conflict());
account.MapPut("/connections/{id}/roots", async (string id, RootsUpdate body, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{
    var userId = AccountFilter.Session(ctx).User.Id;
    var state = await store.State(userId, ct);
    if (state.Revision != body.ExpectedRevision) return Conflict();
    var connection = state.Connections.SingleOrDefault(c => c.Id == id);
    if (connection == null) return Results.NotFound();
    var updated = connection with { Roots = body.Roots };
    Validation.Connection(updated);
    var settings = (System.Text.Json.Nodes.JsonObject)state.Settings.DeepClone();
    settings["accountPhotosOnly"] = body.AccountPhotosOnly;
    return await store.SaveState(userId, new(body.ExpectedRevision, settings, state.Connections.Select(c => c.Id == id ? updated : c).ToArray()), ct) ? Results.NoContent() : Conflict();
});
account.MapPost("/browse", async (BrowseRequest body, HttpContext ctx, PhotoBrowser browser, CancellationToken ct) => Results.Ok(await browser.Start(AccountFilter.Session(ctx).User.Id, body, ct)));
account.MapGet("/browse/{id:guid}", async (Guid id, HttpContext ctx, PhotoBrowser browser, CancellationToken ct) =>
    await browser.Status(AccountFilter.Session(ctx).User.Id, id, ct) is { } result ? Results.Ok(result) : Results.NotFound());

var device = app.MapGroup("/api/device").AddEndpointFilter(new AccountFilter("device"));
account.MapPost("/services", async (ServiceLinkRequest body, HttpContext ctx, ServiceLinker linker, CancellationToken ct) => Results.Ok(await linker.Start(AccountFilter.Session(ctx).User.Id, body, ct)));
account.MapGet("/services/{id:guid}", async (Guid id, HttpContext ctx, ServiceLinker linker, CancellationToken ct) =>
    await linker.Status(AccountFilter.Session(ctx).User.Id, id, ct) is { } result ? Results.Ok(result) : Results.NotFound());
device.MapPost("/services/next", async (HttpContext ctx, ServiceLinker linker, CancellationToken ct) =>
{
    var session = AccountFilter.Session(ctx);
    return await linker.Next(session.User.Id, session.DeviceId!.Value, ct) is { } work ? Results.Ok(work) : Results.NoContent();
});
device.MapPost("/services/{id:guid}/complete", async (Guid id, ServiceLinkResult body, HttpContext ctx, ServiceLinker linker, CancellationToken ct) =>
{
    var session = AccountFilter.Session(ctx);
    return await linker.Complete(session.User.Id, session.DeviceId!.Value, id, body, ct) ? Results.NoContent() : Results.Conflict();
});
device.MapGet("/state", async (HttpContext ctx, IAccountStore store, CancellationToken ct) => Results.Ok(await store.State(AccountFilter.Session(ctx).User.Id, ct)));
device.MapPost("/browse/next", async (HttpContext ctx, PhotoBrowser browser, CancellationToken ct) =>
{
    var session = AccountFilter.Session(ctx);
    return await browser.Next(session.User.Id, session.DeviceId!.Value, ct) is { } work ? Results.Ok(work) : Results.NoContent();
});
device.MapPost("/browse/{id:guid}/complete", async (Guid id, BrowseListing body, HttpContext ctx, PhotoBrowser browser, CancellationToken ct) =>
{
    var session = AccountFilter.Session(ctx);
    return await browser.Complete(session.User.Id, session.DeviceId!.Value, id, body, ct) ? Results.NoContent() : Results.Conflict();
});
device.MapPut("/state", async (StateUpdate body, HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{
    Validation.State(body);
    return await store.SaveState(AccountFilter.Session(ctx).User.Id, body, ct) ? Results.NoContent() : Conflict();
});
device.MapPost("/logout", async (HttpContext ctx, IAccountStore store, CancellationToken ct) =>
{
    var session = AccountFilter.Session(ctx);
    await store.RevokeDevice(session.User.Id, session.DeviceId!.Value, ct); return Results.NoContent();
});
app.MapFallbackToFile("index.html");
if (!app.Environment.IsEnvironment("Testing")) await app.Services.GetRequiredService<IAccountStore>().Initialize(CancellationToken.None);
await app.RunAsync();

static IResult Conflict() => Results.Conflict(new { error = "Your account changed on another device. Refresh before saving." });
public partial class Program;

public sealed class AccountFilter(string kind) : IEndpointFilter
{
    public static AccountSession Session(HttpContext ctx) => (AccountSession)ctx.Items["account"]!;
    public async ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        var ctx = context.HttpContext;
        var token = kind == "browser" ? ctx.Request.Cookies[BrowserAuth.Cookie] :
            ctx.Request.Headers.Authorization.ToString() is { } auth && auth.StartsWith("Bearer ", StringComparison.Ordinal) ? auth[7..] : null;
        var session = token == null ? null : await ctx.RequestServices.GetRequiredService<IAccountStore>().Session(token, ctx.RequestAborted);
        if (session == null || session.Kind != kind) return Results.Unauthorized();
        if (kind == "browser" && ctx.Request.Method is not ("GET" or "HEAD"))
        {
            var options = ctx.RequestServices.GetRequiredService<ServerOptions>();
            var secrets = ctx.RequestServices.GetRequiredService<Secrets>();
            if (ctx.Request.Headers.Origin != options.PublicOrigin || !Secrets.Matches(ctx.Request.Headers["X-CSRF-Token"].ToString(), secrets.Csrf(token!)))
                return Results.StatusCode(403);
        }
        ctx.Items["account"] = session;
        return await next(context);
    }
}
