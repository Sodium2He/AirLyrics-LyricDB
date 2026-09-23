using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace AirLyrics.Maintainer.Scanning;

internal static class NativeFileIdentity
{
    [StructLayout(LayoutKind.Sequential)]
    private struct ByHandleFileInformation
    {
        public uint FileAttributes;
        public uint CreationTimeLow;
        public uint CreationTimeHigh;
        public uint LastAccessTimeLow;
        public uint LastAccessTimeHigh;
        public uint LastWriteTimeLow;
        public uint LastWriteTimeHigh;
        public uint VolumeSerialNumber;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint NumberOfLinks;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetFileInformationByHandle(
        SafeFileHandle hFile,
        out ByHandleFileInformation lpFileInformation);

    public static (uint? VolumeSerial, ulong? FileIndex) TryRead(SafeFileHandle handle)
    {
        if (!GetFileInformationByHandle(handle, out var info))
        {
            return (null, null);
        }

        var index = ((ulong)info.FileIndexHigh << 32) | info.FileIndexLow;
        return (info.VolumeSerialNumber, index);
    }
}
