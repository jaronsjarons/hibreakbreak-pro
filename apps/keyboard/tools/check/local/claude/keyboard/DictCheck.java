package local.claude.keyboard;

import java.io.FileInputStream;
import java.util.Arrays;

/**
 * Mac-side check of the suggestion engine: tools/dictcheck.sh "word1,word2,..."; a word may carry simulated taps,
 * "fot:0,0;0,0;-0.45,0" = per-letter offset (dx,dy in key widths) from that key's centre; "lazy>doh" = previous word.
 */
public class DictCheck {
    public static void main(String[] a) throws Exception {
        String assets = a[0];
        Dictionary d = new Dictionary();
        d.loadNext(new FileInputStream(assets + "/next.txt"));
        d.loadFixes(new FileInputStream(assets + "/fixes.txt"));
        d.load(new FileInputStream(assets + "/words.txt"));
        // Same geometry as KeyboardView on the HiBreak: rows 54 dp tall, keys 1/10 of 824 px at density 1.875.
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] off = {0, 0.5f, 1.5f};
        float aspect = Math.round(54 * 1.875f) / 82.4f;
        float[] cx = new float[26], cy = new float[26];
        for (int r = 0; r < 3; r++) for (int i = 0; i < rows[r].length(); i++) {
            cx[rows[r].charAt(i) - 'a'] = off[r] + i + 0.5f;
            cy[rows[r].charAt(i) - 'a'] = (r + 0.5f) * aspect;
        }
        d.setKeyCenters(cx, cy);
        for (String spec : a[1].split(",(?![-0-9.])")) {
            String w = spec, taps = null, prev = null;
            if (spec.contains(">")) { prev = spec.substring(0, spec.indexOf('>')); spec = spec.substring(spec.indexOf('>') + 1); w = spec; }
            if (spec.contains(":")) { w = spec.substring(0, spec.indexOf(':')); taps = spec.substring(spec.indexOf(':') + 1); }
            float[] tx = new float[48], ty = new float[48];
            Arrays.fill(tx, Float.NaN); Arrays.fill(ty, Float.NaN);
            if (taps != null) {
                String[] t = taps.split(";");
                for (int i = 0; i < w.length() && i < t.length; i++) {
                    String[] xy = t[i].split(",");
                    tx[i] = cx[w.charAt(i) - 'a'] + Float.parseFloat(xy[0]);
                    ty[i] = cy[w.charAt(i) - 'a'] + Float.parseFloat(xy[1]);
                }
            }
            boolean[] tog = new boolean[48];
            if (w.endsWith("!")) { w = w.substring(0, w.length() - 1); java.util.Arrays.fill(tog, true); }   // "word!" = every key pressed together
            d.setTaps(w, tx, ty, tog);
            String fixBefore = d.mixUpBefore(prev, w);
            System.out.println(spec + " -> " + d.suggest(w, 2, prev, null) + "  space-> " + d.correction(w, prev)
                    + (fixBefore != null ? "  word before-> " + fixBefore : ""));
        }
    }
}
