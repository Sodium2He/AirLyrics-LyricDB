using AirLyrics.Maintainer.Scanning;

namespace AirLyrics.Maintainer.Publish;

public static class UrlPathEncoder
{
    public static string EncodeSegments(params string[] segments)
    {
        return string.Join('/', segments.Select(Uri.EscapeDataString));
    }

    public static string ShardRelativeUrl(BucketKey key, string revisionFileName)
    {
        var parts = new List<string> { "shards", key.KindName };
        parts.AddRange(key.Segments);
        parts.Add(revisionFileName);
        return EncodeSegments([.. parts]);
    }
}
