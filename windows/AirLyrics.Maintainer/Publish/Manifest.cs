using System.Text.Json.Serialization;
using AirLyrics.Maintainer.Scanning;

namespace AirLyrics.Maintainer.Publish;

public sealed class PublishManifest
{
    [JsonPropertyName("schema_version")]
    public int SchemaVersion { get; init; }

    [JsonPropertyName("library_id")]
    public required string LibraryId { get; init; }

    [JsonPropertyName("generation")]
    public required long Generation { get; init; }

    [JsonPropertyName("built_at_utc")]
    public required long BuiltAtUtc { get; init; }

    [JsonPropertyName("shards")]
    public List<ManifestShard> Shards { get; init; } = [];
}

public sealed class ManifestShard
{
    [JsonPropertyName("shard_id")]
    public required string ShardId { get; init; }

    [JsonPropertyName("bucket")]
    public required string Bucket { get; init; }

    [JsonPropertyName("bucket_kind")]
    public required string BucketKind { get; init; }

    [JsonPropertyName("revision")]
    public required string Revision { get; init; }

    [JsonPropertyName("relative_url")]
    public required string RelativeUrl { get; init; }

    [JsonPropertyName("sha256")]
    public required string Sha256 { get; init; }

    [JsonPropertyName("byte_size")]
    public required long ByteSize { get; init; }

    [JsonPropertyName("track_count")]
    public required int TrackCount { get; init; }
}

[JsonSourceGenerationOptions(WriteIndented = true)]
[JsonSerializable(typeof(PublishManifest))]
internal partial class ManifestJsonContext : JsonSerializerContext;

public static class ManifestIO
{
    public static void Write(string path, PublishManifest manifest)
    {
        var json = System.Text.Json.JsonSerializer.Serialize(manifest, ManifestJsonContext.Default.PublishManifest);
        var temp = path + ".tmp";
        File.WriteAllText(temp, json);
        if (File.Exists(path))
        {
            File.Replace(temp, path, destinationBackupFileName: null);
        }
        else
        {
            File.Move(temp, path);
        }
    }

    public static PublishManifest Read(string path)
    {
        var json = File.ReadAllText(path);
        return System.Text.Json.JsonSerializer.Deserialize(json, ManifestJsonContext.Default.PublishManifest)
            ?? throw new InvalidOperationException("manifest.json is empty or invalid.");
    }

    public static string LocalShardPath(string publishDirectory, BucketKey key, string revisionFileName)
    {
        var parts = new List<string> { publishDirectory, "shards", key.KindName };
        parts.AddRange(key.Segments);
        parts.Add(revisionFileName);
        return Path.Combine(parts.ToArray());
    }
}
