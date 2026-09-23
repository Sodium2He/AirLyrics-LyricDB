namespace AirLyrics.Maintainer.Scanning;

public static class AudioExtensions
{
    public static readonly string[] Values =
    [
        ".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".wma", ".aac"
    ];

    public static bool IsAudioFile(string path)
    {
        return Values.Contains(Path.GetExtension(path), StringComparer.OrdinalIgnoreCase);
    }
}
