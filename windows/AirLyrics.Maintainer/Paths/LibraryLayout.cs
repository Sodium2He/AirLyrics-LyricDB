namespace AirLyrics.Maintainer.Paths;

public static class LibraryLayout
{
    public const string DatabaseFolderName = "$DB";
    public const string AlbumFolder = "Album";
    public const string MainFolder = "Main";

    public static readonly string[] ScanFolderNames = [AlbumFolder, MainFolder];

    public static IReadOnlyList<string> ScanRootCandidates(string libraryRoot)
    {
        var root = Path.GetFullPath(libraryRoot);
        return ScanFolderNames
            .Select(name => Path.GetFullPath(Path.Combine(root, name)))
            .ToList();
    }

    public static IReadOnlyList<string> ExistingScanRoots(string libraryRoot, Func<string, bool> directoryExists)
    {
        return ScanRootCandidates(libraryRoot).Where(directoryExists).ToList();
    }

    public static bool IsDatabaseFolderName(string path)
    {
        return string.Equals(Path.GetFileName(path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)),
            DatabaseFolderName,
            StringComparison.OrdinalIgnoreCase);
    }
}
