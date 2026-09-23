namespace AirLyrics.Maintainer.Scanning;

public static class PosixPath
{
    public static string ToRelative(string root, string fullPath)
    {
        return Path.GetRelativePath(root, fullPath).Replace('\\', '/');
    }

    public static string DirectoryName(string relativePosix)
    {
        var index = relativePosix.LastIndexOf('/');
        return index < 0 ? string.Empty : relativePosix[..index];
    }

    public static string FileName(string relativePosix)
    {
        var index = relativePosix.LastIndexOf('/');
        return index < 0 ? relativePosix : relativePosix[(index + 1)..];
    }

    public static string[] Split(string relativePosix)
    {
        return relativePosix.Length == 0
            ? []
            : relativePosix.Split('/', StringSplitOptions.RemoveEmptyEntries);
    }
}
