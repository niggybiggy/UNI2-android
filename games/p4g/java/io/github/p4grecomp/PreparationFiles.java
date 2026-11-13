package io.github.p4grecomp;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

/** Readiness of the locally generated Maxwell cache. No APK asset manifest is used. */
final class PreparationFiles {
    static final String FORMAT = "format\tmaxwell-local-v1-a82350bb77";
    static String failure(File runtime) {
        try (Reader r = new InputStreamReader(new FileInputStream(new File(runtime, "failed.txt")), "UTF-8")) {
            char[] text = new char[2048];
            int n = r.read(text);
            return n > 0 ? new String(text, 0, n).trim() : "Shader generation failed.";
        } catch (IOException e) { return ""; }
    }
    static boolean spirv(File f) throws IOException {
        if (!f.isFile() || f.length() < 20 || f.length() > 32L * 1024 * 1024 || f.length() % 4 != 0) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != 0x03022307) return false;
            in.readInt(); in.readInt();
            if (in.readInt() == 0 || in.readInt() != 0) return false;
            long left = f.length() / 4 - 5;
            while (left > 0) {
                int word = Integer.reverseBytes(in.readInt()), count = word >>> 16;
                if (count == 0 || count > left) return false;
                for (int i = 1; i < count; ++i) in.readInt();
                left -= count;
            }
            return true;
        }
    }
    static boolean module(File dir, String hash) throws IOException {
        return hash.matches("[0-9a-f]{16}") && spirv(new File(dir, hash + ".spv")) &&
                new File(dir, hash + ".resources").isFile();
    }
    static boolean ready(File runtime) throws IOException {
        File marker = new File(runtime, "prepared.tsv");
        if (!marker.isFile()) return false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(marker), "UTF-8"))) {
            if (!FORMAT.equals(r.readLine())) return false;
            Set<String> seeds = new HashSet<>();
            for (String line; (line = r.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 2 || !"seed".equals(fields[0]) || !seeds.add(fields[1]) ||
                        !module(runtime, fields[1])) return false;
            }
        }
        // Runtime-generated modules are checked and regenerated individually on use.
        return true; // A valid, probed compiler may have zero ExeFS seeds.
    }
}
