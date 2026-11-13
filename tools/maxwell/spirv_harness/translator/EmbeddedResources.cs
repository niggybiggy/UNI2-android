// Only the GLSL helper loader used by the shader translator. No emulator frontend dependencies.
using System;
using System.IO;
using System.Linq;
namespace Ryujinx.Common {
    public static class EmbeddedResources {
        public static string ReadAllText(string name) {
            string[] parts = name.Split('/', 2);
            string assemblyName = parts.Length == 2 ? parts[0] : "Ryujinx.Graphics.Shader";
            string relative = parts.Length == 2 ? parts[1] : parts[0];
            var assembly = AppDomain.CurrentDomain.GetAssemblies().First(a => a.GetName().Name == assemblyName);
            using Stream stream = assembly.GetManifestResourceStream(assemblyName + "." + relative.Replace('/', '.'));
            if (stream == null) throw new IOException("Missing translator helper: " + name);
            using StreamReader reader = new(stream);
            return reader.ReadToEnd();
        }
    }
}
