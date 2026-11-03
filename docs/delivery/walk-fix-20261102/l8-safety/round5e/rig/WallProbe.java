import com.alibaba.druid.wall.*;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
public class WallProbe {
  public static void main(String[] a) {
    WallConfig wc = new WallConfig("META-INF/druid/wall/mysql"); wc.setMultiStatementAllow(false);
    MySqlWallProvider p = new MySqlWallProvider(wc);
    String[] sqls = {
      "select id from content_sensitive_word where BINARY word = BINARY 'x'",
      "select id from content_sensitive_word where BINARY word = BINARY word",
      "select a.id from content_sensitive_word a join content_sensitive_allow b on BINARY a.word = BINARY b.phrase",
      "select id from content_sensitive_word where CAST(word AS BINARY) = CAST('x' AS BINARY)",
      "select id from content_sensitive_word where 1 = 0",
      "select id from content_sensitive_word where id = 1 and 1 = 0",
      "select 1; select 2"};
    for (String s : sqls) { WallCheckResult r = p.check(s); System.out.println((r.getViolations().isEmpty() ? "ALLOW  " : "REFUSE ") + s + "  " + r.getViolations()); }
  }
}
