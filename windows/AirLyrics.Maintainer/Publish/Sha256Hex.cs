using System.Security.Cryptography;

namespace AirLyrics.Maintainer.Publish;

public static class Sha256Hex
{
    public static string OfFile(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    public static string OfUtf8(string text)
    {
        return Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(text)))
            .ToLowerInvariant();
    }
}
