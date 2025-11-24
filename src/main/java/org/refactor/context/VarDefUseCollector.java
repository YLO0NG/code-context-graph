package org.refactor.context;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.Set;

public class VarDefUseCollector {

    public static void collect(Node stmt, Set<String> defs, Set<String> uses) {

        if (stmt instanceof IfStmt ifStmt) {
            analyzeExpression(ifStmt.getCondition(), defs, uses);
            return;
        }

        if (stmt instanceof ForStmt forStmt) {
            forStmt.getInitialization().forEach(init -> analyzeExpression(init, defs, uses));
            forStmt.getCompare().ifPresent(c -> analyzeExpression(c, defs, uses));
            forStmt.getUpdate().forEach(u -> analyzeExpression(u, defs, uses));
            return;
        }

        if (stmt instanceof WhileStmt whileStmt) {
            analyzeExpression(whileStmt.getCondition(), defs, uses);
            return;
        }

        if (stmt instanceof DoStmt doStmt) {
            analyzeExpression(doStmt.getCondition(), defs, uses);
            return;
        }

        if (stmt instanceof SwitchStmt switchStmt) {
            analyzeExpression(switchStmt.getSelector(), defs, uses);
            return;
        }

        if (stmt instanceof TryStmt) {
            return;
        }

        // 普通语句：整棵子树分析
        analyzeExpression(stmt, defs, uses);
    }

    private static void analyzeExpression(Node node, Set<String> defs, Set<String> uses) {
        node.walk(n -> {

            // 1) NameExpr：默认视为 use，但要排除赋值左值
            if (n instanceof NameExpr nameExpr) {
                if (nameExpr.getParentNode().isPresent()) {
                    Node parent = nameExpr.getParentNode().get();
                    if (parent instanceof AssignExpr assign &&
                            assign.getTarget() == nameExpr) {
                        // 这是赋值左侧，稍后由 AssignExpr 处理 defs，不算 use
                        return;
                    }
                }
                try {
                    uses.add(nameExpr.resolve().getName());
                } catch (Exception ignored) {
                }
            }

            // 2) 变量声明：def
            if (n instanceof VariableDeclarator vd) {
                try {
                    defs.add(vd.resolve().getName());
                } catch (Exception ignored) {
                }
            }

            // 3) 赋值左值：def
            if (n instanceof AssignExpr assignExpr) {
                Expression target = assignExpr.getTarget();
                if (target.isNameExpr()) {
                    try {
                        defs.add(target.asNameExpr().resolve().getName());
                    } catch (Exception ignored) {
                    }
                }
                // 右侧表达式里的 NameExpr 会在 walk 过程中自然被视为 use
            }
        });
    }
}
