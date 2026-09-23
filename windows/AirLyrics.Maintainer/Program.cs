using AirLyrics.Maintainer;
using AirLyrics.Maintainer.Paths;
using AirLyrics.Maintainer.Reading;

if (args.Length > 0 && string.Equals(args[0], "--probe", StringComparison.OrdinalIgnoreCase))
{
    var source = args.Length >= 2 ? args[1] : @"E:\Cache";
    try
    {
        var report = new TagProbeRunner(MaintainerPaths.ForLibrary(source)).Run(
            source,
            new Progress<string>(path => Console.WriteLine("Read " + path)));
        Console.WriteLine($"Files: {report.Files.Count}");
        foreach (var error in report.Errors)
        {
            Console.WriteLine(error);
        }

        return report.Files.Count == 0 && report.Errors.Count > 0 ? 2 : 0;
    }
    catch (Exception ex)
    {
        Console.Error.WriteLine(ex);
        return 1;
    }
}

ApplicationConfiguration.Initialize();
Application.Run(new MainForm());
return 0;
