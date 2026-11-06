package local.claude.keyboard;

import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;

/** Replays typing a word letter by letter like the keyboard does (shown-and-passed words, context): SeqCheck assets prev word. */
public class SeqCheck {
    public static void main(String[] a) throws Exception {
        Dictionary d = new Dictionary();
        d.loadNext(new FileInputStream(a[0] + "/next.txt"));
        d.loadFixes(new FileInputStream(a[0] + "/fixes.txt"));
        d.load(new FileInputStream(a[0] + "/words.txt"));
        String prev = a[1].equals("-") ? null : a[1], w = a[2];
        HashMap<String, Integer> passed = new HashMap<>();
        for (int i = 1; i <= w.length(); i++) {
            String p = w.substring(0, i);
            List<String> s = d.suggest(p, 2, prev, passed);
            System.out.println(p + " -> " + s + "  corr=" + d.correction(p, prev) + "  keep=" + d.keepAsTyped(p, prev));
            for (String x : s) passed.merge(x, 1, Integer::sum);
        }
    }
}
