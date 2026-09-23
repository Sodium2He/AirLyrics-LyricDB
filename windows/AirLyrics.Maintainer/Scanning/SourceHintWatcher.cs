using AirLyrics.Maintainer.Paths;

namespace AirLyrics.Maintainer.Scanning;

public sealed class SourceHintWatcher : IDisposable
{
    private readonly List<FileSystemWatcher> _watchers = [];

    public bool ReconciliationNeeded { get; private set; }

    public event EventHandler? Hinted;

    public void Watch(string libraryRoot)
    {
        DisposeWatchers();
        ReconciliationNeeded = false;
        if (!Directory.Exists(libraryRoot))
        {
            return;
        }

        foreach (var directory in LibraryLayout.ExistingScanRoots(libraryRoot, Directory.Exists))
        {
            var watcher = new FileSystemWatcher(directory)
            {
                IncludeSubdirectories = true,
                NotifyFilter = NotifyFilters.FileName
                    | NotifyFilters.DirectoryName
                    | NotifyFilters.LastWrite
                    | NotifyFilters.Size,
                InternalBufferSize = 64 * 1024
            };
            watcher.Created += OnHint;
            watcher.Changed += OnHint;
            watcher.Deleted += OnHint;
            watcher.Renamed += OnHint;
            watcher.Error += (_, _) =>
            {
                ReconciliationNeeded = true;
                Hinted?.Invoke(this, EventArgs.Empty);
            };
            watcher.EnableRaisingEvents = true;
            _watchers.Add(watcher);
        }
    }

    public void Clear() => ReconciliationNeeded = false;

    public void Dispose() => DisposeWatchers();

    private void OnHint(object sender, FileSystemEventArgs e)
    {
        ReconciliationNeeded = true;
        Hinted?.Invoke(this, EventArgs.Empty);
    }

    private void DisposeWatchers()
    {
        foreach (var watcher in _watchers)
        {
            watcher.EnableRaisingEvents = false;
            watcher.Dispose();
        }

        _watchers.Clear();
    }
}
