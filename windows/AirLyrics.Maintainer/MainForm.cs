using AirLyrics.Maintainer.Paths;
using AirLyrics.Maintainer.Publish;
using AirLyrics.Maintainer.Reading;
using AirLyrics.Maintainer.Scanning;

namespace AirLyrics.Maintainer;

public sealed class MainForm : Form
{
    private readonly SourceHintWatcher _watcher = new();
    private readonly TextBox _sourceBox;
    private readonly TextBox _webDavBox;
    private readonly TextBox _webDavUser;
    private readonly TextBox _webDavPassword;
    private readonly TextBox _logBox;
    private readonly Button _publishButton;
    private readonly Button _probeButton;
    private readonly Button _uploadButton;
    private readonly CheckBox _fullReread;
    private readonly Label _status;

    public MainForm()
    {
        Text = "AirLyrics Maintainer";
        Width = 900;
        Height = 700;
        MinimumSize = new Size(720, 560);

        var sourceLabel = new Label
        {
            Text = "Music root (read-only). Scans only Album and Main. Output is sibling $DB.",
            AutoSize = true,
            Left = 12,
            Top = 12
        };
        _sourceBox = new TextBox
        {
            Left = 12,
            Top = 36,
            Width = 720,
            Text = @"E:\Cache"
        };
        var browse = new Button
        {
            Text = "Browse",
            Left = 744,
            Top = 34,
            Width = 120
        };
        browse.Click += (_, _) => Browse();

        _publishButton = new Button
        {
            Text = "Scan & Publish",
            Left = 12,
            Top = 76,
            Width = 140
        };
        _publishButton.Click += (_, _) => RunPublish();

        _fullReread = new CheckBox
        {
            Text = "Full re-read tags",
            Left = 164,
            Top = 80,
            Width = 140
        };

        _probeButton = new Button
        {
            Text = "Probe tags",
            Left = 320,
            Top = 76,
            Width = 120
        };
        _probeButton.Click += (_, _) => RunProbe();

        var openDb = new Button
        {
            Text = "Open $DB",
            Left = 448,
            Top = 76,
            Width = 130
        };
        openDb.Click += (_, _) =>
        {
            var paths = CurrentPaths();
            paths.EnsureDatabaseLayout();
            System.Diagnostics.Process.Start("explorer.exe", paths.DatabaseDirectory);
        };

        var openReports = new Button
        {
            Text = "Open reports",
            Left = 586,
            Top = 76,
            Width = 120
        };
        openReports.Click += (_, _) =>
        {
            var paths = CurrentPaths();
            paths.EnsureDatabaseLayout();
            System.Diagnostics.Process.Start("explorer.exe", paths.ReportsDirectory);
        };

        var webDavLabel = new Label
        {
            Text = "WebDAV folder URL (upload shards first, manifest last).",
            AutoSize = true,
            Left = 12,
            Top = 112
        };
        _webDavBox = new TextBox
        {
            Left = 12,
            Top = 136,
            Width = 852
        };
        _webDavUser = new TextBox
        {
            Left = 12,
            Top = 172,
            Width = 250,
            PlaceholderText = "Username (optional)"
        };
        _webDavPassword = new TextBox
        {
            Left = 270,
            Top = 172,
            Width = 250,
            UseSystemPasswordChar = true,
            PlaceholderText = "Password (optional)"
        };
        _uploadButton = new Button
        {
            Text = "Upload WebDAV",
            Left = 530,
            Top = 170,
            Width = 140
        };
        _uploadButton.Click += (_, _) => RunUpload();

        _status = new Label
        {
            Text = "Idle",
            AutoSize = true,
            Left = 12,
            Top = 212
        };

        _logBox = new TextBox
        {
            Left = 12,
            Top = 240,
            Width = 852,
            Height = 390,
            Multiline = true,
            ScrollBars = ScrollBars.Both,
            ReadOnly = true,
            Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right,
            Font = new Font("Consolas", 9f)
        };

        Controls.Add(sourceLabel);
        Controls.Add(_sourceBox);
        Controls.Add(browse);
        Controls.Add(_publishButton);
        Controls.Add(_fullReread);
        Controls.Add(_probeButton);
        Controls.Add(openDb);
        Controls.Add(openReports);
        Controls.Add(webDavLabel);
        Controls.Add(_webDavBox);
        Controls.Add(_webDavUser);
        Controls.Add(_webDavPassword);
        Controls.Add(_uploadButton);
        Controls.Add(_status);
        Controls.Add(_logBox);

        _watcher.Hinted += (_, _) =>
        {
            BeginInvoke(() => _status.Text = "Source change hinted — run Scan & Publish. Events are not used as deletions.");
        };

        Load += (_, _) =>
        {
            var paths = CurrentPaths();
            Append($"Library root: {paths.DataRoot}");
            Append($"Scan roots: {LibraryLayout.AlbumFolder} and {LibraryLayout.MainFolder} only");
            Append($"Database: {paths.DatabaseDirectory}");
            Append("This tool never writes tags, sidecar files, or names under Album or Main.");
            WatchSource();
        };
        FormClosed += (_, _) => _watcher.Dispose();
        _sourceBox.Leave += (_, _) => WatchSource();
    }

    private MaintainerPaths CurrentPaths() => MaintainerPaths.ForLibrary(_sourceBox.Text.Trim());

    private void WatchSource()
    {
        try
        {
            _watcher.Watch(_sourceBox.Text.Trim());
        }
        catch (Exception ex)
        {
            Append(ex.Message);
        }
    }

    private void Browse()
    {
        using var dialog = new FolderBrowserDialog
        {
            SelectedPath = Directory.Exists(_sourceBox.Text) ? _sourceBox.Text : @"E:\Cache"
        };
        if (dialog.ShowDialog(this) == DialogResult.OK)
        {
            _sourceBox.Text = dialog.SelectedPath;
            WatchSource();
        }
    }

    private async void RunPublish()
    {
        SetBusy(true);
        _logBox.Clear();
        var source = _sourceBox.Text.Trim();
        var paths = CurrentPaths();
        var fullReread = _fullReread.Checked;
        var progress = new Progress<string>(Append);
        try
        {
            if (!Directory.Exists(source))
            {
                throw new DirectoryNotFoundException(source);
            }

            var createNew = false;
            if (!File.Exists(paths.StateDatabasePath) && File.Exists(paths.ManifestPath))
            {
                var answer = MessageBox.Show(
                    this,
                    "private/state.sqlite is missing while a published manifest exists. Create a new library_id? This does not silently reuse the old generation.",
                    "State missing",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning);
                createNew = answer == DialogResult.Yes;
                if (!createNew)
                {
                    Append("Cancelled: restore state.sqlite or confirm a new library_id.");
                    return;
                }
            }

            var pipeline = PublishPipeline.CreateDefault(paths);
            var result = await Task.Run(() => pipeline.Run(
                new PublishRequest
                {
                    SourceRoot = source,
                    FullRereadTags = fullReread,
                    CreateNewLibraryIfStateLost = createNew,
                    AllowSourceRootChange = true
                },
                progress));

            if (result.Success)
            {
                _status.Text = $"generation {result.Generation} · {result.ShardCount} shards · reused {result.ReusedShardCount} · rebuilt {result.RebuiltShardCount}";
                _watcher.Clear();
            }
            else
            {
                _status.Text = "Publish failed — previous generation kept";
                Append(result.Error ?? "Unknown failure");
            }
        }
        catch (Exception ex)
        {
            Append(ex.ToString());
            _status.Text = "Publish failed — previous generation kept";
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async void RunProbe()
    {
        SetBusy(true);
        _logBox.Clear();
        var source = _sourceBox.Text.Trim();
        var paths = CurrentPaths();
        try
        {
            if (!Directory.Exists(source))
            {
                throw new DirectoryNotFoundException(source);
            }

            var progress = new Progress<string>(path => Append("Read " + path));
            var report = await Task.Run(() => new TagProbeRunner(paths).Run(source, progress));
            Append($"Files: {report.Files.Count}");
            foreach (var error in report.Errors)
            {
                Append(error);
            }

            var lyricsWithUnsync = report.Files.Count(file =>
                file.Lyrics.Any(entry => !string.IsNullOrEmpty(entry.UnsynchronizedLyrics)));
            Append($"Files with UnsynchronizedLyrics: {lyricsWithUnsync}");
            Append("ArtistRaw is stored whole; it is not split on '/'.");
        }
        catch (Exception ex)
        {
            Append(ex.ToString());
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async void RunUpload()
    {
        SetBusy(true);
        _logBox.Clear();
        var paths = CurrentPaths();
        try
        {
            var url = _webDavBox.Text.Trim();
            if (!Uri.TryCreate(url, UriKind.Absolute, out var folder))
            {
                throw new InvalidOperationException("Enter an absolute WebDAV folder URL.");
            }

            if (!File.Exists(paths.ManifestPath))
            {
                throw new FileNotFoundException("Publish the library before uploading.", paths.ManifestPath);
            }

            var user = _webDavUser.Text.Trim();
            var password = _webDavPassword.Text;
            var result = await Task.Run(() =>
                WebDavPublisher.Create(folder, string.IsNullOrEmpty(user) ? null : user, password)
                    .Upload(paths.PublishDirectory));
            if (result.Success)
            {
                _status.Text = $"Uploaded {result.UploadedFiles} files (manifest last)";
                Append("WebDAV upload finished. Shards were sent before manifest.json.");
            }
            else
            {
                _status.Text = "Upload failed — remote previous generation should be kept";
                Append(result.Error ?? "Unknown failure");
            }
        }
        catch (Exception ex)
        {
            Append(ex.ToString());
            _status.Text = "Upload failed — remote previous generation should be kept";
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void SetBusy(bool busy)
    {
        _publishButton.Enabled = !busy;
        _probeButton.Enabled = !busy;
        _uploadButton.Enabled = !busy;
    }

    private void Append(string line)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => Append(line));
            return;
        }

        _logBox.AppendText(line + Environment.NewLine);
    }
}
