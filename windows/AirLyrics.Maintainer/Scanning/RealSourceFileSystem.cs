namespace AirLyrics.Maintainer.Scanning;

public sealed class RealSourceFileSystem : ISourceFileSystem
{
    public bool DirectoryExists(string path) => Directory.Exists(path);

    public bool IsReparsePoint(string path)
    {
        try
        {
            return File.GetAttributes(path).HasFlag(FileAttributes.ReparsePoint);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new SourceScanException($"Cannot read attributes for '{path}'.", ex);
        }
    }

    public string[] GetFiles(string directory)
    {
        try
        {
            return Directory.GetFiles(directory);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or DirectoryNotFoundException)
        {
            throw new SourceScanException($"Cannot list files in '{directory}'.", ex);
        }
    }

    public string[] GetDirectories(string directory)
    {
        try
        {
            return Directory.GetDirectories(directory);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or DirectoryNotFoundException)
        {
            throw new SourceScanException($"Cannot list directories in '{directory}'.", ex);
        }
    }

    public FileMetadata GetMetadata(string path)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists)
            {
                throw new SourceScanException($"File disappeared during scan: '{path}'.");
            }

            uint? volume = null;
            ulong? index = null;
            using (var stream = OpenRead(path))
            {
                if (stream is FileStream fileStream)
                {
                    (volume, index) = NativeFileIdentity.TryRead(fileStream.SafeFileHandle);
                }
            }

            return new FileMetadata
            {
                Size = info.Length,
                MtimeUtcMs = new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeMilliseconds(),
                VolumeSerial = volume,
                FileIndex = index,
                IsReparsePoint = info.Attributes.HasFlag(FileAttributes.ReparsePoint)
            };
        }
        catch (SourceScanException)
        {
            throw;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new SourceScanException($"Cannot read metadata for '{path}'.", ex);
        }
    }

    public Stream OpenRead(string path)
    {
        try
        {
            var stream = new FileStream(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete,
                4096,
                FileOptions.SequentialScan);
            if (stream.CanWrite)
            {
                stream.Dispose();
                throw new SourceScanException($"Refusing writable handle for source file '{path}'.");
            }

            return stream;
        }
        catch (SourceScanException)
        {
            throw;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new SourceScanException($"Cannot open '{path}' for reading.", ex);
        }
    }
}
