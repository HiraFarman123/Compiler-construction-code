package ccfe;

import ccfe.Lexer.Kind;
import ccfe.Lexer.Token;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Reproduces every number reported in Section 4 of the manuscript. Run: java -Xss16m ccfe.Experiments */
public final class Experiments {

    public static final String G_C = String.join("\n",
        "Program -> TopList",
        "TopList -> Top TopList | ε",
        "Top -> Type id TopTail | void id ( ParamsOpt ) FuncBody | NonDeclStmt",
        "TopTail -> ( ParamsOpt ) FuncBody | VarTail",
        "FuncBody -> Block | ;",
        "ParamsOpt -> Param ParamRest | ε",
        "ParamRest -> , Param ParamRest | ε",
        "Param -> Type id",
        "VarTail -> InitOpt MoreVars ;",
        "MoreVars -> , id InitOpt MoreVars | ε",
        "InitOpt -> = Expr | ε",
        "Type -> int | char | float",
        "Block -> { StmtList }",
        "StmtList -> Stmt StmtList | ε",
        "Stmt -> Type id VarTail | NonDeclStmt",
        "NonDeclStmt -> id IdTail ; | if ( Cond ) Stmt ElsePart | return RetExpr ; | Block | ;",
        "ElsePart -> else Stmt | ε",
        "RetExpr -> Expr | ε",
        "IdTail -> AssignOp Expr | ( ArgsOpt )",
        "AssignOp -> = | += | -=",
        "ArgsOpt -> Expr ArgRest | ε",
        "ArgRest -> , Expr ArgRest | ε",
        "Cond -> Expr RelOp Expr",
        "RelOp -> < | > | <= | >= | == | !=",
        "Expr -> Term Expr'",
        "Expr' -> AddOp Term Expr' | ε",
        "AddOp -> + | -",
        "Term -> Factor Term'",
        "Term' -> MulOp Factor Term' | ε",
        "MulOp -> * | /",
        "Factor -> ( Expr ) | id FactorTail | num | charlit | - Factor",
        "FactorTail -> ( ArgsOpt ) | ε");

    static String terminalOf(Token t) {
        switch (t.kind) {
            case ID: return "id"; case NUM: return "num"; case CHAR_LIT: return "charlit";
            case EOF: return Grammar.END; case ERROR: return "<lexerr>";
            default: return t.lexeme;
        }
    }

    // ------------------------------------------------------------------ test corpus
    record Case(String id, String cat, String src, boolean accept, int seeded, int[] lines) {}

    static List<Case> corpus() {
        List<Case> c = new ArrayList<>();
        String[][] valid = {
            {"DEC", "int a = 9;"}, {"DEC", "int hiraf;"}, {"DEC", "char f;"}, {"DEC", "float x = 3.14;"},
            {"DEC", "int a, b = 2, c;"}, {"DEC", "char c = 'x';"}, {"DEC", "char nl = '\\n';"},
            {"EXP", "a = b + c * d;"}, {"EXP", "a += 1;"}, {"EXP", "a -= b / 2;"},
            {"EXP", "x = (a + b) * (c - d) / e;"}, {"EXP", "x = ((((a))));"}, {"EXP", "x = -a + -(b * c);"},
            {"EXP", "x = f(a, b + 1, g(c));"}, {"EXP", "print(x);"}, {"EXP", "y = a * b + c * d - e / f + g;"},
            {"EXP", "x=a+b;"}, {"EXP", "int_var_1 = _x2;"},
            {"CND", "if (a < b) x = 1;"}, {"CND", "if (a == 3) { x = 1; } else { x = 2; }"},
            {"CND", "if (a >= b) if (c != d) x = 1; else x = 2;"},
            {"CND", "if (a <= b + 1) { if (c > d) { y = 1; } else { y = 2; } } else y = 3;"},
            {"CND", "if ((a + b) * c > d - e) ;"},
            {"FUN", "void khizar(int a);"}, {"FUN", "void khizar(int a) { a = a + 1; }"},
            {"FUN", "int add(int a, int b) { return a + b; }"}, {"FUN", "void f() { }"},
            {"FUN", "float avg(float a, float b) { float s = a + b; return s / 2; }"}, {"FUN", "void g() { return; }"},
            {"BND", "int a = 1; // comment\n/* block\n comment */ int b = 2;"},
            {"BND", ""}, {"BND", ";"}, {"BND", "int " + "a".repeat(255) + " = 0;"}, {"BND", "int big = 2147483647;"},
            {"BND", "x = " + "(".repeat(100) + "a" + ")".repeat(100) + ";"},
            {"BND", "{".repeat(50) + "x = 1;" + "}".repeat(50)},
            {"BND", "if (a < b) ".repeat(30) + "x = 1;"}, {"BND", "{ }"},
        };
        int i = 1;
        for (String[] v : valid) c.add(new Case("V" + (i++), v[0], v[1], true, 0, new int[0]));
        c.add(new Case("V" + (i++), "PRG", MEDIUM_PROGRAM, true, 0, new int[0]));
        c.add(new Case("V" + (i++), "PRG", generate(20_000, 7), true, 0, new int[0]));

        String[][] invalid = {
            {"SYN", "int a = ;"}, {"SYN", "if (a < b x = 1;"}, {"SYN", "int a = 9"}, {"SYN", "a = b + * c;"},
            {"SYN", "int = 5;"}, {"SYN", "if a < b) x = 1;"}, {"SYN", "x = (a + b;"}, {"SYN", "void f(int a { }"},
            {"SYN", "if (a) x = 1;"}, {"SYN", "else x = 1;"}, {"SYN", "x = a b;"}, {"SYN", "return return;"},
            {"SYN", "{ x = 1;"}, {"SYN", "x = 1; }"}, {"SYN", "x == 1;"}, {"SYN", "void f(int a, ) { }"},
            {"SYN", "float f(float) { }"}, {"SYN", "int a = 9 int b = 2;"},
            {"LEX", "int 9a = 1;"}, {"LEX", "x = a @ b;"}, {"LEX", "char c = 'ab';"}, {"LEX", "x = a ! b;"},
        };
        i = 1;
        for (String[] v : invalid) c.add(new Case("I" + (i++), v[0], v[1], false, 1, new int[]{1}));

        // multiple seeded errors, one per line, separated by valid lines
        c.add(new Case("M1", "MUL", "int a = ;\nint b = 2;\nc = * 3;\nint d = 4;\ne = (5;", false, 3, new int[]{1, 3, 5}));
        c.add(new Case("M2", "MUL", "if (a < b x = 1;\ny = 2;\nz = 2 + ;\nw = 1;\nv = (3;", false, 3, new int[]{1, 3, 5}));
        c.add(new Case("M3", "MUL", "void f(int a) {\n  a = ;\n  int b = 2;\n  c = 3 +;\n  b = 1;\n  if (a > ) b = 1;\n  b = 2;\n  return a +;\n}",
                false, 4, new int[]{2, 4, 6, 8}));
        c.add(new Case("M4", "MUL", "int x = 1 $ 2;\nint ok = 0;\nint y = @;\nint ok2 = 0;\nz = 3 +;", false, 3, new int[]{1, 3, 5}));
        c.add(new Case("M5", "MUL", "void f() {\n  if (a < b) {\n    x = ;\n  } else {\n    y = 1;\n  }\n  z = (a + ;\n}\nint g = ;", false, 3, new int[]{3, 7, 9}));
        int[] lines = new int[10];
        c.add(new Case("M6", "MUL", seedErrors(generate(20_000, 11), 10, lines), false, 10, lines));
        return c;
    }

    static final String MEDIUM_PROGRAM = String.join("\n",
        "/* medium-sized program used as an end-to-end validation input */",
        "int counter = 0;",
        "float rate = 0.75;",
        "char grade = 'A';",
        "int max(int a, int b) {",
        "  if (a > b) return a;",
        "  else return b;",
        "}",
        "float scale(float v, float k) {",
        "  float r = v * k;",
        "  r -= (v - k) / 2;",
        "  return r;",
        "}",
        "void update(int step) {",
        "  counter += step;",
        "  if (counter >= 100) {",
        "    counter = counter - 100;",
        "    if (step != 0) report(counter, step * 2);",
        "  } else {",
        "    counter = counter + max(step, 1);",
        "  }",
        "}",
        "void report(int c, int s);",
        "int main() {",
        "  int i = 0, total = 0;",
        "  total = max(i, 10) + scale(rate, 2.5) * -3;",
        "  if (total < 0) total = -total;",
        "  update(total);",
        "  { int inner = (total + 1) * (total - 1); inner -= 1; }",
        "  return total;",
        "}");

    // ------------------------------------------------------------------ synthetic program generator
    static final String[] STMTS = {
        "x%d = a + b * (c - %d) / d;", "int v%d = %d;", "if (x%d < %d) { y = y + 1; } else y = y - 1;",
        "call%d(a, b + %d, c);", "float f%d = %d.5 * rate;", "if (a == %d) if (b != %d) z = 1; else z = 2;",
        "t%d += (((a + %d)));", "char ch%d = 'q'; k = -%d;" };

    static String generate(int targetTokens, long seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        int fn = 0, approx = 0;
        while (approx < targetTokens) {
            sb.append("int fn").append(fn++).append("(int a, int b) {\n");
            approx += 11;
            int n = 20 + r.nextInt(20);
            for (int s = 0; s < n; s++) {
                String t = STMTS[r.nextInt(STMTS.length)];
                int v = r.nextInt(1000);
                sb.append("  ").append(String.format(t, v, v)).append('\n');
                approx += 14;
            }
            sb.append("  return a;\n}\n");
            approx += 4;
        }
        return sb.toString();
    }

    /** replace k evenly spaced statement lines with a deliberately broken version; records their line numbers */
    static String seedErrors(String prog, int k, int[] linesOut) {
        String[] ls = prog.split("\n", -1);
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < ls.length; i++) if (ls[i].startsWith("  ") && !ls[i].contains("return") && ls[i].trim().startsWith("x")) cand.add(i);
        String[] breakers = {"x = a + ;", "x = (a + b;", "x = a b;", "int = 3;", "if (a < b x = 1;"};
        for (int j = 0; j < k; j++) {
            int idx = cand.get((int) ((long) j * cand.size() / k) + 1);
            ls[idx] = "  " + breakers[j % breakers.length];
            linesOut[j] = idx + 1;
        }
        return String.join("\n", ls);
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] args) {
        System.out.println("Java " + System.getProperty("java.version") + " / " + System.getProperty("java.vm.name")
                + " / " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " / maxHeap=" + Runtime.getRuntime().maxMemory() / (1 << 20) + " MB / cpus=" + Runtime.getRuntime().availableProcessors());
        grammarExperiments();
        corpusExperiments();
        performanceExperiments();
    }

    static void grammarExperiments() {
        System.out.println("\n=== GRAMMAR EXPERIMENTS ===");
        String[][] gs = {
            {"G1 ambiguous expression", "E -> E + E | E * E | ( E ) | id"},
            {"G2 left-recursive expression", "E -> E + T | T\nT -> T * F | F\nF -> ( E ) | id"},
            {"G3 dangling else", "S -> if ( C ) S | if ( C ) S else S | a\nC -> b"},
            {"G4 indirect left recursion", "A -> B a | b\nB -> A c | d"},
            {"G5 original <cond> (Table 1)", "Cond -> Keyw ( Stm ) | Cond Keyw ( Stm )\nKeyw -> if\nStm -> id < id"},
            {"G_C complete C-like grammar", G_C},
        };
        for (String[] g : gs) {
            Grammar gr = Grammar.parse(g[1]);
            System.out.println("\n--- " + g[0] + " ---");
            System.out.print(gr);
            long t0 = System.nanoTime();
            Grammar t = gr;
            boolean lr = gr.hasLeftRecursion();
            if (lr) t = t.eliminateLeftRecursion();
            Grammar f = t.leftFactor();
            boolean factored = f.productionCount() != t.productionCount() || f.rules.size() != t.rules.size();
            t = f;
            Grammar.Table tab = t.ll1Table();
            long t1 = System.nanoTime();
            System.out.println("left-recursive=" + lr + " factored=" + factored + " NT=" + t.rules.size()
                    + " prods=" + t.productionCount() + " terminals=" + t.terminals().size()
                    + " tableEntries=" + tab.filled() + " conflicts=" + tab.conflicts);
            if (lr || factored) { System.out.println("transformed:"); System.out.print(t); }
            if (!g[0].startsWith("G_C")) {
                Map<String, Set<String>> fi = t.first(), fo = t.follow(fi);
                for (String a : t.rules.keySet()) System.out.println("  FIRST(" + a + ")=" + fi.get(a) + "  FOLLOW(" + a + ")=" + fo.get(a));
            }
            int bound = g[0].startsWith("G_C") ? 7 : (g[0].startsWith("G3") ? 11 : 9);
            long a0 = System.nanoTime();
            Grammar.AmbiguityResult ar = gr.boundedAmbiguity(bound, 4 * bound + 12, 20_000_000L);
            long a1 = System.nanoTime();
            System.out.printf("analysis(LR+LF+FIRST/FOLLOW+table)=%.3f ms  ambiguity(bound=%d, exhausted=%b, forms=%d)=%.1f ms witness=%s derivations=%d%n",
                    (t1 - t0) / 1e6, bound, ar.exhausted, ar.formsExplored, (a1 - a0) / 1e6, ar.witness, ar.derivations);
        }
        // timing of the full grammar pipeline on G_C (median of 200 runs)
        Grammar gc = Grammar.parse(G_C);
        long[] ts = new long[200];
        for (int r = 0; r < 260; r++) {
            long a = System.nanoTime(); Grammar.Table tb = gc.leftFactor().ll1Table(); long b = System.nanoTime();
            if (r >= 60) ts[r - 60] = b - a; if (tb.filled() < 0) System.out.print("");
        }
        Arrays.sort(ts);
        System.out.printf("G_C pipeline median=%.3f ms p95=%.3f ms%n", ts[100] / 1e6, ts[190] / 1e6);
        Grammar.Table tb = gc.ll1Table();
        Map<String, Set<String>> fi = gc.first(), fo = gc.follow(fi);
        System.out.println("G_C FIRST/FOLLOW:");
        for (String a : gc.rules.keySet()) System.out.println("  " + a + " | FIRST=" + fi.get(a) + " | FOLLOW=" + fo.get(a));
    }

    static void corpusExperiments() {
        System.out.println("\n=== TEST CORPUS ===");
        Grammar gc = Grammar.parse(G_C);
        Grammar.Table tab = gc.ll1Table();
        int pass = 0, total = 0, agree = 0, seededTot = 0, detectedTot = 0, reportedTot = 0, spuriousTot = 0;
        Map<String, int[]> byCat = new TreeMap<>();
        for (Case cs : corpus()) {
            total++;
            Lexer lx = new Lexer(cs.src());
            List<Token> toks = lx.tokenize();
            Parser ps = new Parser(toks);
            boolean ok = ps.parse() && lx.errors.isEmpty();
            List<String> diags = new ArrayList<>(lx.errors); diags.addAll(ps.errors);
            List<String> term = new ArrayList<>(); for (Token t : toks) term.add(terminalOf(t));
            term.remove(term.size() - 1);
            boolean tableOk = gc.predictiveParse(tab, term);
            if (tableOk == ok) agree++;
            boolean correct = ok == cs.accept();
            int detected = 0, spurious = 0;
            if (!cs.accept()) {
                boolean[] used = new boolean[diags.size()];
                for (int ln : cs.lines()) {
                    boolean hit = false;
                    for (int d = 0; d < diags.size(); d++) {
                        int dl = Integer.parseInt(diags.get(d).split(":")[0]);
                        if (dl == ln || dl == ln + 1) { used[d] = true; hit = true; }
                    }
                    if (hit) detected++;
                }
                for (boolean u : used) if (!u) spurious++;
                // diagnostics beyond the first on the same erroneous line are cascades
                spurious += Math.max(0, countOnSeededLines(diags, cs.lines()) - detected);
                seededTot += cs.seeded(); detectedTot += detected; reportedTot += diags.size(); spuriousTot += spurious;
            }
            if (correct) pass++;
            byCat.computeIfAbsent(cs.cat(), x -> new int[3]);
            byCat.get(cs.cat())[0]++; if (correct) byCat.get(cs.cat())[1]++; if (tableOk == ok) byCat.get(cs.cat())[2]++;
            String shown = cs.src().length() > 60 ? cs.src().substring(0, 57).replace("\n", "⏎") + "..." : cs.src().replace("\n", "⏎");
            System.out.printf("%-4s %-4s %-6s exp=%-6s rdp=%-6s table=%-6s tokens=%-6d seeded=%d detected=%d diags=%d  %s%n",
                    cs.id(), cs.cat(), correct ? "PASS" : "FAIL", cs.accept() ? "accept" : "reject", ok ? "accept" : "reject",
                    tableOk ? "accept" : "reject", toks.size() - 1, cs.seeded(), detected, diags.size(), shown);
            if (!cs.accept() && cs.seeded() <= 4) for (String d : diags) System.out.println("        " + d);
        }
        System.out.println("TOTAL " + pass + "/" + total + " correct; RDP vs table-driven agreement " + agree + "/" + total);
        System.out.println("seeded=" + seededTot + " detected=" + detectedTot + " reported=" + reportedTot + " spurious=" + spuriousTot);
        byCat.forEach((k, v) -> System.out.println("  cat " + k + ": n=" + v[0] + " correct=" + v[1] + " agree=" + v[2]));
    }

    static int countOnSeededLines(List<String> diags, int[] lines) {
        int n = 0;
        for (String d : diags) { int dl = Integer.parseInt(d.split(":")[0]); for (int ln : lines) if (dl == ln || dl == ln + 1) { n++; break; } }
        return n;
    }

    static void performanceExperiments() {
        System.out.println("\n=== PERFORMANCE ===");
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        Grammar gc = Grammar.parse(G_C); Grammar.Table tab = gc.ll1Table();
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000};
        System.out.println("tokens,lines,chars,lex_median_ms,lex_iqr_ms,parse_median_ms,parse_iqr_ms,table_median_ms,total_median_ms,ns_per_token,lex_alloc_MB,parse_alloc_MB,tokens_retained_MB,max_depth");
        for (int n : sizes) {
            String src = generate(n, 42);
            int reps = n >= 1_000_000 ? 15 : 30, warm = n >= 1_000_000 ? 5 : 20;
            double[] lex = new double[reps], par = new double[reps], tbl = new double[reps];
            int ntok = 0, maxDepth = 0; long lexAlloc = 0, parAlloc = 0;
            for (int r = 0; r < warm + reps; r++) {
                long a0 = mx.getThreadAllocatedBytes(tid), t0 = System.nanoTime();
                Lexer lx = new Lexer(src); List<Token> toks = lx.tokenize();
                long t1 = System.nanoTime(), a1 = mx.getThreadAllocatedBytes(tid);
                Parser p = new Parser(toks); boolean ok = p.parse();
                long t2 = System.nanoTime(), a2 = mx.getThreadAllocatedBytes(tid);
                if (!ok) throw new IllegalStateException("generated program rejected: " + p.errors.get(0));
                List<String> term = new ArrayList<>(toks.size()); for (Token t : toks) term.add(terminalOf(t)); term.remove(term.size() - 1);
                long t3 = System.nanoTime(); boolean ok2 = gc.predictiveParse(tab, term); long t4 = System.nanoTime();
                if (!ok2) throw new IllegalStateException("table parser rejected");
                if (r >= warm) { lex[r - warm] = (t1 - t0) / 1e6; par[r - warm] = (t2 - t1) / 1e6; tbl[r - warm] = (t4 - t3) / 1e6; }
                ntok = toks.size() - 1; maxDepth = p.maxDepth; lexAlloc = a1 - a0; parAlloc = a2 - a1;
            }
            // retained memory of the token stream
            System.gc(); sleep(); long base = used();
            List<Token> keep = new Lexer(src).tokenize();
            System.gc(); sleep(); long withTokens = used();
            double retained = Math.max(0, withTokens - base) / 1048576.0;
            if (keep.isEmpty()) System.out.print("");
            double lm = median(lex), pm = median(par);
            System.out.printf(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f,%.2f,%.2f,%.2f,%d%n",
                    ntok, src.split("\n").length, src.length(), lm, iqr(lex), pm, iqr(par), median(tbl), lm + pm,
                    (lm + pm) * 1e6 / ntok, lexAlloc / 1048576.0, parAlloc / 1048576.0, retained, maxDepth);
        }
        // deepest nesting supported with the default 512 KB-1 MB thread stack vs. a 16 MB stack
        for (int stackKb : new int[]{512, 16384}) {
            int[] deepest = {0};
            Thread th = new Thread(null, () -> {
                int lo = 10, hi = 200_000;
                while (lo < hi) {
                    int mid = (lo + hi + 1) / 2;
                    String s = "x = " + "(".repeat(mid) + "a" + ")".repeat(mid) + ";";
                    try { Parser p = new Parser(new Lexer(s).tokenize()); p.parse(); lo = mid; }
                    catch (StackOverflowError e) { hi = mid - 1; }
                }
                deepest[0] = lo;
            }, "depth", stackKb * 1024L);
            th.start(); try { th.join(); } catch (InterruptedException e) { }
            System.out.println("max parenthesis nesting with " + stackKb + " KB stack = " + deepest[0]);
        }
    }

    static long used() { Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }
    static void sleep() { try { Thread.sleep(200); } catch (InterruptedException e) { } }
    static double median(double[] a) { double[] b = a.clone(); Arrays.sort(b); return b.length % 2 == 1 ? b[b.length / 2] : (b[b.length / 2 - 1] + b[b.length / 2]) / 2; }
    static double iqr(double[] a) { double[] b = a.clone(); Arrays.sort(b); return b[(3 * b.length) / 4] - b[b.length / 4]; }
    static double sd(double[] a) { double m = 0; for (double x : a) m += x; m /= a.length; double s = 0; for (double x : a) s += (x - m) * (x - m); return Math.sqrt(s / (a.length - 1)); }
}
