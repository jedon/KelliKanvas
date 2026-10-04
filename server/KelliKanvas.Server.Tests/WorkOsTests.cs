using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using KelliKanvas.Server;
using Microsoft.AspNetCore.WebUtilities;
using Xunit;

namespace KelliKanvas.Server.Tests;

public sealed class WorkOsTests
{
    [Fact]
    public async Task AuthKit_uses_PKCE_server_only_credentials_and_verified_allowed_email()
    {
        var options = new ServerOptions("https://kanvas.kelli.photo", "fixture-db", Convert.ToBase64String(new byte[32]),
            "client_fixture", "sk_server_fixture", null, ["kelli@example.test"]);
        var handler = new Handler();
        using var client = new HttpClient(handler);
        var workOs = new WorkOs(client, options);
        var verifier = Secrets.Token();
        var authorization = new Uri(workOs.Authorize("fixture-state", verifier));
        var query = QueryHelpers.ParseQuery(authorization.Query);
        Assert.Equal("api.workos.com", authorization.Host);
        Assert.Equal("authkit", query["provider"]);
        Assert.Equal(options.Callback, query["redirect_uri"]);
        Assert.Equal("S256", query["code_challenge_method"]);
        Assert.Equal(WebEncoders.Base64UrlEncode(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))), query["code_challenge"]);
        Assert.DoesNotContain(options.WorkOsApiKey, authorization.AbsoluteUri);
        Assert.DoesNotContain(verifier, authorization.AbsoluteUri);
        Assert.Equal("user_fixture", (await workOs.Authenticate("fixture-code", verifier, default))!.Id);
        Assert.Equal("https://api.workos.com/user_management/authenticate", handler.Endpoint);
        Assert.Equal("POST", handler.Method);
        Assert.Equal(verifier, handler.Body!["code_verifier"]!.GetValue<string>());
        Assert.Equal(options.WorkOsApiKey, handler.Body["client_secret"]!.GetValue<string>());
        handler.Verified = false;
        Assert.Null(await workOs.Authenticate("fixture-code", verifier, default));
        handler.Verified = true; handler.Email = "someone@example.test";
        Assert.Null(await workOs.Authenticate("fixture-code", verifier, default));
        handler.Status = HttpStatusCode.Unauthorized;
        Assert.Null(await workOs.Authenticate("fixture-code", verifier, default));
    }
    private sealed class Handler : HttpMessageHandler
    {
        public string Email = "kelli@example.test";
        public bool Verified = true;
        public HttpStatusCode Status = HttpStatusCode.OK;
        public string? Endpoint;
        public string? Method;
        public JsonObject? Body;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Endpoint = request.RequestUri!.AbsoluteUri; Method = request.Method.Method;
            Body = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!.AsObject();
            var payload = new JsonObject { ["user"] = new JsonObject { ["id"] = "user_fixture", ["email"] = Email,
                ["email_verified"] = Verified, ["first_name"] = "Kelli", ["last_name"] = "Fixture" } };
            return new(Status) { Content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json") };
        }
    }
}
