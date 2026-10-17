import org.joni.Regex;
import org.jcodings.specific.UTF8Encoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Each stdin line: base64(regex). Compiles with joni; prints FAIL lines. */
public class RegexAudit {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        int fail = 0;
        Base64.Decoder dec = Base64.getDecoder();
        for (String line : lines) {
            if (line.isBlank()) continue;
            String regex = new String(dec.decode(line.trim()), StandardCharsets.UTF_8);
            try {
                byte[] b = regex.getBytes(StandardCharsets.UTF_8);
                new Regex(b, 0, b.length, 0, UTF8Encoding.INSTANCE);
            } catch (Throwable e) {
                fail++;
                System.out.println("FAIL\t" + Base64.getEncoder().encodeToString(regex.getBytes(StandardCharsets.UTF_8)) + "\t" + e.getMessage().split("\n")[0]);
            }
        }
        System.out.println("total=" + lines.size() + " failed=" + fail);
    }
}
