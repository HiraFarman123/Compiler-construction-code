package ccfe;

import ccfe.Lexer.Kind;
import ccfe.Lexer.Token;
import java.util.*;

/**
 * Recursive-descent parser for the LL(1) grammar G_C (Table 1 of the manuscript).
 * One method per non-terminal; productions are selected with one token of lookahead
 * exactly as in the predictive parsing table. The single LL(1) conflict of G_C,
 * (ElsePart, else), is resolved in favour of ElsePart -> else Stmt (nearest-if rule).
 * Panic-mode recovery: a syntax error unwinds to the nearest statement-level
 * procedure, which discards tokens until a synchronising token is found.
 */
public final class Parser {

    static final class SyntaxError extends RuntimeException {
        SyntaxError() { super(null, null, false, false); }
    }

    private static final EnumSet<Kind> TYPE = EnumSet.of(Kind.INT, Kind.CHAR, Kind.FLOAT);
    private static final EnumSet<Kind> ASSIGN_OP = EnumSet.of(Kind.ASSIGN, Kind.PLUS_ASSIGN, Kind.MINUS_ASSIGN);
    private static final EnumSet<Kind> REL_OP = EnumSet.of(Kind.LT, Kind.GT, Kind.LE, Kind.GE, Kind.EQ, Kind.NE);
    private static final EnumSet<Kind> FIRST_STMT = EnumSet.of(Kind.INT, Kind.CHAR, Kind.FLOAT, Kind.ID,
            Kind.IF, Kind.RETURN, Kind.LBRACE, Kind.SEMI);
    private static final EnumSet<Kind> FIRST_EXPR = EnumSet.of(Kind.LPAREN, Kind.ID, Kind.NUM, Kind.CHAR_LIT, Kind.MINUS);
    /** synchronising set = FOLLOW(Stmt) U FIRST(Stmt) U {void} (statement-level panic mode) */
    private static final EnumSet<Kind> SYNC = EnumSet.of(Kind.SEMI, Kind.RBRACE, Kind.EOF, Kind.INT, Kind.CHAR,
            Kind.FLOAT, Kind.VOID, Kind.IF, Kind.RETURN, Kind.LBRACE, Kind.ELSE);

    private final List<Token> toks; private int p = 0;
    public final List<String> errors = new ArrayList<>();
    public int depth = 0, maxDepth = 0, calls = 0;

    public Parser(List<Token> tokens) { this.toks = tokens; skipLexErrors(); }

    // ---------- token helpers ----------
    private Token la() { return toks.get(p); }
    private Kind k() { return toks.get(p).kind; }
    private void skipLexErrors() { while (toks.get(p).kind == Kind.ERROR) p++; }   // already reported by the lexer
    private void advance() { if (k() != Kind.EOF) p++; skipLexErrors(); }
    private Token expect(Kind kind, String what) {
        if (k() == kind) { Token t = la(); advance(); return t; }
        throw error("expected " + what);
    }
    private SyntaxError error(String msg) {
        Token t = la();
        errors.add(t.line + ":" + t.col + ": syntax error: " + msg + " but found '" + t.lexeme + "'");
        return new SyntaxError();
    }
    private void enter() { calls++; if (++depth > maxDepth) maxDepth = depth; }
    private void leave() { depth--; }

    /** panic mode: discard tokens until a synchronising token; consume a ';' if that is where we stopped */
    private void synchronize(int errorPos) {
        if (p == errorPos && k() != Kind.EOF && k() != Kind.RBRACE) advance();   // guarantee progress
        while (!SYNC.contains(k())) advance();
        if (k() == Kind.SEMI) advance();
    }

    // ---------- Program -> TopList EOF ----------
    public boolean parse() {
        topList();
        if (k() != Kind.EOF) { error("expected end of input"); }
        return errors.isEmpty();
    }

    // TopList -> Top TopList | eps        (FOLLOW = {$})
    private void topList() {
        while (k() != Kind.EOF) {
            if (k() == Kind.RBRACE) { error("unmatched '}'"); advance(); continue; }
            int start = p;
            try { top(); } catch (SyntaxError e) { synchronize(start); }
        }
    }

    // Top -> Type id TopTail | void id ( ParamsOpt ) FuncBody | NonDeclStmt
    private void top() {
        enter();
        if (TYPE.contains(k())) { advance(); expect(Kind.ID, "identifier"); topTail(); }
        else if (k() == Kind.VOID) {
            advance(); expect(Kind.ID, "function name"); expect(Kind.LPAREN, "'('");
            paramsOpt(); expect(Kind.RPAREN, "')'"); funcBody();
        }
        else nonDeclStmt();
        leave();
    }

    // TopTail -> ( ParamsOpt ) FuncBody | VarTail
    private void topTail() {
        enter();
        if (k() == Kind.LPAREN) { advance(); paramsOpt(); expect(Kind.RPAREN, "')'"); funcBody(); }
        else varTail();
        leave();
    }

    // FuncBody -> Block | ;
    private void funcBody() {
        enter();
        if (k() == Kind.LBRACE) block(); else expect(Kind.SEMI, "'{' or ';'");
        leave();
    }

    // ParamsOpt -> Param ParamRest | eps ;  ParamRest -> , Param ParamRest | eps ; Param -> Type id
    private void paramsOpt() {
        enter();
        if (TYPE.contains(k())) {
            param();
            while (k() == Kind.COMMA) { advance(); param(); }
        } else if (k() != Kind.RPAREN) throw error("expected parameter type or ')'");
        leave();
    }
    private void param() {
        enter();
        if (!TYPE.contains(k())) throw error("expected parameter type");
        advance(); expect(Kind.ID, "parameter name"); leave();
    }

    // VarTail -> InitOpt MoreVars ;   MoreVars -> , id InitOpt MoreVars | eps   InitOpt -> = Expr | eps
    private void varTail() {
        enter();
        initOpt();
        while (k() == Kind.COMMA) { advance(); expect(Kind.ID, "identifier"); initOpt(); }
        expect(Kind.SEMI, "';'");
        leave();
    }
    private void initOpt() {
        enter();
        if (k() == Kind.ASSIGN) { advance(); expr(); }
        else if (k() != Kind.COMMA && k() != Kind.SEMI) throw error("expected '=', ',' or ';'");
        leave();
    }

    // Block -> { StmtList } ; StmtList -> Stmt StmtList | eps   (FOLLOW = { } })
    private void block() {
        enter();
        expect(Kind.LBRACE, "'{'");
        while (k() != Kind.RBRACE && k() != Kind.EOF) {
            int start = p;
            if (!FIRST_STMT.contains(k())) {
                try { throw error("expected statement"); } catch (SyntaxError e) { synchronize(start); continue; }
            }
            try { stmt(); } catch (SyntaxError e) { synchronize(start); }
        }
        expect(Kind.RBRACE, "'}'");
        leave();
    }

    // Stmt -> Type id VarTail | NonDeclStmt
    private void stmt() {
        enter();
        if (TYPE.contains(k())) { advance(); expect(Kind.ID, "identifier"); varTail(); }
        else nonDeclStmt();
        leave();
    }

    // NonDeclStmt -> id IdTail ; | if ( Cond ) Stmt ElsePart | return RetExpr ; | Block | ;
    private void nonDeclStmt() {
        enter();
        switch (k()) {
            case ID: advance(); idTail(); expect(Kind.SEMI, "';'"); break;
            case IF: {
                advance(); expect(Kind.LPAREN, "'('"); cond(); expect(Kind.RPAREN, "')'");
                stmtRecover();
                if (k() == Kind.ELSE) { advance(); stmtRecover(); }     // ElsePart -> else Stmt | eps
                break;
            }
            case RETURN: advance(); if (FIRST_EXPR.contains(k())) expr(); expect(Kind.SEMI, "';'"); break;
            case LBRACE: block(); break;
            case SEMI: advance(); break;
            default: throw error("expected statement");
        }
        leave();
    }
    /** nested statement with its own recovery point, so an error inside a branch does not lose the if */
    private void stmtRecover() {
        int start = p;
        try { stmt(); } catch (SyntaxError e) { synchronize(start); }
    }

    // IdTail -> AssignOp Expr | ( ArgsOpt )
    private void idTail() {
        enter();
        if (ASSIGN_OP.contains(k())) { advance(); expr(); }
        else if (k() == Kind.LPAREN) { advance(); argsOpt(); expect(Kind.RPAREN, "')'"); }
        else throw error("expected assignment operator or '('");
        leave();
    }

    // ArgsOpt -> Expr ArgRest | eps ; ArgRest -> , Expr ArgRest | eps
    private void argsOpt() {
        enter();
        if (FIRST_EXPR.contains(k())) { expr(); while (k() == Kind.COMMA) { advance(); expr(); } }
        leave();
    }

    // Cond -> Expr RelOp Expr
    private void cond() {
        enter();
        expr();
        if (!REL_OP.contains(k())) throw error("expected relational operator");
        advance(); expr();
        leave();
    }

    // Expr -> Term Expr' ; Expr' -> AddOp Term Expr' | eps
    private void expr() {
        enter(); term();
        while (k() == Kind.PLUS || k() == Kind.MINUS) { advance(); term(); }
        leave();
    }
    // Term -> Factor Term' ; Term' -> MulOp Factor Term' | eps
    private void term() {
        enter(); factor();
        while (k() == Kind.MULT || k() == Kind.DIV) { advance(); factor(); }
        leave();
    }
    // Factor -> ( Expr ) | id FactorTail | num | charlit | - Factor ; FactorTail -> ( ArgsOpt ) | eps
    private void factor() {
        enter();
        switch (k()) {
            case LPAREN: advance(); expr(); expect(Kind.RPAREN, "')'"); break;
            case ID: advance(); if (k() == Kind.LPAREN) { advance(); argsOpt(); expect(Kind.RPAREN, "')'"); } break;
            case NUM: case CHAR_LIT: advance(); break;
            case MINUS: advance(); factor(); break;
            default: throw error("expected operand");
        }
        leave();
    }
}
