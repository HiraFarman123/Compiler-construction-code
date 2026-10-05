package ccfe;

import java.util.*;

/**
 * Grammar-processing module: representation, left-recursion elimination (Paull's algorithm),
 * left factoring, FIRST/FOLLOW computation, LL(1) table construction and conflict reporting,
 * bounded ambiguity search, and a table-driven predictive parser used for cross-validation.
 */
public final class Grammar {
    public static final String EPS = "ε", END = "$";

    public final LinkedHashMap<String, List<List<String>>> rules = new LinkedHashMap<>();
    public String start;

    // ------------------------------------------------------------------ construction
    public static Grammar parse(String text) {
        Grammar g = new Grammar();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] lr = line.split("->");
            String lhs = lr[0].trim();
            if (g.start == null) g.start = lhs;
            List<List<String>> alts = g.rules.computeIfAbsent(lhs, x -> new ArrayList<>());
            for (String alt : lr[1].split("\\|", -1)) {
                List<String> body = new ArrayList<>();
                for (String s : alt.trim().split("\\s+")) if (!s.isEmpty() && !s.equals(EPS)) body.add(s);
                alts.add(body);
            }
        }
        return g;
    }

    public Grammar copy() {
        Grammar g = new Grammar(); g.start = start;
        rules.forEach((a, alts) -> { List<List<String>> c = new ArrayList<>(); for (List<String> b : alts) c.add(new ArrayList<>(b)); g.rules.put(a, c); });
        return g;
    }

    public boolean isNT(String s) { return rules.containsKey(s); }
    public int productionCount() { int n = 0; for (List<List<String>> a : rules.values()) n += a.size(); return n; }
    public Set<String> terminals() {
        Set<String> t = new TreeSet<>();
        for (List<List<String>> alts : rules.values()) for (List<String> b : alts) for (String s : b) if (!isNT(s)) t.add(s);
        return t;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        rules.forEach((a, alts) -> {
            sb.append(a).append(" → ");
            StringJoiner j = new StringJoiner(" | ");
            for (List<String> b : alts) j.add(b.isEmpty() ? EPS : String.join(" ", b));
            sb.append(j).append('\n');
        });
        return sb.toString();
    }

    private String fresh(String base) { String n = base + "'"; while (rules.containsKey(n)) n += "'"; return n; }

    // ------------------------------------------------------------------ left recursion
    /** true if some non-terminal A derives A... in one or more steps (ignoring nullable prefixes) */
    public boolean hasLeftRecursion() {
        for (String a : rules.keySet()) {
            Deque<String> st = new ArrayDeque<>(); Set<String> seen = new HashSet<>();
            st.push(a);
            while (!st.isEmpty()) {
                String x = st.pop();
                for (List<String> b : rules.get(x)) {
                    if (b.isEmpty() || !isNT(b.get(0))) continue;
                    if (b.get(0).equals(a)) return true;
                    if (seen.add(b.get(0))) st.push(b.get(0));
                }
            }
        }
        return false;
    }

    /** Paull's algorithm: removes direct and indirect left recursion (grammar assumed cycle-free). */
    public Grammar eliminateLeftRecursion() {
        Grammar g = copy();
        List<String> order = new ArrayList<>(g.rules.keySet());
        for (int i = 0; i < order.size(); i++) {
            String ai = order.get(i);
            for (int j = 0; j < i; j++) {
                String aj = order.get(j);
                List<List<String>> out = new ArrayList<>();
                for (List<String> b : g.rules.get(ai)) {
                    if (!b.isEmpty() && b.get(0).equals(aj)) {
                        for (List<String> d : g.rules.get(aj)) { List<String> nb = new ArrayList<>(d); nb.addAll(b.subList(1, b.size())); out.add(nb); }
                    } else out.add(b);
                }
                g.rules.put(ai, out);
            }
            // immediate left recursion: A -> A a | b  ==>  A -> b A' ; A' -> a A' | eps
            List<List<String>> rec = new ArrayList<>(), non = new ArrayList<>();
            for (List<String> b : g.rules.get(ai)) (!b.isEmpty() && b.get(0).equals(ai) ? rec : non).add(b);
            if (rec.isEmpty()) continue;
            String a2 = g.fresh(ai);
            List<List<String>> na = new ArrayList<>(), np = new ArrayList<>();
            for (List<String> b : non) { List<String> nb = new ArrayList<>(b); nb.add(a2); na.add(nb); }
            for (List<String> b : rec) { List<String> nb = new ArrayList<>(b.subList(1, b.size())); nb.add(a2); np.add(nb); }
            np.add(new ArrayList<>());
            LinkedHashMap<String, List<List<String>>> re = new LinkedHashMap<>();
            for (Map.Entry<String, List<List<String>>> e : g.rules.entrySet()) {
                if (e.getKey().equals(ai)) { re.put(ai, na); re.put(a2, np); } else re.put(e.getKey(), e.getValue());
            }
            g.rules.clear(); g.rules.putAll(re);
        }
        return g;
    }

    // ------------------------------------------------------------------ left factoring
    public Grammar leftFactor() {
        Grammar g = copy();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String a : new ArrayList<>(g.rules.keySet())) {
                List<List<String>> alts = g.rules.get(a);
                Map<String, List<List<String>>> byHead = new LinkedHashMap<>();
                for (List<String> b : alts) if (!b.isEmpty()) byHead.computeIfAbsent(b.get(0), x -> new ArrayList<>()).add(b);
                for (List<List<String>> grp : byHead.values()) {
                    if (grp.size() < 2) continue;
                    int lcp = grp.get(0).size();
                    for (List<String> b : grp) { int k = 0; while (k < lcp && k < b.size() && b.get(k).equals(grp.get(0).get(k))) k++; lcp = k; }
                    String a2 = g.fresh(a);
                    List<String> prefix = new ArrayList<>(grp.get(0).subList(0, lcp));
                    List<List<String>> tails = new ArrayList<>();
                    for (List<String> b : grp) tails.add(new ArrayList<>(b.subList(lcp, b.size())));
                    tails.sort((x, y) -> y.size() - x.size());            // non-empty tails first
                    List<List<String>> na = new ArrayList<>();
                    boolean placed = false;
                    for (List<String> b : alts) {
                        if (grp.contains(b)) { if (!placed) { List<String> nb = new ArrayList<>(prefix); nb.add(a2); na.add(nb); placed = true; } }
                        else na.add(b);
                    }
                    LinkedHashMap<String, List<List<String>>> re = new LinkedHashMap<>();
                    for (Map.Entry<String, List<List<String>>> e : g.rules.entrySet()) {
                        if (e.getKey().equals(a)) { re.put(a, na); re.put(a2, tails); } else re.put(e.getKey(), e.getValue());
                    }
                    g.rules.clear(); g.rules.putAll(re);
                    changed = true; break;
                }
                if (changed) break;
            }
        }
        return g;
    }

    // ------------------------------------------------------------------ FIRST / FOLLOW
    public Map<String, Set<String>> first() {
        Map<String, Set<String>> f = new LinkedHashMap<>();
        for (String a : rules.keySet()) f.put(a, new LinkedHashSet<>());
        boolean ch = true;
        while (ch) {
            ch = false;
            for (Map.Entry<String, List<List<String>>> e : rules.entrySet())
                for (List<String> b : e.getValue()) ch |= f.get(e.getKey()).addAll(firstOf(b, f));
        }
        return f;
    }

    public Set<String> firstOf(List<String> seq, Map<String, Set<String>> f) {
        Set<String> r = new LinkedHashSet<>();
        for (String s : seq) {
            if (!isNT(s)) { r.add(s); return r; }
            Set<String> fs = f.get(s);
            for (String x : fs) if (!x.equals(EPS)) r.add(x);
            if (!fs.contains(EPS)) return r;
        }
        r.add(EPS);
        return r;
    }

    public Map<String, Set<String>> follow(Map<String, Set<String>> f) {
        Map<String, Set<String>> fo = new LinkedHashMap<>();
        for (String a : rules.keySet()) fo.put(a, new LinkedHashSet<>());
        fo.get(start).add(END);
        boolean ch = true;
        while (ch) {
            ch = false;
            for (Map.Entry<String, List<List<String>>> e : rules.entrySet())
                for (List<String> b : e.getValue())
                    for (int i = 0; i < b.size(); i++) {
                        String x = b.get(i);
                        if (!isNT(x)) continue;
                        Set<String> fr = firstOf(b.subList(i + 1, b.size()), f);
                        for (String t : fr) if (!t.equals(EPS)) ch |= fo.get(x).add(t);
                        if (fr.contains(EPS)) ch |= fo.get(x).addAll(fo.get(e.getKey()));
                    }
        }
        return fo;
    }

    // ------------------------------------------------------------------ LL(1) table
    public static final class Table {
        public final Map<String, Map<String, List<List<String>>>> cells = new LinkedHashMap<>();
        public final List<String> conflicts = new ArrayList<>();
        public int filled() { int n = 0; for (Map<String, List<List<String>>> r : cells.values()) n += r.size(); return n; }
    }

    public Table ll1Table() {
        Map<String, Set<String>> f = first(), fo = follow(f);
        Table t = new Table();
        for (Map.Entry<String, List<List<String>>> e : rules.entrySet()) {
            Map<String, List<List<String>>> row = t.cells.computeIfAbsent(e.getKey(), x -> new LinkedHashMap<>());
            for (List<String> b : e.getValue()) {
                Set<String> fb = firstOf(b, f);
                Set<String> la = new LinkedHashSet<>();
                for (String x : fb) if (!x.equals(EPS)) la.add(x);
                if (fb.contains(EPS)) la.addAll(fo.get(e.getKey()));
                for (String a : la) row.computeIfAbsent(a, x -> new ArrayList<>()).add(b);
            }
            for (Map.Entry<String, List<List<String>>> c : row.entrySet())
                if (c.getValue().size() > 1) t.conflicts.add("M[" + e.getKey() + ", " + c.getKey() + "] has " + c.getValue().size() + " productions");
        }
        return t;
    }

    /** Table-driven predictive parse; in a conflicting cell the first listed production is used. */
    public boolean predictiveParse(Table t, List<String> input) {
        Deque<String> st = new ArrayDeque<>(); st.push(END); st.push(start);
        int i = 0;
        while (true) {
            String x = st.pop(), a = i < input.size() ? input.get(i) : END;
            if (x.equals(END)) return a.equals(END);
            if (!isNT(x)) { if (!x.equals(a)) return false; i++; continue; }
            List<List<String>> cell = t.cells.get(x).get(a);
            if (cell == null) return false;
            List<String> b = cell.get(0);
            for (int k = b.size() - 1; k >= 0; k--) st.push(b.get(k));
        }
    }

    // ------------------------------------------------------------------ bounded ambiguity search
    public static final class AmbiguityResult {
        public String witness; public long derivations; public int maxLen; public long formsExplored; public boolean exhausted;
    }

    /**
     * Semi-decision procedure. Counts leftmost derivations of every terminal string of length <= maxLen
     * (layer-by-layer dynamic programming over sentential forms). A string with >= 2 leftmost derivations
     * is a witness of ambiguity. Absence of a witness proves nothing beyond the bound
     * (ambiguity of CFGs is undecidable).
     */
    public AmbiguityResult boundedAmbiguity(int maxLen, int maxSteps, long formBudget) {
        AmbiguityResult r = new AmbiguityResult(); r.maxLen = maxLen; r.exhausted = true;
        Map<String, Set<String>> f = first();
        Set<String> nullable = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : f.entrySet()) if (e.getValue().contains(EPS)) nullable.add(e.getKey());
        Map<List<String>, Long> layer = new HashMap<>();
        layer.put(List.of(start), 1L);
        Map<List<String>, Long> sentences = new HashMap<>();
        for (int step = 0; step < maxSteps && !layer.isEmpty(); step++) {
            Map<List<String>, Long> next = new HashMap<>();
            for (Map.Entry<List<String>, Long> e : layer.entrySet()) {
                List<String> form = e.getKey();
                int nt = -1, terms = 0, nonNullNT = 0;
                for (int i = 0; i < form.size(); i++) {
                    String s = form.get(i);
                    if (isNT(s)) { if (nt < 0) nt = i; if (!nullable.contains(s)) nonNullNT++; } else terms++;
                }
                if (nt < 0) { sentences.merge(form, e.getValue(), Long::sum); continue; }
                for (List<String> b : rules.get(form.get(nt))) {
                    List<String> nf = new ArrayList<>(form.size() + b.size());
                    nf.addAll(form.subList(0, nt)); nf.addAll(b); nf.addAll(form.subList(nt + 1, form.size()));
                    int t2 = 0, nn = 0, all = nf.size();
                    for (String s : nf) { if (!isNT(s)) t2++; else if (!nullable.contains(s)) nn++; }
                    if (t2 + nn > maxLen || all > 2 * maxLen + 4) continue;
                    next.merge(nf, e.getValue(), Long::sum);
                }
            }
            r.formsExplored += next.size();
            if (r.formsExplored > formBudget) { r.exhausted = false; break; }
            layer = next;
        }
        List<String> best = null; long bestCount = 0;
        for (Map.Entry<List<String>, Long> e : sentences.entrySet())
            if (e.getValue() >= 2 && (best == null || e.getKey().size() < best.size())) { best = e.getKey(); bestCount = e.getValue(); }
        if (best != null) { r.witness = String.join(" ", best); r.derivations = bestCount; }
        return r;
    }
}
