package org.refactor.context;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;

/**
 * 方法分析器，用于分析Java方法并构建上下文图
 * <p>
 * 该类负责解析方法声明，提取其中的语句节点，并构建控制流图(CFG)和数据流图(DFG)。
 * 它通过递归遍历方法体中的所有语句来收集信息，然后使用GraphBuilder构建图结构。
 */
public class MethodAnalyzer {

    private final ContextGraph graph;
    private int idCounter = 0;

    /**
     * 构造一个新的方法分析器实例
     */
    public MethodAnalyzer() {
        this.graph = new ContextGraph();
    }

    /**
     * 分析给定的方法声明并构建上下文图
     *
     * @param md 要分析的方法声明
     * @return 包含方法语句、控制流和数据流信息的上下文图
     */
    public ContextGraph analyze(MethodDeclaration md) {
        idCounter = 0;
        graph.stmts.clear();
        graph.stmtIndex.clear();

        md.getBody().ifPresent(body -> {
            // 从方法体 BlockStmt 开始递归收集语句
            for (Statement s : body.getStatements()) {
                collectStmtRecursive(s);
            }
        });

        // 语句收集完，再构建 CFG + DFG
        GraphBuilder.buildCFG(graph);
        GraphBuilder.buildDFG(graph);

        return graph;
    }

    /**
     * 递归收集语句节点
     *
     * @param s 当前要处理的语句
     */
    private void collectStmtRecursive(Statement s) {
        // 如果是 BlockStmt，本身不建节点，只遍历里面的语句
        if (s.isBlockStmt()) {
            for (Statement child : s.asBlockStmt().getStatements()) {
                collectStmtRecursive(child);
            }
            return;
        }

        // 为当前语句建一个节点
        StmtNode node = new StmtNode();
        node.id = idCounter++;
        node.code = s.toString();
        node.kind = s.getClass().getSimpleName();
        node.lineStart = s.getBegin().map(p -> p.line).orElse(-1);
        node.lineEnd = s.getEnd().map(p -> p.line).orElse(-1);
        node.astNode = s;

        VarDefUseCollector.collect(s, node.defs, node.uses);

        graph.stmts.add(node);
        graph.stmtIndex.put(s, node.id);

        // 对控制结构内部再递归收集子语句
        if (s.isIfStmt()) {
            IfStmt is = s.asIfStmt();
            collectStmtRecursive(is.getThenStmt());
            is.getElseStmt().ifPresent(this::collectStmtRecursive);
        } else if (s.isForStmt()) {
            collectStmtRecursive(s.asForStmt().getBody());
        } else if (s.isForEachStmt()) {
            collectStmtRecursive(s.asForEachStmt().getBody());
        } else if (s.isWhileStmt()) {
            collectStmtRecursive(s.asWhileStmt().getBody());
        } else if (s.isDoStmt()) {
            collectStmtRecursive(s.asDoStmt().getBody());
        } else if (s.isTryStmt()) {
            TryStmt ts = s.asTryStmt();
            collectStmtRecursive(ts.getTryBlock());
            ts.getCatchClauses().forEach(c -> collectStmtRecursive(c.getBody()));
            ts.getFinallyBlock().ifPresent(this::collectStmtRecursive);
        } else if (s.isSwitchStmt()) {
            s.asSwitchStmt().getEntries().forEach(e -> {
                e.getStatements().forEach(this::collectStmtRecursive);
            });
        }
        // 其它类型语句（表达式、return 等）本身节点已经建完，不需要额外处理
    }
}