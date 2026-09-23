using System.Security.Cryptography;
using System.Text;

namespace AirLyrics.Maintainer.Scanning;

public enum BucketKind
{
    Root,
    Direct,
    Depth3
}

public sealed record BucketKey(BucketKind Kind, IReadOnlyList<string> Segments)
{
    public static BucketKey Root() => new(BucketKind.Root, []);

    public static BucketKey Direct(IReadOnlyList<string> segments)
    {
        if (segments.Count is 0 or > 2)
        {
            throw new ArgumentException("Direct buckets cover 1 or 2 folder segments.", nameof(segments));
        }

        return new BucketKey(BucketKind.Direct, [.. segments]);
    }

    public static BucketKey Depth3(string first, string second, string third)
    {
        return new BucketKey(BucketKind.Depth3, [first, second, third]);
    }

    public static BucketKey FromRelativeFile(string relativePosix)
    {
        var directory = PosixPath.DirectoryName(relativePosix);
        var segments = PosixPath.Split(directory);
        if (segments.Length == 0)
        {
            return Root();
        }

        if (segments.Length >= 3)
        {
            return Depth3(segments[0], segments[1], segments[2]);
        }

        return Direct(segments);
    }

    public string RelativePath => string.Join('/', Segments);

    public string KindName => Kind switch
    {
        BucketKind.Root => "root",
        BucketKind.Direct => "direct",
        BucketKind.Depth3 => "depth3",
        _ => throw new ArgumentOutOfRangeException()
    };

    public string Serialized => KindName + ":" + RelativePath;

    public string ShardId
    {
        get
        {
            if (Kind == BucketKind.Root)
            {
                return "root";
            }

            var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Serialized)))
                .ToLowerInvariant()[..16];
            return Kind == BucketKind.Depth3 ? "d3-" + hash : "direct-" + hash;
        }
    }

    public bool Equals(BucketKey? other)
    {
        if (other is null || Kind != other.Kind || Segments.Count != other.Segments.Count)
        {
            return false;
        }

        return Segments.SequenceEqual(other.Segments, StringComparer.Ordinal);
    }

    public override int GetHashCode()
    {
        var hash = new HashCode();
        hash.Add(Kind);
        foreach (var segment in Segments)
        {
            hash.Add(segment, StringComparer.Ordinal);
        }

        return hash.ToHashCode();
    }
}
