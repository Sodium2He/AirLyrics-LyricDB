using AirLyrics.Maintainer.Paths;

namespace AirLyrics.Maintainer.Scanning;

public sealed class SourceFileRecord
{
    public required string FullPath { get; init; }
    public required string RelativePath { get; init; }
    public required string Filename { get; init; }
    public required long Size { get; init; }
    public required long MtimeUtcMs { get; init; }
    public uint? VolumeSerial { get; init; }
    public ulong? FileIndex { get; init; }
    public required BucketKey Bucket { get; init; }
}

public sealed class SourceSnapshot
{
    public required string SourceRoot { get; init; }
    public required IReadOnlyList<SourceFileRecord> Files { get; init; }
}

public static class SourceWalker
{
    public static SourceSnapshot WalkComplete(string sourceRoot, ISourceFileSystem fs)
    {
        var fullRoot = Path.GetFullPath(sourceRoot);
        if (!fs.DirectoryExists(fullRoot))
        {
            throw new SourceScanException($"Source root is not accessible: '{fullRoot}'.");
        }

        var scanRoots = LibraryLayout.ExistingScanRoots(fullRoot, fs.DirectoryExists);
        if (scanRoots.Count == 0)
        {
            throw new SourceScanException(
                $"Neither '{LibraryLayout.AlbumFolder}' nor '{LibraryLayout.MainFolder}' exists under '{fullRoot}'.");
        }

        var files = new List<SourceFileRecord>();
        foreach (var scanRoot in scanRoots)
        {
            WalkTree(fullRoot, scanRoot, fs, files);
            if (!fs.DirectoryExists(scanRoot))
            {
                throw new SourceScanException($"Scan root disappeared during scan: '{scanRoot}'.");
            }
        }

        files.Sort((a, b) => string.CompareOrdinal(a.RelativePath, b.RelativePath));
        return new SourceSnapshot
        {
            SourceRoot = fullRoot,
            Files = files
        };
    }

    private static void WalkTree(
        string libraryRoot,
        string scanRoot,
        ISourceFileSystem fs,
        List<SourceFileRecord> files)
    {
        var pending = new Stack<string>();
        pending.Push(scanRoot);

        while (pending.Count > 0)
        {
            var directory = pending.Pop();
            var isScanRoot = string.Equals(
                Path.GetFullPath(directory),
                Path.GetFullPath(scanRoot),
                StringComparison.OrdinalIgnoreCase);
            if (!isScanRoot && fs.IsReparsePoint(directory))
            {
                continue;
            }

            if (!isScanRoot && LibraryLayout.IsDatabaseFolderName(directory))
            {
                continue;
            }

            foreach (var file in fs.GetFiles(directory))
            {
                if (fs.IsReparsePoint(file))
                {
                    continue;
                }

                var meta = fs.GetMetadata(file);
                if (meta.IsReparsePoint)
                {
                    continue;
                }

                if (!AudioExtensions.IsAudioFile(file))
                {
                    continue;
                }

                var relative = PosixPath.ToRelative(libraryRoot, file);
                files.Add(new SourceFileRecord
                {
                    FullPath = file,
                    RelativePath = relative,
                    Filename = PosixPath.FileName(relative),
                    Size = meta.Size,
                    MtimeUtcMs = meta.MtimeUtcMs,
                    VolumeSerial = meta.VolumeSerial,
                    FileIndex = meta.FileIndex,
                    Bucket = BucketKey.FromRelativeFile(relative)
                });
            }

            foreach (var child in fs.GetDirectories(directory))
            {
                if (LibraryLayout.IsDatabaseFolderName(child))
                {
                    continue;
                }

                pending.Push(child);
            }
        }
    }
}
