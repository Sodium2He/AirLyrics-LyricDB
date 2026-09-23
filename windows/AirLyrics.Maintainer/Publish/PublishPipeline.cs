using AirLyrics.Maintainer.Paths;
using AirLyrics.Maintainer.Reading;
using AirLyrics.Maintainer.Scanning;
using AirLyrics.Maintainer.State;

namespace AirLyrics.Maintainer.Publish;

public sealed class PublishRequest
{
    public required string SourceRoot { get; init; }
    public bool FullRereadTags { get; init; }
    public bool CreateNewLibraryIfStateLost { get; init; }
    public bool AllowSourceRootChange { get; init; }
}

public sealed class PublishResult
{
    public required bool Success { get; init; }
    public string? Error { get; init; }
    public string? LibraryId { get; init; }
    public long? Generation { get; init; }
    public int ShardCount { get; init; }
    public int ReusedShardCount { get; init; }
    public int RebuiltShardCount { get; init; }
    public string? ManifestPath { get; init; }

    public static PublishResult Fail(string error) => new() { Success = false, Error = error };
}

public sealed class PublishPipeline
{
    public const int ManifestSchemaVersion = 1;

    private readonly MaintainerPaths _paths;
    private readonly ISourceFileSystem _fs;
    private readonly ITagReader _reader;

    public PublishPipeline(MaintainerPaths paths, ISourceFileSystem fs, ITagReader reader)
    {
        _paths = paths;
        _fs = fs;
        _reader = reader;
    }

    public static PublishPipeline CreateDefault(MaintainerPaths paths)
    {
        return new PublishPipeline(
            paths,
            new RealSourceFileSystem(),
            new AtlTagReader());
    }

    public PublishResult Run(PublishRequest request, IProgress<string>? progress = null)
    {
        var runId = DateTime.UtcNow.ToString("yyyyMMddTHHmmssfff") + "-" + Guid.NewGuid().ToString("N")[..8];
        var mode = request.FullRereadTags ? "full-reread" : "fast";
        FileStream? lockStream = null;
        StateStore? state = null;
        var copiedThisRun = new List<string>();
        var stagingRun = Path.Combine(_paths.StagingDirectory, runId);

        try
        {
            var sourceRoot = Path.GetFullPath(request.SourceRoot);
            _paths.RejectIfOverlapsSource(sourceRoot);
            _paths.EnsureDatabaseLayout();

            if (!_fs.DirectoryExists(sourceRoot))
            {
                return FailAndRecord(null, runId, mode, "Source root is not accessible. Incomplete generation was not published.");
            }

            lockStream = new FileStream(
                _paths.LockPath,
                FileMode.OpenOrCreate,
                FileAccess.ReadWrite,
                FileShare.None);

            if (!File.Exists(_paths.StateDatabasePath) && File.Exists(_paths.ManifestPath))
            {
                if (!request.CreateNewLibraryIfStateLost)
                {
                    return PublishResult.Fail(
                        "private state.sqlite is missing while publish/manifest.json exists. " +
                        "Restore state or explicitly create a new library_id.");
                }

                OrphanPreviousPublish(runId);
            }

            state = StateStore.Open(_paths.StateDatabasePath);
            var library = state.GetLibrary();
            if (library is null)
            {
                library = state.CreateLibrary(sourceRoot);
                progress?.Report("Created library_id " + library.LibraryId);
            }
            else if (!string.Equals(library.SourceRoot, sourceRoot, StringComparison.OrdinalIgnoreCase))
            {
                if (!request.AllowSourceRootChange)
                {
                    return FailAndRecord(
                        state,
                        runId,
                        mode,
                        $"Selected source '{sourceRoot}' differs from library root '{library.SourceRoot}'.");
                }

                state.UpdateSourceRoot(library.LibraryId, sourceRoot);
                library = library with { SourceRoot = sourceRoot };
                progress?.Report("Source root changed; all buckets will rebuild.");
            }

            Directory.CreateDirectory(stagingRun);
            progress?.Report("Enumerating source (complete listing required).");
            var snapshot = SourceWalker.WalkComplete(sourceRoot, _fs);
            var previousFiles = state.LoadFiles();
            var previousBuckets = state.LoadBuckets().ToDictionary(item => item.BucketKey, StringComparer.Ordinal);

            var grouped = snapshot.Files.GroupBy(file => file.Bucket, new BucketKeyComparer()).ToList();
            var published = new List<(BucketKey Key, StoredBucket Stored, string PublishPath)>();
            var nextFiles = new List<StoredFile>();
            var reused = 0;
            var rebuilt = 0;

            foreach (var group in grouped)
            {
                var bucket = group.Key;
                var current = group.OrderBy(file => file.RelativePath, StringComparer.Ordinal).ToList();
                var prevInBucket = previousFiles
                    .Where(file => file.BucketKey == bucket.Serialized)
                    .ToList();

                var canReuse = !request.FullRereadTags
                    && previousBuckets.TryGetValue(bucket.Serialized, out var prevBucket)
                    && prevBucket.Revision is not null
                    && prevBucket.Sha256 is not null
                    && prevBucket.RelativeUrl is not null
                    && SameFingerprint(current, prevInBucket)
                    && File.Exists(ResolvePublishedFile(prevBucket.RelativeUrl));

                IReadOnlyList<StoredFile> storedForBucket;
                StoredBucket storedBucket;
                if (canReuse)
                {
                    reused++;
                    storedForBucket = prevInBucket;
                    storedBucket = previousBuckets[bucket.Serialized];
                    progress?.Report("Reuse " + bucket.Serialized + " revision " + storedBucket.Revision);
                }
                else
                {
                    rebuilt++;
                    progress?.Report("Rebuild " + bucket.Serialized);
                    storedForBucket = ExtractBucket(current, prevInBucket, request.FullRereadTags);
                    var stagingShard = Path.Combine(stagingRun, bucket.ShardId + ".sqlite");
                    var built = ShardBuilder.Build(
                        stagingShard,
                        library.LibraryId,
                        bucket,
                        storedForBucket.Select(file => new ShardTrackInput
                        {
                            File = current.First(item => item.RelativePath == file.RelativePath),
                            Extract = file.Extract,
                            Aliases = file.Aliases
                        }).ToList());

                    var dest = ManifestIO.LocalShardPath(_paths.PublishDirectory, bucket, built.Revision + ".sqlite");
                    Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                    File.Copy(built.StagingPath, dest, overwrite: false);
                    copiedThisRun.Add(dest);
                    if (File.Exists(dest + "-wal") || File.Exists(dest + "-shm"))
                    {
                        throw new InvalidOperationException("Published shard must not include WAL files.");
                    }

                    storedBucket = new StoredBucket
                    {
                        BucketKey = bucket.Serialized,
                        Kind = bucket.KindName,
                        RelativePath = bucket.RelativePath,
                        ShardId = bucket.ShardId,
                        Revision = built.Revision,
                        Sha256 = built.Sha256,
                        ByteSize = built.ByteSize,
                        TrackCount = built.TrackCount,
                        RelativeUrl = UrlPathEncoder.ShardRelativeUrl(bucket, built.Revision + ".sqlite")
                    };
                }

                nextFiles.AddRange(storedForBucket);
                published.Add((bucket, storedBucket, ResolvePublishedFile(storedBucket.RelativeUrl!)));
            }

            var generation = library.LastSuccessfulGeneration + 1;
            var manifest = new PublishManifest
            {
                SchemaVersion = ManifestSchemaVersion,
                LibraryId = library.LibraryId,
                Generation = generation,
                BuiltAtUtc = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                Shards = published
                    .OrderBy(item => item.Key.Serialized, StringComparer.Ordinal)
                    .Select(item => new ManifestShard
                    {
                        ShardId = item.Stored.ShardId,
                        Bucket = item.Stored.RelativePath,
                        BucketKind = item.Stored.Kind,
                        Revision = item.Stored.Revision!,
                        RelativeUrl = item.Stored.RelativeUrl!,
                        Sha256 = item.Stored.Sha256!,
                        ByteSize = item.Stored.ByteSize!.Value,
                        TrackCount = item.Stored.TrackCount!.Value
                    })
                    .ToList()
            };

            if (File.Exists(_paths.ManifestPath))
            {
                File.Copy(_paths.ManifestPath, _paths.PreviousManifestPath, overwrite: true);
            }

            var stagingManifest = Path.Combine(stagingRun, "manifest.json");
            ManifestIO.Write(stagingManifest, manifest);
            File.Copy(stagingManifest, _paths.ManifestPath, overwrite: true);

            state.ReplaceAfterSuccess(
                library,
                generation,
                nextFiles,
                published.Select(item => item.Stored).ToList());
            state.RecordRun(runId, "success", mode, generation, null);
            RecycleUnreferenced();

            try
            {
                Directory.Delete(stagingRun, recursive: true);
            }
            catch (IOException)
            {
            }

            progress?.Report($"Published generation {generation} with {manifest.Shards.Count} shard(s).");
            return new PublishResult
            {
                Success = true,
                LibraryId = library.LibraryId,
                Generation = generation,
                ShardCount = manifest.Shards.Count,
                ReusedShardCount = reused,
                RebuiltShardCount = rebuilt,
                ManifestPath = _paths.ManifestPath
            };
        }
        catch (Exception ex)
        {
            foreach (var copied in copiedThisRun)
            {
                TryDelete(copied);
            }

            state?.RecordRun(runId, "failed", mode, null, ex.Message);
            progress?.Report(ex.Message);
            return PublishResult.Fail(ex.Message);
        }
        finally
        {
            state?.Dispose();
            lockStream?.Dispose();
        }
    }

    private List<StoredFile> ExtractBucket(
        List<SourceFileRecord> current,
        List<StoredFile> previous,
        bool fullReread)
    {
        var result = new List<StoredFile>();
        foreach (var file in current)
        {
            var previousMatch = FindPrevious(file, previous);
            ExtractedTrack extract;
            if (!fullReread
                && previousMatch is not null
                && previousMatch.Size == file.Size
                && previousMatch.MtimeUtcMs == file.MtimeUtcMs
                && previousMatch.RelativePath == file.RelativePath)
            {
                extract = previousMatch.Extract;
            }
            else
            {
                using var stream = _fs.OpenRead(file.FullPath);
                extract = _reader.Read(stream, file.FullPath);
                if (!extract.OpenedReadOnly)
                {
                    throw new SourceScanException($"Tag reader obtained a writable handle for '{file.FullPath}'.");
                }

                var after = _fs.GetMetadata(file.FullPath);
                if (after.Size != file.Size || after.MtimeUtcMs != file.MtimeUtcMs)
                {
                    throw new SourceScanException($"File changed during read: '{file.RelativePath}'.");
                }
            }

            result.Add(new StoredFile
            {
                RelativePath = file.RelativePath,
                BucketKey = file.Bucket.Serialized,
                Filename = file.Filename,
                Size = file.Size,
                MtimeUtcMs = file.MtimeUtcMs,
                VolumeSerial = file.VolumeSerial,
                FileIndex = file.FileIndex,
                Extract = extract,
                Aliases = CarryAliases(previousMatch, extract)
            });
        }

        return result;
    }

    private static StoredFile? FindPrevious(SourceFileRecord file, List<StoredFile> previous)
    {
        var byPath = previous.FirstOrDefault(item => item.RelativePath == file.RelativePath);
        if (byPath is not null)
        {
            return byPath;
        }

        if (file.VolumeSerial is null || file.FileIndex is null)
        {
            return null;
        }

        return previous.FirstOrDefault(item =>
            item.VolumeSerial == file.VolumeSerial && item.FileIndex == file.FileIndex);
    }

    private static List<StoredAlias> CarryAliases(StoredFile? previous, ExtractedTrack current)
    {
        var aliases = previous?.Aliases.Select(item => item).ToList() ?? [];
        if (previous is null)
        {
            return aliases;
        }

        AddChanged(aliases, "title", previous.Extract.Title, current.Title);
        AddChanged(aliases, "artist", previous.Extract.ArtistRaw, current.ArtistRaw);
        AddChanged(aliases, "album", previous.Extract.Album, current.Album);
        AddChanged(aliases, "album_artist", previous.Extract.AlbumArtist, current.AlbumArtist);
        return aliases;
    }

    private static void AddChanged(List<StoredAlias> aliases, string field, string? oldValue, string? newValue)
    {
        if (oldValue is null || string.Equals(oldValue, newValue, StringComparison.Ordinal))
        {
            return;
        }

        if (aliases.Any(item => item.Field == field && item.OldValue == oldValue))
        {
            return;
        }

        aliases.Add(new StoredAlias
        {
            Field = field,
            OldValue = oldValue,
            Source = "observed_continuity"
        });
    }

    private static bool SameFingerprint(List<SourceFileRecord> current, List<StoredFile> previous)
    {
        if (current.Count != previous.Count)
        {
            return false;
        }

        var prevByPath = previous.ToDictionary(item => item.RelativePath, StringComparer.Ordinal);
        foreach (var file in current)
        {
            if (!prevByPath.TryGetValue(file.RelativePath, out var prev))
            {
                return false;
            }

            if (prev.Size != file.Size || prev.MtimeUtcMs != file.MtimeUtcMs)
            {
                return false;
            }
        }

        return true;
    }

    private string ResolvePublishedFile(string relativeUrl)
    {
        var segments = relativeUrl.Split('/', StringSplitOptions.RemoveEmptyEntries)
            .Select(Uri.UnescapeDataString)
            .ToArray();
        return Path.GetFullPath(Path.Combine(new[] { _paths.PublishDirectory }.Concat(segments).ToArray()));
    }

    private void RecycleUnreferenced()
    {
        var referenced = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var manifestPath in new[] { _paths.ManifestPath, _paths.PreviousManifestPath })
        {
            if (!File.Exists(manifestPath))
            {
                continue;
            }

            var manifest = ManifestIO.Read(manifestPath);
            foreach (var shard in manifest.Shards)
            {
                referenced.Add(ResolvePublishedFile(shard.RelativeUrl));
            }
        }

        if (!Directory.Exists(_paths.PublishShardsDirectory))
        {
            return;
        }

        foreach (var file in Directory.GetFiles(_paths.PublishShardsDirectory, "*.sqlite", SearchOption.AllDirectories))
        {
            if (!referenced.Contains(Path.GetFullPath(file)))
            {
                TryDelete(file);
            }
        }
    }

    private void OrphanPreviousPublish(string runId)
    {
        var dest = Path.Combine(_paths.ReportsDirectory, "orphan-library-" + runId);
        Directory.CreateDirectory(dest);
        if (Directory.Exists(_paths.PublishDirectory))
        {
            var orphanPublish = Path.Combine(dest, "publish");
            Directory.Move(_paths.PublishDirectory, orphanPublish);
        }

        _paths.EnsureDatabaseLayout();
    }

    private PublishResult FailAndRecord(StateStore? state, string runId, string mode, string error)
    {
        state?.RecordRun(runId, "failed", mode, null, error);
        return PublishResult.Fail(error);
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
        }
    }

    private sealed class BucketKeyComparer : IEqualityComparer<BucketKey>
    {
        public bool Equals(BucketKey? x, BucketKey? y) => x == y;

        public int GetHashCode(BucketKey obj) => obj.GetHashCode();
    }
}
