package local.claude.keyboard;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Bundled word-frequency list: prefix completion + edit-distance-1 correction. Lower-case words only. */
final class Dictionary {
    private String[] sorted = new String[0];   // alphabetical, for prefix ranges
    private int[] sortedFreq = new int[0];
    private final HashMap<String, Integer> freq = new HashMap<>(70000);
    // Context -> likely next words: one word, or two or three joined with "_" ("at_the", "^_how_are"); "^" = sentence start.
    private final HashMap<String, String[]> next = new HashMap<>(160000);
    private final HashMap<String, String> fixes = new HashMap<>(5000);    // sure-thing misspellings: "ts" -> "its"
    private volatile boolean ready;

    // Physical neighbours on QWERTY: a substitution with a neighbour is the most likely typo.
    private static final String[] NEAR = new String[26];
    static {
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (int r = 0; r < 3; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                StringBuilder sb = new StringBuilder();
                for (int rr = Math.max(0, r - 1); rr <= Math.min(2, r + 1); rr++) {
                    for (int j = 0; j < rows[rr].length(); j++) {
                        float off = (rr == 0 ? 0 : rr == 1 ? 0.5f : 1.5f) - (r == 0 ? 0 : r == 1 ? 0.5f : 1.5f);
                        if (Math.abs(j + off - i) <= 1.01f && !(rr == r && j == i)) sb.append(rows[rr].charAt(j));
                    }
                }
                NEAR[rows[r].charAt(i) - 'a'] = sb.toString();
            }
        }
    }

    boolean isReady() { return ready; }

    /** assets/next.txt: "context next1 ... next8" per line, most likely first. Call before load() finishes so ready covers both. */
    void loadNext(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536);
        HashMap<String, String> pool = new HashMap<>(40000);   // the same few thousand words repeat across 100k lines
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(" ");
            if (p.length < 2) continue;
            String[] n = new String[p.length - 1];
            for (int i = 1; i < p.length; i++) {
                String w = pool.get(p[i]);
                if (w == null) { w = p[i]; pool.put(w, w); }
                n[i - 1] = w;
            }
            next.put(p[0], n);
        }
        br.close();
    }

    /** "a_b_c" -> "b_c" -> "c" -> null: the next shorter context to fall back to. */
    static String shorter(String key) {
        int i = key == null ? -1 : key.indexOf('_');
        return i < 0 ? null : key.substring(i + 1);
    }

    /** The context key with w added, kept to the last three words ("^^" = message start becomes "^"). */
    static String extend(String key, String w) {
        if (key == null || key.equals("^url")) return w;
        String[] t = ((key.equals("^^") ? "^" : key) + "_" + w).split("_");
        StringBuilder k = new StringBuilder();
        for (int i = Math.max(0, t.length - 3); i < t.length; i++) { if (k.length() > 0) k.append('_'); k.append(t[i]); }
        return k.toString();
    }

    /** Bundled next words for the most specific context that has an entry. */
    private String[] nextFor(String key) {
        for (String k = key; k != null; k = shorter(k)) { String[] n = next.get(k); if (n != null) return n; }
        return null;
    }

    /** Your own next words for the most specific context you've typed before. */
    private HashMap<String, Integer> mineFor(String key) {
        for (String k = key; k != null; k = shorter(k)) { HashMap<String, Integer> m = userNext.get(k); if (m != null && !m.isEmpty()) return m; }
        return null;
    }

    // ---- mix-ups: real words that are often typed for each other ----

    private static final String[][] MIXUPS = {{"its", "it's"}, {"your", "you're"}, {"their", "there", "they're"}, {"then", "than"}};
    final java.util.HashSet<String> mixOff = new java.util.HashSet<>();   // backspaced a mix-up fix: leave this word alone

    private static String[] mixSet(String lower) {
        for (String[] set : MIXUPS) for (String w : set) if (w.equals(lower)) return set;
        return null;
    }

    /**
     * The word you meant when the context says so ("more then" -> than, "it's" -> its own...): only when the typed word
     * is nowhere in the context's list, the other one is in its top 3, and you don't write it this way yourself.
     */
    String mixUp(String lower, String key) {
        if (!ready || key == null || mixOff.contains(lower)) return null;
        String[] set = mixSet(lower);
        String[] ctx = set == null ? null : nextFor(key);
        if (ctx == null || contextBoost(ctx, lower) > 1f) return null;
        HashMap<String, Integer> mine = mineFor(key);
        Integer own = mine == null ? null : mine.get(lower);
        if (own != null && own >= 2) return null;
        for (int r = 0; r < Math.min(3, ctx.length); r++)
            for (String w : set) if (!w.equals(lower) && ctx[r].equals(w)) return w;
        return null;
    }

    /**
     * The word before the one just finished, when the pair says it was a mix-up ("your going" -> you're): key = the
     * context of the finished word (ending with the earlier word), next = the finished word. Returns the earlier word's fix.
     */
    String mixUpBefore(String key, String nextWord) {
        if (!ready || key == null || nextWord == null) return null;
        int u = key.lastIndexOf('_');
        String prev = key.substring(u + 1);
        String[] set = mixSet(prev);
        if (set == null || mixOff.contains(prev)) return null;
        String[] fits = nextFor(key);
        if (contextBoost(fits, nextWord) > 1f) return null;   // the pair is fine as typed ("is there going")
        HashMap<String, Integer> mine = mineFor(key);
        Integer own = mine == null ? null : mine.get(nextWord);
        if (own != null && own >= 2) return null;
        // The other word must fit after the word before it too: "is there going" stays, since "is they're" isn't English.
        String before = u < 0 ? null : key.substring(key.lastIndexOf('_', u - 1) + 1, u);
        String best = null; float bestFit = 1f;
        for (String w : set) {
            if (w.equals(prev)) continue;
            String[] alt = before == null ? next.get(w) : next.get(before + "_" + w);
            float fit = contextBoost(alt, nextWord);
            if (fit > bestFit) { bestFit = fit; best = w; }
        }
        return best;
    }

    /** assets/fixes.txt: "typo fix" per line (fix stored lower-case; matchCase restores I'm). */
    void loadFixes(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536);
        String line;
        while ((line = br.readLine()) != null) {
            int sp = line.indexOf(' ');
            if (sp > 0) fixes.put(line.substring(0, sp), line.substring(sp + 1).toLowerCase());
        }
        br.close();
    }

    /** The listed fix for a common misspelling, or null. */
    String fix(String lower) { return ready ? fixes.get(lower) : null; }

    // How chat messages begin (an empty field); your own usual first words take over once used twice.
    private static final String[] MESSAGE_START = {"i", "hey", "yeah", "thanks", "ok", "haha", "yes", "no"};
    private static final String[] URL_START = {"www.", "https://"};   // an empty URL bar (searches go straight to Google)

    /** An empty URL bar: your most visited sites first, then the defaults. */
    private String[] urlStart() {
        ArrayList<String> out = new ArrayList<>(sites("", 3));
        for (String s : URL_START) if (out.size() < 3 && !out.contains(s)) out.add(s);
        return out.toArray(new String[0]);
    }

    /** Most likely words after prev (lower-case, or "^" for a sentence start); yours first, then the bundled table. */
    String[] predict(String prev) {
        if (!ready || prev == null) return new String[0];
        // Yours first (most specific context first), then the bundled lists, longest context first.
        ArrayList<String> out = new ArrayList<>();
        for (String k = prev; k != null; k = shorter(k)) {
            HashMap<String, Integer> mine = userNext.get(k);
            if (mine == null) continue;
            ArrayList<Cand> ranked = new ArrayList<>();
            for (java.util.Map.Entry<String, Integer> e : mine.entrySet()) {
                if (e.getValue() >= 2 && !blocked.contains(e.getKey())) ranked.add(new Cand(e.getKey(), e.getValue()));
            }
            java.util.Collections.sort(ranked, new java.util.Comparator<Cand>() {
                public int compare(Cand x, Cand y) { return Float.compare(y.score, x.score); }
            });
            for (Cand c : ranked) if (out.size() < 8 && !out.contains(c.word)) out.add(c.word);
        }
        if ("^^".equals(prev) || "^url".equals(prev)) {
            for (String w : "^^".equals(prev) ? MESSAGE_START : urlStart()) if (out.size() < 8 && !out.contains(w) && !blocked.contains(w)) out.add(w);
        } else {
            for (String k = prev; k != null && out.size() < 8; k = shorter(k)) {
                String[] n = next.get(k);
                if (n != null) for (String w : n) if (out.size() < 8 && !out.contains(w) && !blocked.contains(w)) out.add(w);
            }
        }
        return out.toArray(new String[0]);
    }

    // ---- learning: what you type, kept on the phone (learned.txt) ----

    private final HashMap<String, Integer> userCount = new HashMap<>();                 // word -> times you used it
    private final HashMap<String, HashMap<String, Integer>> userNext = new HashMap<>(); // prev -> next -> times
    private final HashMap<String, String> userForm = new HashMap<>();                    // caron -> Caron
    private final ArrayList<String> learnedOnly = new ArrayList<>();                     // learned words not in the list
    final java.util.HashSet<String> blocked = new java.util.HashSet<>();                 // never suggest these
    private static final int LEARNED_BASE = 20000;   // a new word you've used twice counts as fairly common

    /** You ended a word with this (lower-case); form = how you capitalise it when it is a name, or null. */
    void learnUse(String lower, String form, String prev) {
        if (lower.isEmpty() || blocked.contains(lower)) return;
        Integer c = userCount.get(lower);
        int n = c == null ? 1 : c + 1;
        userCount.put(lower, n);
        if (n >= 2 && lower.length() >= 2) raiseToLearned(lower);   // used twice: at least fairly common from now on
        if (form != null) userForm.put(lower, form);
        for (String key = prev; key != null; key = shorter(key)) {   // "at_the", then "the": your phrases and your pairs
            HashMap<String, Integer> m = userNext.get(key);
            if (m == null) { m = new HashMap<>(); userNext.put(key, m); }
            Integer k = m.get(lower);
            m.put(lower, k == null ? 1 : k + 1);
        }
    }

    private void raiseToLearned(String lower) {
        Integer f = freq.get(lower);
        if (f == null) {
            freq.put(lower, LEARNED_BASE);
            learnedOnly.add(lower);
        } else if (f < LEARNED_BASE) {
            freq.put(lower, LEARNED_BASE);
            int i = lowerBound(lower);   // completions read the sorted copy
            if (i < sorted.length && sorted[i].equals(lower)) sortedFreq[i] = LEARNED_BASE;
        }
    }

    // ---- sites: whole addresses you go to from the URL bar ----

    private final HashMap<String, Integer> userSites = new HashMap<>();

    /** You pressed Go on this address (lower-case, no scheme, no trailing slash). */
    void learnSite(String site) {
        if (site.isEmpty() || site.indexOf(' ') >= 0 || site.indexOf('.') < 0) return;
        Integer c = userSites.get(site);
        userSites.put(site, c == null ? 1 : c + 1);
    }

    /**
     * Your sites starting with what's typed (also without "www."), most visited first. With nothing typed only sites
     * you went to at least twice count; once typing, one visit is enough.
     */
    List<String> sites(String typed, int max) {
        ArrayList<Cand> hits = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : userSites.entrySet()) {
            String s = e.getKey();
            boolean match = typed.isEmpty() ? e.getValue() >= 2
                    : (s.startsWith(typed) || (s.startsWith("www.") && s.substring(4).startsWith(typed))) && !s.equals(typed);
            if (match) hits.add(new Cand(s, e.getValue()));
        }
        java.util.Collections.sort(hits, new java.util.Comparator<Cand>() {
            public int compare(Cand x, Cand y) { return Float.compare(y.score, x.score); }
        });
        ArrayList<String> out = new ArrayList<>();
        for (Cand c : hits) if (out.size() < max) out.add(c.word);
        return out;
    }

    /** Undo one learnUse (backspace took the word back). */
    void unlearn(String lower, String prev) {
        Integer c = userCount.get(lower);
        if (c != null) { if (c <= 1) userCount.remove(lower); else userCount.put(lower, c - 1); }
        for (String key = prev; key != null; key = shorter(key)) {
            HashMap<String, Integer> m = userNext.get(key);
            Integer k = m == null ? null : m.get(lower);
            if (k != null) { if (k <= 1) m.remove(lower); else m.put(lower, k - 1); }
        }
    }

    /** Never suggest this word again (long-press in the strip). */
    void block(String lower) {
        blocked.add(lower);
        userCount.remove(lower);
        for (HashMap<String, Integer> m : userNext.values()) m.remove(lower);
    }

    /** Your capitalisation of a learned name, or the word as given. */
    String form(String lower) { String f = userForm.get(lower); return f == null ? lower : f; }

    private float userBoost(String w, HashMap<String, Integer> mineNext) {
        Integer c = userCount.get(w);
        float b = c == null ? 1f : 1f + 0.7f * Math.min(c, 20);
        Integer k = mineNext == null ? null : mineNext.get(w);
        if (k != null && k >= 2) b *= Math.min(12f, 4f + k);
        return b;
    }

    String dumpLearned() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : userCount.entrySet()) sb.append("w ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        for (java.util.Map.Entry<String, String> e : userForm.entrySet()) sb.append("f ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        for (java.util.Map.Entry<String, HashMap<String, Integer>> e : userNext.entrySet())
            for (java.util.Map.Entry<String, Integer> k : e.getValue().entrySet())
                sb.append("n ").append(e.getKey()).append(' ').append(k.getKey()).append(' ').append(k.getValue()).append('\n');
        for (String b : blocked) sb.append("x ").append(b).append('\n');
        for (java.util.Map.Entry<String, Integer> e : userSites.entrySet()) sb.append("s ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        return sb.toString();
    }

    void restoreLearned(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(" ");
            try {
                if (p[0].equals("w") && p.length == 3) {
                    int n = Integer.parseInt(p[2]);
                    userCount.put(p[1], n);
                    if (n >= 2 && p[1].length() >= 2) raiseToLearned(p[1]);
                } else if (p[0].equals("f") && p.length == 3) {
                    userForm.put(p[1], p[2]);
                } else if (p[0].equals("n") && p.length == 4) {
                    HashMap<String, Integer> m = userNext.get(p[1]);
                    if (m == null) { m = new HashMap<>(); userNext.put(p[1], m); }
                    m.put(p[2], Integer.parseInt(p[3]));
                } else if (p[0].equals("x") && p.length == 2) {
                    blocked.add(p[1]);
                } else if (p[0].equals("s") && p.length == 3) {
                    userSites.put(p[1], Integer.parseInt(p[2]));
                }
            } catch (NumberFormatException ignored) { }
        }
        br.close();
    }

    void load(InputStream in) throws Exception {
        ArrayList<String> w = new ArrayList<>(50000);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536);
        String line;
        while ((line = br.readLine()) != null) {
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            String word = line.substring(0, sp);
            int c = Integer.parseInt(line.substring(sp + 1));
            if (!freq.containsKey(word)) { freq.put(word, c); w.add(word); }
        }
        br.close();
        String[] s = w.toArray(new String[0]);
        Arrays.sort(s);
        int[] f = new int[s.length];
        for (int i = 0; i < s.length; i++) f[i] = freq.get(s[i]);
        sorted = s;
        sortedFreq = f;
        ready = true;
    }

    synchronized void addUserWord(String w) {
        if (!freq.containsKey(w)) freq.put(w, 50000);
    }

    boolean contains(String lower) { return ready && freq.containsKey(lower); }

    int frequency(String lower) { Integer f = freq.get(lower); return f == null ? 0 : f; }

    /** Best correction of an unknown word (one edit away, or two words run together: "ofthe" -> "of the"), or null. */
    String correction(String lower) { return correction(lower, null); }

    /** As correction(lower), with words that usually follow prevWord preferred ("lazy doh" -> dog, not do). */
    String correction(String lower, String prevWord) {
        if (!ready || lower.length() < 2) return null;
        String listed = fixes.get(lower);
        if (listed != null) return listed;
        if (isWord(lower)) {
            String mix = mixUp(lower, prevWord);
            if (mix != null) return mix;
            String rare = rareTypo(lower, prevWord);
            return rare != null ? rare : realWordSlip(lower, prevWord);
        }
        String[] ctx = nextFor(prevWord);
        String best = null; float bestScore = 0;
        for (Cand c : edits(lower)) {
            if (blocked.contains(c.word)) continue;
            float sc = c.score * contextBoost(ctx, c.word);
            if (sc > bestScore) { bestScore = sc; best = c.word; }
        }
        if (bestScore < WEAK_EDIT) {
            for (Cand c : edits2(lower)) {
                if (blocked.contains(c.word)) continue;
                float sc = c.score * contextBoost(ctx, c.word);
                if (sc > bestScore) { bestScore = sc; best = c.word; }
            }
        }
        Cand sp = split(lower);
        // Only split on its own when both halves are common words (keeps a name like "caron" from becoming "car on").
        if (sp != null && sp.score > bestScore && commonParts(sp.word)) best = sp.word;
        return best;
    }

    // Common words win: a rare word ("tat", 1.4k) one slip from a core word at least 200x as common ("that") is a typo.
    private static final int RARE = 3000, CORE = 50000, RARE_RATIO = 200;

    /** A rare real word is almost always a slip for a very common one; returns that word, or null. */
    String rareTypo(String lower, String prevWord) {
        int f = frequency(lower);
        // Two-letter subtitle scraps ("sp", "ot") count as rare below 5,000; real ones (uh, ok, ha, ex) are far above that.
        if (lower.length() < 2 || f >= (lower.length() == 2 ? 5000 : RARE)) return null;
        String[] ctx = nextFor(prevWord);
        String best = null; float bestScore = 0;
        for (Cand c : edits(lower)) {
            int cf = frequency(c.word);
            if (cf < CORE || cf < f * RARE_RATIO) continue;
            float sc = c.score * contextBoost(ctx, c.word);
            if (sc > bestScore) { bestScore = sc; best = c.word; }
        }
        return best;
    }

    /** The typed word is a word to keep (not a listed misspelling and not a rare slip for a common word). */
    boolean keepAsTyped(String lower, String prevWord) {
        return isWord(lower) && fixes.get(lower) == null && mixUp(lower, prevWord) == null && rareTypo(lower, prevWord) == null
                && realWordSlip(lower, prevWord) == null;
    }

    /**
     * A slip that made another real word ("kind if" -> of, "out if" -> of): only when a letter's tap landed near the
     * neighbouring key (likelihood >= 0.6), the typed word does not fit after the previous word, and the neighbour word
     * is one of the 3 likeliest there. Short words only, where these slips happen.
     */
    String realWordSlip(String lower, String prevWord) {
        if (!ready || !geometry || prevWord == null || lower.length() > 6 || !lower.equals(tapWord)) return null;
        String[] ctx = nextFor(prevWord);
        HashMap<String, Integer> mine = mineFor(prevWord);
        if (ctx == null && mine == null) return null;
        if (contextBoost(ctx, lower) > 1f || (mine != null && mine.containsKey(lower))) return null;   // it fits: keep it
        String best = null; float bestFit = 0;
        for (int i = 0; i < lower.length(); i++) {
            char t = lower.charAt(i);
            if (i >= tapX.length || Float.isNaN(tapX[i]) || t < 'a' || t > 'z') continue;
            for (int k = 0; k < 26; k++) {
                if (k == t - 'a' || tapLikelihood(i, k, t - 'a') < 0.6f) continue;
                String v = lower.substring(0, i) + (char) ('a' + k) + lower.substring(i + 1);
                if (!isWord(v) || frequency(v) < 5000 || blocked.contains(v)) continue;
                Integer mineCount = mine == null ? null : mine.get(v);
                float fit = Math.max(contextBoost(ctx, v), mineCount != null && mineCount >= 2 ? 8f : 1f);
                if (fit >= 4f && fit > bestFit) { bestFit = fit; best = v; }
            }
        }
        return best;
    }

    private static final int MIN_SPLIT_PART = 2000;
    private static final float WEAK_EDIT = 5000f;   // a one-edit fix scoring below this also tries two edits

    /** A real word for correction purposes: rare 2-3 letter subtitle entries ("fx") don't count. */
    boolean isWord(String lower) {
        Integer f = freq.get(lower);
        return f != null && (lower.length() > 3 || f >= 300);
    }

    private static float contextBoost(String[] ctx, String w) {
        if (ctx != null) for (int r = 0; r < ctx.length; r++) if (ctx[r].equals(w)) return 8f / (1 + 0.5f * r);
        return 1f;
    }
    private static final int AUTO_SPLIT_PART = 20000;

    private boolean commonParts(String pair) {
        for (String p : pair.split(" ")) if (frequency(p) < AUTO_SPLIT_PART) return false;
        return true;
    }

    /**
     * Two or three known words typed without the spaces between them ("justwant", "howareyou"), or with a bottom-row
     * letter hit instead of the space bar ("ofbthe"). A part may be a listed misspelling ("imgoing" -> "i'm going").
     * Scored by the geometric mean of the parts' counts.
     */
    Cand split(String w) {
        int n = w.length();
        if (!ready || n < 3) return null;
        Cand best = null;
        for (int i = 1; i < n; i++) {
            best = better(best, 0.5f, w.substring(0, i), w.substring(i));
            if (i < n - 1 && "cvbnm".indexOf(w.charAt(i)) >= 0) best = better(best, 0.1f, w.substring(0, i), w.substring(i + 1));
        }
        if (n >= 5) {
            for (int i = 1; i < n - 1; i++) {
                String a = partWord(w.substring(0, i));
                if (a == null) continue;
                for (int j = i + 1; j < n; j++) best = better(best, 0.35f, w.substring(0, i), w.substring(i, j), w.substring(j));
            }
        }
        return best;
    }

    private Cand better(Cand best, float weight, String... raw) {
        double product = 1;
        StringBuilder sb = new StringBuilder();
        for (String r : raw) {
            String p = partWord(r);
            if (p == null) return best;
            product *= freq.get(p);
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        float score = (float) Math.pow(product, 1.0 / raw.length) * weight;
        return best == null || score > best.score ? new Cand(sb.toString(), score) : best;
    }

    /** The word a split part stands for: itself if common enough, its listed fix ("im" -> "i'm"), else null. */
    private String partWord(String p) {
        if (p.length() == 1 && !p.equals("a") && !p.equals("i")) return null;
        Integer f = freq.get(p);
        // Two-letter parts must be everyday words (of, me, ok), not subtitle scraps (tu, ba).
        if (f != null && f >= (p.length() == 2 ? 100000 : MIN_SPLIT_PART)) return p;
        String fx = fixes.get(p);
        if (fx != null && fx.indexOf(' ') < 0) {
            Integer ff = freq.get(fx);
            if (ff != null && ff >= MIN_SPLIT_PART) return fx;
        }
        return null;
    }

    /**
     * A known word followed by the start of the next one, space forgotten ("canyo" -> "can you", "justw" -> "just want").
     * The second word comes from the words that usually follow the first, so "toge" stays "together": it is only
     * offered when it beats the plain completions by far.
     */
    private Cand partialSplit(String w, float bestCompletion) {
        int n = w.length();
        if (n < 3) return null;
        Cand best = null;
        for (int i = 1; i < n; i++) {
            String a = partWord(w.substring(0, i));
            if (a == null) continue;
            String rest = w.substring(i);
            // A single trailing letter only counts after a real word of 3+ letters ("justw"), not after a fix ("fot").
            if (rest.length() == 1 && (a.length() < 3 || !a.equals(w.substring(0, i)))) continue;
            String[] ctx = next.get(a);
            if (ctx == null) continue;
            for (String c : ctx) {
                if (c.length() <= rest.length() || !c.startsWith(rest)) continue;
                float score = Math.min(frequency(a), frequency(c)) * 0.5f;
                if (score > bestCompletion * 20 && (best == null || score > best.score)) best = new Cand(a + " " + c, score);
                break;
            }
        }
        return best;
    }

    /** Up to max suggestions for the typed word (lower-case), never including the typed word itself. */
    List<String> suggest(String lower, int max) { return suggest(lower, max, null, null); }

    /** As suggest(lower, max), with words that usually follow prevWord ranked up ("thank y" -> you). */
    List<String> suggest(String lower, int max, String prevWord, HashMap<String, Integer> passed) {
        ArrayList<Cand> all = new ArrayList<>();
        if (!ready || lower.isEmpty()) return new ArrayList<>();
        // Completions: the most frequent words starting with the typed letters.
        int lo = lowerBound(lower);
        Cand[] top = new Cand[max + 7];   // extra room: passed-over words drop out and the next ones move up
        for (int i = lo; i < sorted.length && sorted[i].startsWith(lower); i++) {
            if (sorted[i].length() == lower.length()) continue;
            insertTop(top, new Cand(sorted[i], sortedFreq[i]));
        }
        for (Cand c : top) if (c != null) all.add(c);
        String listed = fixes.get(lower);
        if (listed != null) all.add(new Cand(listed, 1e12f));
        String slip = listed == null && isWord(lower) ? realWordSlip(lower, prevWord) : null;
        if (slip != null) all.add(new Cand(slip, 1e11f));
        // Corrections: known words one edit away (only when there's something to correct).
        boolean known = isWord(lower) && frequency(lower) >= (lower.length() == 2 ? 5000 : RARE);   // rare words get typo candidates
        // Letters that already begin common words ("gre", "comp") are a word in progress, not a typo:
        // completions lead and corrections/splits stay in the background.
        boolean live = top[0] != null && top[0].score >= 1000;
        float fixWeight = live ? 0.05f : 4f;
        if (lower.length() >= 2 && !known) {   // a real word ("the", "in") needs no typo search
            float bestEdit = 0;
            for (Cand c : edits(lower)) { all.add(new Cand(c.word, c.score * fixWeight)); bestEdit = Math.max(bestEdit, c.score); }
            if (!live && bestEdit < WEAK_EDIT) for (Cand c : edits2(lower)) all.add(new Cand(c.word, c.score * 4f));
        }
        if (!known) fuzzyCompletions(lower, all, live ? 0.03f : 1f);
        if (!known) {
            float bestCompletion = top[0] == null ? 0 : top[0].score;
            Cand sp = split(lower);
            if (sp != null && (!live || sp.score > bestCompletion * 20)) all.add(new Cand(sp.word, sp.score * 4f));
            Cand ps = partialSplit(lower, bestCompletion);
            if (ps != null) all.add(new Cand(ps.word, ps.score * 4f));
        }
        Cand[] best = new Cand[max + 1];
        HashMap<String, Cand> seen = new HashMap<>();
        for (Cand c : all) {
            Cand prev = seen.get(c.word);
            if (prev == null || prev.score < c.score) seen.put(c.word, c);
        }
        String[] ctx = nextFor(prevWord);
        HashMap<String, Integer> mineNext = mineFor(prevWord);
        for (String w : learnedOnly) {   // your own words complete like any other
            if (w.length() > lower.length() && w.startsWith(lower) && !seen.containsKey(w)) seen.put(w, new Cand(w, frequency(w)));
        }
        if (mineNext != null) {
            for (String w : mineNext.keySet()) if (w.length() > lower.length() && w.startsWith(lower) && !seen.containsKey(w)) seen.put(w, new Cand(w, frequency(w)));
        }
        if (ctx != null) {
            // Likely next words that fit the typed letters join even when plain frequency left them out.
            for (String w : ctx) if (w.length() > lower.length() && w.startsWith(lower) && !seen.containsKey(w)) seen.put(w, new Cand(w, frequency(w)));
        }
        for (Cand c : seen.values()) {
            if (ctx != null) {
                for (int r = 0; r < ctx.length; r++) {
                    if (ctx[r].equals(c.word)) { c = new Cand(c.word, c.score * 8f / (1 + 0.5f * r)); break; }
                }
            }
            if (blocked.contains(c.word)) continue;
            c = new Cand(c.word, c.score * userBoost(c.word, mineNext));
            // A word shown for earlier letters and not picked: you're heading for something longer ("great" -> greatest).
            Integer times = passed == null ? null : passed.get(c.word);
            // ...unless all its letters are typed now ("justwant" -> just want): then it is the word, not a stop on the way.
            if (times != null && c.word.replace(" ", "").replace("'", "").length() <= lower.length()) times = null;
            if (times != null) c = new Cand(c.word, c.score * (float) Math.pow(0.3, times));
            insertTop(best, c);
        }
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < max && best[i] != null; i++) out.add(best[i].word);
        return out;
    }

    private static void insertTop(Cand[] top, Cand c) {
        int n = top.length - 1;
        if (top[n - 1] != null && top[n - 1].score >= c.score) return;
        int i = n - 1;
        while (i >= 0 && (top[i] == null || top[i].score < c.score)) { top[i + 1] = top[i]; i--; }
        top[i + 1] = c;
        top[n] = null;
    }

    private int lowerBound(String key) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid].compareTo(key) < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static final String ALPHA = "abcdefghijklmnopqrstuvwxyz'";

    interface Sink { void take(String variant, float weight); }

    // Key geometry (letter key centres, in key widths) and where each letter of the typed word was tapped.
    private final float[] keyX = new float[26], keyY = new float[26];
    private boolean geometry;
    private float[] tapX = new float[0], tapY = new float[0];
    private String tapWord = "";
    private static final float TAP_SIGMA = 0.55f;   // touch scatter, in key widths

    void setKeyCenters(float[] cx, float[] cy) {
        System.arraycopy(cx, 0, keyX, 0, 26);
        System.arraycopy(cy, 0, keyY, 0, 26);
        geometry = true;
    }

    /** Tap positions (key widths; NaN = unknown) for the letters of lower, the word being typed. */
    void setTaps(String lower, float[] xs, float[] ys) { setTaps(lower, xs, ys, new boolean[0]); }

    /** together[i]: letter i was pressed at the same moment as letter i - 1, so their order (or one of them) is unsure. */
    void setTaps(String lower, float[] xs, float[] ys, boolean[] together) {
        tapWord = lower; tapX = xs; tapY = ys; tapTogether = together;
    }

    private boolean[] tapTogether = new boolean[0];

    private boolean together(int i, boolean useTaps) { return useTaps && i > 0 && i < tapTogether.length && tapTogether[i]; }

    /** How much likelier key k is than the key that registered, given where letter i was tapped. */
    private float tapLikelihood(int i, int k, int typed) {
        float dk = sq(tapX[i] - keyX[k]) + sq(tapY[i] - keyY[k]);
        float dt = sq(tapX[i] - keyX[typed]) + sq(tapY[i] - keyY[typed]);
        return (float) Math.min(1.5, Math.exp(-(dk - dt) / (2 * TAP_SIGMA * TAP_SIGMA)));
    }

    private static float sq(float v) { return v * v; }

    /**
     * Every one-edit variant of w with its typo likelihood. cheap = the likely typos only (extra key, swapped
     * letters, neighbour key, doubled letter, apostrophe); otherwise also far keys and any missed letter.
     */
    // Tap positions line up with any same-length variant of the typed word (substitutions keep letter positions).
    private void variants(String w, boolean cheap, Sink sink) { variants(w, cheap, sink, w.length() == tapWord.length()); }

    private void variants(String w, boolean cheap, Sink sink, boolean useTaps) {
        int n = w.length();
        char[] a = w.toCharArray();
        StringBuilder sb = new StringBuilder(n + 1);
        for (int i = 0; i < n; i++) {
            // deletion (extra key hit)
            sb.setLength(0); sb.append(w, 0, i).append(w, i + 1, n);
            // An extra key: likely when it repeats a neighbour ("thhe"), less so otherwise ("layz" is lazy, not lay).
            boolean repeat = (i > 0 && a[i - 1] == a[i]) || (i + 1 < n && a[i + 1] == a[i]);
            // A letter pressed together with its neighbour is often the stray one of the pair.
            boolean pair = together(i, useTaps) || together(i + 1, useTaps);
            sink.take(sb.toString(), n <= 2 ? 0.05f : repeat || pair ? 1.0f : 0.4f);   // "im" is i'm, not "i"
            // transposition
            if (i + 1 < n && a[i] != a[i + 1]) {
                sb.setLength(0); sb.append(w, 0, i).append(a[i + 1]).append(a[i]).append(w, i + 2, n);
                // swapped letters: the commonest fast-typing slip, and near-certain for two keys pressed together
                sink.take(sb.toString(), together(i + 1, useTaps) ? 6.0f : 2.0f);
            }
            // substitution: where the finger landed decides how likely each other key is; without a tap
            // position, neighbours weigh much more than far keys
            char typedKey = useTaps && i < tapWord.length() ? tapWord.charAt(i) : 0;
            boolean tapped = useTaps && geometry && i < tapX.length && !Float.isNaN(tapX[i]) && typedKey >= 'a' && typedKey <= 'z';
            String near = (a[i] >= 'a' && a[i] <= 'z') ? NEAR[a[i] - 'a'] : "";
            for (int k = 0; k < 26; k++) {
                char c = (char) ('a' + k);
                if (c == a[i]) continue;
                float wt;
                boolean isNear;
                if (tapped) {
                    wt = tapLikelihood(i, k, typedKey - 'a');
                    isNear = wt >= 0.05f;
                    if (!isNear) wt = Math.max(wt, 0.002f);
                } else {
                    isNear = near.indexOf(c) >= 0;
                    wt = isNear ? 1.0f : 0.08f;
                }
                if (cheap && !isNear) continue;
                sb.setLength(0); sb.append(w, 0, i).append(c).append(w, i + 1, n);
                sink.take(sb.toString(), wt);
            }
        }
        // insertion (missed key), incl. apostrophe for dont -> don't
        for (int i = 0; i <= n; i++) {
            for (int k = 0; k < ALPHA.length(); k++) {
                char c = ALPHA.charAt(k);
                if (c == '\'' && (i == 0 || i == n)) continue;
                boolean dbl = (i > 0 && a[i - 1] == c) || (i < n && a[i] == c);   // "helo" -> "hello"
                if (cheap && !dbl && c != '\'') continue;
                sb.setLength(0); sb.append(w, 0, i).append(c).append(w, i, n);
                sink.take(sb.toString(), c == '\'' ? 3f : dbl ? 2.5f : 0.6f);
            }
        }
    }

    private ArrayList<Cand> edits(final String w) {
        final ArrayList<Cand> out = new ArrayList<>();
        variants(w, false, new Sink() {
            public void take(String v, float wt) { hit(out, v, wt); }
        });
        return out;
    }

    /** Two likely typos in one longer word ("neccesary" -> "necessary"); only used when one edit finds nothing. */
    private ArrayList<Cand> edits2(final String w) {
        final ArrayList<Cand> out = new ArrayList<>();
        if (w.length() < 5) return out;
        variants(w, true, new Sink() {
            public void take(String v1, final float w1) {
                variants(v1, true, new Sink() {
                    public void take(String v2, float w2) { if (!v2.equals(w)) hit(out, v2, w1 * w2 * 0.15f); }
                }, v1.length() == tapWord.length());   // taps still line up after a substitution
            }
        });
        return out;
    }

    /**
     * Completions of the typed letters with one likely typo in them ("inxred" -> "incredible"), so suggestions
     * keep working before the word is finished. Same-length variants only for 3 letters (2-letter ranges are huge).
     */
    private void fuzzyCompletions(final String w, final ArrayList<Cand> all, final float scale) {
        final int n = w.length();
        if (n < 3) return;
        final Cand[] top = new Cand[3];
        variants(w, true, new Sink() {
            public void take(String v, float wt) {
                if (v.length() < 3 || (n == 3 && v.length() != 3)) return;
                int end = Math.min(sorted.length, lowerBound(v) + 3000);
                for (int i = lowerBound(v); i < end && sorted[i].startsWith(v); i++) {
                    // A tap near the neighbouring key ("thi" with the i on the o border) lets "though" compete mid-word.
                    float sc = wt >= 0.6f ? Math.max(scale, 0.6f) : scale;
                    if (sorted[i].length() > v.length()) insertTop(top, new Cand(sorted[i], sortedFreq[i] * wt * 0.3f * sc));
                }
            }
        });
        for (Cand c : top) if (c != null) all.add(c);
    }

    private void hit(ArrayList<Cand> out, String cand, float weight) {
        Integer f = freq.get(cand);
        if (f != null) out.add(new Cand(cand, f * weight));
    }

    static final class Cand {
        final String word; final float score;
        Cand(String w, float s) { word = w; score = s; }
    }
}
