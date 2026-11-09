import com.chengyinhub.business.service.safety.SensitiveWordMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Round5D 误伤报告:用给定词表(与后台导入同格式)扫仓库种子数据和小程序随包文本,打印命中。
 * 用法见同目录 run.sh。只读,不连库、不联网。命中词只打印在本地终端,供运营判断是否加放行短语。
 */
public class SensitiveFalsePositiveReport {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法: SensitiveFalsePositiveReport <仓库根> <词表文件> [放行短语文件]");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        List<SensitiveWordMatcher.Entry> words = new ArrayList<>();
        for (String line : lines(Paths.get(args[1]))) {
            String[] p = line.split("[,，\\t]", -1);
            words.add(new SensitiveWordMatcher.Entry(p[0].trim(), p.length > 1 ? p[1].trim() : "OTHER",
                    p.length > 2 ? p[2].trim() : "BLOCK"));
        }
        List<String> allows = args.length > 2 ? lines(Paths.get(args[2])) : new ArrayList<>();
        SensitiveWordMatcher matcher = SensitiveWordMatcher.build(words, allows);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root.resolve("chengyinhub-admin/sql"))) {
            s.filter(f -> f.getFileName().toString().startsWith("seed") && f.toString().endsWith(".sql")).forEach(files::add);
        }
        try (Stream<Path> s = Files.walk(root.resolve("chengyinhub-xcx"))) {
            s.filter(Files::isRegularFile)
             .filter(f -> {
                 String p = root.relativize(f).toString().replace('\\', '/');
                 return !p.contains("/node_modules/") && !p.contains("/tests/") && !p.contains("/scripts/")
                         && !p.contains("/miniprogram_npm/")
                         && (p.endsWith(".wxml") || p.endsWith(".js") || p.endsWith(".json"));
             }).forEach(files::add);
        }
        Map<String, Integer> byWord = new TreeMap<>();
        int scannedLines = 0;
        int hitLines = 0;
        for (Path f : files) {
            List<String> content;
            try {
                content = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException notText) {
                continue;
            }
            for (int i = 0; i < content.size(); i++) {
                String line = content.get(i);
                scannedLines++;
                List<String> hits = matcher.matchedWordsForReport(line);
                if (hits.isEmpty()) continue;
                hitLines++;
                for (String h : hits) byWord.merge(h, 1, Integer::sum);
                String snippet = line.trim();
                if (snippet.length() > 120) snippet = snippet.substring(0, 120) + "…";
                System.out.println(root.relativize(f) + ":" + (i + 1) + "\t" + String.join("|", hits) + "\t" + snippet);
            }
        }
        System.out.println();
        System.out.println("词条 " + matcher.size() + " 条,放行短语 " + allows.size() + " 条");
        System.out.println("扫描文件 " + files.size() + " 个,行 " + scannedLines + " 行,命中行 " + hitLines + " 行");
        for (Map.Entry<String, Integer> e : byWord.entrySet()) {
            System.out.println("  " + e.getKey() + "\t" + e.getValue());
        }
    }

    private static List<String> lines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
        }
        return out;
    }
}
