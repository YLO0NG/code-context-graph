package org.refactor.context;

import com.github.javaparser.ast.stmt.Statement;

import java.util.HashSet;
import java.util.Set;

/**
 * 表示方法中的一条语句（或一个控制结构语句）
 */
public class StmtNode {
    public int id;              // 语句在方法内的编号
    public String code;         // 语句源码
    public int lineStart;       // 起始行号
    public int lineEnd;         // 结束行号
    public Set<String> defs = new HashSet<>(); // 定义的变量
    public Set<String> uses = new HashSet<>(); // 使用的变量
    public String kind;         // 语句类型名称（类名）

    // AST 节点引用，用于构建 CFG（不参与 JSON 序列化）
    // 标记为transient使其在序列化为JSON时被忽略
    public transient Statement astNode;
}