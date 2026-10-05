# Reference implementation and experiment harness

Produces every number in Section 4 of the revised manuscript.

    mkdir -p out
    javac -encoding UTF-8 -d out src/ccfe/*.java
    java -Dstdout.encoding=UTF-8 -Xss16m -Xms1g -Xmx2g -cp out ccfe.Experiments > results.txt

- Lexer.java       lexical analysis (Table 3, Eqs. 1-2)
- Grammar.java     left recursion, left factoring, FIRST/FOLLOW, LL(1) table, bounded ambiguity search, table-driven parser
- Parser.java      recursive-descent parser for G_C (Table 4) with panic-mode recovery
- Experiments.java 68-case test suite, cross-validation, performance measurements

results.txt is the run reported in the paper. Timing numbers depend on the machine and JVM flags; correctness numbers do not.
