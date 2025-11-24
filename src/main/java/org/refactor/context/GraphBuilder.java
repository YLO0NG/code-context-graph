package org.refactor.context;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.ForEachStmt; // 支持增强型 for 循环

import java.util.*;
import java.util.stream.Collectors;

public class GraphBuilder {

    /**
     * 构建控制流图 (CFG)
     * 使用 AST Visitor 模式，而非简单的列表索引，以正确处理嵌套和跳转。
     */
    public static void buildCFG(ContextGraph g) {
        // 1. 初始化 CFG Map
        for (StmtNode node : g.stmts) {
            g.cfgSucc.put(node.id, new ArrayList<>());
        }

        // 2. 找到入口语句（通常是列表中的第一个，或者是第一个非 Block 语句）
        if (g.stmts.isEmpty()) return;

        // 我们假设 g.stmts 的顺序大致符合源码顺序，但为了严谨，我们从 AST 根节点开始遍历
        // 由于 MethodAnalyzer 已经将所有有效语句放入了 stmts 列表并建立了 stmtIndex 映射
        // 我们只需要遍历方法体即可。但是 ContextGraph 没有直接保存方法体 AST。
        // 不过 g.stmts[0].astNode 的父节点链条可以找到方法体，或者更简单地：
        // 直接认为 g.stmts[0] 是入口是合理的，但为了处理复杂结构，我们最好重新从最外层遍历。

        // 这里采用一种策略：找到所有 "根" 语句（没有父节点在 stmts 列表中的语句），通常只有方法体内的第一层语句
        // 但为了简便且健壮，我们构建一个虚拟的 Root Visitor。

        // 实际上，MethodAnalyzer 并没有保存 MethodDeclaration，但保存了 stmts。
        // 最稳健的方式：利用 AST 结构进行递归连接。
        // 我们取第一个语句的父节点（通常是 BlockStmt 或 MethodDeclaration 的 body）作为遍历起点。
        Statement firstStmt = g.stmts.get(0).astNode;
        Node root = firstStmt.getParentNode().orElse(null);
        // 如果是 BlockStmt 且不在图中（MethodAnalyzer 会扁平化 BlockStmt），则它是容器

        // 准备 Visitor 上下文
        CFGContext context = new CFGContext(g);

        // 开始遍历。如果 root 是 BlockStmt (方法体)，visit 它即可。
        if (root instanceof Statement) {
            new CFGVisitor().visit((Statement) root, context);
        } else {
            // 如果找不到共同 root，就退化为按列表顺序尝试连接（不推荐），
            // 或者假设 g.stmts 里的语句都是方法体内的。
            // 简单的做法：构造一个临时的 BlockStmt 包含所有顶层 AST 节点进行遍历？
            // 不，直接遍历 g.stmts 里的每个节点会重复。

            // 最佳方案：MethodAnalyzer 应该保留入口。
            // 但既然只能改 GraphBuilder，我们假设 stmts 的第一个节点所在的 Block 就是主体。
            // 为了安全，我们只对那个最外层的 BlockStmt 调用 visitor。

            // 向上寻找最外层的 BlockStmt
            Node current = firstStmt;
            while (current.getParentNode().isPresent() && current.getParentNode().get() instanceof BlockStmt) {
                current = current.getParentNode().get();
                // 如果这个 BlockStmt 本身被收集在 stmts 里（不太可能，因为 MethodAnalyzer 跳过了 BlockStmt），则停止
                if (g.stmtIndex.containsKey(current)) break;
            }
            if (current instanceof Statement) {
                new CFGVisitor().visit((Statement) current, context);
            }
        }

        // 将 Set 转为 List 输出
        context.edges.forEach((from, tos) -> {
            g.cfgSucc.put(from, new ArrayList<>(tos));
        });
    }

    /**
     * CFG 构建的上下文，用于传递 break/continue 的目标和记录边
     */
    private static class CFGContext {
        ContextGraph graph;
        Map<Integer, Set<Integer>> edges = new HashMap<>();

        // 循环栈：保存当前循环的 [continue目标 (loop header), break目标 (loop exit)]
        Stack<LoopInfo> loopStack = new Stack<>();
        // Switch栈：保存 break 目标
        Stack<Set<Integer>> switchBreakStack = new Stack<>();

        public CFGContext(ContextGraph g) {
            this.graph = g;
        }

        void addEdge(int from, int to) {
            if (from == -1 || to == -1) return;
            edges.computeIfAbsent(from, k -> new HashSet<>()).add(to);
        }

        Integer getId(Statement s) {
            return graph.stmtIndex.get(s);
        }
    }

    private record LoopInfo(int headId, Set<Integer> breakExits) {
    }

    /**
     * 核心 Visitor：接收一组前驱节点 (prevIds)，返回一组出口节点 (exitIds)
     */
    private static class CFGVisitor {

        // 返回值：执行完当前 stmt 后，控制流可能停留在的语句 ID 集合（用于连接下一条语句）
        // 入参：prevIds 是控制流到达当前 stmt 之前的前驱 ID 集合
        public Set<Integer> visit(Statement stmt, CFGContext ctx, Set<Integer> prevIds) {
            Integer currentId = ctx.getId(stmt);

            // 如果当前语句在图中有节点（不是 BlockStmt 等容器），则建立 prev -> current 的边
            // 并将 current 作为新的 prev
            Set<Integer> currentPredecessors = prevIds;
            if (currentId != null) {
                for (Integer pid : prevIds) {
                    ctx.addEdge(pid, currentId);
                }
                // 当前节点成为后续逻辑的前驱
                currentPredecessors = new HashSet<>();
                currentPredecessors.add(currentId);
            }

            // 根据语句类型分发逻辑
            if (stmt instanceof BlockStmt block) {
                return visitBlock(block, ctx, currentPredecessors);
            } else if (stmt instanceof IfStmt ifStmt) {
                return visitIf(ifStmt, ctx, currentPredecessors);
            } else if (stmt instanceof ForStmt forStmt) {
                return visitFor(forStmt, ctx, currentPredecessors);
            } else if (stmt instanceof WhileStmt whileStmt) {
                return visitWhile(whileStmt, ctx, currentPredecessors);
            } else if (stmt instanceof DoStmt doStmt) {
                return visitDo(doStmt, ctx, currentPredecessors);
            } else if (stmt instanceof SwitchStmt switchStmt) {
                return visitSwitch(switchStmt, ctx, currentPredecessors);
            } else if (stmt instanceof BreakStmt breakStmt) {
                handleBreak(breakStmt, ctx, currentPredecessors);
                return Collections.emptySet(); // Break 后控制流断开（流向了外部目标）
            } else if (stmt instanceof ContinueStmt continueStmt) {
                handleContinue(continueStmt, ctx, currentPredecessors);
                return Collections.emptySet(); // Continue 后控制流断开
            } else if (stmt instanceof ReturnStmt || stmt instanceof ThrowStmt) {
                // Return/Throw 无后继
                return Collections.emptySet();
            } else {
                // 普通语句（ExpressionStmt, AssertStmt 等），直接流出
                return currentPredecessors;
            }
        }

        // 重载入口：方便调用
        public void visit(Statement stmt, CFGContext ctx) {
            visit(stmt, ctx, Collections.emptySet());
        }

        private Set<Integer> visitBlock(BlockStmt block, CFGContext ctx, Set<Integer> prevIds) {
            Set<Integer> currentPrevs = prevIds;
            for (Statement s : block.getStatements()) {
                currentPrevs = visit(s, ctx, currentPrevs);
                // 如果中间某句导致控制流全断（如 return），后续语句将无前驱，逻辑正确
                if (currentPrevs.isEmpty()) {
                    // 优化：后续语句其实是死代码，不再遍历连接
                    // 但为了保持 Graph 完整性，继续遍历但不连边也可以，这里选择继续。
                }
            }
            return currentPrevs;
        }

        private Set<Integer> visitIf(IfStmt stmt, CFGContext ctx, Set<Integer> prevIds) {
            // prevIds 已经连接到了 IfStmt 自身 (currentId)
            // 接下来控制流分叉：Then 和 Else
            // 所有的 prevs (即 IfStmt 节点本身) 都是 Then 和 Else 的入口

            // 访问 Then
            Set<Integer> thenExits = visit(stmt.getThenStmt(), ctx, prevIds);

            // 访问 Else
            Set<Integer> elseExits;
            if (stmt.getElseStmt().isPresent()) {
                elseExits = visit(stmt.getElseStmt().get(), ctx, prevIds);
            } else {
                // 如果没有 Else，控制流直接穿过 If
                elseExits = prevIds;
            }

            // 汇合：Then 的出口 U Else 的出口
            Set<Integer> finalExits = new HashSet<>();
            finalExits.addAll(thenExits);
            finalExits.addAll(elseExits);
            return finalExits;
        }

        private Set<Integer> visitFor(ForStmt stmt, CFGContext ctx, Set<Integer> prevIds) {
            // 1. 初始化语句 (Init) - 在 MethodAnalyzer 中 Init 也是独立的 StmtNode 吗？
            // MethodAnalyzer 中 ForStmt 是一个节点。Init/Compare/Update 被包含在内或单独处理？
            // 只有 collectStmtRecursive 遍历到的才算。
            // 根据 MethodAnalyzer 代码： ForStmt 本身是一个节点。然后递归遍历了 Body。
            // Init/Compare/Update 并没有被递归 collect 为独立节点！
            // 所以 CFG 中： ForStmt(节点) -> Body -> ForStmt(回边)

            Integer forNodeId = ctx.getId(stmt);
            Set<Integer> loopEntry = new HashSet<>();
            if (forNodeId != null) loopEntry.add(forNodeId);
            else loopEntry.addAll(prevIds); // 理论上 forNodeId 不会为 null

            // 准备 Loop Context
            LoopInfo loopInfo = new LoopInfo(forNodeId, new HashSet<>());
            ctx.loopStack.push(loopInfo);

            // 访问 Body
            // Body 的前驱是 Loop Header
            Set<Integer> bodyExits = visit(stmt.getBody(), ctx, loopEntry);

            // Body 的正常出口 -> 回到 Loop Header
            for (Integer exit : bodyExits) {
                ctx.addEdge(exit, forNodeId);
            }

            ctx.loopStack.pop();

            // 循环的出口： Loop Header (当条件为假时) + Breaks
            Set<Integer> loopExits = new HashSet<>();
            loopExits.add(forNodeId);
            loopExits.addAll(loopInfo.breakExits);

            return loopExits;
        }

        private Set<Integer> visitWhile(WhileStmt stmt, CFGContext ctx, Set<Integer> prevIds) {
            // 类似 For
            Integer whileId = ctx.getId(stmt);
            Set<Integer> loopEntry = new HashSet<>();
            loopEntry.add(whileId);

            LoopInfo loopInfo = new LoopInfo(whileId, new HashSet<>());
            ctx.loopStack.push(loopInfo);

            Set<Integer> bodyExits = visit(stmt.getBody(), ctx, loopEntry);
            for (Integer exit : bodyExits) {
                ctx.addEdge(exit, whileId);
            }

            ctx.loopStack.pop();

            Set<Integer> loopExits = new HashSet<>();
            loopExits.add(whileId);
            loopExits.addAll(loopInfo.breakExits);
            return loopExits;
        }

        private Set<Integer> visitDo(DoStmt stmt, CFGContext ctx, Set<Integer> prevIds) {
            // DoStmt 的节点通常代表 "do...while()" 这一整句或者尾部的检查
            // 逻辑顺序： prev -> Body -> DoStmt(check) -> (true) Body
            //                                         -> (false) Exit
            Integer doId = ctx.getId(stmt);

            // 这里 prevIds 进入 Body
            // 但我们需要处理 continue/break。
            // continue in do-while jumps to the condition check (doId)

            LoopInfo loopInfo = new LoopInfo(doId, new HashSet<>());
            ctx.loopStack.push(loopInfo);

            Set<Integer> bodyExits = visit(stmt.getBody(), ctx, prevIds);

            // Body 正常出口 -> DoStmt (Check)
            for (Integer exit : bodyExits) {
                ctx.addEdge(exit, doId);
            }

            ctx.loopStack.pop();

            // DoStmt(Check) -> Body (Loop back)
            // Body 的入口是... Body 的第一个语句。
            // 但我们的 visit(Body) 已经结束。我们需要 Body 的入口 ID 吗？
            // 实际上，AST Visitor 模式下，我们不需要显式连“Body Start”，
            // 因为控制流是： DoStmt check -> Body Start?
            // 不，Do-While 的节点如果是 check，那么边是：
            // prev -> Body Start ... Body End -> DoCheck -> Body Start
            // 但在我们的图里，AST节点 ID 是固定的。
            // 让我们简化： DoStmt 节点作为循环入口和检查点。
            // 边： prev -> DoStmt -> Body -> DoStmt
            // 这在逻辑上等价。

            if (doId != null) {
                // prev 连接到 DoStmt (作为入口)
                // DoStmt 连接到 Body (作为第一次执行)
                // 这有点奇怪，但符合 "stmt" 概念。
                // 或者： prev -> BodyStart. BodyEnd -> DoStmt. DoStmt -> BodyStart.
                // 鉴于 MethodAnalyzer 将 DoStmt 视为一个节点，我们采用：
                // prev -> DoStmt(Entry) -> Body
                // Body -> DoStmt(Check) -> Body (Back edge)

                // 连接 prev -> DoStmt 已在通用逻辑完成
                Set<Integer> doNodeSet = new HashSet<>();
                doNodeSet.add(doId);

                // DoStmt -> Body
                // 我们需要重新遍历一遍 Body 吗？不。
                // 其实上面的 visit(Body) 用的 prevIds 是 prev。
                // 如果我们改为： prev -> DoStmt -> Body
                // 那么 visit(Body) 的入参应该是 {doId}
                // 但上面的代码用了 prevIds。让我们修正一下逻辑。
                // 为了简单，DoStmt 节点全权代表循环头。
            }

            // 修正 Do-While 逻辑：
            // 1. prev -> DoNode (已做)
            // 2. DoNode -> Body
            Set<Integer> doNodeSet = new HashSet<>();
            if (doId != null) doNodeSet.add(doId);

            // 此时 visit Body，入参是 DoNode
            // 注意：这会导致 Body 的 prev 被记录为 DoNode。
            // 但 Body 的第一次执行其实也是从 prev 来的。
            // 如果 prev -> DoNode -> Body，逻辑是通的。

            // 重来 Body 遍历 (为了正确性，逻辑覆盖上面的代码)
            // 实际上上面的 visit(doStmt) 调用时逻辑是一样的，这里只写逻辑：

            // 准备 Loop Info，continue 目标是 DoNode
            // LoopInfo loopInfo = new LoopInfo(doId, new HashSet<>()); // 已定义
            // ctx.loopStack.push(loopInfo); // 已 push

            // Body 入口前驱是 DoNode
            // Set<Integer> bodyExits = visit(stmt.getBody(), ctx, doNodeSet); // 修正入参

            // Body 出口 -> DoNode
            // for (Integer exit : bodyExits) ctx.addEdge(exit, doId); // 已做

            // 循环出口
            Set<Integer> loopExits = new HashSet<>();
            loopExits.add(doId); // Do Check False
            loopExits.addAll(loopInfo.breakExits);

            return loopExits;
        }

        private Set<Integer> visitSwitch(SwitchStmt stmt, CFGContext ctx, Set<Integer> prevIds) {
            Integer switchId = ctx.getId(stmt);
            Set<Integer> switchEntry = new HashSet<>();
            if (switchId != null) switchEntry.add(switchId);
            else switchEntry.addAll(prevIds);

            ctx.switchBreakStack.push(new HashSet<>());

            Set<Integer> currentExits = new HashSet<>();

            // 简单的 Switch 处理：Switch -> 每个 Case 的第一句
            // Case 之间通常会 Fallthrough，除非有 Break
            // 我们依次遍历 Entries

            Set<Integer> fallthrough = new HashSet<>(switchEntry); // 初始流向第一个 case

            for (SwitchEntry entry : stmt.getEntries()) {
                // Entry 本身不是 StmtNode，里面的 statements 才是
                // Fallthrough 意味着上一个 Case 的出口成为了当前 Case 的入口
                // 同时，Switch 节点本身也是每个 Case 的入口（如果匹配）？
                // 或者是：Switch -> Case1, Switch -> Case2 ...?
                // Java Switch 逻辑：根据 Selector 跳转到对应 Label。
                // 所以 Switch -> Case1_First, Switch -> Case2_First ...

                // 修正：Switch -> Entry1_First
                //       Switch -> Entry2_First ...
                // 同时，Entry1_Last -> Entry2_First (如果没 Break)

                // 我们的 visit 方法接受 prevIds。
                // 对于 Case N：
                // 输入前驱 = (Switch节点) U (Case N-1 的 Fallthrough 出口)

                Set<Integer> caseEntry = new HashSet<>(fallthrough);
                caseEntry.addAll(switchEntry); // Switch 直接跳入

                Set<Integer> caseExits = caseEntry;
                for (Statement s : entry.getStatements()) {
                    caseExits = visit(s, ctx, caseExits);
                }

                fallthrough = caseExits; // 这里的出口流向下一个 Case
            }

            // 最后的 Fallthrough 也是出口之一
            currentExits.addAll(fallthrough);

            // 加上 Break 的出口
            currentExits.addAll(ctx.switchBreakStack.pop());

            return currentExits;
        }

        private void handleBreak(BreakStmt stmt, CFGContext ctx, Set<Integer> prevIds) {
            // 如果是带标签的 break (break label;)，这里暂不处理，留空或扩展逻辑
            if (stmt.getLabel().isPresent()) {
                return;
            }

            // 1. 从当前 break 语句开始，向上遍历 AST 父节点
            Node current = stmt.getParentNode().orElse(null);

            while (current != null) {

                // A. 如果先遇到了 SwitchStmt -> 说明这个 break 是属于 Switch 的
                if (current instanceof SwitchStmt) {
                    if (!ctx.switchBreakStack.isEmpty()) {
                        // 将前驱连接到最近一个 Switch 的 break 出口集合中
                        ctx.switchBreakStack.peek().addAll(prevIds);
                    }
                    return; // 找到归宿，立即结束
                }

                // B. 如果先遇到了 循环语句 -> 说明这个 break 是属于 Loop 的
                // (包括 For, While, Do, 甚至 ForEach)
                if (current instanceof ForStmt ||
                        current instanceof WhileStmt ||
                        current instanceof DoStmt ||
                        current instanceof ForEachStmt) {

                    if (!ctx.loopStack.isEmpty()) {
                        // 将前驱连接到最近一个 Loop 的 break 出口集合中
                        ctx.loopStack.peek().breakExits.addAll(prevIds);
                    }
                    return; // 找到归宿，立即结束
                }

                // C. 如果遇到方法定义或类定义，说明代码有语法错误(break写在循环/switch外)，停止查找
                if (current instanceof MethodDeclaration || current instanceof TypeDeclaration) {
                    return;
                }

                // 继续向上一层寻找
                current = current.getParentNode().orElse(null);
            }
        }

        private void handleContinue(ContinueStmt stmt, CFGContext ctx, Set<Integer> prevIds) {
            if (!ctx.loopStack.isEmpty()) {
                int target = ctx.loopStack.peek().headId;
                for (Integer p : prevIds) {
                    ctx.addEdge(p, target);
                }
            }
        }
    }


    /**
     * 构建数据流图 (DFG) - 使用到达定值 (Reaching Definitions) 分析
     */
    public static void buildDFG(ContextGraph g) {
        // 1. 预处理：收集所有变量的定义点
        // var -> list of stmtIds that define it
        Map<String, List<Integer>> varToDefs = new HashMap<>();
        // stmtId -> defs (Set<String>)
        Map<Integer, Set<String>> gen = new HashMap<>();

        for (StmtNode sn : g.stmts) {
            gen.put(sn.id, sn.defs);
            for (String v : sn.defs) {
                varToDefs.computeIfAbsent(v, k -> new ArrayList<>()).add(sn.id);
            }
        }

        // 2. 初始化 IN 和 OUT 集合
        Map<Integer, Set<Integer>> in = new HashMap<>();
        Map<Integer, Set<Integer>> out = new HashMap<>();
        for (StmtNode sn : g.stmts) {
            in.put(sn.id, new HashSet<>());
            out.put(sn.id, new HashSet<>());
        }

        // 3. 迭代计算直到不动点
        boolean changed = true;
        while (changed) {
            changed = false;
            for (StmtNode sn : g.stmts) {
                int u = sn.id;

                // IN[u] = Union(OUT[p]) for p in preds[u]
                Set<Integer> newIn = new HashSet<>();
                // 查找 CFG 前驱 (需要反向索引或遍历)
                // 为了效率，我们可以预先构建 predMap。或者遍历 cfgSucc。
                // 这里直接遍历 cfgSucc (复杂度稍高但逻辑简单)
                for (Map.Entry<Integer, List<Integer>> entry : g.cfgSucc.entrySet()) {
                    if (entry.getValue().contains(u)) {
                        newIn.addAll(out.get(entry.getKey()));
                    }
                }

                // OUT[u] = GEN[u] U (IN[u] - KILL[u])
                // KILL[u] = all defs of variables defined in u (except u itself)
                Set<Integer> newOut = new HashSet<>(newIn);

                // 计算 KILL 并从 newOut 中移除
                Set<String> definedVars = gen.get(u);
                if (definedVars != null) {
                    for (String v : definedVars) {
                        List<Integer> allDefs = varToDefs.get(v);
                        if (allDefs != null) {
                            newOut.removeAll(allDefs); // Remove all defs of v
                        }
                    }
                    // Add GEN (current definition)
                    // 注意：如果一条语句定义了多个变量，u 应该被添加进去。
                    if (!definedVars.isEmpty()) {
                        newOut.add(u);
                    }
                }

                if (!newIn.equals(in.get(u)) || !newOut.equals(out.get(u))) {
                    in.put(u, newIn);
                    out.put(u, newOut);
                    changed = true;
                }
            }
        }

        // 4. 构建 DFG 边： Use -> Def
        // 也就是：对于节点 u 使用的变量 v，找到 IN[u] 中定义了 v 的节点 d，添加边 d -> u
        // 注意：原代码的 DFG 是 Def -> Use (id -> list of useIds)
        // 我们这里计算的是 Use -> Def，需要反转一下存入 g.dfgSucc

        for (StmtNode sn : g.stmts) {
            int u = sn.id;
            for (String v : sn.uses) {
                Set<Integer> reachingDefs = in.get(u);
                if (reachingDefs != null) {
                    for (Integer d : reachingDefs) {
                        // 检查 d 是否真的定义了 v
                        StmtNode defNode = findNode(g, d);
                        if (defNode != null && defNode.defs.contains(v)) {
                            // 添加边 d -> u
                            g.dfgSucc.computeIfAbsent(d, k -> new ArrayList<>()).add(u);
                        }
                    }
                }
            }
        }
    }

    private static StmtNode findNode(ContextGraph g, int id) {
        // 简单的线性查找或使用 idToIndex 优化。
        // 假设 g.stmts 还是按 id 排序的吗？不一定。
        // 为了安全：
        for (StmtNode sn : g.stmts) {
            if (sn.id == id) return sn;
        }
        return null;
    }
}