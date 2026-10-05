package ccfe;

import java.util.*;

/**
 * Lexical analyser L : Sigma* -> Tok* for the simplified C-like language.
 * Maximal-munch scanning, keywords take priority over identifiers,
 * whitespace and comments are discarded. One pass, O(|S|) time.
 */
public final class Lexer {

    public enum Kind {
        // keywords
        INT, CHAR, FLOAT, VOID, IF, ELSE, RETURN,
        // identifiers and literals
        ID, NUM, CHAR_LIT,
        // arithmetic operators
        PLUS, MINUS, MULT, DIV,
        // assignment operators
        ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN,
        // relational operators
        LT, GT, LE, GE, EQ, NE,
        // delimiters
        LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI,
        // special
        ERROR, EOF
    }

    public static final class Token {
        public final Kind kind; public final String lexeme; public final int line, col;
        Token(Kind k, String lx, int l, int c) { kind = k; lexeme = lx; line = l; col = c; }
        @Override public String toString() { return "<" + kind + "," + lexeme + ">"; }
    }

    private static final Map<String, Kind> KEYWORDS = Map.of(
            "int", Kind.INT, "char", Kind.CHAR, "float", Kind.FLOAT, "void", Kind.VOID,
            "if", Kind.IF, "else", Kind.ELSE, "return", Kind.RETURN);

    private final String src; private int pos = 0, line = 1, col = 1;
    public final List<String> errors = new ArrayList<>();

    public Lexer(String source) { this.src = source; }

    public List<Token> tokenize() {
        ArrayList<Token> out = new ArrayList<>(Math.max(16, src.length() / 3));
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) { out.add(new Token(Kind.EOF, "$", line, col)); return out; }
            out.add(next());
        }
    }

    private char peek(int k) { int p = pos + k; return p < src.length() ? src.charAt(p) : '\0'; }

    private void advance() { if (src.charAt(pos) == '\n') { line++; col = 1; } else col++; pos++; }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { advance(); continue; }
            if (c == '/' && peek(1) == '/') { while (pos < src.length() && src.charAt(pos) != '\n') advance(); continue; }
            if (c == '/' && peek(1) == '*') {
                int l = line, cc = col; advance(); advance();
                while (pos < src.length() && !(src.charAt(pos) == '*' && peek(1) == '/')) advance();
                if (pos >= src.length()) { errors.add(l + ":" + cc + ": lexical error: unterminated comment"); return; }
                advance(); advance(); continue;
            }
            return;
        }
    }

    private Token next() {
        int l = line, c = col, start = pos; char ch = src.charAt(pos);
        if (Character.isLetter(ch) || ch == '_') {
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) advance();
            String lx = src.substring(start, pos);
            return new Token(KEYWORDS.getOrDefault(lx, Kind.ID), lx, l, c);
        }
        if (Character.isDigit(ch)) {
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) advance();
            if (peek(0) == '.' && Character.isDigit(peek(1))) {
                advance(); while (pos < src.length() && Character.isDigit(src.charAt(pos))) advance();
            }
            if (pos < src.length() && (Character.isLetter(src.charAt(pos)) || src.charAt(pos) == '_')) {
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) advance();
                String bad = src.substring(start, pos);
                errors.add(l + ":" + c + ": lexical error: malformed identifier/number '" + bad + "'");
                return new Token(Kind.ERROR, bad, l, c);
            }
            return new Token(Kind.NUM, src.substring(start, pos), l, c);
        }
        if (ch == '\'') {
            advance();
            if (peek(0) == '\\' && "ntr0\\'".indexOf(peek(1)) >= 0 && peek(2) == '\'') { advance(); advance(); advance(); }
            else if (peek(0) != '\'' && peek(0) != '\n' && peek(0) != '\0' && peek(1) == '\'') { advance(); advance(); }
            else {
                while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != ';') advance();
                errors.add(l + ":" + c + ": lexical error: malformed character literal");
                return new Token(Kind.ERROR, src.substring(start, pos), l, c);
            }
            return new Token(Kind.CHAR_LIT, src.substring(start, pos), l, c);
        }
        char n = peek(1);
        Kind k; int len = 2;
        switch (ch) {
            case '+': k = n == '=' ? Kind.PLUS_ASSIGN : Kind.PLUS; if (n != '=') len = 1; break;
            case '-': k = n == '=' ? Kind.MINUS_ASSIGN : Kind.MINUS; if (n != '=') len = 1; break;
            case '=': k = n == '=' ? Kind.EQ : Kind.ASSIGN; if (n != '=') len = 1; break;
            case '<': k = n == '=' ? Kind.LE : Kind.LT; if (n != '=') len = 1; break;
            case '>': k = n == '=' ? Kind.GE : Kind.GT; if (n != '=') len = 1; break;
            case '!':
                if (n == '=') { k = Kind.NE; break; }
                k = Kind.ERROR; len = 1; break;
            case '*': k = Kind.MULT; len = 1; break;
            case '/': k = Kind.DIV; len = 1; break;
            case '(': k = Kind.LPAREN; len = 1; break;
            case ')': k = Kind.RPAREN; len = 1; break;
            case '{': k = Kind.LBRACE; len = 1; break;
            case '}': k = Kind.RBRACE; len = 1; break;
            case ',': k = Kind.COMMA; len = 1; break;
            case ';': k = Kind.SEMI; len = 1; break;
            default: k = Kind.ERROR; len = 1;
        }
        for (int i = 0; i < len; i++) advance();
        String lx = src.substring(start, pos);
        if (k == Kind.ERROR) errors.add(l + ":" + c + ": lexical error: unexpected character '" + lx + "'");
        return new Token(k, lx, l, c);
    }
}
