using AirLyrics.Maintainer.Paths;
using AirLyrics.Maintainer.Publish;
using AirLyrics.Maintainer.Reading;
using AirLyrics.Maintainer.Scanning;
using Microsoft.Data.Sqlite;
using Xunit;

namespace AirLyrics.Maintainer.Tests;

public class PublishPipelineTests
{
    [Fact]
    public void FirstPublish_assignsContractBucketsAndEncodesHash()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(
            Path.Combine(env.Source, "Album", "Touhou", "同人", "社團", "專輯", "01.wav"),
            "Deep", "Artist", "Album", "[00:01.00]<00:01.00>あ<00:01.40>い");
        TestAudio.WriteWav(
            Path.Combine(env.Source, "Album", "Instrumental+", "# アニメ", "專輯", "01.wav"),
            "Hash", "Artist / Other", "Album", "[00:01.00]line");
        TestAudio.WriteWav(
            Path.Combine(env.Source, "Main", "J-POP, EN, CN+", "J-POP", "歌手", "曲.wav"),
            "Main", "Artist", "Album", "plain words");
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "shallow.wav"), "Shallow", "Artist", "Album", null);
        TestAudio.WriteWav(Path.Combine(env.Source, "root.wav"), "Root", "Artist", "Album", null);
        TestAudio.WriteWav(Path.Combine(env.Source, "Other", "Keep", "This", "skip.wav"), "Skip", "Artist", "Album", null);

        var stamps = env.SourceWriteTimes();
        var result = env.Publish();
        Assert.True(result.Success, result.Error);
        Assert.Equal(1, result.Generation);
        Assert.Equal(4, result.ShardCount);
        Assert.Equal(stamps, env.SourceWriteTimes());
        Assert.Equal(Path.Combine(env.Source, LibraryLayout.DatabaseFolderName), env.Paths.DatabaseDirectory);
        Assert.True(File.Exists(Path.Combine(env.Source, "$DB", "publish", "manifest.json")));

        var manifest = ManifestIO.Read(env.Paths.ManifestPath);
        Assert.Equal(result.LibraryId, manifest.LibraryId);
        Assert.Contains(manifest.Shards, shard => shard.BucketKind == "depth3" && shard.Bucket == "Album/Touhou/同人");
        Assert.Contains(manifest.Shards, shard => shard.BucketKind == "depth3" && shard.Bucket == "Album/Instrumental+/# アニメ");
        Assert.Contains(manifest.Shards, shard => shard.BucketKind == "direct" && shard.Bucket == "Album");
        Assert.DoesNotContain(manifest.Shards, shard => shard.BucketKind == "root");
        Assert.DoesNotContain(manifest.Shards, shard => shard.Bucket.StartsWith("Other", StringComparison.Ordinal));

        var hashed = manifest.Shards.Single(shard => shard.Bucket.Contains('#'));
        Assert.Contains("%23", hashed.RelativeUrl);
        Assert.DoesNotContain("/#", hashed.RelativeUrl);

        using var hashedDb = OpenShard(env, hashed);
        Assert.Equal("Artist / Other", Scalar(hashedDb, "SELECT artist FROM track_artist"));

        var plain = manifest.Shards.Single(shard => shard.Bucket == "Main/J-POP, EN, CN+/J-POP");
        using var plainDb = OpenShard(env, plain);
        Assert.Equal("plain", Scalar(plainDb, "SELECT format FROM lyrics"));
        Assert.Equal("present", Scalar(plainDb, "SELECT lyrics_status FROM track"));
        Assert.DoesNotContain(Directory.GetFiles(env.Paths.PublishShardsDirectory, "*", SearchOption.AllDirectories), path =>
            path.EndsWith("-wal", StringComparison.OrdinalIgnoreCase) ||
            path.EndsWith("-shm", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void ShardFormat_followsOriginalTimeMarkers()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "elrc.wav"), "E", "Ar", "Al", null);
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "lrc.wav"), "L", "Ar", "Al", null);
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "word.wav"), "W", "Ar", "Al", null);
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "plain.wav"), "P", "Ar", "Al", null);

        var reader = new ScriptedTagReader();
        reader.Set("elrc.wav", "[00:10.00]<00:10.00>あ<00:10.20>い<00:10.40>う");
        reader.Set("lrc.wav", "[00:10.00]hello");
        reader.Set("word.wav", "[00:01.00]你[00:01.20]好[00:01.40]嗎");
        reader.Set("plain.wav", "just words");

        var result = env.PublishWith(reader);
        Assert.True(result.Success, result.Error);
        using var db = OpenShard(env, ManifestIO.Read(env.Paths.ManifestPath).Shards.Single());
        Assert.Equal("elrc", Scalar(db, "SELECT format FROM lyrics WHERE track_id = (SELECT track_id FROM track WHERE filename = 'elrc.wav')"));
        Assert.Equal("lrc", Scalar(db, "SELECT format FROM lyrics WHERE track_id = (SELECT track_id FROM track WHERE filename = 'lrc.wav')"));
        Assert.Equal("lrc", Scalar(db, "SELECT format FROM lyrics WHERE track_id = (SELECT track_id FROM track WHERE filename = 'word.wav')"));
        Assert.Equal("plain", Scalar(db, "SELECT format FROM lyrics WHERE track_id = (SELECT track_id FROM track WHERE filename = 'plain.wav')"));
    }

    [Fact]
    public void UnchangedTree_reusesRevisionAndDoesNotRewrite()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "01.wav"), "T", "Ar", "Al", "[00:01.00]x");
        var first = env.Publish();
        var revision = ManifestIO.Read(env.Paths.ManifestPath).Shards.Single().Revision;
        var second = env.Publish();
        Assert.True(second.Success, second.Error);
        Assert.Equal(2, second.Generation);
        Assert.Equal(1, second.ReusedShardCount);
        Assert.Equal(0, second.RebuiltShardCount);
        Assert.Equal(revision, ManifestIO.Read(env.Paths.ManifestPath).Shards.Single().Revision);
    }

    [Fact]
    public void Depth3FolderRename_removesOldBucketAndRebuildsNew()
    {
        using var env = TestEnv.Create();
        var original = Path.Combine(env.Source, "Album", "Touhou", "同人", "01.wav");
        TestAudio.WriteWav(original, "T", "Ar", "Al", "[00:01.00]x");
        env.Publish();
        Directory.Move(Path.Combine(env.Source, "Album", "Touhou", "同人"), Path.Combine(env.Source, "Album", "Touhou", "moved"));
        var result = env.Publish();
        Assert.True(result.Success, result.Error);
        var shards = ManifestIO.Read(env.Paths.ManifestPath).Shards;
        Assert.DoesNotContain(shards, shard => shard.Bucket == "Album/Touhou/同人");
        Assert.Contains(shards, shard => shard.Bucket == "Album/Touhou/moved");
        Assert.Equal(1, result.RebuiltShardCount);
    }

    [Fact]
    public void Layer4Move_rebuildsOnlyTheSameDepth3Bucket()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "Touhou", "同人", "a", "01.wav"), "T", "Ar", "Al", "[00:01.00]x");
        TestAudio.WriteWav(Path.Combine(env.Source, "Main", "Keep", "This", "02.wav"), "U", "Ar", "Al", "[00:01.00]y");
        var first = ManifestIO.Read(env.Publish().ManifestPath!).Shards.ToDictionary(shard => shard.Bucket);
        Directory.CreateDirectory(Path.Combine(env.Source, "Album", "Touhou", "同人", "b"));
        File.Move(
            Path.Combine(env.Source, "Album", "Touhou", "同人", "a", "01.wav"),
            Path.Combine(env.Source, "Album", "Touhou", "同人", "b", "01.wav"));
        var second = env.Publish();
        var shards = ManifestIO.Read(env.Paths.ManifestPath).Shards.ToDictionary(shard => shard.Bucket);
        Assert.NotEqual(first["Album/Touhou/同人"].Revision, shards["Album/Touhou/同人"].Revision);
        Assert.Equal(first["Main/Keep/This"].Revision, shards["Main/Keep/This"].Revision);
        Assert.Equal(1, second.RebuiltShardCount);
        Assert.Equal(1, second.ReusedShardCount);
    }

    [Fact]
    public void CrossBucketMove_rebuildsBothBuckets()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "Left", "One", "01.wav"), "T", "Ar", "Al", "[00:01.00]x");
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "Right", "Two", "02.wav"), "U", "Ar", "Al", "[00:01.00]y");
        env.Publish();
        File.Move(
            Path.Combine(env.Source, "Album", "Left", "One", "01.wav"),
            Path.Combine(env.Source, "Album", "Right", "Two", "01.wav"));
        var result = env.Publish();
        Assert.True(result.Success, result.Error);
        var shards = ManifestIO.Read(env.Paths.ManifestPath).Shards;
        Assert.DoesNotContain(shards, shard => shard.Bucket == "Album/Left/One");
        Assert.Equal(2, shards.Single(shard => shard.Bucket == "Album/Right/Two").TrackCount);
        Assert.Equal(1, result.RebuiltShardCount);
    }

    [Fact]
    public void DeleteLyrics_nextGenerationStoresAbsent()
    {
        using var env = TestEnv.Create();
        var path = Path.Combine(env.Source, "Album", "A", "B", "01.wav");
        TestAudio.WriteWav(path, "T", "Ar", "Al", "[00:01.00]hello");
        env.Publish();
        TestAudio.WriteWav(path, "T", "Ar", "Al", lyrics: null);
        var result = env.Publish();
        Assert.True(result.Success, result.Error);
        using var db = OpenShard(env, ManifestIO.Read(env.Paths.ManifestPath).Shards.Single());
        Assert.Equal("absent", Scalar(db, "SELECT lyrics_status FROM track"));
        Assert.Equal("0", Scalar(db, "SELECT COUNT(*) FROM lyrics"));
    }

    [Fact]
    public void TagRewrite_rebuildsAndKeepsOldTitleAlias()
    {
        using var env = TestEnv.Create();
        var path = Path.Combine(env.Source, "Album", "A", "B", "01.wav");
        TestAudio.WriteWav(path, "Old Title", "Ar", "Al", "[00:01.00]x");
        env.Publish();
        TestAudio.WriteWav(path, "New Title", "Ar", "Al", "[00:01.00]x");
        env.Publish();
        using var db = OpenShard(env, ManifestIO.Read(env.Paths.ManifestPath).Shards.Single());
        Assert.Equal("New Title", Scalar(db, "SELECT title FROM track"));
        Assert.Equal("Old Title", Scalar(db, "SELECT old_value FROM match_alias WHERE field = 'title'"));
    }

    [Fact]
    public void Disconnect_doesNotPublishEmptyGeneration()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "01.wav"), "T", "Ar", "Al", "[00:01.00]x");
        env.Publish();
        var generation = ManifestIO.Read(env.Paths.ManifestPath).Generation;
        Directory.Delete(Path.Combine(env.Source, LibraryLayout.AlbumFolder), recursive: true);
        var main = Path.Combine(env.Source, LibraryLayout.MainFolder);
        if (Directory.Exists(main))
        {
            Directory.Delete(main, recursive: true);
        }

        var failed = env.Publish();
        Assert.False(failed.Success);
        Assert.Equal(generation, ManifestIO.Read(env.Paths.ManifestPath).Generation);
        Assert.Contains("Neither", failed.Error, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void EnumerationFailure_doesNotInferDeletion()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "01.wav"), "T", "Ar", "Al", "[00:01.00]x");
        env.Publish();
        var generation = ManifestIO.Read(env.Paths.ManifestPath).Generation;
        var fs = new SelectiveFailFileSystem(Path.Combine(env.Source, "Album"));
        var pipeline = new PublishPipeline(env.Paths, fs, new AtlTagReader());
        var failed = pipeline.Run(new PublishRequest { SourceRoot = env.Source });
        Assert.False(failed.Success);
        Assert.Equal(generation, ManifestIO.Read(env.Paths.ManifestPath).Generation);
        Assert.Equal(1, ManifestIO.Read(env.Paths.ManifestPath).Shards.Single().TrackCount);
    }

    [Fact]
    public void MissingStateWithManifest_refusesSilentGenerationReset()
    {
        using var env = TestEnv.Create();
        TestAudio.WriteWav(Path.Combine(env.Source, "Album", "A", "B", "01.wav"), "T", "Ar", "Al", "[00:01.00]x");
        env.Publish();
        File.Delete(env.Paths.StateDatabasePath);
        foreach (var sidecar in Directory.GetFiles(env.Paths.PrivateDirectory, "state.sqlite*"))
        {
            File.Delete(sidecar);
        }

        var refused = env.Pipeline().Run(new PublishRequest
        {
            SourceRoot = env.Source,
            CreateNewLibraryIfStateLost = false
        });
        Assert.False(refused.Success);
        Assert.Contains("library_id", refused.Error, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(1, ManifestIO.Read(env.Paths.ManifestPath).Generation);
    }

    private static SqliteConnection OpenShard(TestEnv env, ManifestShard shard)
    {
        var segments = shard.RelativeUrl.Split('/', StringSplitOptions.RemoveEmptyEntries)
            .Select(Uri.UnescapeDataString)
            .ToArray();
        var path = Path.Combine(new[] { env.Paths.PublishDirectory }.Concat(segments).ToArray());
        var connection = new SqliteConnection($"Data Source={path};Mode=ReadOnly");
        connection.Open();
        return connection;
    }

    private static string Scalar(SqliteConnection connection, string sql)
    {
        using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        return cmd.ExecuteScalar()?.ToString() ?? "";
    }

    private sealed class TestEnv : IDisposable
    {
        private readonly string _root;

        private TestEnv(string root, string source, MaintainerPaths paths)
        {
            _root = root;
            Source = source;
            Paths = paths;
        }

        public string Source { get; }
        public MaintainerPaths Paths { get; }

        public static TestEnv Create()
        {
            var root = Path.Combine(Path.GetTempPath(), "airlyrics-stage-b", Guid.NewGuid().ToString("N"));
            var source = Path.Combine(root, "music");
            Directory.CreateDirectory(source);
            return new TestEnv(root, source, MaintainerPaths.ForLibrary(source));
        }

        public PublishPipeline Pipeline() => PublishPipeline.CreateDefault(Paths);

        public PublishResult Publish() => Pipeline().Run(new PublishRequest { SourceRoot = Source });

        public PublishResult PublishWith(ITagReader reader) =>
            new PublishPipeline(Paths, new RealSourceFileSystem(), reader)
                .Run(new PublishRequest { SourceRoot = Source });

        public Dictionary<string, long> SourceWriteTimes()
        {
            var database = Path.GetFullPath(Paths.DatabaseDirectory);
            return Directory.GetFiles(Source, "*", SearchOption.AllDirectories)
                .Where(path => !IsUnder(path, database))
                .ToDictionary(
                    path => path,
                    path => new FileInfo(path).LastWriteTimeUtc.Ticks,
                    StringComparer.OrdinalIgnoreCase);
        }

        private static bool IsUnder(string path, string parent)
        {
            var full = Path.GetFullPath(path);
            var prefix = parent.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
                + Path.DirectorySeparatorChar;
            return full.StartsWith(prefix, StringComparison.OrdinalIgnoreCase);
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

    private sealed class SelectiveFailFileSystem : ISourceFileSystem
    {
        private readonly RealSourceFileSystem _inner = new();
        private readonly string _failDirectory;

        public SelectiveFailFileSystem(string failDirectory)
        {
            _failDirectory = failDirectory;
        }

        public bool DirectoryExists(string path) => _inner.DirectoryExists(path);

        public bool IsReparsePoint(string path) => _inner.IsReparsePoint(path);

        public string[] GetFiles(string directory)
        {
            if (PathsEqual(directory, _failDirectory))
            {
                throw new SourceScanException($"Cannot list files in '{directory}'.");
            }

            return _inner.GetFiles(directory);
        }

        public string[] GetDirectories(string directory)
        {
            if (PathsEqual(directory, _failDirectory))
            {
                throw new SourceScanException($"Cannot list directories in '{directory}'.");
            }

            return _inner.GetDirectories(directory);
        }

        public FileMetadata GetMetadata(string path) => _inner.GetMetadata(path);

        public Stream OpenRead(string path) => _inner.OpenRead(path);

        private static bool PathsEqual(string left, string right)
        {
            return string.Equals(
                Path.GetFullPath(left).TrimEnd(Path.DirectorySeparatorChar),
                Path.GetFullPath(right).TrimEnd(Path.DirectorySeparatorChar),
                StringComparison.OrdinalIgnoreCase);
        }
    }

    private sealed class ScriptedTagReader : ITagReader
    {
        private readonly Dictionary<string, string> _lyrics = new(StringComparer.OrdinalIgnoreCase);

        public void Set(string filename, string lyrics) => _lyrics[filename] = lyrics;

        public ExtractedTrack Read(Stream stream, string pathForErrors)
        {
            Assert.False(stream.CanWrite);
            var lyrics = _lyrics[Path.GetFileName(pathForErrors)];
            return AtlTagReader.Map(new TagProbeResult
            {
                Path = pathForErrors,
                OpenedReadOnly = true,
                Title = Path.GetFileNameWithoutExtension(pathForErrors),
                ArtistRaw = "Ar",
                Album = "Al",
                Lyrics =
                [
                    new LyricsProbeEntry
                    {
                        ContentType = "LYRICS",
                        UnsynchronizedLyrics = lyrics
                    }
                ]
            });
        }
    }
}
