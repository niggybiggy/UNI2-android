// Local translation of the player's Maxwell code. No game shader is embedded here.
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace SpirvHarness
{
    static partial class Program
    {
        // Original synthetic EXIT-only program. Exercises the same code path as the native ABI.
        static unsafe int TestLocal(string directory)
        {
            Directory.CreateDirectory(directory);
            byte[] code = new byte[16], control = new byte[0x880], error = new byte[2048];
            BitConverter.GetBytes(0xe30000000007000ful).CopyTo(code, 8);
            BitConverter.GetBytes(0x98761234u).CopyTo(control, 0);
            BitConverter.GetBytes(code.Length).CopyTo(control, 0x704);
            foreach (int off in new[] { 0x748, 0x74c, 0x750 }) BitConverter.GetBytes(1).CopyTo(control, off);
            byte[] output = Encoding.UTF8.GetBytes(Path.GetFullPath(Path.Combine(directory, "probe.spv")) + "\0");
            byte[] resources = Encoding.UTF8.GetBytes(Path.GetFullPath(Path.Combine(directory, "probe.resources")) + "\0");
            fixed (byte* cp = code, ctrl = control, op = output, rp = resources, ep = error)
            {
                if (TranslateLocal(cp, code.Length, ctrl, control.Length, op, rp, ep, error.Length) != 0)
                    throw new InvalidOperationException(Encoding.UTF8.GetString(error).TrimEnd('\0'));
                control[0] = 0;
                if (TranslateLocal(cp, code.Length, ctrl, control.Length, op, rp, ep, error.Length) == 0)
                    throw new InvalidOperationException("Bad control was accepted.");
                if (TranslateLocal(cp, code.Length, ctrl, 4, op, rp, ep, error.Length) == 0)
                    throw new InvalidOperationException("Short control was accepted.");
                BitConverter.GetBytes(0x98761234u).CopyTo(control, 0);
                BitConverter.GetBytes(int.MaxValue).CopyTo(control, 0x708);
                if (TranslateLocal(cp, code.Length, ctrl, control.Length, op, rp, ep, error.Length) == 0)
                    throw new InvalidOperationException("Out-of-bounds program was accepted.");
            }
            foreach (int stage in new[] { 1, 5 })
            {
                byte[] graphics = new byte[0x100], descriptor = new byte[0x880];
                BitConverter.GetBytes(0x12345678u).CopyTo(graphics, 0);
                BitConverter.GetBytes(stage << 10).CopyTo(graphics, 0x30);
                BitConverter.GetBytes(0xe30000000007000ful).CopyTo(graphics, 0x88);
                BitConverter.GetBytes(0x98761234u).CopyTo(descriptor, 0);
                BitConverter.GetBytes(0x60).CopyTo(descriptor, 0x6f8);
                BitConverter.GetBytes(graphics.Length).CopyTo(descriptor, 0x704);
                BitConverter.GetBytes(0x30).CopyTo(descriptor, 0x708);
                byte[] path = Encoding.UTF8.GetBytes(Path.GetFullPath(Path.Combine(directory, "probe-" + stage + ".spv")) + "\0");
                byte[] meta = Encoding.UTF8.GetBytes(Path.GetFullPath(Path.Combine(directory, "probe-" + stage + ".resources")) + "\0");
                fixed (byte* cp = graphics, ctrl = descriptor, op = path, rp = meta, ep = error)
                    if (TranslateLocal(cp, graphics.Length, ctrl, descriptor.Length, op, rp, ep, error.Length) != 0)
                        throw new InvalidOperationException(Encoding.UTF8.GetString(error).TrimEnd('\0'));
            }
            Console.WriteLine("PASS: local Maxwell translation, metadata, invalid control and bounds checks");
            return 0;
        }

        internal static unsafe int TranslateLocal(byte* codePointer, int codeSize, byte* controlPointer,
            int controlSize, byte* outputPointer, byte* resourcesPointer, byte* errorPointer, int errorSize)
        {
            try
            {
                if (codePointer == null || controlPointer == null || outputPointer == null || resourcesPointer == null ||
                    codeSize < 8 || codeSize > 16 * 1024 * 1024 || controlSize < 0x758 || controlSize > 0x880)
                    throw new ArgumentException("Invalid Maxwell shader input.");
                byte[] code = new ReadOnlySpan<byte>(codePointer, codeSize).ToArray();
                byte[] control = new ReadOnlySpan<byte>(controlPointer, controlSize).ToArray();
                int start = BitConverter.ToInt32(control, 0x708);
                int constantOffset = BitConverter.ToInt32(control, 0x700);
                int constantSize = BitConverter.ToInt32(control, 0x6fc);
                if (BitConverter.ToUInt32(control, 0) != 0x98761234 ||
                    BitConverter.ToInt32(control, 0x704) != codeSize || start < 0 || start > codeSize - 8 ||
                    constantOffset < 0 || constantSize < 0 || (long)constantOffset + constantSize > codeSize ||
                    (start != 0 && codeSize - start < 0x50))
                    throw new ArgumentException("Invalid Maxwell control section.");
                string output = Marshal.PtrToStringUTF8((IntPtr)outputPointer);
                string resources = Marshal.PtrToStringUTF8((IntPtr)resourcesPointer);
                Result result = Translate(code, control, out byte[] spirv);
                if (spirv == null || spirv.Length < 20 || spirv.Length % 4 != 0 || BitConverter.ToUInt32(spirv) != 0x07230203)
                    throw new InvalidOperationException("Shader translation failed: " + result.Error);
                // Metadata is committed first; SPIR-V is the final validity marker.
                AtomicWrite(resources, new UTF8Encoding(false).GetBytes(result.Resources));
                AtomicWrite(output, spirv);
                if (errorPointer != null && errorSize > 0) errorPointer[0] = 0;
                return 0;
            }
            catch (Exception exception)
            {
                if (errorPointer != null && errorSize > 0)
                {
                    byte[] text = Encoding.UTF8.GetBytes(exception.Message);
                    int size = Math.Min(text.Length, errorSize - 1);
                    new ReadOnlySpan<byte>(text, 0, size).CopyTo(new Span<byte>(errorPointer, size));
                    errorPointer[size] = 0;
                }
                return 1;
            }
        }

        static void AtomicWrite(string target, byte[] data)
        {
            string temporary = target + ".new-" + Guid.NewGuid().ToString("N");
            try
            {
                using (FileStream file = new(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                {
                    file.Write(data);
                    file.Flush(true);
                }
                File.Move(temporary, target, true);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }
    }

    static unsafe class LocalExports
    {
        [UnmanagedCallersOnly(EntryPoint = "spirv_harness_api_version")]
        static int Version() => 1;

        [UnmanagedCallersOnly(EntryPoint = "spirv_harness_translate")]
        static int Translate(byte* code, int size, byte* control, int controlSize,
            byte* output, byte* resources, byte* error, int errorSize) =>
            Program.TranslateLocal(code, size, control, controlSize, output, resources, error, errorSize);
    }
}
