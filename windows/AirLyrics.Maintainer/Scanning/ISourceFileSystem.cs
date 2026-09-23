namespace AirLyrics.Maintainer.Scanning;

public interface ISourceFileSystem
{
    bool DirectoryExists(string path);

    string[] GetFiles(string directory);

    string[] GetDirectories(string directory);

    bool IsReparsePoint(string path);

    FileMetadata GetMetadata(string path);

    Stream OpenRead(string path);
}
