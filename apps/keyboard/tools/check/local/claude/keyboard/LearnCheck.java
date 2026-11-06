package local.claude.keyboard;

import java.io.FileInputStream;

/** Mac check of learning: tools/learncheck.sh. Learns a few words and phrases, then shows suggestions and timing. */
public class LearnCheck {
    public static void main(String[] a) throws Exception {
        Dictionary d = new Dictionary();
        d.loadNext(new FileInputStream(a[0] + "/next.txt"));
        d.loadFixes(new FileInputStream(a[0] + "/fixes.txt"));
        d.load(new FileInputStream(a[0] + "/words.txt"));
        String[] s = {"car", "pi", "fl"};
        for (String w : s) System.out.println("before  " + w + " -> " + d.suggest(w, 2, null, null));
        System.out.println("before  predict(play) -> " + java.util.Arrays.toString(d.predict("play")));
        for (int i = 0; i < 3; i++) { d.learnUse("caron", "Caron", "^"); d.learnUse("piano", null, "play"); d.learnUse("fluxbox", null, null); }
        for (String w : s) System.out.println("after   " + w + " -> " + d.suggest(w, 2, null, null) + " form=" + d.form("caron"));
        System.out.println("after   predict(play) -> " + java.util.Arrays.toString(d.predict("play")));
        System.out.println("after   caron typo 'carin' -> " + d.suggest("carin", 2, null, null));
        for (int i = 0; i < 2000; i++) { d.learnUse("zq" + i + "word", null, "the"); d.learnUse("zq" + i + "word", null, "the"); }
        String[] bench = {"t", "th", "the", "inxredi", "neccesary", "justwant", "grea", "car"};
        for (int r = 0; r < 200; r++) for (String w : bench) { d.suggest(w, 2, "the", null); d.correction(w, "the"); }
        for (String w : bench) {
            long t = System.nanoTime();
            for (int r = 0; r < 20; r++) { d.suggest(w, 2, "the", null); d.correction(w, "the"); }
            System.out.println("speed with 2000 learned  " + w + " " + (System.nanoTime() - t) / 20000 + " us");
        }
    }
}
