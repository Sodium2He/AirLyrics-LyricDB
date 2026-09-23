namespace AirLyrics.Maintainer.Paths;

public sealed class MaintainerPaths
{
    public MaintainerPaths(string libraryRoot)
    {
        DataRoot = Path.GetFullPath(libraryRoot);
    }

    public static MaintainerPaths ForLibrary(string libraryRoot) => new(libraryRoot);

    public string DataRoot { get; }

    public string DatabaseDirectory => Path.Combine(DataRoot, LibraryLayout.DatabaseFolderName);

    public string PrivateDirectory => Path.Combine(DatabaseDirectory, "private");

    public string StateDatabasePath => Path.Combine(PrivateDirectory, "state.sqlite");

    public string LockPath => Path.Combine(PrivateDirectory, "maintainer.lock");

    public string StagingDirectory => Path.Combine(DatabaseDirectory, "staging");

    public string PublishDirectory => Path.Combine(DatabaseDirectory, "publish");

    public string PublishShardsDirectory => Path.Combine(PublishDirectory, "shards");

    public string ManifestPath => Path.Combine(PublishDirectory, "manifest.json");

    public string PreviousManifestPath => Path.Combine(PublishDirectory, "manifest.prev.json");

    public string ReportsDirectory => Path.Combine(DatabaseDirectory, "reports");

    public void EnsureDatabaseLayout()
    {
        Directory.CreateDirectory(PrivateDirectory);
        Directory.CreateDirectory(StagingDirectory);
        Directory.CreateDirectory(PublishShardsDirectory);
        Directory.CreateDirectory(ReportsDirectory);
    }

    public void RejectIfOverlapsSource(string libraryRoot)
    {
        var database = WithTrailingSep(Path.GetFullPath(DatabaseDirectory));
        foreach (var scanRoot in LibraryLayout.ScanRootCandidates(libraryRoot))
        {
            var source = WithTrailingSep(Path.GetFullPath(scanRoot));
            if (database.StartsWith(source, StringComparison.OrdinalIgnoreCase) ||
                source.StartsWith(database, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException(
                    $"Database directory '{DatabaseDirectory}' overlaps scan root '{scanRoot}'.");
            }
        }
    }

    public static bool IsReparsePoint(string path)
    {
        var attributes = File.GetAttributes(path);
        return attributes.HasFlag(FileAttributes.ReparsePoint);
    }

    private static string WithTrailingSep(string path)
    {
        return path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            + Path.DirectorySeparatorChar;
    }
}
