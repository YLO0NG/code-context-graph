package org.refactor.context;

import com.github.javaparser.ast.stmt.Statement;

import java.util.*;

/**
 * 存放一个方法的所有语句节点 + CFG + DFG
 */
public class ContextGraph {
    // 顺序收集的语句列表
    public List<StmtNode> stmts = new ArrayList<>();

    // CFG：控制流后继边  id -> 后继 id 列表
    public Map<Integer, List<Integer>> cfgSucc = new HashMap<>();

    // DFG：数据流后继边  定义语句 id -> 使用该值的语句 id 列表
    public Map<Integer, List<Integer>> dfgSucc = new HashMap<>();

    // AST 节点到语句 id 的映射（内部使用，不输出 JSON）
    public transient Map<Statement, Integer> stmtIndex = new IdentityHashMap<>();
}
