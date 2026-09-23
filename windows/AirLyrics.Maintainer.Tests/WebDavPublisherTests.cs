using AirLyrics.Maintainer.Publish;
using Xunit;

namespace AirLyrics.Maintainer.Tests;

public class WebDavPublisherTests
{
    [Fact]
    public void Upload_putsShardsBeforeManifestAndSkipsManifestOnShardFailure()
    {
        using var env = PublishWorkspace.Create();
        var shardPath = Path.Combine(env.Publish, "shards", "s1", "r1.sqlite");
        Directory.CreateDirectory(Path.GetDirectoryName(shardPath)!);
        File.WriteAllBytes(shardPath, [1, 2, 3, 4]);
        ManifestIO.Write(Path.Combine(env.Publish, "manifest.json"), new PublishManifest
        {
            SchemaVersion = 1,
            LibraryId = "lib",
            Generation = 4,
            BuiltAtUtc = 0,
            Shards =
            [
                new ManifestShard
                {
                    ShardId = "s1",
                    Bucket = "Album/A/B",
                    BucketKind = "depth3",
                    Revision = "r1",
                    RelativeUrl = "shards/s1/r1.sqlite",
                    Sha256 = "abc",
                    ByteSize = 4,
                    TrackCount = 1
                }
            ]
        });

        var transport = new RecordingTransport();
        var result = new WebDavPublisher(transport).Upload(env.Publish);
        Assert.True(result.Success, result.Error);
        Assert.Equal("PUT", transport.Calls[^1].Method);
        Assert.Equal("manifest.json", transport.Calls[^1].Uri.ToString());
        Assert.Contains(transport.Calls, call => call.Method == "PUT" && call.Uri.ToString() == "shards/s1/r1.sqlite");
        var shardPut = transport.Calls.FindIndex(call => call.Method == "PUT" && call.Uri.ToString().Contains("r1.sqlite"));
        var manifestPut = transport.Calls.FindIndex(call => call.Method == "PUT" && call.Uri.ToString() == "manifest.json");
        Assert.True(shardPut >= 0 && manifestPut > shardPut);
    }

    [Fact]
    public void Upload_doesNotPutManifestWhenAShardIsMissing()
    {
        using var env = PublishWorkspace.Create();
        ManifestIO.Write(Path.Combine(env.Publish, "manifest.json"), new PublishManifest
        {
            SchemaVersion = 1,
            LibraryId = "lib",
            Generation = 1,
            BuiltAtUtc = 0,
            Shards =
            [
                new ManifestShard
                {
                    ShardId = "s1",
                    Bucket = "A",
                    BucketKind = "depth3",
                    Revision = "r1",
                    RelativeUrl = "shards/s1/r1.sqlite",
                    Sha256 = "abc",
                    ByteSize = 1,
                    TrackCount = 1
                }
            ]
        });

        var transport = new RecordingTransport();
        var result = new WebDavPublisher(transport).Upload(env.Publish);
        Assert.False(result.Success);
        Assert.DoesNotContain(transport.Calls, call => call.Uri.ToString() == "manifest.json");
    }

    [Fact]
    public void Upload_doesNotPutManifestWhenShardPutFails()
    {
        using var env = PublishWorkspace.Create();
        var shardPath = Path.Combine(env.Publish, "shards", "s1", "r1.sqlite");
        Directory.CreateDirectory(Path.GetDirectoryName(shardPath)!);
        File.WriteAllBytes(shardPath, [9]);
        ManifestIO.Write(Path.Combine(env.Publish, "manifest.json"), new PublishManifest
        {
            SchemaVersion = 1,
            LibraryId = "lib",
            Generation = 2,
            BuiltAtUtc = 0,
            Shards =
            [
                new ManifestShard
                {
                    ShardId = "s1",
                    Bucket = "A",
                    BucketKind = "depth3",
                    Revision = "r1",
                    RelativeUrl = "shards/s1/r1.sqlite",
                    Sha256 = "abc",
                    ByteSize = 1,
                    TrackCount = 1
                }
            ]
        });

        var transport = new RecordingTransport { FailPutPath = "shards/s1/r1.sqlite" };
        var result = new WebDavPublisher(transport).Upload(env.Publish);
        Assert.False(result.Success);
        Assert.DoesNotContain(transport.Calls, call => call.Method == "PUT" && call.Uri.ToString() == "manifest.json");
    }

    private sealed class RecordingTransport : IWebDavTransport
    {
        public List<WebDavRequest> Calls { get; } = [];
        public string? FailPutPath { get; init; }

        public WebDavResponse Send(WebDavRequest request)
        {
            Calls.Add(request);
            if (request.Method == "PUT" &&
                FailPutPath != null &&
                request.Uri.ToString() == FailPutPath)
            {
                return new WebDavResponse { Status = 500 };
            }

            return new WebDavResponse { Status = request.Method == "MKCOL" ? 201 : 200 };
        }
    }

    private sealed class PublishWorkspace : IDisposable
    {
        private readonly string _root;
        public string Publish { get; }

        private PublishWorkspace(string root)
        {
            _root = root;
            Publish = Path.Combine(root, "publish");
            Directory.CreateDirectory(Publish);
        }

        public static PublishWorkspace Create()
        {
            var root = Path.Combine(Path.GetTempPath(), "airlyrics-webdav", Guid.NewGuid().ToString("N"));
            return new PublishWorkspace(root);
        }

        public void Dispose()
        {
            try
            {
                if (Directory.Exists(_root))
                {
                    Directory.Delete(_root, recursive: true);
                }
            }
            catch (IOException)
            {
            }
        }
    }
}
