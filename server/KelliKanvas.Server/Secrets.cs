using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.WebUtilities;

namespace KelliKanvas.Server;

public sealed class Secrets(ServerOptions options)
{
    private readonly byte[] key = Convert.FromBase64String(options.EncryptionKey);
    public static string Token() => WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));
    public static string Hash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));
    public static bool Matches(string left, string right) => CryptographicOperations.FixedTimeEquals(
        SHA256.HashData(Encoding.UTF8.GetBytes(left)), SHA256.HashData(Encoding.UTF8.GetBytes(right)));
    public string Csrf(string session) => WebEncoders.Base64UrlEncode(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes("csrf:" + session)));
    public byte[] Encrypt(string plaintext, string context)
    {
        var bytes = Encoding.UTF8.GetBytes(plaintext);
        try
        {
            var envelope = new byte[1 + 12 + 16 + bytes.Length]; envelope[0] = 1;
            RandomNumberGenerator.Fill(envelope.AsSpan(1, 12));
            using var cipher = new AesGcm(key, 16);
            cipher.Encrypt(envelope.AsSpan(1, 12), bytes, envelope.AsSpan(29), envelope.AsSpan(13, 16), Encoding.UTF8.GetBytes(context));
            return envelope;
        }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }
    public string Decrypt(byte[] envelope, string context)
    {
        if (envelope.Length < 29 || envelope[0] != 1) throw new CryptographicException("Invalid connection envelope.");
        var bytes = new byte[envelope.Length - 29];
        try
        {
            using var cipher = new AesGcm(key, 16);
            cipher.Decrypt(envelope.AsSpan(1, 12), envelope.AsSpan(29), envelope.AsSpan(13, 16), bytes, Encoding.UTF8.GetBytes(context));
            return Encoding.UTF8.GetString(bytes);
        }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }
}
