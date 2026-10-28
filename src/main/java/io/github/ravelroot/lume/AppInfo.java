package io.github.ravelroot.lume;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Component;

import io.github.ravelroot.lume.db.Database;

/** Versões do Lume e dos componentes, para a tela Preferências → Sobre e para a tela Sistema. */
@Component
public class AppInfo {

    private final String version;
    private final Database db;
    private final LumeProperties properties;

    public AppInfo(Database db, LumeProperties properties) {
        this.db = db;
        this.properties = properties;
        this.version = readVersion();
    }

    static String readVersion() {
        Properties props = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("/lume.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // sem o arquivo, a versão fica "dev"
        }
        String value = props.getProperty("version", "dev");
        return value.startsWith("@") ? "dev" : value;
    }

    public String version() {
        return version;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("java", System.getProperty("java.version"));
        out.put("java_vendor", System.getProperty("java.vendor"));
        out.put("spring_boot", SpringBootVersion.getVersion());
        out.put("tomcat", tomcatVersion());
        out.put("pdfbox", org.apache.pdfbox.util.Version.getVersion());
        out.put("sqlite", db.one("SELECT sqlite_version() AS v").str("v"));
        out.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        out.put("data_dir", properties.dataDir().toAbsolutePath().toString());
        out.put("portable", properties.portable());
        return out;
    }

    private static String tomcatVersion() {
        try {
            return org.apache.catalina.util.ServerInfo.getServerNumber();
        } catch (LinkageError e) {
            return "";
        }
    }
}
