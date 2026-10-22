package uk.loggerai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack frames for an error, innermost first, with the app's own code marked
 * (in_app) so LoggerAI groups errors by where they happened in your code.
 * Library code is anything in the JDK or a well-known framework, unless the
 * app names its own packages (LOGGERAI_APP_PACKAGES=com.acme,org.shop).
 */
final class Frames {
    private static final String[] LIBRARIES = {
        "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "kotlin.", "scala.", "groovy.",
        "org.springframework.", "org.apache.", "org.hibernate.", "org.eclipse.", "org.glassfish.", "org.jboss.",
        "com.fasterxml.", "com.zaxxer.", "ch.qos.", "org.slf4j.", "io.netty.", "reactor.", "io.micrometer.",
        "com.mysql.", "org.postgresql.", "org.mariadb.", "com.microsoft.sqlserver.", "oracle.", "org.h2.",
        "io.undertow.", "org.junit.", "com.google.", "okhttp3.", "io.lettuce.", "redis.clients.", "feign.",
        "net.bytebuddy.", "org.aspectj.", "io.quarkus.", "io.micronaut.", "io.vertx.", "uk.loggerai.",
    };

    private Frames() {}

    static boolean inApp(String className, List<String> appPackages) {
        if (className == null) return false;
        if (className.contains("$$SpringCGLIB$$") || className.contains("$$EnhancerBySpringCGLIB$$") || className.startsWith("jdk.proxy")) return false;
        if (!appPackages.isEmpty()) {
            for (String p : appPackages) if (className.startsWith(p)) return true;
            return false;
        }
        for (String p : LIBRARIES) if (className.startsWith(p)) return false;
        return true;
    }

    static List<Map<String, Object>> of(Throwable t, List<String> appPackages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StackTraceElement e : t.getStackTrace()) {
            String cls = e.getClassName();
            int dot = cls.lastIndexOf('.');
            String pkg = dot > 0 ? cls.substring(0, dot).replace('.', '/') + "/" : "";
            String file = e.getFileName() != null ? pkg + e.getFileName() : cls;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("file", file);
            f.put("line", Math.max(e.getLineNumber(), 0));
            f.put("function", e.getMethodName());
            f.put("class", cls);
            f.put("in_app", inApp(cls, appPackages));
            out.add(f);
            if (out.size() >= 50) break;
        }
        return out;
    }
}
