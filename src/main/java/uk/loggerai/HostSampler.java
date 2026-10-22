package uk.loggerai;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server the app runs on: CPU, memory and disk, sampled every 30 seconds.
 * Inside a container (Docker, Kubernetes) the container's own limits count,
 * not the whole machine's. Never throws.
 */
final class HostSampler {
    private static final String CGROUP = "/sys/fs/cgroup";
    private final File root;
    private final java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

    HostSampler(String root) {
        File f = new File(root == null ? "/" : root);
        this.root = f.exists() ? f : new File("/");
        sample(); // the first reading only primes the JVM's CPU counter
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            return null;
        }
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** (used, limit) MB for a container with a memory limit (cgroup v2). */
    private static double[] cgroupMemory() {
        String limit = read(CGROUP + "/memory.max");
        String current = read(CGROUP + "/memory.current");
        if (limit == null || current == null) return null;
        limit = limit.trim();
        current = current.trim();
        if (limit.isEmpty() || limit.equals("max") || !current.matches("\\d+")) return null;
        double used = Double.parseDouble(current);
        String stat = read(CGROUP + "/memory.stat");
        if (stat != null) {
            for (String line : stat.split("\n")) {
                if (line.startsWith("inactive_file ")) { // page cache the kernel can drop: not really in use
                    used -= Double.parseDouble(line.substring(14).trim());
                    break;
                }
            }
        }
        return new double[] {Math.max(used, 0) / 1048576, Double.parseDouble(limit) / 1048576};
    }

    private static Map<String, Double> meminfo() {
        Map<String, Double> out = new LinkedHashMap<>();
        String s = read("/proc/meminfo");
        if (s == null) return out;
        for (String line : s.split("\n")) {
            String[] p = line.split(":\\s+");
            if (p.length == 2) {
                try {
                    out.put(p[0], Double.parseDouble(p[1].split("\\s+")[0]) / 1024); // kB -> MB
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static Double cgroupCpus() {
        String raw = read(CGROUP + "/cpu.max");
        if (raw == null) return null;
        String[] p = raw.trim().split("\\s+");
        if (p.length == 2 && !p[0].equals("max")) {
            try {
                return Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    Map<String, Object> sample() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", System.currentTimeMillis() / 1000.0);
        try {
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                double cpu = sun.getCpuLoad(); // the whole machine, or the container (the JVM reads its limits)
                if (cpu >= 0) r.put("cpu_pct", r1(Math.min(cpu, 1) * 100));
            }
            Double limit = cgroupCpus();
            r.put("cpus", limit != null ? Math.round(limit * 100) / 100.0 : (double) os.getAvailableProcessors());
        } catch (Throwable ignored) {
        }
        try {
            double load = os.getSystemLoadAverage();
            if (load >= 0) r.put("load1", Math.round(load * 100) / 100.0);
        } catch (Throwable ignored) {
        }
        try {
            double[] cg = cgroupMemory();
            Map<String, Double> mi;
            if (cg != null) {
                r.put("mem_used_pct", r1(cg[0] / cg[1] * 100));
                r.put("mem_total_mb", (long) Math.round(cg[1]));
            } else if (!(mi = meminfo()).isEmpty() && mi.containsKey("MemTotal") && mi.containsKey("MemAvailable")) {
                r.put("mem_used_pct", r1((1 - mi.get("MemAvailable") / mi.get("MemTotal")) * 100));
                r.put("mem_total_mb", (long) Math.round(mi.get("MemTotal")));
                Double swap = mi.get("SwapTotal");
                if (swap != null && swap > 0) r.put("swap_used_pct", r1((1 - mi.getOrDefault("SwapFree", 0.0) / swap) * 100));
            } else if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalMemorySize();
                if (total > 0) {
                    r.put("mem_used_pct", r1((1 - (double) sun.getFreeMemorySize() / total) * 100));
                    r.put("mem_total_mb", total / 1048576);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            long total = root.getTotalSpace();
            if (total > 0) r.put("disk_used_pct", r1((1 - (double) root.getFreeSpace() / total) * 100));
        } catch (Throwable ignored) {
        }
        try {
            String status = read("/proc/self/status");
            Double rss = null;
            if (status != null) {
                for (String line : status.split("\n")) {
                    if (line.startsWith("VmRSS:")) rss = Double.parseDouble(line.substring(6).trim().split("\\s+")[0]) / 1024;
                }
            }
            if (rss == null) { // not Linux: the JVM's heap and non-heap in use
                var mem = ManagementFactory.getMemoryMXBean();
                rss = (mem.getHeapMemoryUsage().getUsed() + mem.getNonHeapMemoryUsage().getUsed()) / 1048576.0;
            }
            r.put("process_mem_mb", r1(rss));
        } catch (Throwable ignored) {
        }
        return r;
    }
}
